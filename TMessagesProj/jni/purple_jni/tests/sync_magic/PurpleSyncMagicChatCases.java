package org.telegram.messenger.purple;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Host cases for PurpleSyncMagicChat, the lookup service, against a fake
 * Telegram (scripted replies per method), fake local lists and a fake clock,
 * with the real core policy over JNI. Each case asserts the exact requests
 * sent, so a lookup that searches, asks twice or creates behind a wait fails.
 */
public final class PurpleSyncMagicChatCases {
    private static int checks;
    private static final long START = 1792886400L;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }

    static final class Fake implements PurpleSyncMagicChat.Port {
        long now = START;
        boolean current = true;
        String binding = "";
        boolean unreadable;
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final List<long[]> delays = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        final List<String> diag = new ArrayList<>();
        final Map<String, ArrayDeque<PurpleSyncMagicChat.Reply<?>>> replies = new HashMap<>();
        List<PurpleSyncMagicCore.Candidate> visible = new ArrayList<>();
        PurpleSyncMagicCore.BoundState boundState = PurpleSyncMagicCore.BoundState.Unknown;
        PurpleSyncMagicCore.Candidate bound;
        /** Chats in the fake MessagesController memory: visible ones and every chat a reply carried, as putChats does. */
        final Map<Long, PurpleSyncMagicCore.Candidate> known = new HashMap<>();
        /** Chats in the fake local database, which a cold start has not loaded into memory yet. */
        final Map<Long, PurpleSyncMagicCore.Candidate> database = new HashMap<>();
        int storedReads;

        void reply(String method, PurpleSyncMagicChat.Reply<?> value) { replies.computeIfAbsent(method, k -> new ArrayDeque<>()).add(value); }
        void drain() { for (int i = 0; i < 10000 && !tasks.isEmpty(); ++i) tasks.removeFirst().run(); check(tasks.isEmpty(), "the loop settles"); }
        /** Moves the clock to the first pending delay and runs it. */
        boolean tick() {
            if (delayed.isEmpty()) return false;
            int first = 0;
            for (int i = 1; i < delays.size(); ++i) if (delays.get(i)[0] < delays.get(first)[0]) first = i;
            now = Math.max(now, delays.get(first)[0] / 1000);
            delays.remove(first); Runnable task = delayed.remove(first); task.run(); drain(); return true;
        }

        public boolean current() { return current; }
        public long now() { return now; }
        public void post(Runnable task) { tasks.addLast(task); }
        public void delay(Runnable task, long millis) { delayed.add(task); delays.add(new long[] { now * 1000 + millis }); }
        public void cancelDelay(Runnable task) { int i = delayed.indexOf(task); if (i >= 0) { delayed.remove(i); delays.remove(i); } }
        public String readBinding() { return unreadable ? null : binding; }
        public boolean writeBinding(String expected, String next) { if (!expected.equals(binding)) return false; binding = next; return true; }
        public void local(PurpleSyncChatCore.Peer chat, Consumer<PurpleSyncMagicChat.Local> done) {
            PurpleSyncMagicCore.BoundState state = chat == null ? PurpleSyncMagicCore.BoundState.Unknown : boundState;
            PurpleSyncMagicCore.Candidate value = chat != null && chat.kind == PurpleSyncChatCore.PeerKind.Self ? self() : bound;
            for (PurpleSyncMagicCore.Candidate item : visible) known.put(item.channelId, item);
            if (chat != null && chat.kind == PurpleSyncChatCore.PeerKind.Channel && state == PurpleSyncMagicCore.BoundState.Unknown && known.containsKey(chat.id)) {
                state = PurpleSyncMagicCore.BoundState.Present; value = local(known.get(chat.id));
            }
            if (chat != null && chat.kind == PurpleSyncChatCore.PeerKind.Self) state = PurpleSyncMagicCore.BoundState.Present;
            done.accept(new PurpleSyncMagicChat.Local(new ArrayList<>(visible), state, value, true, true));
        }
        @SuppressWarnings("unchecked")
        private <T> void answer(String method, Consumer<PurpleSyncMagicChat.Reply<T>> done) {
            sent.add(method);
            ArrayDeque<PurpleSyncMagicChat.Reply<?>> queue = replies.get(method);
            check(queue != null && !queue.isEmpty(), "a scripted reply for " + method);
            PurpleSyncMagicChat.Reply<?> reply = queue.removeFirst();
            if (reply.value instanceof PurpleSyncMagicCore.Candidate) remember((PurpleSyncMagicCore.Candidate) reply.value);
            if (reply.value instanceof List) for (Object item : (List<?>) reply.value) remember((PurpleSyncMagicCore.Candidate) item);
            if (reply.value instanceof PurpleSyncMagicChat.Chats) for (PurpleSyncMagicCore.Candidate item : ((PurpleSyncMagicChat.Chats) reply.value).candidates) remember(item);
            done.accept((PurpleSyncMagicChat.Reply<T>) reply);
        }
        private void remember(PurpleSyncMagicCore.Candidate chat) { known.put(chat.channelId, chat); }
        /** The local copy of a chat: what the list shows, without counts. */
        private static PurpleSyncMagicCore.Candidate local(PurpleSyncMagicCore.Candidate chat) {
            PurpleSyncMagicCore.Candidate copy = chat(chat.channelId, null, null); copy.member = chat.member; return copy;
        }
        public void stored(long id, Consumer<PurpleSyncMagicChat.Local> done) {
            ++storedReads;
            PurpleSyncMagicCore.Candidate row = database.get(id);
            if (row != null) known.put(id, row);
            done.accept(new PurpleSyncMagicChat.Local(new ArrayList<>(), row != null ? PurpleSyncMagicCore.BoundState.Present : PurpleSyncMagicCore.BoundState.Unknown,
                row != null ? local(row) : null, true, true));
        }
        /** The except_ids of every getAllChats sent. */
        final List<List<Long>> excepts = new ArrayList<>();
        @SuppressWarnings("unchecked")
        public void allChats(List<Long> except, Consumer<PurpleSyncMagicChat.Reply<PurpleSyncMagicChat.Chats>> done) {
            excepts.add(new ArrayList<>(except));
            this.<Object>answer(PurpleSyncMagicCore.ALL_CHATS, reply -> {
                if (!(reply.value instanceof List)) { done.accept((PurpleSyncMagicChat.Reply<PurpleSyncMagicChat.Chats>) (PurpleSyncMagicChat.Reply<?>) reply); return; }
                List<PurpleSyncMagicCore.Candidate> list = (List<PurpleSyncMagicCore.Candidate>) reply.value;
                List<Long> ids = new ArrayList<>();
                for (PurpleSyncMagicCore.Candidate chat : list) ids.add(chat.channelId);
                done.accept(PurpleSyncMagicChat.Reply.ok(new PurpleSyncMagicChat.Chats(list, ids, -1)));
            });
        }
        public void fullInfo(long id, Consumer<PurpleSyncMagicChat.Reply<PurpleSyncMagicCore.Candidate>> done) { answer(PurpleSyncMagicCore.FULL_INFO, done); }
        public void create(Consumer<PurpleSyncMagicChat.Reply<PurpleSyncMagicCore.Candidate>> done) { answer(PurpleSyncMagicCore.CREATE, done); }
        public void archive(long id, Consumer<PurpleSyncMagicChat.Reply<Boolean>> done) { answer(PurpleSyncMagicCore.EDIT_FOLDERS, done); }
        public void mute(long id, Consumer<PurpleSyncMagicChat.Reply<Boolean>> done) { answer(PurpleSyncMagicCore.NOTIFY, done); }
        public void diag(String event, PurpleDiagLogStore.Field... fields) {
            StringBuilder line = new StringBuilder(event);
            for (PurpleDiagLogStore.Field field : fields) line.append(' ').append(field.key).append('=').append(field.value);
            diag.add(line.toString());
        }
        boolean logged(String text) { for (String line : diag) if (line.contains(text)) return true; return false; }
    }

    static PurpleSyncMagicCore.Candidate self() {
        PurpleSyncMagicCore.Candidate result = new PurpleSyncMagicCore.Candidate();
        result.facts.kind = PurpleSyncChatCore.Kind.Self; result.facts.canSendFiles = true;
        result.facts.otherMembers = 0L; result.facts.otherAdmins = 0L;
        return result;
    }

    static PurpleSyncMagicCore.Candidate chat(long id, Long others, Long admins) {
        PurpleSyncMagicCore.Candidate result = new PurpleSyncMagicCore.Candidate();
        result.channelId = id; result.title = PurpleSyncMagicCore.TITLE; result.megagroup = true;
        result.facts.kind = PurpleSyncChatCore.Kind.Supergroup;
        result.facts.creator = result.facts.admin = result.facts.soleAdmin = result.facts.canSendFiles = true;
        result.facts.otherMembers = others; result.facts.otherAdmins = admins;
        return result;
    }

    static PurpleSyncChatCore.Peer channel(long id) { return PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, id); }

    static PurpleSyncMagicChat.Result run(Fake fake, PurpleSyncMagicChat service, PurpleSyncMagicChat.Trigger trigger, boolean fresh) {
        PurpleSyncMagicChat.Result[] result = { null };
        service.lookup(trigger, fresh, value -> result[0] = value);
        fake.drain();
        check(result[0] != null, "the lookup answered");
        return result[0];
    }

    static <T> PurpleSyncMagicChat.Reply<T> ok(T value) { return PurpleSyncMagicChat.Reply.ok(value); }
    /** A messages.chatsSlice page: {@code ids} are the chats it names, {@code magic} the magic chats among them. */
    static PurpleSyncMagicChat.Reply<PurpleSyncMagicChat.Chats> slice(int count, List<PurpleSyncMagicCore.Candidate> magic, Long... ids) {
        List<Long> all = new ArrayList<>(Arrays.asList(ids));
        for (PurpleSyncMagicCore.Candidate chat : magic) all.add(chat.channelId);
        return PurpleSyncMagicChat.Reply.ok(new PurpleSyncMagicChat.Chats(magic, all, count));
    }
    static <T> PurpleSyncMagicChat.Reply<T> error(String text, int code) { return PurpleSyncMagicChat.Reply.failed(text, code); }

    public static void main(String[] args) {
        PurpleCore.ensureLoaded();

        // Fresh device, no magic chat anywhere: one getAllChats, then create, archive, mute and verify.
        Fake fake = new Fake();
        PurpleSyncMagicChat service = new PurpleSyncMagicChat(fake);
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        fake.reply(PurpleSyncMagicCore.CREATE, ok(chat(5000, null, null)));
        fake.reply(PurpleSyncMagicCore.EDIT_FOLDERS, ok(true));
        fake.reply(PurpleSyncMagicCore.NOTIFY, ok(true));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5000, 0L, 0L)));
        PurpleSyncMagicChat.Result result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(5000).equals(result.chat), "the created chat is used");
        check(fake.sent.equals(Arrays.asList(PurpleSyncMagicCore.ALL_CHATS, PurpleSyncMagicCore.CREATE, PurpleSyncMagicCore.EDIT_FOLDERS,
            PurpleSyncMagicCore.NOTIFY, PurpleSyncMagicCore.FULL_INFO)), "absence proof, create, archive, mute, verify: " + fake.sent);
        PurpleSyncMagicCore.Binding binding = PurpleSyncMagicCore.parse(fake.binding);
        check(binding != null && binding.mode == PurpleSyncMagicCore.Mode.Magic && channel(5000).equals(binding.chat), "the binding names the new chat");
        check(fake.logged("lookup_start trigger=startup") && fake.logged("lookup_result result=created"), "diagnostics name the trigger and the creation");
        for (String line : fake.diag) check(!line.contains("5000"), "no chat id in diagnostics: " + line);

        // The next lookup uses the bound chat with no request at all.
        fake.sent.clear();
        fake.boundState = PurpleSyncMagicCore.BoundState.Present; fake.bound = chat(5000, null, null);
        result = run(fake, service, PurpleSyncMagicChat.Trigger.auto, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(5000).equals(result.chat) && fake.sent.isEmpty(), "a bound chat costs no request");
        // A post a day later verifies once more.
        fake.now += 24 * 60 * 60;
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5000, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.post, true);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && fake.sent.equals(Collections.singletonList(PurpleSyncMagicCore.FULL_INFO)), "a stale chat is verified before a post");

        // Another member joined: a problem, shown once.
        fake.now += 24 * 60 * 60; fake.sent.clear();
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5000, 1L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.post, true);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && result.problem == PurpleSyncMagicCore.Problem.ExtraMember && result.show, "an extra member is a problem to show");
        service.shown(); fake.drain();
        check(fake.logged("problem_shown reason=extra_member"), "showing it is logged");
        fake.sent.clear();
        result = run(fake, service, PurpleSyncMagicChat.Trigger.post, true);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && !result.show && fake.sent.isEmpty(), "the same problem is not shown twice");
        check(service.last().kind == PurpleSyncMagicChat.Result.Kind.Problem, "the status keeps the problem");
        fake.sent.clear();
        for (PurpleSyncMagicChat.Trigger trigger : PurpleSyncMagicChat.Trigger.values()) {
            if (trigger == PurpleSyncMagicChat.Trigger.manual) continue;
            result = run(fake, service, trigger, trigger == PurpleSyncMagicChat.Trigger.post);
            check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && !result.show, trigger + " keeps the pause without asking again");
        }
        check(fake.sent.isEmpty(), "a standing problem sends nothing: " + fake.sent);
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5000, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.manual, true);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && fake.sent.equals(Collections.singletonList(PurpleSyncMagicCore.FULL_INFO)),
            "a manual check reads the chat again and resumes once it is fixed");
        check(PurpleSyncMagicCore.parse(fake.binding).problem == PurpleSyncMagicCore.Problem.None && fake.logged("auto_resumed reason=user_check"),
            "and clears the problem");

        // A visible chat needs no absence proof; the oldest of several wins.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.visible = new ArrayList<>(Arrays.asList(chat(7000, null, null), chat(6000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(6000, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.screen, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(6000).equals(result.chat), "the oldest visible chat is bound");
        check(fake.sent.equals(Collections.singletonList(PurpleSyncMagicCore.FULL_INFO)), "only its full info is read: " + fake.sent);
        check(fake.logged("lookup_result result=found"), "a found chat is logged");

        // An older chat appearing later is adopted.
        fake.sent.clear();
        fake.boundState = PurpleSyncMagicCore.BoundState.Present; fake.bound = chat(6000, null, null);
        fake.visible = new ArrayList<>(Arrays.asList(chat(6000, null, null), chat(5500, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5500, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.auto, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(5500).equals(result.chat), "devices converge on the oldest chat");
        check(fake.logged("lookup_result result=rebound"), "the rebind is logged");

        // Only ineligible magic chats: a problem, and nothing is created.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.visible = new ArrayList<>(Collections.singletonList(chat(6000, 2L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && result.problem == PurpleSyncMagicCore.Problem.MagicIneligible && fake.sent.isEmpty(),
            "an ineligible magic chat is never replaced behind the user's back");

        // A long flood on getAllChats is kept across a restart, for every trigger.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, error("FLOOD_WAIT_43200", 420));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && result.retryAt == START + 43201, "a flood is a wait until its end");
        check(fake.logged("request method=messages.getallchats outcome=flood seconds=43200"), "the flood is logged with its seconds");
        String kept = fake.binding;
        for (PurpleSyncMagicChat.Trigger trigger : PurpleSyncMagicChat.Trigger.values()) {
            Fake restarted = new Fake(); restarted.binding = kept; restarted.now = START + 60;
            result = run(restarted, new PurpleSyncMagicChat(restarted), trigger, trigger == PurpleSyncMagicChat.Trigger.post);
            check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && restarted.sent.isEmpty(), "after a restart, " + trigger + " sends nothing during the wait");
        }
        Fake later = new Fake(); later.binding = kept; later.now = START + 43201;
        later.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.singletonList(chat(8000, null, null))));
        later.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(8000, 0L, 0L)));
        result = run(later, new PurpleSyncMagicChat(later), PurpleSyncMagicChat.Trigger.auto, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(8000).equals(result.chat), "after the wait the listed chat is bound");
        // The wait also wakes the lookup up by itself.
        check(!fake.delayed.isEmpty(), "a wake-up is scheduled for the end of the wait");

        // A flood on getFullChannel holds that request across a restart too, though core would verify again.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.visible = new ArrayList<>(Collections.singletonList(chat(6000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, error("FLOOD_WAIT_3600", 420));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.screen, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && result.retryAt == START + 3601, "a full-info flood is a wait");
        Fake again = new Fake(); again.binding = fake.binding; again.now = START + 60;
        again.visible = new ArrayList<>(Collections.singletonList(chat(6000, null, null)));
        result = run(again, new PurpleSyncMagicChat(again), PurpleSyncMagicChat.Trigger.manual, true);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && again.sent.isEmpty() && again.logged("wait_honored method=channels.getfullchannel"),
            "the restarted lookup honours it without a request");

        // The wake-up after a manual check that ended in a wait is a retry, not another user check.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, error("FLOOD_WAIT_600", 420));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.manual, true);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait, "the manual check waits");
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.singletonList(chat(8100, null, null))));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(8100, 0L, 0L)));
        check(fake.tick() && service.last().kind == PurpleSyncMagicChat.Result.Kind.Use, "the wake-up finds the chat");
        check(fake.logged("lookup_start trigger=retry") && fake.diag.stream().filter(line -> line.contains("trigger=manual")).count() == 1,
            "and is logged as a retry: " + fake.diag);

        // A short flood is retried once in place.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.visible = new ArrayList<>(Collections.singletonList(chat(6000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, error("FLOOD_WAIT_10", 420));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(6000, 0L, 0L)));
        PurpleSyncMagicChat.Result[] shortResult = { null };
        service.lookup(PurpleSyncMagicChat.Trigger.manual, true, value -> shortResult[0] = value);
        fake.drain();
        check(shortResult[0] == null && fake.tick(), "the short flood waits in place");
        check(shortResult[0] != null && shortResult[0].kind == PurpleSyncMagicChat.Result.Kind.Use && fake.sent.size() == 2, "and is retried once");

        // A lost create reply blocks another creation for a day; an error reply does not.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        fake.reply(PurpleSyncMagicCore.CREATE, PurpleSyncMagicChat.Reply.timedOut());
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && result.retryAt == START + 24 * 60 * 60, "an uncertain creation waits a day");
        fake.now += 3600; fake.sent.clear();
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.screen, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && !fake.sent.contains(PurpleSyncMagicCore.CREATE), "no second creation within the day");
        fake.now += 3600; fake.sent.clear();
        fake.visible = new ArrayList<>(Collections.singletonList(chat(9000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(9000, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.screen, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(9000).equals(result.chat), "the chat arriving through updates is bound");

        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        fake.reply(PurpleSyncMagicCore.CREATE, error("FLOOD_WAIT_600", 420));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && result.retryAt == START + 601, "a flood on create waits only for the flood");
        fake.now += 601; fake.sent.clear();
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        fake.reply(PurpleSyncMagicCore.CREATE, error("CHANNELS_TOO_MUCH", 400));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.screen, false);
        check(fake.sent.contains(PurpleSyncMagicCore.CREATE), "after a flood ends, creation is tried again");
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && result.problem == PurpleSyncMagicCore.Problem.CreateLimit && result.show, "the channel limit is a problem");

        // A sliced getAllChats reply is paged with except_ids; only the complete list may lead to Create.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, slice(3, Collections.emptyList(), 11L, 12L));
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, slice(3, Collections.emptyList(), 13L));
        fake.reply(PurpleSyncMagicCore.CREATE, PurpleSyncMagicChat.Reply.timedOut());
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(fake.sent.equals(Arrays.asList(PurpleSyncMagicCore.ALL_CHATS, PurpleSyncMagicCore.ALL_CHATS, PurpleSyncMagicCore.CREATE)),
            "two pages, then create: " + fake.sent);
        check(fake.excepts.equals(Arrays.asList(Collections.emptyList(), Arrays.asList(11L, 12L))), "the second page excludes the first: " + fake.excepts);
        // A list still partial after the page limit proves nothing: no create, a recorded wait.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        for (int i = 0; i < PurpleSyncMagicChat.ALL_CHATS_PAGES; ++i) fake.reply(PurpleSyncMagicCore.ALL_CHATS, slice(1000, Collections.emptyList(), 100L + i));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && result.retryAt == START + PurpleSyncMagicChat.PARTIAL_WAIT
            && fake.sent.size() == PurpleSyncMagicChat.ALL_CHATS_PAGES && !fake.sent.contains(PurpleSyncMagicCore.CREATE), "a partial list waits: " + fake.sent);
        check(fake.logged("result=absent_waiting reason=list_partial")
            && PurpleSyncMagicCore.waitUntil(fake.binding, PurpleSyncMagicCore.ALL_CHATS, START) == START + PurpleSyncMagicChat.PARTIAL_WAIT,
            "it is logged and the wait is kept across restarts");
        // A magic chat in a partial list is still found.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        for (int i = 0; i < PurpleSyncMagicChat.ALL_CHATS_PAGES; ++i)
            fake.reply(PurpleSyncMagicCore.ALL_CHATS, slice(1000, i == 2 ? Collections.singletonList(chat(6100, null, null)) : Collections.emptyList(), 100L + i));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(6100, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(6100).equals(result.chat) && !fake.sent.contains(PurpleSyncMagicCore.CREATE),
            "a magic chat on a page is bound without creating");
        // A bound chat missing from a partial list is not called gone.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.binding = PurpleSyncMagicCore.verify("", chat(4000, 0L, 0L), START).binding;
        for (int i = 0; i < PurpleSyncMagicChat.ALL_CHATS_PAGES; ++i)
            fake.reply(PurpleSyncMagicCore.ALL_CHATS, slice(1000, i == 0 ? Collections.singletonList(chat(6100, null, null)) : Collections.emptyList(), 100L + i));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && PurpleSyncMagicCore.parse(fake.binding).problem == PurpleSyncMagicCore.Problem.None,
            "the bound chat waits instead of being gone: " + result.kind);

        // A cold start: the bound chat is only in the local database. It is read from there, with no request.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.binding = PurpleSyncMagicCore.verify("", chat(4000, 0L, 0L), START).binding;
        fake.database.put(4000L, chat(4000, null, null));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(4000).equals(result.chat) && fake.sent.isEmpty() && fake.storedReads == 1,
            "a stored bound chat costs no getAllChats: " + fake.sent);
        result = run(fake, service, PurpleSyncMagicChat.Trigger.auto, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && fake.sent.isEmpty() && fake.storedReads == 1, "once loaded, memory answers");
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.binding = PurpleSyncMagicCore.verify("", chat(4000, 0L, 0L), START).binding;
        PurpleSyncMagicCore.Candidate left = chat(4000, null, null); left.member = false;
        fake.database.put(4000L, left);
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && result.problem == PurpleSyncMagicCore.Problem.ChatLeft && fake.sent.isEmpty(),
            "a stored chat the user left is a problem, also without a request");

        // A refused create that is not a flood waits ten minutes, kept across restarts, then twice as long.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        fake.reply(PurpleSyncMagicCore.CREATE, error("PEER_FLOOD", 400));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && result.retryAt == START + PurpleSyncMagicChat.ERROR_WAIT,
            "PEER_FLOOD is a wait: " + result.kind + " " + result.retryAt);
        fake.sent.clear(); fake.now += 60;
        Fake restarted = new Fake(); restarted.binding = fake.binding; restarted.now = fake.now;
        restarted.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        result = run(restarted, new PurpleSyncMagicChat(restarted), PurpleSyncMagicChat.Trigger.screen, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait && !restarted.sent.contains(PurpleSyncMagicCore.CREATE),
            "no create during the wait, after a restart too: " + restarted.sent);
        fake.now = START + PurpleSyncMagicChat.ERROR_WAIT;
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        fake.reply(PurpleSyncMagicCore.CREATE, error("CHAT_INVALID", 400));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.screen, false);
        check(fake.sent.contains(PurpleSyncMagicCore.CREATE) && result.kind == PurpleSyncMagicChat.Result.Kind.Wait
            && result.retryAt == fake.now + 2 * PurpleSyncMagicChat.ERROR_WAIT, "the next refusal waits twice as long");
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, error("CHAT_INVALID", 400));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Wait
            && PurpleSyncMagicCore.waitUntil(fake.binding, PurpleSyncMagicCore.ALL_CHATS, START) == START + PurpleSyncMagicChat.ERROR_WAIT,
            "a refused getAllChats waits the same way");

        // A bound chat missing from memory: one getAllChats settles present or gone.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.binding = PurpleSyncMagicCore.verify("", chat(4000, 0L, 0L), START).binding;
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.singletonList(chat(4000, null, null))));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(4000).equals(result.chat), "a listed bound chat is used");
        fake.known.clear(); fake.sent.clear();
        fake.reply(PurpleSyncMagicCore.ALL_CHATS, ok(Collections.emptyList()));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && result.problem == PurpleSyncMagicCore.Problem.ChatGone
            && fake.sent.equals(Collections.singletonList(PurpleSyncMagicCore.ALL_CHATS)), "an unlisted bound chat is gone, after one request");
        fake.boundState = PurpleSyncMagicCore.BoundState.Gone; fake.sent.clear();
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && fake.sent.isEmpty(), "a chat known gone costs no request");

        // An older magic chat that fails verification is skipped: sync goes on in the bound chat.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.binding = PurpleSyncMagicCore.verify("", chat(6000, 0L, 0L), START).binding;
        fake.boundState = PurpleSyncMagicCore.BoundState.Present; fake.bound = chat(6000, null, null);
        fake.visible = new ArrayList<>(Arrays.asList(chat(5000, null, null), chat(6000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5000, 1L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.auto, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(6000).equals(result.chat)
            && fake.sent.equals(Collections.singletonList(PurpleSyncMagicCore.FULL_INFO)), "the bound chat stays in use: " + result.kind + " " + fake.sent);
        check(PurpleSyncMagicCore.parse(fake.binding).problem == PurpleSyncMagicCore.Problem.None && fake.logged("rejected reason=extra_member"),
            "nothing pauses, and the rejection is logged");
        fake.sent.clear();
        result = run(fake, service, PurpleSyncMagicChat.Trigger.post, true);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(6000).equals(result.chat) && fake.sent.isEmpty(),
            "the rejected chat is not read again: " + fake.sent);
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5000, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.manual, true);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(5000).equals(result.chat)
            && fake.sent.equals(Collections.singletonList(PurpleSyncMagicCore.FULL_INFO)), "a manual check reads it again and adopts it once fixed");
        // Unbound, the next candidate is tried; magic_ineligible only when none is left.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.visible = new ArrayList<>(Arrays.asList(chat(5000, null, null), chat(6000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5000, 0L, 1L)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(6000, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && channel(6000).equals(result.chat)
            && fake.sent.equals(Arrays.asList(PurpleSyncMagicCore.FULL_INFO, PurpleSyncMagicCore.FULL_INFO)), "a failing older chat is skipped and the next bound");
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.visible = new ArrayList<>(Collections.singletonList(chat(5000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(5000, 2L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && result.problem == PurpleSyncMagicCore.Problem.MagicIneligible
            && !fake.sent.contains(PurpleSyncMagicCore.CREATE), "with no candidate left it is magic_ineligible, and nothing is created");

        // Saved Messages chosen by hand is used as it is.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.binding = PurpleSyncMagicCore.manual("", PurpleSyncChatCore.Peer.self());
        result = run(fake, service, PurpleSyncMagicChat.Trigger.auto, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use && PurpleSyncChatCore.Peer.self().equals(result.chat) && result.manual && fake.sent.isEmpty(),
            "a manual Saved Messages choice is used without requests");

        // One lookup at a time: triggers arriving meanwhile share its single request.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        fake.visible = new ArrayList<>(Collections.singletonList(chat(6000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(6000, 0L, 0L)));
        int[] answers = { 0 };
        for (PurpleSyncMagicChat.Trigger trigger : PurpleSyncMagicChat.Trigger.values()) service.lookup(trigger, false, value -> ++answers[0]);
        fake.drain();
        check(answers[0] == PurpleSyncMagicChat.Trigger.values().length && fake.sent.size() == 1, "every trigger, one lookup, one request");

        // Sync found another device's sync space in the bound chat: a problem that pauses until the user acts.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake);
        service.raise(PurpleSyncMagicCore.Problem.SpaceMismatch); fake.drain();
        check(service.last().kind == PurpleSyncMagicChat.Result.Kind.Unavailable && fake.binding.isEmpty(), "nothing to raise before a chat is bound");
        fake.visible = new ArrayList<>(Collections.singletonList(chat(6000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(6000, 0L, 0L)));
        result = run(fake, service, PurpleSyncMagicChat.Trigger.auto, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Use, "the chat is bound");
        service.raise(PurpleSyncMagicCore.Problem.SpaceMismatch); fake.drain();
        check(service.last().kind == PurpleSyncMagicChat.Result.Kind.Problem && service.last().problem == PurpleSyncMagicCore.Problem.SpaceMismatch
            && service.last().show && channel(6000).equals(service.last().chat), "a space mismatch is a problem to show, naming the chat");
        check(PurpleSyncMagicCore.parse(fake.binding).problem == PurpleSyncMagicCore.Problem.SpaceMismatch && fake.logged("auto_paused reason=space_mismatch"),
            "it is recorded and logged");
        fake.sent.clear(); fake.boundState = PurpleSyncMagicCore.BoundState.Present; fake.bound = chat(6000, null, null);
        result = run(fake, service, PurpleSyncMagicChat.Trigger.auto, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Problem && result.problem == PurpleSyncMagicCore.Problem.SpaceMismatch && fake.sent.isEmpty(),
            "later automatic lookups keep the pause and send nothing");
        // The Sync chat screen: the mismatch is shown once and offers Join, which clears it.
        PurpleSyncMagicScreen.Frame frame = PurpleSyncMagicScreen.frame(service.last(), service.binding());
        check(frame.line == PurpleSyncMagicScreen.Line.Problem && frame.showProblem && frame.canJoin() && frame.canOpen() && !frame.manual,
            "the screen shows the mismatch once and offers Join");
        check(channel(6000).equals(service.hidden()), "the magic chat stays hidden from the lists while paused");
        fake.sent.clear();
        PurpleSyncMagicChat.Result[] action = { null };
        service.joined(value -> action[0] = value); fake.drain();
        check(action[0] != null && action[0].kind == PurpleSyncMagicChat.Result.Kind.Use && channel(6000).equals(action[0].chat)
            && PurpleSyncMagicCore.parse(fake.binding).problem == PurpleSyncMagicCore.Problem.None, "Join clears the problem and the chat is used again");
        check(fake.logged("user_action action=join") && fake.sent.isEmpty(), "Join is logged, and the chat verified today costs no request: " + fake.sent);
        check(!PurpleSyncMagicScreen.frame(service.last(), service.binding()).canJoin(), "Join is offered only for the mismatch");

        // The list toggle is kept in the binding; only the magic chat is hidden.
        check(channel(6000).equals(service.hidden()), "the magic chat is hidden from the lists by default");
        Boolean[] listed = { null };
        service.list(true, value -> listed[0] = value); fake.drain();
        check(Boolean.TRUE.equals(listed[0]) && PurpleSyncMagicCore.parse(fake.binding).listed && service.hidden() == null, "showing it in the lists is kept");
        frame = PurpleSyncMagicScreen.frame(service.last(), service.binding());
        check(frame.canList() && frame.listed && !frame.canUseDefault(), "the screen shows the toggle on");
        service.list(false, value -> listed[0] = value); fake.drain();
        check(Boolean.TRUE.equals(listed[0]) && channel(6000).equals(service.hidden()), "turning it off hides the chat again");
        service.list(true, null); fake.drain();

        // Only Saved Messages or a supergroup the user is alone in may be picked (Q2).
        check(PurpleSyncMagicScreen.pick(PurpleSyncChatCore.Peer.self(), null) == PurpleSyncMagicScreen.Pick.Allowed, "Saved Messages may be picked");
        check(PurpleSyncMagicScreen.pick(channel(7100), chat(7100, 0L, 0L)) == PurpleSyncMagicScreen.Pick.Allowed, "a supergroup the user is alone in");
        check(PurpleSyncMagicScreen.pick(channel(7100), chat(7100, 1L, 0L)) == PurpleSyncMagicScreen.Pick.NotAlone, "not one with another member");
        check(PurpleSyncMagicScreen.pick(channel(7100), chat(7100, null, null)) == PurpleSyncMagicScreen.Pick.Unknown, "nor one whose members are unknown");
        PurpleSyncMagicCore.Candidate broadcast = chat(7100, 0L, 0L); broadcast.megagroup = false;
        check(PurpleSyncMagicScreen.pick(channel(7100), broadcast) == PurpleSyncMagicScreen.Pick.Unsupported, "nor a broadcast channel");
        check(PurpleSyncMagicScreen.pick(channel(7100), chat(7200, 0L, 0L)) == PurpleSyncMagicScreen.Pick.Unsupported, "nor facts of another chat");
        check(PurpleSyncMagicScreen.pick(PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.User, 42), null) == PurpleSyncMagicScreen.Pick.Unsupported
            && PurpleSyncMagicScreen.pick(PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Chat, 42), null) == PurpleSyncMagicScreen.Pick.Unsupported,
            "nor a private chat or a basic group");

        // A picked chat is used as a manual binding, never hidden; Use Magic Purple Sync binds the magic chat again.
        fake.sent.clear(); fake.bound = chat(7100, 0L, 0L); fake.boundState = PurpleSyncMagicCore.BoundState.Present;
        service.choose(channel(7100), value -> action[0] = value); fake.drain();
        check(action[0].kind == PurpleSyncMagicChat.Result.Kind.Use && channel(7100).equals(action[0].chat) && action[0].manual && fake.sent.isEmpty(),
            "a picked chat is used without requests");
        frame = PurpleSyncMagicScreen.frame(service.last(), service.binding());
        check(service.hidden() == null && frame.manual && frame.canUseDefault() && !frame.canList(), "a picked chat is never hidden and offers the default");
        check(PurpleSyncMagicCore.parse(fake.binding).listed, "picking keeps the list choice");
        fake.bound = null; fake.boundState = PurpleSyncMagicCore.BoundState.Unknown;
        fake.visible = new ArrayList<>(Collections.singletonList(chat(6000, null, null)));
        fake.reply(PurpleSyncMagicCore.FULL_INFO, ok(chat(6000, 0L, 0L)));
        service.useDefault(value -> action[0] = value); fake.drain();
        check(action[0].kind == PurpleSyncMagicChat.Result.Kind.Use && channel(6000).equals(action[0].chat) && !action[0].manual,
            "Use Magic Purple Sync binds the magic chat again");
        PurpleSyncMagicCore.Binding back = PurpleSyncMagicCore.parse(fake.binding);
        check(back.mode == PurpleSyncMagicCore.Mode.Magic && back.listed, "the list choice survives returning to the default");
        check(fake.logged("user_action action=choose") && fake.logged("user_action action=default") && fake.logged("listed value=on")
            && fake.logged("listed value=off"), "each choice is logged");

        // "reason" is the one fixed field name; its value is a vocabulary word like every other.
        for (String line : fake.diag) for (String part : line.split(" ")) {
            for (String word : part.split("=")) if (!word.matches("[0-9]+") && !word.equals("reason"))
                check(PurpleDiagLogStore.VOCABULARY.contains(word), "vocabulary word: " + word);
        }

        // An unreadable binding or a closed service answers Unavailable and sends nothing.
        fake = new Fake(); service = new PurpleSyncMagicChat(fake); fake.unreadable = true;
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Unavailable && fake.sent.isEmpty(), "an unreadable binding stops the lookup");
        fake.unreadable = false; service.close(); fake.drain();
        result = run(fake, service, PurpleSyncMagicChat.Trigger.startup, false);
        check(result.kind == PurpleSyncMagicChat.Result.Kind.Unavailable && fake.sent.isEmpty(), "a closed service answers Unavailable");

        // Every diagnostic word is in the vocabulary.
        for (String line : later.diag) for (String part : line.split(" ")) {
            for (String word : part.split("=")) if (!word.matches("[0-9]+")) check(PurpleDiagLogStore.VOCABULARY.contains(word), "vocabulary word: " + word);
        }
        System.out.println("SYNC_MAGIC_CHAT_CHECKS=" + checks);
    }
}
