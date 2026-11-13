package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Host test suite for {@link PurpleMediaReplyDiagnostics} and its wiring in
 * {@link PurpleMediaReply}: real TL media attributes, ACK/update correlation,
 * admission/rejection visibility, repeat collapse, privacy bounds, export
 * hygiene, and logging fault non-interference.
 */
public final class PurpleMediaReplyDiagCases {
    private static int checks;

    private static void check(boolean value, String message) {
        ++checks;
        if (!value) throw new AssertionError(message);
    }

    private static List<String> mediaLines(int fromIndex) {
        List<String> out = new ArrayList<>();
        synchronized (FileLog.lines) {
            for (int i = fromIndex; i < FileLog.lines.size(); ++i) {
                String line = FileLog.lines.get(i);
                if (line != null && line.startsWith("Purple: ")) {
                    out.add(line.substring("Purple: ".length()));
                }
            }
        }
        return out;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }

    private static boolean hasLine(List<String> lines, String... parts) {
        for (String line : lines) {
            boolean ok = true;
            for (String p : parts) {
                if (!line.contains(p)) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
        return false;
    }

    private static TLRPC.Message tlVoiceMsg(int id, long chatId, long userId, boolean out, int date) {
        TLRPC.Message m = new TLRPC.Message();
        m.id = id;
        m.date = date;
        m.out = out;
        m.random_id = 5544332211L + Math.abs(id);
        m.peer_id = new TLRPC.Peer();
        if (chatId != 0) m.peer_id.chat_id = chatId;
        else m.peer_id.user_id = userId;
        TLRPC.TL_documentAttributeAudio attr = new TLRPC.TL_documentAttributeAudio();
        attr.voice = true;
        TLRPC.Document doc = new TLRPC.Document();
        doc.mime_type = "audio/ogg";
        doc.attributes.add(attr);
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = doc;
        m.media = media;
        return m;
    }

    private static TLRPC.Message tlRoundMsg(int id, long channelId, boolean out, int date, int w, int h) {
        TLRPC.Message m = new TLRPC.Message();
        m.id = id;
        m.date = date;
        m.out = out;
        m.random_id = 6655443322L + Math.abs(id);
        m.peer_id = new TLRPC.Peer();
        m.peer_id.channel_id = channelId;
        TLRPC.TL_documentAttributeVideo attr = new TLRPC.TL_documentAttributeVideo();
        attr.round_message = true;
        attr.w = w;
        attr.h = h;
        TLRPC.Document doc = new TLRPC.Document();
        doc.mime_type = "video/mp4";
        doc.attributes.add(attr);
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = doc;
        m.media = media;
        return m;
    }

    private static TLRPC.Message tlAudioTrackMsg(int id, long userId, int date) {
        TLRPC.Message m = new TLRPC.Message();
        m.id = id;
        m.date = date;
        m.out = true;
        m.peer_id = new TLRPC.Peer();
        m.peer_id.user_id = userId;
        TLRPC.TL_documentAttributeAudio attr = new TLRPC.TL_documentAttributeAudio();
        attr.voice = false;
        TLRPC.Document doc = new TLRPC.Document();
        doc.mime_type = "audio/mpeg";
        doc.attributes.add(attr);
        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = doc;
        m.media = media;
        return m;
    }

    private static TLRPC.Message tlPhotoMsg(int id, long userId, int date) {
        TLRPC.Message m = new TLRPC.Message();
        m.id = id;
        m.date = date;
        m.out = true;
        m.peer_id = new TLRPC.Peer();
        m.peer_id.user_id = userId;
        m.media = new TLRPC.TL_messageMediaPhoto();
        return m;
    }

    private static void resetAccount(String key, long lifetime, String policyText) throws Exception {
        AndroidUtilities.drain();
        FileLog.injectedFailure = null;
        PurpleAccountOwner.isReady = true;
        PurpleAccountFiles.identity = new PurpleAccountFiles.Identity(key, lifetime);
        MessagesController.chats.clear();
        File journal = new File(System.getProperty("scratch"), "media-reply-journal.json");
        Files.deleteIfExists(journal.toPath());
        PurpleAccountSettings.policy(policyText);
        PurpleMediaReply.invalidate();
        AndroidUtilities.drain();
    }

    private static void verifyLineClean(String line, String context) {
        check(line.length() <= PurpleDiagLogStore.LINE_LIMIT, context + ": length <= 512");
        int space = line.indexOf(' ');
        check(space > 0, context + ": timestamp separator");
        String body = line.substring(space + 1);
        check(!body.contains("=invalid") && !body.endsWith(" invalid"),
                context + ": no invalid vocabulary token in " + body);
        int digits = 0;
        for (int i = 0; i < body.length(); ++i) {
            char c = body.charAt(i);
            digits = (c >= '0' && c <= '9') ? digits + 1 : 0;
            check(digits < 6, context + ": no 6+ digit run in " + body);
            check(c != '/' && c != '\\', context + ": no path separator in " + body);
            check(c >= 0x20 && c <= 0x7e, context + ": printable ASCII only");
        }
        String lower = body.toLowerCase();
        check(!lower.contains("canary") && !lower.contains("secret_user_text"),
                context + ": no canary leak in " + body);
    }

    public static void main(String[] args) throws Exception {
        check(new File(PurpleMediaReply.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getCanonicalFile().equals(new File(args[1]).getCanonicalFile()), "actual AGP adapter");
        check(new File(PurpleMediaReplyDiagnostics.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getCanonicalFile().equals(new File(args[1]).getCanonicalFile()), "actual AGP diagnostics");
        check(new File(PurpleDiagLogStore.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getCanonicalFile().equals(new File(args[1]).getCanonicalFile()), "actual AGP diag store");
        check(new File(PurpleDiagLog.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getCanonicalFile().equals(new File(args[1]).getCanonicalFile()), "actual AGP diag log");

        System.load(args[0]);
        Files.createDirectories(Paths.get(System.getProperty("scratch")));
        ApplicationLoader.files = new File(System.getProperty("scratch"), "diag-root");

        // 1. Native TL audio/video attributes and bounds.
        TLRPC.Message voice = tlVoiceMsg(10, 7712345678L, 0, true, 1000);
        TLRPC.Message nonVoiceAudio = tlAudioTrackMsg(11, 7712345678L, 1000);
        TLRPC.Message roundOk = tlRoundMsg(12, 1009988776655L, true, 1000, 480, 480);
        TLRPC.Message roundTooBig = tlRoundMsg(13, 1009988776655L, true, 1000, 1281, 480);
        TLRPC.Message webPageVoice = new TLRPC.Message();
        webPageVoice.id = 14;
        webPageVoice.peer_id = voice.peer_id;
        TLRPC.TL_messageMediaWebPage wp = new TLRPC.TL_messageMediaWebPage();
        wp.webpage = new TLRPC.WebPage();
        wp.webpage.document = voice.media.document;
        webPageVoice.media = wp;
        TLRPC.Message photo = tlPhotoMsg(15, 7712345678L, 1000);
        TLRPC.Message empty = new TLRPC.TL_messageEmpty();

        check("voice".equals(PurpleMediaReplyDiagnostics.mediaCode(voice)), "TL audio voice=true");
        check("other".equals(PurpleMediaReplyDiagnostics.mediaCode(nonVoiceAudio)), "TL audio voice=false excluded");
        check("round".equals(PurpleMediaReplyDiagnostics.mediaCode(roundOk)), "TL round video <=1280");
        check("other".equals(PurpleMediaReplyDiagnostics.mediaCode(roundTooBig)), "TL round video >1280 excluded");
        check("voice".equals(PurpleMediaReplyDiagnostics.mediaCode(webPageVoice)), "TL webpage voice");
        check("other".equals(PurpleMediaReplyDiagnostics.mediaCode(photo)), "TL photo excluded");
        check("none".equals(PurpleMediaReplyDiagnostics.mediaCode(empty)), "TL empty has no media");

        // 2. Non-media ordinary chat messages and batches emit zero lines and consume zero seq.
        resetAccount("0-101", 1, "[media_reply.private]\nvoice='both'\n[media_reply.groups]\nvoice='both'\n");
        ConnectionsManager.now = 1000;
        int startIdx = FileLog.lines.size();
        ArrayList<TLRPC.Message> nonMediaLocal = new ArrayList<>();
        nonMediaLocal.add(nonVoiceAudio);
        nonMediaLocal.add(photo);
        TLRPC.Updates nonMediaUpdates = new TLRPC.Updates();
        TL_update.TL_updateNewMessage nu = new TL_update.TL_updateNewMessage();
        nu.message = tlPhotoMsg(88997766, 7712345678L, 1001);
        nonMediaUpdates.updates.add(nu);
        check(PurpleMediaReplyDiagnostics.forwardAckEnter(0, nonMediaUpdates, null, nonMediaLocal) == null,
                "forward ACK ignores non-media");
        ArrayList<MessageObject> nonMediaObjs = new ArrayList<>();
        nonMediaObjs.add(new MessageObject(roundTooBig));
        check(PurpleMediaReplyDiagnostics.multiAckEnter(0, nonMediaUpdates, null, nonMediaObjs, false) == null,
                "multi ACK ignores non-media");
        check(PurpleMediaReplyDiagnostics.singleAckEnter(0, new TLRPC.TL_updateShortSentMessage(), null, photo, false) == null,
                "single ACK ignores non-media");
        PurpleMediaReply.observeUpdate(0, nu, nu.message, false, false);
        PurpleMediaReply.observe(0, nonVoiceAudio, true, false);
        PurpleMediaReply.observe(0, photo, false, true);
        AndroidUtilities.drain();
        check(mediaLines(startIdx).isEmpty(), "zero lines emitted for non-media control");

        // Reset cutoff to 0 via invalidate() while clock=0, then seed via cutoff_seeded at now=1000.
        ConnectionsManager.now = 0;
        PurpleMediaReply.invalidate();
        AndroidUtilities.drain();
        int beforeSeedIdx = FileLog.lines.size();
        ConnectionsManager.now = 1000;
        TLRPC.Message seedMsg = tlVoiceMsg(88997760, 7712345678L, 0, true, 1000);
        TLRPC.Chat basicGroup = new TLRPC.Chat();
        basicGroup.channel = false;
        basicGroup.allowed = true;
        MessagesController.chats.put(7712345678L, basicGroup);
        PurpleMediaReply.observe(0, seedMsg, true, false);
        AndroidUtilities.drain();
        List<String> seedLines = mediaLines(beforeSeedIdx);
        check(hasLine(seedLines, "media_reply observe", "stage=ui", "outcome=admitted", "media=voice", "kind=group"),
                "cutoff seed voice observe emitted");
        check(hasLine(seedLines, "media_reply dispatch_reject", "reason=cutoff_seeded", "outcome=seeded", "date_vs_cutoff_s=0"),
                "cutoff_seeded emitted on initial dispatch");
        check(ConnectionsManager.requests.isEmpty(), "cutoff seeding did not enqueue request");

        // Advance time so subsequent voice messages are after cutoff=1000.
        ConnectionsManager.now = 1005;

        // 3. Forward ACK boundary: misses + schedule mismatch + happy basic-group correlation into observe/dispatch/RPC.
        int fwdStart = FileLog.lines.size();
        TLRPC.Message localUnsentVoice = tlVoiceMsg(-5, 7712345678L, 0, true, 1005);
        localUnsentVoice.send_state = 1;
        TLRPC.Message serverVoice = tlVoiceMsg(88997766, 7712345678L, 0, true, 1005);
        serverVoice.send_state = 0;

        ArrayList<TLRPC.Message> fwdLocal = new ArrayList<>();
        fwdLocal.add(localUnsentVoice);
        TLRPC.Updates fwdUpdates = new TLRPC.Updates();
        TL_update.TL_updateMessageID mid = new TL_update.TL_updateMessageID();
        mid.id = 88997766;
        mid.random_id = localUnsentVoice.random_id;
        fwdUpdates.updates.add(mid);
        TL_update.TL_updateNewMessage srvNew = new TL_update.TL_updateNewMessage();
        srvNew.message = serverVoice;
        fwdUpdates.updates.add(srvNew);

        PurpleMediaReplyDiagnostics.AckTrace fwdAck =
                PurpleMediaReplyDiagnostics.forwardAckEnter(0, fwdUpdates, null, fwdLocal);
        check(fwdAck != null && fwdAck.seq > 0, "forward ACK trace allocated seq");
        fwdAck.forwardMiss(serverVoice, "corr_miss_id");
        fwdAck.forwardMiss(serverVoice, "corr_miss_rand");
        fwdAck.forwardMiss(serverVoice, "corr_miss_idx");
        fwdAck.forwardMatched(localUnsentVoice, serverVoice, false, true);
        fwdAck.forwardEnd(0, fwdUpdates, fwdLocal);
        fwdLocal.clear();
        fwdUpdates.updates.clear();
        PurpleMediaReplyDiagnostics.AckTrace fwdOkAck =
                PurpleMediaReplyDiagnostics.forwardAckEnter(0, new TLRPC.Updates(), null, java.util.Collections.singletonList(localUnsentVoice));
        check(fwdOkAck != null, "forward OK ACK trace");
        check(fwdOkAck.seq == fwdAck.seq + 1 || (fwdAck.seq == 9999 && fwdOkAck.seq == 1),
                "seq increments strictly for media batches only");
        localUnsentVoice.id = serverVoice.id;
        localUnsentVoice.send_state = 0;
        fwdOkAck.forwardMatched(localUnsentVoice, serverVoice, false, false);
        int reqsBefore = ConnectionsManager.requests.size();
        PurpleMediaReply.observe(0, serverVoice, true, false, fwdOkAck.seq);
        AndroidUtilities.drain();
        fwdOkAck.forwardEnd(1, new TLRPC.Updates(), java.util.Collections.emptyList());
        check(ConnectionsManager.requests.size() == reqsBefore + 1, "basic-group forward voice admitted and enqueued");
        ConnectionsManager.callbacks.get(ConnectionsManager.callbacks.size() - 1).run(new TLRPC.Updates(), null);
        AndroidUtilities.drain();

        List<String> fwdLines = mediaLines(fwdStart);
        String okSeqTag = "seq=" + fwdOkAck.seq + " ";
        check(hasLine(fwdLines, "media_reply ack_enter", "source=forward", "msg_id_cnt=1", "new_msg_cnt=1", "media_local_cnt=1", "media_server_cnt=1"), "forward ack_enter");
        check(hasLine(fwdLines, "media_reply ack_skip", "reason=corr_miss_id"), "forward corr_miss_id");
        check(hasLine(fwdLines, "media_reply ack_skip", "reason=corr_miss_rand"), "forward corr_miss_rand");
        check(hasLine(fwdLines, "media_reply ack_skip", "reason=corr_miss_idx"), "forward corr_miss_idx");
        check(hasLine(fwdLines, "media_reply ack_skip", "reason=schedule_mismatch", "sched_mismatch=1"), "forward schedule_mismatch");
        check(hasLine(fwdLines, "media_reply ack_end", "outcome=rejected", "matched_cnt=0"), "forward sentCount=0");
        check(hasLine(fwdLines, "media_reply ack_step", okSeqTag, "source=forward", "media=voice", "srv_media=voice", "kind=group"), "forward matched correlated");
        check(hasLine(fwdLines, "media_reply observe", okSeqTag, "stage=ui", "outcome=admitted", "kind=group", "media=voice"), "observe shares ACK seq");
        check(hasLine(fwdLines, "media_reply plan_result", okSeqTag, "outcome=admitted", "kind=group", "delta_sign=pos", "date_vs_cutoff_s=5"), "plan_result positive delta");
        check(hasLine(fwdLines, "media_reply journal", okSeqTag, "outcome=loaded"), "journal loaded");
        check(hasLine(fwdLines, "media_reply journal", okSeqTag, "outcome=reserved"), "journal reserved");
        check(hasLine(fwdLines, "media_reply rpc_enqueued", okSeqTag, "kind=group", "media=voice"), "rpc_enqueued correlated");
        check(hasLine(fwdLines, "media_reply rpc_result", okSeqTag, "outcome=ok", "reason=none", "stale=0"), "rpc_result correlated");

        // 4. Multi ACK + Single ACK (TL_updateShortSentMessage vs Updates / message==null / error).
        int multiStart = FileLog.lines.size();
        TLRPC.Message multiLocal = tlVoiceMsg(-10, 0, 7712345678L, true, 1005);
        multiLocal.send_state = 1;
        TLRPC.Message multiSrv = tlVoiceMsg(88997767, 0, 7712345678L, true, 1005);
        ArrayList<MessageObject> multiObjs = new ArrayList<>();
        multiObjs.add(new MessageObject(multiLocal));
        TLRPC.Updates multiUpd = new TLRPC.Updates();
        TL_update.TL_updateNewMessage mu = new TL_update.TL_updateNewMessage();
        mu.message = multiSrv;
        multiUpd.updates.add(mu);
        PurpleMediaReplyDiagnostics.AckTrace multiAck =
                PurpleMediaReplyDiagnostics.multiAckEnter(0, multiUpd, null, multiObjs, false);
        check(multiAck != null, "multi ACK entered");
        multiAck.multiMiss(multiLocal, "corr_miss_rand");
        multiAck.multiMiss(multiLocal, "corr_miss_obj");
        multiLocal.id = 88997767;
        multiLocal.send_state = 0;
        multiAck.multiMatched(multiLocal, multiSrv, false, false);
        PurpleMediaReply.observe(0, multiLocal, true, false, multiAck.seq);
        PurpleMediaReply.observe(0, multiLocal, true, false, multiAck.seq);
        AndroidUtilities.drain();
        multiAck.multiEnd(1, multiUpd.updates, false);
        List<String> multiLines = mediaLines(multiStart);
        check(hasLine(multiLines, "media_reply ack_enter", "source=multi"), "multi ack_enter");
        check(hasLine(multiLines, "media_reply ack_skip", "source=multi", "reason=corr_miss_rand"), "multi corr_miss_rand");
        check(hasLine(multiLines, "media_reply ack_skip", "source=multi", "reason=corr_miss_obj"), "multi corr_miss_obj");
        check(hasLine(multiLines, "media_reply ack_step", "source=multi", "reason=matched"), "multi matched");
        check(hasLine(multiLines, "media_reply ack_end", "source=multi", "outcome=ok"), "multi ack_end");

        int singleStart = FileLog.lines.size();
        TLRPC.Message sLocal = tlVoiceMsg(-20, 7712345678L, 0, true, 1005);
        sLocal.send_state = 1;
        TLRPC.TL_updateShortSentMessage sShort = new TLRPC.TL_updateShortSentMessage();
        sShort.id = 88997768;
        sShort.date = 1005;
        sShort.out = true;
        sShort.media = sLocal.media;
        PurpleMediaReplyDiagnostics.AckTrace shortAck =
                PurpleMediaReplyDiagnostics.singleAckEnter(0, sShort, null, sLocal, false);
        check(shortAck != null, "short sent ACK entered");
        sLocal.id = sShort.id;
        sLocal.send_state = 0;
        shortAck.singlePrepared(sLocal, sShort, false, false);
        PurpleMediaReply.observe(0, sLocal, true, false, shortAck.seq);
        AndroidUtilities.drain();

        TLRPC.Updates sUpdatesErr = new TLRPC.Updates();
        sUpdatesErr.updates.add(new TL_update.TL_updateMessageID());
        PurpleMediaReplyDiagnostics.AckTrace nullMsgAck =
                PurpleMediaReplyDiagnostics.singleAckEnter(0, sUpdatesErr, null, sLocal, false);
        check(nullMsgAck != null, "single message==null ACK entered");
        nullMsgAck.singleError(null, sUpdatesErr);

        TLRPC.TL_error canaryErr = new TLRPC.TL_error();
        canaryErr.code = 420;
        canaryErr.text = "FLOOD_WAIT_SECRET_CANARY_987654";
        PurpleMediaReplyDiagnostics.AckTrace errAck =
                PurpleMediaReplyDiagnostics.singleAckEnter(0, null, canaryErr, sLocal, false);
        check(errAck != null, "single error ACK entered");
        errAck.singleError(canaryErr, null);

        List<String> singleLines = mediaLines(singleStart);
        check(hasLine(singleLines, "media_reply ack_enter", "source=single", "updates_type=short_sent", "srv_media=voice"), "single short_sent enter");
        check(hasLine(singleLines, "media_reply ack_step", "source=single", "updates_type=short_sent", "send_state=sent"), "single short_sent prepared");
        check(hasLine(singleLines, "media_reply ack_end", "source=single", "reason=message_null", "rem_cnt=1"), "single message_null");
        check(hasLine(singleLines, "media_reply ack_end", "source=single", "reason=send_error", "rpc_err=flood", "error_code=420"), "single send_error mapped");

        // 5. MessagesController.observeUpdate variants + bulk history repeat collapse.
        int updStart = FileLog.lines.size();
        TLRPC.Message chRound = tlRoundMsg(88997769, 1009988776655L, true, 1005, 360, 360);
        chRound.reply_to = new TLRPC.MessageReplyHeader();
        chRound.reply_to.reply_to_top_id = 42;
        TL_update.TL_updateNewChannelMessage uCh = new TL_update.TL_updateNewChannelMessage();
        uCh.message = chRound;
        PurpleMediaReply.observeUpdate(0, uCh, chRound, false, false);
        TL_update.TL_updateNewScheduledMessage uSched = new TL_update.TL_updateNewScheduledMessage();
        uSched.message = chRound;
        PurpleMediaReply.observeUpdate(0, uSched, chRound, false, true);
        TL_update.TL_updateNewMessage uDiff = new TL_update.TL_updateNewMessage();
        uDiff.message = tlVoiceMsg(88997770, 7712345678L, 0, false, 1005);
        for (int i = 0; i < 8; ++i) {
            PurpleMediaReply.observeUpdate(0, uDiff, uDiff.message, true, false);
        }
        PurpleDiagLog.export(ignored -> {});
        AndroidUtilities.drain();
        List<String> updLines = mediaLines(updStart);
        check(hasLine(updLines, "media_reply update_enter", "source=channel_message", "diff=0", "from_scheduled=0", "media=round", "seq="), "channel_message update_enter has seq");
        check(hasLine(updLines, "media_reply update_enter", "source=scheduled_message", "from_scheduled=1"), "scheduled_message update_enter");
        check(hasLine(updLines, "media_reply update_enter", "source=new_message", "diff=1") &&
                !hasLine(updLines, "source=new_message", "diff=1", "seq="), "history diff omits seq");
        check(hasLine(updLines, "diag repeated", "area=media_reply", "event=update_enter", "count=5"), "bulk history update collapsed by PurpleDiagRepeats");
        check(hasLine(updLines, "diag repeated", "area=media_reply", "event=observe", "count=5"), "bulk history observe collapsed by PurpleDiagRepeats");

        // 6. All remaining observe / UI / dispatch / journal / RPC failure gates.
        int rejStart = FileLog.lines.size();
        // null raw with trace, nonpositive id, unsent, scheduled, quick_reply, null_peer, service_action, not_media with trace, incoming_ack, future_date, identity_missing, zero_dialog_id, queue_full
        PurpleMediaReply.observe(0, null, true, false, 77);
        TLRPC.Message negId = tlVoiceMsg(-1, 7712345678L, 0, true, 1005);
        PurpleMediaReply.observe(0, negId, true, false);
        TLRPC.Message sending = tlVoiceMsg(88997771, 7712345678L, 0, true, 1005);
        sending.send_state = 1;
        PurpleMediaReply.observe(0, sending, true, false);
        TLRPC.Message sched = tlVoiceMsg(88997772, 7712345678L, 0, true, 1005);
        sched.from_scheduled = true;
        PurpleMediaReply.observe(0, sched, true, false);
        TLRPC.Message qr = tlVoiceMsg(88997773, 7712345678L, 0, true, 1005);
        qr.quick_reply_shortcut_id = 3;
        PurpleMediaReply.observe(0, qr, true, false);
        TLRPC.Message noPeer = tlVoiceMsg(88997774, 0, 0, true, 1005);
        noPeer.peer_id = null;
        PurpleMediaReply.observe(0, noPeer, true, false);
        TLRPC.Message act = tlVoiceMsg(88997775, 7712345678L, 0, true, 1005);
        act.action = new TLRPC.MessageAction();
        PurpleMediaReply.observe(0, act, true, false);
        PurpleMediaReply.observe(0, photo, true, false, 78);
        TLRPC.Message inAck = tlVoiceMsg(88997776, 7712345678L, 0, false, 1005);
        PurpleMediaReply.observe(0, inAck, true, false);
        TLRPC.Message fut = tlVoiceMsg(88997777, 7712345678L, 0, true, 200000);
        PurpleMediaReply.observe(0, fut, true, false);
        TLRPC.Message zeroDid = tlVoiceMsg(88997778, 0, 0, true, 1005);
        PurpleMediaReply.observe(0, zeroDid, true, false);

        PurpleAccountFiles.Identity savedId = PurpleAccountFiles.identity;
        PurpleAccountFiles.identity = null;
        PurpleMediaReply.observe(0, tlVoiceMsg(88997779, 7712345678L, 0, true, 1005), true, false);
        PurpleAccountFiles.identity = savedId;

        // queue_full (32 pending) + revision_stale
        for (int i = 0; i < 33; ++i) {
            PurpleMediaReply.observe(0, tlVoiceMsg(88997800 + i, 7712345678L, 0, true, 1005), true, false);
        }
        PurpleMediaReply.invalidate();
        AndroidUtilities.drain();

        // identity_stale on UI
        PurpleMediaReply.observe(0, tlVoiceMsg(88997850, 7712345678L, 0, true, 1005), true, false);
        PurpleAccountFiles.identity = new PurpleAccountFiles.Identity("0-101", 2);
        AndroidUtilities.drain();
        resetAccount("0-101", 1, "[media_reply.private]\nvoice='both'\n[media_reply.groups]\nvoice='both'\n");
        ConnectionsManager.now = 1005;
        // seed cutoff=1005
        PurpleMediaReply.observe(0, tlVoiceMsg(88997851, 0, 7712345678L, true, 1005), true, false);
        AndroidUtilities.drain();
        ConnectionsManager.now = 1050;

        // owner_not_ready
        PurpleAccountOwner.isReady = false;
        PurpleMediaReply.observe(0, tlVoiceMsg(88997852, 0, 7712345678L, true, 1050), true, false);
        AndroidUtilities.drain();
        PurpleAccountOwner.isReady = true;

        // chat_missing & chat_cannot_send
        MessagesController.chats.put(7712345678L, null);
        PurpleMediaReply.observe(0, tlVoiceMsg(88997853, 7712345678L, 0, true, 1050), true, false);
        AndroidUtilities.drain();
        TLRPC.Chat lockedGroup = new TLRPC.Chat();
        lockedGroup.allowed = false;
        MessagesController.chats.put(7712345678L, lockedGroup);
        PurpleMediaReply.observe(0, tlVoiceMsg(88997854, 7712345678L, 0, true, 1050), true, false);
        AndroidUtilities.drain();
        MessagesController.chats.put(7712345678L, basicGroup);

        // settings_missing & settings_stale
        PurpleAccountSettings.Effective savedEff = PurpleAccountSettings.effective;
        PurpleAccountSettings.effective = null;
        PurpleMediaReply.observe(0, tlVoiceMsg(88997855, 7712345678L, 0, true, 1050), true, false);
        AndroidUtilities.drain();
        PurpleAccountSettings.effective = savedEff;
        PurpleAccountFiles.identity = new PurpleAccountFiles.Identity("0-101", 3);
        PurpleMediaReply.observe(0, tlVoiceMsg(88997856, 7712345678L, 0, true, 1050), true, false);
        AndroidUtilities.drain();
        resetAccount("0-101", 1, "[media_reply.private]\nvoice='both'\n[media_reply.groups]\nvoice='both'\n");
        ConnectionsManager.now = 1050;
        PurpleMediaReply.observe(0, tlVoiceMsg(88997857, 7712345678L, 0, true, 1050), true, false);
        AndroidUtilities.drain();
        ConnectionsManager.now = 1060;

        // plan_null (date < cutoff -> negative delta preserved via delta_sign=neg date_vs_cutoff_s=50)
        PurpleMediaReply.observe(0, tlVoiceMsg(88997858, 7712345678L, 0, true, 1000), true, false);
        AndroidUtilities.drain();

        // reserve_duplicate & inflight_limit
        TLRPC.Message dup = tlVoiceMsg(88997859, 7712345678L, 0, true, 1060);
        PurpleMediaReply.observe(0, dup, true, false);
        AndroidUtilities.drain();
        PurpleMediaReply.observe(0, dup, true, false);
        AndroidUtilities.drain();
        for (int i = 0; i < 8; ++i) {
            PurpleMediaReply.observe(0, tlVoiceMsg(88997860 + i, 7712345678L, 0, true, 1060), true, false);
        }
        AndroidUtilities.drain();
        // trigger RPC result error variants
        int cbBase = ConnectionsManager.callbacks.size() - 8;
        TLRPC.TL_error ePay = new TLRPC.TL_error(); ePay.text = "ALLOW_PAYMENT_REQUIRED_50";
        TLRPC.TL_error eSlow = new TLRPC.TL_error(); eSlow.text = "SLOWMODE_WAIT_99887766";
        TLRPC.TL_error eBan = new TLRPC.TL_error(); eBan.text = "USER_BANNED_IN_CHANNEL";
        TLRPC.TL_error eCanary = new TLRPC.TL_error(); eCanary.text = "SECRET_CANARY_987654";
        ConnectionsManager.callbacks.get(cbBase).run(null, ePay);
        ConnectionsManager.callbacks.get(cbBase + 1).run(null, eSlow);
        ConnectionsManager.callbacks.get(cbBase + 2).run(null, eBan);
        ConnectionsManager.callbacks.get(cbBase + 3).run(null, eCanary);
        ConnectionsManager.callbacks.get(cbBase + 4).run(new org.telegram.tgnet.TLObject(), null);
        AndroidUtilities.drain();

        // storage_replace_failed & state_failed
        ConnectionsManager.now = 1000;
        resetAccount("0-102", 1, "[media_reply.private]\nvoice='both'\n");
        PurpleMediaReply.observe(0, tlVoiceMsg(88997900, 0, 7712345678L, true, 1000), true, false);
        AndroidUtilities.drain();
        ConnectionsManager.now = 1005;
        android.system.Os.fault = "readback";
        PurpleMediaReply.observe(0, tlVoiceMsg(88997901, 0, 7712345678L, true, 1005), true, false);
        AndroidUtilities.drain();
        android.system.Os.fault = "";
        PurpleMediaReply.observe(0, tlVoiceMsg(88997902, 0, 7712345678L, true, 1005), true, false);
        AndroidUtilities.drain();

        // journal_unreadable
        ConnectionsManager.now = 1000;
        resetAccount("0-103", 1, "[media_reply.private]\nvoice='both'\n");
        PurpleMediaReply.observe(0, tlVoiceMsg(88997910, 0, 7712345678L, true, 1000), true, false);
        AndroidUtilities.drain();
        ConnectionsManager.now = 1005;
        Files.write(Paths.get(System.getProperty("scratch"), "media-reply-journal.json"), "{corrupt".getBytes(StandardCharsets.UTF_8));
        PurpleMediaReply.observe(0, tlVoiceMsg(88997911, 0, 7712345678L, true, 1005), true, false);
        AndroidUtilities.drain();

        List<String> rejLines = mediaLines(rejStart);
        check(hasLine(rejLines, "media_reply observe", "reason=null_raw"), "null_raw");
        check(hasLine(rejLines, "media_reply observe", "reason=nonpositive_id"), "nonpositive_id");
        check(hasLine(rejLines, "media_reply observe", "reason=unsent", "send_state=sending"), "unsent");
        check(hasLine(rejLines, "media_reply observe", "reason=scheduled", "from_scheduled=1"), "scheduled");
        check(hasLine(rejLines, "media_reply observe", "reason=quick_reply", "quick_reply=1"), "quick_reply");
        check(hasLine(rejLines, "media_reply observe", "reason=null_peer", "has_peer=0"), "null_peer");
        check(hasLine(rejLines, "media_reply observe", "reason=service_action", "action_blocked=1"), "service_action");
        check(hasLine(rejLines, "media_reply observe", "reason=not_media", "media=other"), "not_media");
        check(hasLine(rejLines, "media_reply observe", "reason=incoming_ack", "ack=1", "own=0"), "incoming_ack");
        check(hasLine(rejLines, "media_reply observe", "reason=future_date", "future_s=99999"), "future_date clamped");
        check(hasLine(rejLines, "media_reply observe", "reason=identity_missing"), "identity_missing");
        check(hasLine(rejLines, "media_reply observe", "reason=zero_dialog_id"), "zero_dialog_id");
        check(hasLine(rejLines, "media_reply observe", "reason=queue_full"), "queue_full");
        check(hasLine(rejLines, "media_reply dispatch_reject", "stage=ui", "reason=revision_stale"), "revision_stale");
        check(hasLine(rejLines, "media_reply dispatch_reject", "stage=ui", "reason=identity_stale"), "identity_stale");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=owner_not_ready"), "owner_not_ready");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=chat_missing"), "chat_missing");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=chat_cannot_send"), "chat_cannot_send");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=settings_missing"), "settings_missing");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=settings_stale"), "settings_stale");
        check(hasLine(rejLines, "media_reply plan_result", "outcome=null", "reason=plan_null", "delta_sign=neg", "date_vs_cutoff_s=50"), "plan_null delta");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=reserve_duplicate"), "reserve_duplicate");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=inflight_limit"), "inflight_limit");
        check(hasLine(rejLines, "media_reply rpc_result", "outcome=error", "reason=payment", "rpc_err=payment"), "rpc_result payment");
        check(hasLine(rejLines, "media_reply rpc_result", "outcome=error", "rpc_err=slowmode"), "rpc_result slowmode");
        check(hasLine(rejLines, "media_reply rpc_result", "outcome=error", "reason=permission", "rpc_err=banned"), "rpc_result banned");
        check(hasLine(rejLines, "media_reply rpc_result", "outcome=error", "reason=uncertain", "rpc_err=other"), "rpc_result canary scrubbed");
        check(hasLine(rejLines, "media_reply rpc_result", "outcome=uncertain"), "rpc_result non-Updates uncertain");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=storage_replace_failed", "exception=io"), "storage_replace_failed");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=state_failed"), "state_failed");
        check(hasLine(rejLines, "media_reply dispatch_reject", "reason=journal_unreadable", "exception=io"), "journal_unreadable");
        check(hasLine(rejLines, "media_reply invalidate", "cancelled="), "invalidate cancellation logged");

        // 7. Export file + privacy audit across all emitted lines & exported report.
        List<String> allEmitted = mediaLines(0);
        check(allEmitted.size() >= 40, "substantial diagnostic trace recorded");
        for (int i = 0; i < allEmitted.size(); ++i) {
            verifyLineClean(allEmitted.get(i), "FileLog line " + i);
        }
        final File[] exported = new File[1];
        final CountDownLatch exportLatch = new CountDownLatch(1);
        PurpleDiagLog.export(file -> {
            exported[0] = file;
            exportLatch.countDown();
        });
        long deadline = System.currentTimeMillis() + 5000L;
        while (exportLatch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            AndroidUtilities.drain();
            if (exportLatch.getCount() > 0) Thread.sleep(10L);
        }
        check(exported[0] != null && exported[0].isFile(), "PurpleDiagLog export succeeded");
        String exportText = new String(Files.readAllBytes(exported[0].toPath()), StandardCharsets.UTF_8);
        check(exportText.contains("media_reply") && !exportText.toLowerCase().contains("canary")
                && !exportText.contains("7712345678") && !exportText.contains("88997766"), "exported file privacy clean");

        // 8. Logging failure non-interference (identical admission and guard behavior when FileLog fails).
        ConnectionsManager.now = 1000;
        resetAccount("0-104", 1, "[media_reply.private]\nvoice='both'\n[media_reply.groups]\nvoice='both'\n");
        ConnectionsManager.requests.clear();
        try {
            FileLog.injectedFailure = new RuntimeException("diag-boom");
            MessagesController.chats.put(7712345678L, basicGroup);
            PurpleMediaReply.observe(0, tlVoiceMsg(88997920, 7712345678L, 0, true, 1000), true, false);
            AndroidUtilities.drain();
            check(ConnectionsManager.requests.isEmpty(), "cutoff seed guard holds under log failure");
            ConnectionsManager.now = 1005;
            TLRPC.Message okGroupVoice = tlVoiceMsg(88997921, 7712345678L, 0, true, 1005);
            PurpleMediaReplyDiagnostics.AckTrace ackDuringFail =
                    PurpleMediaReplyDiagnostics.singleAckEnter(0, null, null, okGroupVoice, false);
            check(ackDuringFail != null, "ackEnter returns safely on logging failure");
            ackDuringFail.singlePrepared(okGroupVoice, null, false, false);
            PurpleMediaReply.observe(0, okGroupVoice, true, false, ackDuringFail.seq);
            AndroidUtilities.drain();
            check(ConnectionsManager.requests.size() == 1, "admission & enqueue succeed identically under log failure");
            PurpleMediaReply.observe(0, tlVoiceMsg(88997922, 7712345678L, 0, true, 999), true, false);
            AndroidUtilities.drain();
            check(ConnectionsManager.requests.size() == 1, "before-cutoff guard holds under log failure");
        } finally {
            FileLog.injectedFailure = null;
        }

        System.out.println("MEDIA_REPLY_DIAG_CHECKS=" + checks);
    }
}
