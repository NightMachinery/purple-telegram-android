/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.json.JSONException;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Privacy-safe fixed-vocabulary diagnostics for {@link PurpleMediaReply} admission, ACK and dispatch stages. */
public final class PurpleMediaReplyDiagnostics {
    private static final String AREA = "media_reply";
    private static final int PROCESS_RUN = initRun();
    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);

    private PurpleMediaReplyDiagnostics() {
    }

    private static int initRun() {
        try {
            int raw = new SecureRandom().nextInt() ^ (int) System.nanoTime()
                    ^ System.identityHashCode(PurpleMediaReplyDiagnostics.class);
            return Math.floorMod(raw, 9999) + 1;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    /** Allocates a bounded non-identifying trigger sequence in {@code 1..9999}. */
    static int nextSeq() {
        return SEQUENCE.updateAndGet(v -> v >= 9999 || v < 1 ? 1 : v + 1);
    }

    static int runId() {
        return PROCESS_RUN;
    }

    static boolean isMediaMessage(TLRPC.Message message) {
        if (message == null) return false;
        try {
            return MessageObject.isVoiceMessage(message) || MessageObject.isRoundVideoMessage(message);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String mediaCode(TLRPC.Message message) {
        if (message == null) return "none";
        try {
            if (MessageObject.isVoiceMessage(message)) return "voice";
            if (MessageObject.isRoundVideoMessage(message)) return "round";
            return message.media != null ? "other" : "none";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    static String mediaName(int media) {
        return media == 1 ? "voice" : media == 2 ? "round" : "none";
    }

    static String kindName(int kind) {
        return kind == 0 ? "private" : kind == 2 ? "channel" : kind == 1 ? "group" : "unknown";
    }

    static String kindFromMessage(TLRPC.Message message) {
        if (message == null || message.peer_id == null) return "unknown";
        if (message.peer_id.channel_id != 0) return "channel";
        if (message.peer_id.chat_id != 0) return "group";
        if (message.peer_id.user_id != 0 || message.dialog_id > 0) return "private";
        if (message.dialog_id < 0) return "group";
        return "unknown";
    }

    static String kindFromDidPeer(long did, String peer) {
        if (did > 0) return "private";
        if (peer != null && peer.startsWith("channel:")) return "channel";
        if (did < 0) return "group";
        return "unknown";
    }

    static String peerKind(TLRPC.Message message) {
        if (message == null || message.peer_id == null) return "none";
        if (message.peer_id.channel_id != 0) return "channel";
        if (message.peer_id.chat_id != 0) return "chat";
        if (message.peer_id.user_id != 0) return "user";
        return "none";
    }

    static boolean hasTopic(TLRPC.Message message) {
        return message != null && message.reply_to != null
                && (message.reply_to.reply_to_top_id != 0 || message.reply_to.forum_topic);
    }

    static String sendStateCode(int sendState) {
        switch (sendState) {
            case 0: return "sent";
            case 1: return "sending";
            case 2: return "send_error";
            case 3: return "editing";
            default: return "other";
        }
    }

    static String deltaSign(long delta) {
        return delta < 0 ? "neg" : delta > 0 ? "pos" : "zero";
    }

    static int clampedAbsDelta(long delta) {
        if (delta == Long.MIN_VALUE) return 99999;
        long abs = Math.abs(delta);
        return abs > 99999L ? 99999 : (int) abs;
    }

    static int clampErrorCode(int code) {
        if (code < 0) return -1;
        return Math.min(code, 99999);
    }

    static String mapErrorCategory(TLRPC.TL_error error) {
        if (error == null) return "none";
        String type = error.text == null ? "" : error.text;
        if (type.contains("PAYMENT") || type.contains("STARS")) return "payment";
        if (type.startsWith("SLOWMODE_")) return "slowmode";
        if (error.code == 420 || type.startsWith("FLOOD_")) return "flood";
        if (type.contains("FORBIDDEN")) return "forbidden";
        if (type.contains("BANNED")) return "banned";
        if (type.contains("RESTRICTED")) return "restricted";
        return "other";
    }

    static String reasonName(PurpleMediaReply.Reason reason) {
        if (reason == null) return "none";
        switch (reason) {
            case None: return "none";
            case ApprovalRequired: return "payment";
            case Flood: return "flood";
            case Permission: return "permission";
            case StorageRefused: return "storage_refused";
            case JournalUnreadable: return "journal_unreadable";
            case JournalFull: return "journal_full";
            case Cancelled: return "cancelled";
            case Uncertain:
            default: return "uncertain";
        }
    }

    static String exceptionCategory(Throwable error) {
        if (error instanceof JSONException) return "json";
        if (error instanceof IOException) return "io";
        if (error instanceof IllegalArgumentException) return "illegal_argument";
        if (error instanceof IllegalStateException) return "illegal_state";
        if (error instanceof SecurityException) return "security";
        if (error instanceof UnsatisfiedLinkError) return "unsatisfied_link";
        return "unexpected";
    }

    private static void emit(String event, int seq, PurpleDiagLogStore.Field... fields) {
        try {
            ArrayList<PurpleDiagLogStore.Field> out = new ArrayList<>(fields.length + 2);
            out.add(PurpleDiagLog.count("run", PROCESS_RUN));
            if (seq > 0) out.add(PurpleDiagLog.count("seq", seq));
            for (PurpleDiagLogStore.Field field : fields) {
                if (field != null) out.add(field);
            }
            PurpleDiagLog.event(AREA, event, out.toArray(new PurpleDiagLogStore.Field[0]));
        } catch (Throwable ignored) {
        }
    }

    private static String updatesType(Object response) {
        if (response == null) return "null";
        if (response instanceof TLRPC.TL_updateShortSentMessage) return "short_sent";
        if (response instanceof TLRPC.Updates) return "updates";
        return "other";
    }

    private static String shortSentMedia(TLRPC.TL_updateShortSentMessage res) {
        if (res == null || res.media == null) return "none";
        try {
            TLRPC.Message tmp = new TLRPC.Message();
            tmp.media = res.media;
            return mediaCode(tmp);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static TLRPC.Message extractUpdateMessage(TLRPC.Update update) {
        if (update instanceof TL_update.TL_updateNewMessage) {
            return ((TL_update.TL_updateNewMessage) update).message;
        }
        if (update instanceof TL_update.TL_updateNewChannelMessage) {
            return ((TL_update.TL_updateNewChannelMessage) update).message;
        }
        if (update instanceof TL_update.TL_updateNewScheduledMessage) {
            return ((TL_update.TL_updateNewScheduledMessage) update).message;
        }
        if (update instanceof TL_update.TL_updateQuickReplyMessage) {
            return ((TL_update.TL_updateQuickReplyMessage) update).message;
        }
        return null;
    }

    private static String updateSourceCode(Object update) {
        if (update instanceof TL_update.TL_updateNewMessage) return "new_message";
        if (update instanceof TL_update.TL_updateNewChannelMessage) return "channel_message";
        if (update instanceof TL_update.TL_updateNewScheduledMessage) return "scheduled_message";
        if (update instanceof TL_update.TL_updateQuickReplyMessage) return "quick_reply";
        return "other";
    }

    private static void countUpdates(Object response, int[] counts, String[] firstSrvMedia) {
        if (!(response instanceof TLRPC.Updates)) return;
        ArrayList<TLRPC.Update> updates = ((TLRPC.Updates) response).updates;
        if (updates == null) return;
        for (int i = 0; i < updates.size(); ++i) {
            TLRPC.Update update = updates.get(i);
            if (update instanceof TL_update.TL_updateMessageID) {
                ++counts[0];
            } else {
                TLRPC.Message msg = extractUpdateMessage(update);
                if (msg != null) {
                    ++counts[1];
                    if (isMediaMessage(msg)) {
                        ++counts[2];
                        if (firstSrvMedia[0] == null) firstSrvMedia[0] = mediaCode(msg);
                    }
                }
            }
        }
    }

    /** Correlated state for a single forward, multi-media, or single-message ACK callback. */
    public static final class AckTrace {
        public final int account;
        public final String source;
        public final int seq;
        private final String initialSrvMedia;
        private boolean ended;

        AckTrace(int account, String source, int seq, String initialSrvMedia) {
            this.account = account;
            this.source = source;
            this.seq = seq;
            this.initialSrvMedia = initialSrvMedia == null ? "none" : initialSrvMedia;
        }

        public void forwardMiss(TLRPC.Message serverMsg, String reasonCode) {
            try {
                emit("ack_skip", seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.reason(reasonCode),
                        PurpleDiagLog.code("srv_media", mediaCode(serverMsg)),
                        PurpleDiagLog.flag("srv_id_pos", serverMsg != null && serverMsg.id > 0),
                        PurpleDiagLog.flag("srv_own", serverMsg != null && serverMsg.out),
                        PurpleDiagLog.code("kind", kindFromMessage(serverMsg)));
            } catch (Throwable ignored) {
            }
        }

        public void forwardMatched(TLRPC.Message localMsg, TLRPC.Message serverMsg, boolean scheduled, boolean currentSchedule) {
            try {
                boolean mismatch = scheduled != currentSchedule;
                String event = mismatch ? "ack_skip" : "ack_step";
                emit(event, seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.reason(mismatch ? "schedule_mismatch" : "matched"),
                        PurpleDiagLog.code("media", mediaCode(localMsg)),
                        PurpleDiagLog.code("srv_media", mediaCode(serverMsg)),
                        PurpleDiagLog.flag("id_pos", localMsg != null && localMsg.id > 0),
                        PurpleDiagLog.flag("srv_id_pos", serverMsg != null && serverMsg.id > 0),
                        PurpleDiagLog.flag("own", localMsg != null && localMsg.out),
                        PurpleDiagLog.flag("srv_own", serverMsg != null && serverMsg.out),
                        PurpleDiagLog.flag("sched_mismatch", mismatch),
                        PurpleDiagLog.code("kind", kindFromMessage(serverMsg != null ? serverMsg : localMsg)));
            } catch (Throwable ignored) {
            }
        }

        public void forwardEnd(int sentCount, TLRPC.Updates updates, List<TLRPC.Message> remainingLocal) {
            try {
                if (ended) return;
                ended = true;
                int remUpdates = updates != null && updates.updates != null ? updates.updates.size() : 0;
                int remLocal = remainingLocal != null ? remainingLocal.size() : 0;
                emit("ack_end", seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.code("outcome", sentCount > 0 ? "ok" : "rejected"),
                        PurpleDiagLog.count("matched_cnt", sentCount),
                        PurpleDiagLog.count("rem_cnt", remUpdates),
                        PurpleDiagLog.count("unmatched_cnt", remLocal));
            } catch (Throwable ignored) {
            }
        }

        public void multiMiss(TLRPC.Message localMsg, String reasonCode) {
            try {
                emit("ack_skip", seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.reason(reasonCode),
                        PurpleDiagLog.code("media", mediaCode(localMsg)),
                        PurpleDiagLog.flag("id_pos", localMsg != null && localMsg.id > 0),
                        PurpleDiagLog.flag("own", localMsg != null && localMsg.out),
                        PurpleDiagLog.code("kind", kindFromMessage(localMsg)));
            } catch (Throwable ignored) {
            }
        }

        public void multiMatched(TLRPC.Message localMsg, TLRPC.Message serverMsg, boolean scheduled, boolean currentSchedule) {
            try {
                if (!isMediaMessage(localMsg) && !isMediaMessage(serverMsg)) return;
                emit("ack_step", seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.reason("matched"),
                        PurpleDiagLog.code("media", mediaCode(localMsg)),
                        PurpleDiagLog.code("srv_media", mediaCode(serverMsg)),
                        PurpleDiagLog.flag("id_pos", localMsg != null && localMsg.id > 0),
                        PurpleDiagLog.flag("own", localMsg != null && localMsg.out),
                        PurpleDiagLog.flag("sched_mismatch", scheduled != currentSchedule),
                        PurpleDiagLog.code("kind", kindFromMessage(localMsg)));
            } catch (Throwable ignored) {
            }
        }

        public void multiEnd(int sentCount, List<?> remainingUpdates, boolean isSentError) {
            try {
                if (ended) return;
                ended = true;
                int rem = remainingUpdates != null ? remainingUpdates.size() : 0;
                emit("ack_end", seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.code("outcome", isSentError ? "error" : sentCount > 0 ? "ok" : "rejected"),
                        PurpleDiagLog.flag("has_error", isSentError),
                        PurpleDiagLog.count("matched_cnt", sentCount),
                        PurpleDiagLog.count("rem_cnt", rem));
            } catch (Throwable ignored) {
            }
        }

        public void singlePrepared(TLRPC.Message localMsg, Object response, boolean scheduled, boolean currentSchedule) {
            try {
                boolean mismatch = scheduled != currentSchedule;
                int rem = response instanceof TLRPC.Updates && ((TLRPC.Updates) response).updates != null
                        ? ((TLRPC.Updates) response).updates.size() : 0;
                emit(mismatch ? "ack_skip" : "ack_step", seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.reason(mismatch ? "schedule_mismatch" : "matched"),
                        PurpleDiagLog.code("updates_type", updatesType(response)),
                        PurpleDiagLog.code("media", mediaCode(localMsg)),
                        PurpleDiagLog.code("srv_media", initialSrvMedia),
                        PurpleDiagLog.flag("id_pos", localMsg != null && localMsg.id > 0),
                        PurpleDiagLog.code("send_state", sendStateCode(localMsg != null ? localMsg.send_state : -1)),
                        PurpleDiagLog.flag("own", localMsg != null && localMsg.out),
                        PurpleDiagLog.flag("sched_mismatch", mismatch),
                        PurpleDiagLog.count("rem_cnt", rem),
                        PurpleDiagLog.code("kind", kindFromMessage(localMsg)));
            } catch (Throwable ignored) {
            }
        }

        public void singleError(TLRPC.TL_error error, Object response) {
            try {
                if (ended) return;
                ended = true;
                int rem = response instanceof TLRPC.Updates && ((TLRPC.Updates) response).updates != null
                        ? ((TLRPC.Updates) response).updates.size() : 0;
                emit("ack_end", seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.code("outcome", "error"),
                        PurpleDiagLog.reason(error != null ? "send_error" : "message_null"),
                        PurpleDiagLog.code("updates_type", updatesType(response)),
                        PurpleDiagLog.code("srv_media", initialSrvMedia),
                        PurpleDiagLog.count("rem_cnt", rem),
                        PurpleDiagLog.code("rpc_err", mapErrorCategory(error)),
                        PurpleDiagLog.count("error_code", error != null ? clampErrorCode(error.code) : 0));
            } catch (Throwable ignored) {
            }
        }

        public void errorEnd(TLRPC.TL_error error) {
            try {
                if (ended) return;
                ended = true;
                emit("ack_end", seq,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("source", source),
                        PurpleDiagLog.code("outcome", "error"),
                        PurpleDiagLog.reason("send_error"),
                        PurpleDiagLog.code("rpc_err", mapErrorCategory(error)),
                        PurpleDiagLog.count("error_code", error != null ? clampErrorCode(error.code) : 0));
            } catch (Throwable ignored) {
            }
        }
    }

    public static AckTrace forwardAckEnter(int account, Object response, TLRPC.TL_error error, List<TLRPC.Message> newMsgObjArr) {
        try {
            int localMedia = 0;
            if (newMsgObjArr != null) {
                for (int i = 0; i < newMsgObjArr.size(); ++i) {
                    if (isMediaMessage(newMsgObjArr.get(i))) ++localMedia;
                }
            }
            int[] counts = new int[3];
            String[] firstSrvMedia = new String[1];
            countUpdates(response, counts, firstSrvMedia);
            if (localMedia == 0 && counts[2] == 0) return null;
            int seq = nextSeq();
            emit("ack_enter", seq,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("source", "forward"),
                    PurpleDiagLog.flag("has_error", error != null),
                    PurpleDiagLog.code("updates_type", updatesType(response)),
                    PurpleDiagLog.count("msg_id_cnt", counts[0]),
                    PurpleDiagLog.count("new_msg_cnt", counts[1]),
                    PurpleDiagLog.count("media_local_cnt", localMedia),
                    PurpleDiagLog.count("media_server_cnt", counts[2]),
                    PurpleDiagLog.code("rpc_err", mapErrorCategory(error)));
            return new AckTrace(account, "forward", seq, firstSrvMedia[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static AckTrace multiAckEnter(int account, Object response, TLRPC.TL_error error, List<MessageObject> msgObjs, boolean scheduled) {
        try {
            int localMedia = 0;
            if (msgObjs != null) {
                for (int i = 0; i < msgObjs.size(); ++i) {
                    MessageObject obj = msgObjs.get(i);
                    if (obj != null && isMediaMessage(obj.messageOwner)) ++localMedia;
                }
            }
            int[] counts = new int[3];
            String[] firstSrvMedia = new String[1];
            countUpdates(response, counts, firstSrvMedia);
            if (localMedia == 0 && counts[2] == 0) return null;
            int seq = nextSeq();
            emit("ack_enter", seq,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("source", "multi"),
                    PurpleDiagLog.flag("scheduled", scheduled),
                    PurpleDiagLog.flag("has_error", error != null),
                    PurpleDiagLog.code("updates_type", updatesType(response)),
                    PurpleDiagLog.count("msg_id_cnt", counts[0]),
                    PurpleDiagLog.count("new_msg_cnt", counts[1]),
                    PurpleDiagLog.count("media_local_cnt", localMedia),
                    PurpleDiagLog.count("media_server_cnt", counts[2]),
                    PurpleDiagLog.code("rpc_err", mapErrorCategory(error)));
            return new AckTrace(account, "multi", seq, firstSrvMedia[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static AckTrace singleAckEnter(int account, Object response, TLRPC.TL_error error, TLRPC.Message newMsgObj, boolean scheduled) {
        try {
            boolean localMedia = isMediaMessage(newMsgObj);
            String srvMedia = "none";
            boolean srvHasMedia = false;
            if (response instanceof TLRPC.TL_updateShortSentMessage) {
                srvMedia = shortSentMedia((TLRPC.TL_updateShortSentMessage) response);
                srvHasMedia = "voice".equals(srvMedia) || "round".equals(srvMedia);
            } else if (response instanceof TLRPC.Updates) {
                int[] counts = new int[3];
                String[] firstSrvMedia = new String[1];
                countUpdates(response, counts, firstSrvMedia);
                srvHasMedia = counts[2] > 0;
                if (firstSrvMedia[0] != null) srvMedia = firstSrvMedia[0];
            }
            if (!localMedia && !srvHasMedia) return null;
            int seq = nextSeq();
            emit("ack_enter", seq,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("source", "single"),
                    PurpleDiagLog.flag("scheduled", scheduled),
                    PurpleDiagLog.flag("has_error", error != null),
                    PurpleDiagLog.code("updates_type", updatesType(response)),
                    PurpleDiagLog.code("media", mediaCode(newMsgObj)),
                    PurpleDiagLog.code("srv_media", srvMedia),
                    PurpleDiagLog.flag("id_pos", newMsgObj != null && newMsgObj.id > 0),
                    PurpleDiagLog.flag("own", newMsgObj != null && newMsgObj.out),
                    PurpleDiagLog.code("kind", kindFromMessage(newMsgObj)));
            return new AckTrace(account, "single", seq, srvMedia);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int traceUpdateEntry(int account, Object baseUpdate, TLRPC.Message message,
            boolean fromGetDifference, boolean scheduledUpdate) {
        try {
            if (!isMediaMessage(message)) return 0;
            boolean history = fromGetDifference || scheduledUpdate;
            int seq = history ? 0 : nextSeq();
            emit("update_enter", seq,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("source", updateSourceCode(baseUpdate)),
                    PurpleDiagLog.flag("diff", fromGetDifference),
                    PurpleDiagLog.flag("from_scheduled", scheduledUpdate),
                    PurpleDiagLog.code("media", mediaCode(message)),
                    PurpleDiagLog.code("kind", kindFromMessage(message)),
                    PurpleDiagLog.flag("own", message != null && message.out),
                    PurpleDiagLog.flag("id_pos", message != null && message.id > 0),
                    PurpleDiagLog.flag("has_peer", message != null && message.peer_id != null));
            return seq;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static int ensureSeqForObserve(int trace, TLRPC.Message raw, boolean history) {
        if (trace > 0) return trace;
        if (history || !isMediaMessage(raw)) return 0;
        return nextSeq();
    }

    static void observeGateReject(int account, int trace, TLRPC.Message raw, boolean ack, boolean history) {
        try {
            boolean media = isMediaMessage(raw);
            if (trace <= 0 && !media) return;
            String reason;
            if (raw == null) reason = "null_raw";
            else if (history) reason = "history";
            else if (raw.id <= 0) reason = "nonpositive_id";
            else if (raw.send_state != 0) reason = "unsent";
            else if (raw.from_scheduled) reason = "scheduled";
            else if (raw.quick_reply_shortcut_id != 0 || raw.quick_reply_shortcut != null) reason = "quick_reply";
            else if (raw.peer_id == null) reason = "null_peer";
            else if (raw.action != null && !(raw.action instanceof TLRPC.TL_messageActionEmpty)) reason = "service_action";
            else reason = "rejected";

            int seq = history ? 0 : trace;
            emit("observe", seq,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", "gate"),
                    PurpleDiagLog.code("outcome", "rejected"),
                    PurpleDiagLog.reason(reason),
                    PurpleDiagLog.flag("ack", ack),
                    PurpleDiagLog.flag("history", history),
                    PurpleDiagLog.flag("has_raw", raw != null),
                    PurpleDiagLog.flag("id_pos", raw != null && raw.id > 0),
                    PurpleDiagLog.code("send_state", sendStateCode(raw != null ? raw.send_state : -1)),
                    PurpleDiagLog.flag("from_scheduled", raw != null && raw.from_scheduled),
                    PurpleDiagLog.flag("quick_reply", raw != null && (raw.quick_reply_shortcut_id != 0 || raw.quick_reply_shortcut != null)),
                    PurpleDiagLog.flag("has_peer", raw != null && raw.peer_id != null),
                    PurpleDiagLog.flag("action_blocked", raw != null && raw.action != null && !(raw.action instanceof TLRPC.TL_messageActionEmpty)),
                    PurpleDiagLog.flag("own", raw != null && raw.out),
                    PurpleDiagLog.code("media", mediaCode(raw)),
                    PurpleDiagLog.code("kind", kindFromMessage(raw)),
                    PurpleDiagLog.code("peer_kind", peerKind(raw)));
        } catch (Throwable ignored) {
        }
    }

    static void observeMediaReject(int account, int trace, TLRPC.Message raw, int media, boolean ack, String reason, long futureDelta) {
        try {
            if (trace <= 0 && media == 0) return;
            if ("future_date".equals(reason)) {
                emit("observe", trace,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("stage", "gate"),
                        PurpleDiagLog.code("outcome", "rejected"),
                        PurpleDiagLog.reason(reason),
                        PurpleDiagLog.flag("ack", ack),
                        PurpleDiagLog.flag("own", raw != null && raw.out),
                        PurpleDiagLog.code("media", mediaName(media)),
                        PurpleDiagLog.code("kind", kindFromMessage(raw)),
                        PurpleDiagLog.count("future_s", clampedAbsDelta(futureDelta)));
            } else {
                emit("observe", trace,
                        PurpleDiagLog.slot(account),
                        PurpleDiagLog.code("stage", "gate"),
                        PurpleDiagLog.code("outcome", "rejected"),
                        PurpleDiagLog.reason(reason),
                        PurpleDiagLog.flag("ack", ack),
                        PurpleDiagLog.flag("own", raw != null && raw.out),
                        PurpleDiagLog.code("media", media == 0 ? mediaCode(raw) : mediaName(media)),
                        PurpleDiagLog.code("kind", kindFromMessage(raw)),
                        PurpleDiagLog.code("peer_kind", peerKind(raw)));
            }
        } catch (Throwable ignored) {
        }
    }

    static void observeAdmitted(int account, int trace, TLRPC.Message raw, int media, boolean ack, boolean own, boolean hasTopic, int pendingNow) {
        try {
            emit("observe", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", "ui"),
                    PurpleDiagLog.code("outcome", "admitted"),
                    PurpleDiagLog.flag("ack", ack),
                    PurpleDiagLog.flag("own", own),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.code("kind", kindFromMessage(raw)),
                    PurpleDiagLog.code("peer_kind", peerKind(raw)),
                    PurpleDiagLog.flag("has_topic", hasTopic),
                    PurpleDiagLog.count("pending", pendingNow));
        } catch (Throwable ignored) {
        }
    }

    static void dispatchReject(int account, int trace, String stage, String reason,
            String kind, int media, boolean ack, boolean own, boolean hasTopic) {
        try {
            emit("dispatch_reject", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", stage),
                    PurpleDiagLog.reason(reason),
                    PurpleDiagLog.code("kind", kind),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.flag("ack", ack),
                    PurpleDiagLog.flag("own", own),
                    PurpleDiagLog.flag("has_topic", hasTopic));
        } catch (Throwable ignored) {
        }
    }

    static void dispatchStateReject(int account, int trace, String stage, String reason,
            String kind, int media, boolean ack, boolean own, boolean hasTopic, int inFlight, int reserved, Throwable error) {
        try {
            emit("dispatch_reject", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", stage),
                    PurpleDiagLog.reason(reason),
                    PurpleDiagLog.code("kind", kind),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.flag("ack", ack),
                    PurpleDiagLog.flag("own", own),
                    PurpleDiagLog.flag("has_topic", hasTopic),
                    PurpleDiagLog.count("in_flight", inFlight),
                    PurpleDiagLog.count("reserved", reserved),
                    error != null ? PurpleDiagLog.code("exception", exceptionCategory(error)) : null);
        } catch (Throwable ignored) {
        }
    }

    static void cutoffSeeded(int account, int trace, String kind, int media, boolean ack, boolean own, boolean hasTopic, long dateDelta) {
        try {
            emit("dispatch_reject", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", "dispatch"),
                    PurpleDiagLog.reason("cutoff_seeded"),
                    PurpleDiagLog.code("outcome", "seeded"),
                    PurpleDiagLog.code("kind", kind),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.flag("ack", ack),
                    PurpleDiagLog.flag("own", own),
                    PurpleDiagLog.flag("has_topic", hasTopic),
                    PurpleDiagLog.code("delta_sign", deltaSign(dateDelta)),
                    PurpleDiagLog.count("date_vs_cutoff_s", clampedAbsDelta(dateDelta)));
        } catch (Throwable ignored) {
        }
    }

    static void planOutcome(int account, int trace, String outcome, String reason, String kind,
            int media, boolean ack, boolean own, boolean hasTopic, long dateMinusCutoff) {
        try {
            emit("plan_result", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", "plan"),
                    PurpleDiagLog.code("outcome", outcome),
                    reason != null ? PurpleDiagLog.reason(reason) : null,
                    PurpleDiagLog.code("kind", kind),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.flag("ack", ack),
                    PurpleDiagLog.flag("own", own),
                    PurpleDiagLog.flag("has_topic", hasTopic),
                    PurpleDiagLog.code("delta_sign", deltaSign(dateMinusCutoff)),
                    PurpleDiagLog.count("date_vs_cutoff_s", clampedAbsDelta(dateMinusCutoff)));
        } catch (Throwable ignored) {
        }
    }

    static void journalOutcome(int account, int trace, String outcome, String kind, int media, int reserved, Throwable error) {
        try {
            emit("journal", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", "journal"),
                    PurpleDiagLog.code("outcome", outcome),
                    PurpleDiagLog.code("kind", kind),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.count("reserved", reserved),
                    error != null ? PurpleDiagLog.code("exception", exceptionCategory(error)) : null);
        } catch (Throwable ignored) {
        }
    }

    static void dispatchException(int account, int trace, String kind, int media, boolean ack, boolean own, boolean hasTopic, Throwable error) {
        try {
            emit("dispatch_reject", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", "dispatch"),
                    PurpleDiagLog.reason("storage_refused"),
                    PurpleDiagLog.code("exception", exceptionCategory(error)),
                    PurpleDiagLog.code("kind", kind),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.flag("ack", ack),
                    PurpleDiagLog.flag("own", own),
                    PurpleDiagLog.flag("has_topic", hasTopic));
        } catch (Throwable ignored) {
        }
    }

    static void rpcEnqueued(int account, int trace, int kind, int media, boolean hasTopic, int inFlight, int reserved) {
        try {
            emit("rpc_enqueued", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", "send"),
                    PurpleDiagLog.code("kind", kindName(kind)),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.flag("has_topic", hasTopic),
                    PurpleDiagLog.count("in_flight", inFlight),
                    PurpleDiagLog.count("reserved", reserved));
        } catch (Throwable ignored) {
        }
    }

    static void rpcResult(int account, int trace, int kind, int media, boolean hasTopic, boolean current,
            int inFlight, int reserved, TLRPC.TL_error error, Object response, PurpleMediaReply.Reason mappedReason) {
        try {
            String outcome = error != null ? "error" : (response instanceof TLRPC.Updates ? "ok" : "uncertain");
            emit("rpc_result", trace,
                    PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("stage", "send"),
                    PurpleDiagLog.code("outcome", outcome),
                    PurpleDiagLog.reason(reasonName(mappedReason)),
                    PurpleDiagLog.code("rpc_err", mapErrorCategory(error)),
                    PurpleDiagLog.count("error_code", error != null ? clampErrorCode(error.code) : 0),
                    PurpleDiagLog.flag("stale", !current),
                    PurpleDiagLog.code("kind", kindName(kind)),
                    PurpleDiagLog.code("media", mediaName(media)),
                    PurpleDiagLog.flag("has_topic", hasTopic),
                    PurpleDiagLog.count("in_flight", inFlight),
                    PurpleDiagLog.count("reserved", reserved));
        } catch (Throwable ignored) {
        }
    }

    static void invalidateStart(int slots) {
        try {
            emit("invalidate", 0,
                    PurpleDiagLog.code("stage", "entry"),
                    PurpleDiagLog.count("slots", slots));
        } catch (Throwable ignored) {
        }
    }

    static void invalidateComplete(int cancelled, int tracked) {
        try {
            if (cancelled == 0 && tracked == 0) return;
            emit("invalidate", 0,
                    PurpleDiagLog.code("stage", "ui"),
                    PurpleDiagLog.count("cancelled", cancelled),
                    PurpleDiagLog.count("tracked", tracked));
        } catch (Throwable ignored) {
        }
    }
}
