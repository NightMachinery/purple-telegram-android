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
    private boolean managed, floated, destroyed, completionReceived;
    private Target finalizing;
    private String completedPath;
    private long captureGeneration;
    private static final class Target {
        final int account;
        final long user, dialog, mono;
        final MessageObject reply, top;
        final ChatActivity.ReplyQuote quote;
        final SendMessageChatArguments arguments;
        final MessageSuggestionParams suggestion;
        Target(ChatActivity chat) {
            account = chat.getCurrentAccount(); user = UserConfig.getInstance(account).getClientUserId();
            dialog = chat.getDialogId(); reply = chat.getReplyMessage(); top = chat.getThreadMessage();
            quote = chat.getReplyQuote(); arguments = PurpleRecordingSendAs.freeze(account, dialog, chat.getMessageChatSendParams());
            mono = chat.getSendMonoForumPeerId(); suggestion = chat.getSendMessageSuggestionParams();
        }
        Target(Target original, long dialog, MessageObject top) {
            account = original.account; user = original.user; this.dialog = dialog; this.top = top;
            reply = null; quote = null; mono = 0; suggestion = null;
            arguments = PurpleRecordingSendAs.freeze(account, dialog, null);
        }
    }
    PurpleRoundRecording(ChatActivity chat) { this.chat = chat; }
    void started() {
        captureGeneration++;
        managed = true; floated = false; destroyed = false; completionReceived = false; finalizing = null;
        target = new Target(chat);
        preview = null;
    }
    void resized(boolean small) {
        floated |= small;
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
    void beginFinalization() {
        if (finalizing == null) { finalizing = target; target = null; }
    }
    void abandon() { target = null; finalizing = null; preview = null; }
    void discard() {
        abandon();
        if (chat.instantCameraView != null) chat.instantCameraView.cancel(false);
    }
    void retarget(long dialog, MessageObject top) {
        if (target == null) return;
        target = new Target(target, dialog, top);
    }
    boolean sendMedia(MediaController.PhotoEntry entry, VideoEditedInfo info, boolean notify, int date, int repeat, long stars) {
        if (entry == null || info == null || !info.roundVideo) return false;
        if (completionReceived || entry.path.equals(completedPath)) return managed;
        final Target frozen = finalizing != null ? finalizing : target;
        if (frozen == null) return managed;
        completionReceived = true;
        completedPath = entry.path;
        target = null; preview = null;
        if (UserConfig.getInstance(frozen.account).getClientUserId() != frozen.user) return true;
        final long generation = captureGeneration;
        final java.lang.ref.WeakReference<PurpleRoundRecording> owner = new java.lang.ref.WeakReference<>(this);
        final MessageObject originalReply = frozen.reply;
        final ChatActivity.ReplyQuote originalQuote = frozen.quote;
        final long[] context = {frozen.account, frozen.dialog, frozen.top == null ? 0 : frozen.top.getId(),
                frozen.reply == null ? 0 : frozen.reply.getId(), frozen.user, entry.ttl == 0x7fffffff ? PurpleRecordingSessions.FLAG_ONCE : 0};
        PurpleRecordingRegistry.retainRound(frozen.account, chat.getClassGuid(), entry.path, context, retained -> {
            if (retained == null) return;
            finalizing = null;
            PurpleRecordingDock.roundEnded(frozen.account, chat.getClassGuid());
            if (chat.instantCameraView != null) {
                chat.instantCameraView.hideCamera(true);
                chat.instantCameraView.setVisibility(View.INVISIBLE);
            }
            if (UserConfig.getInstance(frozen.account).getClientUserId() != frozen.user) return;
            final java.util.concurrent.atomic.AtomicBoolean approved = new java.util.concurrent.atomic.AtomicBoolean();
            AlertsCreator.ensurePaidMessageConfirmation(frozen.account, frozen.dialog, 1, paid -> {
                if (!approved.compareAndSet(false, true)) return;
                if (UserConfig.getInstance(frozen.account).getClientUserId() != frozen.user) return;
                PurpleRecordingDelivery.send(retained, frozen.account, frozen.dialog, frozen.arguments, () -> {
                    SendMessagesHelper.prepareSendingVideo(AccountInstance.getInstance(frozen.account), entry.path, info,
                            entry.coverPath, entry.coverPhoto, frozen.dialog, frozen.reply, frozen.top, null,
                            frozen.quote, entry.entities, entry.ttl, null, notify, date, repeat, false, entry.hasSpoiler,
                            entry.caption, frozen.arguments, entry.effectId, paid, frozen.mono, frozen.suggestion);
                }, () -> {
                    final PurpleRoundRecording active = owner.get();
                    if (active != null) active.cleanCompose(generation, originalReply, originalQuote);
                });
            }, stars);
        });
        if (!destroyed) chat.purpleRoundSent(false);
        return true;
    }
    private void cleanCompose(long generation, MessageObject reply, ChatActivity.ReplyQuote quote) {
        if (generation != captureGeneration || destroyed || floated || chat.getReplyMessage() != reply || chat.getReplyQuote() != quote) return;
        final CharSequence text = chat.chatActivityEnterView == null ? null : chat.chatActivityEnterView.getFieldText();
        if (text == null || text.length() == 0) chat.purpleRoundSent(true);
    }
    void beforeLeave() { PurpleRoundBubble.expand(chat.instantCameraView); }
    void destroy() {
        destroyed = true; target = null; preview = null;
        if (finalizing == null) PurpleRecordingDock.roundEnded(chat.getCurrentAccount(), chat.getClassGuid());
    }
}
