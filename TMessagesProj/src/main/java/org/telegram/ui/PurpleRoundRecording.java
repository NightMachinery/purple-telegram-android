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
    private Completion pending;
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
            quote = chat.getReplyQuote(); arguments = PurpleRecordingSendAs.freeze(account, user, dialog, chat.getMessageChatSendParams());
            mono = chat.getSendMonoForumPeerId(); suggestion = chat.getSendMessageSuggestionParams();
        }
        Target(Target original, long dialog, MessageObject top) {
            account = original.account; user = original.user; this.dialog = dialog; this.top = top;
            reply = null; quote = null; mono = 0; suggestion = null;
            arguments = PurpleRecordingSendAs.freeze(account, user, dialog, null);
        }
    }
    PurpleRoundRecording(ChatActivity chat) { this.chat = chat; }
    void started() {
        captureGeneration++;
        managed = true; floated = false; destroyed = false; completionReceived = false; finalizing = null;
        pending = null;
        target = new Target(chat);
        preview = null;
    }
    void resized(boolean small) {
        floated |= small;
        PurpleRecordingDock.floatRound(chat.getCurrentAccount(), chat.getClassGuid(), small);
        if (!small && target != null && chat.chatActivityEnterView != null) chat.chatActivityEnterView.purpleRestoreRoundUi(preview);
    }
    void traceBackground() { if (managed && target != null) PurpleMediaTrace.event(target.account, chat.getClassGuid(), PurpleMediaTrace.Step.CHAT_HIDDEN); }
    void traceForeground() { if (managed && target != null) PurpleMediaTrace.event(target.account, chat.getClassGuid(), PurpleMediaTrace.Step.CHAT_VISIBLE); }
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
    void abandon() { target = null; finalizing = null; preview = null; pending = null; }
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
        if (pending != null) {
            if (pending.entry.path.equals(entry.path) && !info.notReadyYet) pending.info = info;
            return true;
        }
        finalizing = frozen;
        target = null; preview = null;
        pending = new Completion(frozen, entry, info, notify, date, repeat, stars);
        pending.retain(0);
        if (!destroyed) chat.purpleRoundSent(false);
        return true;
    }
    private final class Completion {
        final Target frozen;
        final MediaController.PhotoEntry entry;
        VideoEditedInfo info;
        final boolean notify;
        final int date, repeat;
        final long stars, generation = captureGeneration;
        final long[] context;
        Completion(Target frozen, MediaController.PhotoEntry entry, VideoEditedInfo info,
                boolean notify, int date, int repeat, long stars) {
            this.frozen = frozen; this.entry = entry; this.info = info;
            this.notify = notify; this.date = date; this.repeat = repeat; this.stars = stars;
            context = new long[]{frozen.account, frozen.dialog, frozen.top == null ? 0 : frozen.top.getId(),
                    frozen.reply == null ? 0 : frozen.reply.getId(), frozen.user,
                    entry.ttl == 0x7fffffff ? PurpleRecordingSessions.FLAG_ONCE : 0};
        }
        boolean current() { return pending == this && generation == captureGeneration; }
        void retain(int attempt) {
            if (!current()) return;
            if (attempt == 120) {
                PurpleRecordingRegistry.retainFailedRound(frozen.account, chat.getClassGuid(), entry.path, context,
                        retained -> retained(retained, false));
            } else if (info.notReadyYet) {
                AndroidUtilities.runOnUIThread(() -> retain(attempt + 1), 250);
            } else {
                PurpleRecordingRegistry.retainRound(frozen.account, chat.getClassGuid(), entry.path, context, retained -> {
                    if (!current()) return;
                    if (retained == null) AndroidUtilities.runOnUIThread(() -> retain(attempt + 1), 250);
                    else retained(retained, true);
                });
            }
        }
        void retained(String retained, boolean complete) {
            if (!current() || retained == null) return;
            pending = null; completionReceived = true; completedPath = entry.path; finalizing = null;
            PurpleRecordingDock.roundEnded(frozen.account, chat.getClassGuid());
            final long dockGeneration = PurpleRecordingDock.state.generation;
            if (chat.instantCameraView != null) {
                chat.instantCameraView.hideCamera(true);
                chat.instantCameraView.setVisibility(View.INVISIBLE);
            }
            if (!complete || !PurpleRecordingSendAs.sameUser(frozen.account, frozen.user)) return;
            PurpleRecordingRegistry.retainedRound(retained, original -> {
                if (original != null) deliver(retained, original, dockGeneration);
            });
        }
        void deliver(String retained, PurpleRecordingEntry original, long dockGeneration) {
            if (!PurpleRecordingSendAs.sameUser(frozen.account, frozen.user)) return;
            final Target destination = frozen;
            final MediaController.PhotoEntry media = entry;
            final VideoEditedInfo video = info;
            final boolean sendNotify = notify;
            final int sendDate = date, sendRepeat = repeat;
            final long sourceGeneration = generation;
            final java.lang.ref.WeakReference<PurpleRoundRecording> owner = new java.lang.ref.WeakReference<>(PurpleRoundRecording.this);
            if (original.durationMs > PurpleLongRoundPlan.CIRCLE_MS) {
                final long selectedStartUs = video.startTime, selectedEndUs = video.endTime;
                PurpleLongRoundSend.review(chat, original, destination.account, destination.user, destination.dialog,
                        destination.arguments, (output, arguments, paid) ->
                                SendMessagesHelper.prepareSendingVideo(AccountInstance.getInstance(destination.account),
                                        output.file.getAbsolutePath(), output.info(), media.coverPath, media.coverPhoto,
                                        destination.dialog, destination.reply, destination.top, null, destination.quote,
                                        media.entities, media.ttl, null, sendNotify, sendDate, sendRepeat, false,
                                        media.hasSpoiler, media.caption, arguments, media.effectId, paid,
                                        destination.mono, destination.suggestion), () -> {
                            final PurpleRoundRecording active = owner.get();
                            if (active != null) active.cleanCompose(sourceGeneration, dockGeneration, destination.reply, destination.quote);
                        }, selectedStartUs, selectedEndUs);
                return;
            }
            final java.util.concurrent.atomic.AtomicBoolean approved = new java.util.concurrent.atomic.AtomicBoolean();
            AlertsCreator.ensurePaidMessageConfirmation(destination.account, destination.dialog, 1, paid -> {
                if (!approved.compareAndSet(false, true)) return;
                if (!PurpleRecordingSendAs.sameUser(destination.account, destination.user)) return;
                PurpleRecordingDelivery.send(retained, destination.account, destination.user, destination.dialog, destination.arguments, () -> {
                    SendMessagesHelper.prepareSendingVideo(AccountInstance.getInstance(destination.account), media.path, video,
                            media.coverPath, media.coverPhoto, destination.dialog, destination.reply, destination.top, null,
                            destination.quote, media.entities, media.ttl, null, sendNotify, sendDate, sendRepeat, false, media.hasSpoiler,
                            media.caption, destination.arguments, media.effectId, paid, destination.mono, destination.suggestion);
                }, () -> {
                    final PurpleRoundRecording active = owner.get();
                    if (active != null) active.cleanCompose(sourceGeneration, dockGeneration, destination.reply, destination.quote);
                });
            }, stars);
        }
    }
    private void cleanCompose(long generation, long dockGeneration, MessageObject reply, ChatActivity.ReplyQuote quote) {
        if (generation != captureGeneration || dockGeneration != PurpleRecordingDock.state.generation || destroyed || floated || chat.getReplyMessage() != reply || chat.getReplyQuote() != quote) return;
        final CharSequence text = chat.chatActivityEnterView == null ? null : chat.chatActivityEnterView.getFieldText();
        if (text == null || text.length() == 0) chat.purpleRoundSent(true);
    }
    void beforeLeave() { PurpleRoundBubble.expand(chat.instantCameraView); }
    void destroy() {
        destroyed = true; target = null; preview = null;
        if (finalizing == null) PurpleRecordingDock.roundEnded(chat.getCurrentAccount(), chat.getClassGuid());
    }
}
