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
            done.accept((PurpleSyncMagicChat.Reply<T>) reply);
        }
        private void remember(PurpleSyncMagicCore.Candidate chat) { known.put(chat.channelId, chat); }
        /** The local copy of a chat: what the list shows, without counts. */
        private static PurpleSyncMagicCore.Candidate local(PurpleSyncMagicCore.Candidate chat) {
            PurpleSyncMagicCore.Candidate copy = chat(chat.channelId, null, null); copy.member = chat.member; return copy;
        }
        public void allChats(Consumer<PurpleSyncMagicChat.Reply<List<PurpleSyncMagicCore.Candidate>>> done) { answer(PurpleSyncMagicCore.ALL_CHATS, done); }
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
        check(answers[0] == 5 && fake.sent.size() == 1, "five triggers, one lookup, one request");

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
