package org.telegram.ui;

import android.view.View;
import org.telegram.messenger.*;
import org.telegram.messenger.purple.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.PurpleRoundBubble;
import org.telegram.ui.Components.AlertsCreator;

final class PurpleRoundRecording {
    private final ChatActivity chat;
    private Target target;
    private Object[] preview;
    private boolean managed;
    private static final class Target {
        int account;
        long user, dialog, mono;
        MessageObject reply, top;
        ChatActivity.ReplyQuote quote;
        SendMessageChatArguments arguments;
        org.telegram.messenger.MessageSuggestionParams suggestion;
    }
    PurpleRoundRecording(ChatActivity chat) { this.chat = chat; }
    void started() {
        managed = true;
        target = new Target();
        target.account = chat.getCurrentAccount(); target.user = UserConfig.getInstance(target.account).getClientUserId();
        target.dialog = chat.getDialogId(); target.reply = chat.getReplyMessage(); target.top = chat.getThreadMessage();
        target.quote = chat.getReplyQuote(); target.arguments = PurpleRecordingSendAs.freeze(target.account, target.dialog, chat.getMessageChatSendParams());
        target.mono = chat.getSendMonoForumPeerId(); target.suggestion = chat.getSendMessageSuggestionParams();
        preview = null;
    }
    void resized(boolean small) {
        PurpleRecordingDock.floatRound(chat.getCurrentAccount(), chat.getClassGuid(), small);
        if (!small && target != null && chat.chatActivityEnterView != null) chat.chatActivityEnterView.purpleRestoreRoundUi(preview);
    }
    void pause() { if (chat.instantCameraView != null) chat.instantCameraView.togglePause(); }
    void preview() {
        if (chat.instantCameraView == null || chat.isPaused()) return;
        PurpleRoundBubble.expand(chat.instantCameraView);
        if (!chat.instantCameraView.isPaused()) chat.instantCameraView.togglePause();
    }
    void ready(Object[] args) {
        preview = args.clone();
        if (!PurpleRoundBubble.small(chat.instantCameraView) && chat.chatActivityEnterView != null) chat.chatActivityEnterView.purpleRestoreRoundUi(preview);
    }
    void send(boolean notify, int date, long stars) {
        if (chat.instantCameraView == null || chat.isPaused()) return;
        chat.instantCameraView.send(chat.instantCameraView.isPaused() ? 4 : 1, notify, date, 0,
                PurpleRecordingDock.state.once ? 0x7fffffff : 0, 0, stars);
    }
    void abandon() { target = null; preview = null; }
    void discard() {
        target = null; preview = null;
        if (chat.instantCameraView != null) chat.instantCameraView.cancel(false);
    }
    void retarget(long dialog, MessageObject top) {
        if (target == null) return;
        target.dialog = dialog; target.top = top; target.reply = null; target.quote = null;
        target.mono = 0; target.arguments = PurpleRecordingSendAs.freeze(target.account, dialog, null); target.suggestion = null;
    }
    boolean sendMedia(MediaController.PhotoEntry entry, VideoEditedInfo info, boolean notify, int date, int repeat, long stars) {
        if (entry == null || info == null || !info.roundVideo) return false;
        if (target == null) return managed;
        final Target frozen = target;
        target = null; preview = null;
        if (UserConfig.getInstance(frozen.account).getClientUserId() != frozen.user) return true;
        AlertsCreator.ensurePaidMessageConfirmation(frozen.account, frozen.dialog, 1, paid -> {
            if (UserConfig.getInstance(frozen.account).getClientUserId() != frozen.user) return;
            SendMessagesHelper.prepareSendingVideo(AccountInstance.getInstance(frozen.account), entry.path, info,
                    entry.coverPath, entry.coverPhoto, frozen.dialog, frozen.reply, frozen.top, null,
                    frozen.quote, entry.entities, entry.ttl, null, notify, date, repeat, false, entry.hasSpoiler,
                    entry.caption, frozen.arguments, entry.effectId, paid, frozen.mono, frozen.suggestion);
        }, stars);
        if (chat.instantCameraView != null) {
            chat.instantCameraView.hideCamera(true);
            chat.instantCameraView.setVisibility(View.INVISIBLE);
        }
        PurpleRecordingDock.roundEnded(chat.getCurrentAccount(), chat.getClassGuid());
        return true;
    }
    void beforeLeave() { PurpleRoundBubble.expand(chat.instantCameraView); }
    void destroy() { target = null; preview = null; PurpleRecordingDock.roundEnded(chat.getCurrentAccount(), chat.getClassGuid()); }
}
