/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.telegram.messenger.purple.PurpleDiagLogStore.code;
import static org.telegram.messenger.purple.PurpleDiagLogStore.count;
import static org.telegram.messenger.purple.PurpleDiagLogStore.reason;

/**
 * One account's Magic Purple Sync lookup: which chat this device syncs
 * through, found in the local chat lists, or created once those lists are
 * fully loaded without one. purple-core makes every decision
 * ({@link PurpleSyncMagicCore#plan}); this class runs the requests it asks
 * for, asks upstream's dialog loader for the next list page while the lists
 * are incomplete, and keeps the binding file current.
 *
 * Every trigger (startup, screen, manual, auto, post) runs the same lookup,
 * one at a time; a trigger that arrives while one runs shares its result.
 * A recorded problem is answered without a request until a manual check
 * clears it.
 * Every request first honours the deadline the binding keeps for its method,
 * across restarts. Core classifies each failure: a flood of up to 30 seconds
 * is retried once in place and a longer one is kept in the binding (a
 * Telegram wait); a timeout or network failure backs off in memory only; a
 * refusal raises its problem.
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
        /**
         * Asks upstream's own dialog loader for the next page of the first list not fully loaded (main, then
         * archive), as scrolling the chat list would; {@code changed} runs once, on any thread, when that page has
         * landed or nothing could be asked. Never a request of its own.
         */
        void more(Runnable changed);
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
        /** A Wait Telegram asked for (a flood wait); any other wait is Purple's own and is not worded as Telegram's. */
        final boolean telegram;
        /** A Wait because the chat lists are still loading: the lookup is still looking. */
        final boolean loading;
        Result(Kind kind, PurpleSyncChatCore.Peer chat, PurpleSyncMagicCore.Problem problem, boolean show, long retryAt, boolean manual,
               boolean telegram, boolean loading) {
            this.kind = kind; this.chat = chat; this.problem = problem; this.show = show; this.retryAt = retryAt; this.manual = manual;
            this.telegram = telegram; this.loading = loading;
        }
        static Result unavailable() { return new Result(Kind.Unavailable, null, PurpleSyncMagicCore.Problem.None, false, 0, false, false, false); }
        static Result waiting(long retryAt, boolean telegram, boolean loading) {
            return new Result(Kind.Wait, null, PurpleSyncMagicCore.Problem.None, false, retryAt, false, telegram, loading);
        }
    }

    static final long SHORT_FLOOD = 30;
    /** How long one lookup lets upstream load list pages before it answers Wait; it never creates meanwhile. */
    static final long LISTS_CAP = 60;
    /** When a lookup whose lists are still loading after {@link #LISTS_CAP} looks again. */
    static final long LISTS_RETRY = 60;
    /** The longest in-memory backoff after a timeout or network failure (5, 10, 20, 40, then 60 seconds). */
    static final long TRANSIENT_MAX = 60;
    private static final int EDIT_ATTEMPTS = 3;

    private final Port port;
    private final List<Consumer<Result>> waiters = new ArrayList<>();
    private final Set<String> retried = new HashSet<>();
    private volatile Result last = Result.unavailable();
    /** The stored binding as last read or written, for readers off the lookup thread. */
    private volatile PurpleSyncMagicCore.Binding binding;
    /** Deadlines after a timeout or network failure, per method; in memory only, never in the binding. */
    private final Map<String, Long> transientUntil = new HashMap<>();
    private boolean running, fresh, closed, created, resumed, stored;
    private Trigger trigger;
    /** The chat this lookup created: known to the next plans though the lists may show it only later. */
    private PurpleSyncMagicCore.Candidate createdChat;
    /** When this lookup first waited for the lists, 0 before. */
    private long listsSince;
    private long generation;
    private int failures;
    private Runnable wake;
    private ListsPage listsPage;

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
            running = true; created = false; resumed = false; stored = false; createdChat = null; listsSince = 0; retried.clear(); this.trigger = trigger;
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
                last = new Result(current.kind, current.chat, current.problem, false, current.retryAt, current.manual, current.telegram, current.loading);
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
            last = new Result(Result.Kind.Problem, current.chat, problem, !raised.problemShown, 0, current.manual, false, false);
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
        input.visible = withCreated(local.visible != null ? local.visible : Collections.emptyList());
        input.listsComplete = local.mainLoaded && local.archiveLoaded;
        input.fresh = fresh; input.now = port.now();
        if (local.boundState == PurpleSyncMagicCore.BoundState.Unknown && parsed.chat != null
                && parsed.chat.kind == PurpleSyncChatCore.PeerKind.Channel) {
            if (createdChat != null && createdChat.channelId == parsed.chat.id) {
                // WHY: the chat this lookup created may reach the dialog list later than its reply.
                input.boundState = PurpleSyncMagicCore.BoundState.Present; input.bound = createdChat;
            } else if (!stored) {
                // WHY: after a cold start the archived chat is usually only in the database; a chat missing there
                // too is either still loading or gone, and only the fully loaded lists tell which.
                stored = true;
                port.stored(parsed.chat.id, found -> port.post(() -> planned(run, binding, parsed, found == null ? null
                    : new Local(local.visible, found.boundState, found.bound, local.mainLoaded, local.archiveLoaded))));
                return;
            }
        }
        PurpleSyncMagicCore.Plan plan = PurpleSyncMagicCore.plan(input);
        switch (plan.action) {
            case Use:
                done(run, Result.Kind.Use, plan.chat, PurpleSyncMagicCore.Problem.None, false, 0, parsed.mode == PurpleSyncMagicCore.Mode.Manual,
                    created ? "created" : "bound", input);
                return;
            case Verify: verify(run, binding, plan, input); return;
            case Wait:
                if (plan.retryAt == 0 && !input.listsComplete) { loadLists(run, input); return; }
                waiting(run, plan.retryAt, plan.telegram, input);
                return;
            case Create: create(run, binding); return;
            case Problem: problem(run, plan.problem, plan.show, parsed, input); return;
            default: unavailable(run, "native_unavailable");
        }
    }

    /**
     * The lists are still loading, so nothing is proven absent yet: upstream's loader is asked for the next page,
     * and the lookup plans again once it lands, one page at a time. After {@link #LISTS_CAP} seconds the lookup
     * answers a wait of its own and looks again later; it never creates from incomplete lists.
     */
    private void loadLists(long run, PurpleSyncMagicCore.LookupInput input) {
        long now = port.now();
        if (listsSince == 0) listsSince = now;
        if (now - listsSince >= LISTS_CAP) {
            port.diag("lookup_result", code("result", "absent_waiting"), reason("lists_loading"), count("count", input.visible.size()));
            finish(run, Result.waiting(now + LISTS_RETRY, false, true));
            return;
        }
        ListsPage page = new ListsPage(run);
        listsPage = page;
        port.delay(page, Math.max(1, listsSince + LISTS_CAP - now) * 1000);
        port.more(() -> port.post(page));
    }

    /**
     * One page asked of upstream. Whichever comes first, the page or the cap, plans again; the other finds the page
     * no longer current and does nothing, as does a page of a lookup that has finished.
     */
    private final class ListsPage implements Runnable {
        private final long run;
        ListsPage(long run) { this.run = run; }
        @Override public void run() {
            if (listsPage != this) return;
            listsPage = null;
            port.cancelDelay(this);
            if (live(run)) step(run); else finish(run, Result.unavailable());
        }
    }

    private void verify(long run, String binding, PurpleSyncMagicCore.Plan plan, PurpleSyncMagicCore.LookupInput input) {
        if (plan.chat == null || plan.chat.kind != PurpleSyncChatCore.PeerKind.Channel) { unavailable(run, "native_unavailable"); return; }
        long id = plan.chat.id;
        this.<PurpleSyncMagicCore.Candidate>gated(run, binding, PurpleSyncMagicCore.FULL_INFO, done -> port.fullInfo(id, done), chat -> {
            if (chat == null || chat.channelId != id) { unavailable(run, "rpc"); return; }
            verified(run, plan, input, current -> PurpleSyncMagicCore.verify(current, chat, port.now()));
        }, failure -> {
            // WHY: a refused read of the bound chat pauses sync with its problem; any other candidate is only skipped.
            PurpleSyncMagicCore.Problem problem = PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.FULL_INFO, failure.error);
            verified(run, plan, input, current -> PurpleSyncMagicCore.verifyProblem(current, id, problem, port.now()));
        });
    }

    /** Stores a verify's outcome: a rejected candidate steps again, a problem of the bound chat is raised. */
    private void verified(long run, PurpleSyncMagicCore.Plan plan, PurpleSyncMagicCore.LookupInput input,
                          Function<String, PurpleSyncMagicCore.Verified> outcome) {
        PurpleSyncMagicCore.Problem[] problem = { PurpleSyncMagicCore.Problem.None };
        boolean[] rejected = { false };
        String next = edit(current -> {
            PurpleSyncMagicCore.Verified verified = outcome.apply(current);
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
            createdChat = chosen;
            PurpleSyncChatCore.Peer chat = PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, id);
            edit(current -> PurpleSyncMagicCore.createStep(current, PurpleSyncMagicCore.CreateStep.Created, started, chat));
            cosmetic(run, PurpleSyncMagicCore.EDIT_FOLDERS, done -> port.archive(id, done), PurpleSyncMagicCore.CreateStep.Archived, started, chat, () ->
                cosmetic(run, PurpleSyncMagicCore.NOTIFY, done -> port.mute(id, done), PurpleSyncMagicCore.CreateStep.Muted, started, chat, () -> step(run)));
        }, failure -> {
            // WHY: a refusal means nothing was created; its problem pauses sync until the user checks again.
            notCreated();
            PurpleSyncMagicCore.Problem problem = PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.CREATE, failure.error);
            problem(run, problem, true, PurpleSyncMagicCore.parse(port.readBinding()), null);
        }, () -> uncertain(run, started), this::notCreated);
    }

    private void notCreated() {
        edit(current -> PurpleSyncMagicCore.createStep(current, PurpleSyncMagicCore.CreateStep.None, 0, null));
    }

    /** WHY: a lost reply may still have created the chat, so no second creation for a day. */
    private void uncertain(long run, long started) {
        edit(current -> PurpleSyncMagicCore.createStep(current, PurpleSyncMagicCore.CreateStep.Uncertain, started, null));
        port.diag("lookup_result", code("result", "absent_waiting"), reason("create_uncertain"));
        finish(run, Result.waiting(started + 24 * 60 * 60, false, false));
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
            raised != null && raised.mode == PurpleSyncMagicCore.Mode.Manual, false, false));
    }

    private void waiting(long run, long retryAt, boolean telegram, PurpleSyncMagicCore.LookupInput input) {
        port.diag("lookup_result", code("result", "absent_waiting"), count("count", candidates(input)));
        finish(run, Result.waiting(retryAt, telegram, retryAt == 0));
    }

    private void done(long run, Result.Kind kind, PurpleSyncChatCore.Peer chat, PurpleSyncMagicCore.Problem problem, boolean show, long retryAt,
                      boolean manual, String result, PurpleSyncMagicCore.LookupInput input) {
        port.diag("lookup_result", code("result", result), count("count", candidates(input)));
        failures = 0;
        finish(run, new Result(kind, chat, problem, show, retryAt, manual, false, false));
    }

    private void unavailable(long run, String why) {
        port.diag("lookup_result", code("result", "absent_waiting"), reason(why));
        finish(run, Result.unavailable());
    }

    private void finish(long run, Result result) {
        if (run != generation && !closed) return;
        running = false; fresh = false; createdChat = null;
        if (listsPage != null) { port.cancelDelay(listsPage); listsPage = null; }
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

    /**
     * Sends {@code call} unless the binding holds a deadline for {@code method}, or a transient failure holds it in
     * memory. Core classifies a failure ({@link PurpleSyncMagicCore#classify}): a flood records its end in the
     * binding (after {@code beforeFlood}) and finishes as a Telegram wait; a timeout or network failure backs off in
     * memory and finishes as a wait of Purple's own, or runs {@code network} instead when given; a refusal goes to
     * {@code refused}.
     */
    private <T> void gated(long run, String binding, String method, Call<T> call, Consumer<T> ok, Consumer<Reply<T>> refused,
                           Runnable network, Runnable beforeFlood) {
        long now = port.now();
        long until = PurpleSyncMagicCore.waitUntil(binding, method, now);
        String name = method.toLowerCase(Locale.ROOT);
        if (until < 0) { unavailable(run, "binding_unreadable"); return; }
        if (until > 0) {
            port.diag("wait_honored", code("method", name), count("seconds", (int) Math.min(Integer.MAX_VALUE, until - now)));
            finish(run, Result.waiting(until, true, false));
            return;
        }
        Long held = transientUntil.get(method);
        if (held != null && held > now) {
            port.diag("wait_honored", code("method", name), count("seconds", (int) Math.min(Integer.MAX_VALUE, held - now)));
            finish(run, Result.waiting(held, false, false));
            return;
        }
        call.start(reply -> port.post(() -> {
            if (!live(run)) { finish(run, Result.unavailable()); return; }
            if (reply.error == null && !reply.timeout && reply.code == 0) {
                port.diag("request", code("method", name), code("outcome", "ok"));
                transientUntil.remove(method);
                ok.accept(reply.value);
                return;
            }
            PurpleSyncMagicCore.Classified error = PurpleSyncMagicCore.classify(reply.code, reply.error, reply.timeout);
            switch (error.kind) {
                case Wait: {
                    port.diag("request", code("method", name), code("outcome", "flood"), count("seconds", (int) Math.min(Integer.MAX_VALUE, error.seconds)));
                    if (error.seconds <= SHORT_FLOOD && retried.add(method)) {
                        port.delay(() -> port.post(() -> { if (live(run)) gated(run, binding, method, call, ok, refused, network, beforeFlood); else finish(run, Result.unavailable()); }),
                            error.seconds * 1000 + 1000);
                        return;
                    }
                    if (beforeFlood != null) beforeFlood.run();
                    long retryAt = now + error.seconds + 1;
                    edit(current -> PurpleSyncMagicCore.recordWait(current, method, retryAt));
                    finish(run, Result.waiting(retryAt, true, false));
                    return;
                }
                case Transient: {
                    port.diag("request", code("method", name), code("outcome", reply.timeout ? "timeout" : "network"), count("code", Math.abs(reply.code)));
                    long retryAt = now + Math.min(5L << Math.min(failures++, 4), TRANSIENT_MAX);
                    transientUntil.put(method, retryAt);
                    if (network != null) { network.run(); return; }
                    finish(run, Result.waiting(retryAt, false, false));
                    return;
                }
                default:
                    port.diag("request", code("method", name), code("outcome", "error"), count("code", Math.abs(reply.code)));
                    refused.accept(reply);
            }
        }));
    }

    private <T> void gated(long run, String binding, String method, Call<T> call, Consumer<T> ok, Consumer<Reply<T>> refused) {
        gated(run, binding, method, call, ok, refused, null, null);
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

    /** The local magic chats, with the chat this lookup created when the lists do not show it yet. */
    private List<PurpleSyncMagicCore.Candidate> withCreated(List<PurpleSyncMagicCore.Candidate> visible) {
        if (createdChat == null) return visible;
        for (PurpleSyncMagicCore.Candidate chat : visible) if (chat != null && chat.channelId == createdChat.channelId) return visible;
        List<PurpleSyncMagicCore.Candidate> result = new ArrayList<>(visible);
        result.add(createdChat);
        return result;
    }

    private static int candidates(PurpleSyncMagicCore.LookupInput input) {
        return input.visible.size();
    }
}
