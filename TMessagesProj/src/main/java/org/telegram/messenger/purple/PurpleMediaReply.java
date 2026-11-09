package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public final class PurpleMediaReply {
    private static final Map<String, State> states = new HashMap<>();
    private static final long[] cutoffs = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile long revision = 1;
    private static int pending;
    private static final class State {
        long lifetime;
        String journal;
        boolean failed;
        int inFlight;
        ConnectionsManager connection;
        PurpleAccountFiles.Identity identity;
        Reason reason = Reason.None;
        int reserved = -1;
        boolean loaded;
        final Map<Integer, Long> requests = new HashMap<>();
    }
    public enum Reason { None, JournalFull, JournalUnreadable, StorageRefused, Permission, Flood, ApprovalRequired, Uncertain, Cancelled }
    public static final class Status {
        public final Reason reason;
        public final int reserved, inFlight;
        public final boolean failed, disabled;
        Status(State state, boolean disabled) {
            this.disabled = disabled;
            reason = state == null ? Reason.None : state.reason;
            reserved = state == null ? -1 : state.reserved;
            inFlight = state == null ? 0 : state.inFlight;
            failed = state != null && state.failed;
        }
    }
    public static Status status(int account) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(account);
        JSONObject policy = policy(account);
        State state = identity == null ? null : states.get(identity.key);
        if (state != null && state.lifetime != identity.lifetime) state = null;
        return new Status(state, policy != null && !policy.optBoolean("enabled"));
    }
    private static Reason failure(TLRPC.TL_error error) {
        String type = error.text == null ? "" : error.text;
        if (type.contains("PAYMENT") || type.contains("STARS")) return Reason.ApprovalRequired;
        if (error.code == 420 || type.startsWith("FLOOD_") || type.startsWith("SLOWMODE_")) return Reason.Flood;
        if (type.contains("FORBIDDEN") || type.contains("BANNED") || type.contains("RESTRICTED")) return Reason.Permission;
        return Reason.Uncertain;
    }
    static native int journalCountNative(String journal, String account);
    static native String policyNative(long handle);
    static native String resolvedNative(long handle, String peer, int kind);
    public static JSONObject resolved(int account, String peer, int kind) {
        PurpleAccountSettings.Effective settings = PurpleAccountSettings.forAccount(account);
        if (settings == null || !settings.current()) return null;
        try {
            JSONObject result = new JSONObject(resolvedNative(settings.handle, peer, kind));
            return settings.current() ? result : null;
        } catch (Exception | UnsatisfiedLinkError e) { return null; }
    }
    static native String planNative(long handle, String account, String peer, int kind,
            int id, int topic, long date, int media, boolean own, boolean ack, long cutoff, long revision);
    static native String reserveNative(String previous, String candidate);

    public static synchronized void invalidate() {
        final long revokedBefore = ++revision;
        for (int slot = 0; slot < cutoffs.length; ++slot) {
            cutoffs[slot] = UserConfig.getInstance(slot).isClientActivated()
                    ? ConnectionsManager.getInstance(slot).getCurrentTime() : 0;
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (State state : states.values()) {
                java.util.Iterator<Map.Entry<Integer, Long>> iterator = state.requests.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Integer, Long> request = iterator.next();
                    if (request.getValue() >= revokedBefore) continue;
                    final int token = request.getKey();
                    iterator.remove();
                    --state.inFlight;
                    if (!state.failed) state.reason = Reason.Cancelled;
                    state.connection.cancelRequest(token, true);
                }
            }
            states.entrySet().removeIf(entry -> !entry.getValue().identity.current()
                    && entry.getValue().requests.isEmpty());
        });
    }
    public static JSONObject policy(int account) {
        PurpleAccountSettings.Effective settings = PurpleAccountSettings.forAccount(account);
        if (settings == null || !settings.current()) return null;
        try {
            JSONObject result = new JSONObject(policyNative(settings.handle));
            return settings.current() ? result : null;
        } catch (Exception | UnsatisfiedLinkError e) { return null; }
    }
    public static void observe(int account, TLRPC.Message raw, boolean ack, boolean history) {
        if (history || raw == null || raw.id <= 0 || raw.send_state != 0 || raw.from_scheduled
                || raw.quick_reply_shortcut_id != 0 || raw.quick_reply_shortcut != null
                || raw.peer_id == null || raw.action != null && !(raw.action instanceof TLRPC.TL_messageActionEmpty)) return;
        final int media = MessageObject.isVoiceMessage(raw) ? 1 : MessageObject.isRoundVideoMessage(raw) ? 2 : 0;
        if (media == 0 || ack && !raw.out
                || raw.date > ConnectionsManager.getInstance(account).getCurrentTime()) return;
        final PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(account);
        if (identity == null) return;
        final long did = MessageObject.getDialogId(raw);
        if (did == 0) return;
        final String peer = raw.peer_id.channel_id != 0 ? "channel:" + raw.peer_id.channel_id
                : raw.peer_id.chat_id != 0 ? "chat:" + raw.peer_id.chat_id : "user:" + did;
        final int id = raw.id, date = raw.date;
        final int topic = raw.reply_to != null && raw.reply_to.reply_to_top_id != 0
                ? raw.reply_to.reply_to_top_id : raw.reply_to != null && raw.reply_to.forum_topic
                ? raw.reply_to.reply_to_msg_id : 0;
        final boolean own = raw.out;
        final long capturedRevision;
        synchronized (PurpleMediaReply.class) {
            if (pending >= 32) return;
            ++pending;
            capturedRevision = revision;
        }
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (PurpleMediaReply.class) {
                --pending;
                if (capturedRevision != revision) return;
            }
            if (identity.current()) dispatch(identity, peer, did, id, topic, date, media, own, ack, capturedRevision);
        });
    }
    private static State ensureState(PurpleAccountFiles.Identity identity) {
        State state = states.get(identity.key);
        if (state == null || state.lifetime != identity.lifetime) {
            state = new State(); state.lifetime = identity.lifetime; state.identity = identity;
            state.connection = ConnectionsManager.getInstance(identity.slot);
            states.put(identity.key, state);
        }
        return state;
    }
    private static void dispatch(PurpleAccountFiles.Identity identity, String peer, long did,
            int id, int topic, int date, int media, boolean own, boolean ack, long capturedRevision) {
        if (!PurpleAccountOwner.ready() || !identity.current()) return;
        final int account = identity.slot;
        final long cutoff;
        synchronized (PurpleMediaReply.class) {
            if (capturedRevision != revision) return;
            cutoff = cutoffs[account];
            if (cutoff == 0) { cutoffs[account] = ConnectionsManager.getInstance(account).getCurrentTime(); return; }
        }
        final MessagesController controller = MessagesController.getInstance(account);
        int kind = 0;
        if (did < 0) {
            TLRPC.Chat chat = controller.getChat(-did);
            if (chat == null || !ChatObject.canSendMessages(chat)) {
                State state = ensureState(identity);
                if (!state.failed) state.reason = Reason.Permission;
                return;
            }
            kind = ChatObject.isChannel(chat) && !chat.megagroup ? 2 : 1;
        }
        final PurpleAccountSettings.Effective settings = PurpleAccountSettings.forAccount(account);
        if (settings == null || !settings.current()) return;
        try {
            String candidate = planNative(settings.handle, identity.key, peer, kind, id, topic, date,
                    media, own, ack, cutoff, capturedRevision);
            if (candidate == null || !settings.current() || capturedRevision != revision) return;
            State state = ensureState(identity);
            if (state.failed || state.inFlight >= 8) return;
            PurpleAccountFiles files = PurpleAccountFiles.resolve(identity.key, PurpleAccountOwner.owner());
            if (files == null || !files.directory.isDirectory() || Files.isSymbolicLink(files.directory.toPath())) {
                state.failed = true; state.reason = Reason.StorageRefused; return;
            }
            File journalFile = new File(files.directory, "media-reply-journal.json");
            if (!state.loaded) {
                try {
                    byte[] previous = PurpleMediaReplyStore.read(journalFile);
                    state.journal = previous == null ? null : new String(previous, StandardCharsets.UTF_8);
                    state.reserved = journalCountNative(state.journal, identity.key);
                    if (state.reserved < 0) throw new java.io.IOException("InvalidJournal");
                    state.loaded = true;
                } catch (Exception e) { state.failed = true; state.reason = Reason.JournalUnreadable; return; }
            }
            if (state.reserved >= 4096) { state.failed = true; state.reason = Reason.JournalFull; return; }
            String reserved = reserveNative(state.journal, candidate);
            if (reserved == null) return;
            byte[] reservedBytes = reserved.getBytes(StandardCharsets.UTF_8);
            try {
                PurpleMediaReplyStore.replace(journalFile,
                        state.journal == null ? null : state.journal.getBytes(StandardCharsets.UTF_8), reservedBytes);
                state.journal = reserved;
                ++state.reserved;
            } catch (Exception e) { state.failed = true; state.reason = Reason.StorageRefused; return; }
            if (!settings.current() || capturedRevision != revision || !identity.current()) return;
            JSONObject plan = new JSONObject(candidate);
            JSONObject action = plan.getJSONArray("reserved").getJSONObject(0);
            TLRPC.TL_messages_sendMessage request = new TLRPC.TL_messages_sendMessage();
            request.peer = controller.getInputPeer(did);
            request.message = action.getString("text");
            request.random_id = Long.parseLong(plan.getString("randomId"));
            request.reply_to = new TLRPC.TL_inputReplyToMessage();
            request.reply_to.reply_to_msg_id = id;
            if (topic != 0) { request.reply_to.flags |= 1; request.reply_to.top_msg_id = topic; }
            request.flags |= 1;
            request.no_webpage = true;
            State sending = state;
            synchronized (PurpleAccountFiles.class) {
                synchronized (PurpleAccountSettings.class) {
                    if (!settings.current() || capturedRevision != revision || !identity.current()) return;
                    ++sending.inFlight;
                    final int[] token = {0};
                    token[0] = sending.connection.sendRequest(request, (response, error) -> {
                        AndroidUtilities.runOnUIThread(() -> {
                            if (sending.requests.remove(token[0]) != null) --sending.inFlight;
                            if (identity.current() && capturedRevision == revision && states.get(identity.key) == sending && !sending.failed) {
                                sending.reason = error != null ? failure(error) : response instanceof TLRPC.Updates ? Reason.None : Reason.Uncertain;
                            }
                            if (identity.current() && response instanceof TLRPC.Updates) {
                                org.telegram.messenger.Utilities.stageQueue.postRunnable(() -> {
                                    if (identity.current()) controller.processUpdates((TLRPC.Updates) response, false);
                                });
                            }
                        });
                    }, ConnectionsManager.RequestFlagFailOnServerErrors);
                    sending.requests.put(token[0], capturedRevision);
                }
            }
        } catch (Exception | UnsatisfiedLinkError e) {
            State state = states.get(identity.key);
            if (state != null) { state.failed = true; state.reason = Reason.StorageRefused; }
        }
    }
    private PurpleMediaReply() { }
}
