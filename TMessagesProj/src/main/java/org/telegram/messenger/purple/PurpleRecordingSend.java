/*
 * This is the source code of Purple Telegram for Android.
 *
 * Sends a Recording bin item through upstream's own routes, so the message
 * looks exactly like a normally recorded one (docs/media-recording-design.md,
 * "The Recording bin").
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.IOException;

public final class PurpleRecordingSend {

    private PurpleRecordingSend() {
    }

    /**
     * Sends {@code item} to {@code dialogId} (and topic) from {@code account},
     * replying to {@code replyId} when that message still exists. The file
     * leaves the bin first, into the directory upstream sends that kind from,
     * because upstream uploads and caches the file at its path after this
     * returns. Runs the file work on the registry's thread; {@code done} gets
     * whether the send was handed over, on the UI thread.
     */
    public static void send(PurpleRecordingEntry item, int account, long dialogId, long topicId, int replyId,
            Done done) {
        PurpleRecordingRegistry.onWorker(() -> {
            boolean ok = false;
            try {
                final File source = new File(item.media);
                if (!source.isFile()) throw new IOException("gone");
                final MessagesStorage storage = MessagesStorage.getInstance(account);
                final TLRPC.Message reply = replyId == 0 ? null : storage.getMessage(dialogId, replyId);
                final TLRPC.Message top = topicId == 0 ? null : storage.getMessage(dialogId, topicId);
                final MessageObject replyObject = reply == null ? null : new MessageObject(account, reply, false, false);
                final MessageObject topObject = top == null ? null : new MessageObject(account, top, false, false);
                if (item.round()) {
                    ok = sendRound(item, source, account, dialogId, replyObject, topObject);
                } else {
                    ok = sendVoice(item, source, account, dialogId, replyObject, topObject);
                }
            } catch (Throwable e) {
                PurpleDiagLog.event("recordings", "bin_send", PurpleDiagLog.reason("io_failed"));
            }
            final boolean handed = ok;
            if (handed) PurpleRecordingRegistry.sent(item.id);
            AndroidUtilities.runOnUIThread(() -> done.done(handed));
        });
    }

    public interface Done {
        void done(boolean ok);
    }

    /** As MediaController.startRecording and stopRecordingInternal build a voice message. */
    private static boolean sendVoice(PurpleRecordingEntry item, File source, int account, long dialogId,
            MessageObject reply, MessageObject top) throws IOException {
        final TLRPC.TL_document document = new TLRPC.TL_document();
        document.file_reference = new byte[0];
        document.dc_id = Integer.MIN_VALUE;
        document.id = SharedConfig.getLastLocalId();
        document.user_id = UserConfig.getInstance(account).getClientUserId();
        document.mime_type = "audio/ogg";
        SharedConfig.saveConfig();
        final File target = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO),
                System.currentTimeMillis() + "_" + FileLoader.getAttachFileName(document));
        PurpleRecordingFiles.move(source, target);
        document.date = ConnectionsManager.getInstance(account).getCurrentTime();
        document.size = (int) target.length();
        final TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        audio.voice = true;
        audio.waveform = MediaController.getWaveform(target.getAbsolutePath());
        if (audio.waveform != null) {
            audio.flags |= 4;
        }
        audio.duration = item.durationMs / 1000.0;
        document.attributes.add(audio);
        final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(document, null,
                target.getAbsolutePath(), dialogId, reply, top, null, null, null, null, true, 0, 0, 0, null, null, false);
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.getInstance(account).sendMessage(params));
        return true;
    }

    /** Rounds are sent by PurpleRoundSend (F10 round); a build without it keeps them in the bin. */
    private static boolean sendRound(PurpleRecordingEntry item, File source, int account, long dialogId,
            MessageObject reply, MessageObject top) throws IOException {
        return false;
    }
}
