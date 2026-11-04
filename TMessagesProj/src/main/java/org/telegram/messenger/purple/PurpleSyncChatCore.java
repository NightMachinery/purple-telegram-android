package org.telegram.messenger.purple;

import org.json.JSONObject;

import java.util.Objects;

public final class PurpleSyncChatCore {
    public enum Truth { Unknown, False, True }
    public enum Kind { Unknown, Self, Private, Bot, BasicGroup, Supergroup, Broadcast, Secret, ChannelDirectMessages }
    public enum Ownership { Unknown, Own, Other }
    public enum BroadcastOwnership { Unselected, CreatorAndSoleAdmin }
    public enum Refusal {
        None, UnknownKind, Secret, TopicRouting, Public, Protected, Discussion,
        AutoDelete, Paid, CannotSendFiles, SendAsOther, Migrated, SlowMode,
        BroadcastOwnershipUnselected, BroadcastNeedsCreatorAndSoleAdmin
    }
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

    public static final class Eligibility {
        public final boolean known;
        public final Refusal refusal;
        Eligibility(JSONObject value) {
            known = value.optBoolean("ok"); refusal = named(Refusal.class, value.optString("refusal"), Refusal.UnknownKind);
        }
        public boolean allowed() { return known && refusal == Refusal.None; }
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

    public static Eligibility unknownEligibility() { return new Eligibility(new JSONObject()); }
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
    static Peer decodedPeer(String token) {
        if (token == null || token.isEmpty()) return null;
        if (token.equals("self")) return Peer.self();
        String[] parts = token.split(":", -1); if (parts.length != 2) return null;
        try { PeerKind kind = PeerKind.valueOf(parts[0].substring(0, 1).toUpperCase(java.util.Locale.ROOT) + parts[0].substring(1)); return Peer.of(kind, Long.parseLong(parts[1])); }
        catch (RuntimeException error) { return null; }
    }
    private static <E extends Enum<E>> E named(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value); } catch (RuntimeException error) { return fallback; }
    }
    static native String requestNative(String operation, String input);
    private PurpleSyncChatCore() { }
}
