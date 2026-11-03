/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.telegram.messenger.purple.PurpleDiagLogStore.code;
import static org.telegram.messenger.purple.PurpleDiagLogStore.count;
import static org.telegram.messenger.purple.PurpleDiagLogStore.reason;

/**
 * One account's Magic Purple Sync lookup: which chat this device syncs
 * through, found from the local chat lists, proven absent by one
 * messages.getAllChats, or created. purple-core makes every decision
 * ({@link PurpleSyncMagicCore#plan}); this class runs the requests it asks
 * for and keeps the binding file current.
 *
 * Every trigger (startup, screen, manual, auto, post) runs the same lookup,
 * one at a time; a trigger that arrives while one runs shares its result.
 * A recorded problem is answered without a request until a manual check
 * clears it.
 * Every request first honours the deadline the binding keeps for its method,
 * across restarts; a flood of up to 30 seconds is retried once in place, a
 * longer one or a network failure is recorded and the lookup answers Wait.
 * All state is touched only on the {@link Port#post} thread.
 */
final class PurpleSyncMagicChat {
    /** What started a lookup; {@code retry} is the timed wake-up after a wait, never a user's check. */
    enum Trigger { startup, screen, manual, auto, post, retry }

    /** A Telegram reply: a value, or a failure the gate classifies. */
    static final class Reply<T> {
        final T value;
        /** The TL error text, null on success or for a transport failure. */
        final String error;
        /** The TL error code; negative for transport failures and timeouts. */
        final int code;
        final boolean timeout;
        Reply(T value, String error, int code, boolean timeout) { this.value = value; this.error = error; this.code = code; this.timeout = timeout; }
        static <T> Reply<T> ok(T value) { return new Reply<>(value, null, 0, false); }
        static <T> Reply<T> failed(String error, int code) { return new Reply<>(null, error, code, false); }
        static <T> Reply<T> timedOut() { return new Reply<>(null, null, -504, true); }
    }

    /** The local lists: magic-titled chats, the bound chat's state and whether each list is complete. */
    static final class Local {
        final List<PurpleSyncMagicCore.Candidate> visible;
        final PurpleSyncMagicCore.BoundState boundState;
        final PurpleSyncMagicCore.Candidate bound;
        final boolean mainLoaded, archiveLoaded;
        Local(List<PurpleSyncMagicCore.Candidate> visible, PurpleSyncMagicCore.BoundState boundState, PurpleSyncMagicCore.Candidate bound,
              boolean mainLoaded, boolean archiveLoaded) {
            this.visible = visible; this.boundState = boundState; this.bound = bound; this.mainLoaded = mainLoaded; this.archiveLoaded = archiveLoaded;
        }
    }

    /** One messages.getAllChats page: its magic-titled megagroups, every chat id it named, and the total when it is a slice. */
    static final class Chats {
        final List<PurpleSyncMagicCore.Candidate> candidates;
        final List<Long> ids;
        /** The server's total for a messages.chatsSlice, or -1 for a complete reply. */
        final int count;
        Chats(List<PurpleSyncMagicCore.Candidate> candidates, List<Long> ids, int count) { this.candidates = candidates; this.ids = ids; this.count = count; }
    }

    interface Port {
        boolean current();
        /** Unix seconds. */
        long now();
        /** Runs on the lookup's own thread, after the current task. */
        void post(Runnable task);
        void delay(Runnable task, long millis);
        void cancelDelay(Runnable task);
        /** The binding text, "" when there is none, or null when it cannot be read. */
        String readBinding();
        /** Replaces {@code expected} with {@code next}; false when the file changed meanwhile or the write failed. */
        boolean writeBinding(String expected, String next);
        /** Reads the local lists for {@code bound} (may be null); replies on any thread. */
        void local(PurpleSyncChatCore.Peer bound, Consumer<Local> done);
        /**
         * Reads a bound channel the memory lacks from the local database, as the chat list would load it later;
         * its state is Unknown when the database has no row. Replies on any thread, null when the account changed.
         */
        void stored(long channelId, Consumer<Local> done);
        /** messages.getAllChats: every chat the user is in except {@code except}, any title. */
        void allChats(List<Long> except, Consumer<Reply<Chats>> done);
        /** channels.getFullChannel: the chat with exact member and admin counts. */
        void fullInfo(long channelId, Consumer<Reply<PurpleSyncMagicCore.Candidate>> done);
        /** channels.createChannel for a megagroup titled {@link PurpleSyncMagicCore#TITLE}; the new chat, from the reply's Updates. */
        void create(Consumer<Reply<PurpleSyncMagicCore.Candidate>> done);
        void archive(long channelId, Consumer<Reply<Boolean>> done);
        void mute(long channelId, Consumer<Reply<Boolean>> done);
        void diag(String event, PurpleDiagLogStore.Field... fields);
    }

    /** What a lookup decided. */
    static final class Result {
        enum Kind { Use, Wait, Problem, Unavailable }
        final Kind kind;
        final PurpleSyncChatCore.Peer chat;
        final PurpleSyncMagicCore.Problem problem;
        /** The problem has not been shown yet; the caller shows it and calls {@link #shown}. */
        final boolean show;
        /** Unix seconds when a Wait may end, 0 when unknown. */
        final long retryAt;
        final boolean manual;
        Result(Kind kind, PurpleSyncChatCore.Peer chat, PurpleSyncMagicCore.Problem problem, boolean show, long retryAt, boolean manual) {
            this.kind = kind; this.chat = chat; this.problem = problem; this.show = show; this.retryAt = retryAt; this.manual = manual;
        }
        static Result unavailable() { return new Result(Kind.Unavailable, null, PurpleSyncMagicCore.Problem.None, false, 0, false); }
    }

    static final long SHORT_FLOOD = 30;
    /** messages.getAllChats pages one lookup may read when the server slices the reply. */
    static final int ALL_CHATS_PAGES = 5;
    /** How long an incomplete list waits before it is read again. */
    static final long PARTIAL_WAIT = 60 * 60;
    /** The first wait after a refused request that is not a flood; it doubles up to a day. */
    static final long ERROR_WAIT = 10 * 60;
    private static final int EDIT_ATTEMPTS = 3;

    private final Port port;
    private final List<Consumer<Result>> waiters = new ArrayList<>();
    private final Set<String> retried = new HashSet<>();
    private volatile Result last = Result.unavailable();
    /** The stored binding as last read or written, for readers off the lookup thread. */
    private volatile PurpleSyncMagicCore.Binding binding;
    private boolean running, fresh, closed, created, resumed, stored, partial;
    private Trigger trigger;
    private List<PurpleSyncMagicCore.Candidate> allChats;
    private long generation;
    private int failures, errors;
    private Runnable wake;

    PurpleSyncMagicChat(Port port) { this.port = port; }

    Result last() { return last; }
    PurpleSyncMagicCore.Binding binding() { return binding; }

    /**
     * The magic chat Purple's chat lists hide: the chat a lookup bound in magic mode, unless the user chose to
     * see it. A chat the user picked is never hidden.
     */
    PurpleSyncChatCore.Peer hidden() {
        Result current = last;
        PurpleSyncMagicCore.Binding stored = binding;
        if (current.kind == Result.Kind.Unavailable || current.manual || current.chat == null || stored == null) return null;
        return stored.mode == PurpleSyncMagicCore.Mode.Magic && !stored.listed && current.chat.equals(stored.chat) ? current.chat : null;
    }

    /** The user picked {@code chat}; it is checked like any manual binding by a fresh lookup. */
    void choose(PurpleSyncChatCore.Peer chat, Consumer<Result> done) {
        userEdit("choose", binding -> chat == null ? null : PurpleSyncMagicCore.manual(PurpleSyncMagicCore.clear(binding), chat), done);
    }

    /** Forgets the user's pick or the bound chat, keeping waits and the list choice, and looks up the magic chat. */
    void useDefault(Consumer<Result> done) { userEdit("default", PurpleSyncMagicCore::reset, done); }

    /** The user set this device's own state aside to join the chat's records; the problem is cleared. */
    void joined(Consumer<Result> done) { userEdit("join", PurpleSyncMagicCore::clear, done); }

    /** Whether the magic chat shows in Purple's chat lists; {@code done} gets false when the binding could not be written. */
    void list(boolean listed, Consumer<Boolean> done) {
        port.post(() -> {
            boolean ok = !closed && port.current() && edit(binding -> PurpleSyncMagicCore.listed(binding, listed)) != null;
            if (ok) port.diag("listed", code("value", listed ? "on" : "off"));
            if (done != null) done.accept(ok);
        });
    }

    private void userEdit(String action, UnaryOperator<String> change, Consumer<Result> done) {
        port.post(() -> {
            if (closed || !port.current() || edit(change) == null) { if (done != null) done.accept(Result.unavailable()); return; }
            port.diag("user_action", code("action", action));
            if (wake != null) { port.cancelDelay(wake); wake = null; }
            // WHY: a lookup still running read the old binding; its replies are dropped and its waiters get the new result.
            ++generation; running = false;
            lookup(Trigger.manual, true, done);
        });
    }

    /** Runs a lookup for {@code trigger}, or joins the one running; {@code done} gets the result on the lookup thread. */
    void lookup(Trigger trigger, boolean fresh, Consumer<Result> done) {
        port.post(() -> {
            if (closed || !port.current()) { if (done != null) done.accept(Result.unavailable()); return; }
            if (done != null) waiters.add(done);
            this.fresh |= fresh;
            if (running) return;
            running = true; created = false; resumed = false; stored = false; partial = false; allChats = null; retried.clear(); this.trigger = trigger;
            long run = ++generation;
            port.diag("lookup_start", code("trigger", trigger.name()));
            step(run);
        });
    }

    /** The user saw the current problem; it is not shown again until it changes. */
    void shown() {
        port.post(() -> {
            Result current = last;
            if (closed || current.kind != Result.Kind.Problem) return;
            if (edit(PurpleSyncMagicCore::shown) != null) {
                port.diag("problem_shown", reason(current.problem.code()));
                last = new Result(current.kind, current.chat, current.problem, false, current.retryAt, current.manual);
            }
        });
    }

    /**
     * Records a problem sync found inside the bound chat (records of another sync space only). Like a lookup
     * problem it pauses automatic sync until the user acts, and it is shown once.
     */
    void raise(PurpleSyncMagicCore.Problem problem) {
        port.post(() -> {
            Result current = last;
            if (closed || current.kind != Result.Kind.Use || problem == PurpleSyncMagicCore.Problem.None) return;
            String next = edit(binding -> PurpleSyncMagicCore.raise(binding, problem));
            PurpleSyncMagicCore.Binding raised = next != null ? PurpleSyncMagicCore.parse(next) : null;
            if (raised == null) return;
            port.diag("auto_paused", reason(problem.code()));
            last = new Result(Result.Kind.Problem, current.chat, problem, !raised.problemShown, 0, current.manual);
        });
    }

    /** The unix time before which any of {@code methods} must not be sent, kept in the binding across restarts; 0 for none. */
    long waitUntil(String... methods) {
        String text = port.readBinding();
        if (text == null || text.isEmpty()) return 0;
        long now = port.now(), until = 0;
        for (String method : methods) until = Math.max(until, PurpleSyncMagicCore.waitUntil(text, method, now));
        return until;
    }

    /** Keeps a flood wait a sync request got outside the lookup (record reads, posts, edits, deletions). */
    void recordWait(String method, long retryAt) {
        port.post(() -> {
            if (closed || !port.current() || edit(binding -> PurpleSyncMagicCore.recordWait(binding, method, retryAt)) == null) return;
            port.diag("wait_recorded", code("method", method.toLowerCase(Locale.ROOT)),
                count("seconds", (int) Math.max(0, Math.min(Integer.MAX_VALUE, retryAt - port.now()))));
        });
    }

    void close() {
        port.post(() -> {
            closed = true; ++generation;
            if (wake != null) { port.cancelDelay(wake); wake = null; }
            finish(generation, Result.unavailable());
        });
    }

    private boolean live(long run) { return !closed && run == generation && port.current(); }

    private void step(long run) {
        if (!live(run)) { finish(run, Result.unavailable()); return; }
        String binding = port.readBinding();
        PurpleSyncMagicCore.Binding parsed = binding != null ? PurpleSyncMagicCore.parse(binding) : null;
        if (parsed == null) { unavailable(run, "binding_unreadable"); return; }
        this.binding = parsed;
        if (trigger == Trigger.manual && !resumed) {
            // WHY: core keeps a problem and skips rejected chats until the user acts; a manual check is that act.
            resumed = true;
            boolean paused = parsed.problem != PurpleSyncMagicCore.Problem.None;
            if (edit(current -> {
                String readmitted = PurpleSyncMagicCore.readmit(current);
                return paused && readmitted != null ? PurpleSyncMagicCore.clear(readmitted) : readmitted;
            }) == null) { unavailable(run, "binding_unreadable"); return; }
            if (paused) port.diag("auto_resumed", reason("user_check"));
            step(run);
            return;
        }
        port.local(parsed.chat, local -> port.post(() -> planned(run, binding, parsed, local)));
    }

    private void planned(long run, String binding, PurpleSyncMagicCore.Binding parsed, Local local) {
        if (!live(run)) { finish(run, Result.unavailable()); return; }
        if (local == null) { unavailable(run, "lists_unreadable"); return; }
        port.diag("lists", code("main", local.mainLoaded ? "loaded" : "partial"), code("archive", local.archiveLoaded ? "loaded" : "partial"));
        PurpleSyncMagicCore.LookupInput input = new PurpleSyncMagicCore.LookupInput();
        input.binding = binding; input.boundState = local.boundState; input.bound = local.bound;
        input.visible = local.visible != null ? local.visible : Collections.emptyList();
        input.fresh = fresh; input.now = port.now();
        if (local.boundState == PurpleSyncMagicCore.BoundState.Unknown && parsed.chat != null
                && parsed.chat.kind == PurpleSyncChatCore.PeerKind.Channel) {
            // WHY: after a cold start the archived chat is usually only in the database; only a chat missing
            // there too is either still loading or gone, and the one getAllChats reply settles which.
            if (!stored) {
                stored = true;
                port.stored(parsed.chat.id, found -> port.post(() -> planned(run, binding, parsed, found == null ? null
                    : new Local(local.visible, found.boundState, found.bound, local.mainLoaded, local.archiveLoaded))));
                return;
            }
            if (allChats == null) { proveAbsent(run, binding); return; }
            input.bound = find(allChats, parsed.chat.id);
            if (input.bound == null && partial) { listPartial(run); return; }
            input.boundState = input.bound != null ? PurpleSyncMagicCore.BoundState.Present : PurpleSyncMagicCore.BoundState.Gone;
        }
        if (allChats != null) input.allChats = titled(allChats);
        PurpleSyncMagicCore.Plan plan = PurpleSyncMagicCore.plan(input);
        switch (plan.action) {
            case Use:
                done(run, Result.Kind.Use, plan.chat, PurpleSyncMagicCore.Problem.None, false, 0, parsed.mode == PurpleSyncMagicCore.Mode.Manual,
                    created ? "created" : "bound", input);
                return;
            case Verify: verify(run, binding, plan, input); return;
            case Wait: waiting(run, plan.retryAt, input); return;
            case ProveAbsent: proveAbsent(run, binding); return;
            case Create: create(run, binding); return;
            case Problem: problem(run, plan.problem, plan.show, parsed, input); return;
            default: unavailable(run, "native_unavailable");
        }
    }

    private void proveAbsent(long run, String binding) {
        page(run, binding, new ArrayList<>(), new ArrayList<>(), 1);
    }

    /**
     * Reads messages.getAllChats until the reply is complete. A slice is paged with except_ids; only a complete
     * list may prove that no magic chat exists, so one still partial after {@link #ALL_CHATS_PAGES} pages is used
     * only for the candidates it holds, or else waits.
     */
    private void page(long run, String binding, List<Long> seen, List<PurpleSyncMagicCore.Candidate> found, int pages) {
        this.<Chats>gated(run, binding, PurpleSyncMagicCore.ALL_CHATS, done -> port.allChats(new ArrayList<>(seen), done), chats -> {
            if (chats != null) {
                found.addAll(chats.candidates);
                for (Long id : chats.ids) if (id != null && !seen.contains(id)) seen.add(id);
            }
            partial = chats != null && chats.count >= 0 && seen.size() < chats.count && !chats.ids.isEmpty();
            if (partial && pages < ALL_CHATS_PAGES) { page(run, binding, seen, found, pages + 1); return; }
            if (partial && titled(found).isEmpty()) { listPartial(run); return; }
            allChats = found;
            step(run);
        }, failure -> backOff(run, PurpleSyncMagicCore.ALL_CHATS));
    }

    /** An incomplete list proves nothing absent: wait, and read it again later. */
    private void listPartial(long run) {
        port.diag("lookup_result", code("result", "absent_waiting"), reason("list_partial"));
        hold(run, PurpleSyncMagicCore.ALL_CHATS, port.now() + PARTIAL_WAIT, null);
    }

    private void verify(long run, String binding, PurpleSyncMagicCore.Plan plan, PurpleSyncMagicCore.LookupInput input) {
        if (plan.chat == null || plan.chat.kind != PurpleSyncChatCore.PeerKind.Channel) { unavailable(run, "native_unavailable"); return; }
        long id = plan.chat.id;
        this.<PurpleSyncMagicCore.Candidate>gated(run, binding, PurpleSyncMagicCore.FULL_INFO, done -> port.fullInfo(id, done), chat -> {
            if (chat == null || chat.channelId != id) { unavailable(run, "rpc"); return; }
            PurpleSyncMagicCore.Problem[] problem = { PurpleSyncMagicCore.Problem.None };
            boolean[] rejected = { false };
            String next = edit(current -> {
                PurpleSyncMagicCore.Verified verified = PurpleSyncMagicCore.verify(current, chat, port.now());
                if (verified == null) return null;
                problem[0] = verified.problem; rejected[0] = verified.rejected;
                return verified.binding;
            });
            if (next == null) { unavailable(run, "binding_unreadable"); return; }
            if (rejected[0]) {
                port.diag("rejected", reason(problem[0].code()));
                step(run);
                return;
            }
            if (problem[0] != PurpleSyncMagicCore.Problem.None) {
                PurpleSyncMagicCore.Binding parsed = PurpleSyncMagicCore.parse(next);
                problem(run, problem[0], parsed != null && !parsed.problemShown, parsed, input);
                return;
            }
            if (plan.rebind && !created) port.diag("lookup_result", code("result", input.binding.isEmpty() ? "found" : "rebound"),
                count("count", candidates(input)));
            step(run);
        }, failure -> backOff(run, PurpleSyncMagicCore.FULL_INFO));
    }

    private void create(long run, String binding) {
        if (created) { unavailable(run, "create_uncertain"); return; }
        long started = port.now();
        if (edit(current -> PurpleSyncMagicCore.createStep(current, PurpleSyncMagicCore.CreateStep.Requested, started, null)) == null) {
            unavailable(run, "binding_unreadable"); return;
        }
        gated(run, binding, PurpleSyncMagicCore.CREATE, port::create, chosen -> {
            if (chosen == null || chosen.channelId <= 0) { uncertain(run, started); return; }
            long id = chosen.channelId;
            created = true;
            // WHY: the new chat may reach the dialog list later than this reply.
            List<PurpleSyncMagicCore.Candidate> known = new ArrayList<>(allChats != null ? allChats : Collections.emptyList());
            known.add(chosen);
            allChats = known;
            PurpleSyncChatCore.Peer chat = PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, id);
            edit(current -> PurpleSyncMagicCore.createStep(current, PurpleSyncMagicCore.CreateStep.Created, started, chat));
            cosmetic(run, PurpleSyncMagicCore.EDIT_FOLDERS, done -> port.archive(id, done), PurpleSyncMagicCore.CreateStep.Archived, started, chat, () ->
                cosmetic(run, PurpleSyncMagicCore.NOTIFY, done -> port.mute(id, done), PurpleSyncMagicCore.CreateStep.Muted, started, chat, () -> step(run)));
        }, failure -> {
            // WHY: an error reply means nothing was created, so the next lookup may try again.
            notCreated();
            if (failure.error != null && (failure.error.contains("TOO_MUCH") || failure.error.startsWith("USER_RESTRICTED"))) {
                PurpleSyncMagicCore.Binding parsed = PurpleSyncMagicCore.parse(port.readBinding());
                problem(run, PurpleSyncMagicCore.Problem.CreateLimit, true, parsed, null);
            } else backOff(run, PurpleSyncMagicCore.CREATE);
        }, () -> uncertain(run, started), this::notCreated);
    }

    private void notCreated() {
        edit(current -> PurpleSyncMagicCore.createStep(current, PurpleSyncMagicCore.CreateStep.None, 0, null));
    }

    /** WHY: a lost reply may still have created the chat, so no second creation for a day. */
    private void uncertain(long run, long started) {
        edit(current -> PurpleSyncMagicCore.createStep(current, PurpleSyncMagicCore.CreateStep.Uncertain, started, null));
        port.diag("lookup_result", code("result", "absent_waiting"), reason("create_uncertain"));
        finish(run, new Result(Result.Kind.Wait, null, PurpleSyncMagicCore.Problem.None, false, started + 24 * 60 * 60, false));
    }

    /** Archive and mute are cosmetic: a failure is logged and the lookup goes on. */
    private void cosmetic(long run, String method, Call<Boolean> call, PurpleSyncMagicCore.CreateStep step, long started,
                          PurpleSyncChatCore.Peer chat, Runnable next) {
        String binding = port.readBinding();
        if (binding == null) { next.run(); return; }
        gatedQuiet(run, binding, method, call, ok -> {
            if (Boolean.TRUE.equals(ok)) edit(current -> PurpleSyncMagicCore.createStep(current, step, started, chat));
            next.run();
        }, next);
    }

    private void problem(long run, PurpleSyncMagicCore.Problem problem, boolean show, PurpleSyncMagicCore.Binding parsed,
                         PurpleSyncMagicCore.LookupInput input) {
        String next = edit(current -> PurpleSyncMagicCore.raise(current, problem));
        PurpleSyncMagicCore.Binding raised = next != null ? PurpleSyncMagicCore.parse(next) : parsed;
        boolean unseen = raised != null ? !raised.problemShown : show;
        port.diag("lookup_result", code("result", "ineligible"), reason(problem.code()), count("count", input != null ? candidates(input) : 0));
        finish(run, new Result(Result.Kind.Problem, raised != null ? raised.chat : null, problem, unseen, 0,
            raised != null && raised.mode == PurpleSyncMagicCore.Mode.Manual));
    }

    private void waiting(long run, long retryAt, PurpleSyncMagicCore.LookupInput input) {
        port.diag("lookup_result", code("result", "absent_waiting"), count("count", candidates(input)));
        finish(run, new Result(Result.Kind.Wait, null, PurpleSyncMagicCore.Problem.None, false, retryAt, false));
    }

    private void done(long run, Result.Kind kind, PurpleSyncChatCore.Peer chat, PurpleSyncMagicCore.Problem problem, boolean show, long retryAt,
                      boolean manual, String result, PurpleSyncMagicCore.LookupInput input) {
        port.diag("lookup_result", code("result", result), count("count", candidates(input)));
        failures = 0; errors = 0;
        finish(run, new Result(kind, chat, problem, show, retryAt, manual));
    }

    private void unavailable(long run, String why) {
        port.diag("lookup_result", code("result", "absent_waiting"), reason(why));
        finish(run, Result.unavailable());
    }

    private void finish(long run, Result result) {
        if (run != generation && !closed) return;
        running = false; fresh = false; allChats = null;
        if (result.kind != Result.Kind.Unavailable || closed) last = result;
        List<Consumer<Result>> done = new ArrayList<>(waiters); waiters.clear();
        for (Consumer<Result> waiter : done) {
            try { waiter.accept(result); } catch (RuntimeException | LinkageError ignored) { }
        }
        if (!closed && result.kind == Result.Kind.Wait && result.retryAt > 0) schedule(result.retryAt);
    }

    /** WHY: one wake-up per account, so an unattended device still finds its chat after a wait. */
    private void schedule(long retryAt) {
        if (wake != null) port.cancelDelay(wake);
        Runnable task = () -> { wake = null; lookup(Trigger.retry, false, null); };
        wake = task;
        port.delay(task, Math.max(1000, (retryAt - port.now()) * 1000));
    }

    interface Call<T> { void start(Consumer<Reply<T>> done); }

    private <T> void gated(long run, String binding, String method, Call<T> call, Consumer<T> ok, Consumer<Reply<T>> failed) {
        gated(run, binding, method, call, ok, failed, null, null);
    }

    /**
     * Sends {@code call} unless the binding holds a deadline for {@code method}.
     * A flood records one (after {@code beforeFlood}) and finishes the lookup as
     * Wait; a network failure or timeout does the same, or runs
     * {@code network} instead when given.
     */
    private <T> void gated(long run, String binding, String method, Call<T> call, Consumer<T> ok, Consumer<Reply<T>> failed,
                           Runnable network, Runnable beforeFlood) {
        long now = port.now();
        long until = PurpleSyncMagicCore.waitUntil(binding, method, now);
        String name = method.toLowerCase(Locale.ROOT);
        if (until < 0) { unavailable(run, "binding_unreadable"); return; }
        if (until > 0) {
            port.diag("wait_honored", code("method", name), count("seconds", (int) Math.min(Integer.MAX_VALUE, until - now)));
            finish(run, new Result(Result.Kind.Wait, null, PurpleSyncMagicCore.Problem.None, false, until, false));
            return;
        }
        call.start(reply -> port.post(() -> {
            if (!live(run)) { finish(run, Result.unavailable()); return; }
            Long flood = reply.value == null ? PurpleRpcRetry.floodSeconds(reply.error) : null;
            if (reply.error == null && !reply.timeout && reply.code == 0) {
                port.diag("request", code("method", name), code("outcome", "ok"));
                ok.accept(reply.value);
            } else if (flood != null) {
                port.diag("request", code("method", name), code("outcome", "flood"), count("seconds", (int) Math.min(Integer.MAX_VALUE, flood)));
                if (flood <= SHORT_FLOOD && retried.add(method)) {
                    port.delay(() -> port.post(() -> { if (live(run)) gated(run, binding, method, call, ok, failed, network, beforeFlood); else finish(run, Result.unavailable()); }),
                        flood * 1000 + 1000);
                    return;
                }
                if (beforeFlood != null) beforeFlood.run();
                hold(run, method, now + flood + 1, null);
            } else if (reply.timeout || reply.code < 0 || reply.code >= 500 && reply.code < 600) {
                port.diag("request", code("method", name), code("outcome", reply.timeout ? "timeout" : "network"), count("code", Math.abs(reply.code)));
                hold(run, method, now + Math.min(5L << Math.min(failures++, 4), 60), network);
            } else {
                port.diag("request", code("method", name), code("outcome", "error"), count("code", reply.code));
                failed.accept(reply);
            }
        }));
    }

    /** A refused request that is not a flood (PEER_FLOOD, an unexpected 400) is not sent again on every lookup. */
    private void backOff(long run, String method) {
        long seconds = Math.min(ERROR_WAIT << Math.min(errors++, 8), 24 * 60 * 60);
        port.diag("lookup_result", code("result", "absent_waiting"), reason("rpc"));
        hold(run, method, port.now() + seconds, null);
    }

    private void hold(long run, String method, long retryAt, Runnable instead) {
        edit(current -> PurpleSyncMagicCore.recordWait(current, method, retryAt));
        if (instead != null) { instead.run(); return; }
        finish(run, new Result(Result.Kind.Wait, null, PurpleSyncMagicCore.Problem.None, false, retryAt, false));
    }

    /** A cosmetic request: its own deadline is honoured and recorded, but any failure only skips it. */
    private <T> void gatedQuiet(long run, String binding, String method, Call<T> call, Consumer<T> ok, Runnable skipped) {
        long now = port.now();
        long until = PurpleSyncMagicCore.waitUntil(binding, method, now);
        String name = method.toLowerCase(Locale.ROOT);
        if (until != 0) {
            if (until > 0) port.diag("wait_honored", code("method", name), count("seconds", (int) Math.min(Integer.MAX_VALUE, until - now)));
            skipped.run(); return;
        }
        call.start(reply -> port.post(() -> {
            if (!live(run)) { finish(run, Result.unavailable()); return; }
            Long flood = reply.value == null ? PurpleRpcRetry.floodSeconds(reply.error) : null;
            boolean ok2 = reply.error == null && !reply.timeout && reply.code == 0;
            port.diag("request", code("method", name), code("outcome", ok2 ? "ok" : flood != null ? "flood" : reply.timeout ? "timeout" : reply.code < 0 ? "network" : "error"),
                flood != null ? count("seconds", (int) Math.min(Integer.MAX_VALUE, flood)) : count("code", Math.abs(reply.code)));
            if (flood != null) edit(current -> PurpleSyncMagicCore.recordWait(current, method, now + flood + 1));
            if (ok2) ok.accept(reply.value); else skipped.run();
        }));
    }

    /** Applies {@code change} to the stored binding by compare-and-set; the new text, or null. */
    private String edit(UnaryOperator<String> change) {
        for (int i = 0; i < EDIT_ATTEMPTS; ++i) {
            String current = port.readBinding();
            if (current == null) return null;
            String next = change.apply(current);
            if (next == null) return null;
            if (next.equals(current) || port.writeBinding(current, next)) {
                PurpleSyncMagicCore.Binding parsed = PurpleSyncMagicCore.parse(next);
                if (parsed != null) binding = parsed;
                return next;
            }
        }
        return null;
    }

    private static PurpleSyncMagicCore.Candidate find(List<PurpleSyncMagicCore.Candidate> chats, long id) {
        for (PurpleSyncMagicCore.Candidate chat : chats) if (chat != null && chat.channelId == id) return chat;
        return null;
    }

    private static List<PurpleSyncMagicCore.Candidate> titled(List<PurpleSyncMagicCore.Candidate> chats) {
        List<PurpleSyncMagicCore.Candidate> result = new ArrayList<>();
        for (PurpleSyncMagicCore.Candidate chat : chats) if (chat != null && PurpleSyncMagicCore.TITLE.equals(chat.title)) result.add(chat);
        return result;
    }

    private static int candidates(PurpleSyncMagicCore.LookupInput input) {
        return input.visible.size() + (input.allChats != null ? input.allChats.size() : 0);
    }
}
