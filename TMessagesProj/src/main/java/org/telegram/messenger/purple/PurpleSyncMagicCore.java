/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.telegram.messenger.purple.PurpleSyncChatCore.json;
import static org.telegram.messenger.purple.PurpleSyncChatCore.put;

/**
 * The Magic Purple Sync policy of purple-core: which chat a device syncs
 * through, when it may read or create one, and the per-device binding
 * (sync/chat.json) that remembers the answer, its waits and its problem.
 * Every decision is made in core; this class only carries JSON both ways.
 * A failed native call answers like an unusable chat: {@link Action#Invalid},
 * an {@link Verdict#Ineligible} check, or a null binding, and every operation
 * refuses a null binding, so a failed edit cannot pass for "no binding" ("").
 */
public final class PurpleSyncMagicCore {
    public static final String TITLE = "Magic Purple Sync";
    public static final String CREATE = "channels.createChannel";
    public static final String FULL_INFO = "channels.getFullChannel";
    public static final String EDIT_FOLDERS = "folders.editPeerFolders";
    public static final String NOTIFY = "account.updateNotifySettings";

    public enum Problem {
        None, ChatGone, ChatLeft, NotMegagroup, NotCreator, ExtraMember, ExtraAdmin, PublicUsername,
        Restricted, CannotPost, ForeignRecord, ForwardedRecord, ViaBotRecord, CreateLimit, MagicIneligible, SpaceMismatch,
        /** Telegram refused a lookup request outright (a 400-class error that is not a flood). */
        RequestRefused;

        /** The lowercase code used in the binding file and in diagnostics. */
        public String code() { return name().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
        static Problem parse(String code) {
            for (Problem value : values()) if (value.code().equals(code)) return value;
            return null;
        }
    }
    public enum Verdict { Eligible, Unverified, Ineligible }
    public enum Action { Invalid, Use, Verify, Wait, Create, Problem }
    /** How core sorts a failed lookup request: a flood Telegram asked to wait out, a transient failure, or a refusal. */
    public enum ErrorKind { Wait, Transient, Refused }
    public enum BoundState { Unknown, Present, Gone }
    public enum Mode { None, Magic, Manual }
    public enum CreateStep { None, Requested, Uncertain, Created, Archived, Muted }
    public enum Migration { None, Moved, Published, Joined, Done }

    /** One chat as the client sees it; member and admin counts stay null until a full-info read. */
    public static final class Candidate {
        public long channelId;
        public String title = "";
        public boolean member = true, megagroup, gigagroup;
        public final PurpleSyncChatCore.ChatFacts facts = new PurpleSyncChatCore.ChatFacts();

        JSONObject json() {
            JSONObject result = PurpleSyncChatCore.json("channelId", Long.toString(channelId), "title", title, "member", member,
                "megagroup", megagroup, "gigagroup", gigagroup, "kind", facts.kind.name());
            String[] names = { "publicUsername", "protectedContent", "forum", "linkedDiscussion", "autoDelete", "paidMessages", "canSendFiles", "anonymousAdmin", "postsAsOther", "migrated", "slowMode", "admin", "creator", "soleAdmin" };
            boolean[] values = { facts.publicUsername, facts.protectedContent, facts.forum, facts.linkedDiscussion, facts.autoDelete, facts.paidMessages, facts.canSendFiles, facts.anonymousAdmin, facts.postsAsOther, facts.migrated, facts.slowMode, facts.admin, facts.creator, facts.soleAdmin };
            for (int i = 0; i != names.length; ++i) put(result, names[i], values[i]);
            if (facts.otherMembers != null) put(result, "otherMembers", facts.otherMembers.toString());
            if (facts.otherAdmins != null) put(result, "otherAdmins", facts.otherAdmins.toString());
            return result;
        }
    }

    public static final class Check {
        public final Verdict verdict;
        public final Problem problem;
        Check(JSONObject value) {
            boolean ok = value.optBoolean("ok");
            verdict = ok ? named(Verdict.class, value.optString("verdict"), Verdict.Ineligible) : Verdict.Ineligible;
            Problem parsed = Problem.parse(value.optString("problem"));
            problem = ok && parsed != null ? parsed : Problem.MagicIneligible;
        }
    }

    /** What one lookup has: the binding text ("" when none), the bound chat's state and the chats titled {@link #TITLE}. */
    public static final class LookupInput {
        public String binding = "";
        public BoundState boundState = BoundState.Unknown;
        public Candidate bound;
        public List<Candidate> visible = Collections.emptyList();
        /**
         * The account's main list and archive folder are both fully loaded by upstream's own dialog loaders,
         * unfiltered: the only proof that no magic chat exists, or that a bound chat missing from them is gone.
         */
        public boolean listsComplete;
        public boolean fresh;
        public long now;
    }

    public static final class Plan {
        public final Action action;
        public final PurpleSyncChatCore.Peer chat;
        public final Problem problem;
        public final boolean show, rebind;
        public final long retryAt;
        /** The wait is one Telegram asked for: a flood wait kept in the binding. */
        public final boolean telegram;
        Plan(JSONObject value) {
            boolean ok = value.optBoolean("ok");
            action = ok ? named(Action.class, value.optString("action"), Action.Invalid) : Action.Invalid;
            chat = PurpleSyncChatCore.decodedPeer(value.optString("chat", null));
            Problem parsed = Problem.parse(value.optString("problem"));
            problem = parsed != null ? parsed : Problem.None;
            show = value.optBoolean("show");
            rebind = value.optBoolean("rebind");
            retryAt = number(value, "retryAt");
            telegram = ok && value.optBoolean("telegram");
        }
    }

    /** A parsed binding; {@link #text} is its canonical file content. */
    public static final class Binding {
        public final String text;
        public final Mode mode;
        public final PurpleSyncChatCore.Peer chat;
        public final Problem problem;
        public final boolean problemShown;
        public final long verifiedAt;
        public final Migration migration;
        public final String login;
        /** The user chose to see the bound chat in Purple's chat lists. */
        public final boolean listed;
        Binding(JSONObject value) {
            text = value.optString("binding");
            mode = named(Mode.class, capitalized(value.optString("mode")), Mode.None);
            chat = PurpleSyncChatCore.decodedPeer(value.optString("chat", null));
            Problem parsed = Problem.parse(value.optString("problem"));
            problem = parsed != null ? parsed : Problem.None;
            problemShown = value.optBoolean("problemShown");
            verifiedAt = number(value, "verifiedAt");
            migration = named(Migration.class, capitalized(value.optString("migration")), Migration.None);
            login = value.optString("login");
            listed = value.optBoolean("listed");
        }
    }

    /**
     * A Verify read's outcome: the problem, or None, and the binding to store. A candidate other than the bound
     * chat is only {@link #rejected}: the binding keeps no problem and the next plan skips that chat.
     */
    public static final class Verified {
        public final Problem problem;
        public final String binding;
        public final boolean rejected;
        Verified(Problem problem, String binding, boolean rejected) { this.problem = problem; this.binding = binding; this.rejected = rejected; }
    }

    /** A classified failure; {@link #seconds} is Telegram's wait, for {@link ErrorKind#Wait} only. */
    public static final class Classified {
        public final ErrorKind kind;
        public final long seconds;
        Classified(ErrorKind kind, long seconds) { this.kind = kind; this.seconds = seconds; }
    }

    public static boolean isTitle(String title) {
        return title != null && call("title", json("title", title)).optBoolean("magic");
    }

    public static Check check(Candidate chat) {
        return new Check(chat == null ? new JSONObject() : call("check", json("chat", chat.json())));
    }

    public static Plan plan(LookupInput input) {
        if (input == null || input.binding == null || input.visible == null || (input.boundState == BoundState.Present && input.bound == null)) return new Plan(new JSONObject());
        JSONObject value = json("binding", input.binding, "boundState", input.boundState.name(),
            "visible", candidates(input.visible), "fresh", input.fresh, "listsComplete", input.listsComplete, "now", Long.toString(input.now));
        if (input.bound != null) put(value, "bound", input.bound.json());
        return new Plan(call("plan", value));
    }

    public static Verified verify(String binding, Candidate chat, long now) {
        if (chat == null) return null;
        JSONObject answer = call("verify", json("binding", text(binding), "chat", chat.json(), "now", Long.toString(now)));
        Problem problem = Problem.parse(answer.optString("problem"));
        return answer.optBoolean("ok") && problem != null ? new Verified(problem, answer.optString("binding"), answer.optBoolean("rejected")) : null;
    }

    /**
     * A verify of {@code channelId} found {@code problem} without a full-info reply (Telegram refused the read):
     * raised for the bound chat, only a rejection for any other candidate.
     */
    public static Verified verifyProblem(String binding, long channelId, Problem problem, long now) {
        if (problem == null || problem == Problem.None) return null;
        JSONObject answer = call("verifyProblem", json("binding", text(binding), "channelId", Long.toString(channelId), "problem", problem.code(),
            "now", Long.toString(now)));
        Problem parsed = Problem.parse(answer.optString("problem"));
        return answer.optBoolean("ok") && parsed != null ? new Verified(parsed, answer.optString("binding"), answer.optBoolean("rejected")) : null;
    }

    /**
     * Core's sorting of a failed request: {@code code} is the TL error code (negative for a transport failure),
     * {@code type} its text. A failed native call answers Transient, so it never raises a problem on its own.
     */
    public static Classified classify(int code, String type, boolean timeout) {
        JSONObject answer = call("classify", json("code", Integer.toString(code), "type", type != null ? type : "", "timeout", timeout));
        ErrorKind kind = answer.optBoolean("ok") ? named(ErrorKind.class, answer.optString("kind"), null) : null;
        return kind != null ? new Classified(kind, Math.max(0, number(answer, "seconds"))) : new Classified(ErrorKind.Transient, 0);
    }

    /** The problem a refused {@code method} raises: create_limit, chat_gone, or request_refused. */
    public static Problem refusalProblem(String method, String type) {
        JSONObject answer = call("refusalProblem", json("method", method != null ? method : "", "type", type != null ? type : ""));
        Problem parsed = answer.optBoolean("ok") ? Problem.parse(answer.optString("problem")) : null;
        return parsed != null && parsed != Problem.None ? parsed : Problem.RequestRefused;
    }

    /** Records that {@code method} must not be sent before {@code retryAt}; a recorded deadline only grows. */
    public static String recordWait(String binding, String method, long retryAt) {
        return edited(call("wait", json("binding", text(binding), "method", method, "retryAt", Long.toString(retryAt))));
    }

    /** The unix time before which {@code method} must not be sent, 0 when it may, or -1 when the binding is unreadable. */
    public static long waitUntil(String binding, String method, long now) {
        JSONObject answer = call("waitUntil", json("binding", text(binding), "method", method, "now", Long.toString(now)));
        return answer.optBoolean("ok") ? number(answer, "until") : -1;
    }

    public static String raise(String binding, Problem problem) {
        return problem == null ? null : edited(call("raise", json("binding", text(binding), "problem", problem.code())));
    }
    public static String clear(String binding) { return edited(call("clear", json("binding", text(binding)))); }
    public static String shown(String binding) { return edited(call("shown", json("binding", text(binding)))); }
    /** A manual check: candidates rejected by earlier verifications are considered again. */
    public static String readmit(String binding) { return edited(call("readmit", json("binding", text(binding)))); }
    /** Forgets the chat and its problem, keeping the waits, login and migration step. */
    public static String reset(String binding) { return edited(call("reset", json("binding", text(binding)))); }

    public static String manual(String binding, PurpleSyncChatCore.Peer chat) {
        return chat == null ? null : edited(call("edit", json("binding", text(binding), "set", json("mode", "manual", "chat", chat.token()))));
    }
    public static String createStep(String binding, CreateStep step, long at, PurpleSyncChatCore.Peer created) {
        if (step == null) return null;
        JSONObject set = json("createStep", step.name().toLowerCase(Locale.ROOT), "createAt", Long.toString(at));
        if (created != null) put(set, "created", created.token());
        return edited(call("edit", json("binding", text(binding), "set", set)));
    }
    public static String migration(String binding, Migration step) {
        return step == null ? null : edited(call("edit", json("binding", text(binding), "set", json("migration", step.name().toLowerCase(Locale.ROOT)))));
    }
    public static String listed(String binding, boolean listed) {
        return edited(call("edit", json("binding", text(binding), "set", json("listed", listed))));
    }
    public static String login(String binding, String login) {
        return login == null ? null : edited(call("edit", json("binding", text(binding), "set", json("login", login))));
    }

    /** The binding stored in {@code text} ("" for none), or null when it is not a valid binding. */
    public static Binding parse(String text) {
        JSONObject answer = call("parse", json("binding", text(text)));
        return answer.optBoolean("ok") ? new Binding(answer) : null;
    }

    /** {@code state} moved into the bound chat; a config stage left in the old chat is abandoned. */
    public static final class MovedState {
        public final byte[] state;
        public final boolean changed;
        public final long abandoned;
        MovedState(JSONObject value) {
            state = PurpleSyncAutoCore.decode(value.optString("state"));
            changed = value.optBoolean("changed");
            abandoned = number(value, "abandoned");
        }
    }
    /** The moved state, or null when core refuses the move (a pending library stage, an invalid state or chat). */
    public static MovedState moveState(byte[] state, PurpleSyncChatCore.Peer chat, long selfUserId) {
        if (state == null || chat == null) return null;
        JSONObject answer = call("moveState", json("state", PurpleSyncAutoCore.encode(state), "chat", chat.token(),
                "self", Long.toString(selfUserId)));
        return answer.optBoolean("ok") && !answer.optString("state").isEmpty() ? new MovedState(answer) : null;
    }
    /** The automatic record of account {@code key} carried across a move, or null when it is invalid. */
    public static byte[] moveAuto(byte[] record, String key, String localFingerprint) {
        if (record == null || key == null || localFingerprint == null) return null;
        JSONObject answer = call("moveAuto", json("record", PurpleSyncAutoCore.encode(record), "key", key,
                "fingerprint", localFingerprint));
        return answer.optBoolean("ok") && !answer.optString("record").isEmpty()
                ? PurpleSyncAutoCore.decode(answer.optString("record")) : null;
    }

    private static JSONArray candidates(List<Candidate> chats) {
        JSONArray result = new JSONArray();
        for (Candidate chat : chats) if (chat != null) result.put(chat.json());
        return result;
    }
    /** A null binding (a failed edit) stays null, so the bridge refuses it instead of reading it as no binding. */
    private static Object text(String binding) { return binding == null ? JSONObject.NULL : binding; }
    private static String edited(JSONObject answer) { return answer.optBoolean("ok") ? answer.optString("binding", null) : null; }
    private static long number(JSONObject value, String key) {
        try { return Long.parseLong(value.optString(key, "0")); } catch (NumberFormatException error) { return 0; }
    }
    private static String capitalized(String value) {
        return value == null || value.isEmpty() ? "" : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
    private static <E extends Enum<E>> E named(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value); } catch (RuntimeException error) { return fallback; }
    }
    private static JSONObject call(String operation, JSONObject input) {
        try { PurpleCore.ensureLoaded(); return new JSONObject(requestNative(operation, input.toString())); }
        catch (Exception | LinkageError error) { return new JSONObject(); }
    }
    static native String requestNative(String operation, String input);
    private PurpleSyncMagicCore() { }
}
