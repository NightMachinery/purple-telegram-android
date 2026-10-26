package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PurpleSyncChatCore {
    public enum Truth { Unknown, False, True }
    public enum Kind { Unknown, Self, Private, Bot, BasicGroup, Supergroup, Broadcast, Secret, ChannelDirectMessages }
    public enum Ownership { Unknown, Own, Other }
    public enum BroadcastOwnership { Unselected, CreatorAndSoleAdmin }
    public enum MarkStatus { NotMark, Supported, NewerVersion }
    public enum Refusal {
        None, UnknownKind, Secret, TopicRouting, Public, Protected, Discussion,
        AutoDelete, Paid, CannotSendFiles, SendAsOther, Migrated, SlowMode,
        BroadcastOwnershipUnselected, BroadcastNeedsCreatorAndSoleAdmin
    }
    public enum RecordPresence { Unknown, Empty, Present }
    public enum DirectRead { Unknown, Valid, MarkGone, ChatGone, Migrated }
    public enum SettingsHint { NotRun, Unknown, Absent, Found }
    public enum Action { Invalid, Unset, Use, VerifyMark, Follow, Fill, Searching, Lost, NewerMark, ReadWinnerInventory }
    public enum PeerKind { Self, User, Chat, Channel }

    public static final class Options {
        public final BroadcastOwnership broadcastOwnership;
        public Options(BroadcastOwnership value) { broadcastOwnership = Objects.requireNonNull(value); }
        public static Options restricted() { return new Options(BroadcastOwnership.CreatorAndSoleAdmin); }
    }

    public static final class Peer {
        public final PeerKind kind;
        public final long id;
        private Peer(PeerKind kind, long id) { this.kind = kind; this.id = id; }
        public static Peer self() { return new Peer(PeerKind.Self, 0); }
        public static Peer of(PeerKind kind, long id) {
            if (kind == null || kind == PeerKind.Self || id <= 0) throw new IllegalArgumentException("peer");
            return new Peer(kind, id);
        }
        public static Peer parse(String text, long selfUserId) {
            JSONObject answer = call("token", json("token", text, "selfUserId", Long.toString(selfUserId)));
            return answer.optBoolean("ok") ? decodedPeer(answer.optString("token", null)) : null;
        }
        public String token() { return kind == PeerKind.Self ? "self" : kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + id; }
        @Override public boolean equals(Object value) { return value instanceof Peer && ((Peer) value).kind == kind && ((Peer) value).id == id; }
        @Override public int hashCode() { return Objects.hash(kind, id); }
    }

    public static final class MessageFacts {
        public Kind kind = Kind.Unknown;
        public Truth regular = Truth.Unknown, forwarded = Truth.Unknown, viaBot = Truth.Unknown;
        public Truth scheduled = Truth.Unknown, hasTtl = Truth.Unknown, sentAsOther = Truth.Unknown;
        public Truth out = Truth.Unknown, creator = Truth.Unknown, soleAdmin = Truth.Unknown;
        public Peer sender;
        public long selfUserId;
    }

    public static final class ChatFacts {
        public Kind kind = Kind.Unknown;
        public boolean publicUsername, protectedContent, forum, linkedDiscussion, autoDelete, paidMessages;
        public boolean canSendFiles, anonymousAdmin, postsAsOther, migrated, slowMode, admin, creator, soleAdmin;
        public Long otherMembers, otherAdmins;
    }

    public static final class Readers {
        public final boolean warningRequired, privateRecipient, botOwner, populationUnknown;
        public final Long otherMembers, otherAdmins;
        Readers(JSONObject value) {
            warningRequired = value.optBoolean("warningRequired"); privateRecipient = value.optBoolean("privateRecipient");
            botOwner = value.optBoolean("botOwner"); populationUnknown = value.optBoolean("populationUnknown", true);
            otherMembers = optionalLong(value, "otherMembers"); otherAdmins = optionalLong(value, "otherAdmins");
        }
    }

    public static final class Eligibility {
        public final boolean known;
        public final Refusal refusal;
        public final Readers readers;
        Eligibility(JSONObject value) {
            known = value.optBoolean("ok"); refusal = named(Refusal.class, value.optString("refusal"), Refusal.UnknownKind);
            JSONObject raw = value.optJSONObject("readers"); readers = new Readers(raw != null ? raw : new JSONObject());
        }
        public boolean allowed() { return known && refusal == Refusal.None; }
    }

    public static final class ParsedMark {
        public final MarkStatus status;
        public final String version;
        ParsedMark(JSONObject value) { status = named(MarkStatus.class, value.optString("status"), MarkStatus.NotMark); version = value.optString("version", ""); }
    }

    public static final class Cache {
        public final Peer chat;
        public final int markId;
        public final long markDate;
        public Cache(Peer chat, int markId, long markDate) { this.chat = Objects.requireNonNull(chat); this.markId = markId; this.markDate = markDate; }
        JSONObject json() { return PurpleSyncChatCore.json("chat", chat.token(), "markId", markId, "markDate", Long.toString(markDate)); }
        @Override public boolean equals(Object value) { if (!(value instanceof Cache)) return false; Cache c = (Cache) value; return chat.equals(c.chat) && markId == c.markId && markDate == c.markDate; }
        @Override public int hashCode() { return Objects.hash(chat, markId, markDate); }
    }

    public static final class Mark {
        public final Cache location;
        public final MarkStatus status;
        public final Eligibility eligibility;
        public final RecordPresence records;
        public final boolean readable, freshlyRead;
        public Mark(Cache location, MarkStatus status, Eligibility eligibility, RecordPresence records, boolean readable, boolean freshlyRead) {
            this.location = Objects.requireNonNull(location); this.status = Objects.requireNonNull(status);
            this.eligibility = Objects.requireNonNull(eligibility); this.records = Objects.requireNonNull(records);
            this.readable = readable; this.freshlyRead = freshlyRead;
        }
    }

    public static final class ResolveInput {
        public final long selfUserId;
        public List<Mark> marks = Collections.emptyList();
        public Cache cache;
        public boolean settingsBound, readerWarningAccepted;
        public Peer settingsChat = Peer.self();
        public DirectRead cacheRead = DirectRead.Unknown;
        public SettingsHint settingsHint = SettingsHint.NotRun;
        public Eligibility cachedEligibility = unknownEligibility();
        public int searchRetriesCompleted;
        public ResolveInput(long selfUserId) { this.selfUserId = selfUserId; }
    }

    public static final class Resolution {
        public final Action action;
        public final Cache cache, winner;
        public final Peer readChat, source, olderAlternative;
        public final Readers readers;
        public final boolean needsReaderWarning, postingHeld;
        public final int retryAfterSeconds;
        Resolution(JSONObject value) {
            action = named(Action.class, value.optString("action"), Action.Invalid);
            cache = decodedCache(value.optJSONObject("cache")); winner = decodedCache(value.optJSONObject("winner"));
            readChat = decodedPeer(value.optString("readChat", null)); source = decodedPeer(value.optString("source", null));
            olderAlternative = decodedPeer(value.optString("olderAlternative", null));
            JSONObject raw = value.optJSONObject("readers"); readers = new Readers(raw != null ? raw : new JSONObject());
            needsReaderWarning = value.optBoolean("needsReaderWarning"); postingHeld = value.optBoolean("postingHeld", true);
            retryAfterSeconds = value.optInt("retryAfterSeconds");
        }
    }

    public static Ownership ownership(MessageFacts facts, Options options) {
        if (facts == null || options == null) return Ownership.Unknown;
        JSONObject input = json("kind", facts.kind.name(), "selfUserId", Long.toString(facts.selfUserId), "policy", options.broadcastOwnership.name());
        String[] names = { "regular", "forwarded", "viaBot", "scheduled", "hasTtl", "sentAsOther", "out", "creator", "soleAdmin" };
        Truth[] values = { facts.regular, facts.forwarded, facts.viaBot, facts.scheduled, facts.hasTtl, facts.sentAsOther, facts.out, facts.creator, facts.soleAdmin };
        for (int i = 0; i != names.length; ++i) if (values[i] != null && values[i] != Truth.Unknown) put(input, names[i], values[i] == Truth.True);
        if (facts.sender != null) put(input, "sender", facts.sender.token());
        return named(Ownership.class, call("ownership", input).optString("ownership"), Ownership.Unknown);
    }

    public static Eligibility eligibility(ChatFacts facts, Options options) {
        if (facts == null || options == null) return unknownEligibility();
        JSONObject input = json("kind", facts.kind.name(), "policy", options.broadcastOwnership.name());
        String[] names = { "publicUsername", "protectedContent", "forum", "linkedDiscussion", "autoDelete", "paidMessages", "canSendFiles", "anonymousAdmin", "postsAsOther", "migrated", "slowMode", "admin", "creator", "soleAdmin" };
        boolean[] values = { facts.publicUsername, facts.protectedContent, facts.forum, facts.linkedDiscussion, facts.autoDelete, facts.paidMessages, facts.canSendFiles, facts.anonymousAdmin, facts.postsAsOther, facts.migrated, facts.slowMode, facts.admin, facts.creator, facts.soleAdmin };
        for (int i = 0; i != names.length; ++i) put(input, names[i], values[i]);
        if (facts.otherMembers != null) put(input, "otherMembers", facts.otherMembers.toString());
        if (facts.otherAdmins != null) put(input, "otherAdmins", facts.otherAdmins.toString());
        return new Eligibility(call("eligibility", input));
    }

    public static ParsedMark parseMark(String text, boolean hasMedia, Ownership ownership) {
        return new ParsedMark(call("parse", json("text", text, "hasMedia", hasMedia, "ownership", ownership != null ? ownership.name() : "Unknown")));
    }

    public static Resolution resolve(ResolveInput input) {
        if (input == null || input.marks == null || input.marks.size() > 4096) return new Resolution(new JSONObject());
        JSONObject value = json("selfUserId", Long.toString(input.selfUserId), "settingsBound", input.settingsBound,
            "settingsChat", input.settingsChat != null ? input.settingsChat.token() : "", "cacheRead", input.cacheRead.name(),
            "settingsHint", input.settingsHint.name(), "readerWarningAccepted", input.readerWarningAccepted,
            "searchRetriesCompleted", input.searchRetriesCompleted, "cachedEligibility", eligibilityJson(input.cachedEligibility));
        if (input.cache != null) put(value, "cache", input.cache.json());
        JSONArray marks = new JSONArray();
        for (Mark mark : input.marks) {
            if (mark == null) return new Resolution(new JSONObject());
            marks.put(json("location", mark.location.json(), "status", mark.status.name(), "eligibility", eligibilityJson(mark.eligibility),
                "records", mark.records.name(), "readable", mark.readable, "freshlyRead", mark.freshlyRead));
        }
        put(value, "marks", marks);
        return new Resolution(call("resolve", value));
    }

    public static boolean outranks(Cache first, Cache second) {
        return first != null && second != null && call("outranks", json("first", first.json(), "second", second.json())).optBoolean("outranks");
    }

    public static Eligibility unknownEligibility() { return new Eligibility(new JSONObject()); }
    static JSONObject eligibilityJson(Eligibility value) {
        if (value == null || !value.known) return json("refusal", "UnknownKind", "readers", new JSONObject());
        Readers readers = value.readers;
        JSONObject raw = json("warningRequired", readers.warningRequired, "privateRecipient", readers.privateRecipient,
            "botOwner", readers.botOwner, "populationUnknown", readers.populationUnknown);
        if (readers.otherMembers != null) put(raw, "otherMembers", readers.otherMembers.toString());
        if (readers.otherAdmins != null) put(raw, "otherAdmins", readers.otherAdmins.toString());
        return json("refusal", value.refusal.name(), "readers", raw);
    }
    static JSONObject json(Object... values) {
        JSONObject result = new JSONObject(); for (int i = 0; i < values.length; i += 2) put(result, (String) values[i], values[i + 1]); return result;
    }
    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (org.json.JSONException error) { throw new IllegalArgumentException("sync chat input", error); }
    }
    static JSONObject call(String operation, JSONObject input) {
        try { PurpleCore.ensureLoaded(); return new JSONObject(requestNative(operation, input.toString())); }
        catch (Exception | LinkageError error) { return new JSONObject(); }
    }
    private static Peer decodedPeer(String token) {
        if (token == null || token.isEmpty()) return null;
        if (token.equals("self")) return Peer.self();
        String[] parts = token.split(":", -1); if (parts.length != 2) return null;
        try { PeerKind kind = PeerKind.valueOf(parts[0].substring(0, 1).toUpperCase(java.util.Locale.ROOT) + parts[0].substring(1)); return Peer.of(kind, Long.parseLong(parts[1])); }
        catch (RuntimeException error) { return null; }
    }
    private static Cache decodedCache(JSONObject value) {
        if (value == null) return null; Peer chat = decodedPeer(value.optString("chat", null)); Long date = optionalLong(value, "markDate");
        return chat != null && date != null ? new Cache(chat, value.optInt("markId", -1), date) : null;
    }
    private static Long optionalLong(JSONObject value, String key) {
        try { return value.has(key) ? Long.valueOf(value.getString(key)) : null; } catch (Exception error) { return null; }
    }
    private static <E extends Enum<E>> E named(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value); } catch (RuntimeException error) { return fallback; }
    }
    static native String requestNative(String operation, String input);
    private PurpleSyncChatCore() { }
}
