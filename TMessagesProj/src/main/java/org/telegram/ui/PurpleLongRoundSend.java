package org.telegram.ui;

import android.content.DialogInterface;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.*;
import org.telegram.messenger.purple.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;

public final class PurpleLongRoundSend {
    private static final Map<String, Request> active = new HashMap<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread thread = new Thread(r, "PurpleLongRound"); thread.setDaemon(true); return thread;
    });
    public interface Sender {
        void send(PurpleLongRoundMedia.Output output, SendMessageChatArguments arguments, long paid);
    }
    private PurpleLongRoundSend() { }

    public static boolean fromBin(BaseFragment host, PurpleRecordingEntry item, int account, long dialog,
            long topic, int replyId, boolean original, PurpleRecordingSend.Done done) {
        if (!item.round() || item.durationMs <= PurpleLongRoundPlan.CIRCLE_MS) return false;
        final long user = original ? item.userId : UserConfig.getInstance(account).getClientUserId();
        final SendMessageChatArguments arguments = PurpleRecordingSendAs.freeze(account, user, dialog, null);
        if (arguments == null || original && item.account != account) { done.done(false); return true; }
        final WeakReference<BaseFragment> owner = new WeakReference<>(host);
        worker.execute(() -> {
            MessageObject reply = null, top = null;
            boolean valid = PurpleRecordingSendAs.sameUser(account, user);
            try {
                final MessagesStorage storage = MessagesStorage.getInstance(account);
                final TLRPC.Message replyMessage = replyId == 0 ? null : storage.getMessage(dialog, replyId);
                final TLRPC.Message topMessage = topic == 0 ? null : storage.getMessage(dialog, topic);
                if (topic != 0 && topMessage == null) valid = false;
                if (replyMessage != null) reply = new MessageObject(account, replyMessage, false, false);
                if (topMessage != null) top = new MessageObject(account, topMessage, false, false);
            } catch (Exception failure) { valid = false; }
            final MessageObject frozenReply = reply, frozenTop = top;
            final boolean ready = valid;
            AndroidUtilities.runOnUIThread(() -> {
                final BaseFragment fragment = owner.get();
                if (!ready || fragment == null || fragment.getParentActivity() == null
                        || !PurpleRecordingSendAs.sameUser(account, user)) { done.done(false); return; }
                review(fragment, item, account, user, dialog, arguments, (output, frozen, paid) ->
                        SendMessagesHelper.prepareSendingVideo(AccountInstance.getInstance(account), output.file.getAbsolutePath(),
                                output.info(), null, null, dialog, frozenReply, frozenTop, null, null, null,
                                item.once ? 0x7fffffff : 0, null, true, 0, 0, false, false, null, frozen, 0, paid),
                        () -> done.done(true), -1, -1);
            });
        });
        return true;
    }

    public static void review(BaseFragment host, PurpleRecordingEntry item, int account, long user, long dialog,
            SendMessageChatArguments arguments, Sender sender, Runnable done, long startUs, long endUs) {
        if (host.getParentActivity() == null || host.isPaused()
                || !PurpleRecordingSendAs.sameUser(account, user)) return;
        final Request previous = active.get(item.id);
        if (previous != null) {
            if (!previous.replaceablePayment(account, user)) return;
            previous.finish(false);
        }
        final Request request = new Request(host, item, account, user, dialog, arguments, sender, done, startUs, endUs);
        active.put(item.id, request);
        request.prepare(true);
        AndroidUtilities.runOnUIThread(() -> request.finish(false), 30 * 60_000L);
    }

    private static final class Request {
        final WeakReference<BaseFragment> owner;
        final PurpleRecordingEntry item;
        final int account;
        final long user, dialog, startUs, endUs;
        final Sender sender;
        final Runnable done;
        final SendMessageChatArguments[] arguments = new SendMessageChatArguments[6];
        final PurpleLongRoundMedia.Work work = new PurpleLongRoundMedia.Work();
        PurpleLongRoundQueue queue;
        List<PurpleLongRoundMedia.Output> outputs;
        AlertDialog progress;
        boolean closed, preparing, awaitingPayment;
        int paymentGeneration;
        Request(BaseFragment host, PurpleRecordingEntry item, int account, long user, long dialog,
                SendMessageChatArguments original, Sender sender, Runnable done, long startUs, long endUs) {
            owner = new WeakReference<>(host); this.item = item; this.account = account; this.user = user;
            this.startUs = startUs; this.endUs = endUs;
            this.dialog = dialog; this.sender = sender; this.done = done;
            for (int i = 0; i < arguments.length; i++) arguments[i] = PurpleRecordingSendAs.copyFrozen(account, user, dialog, original);
        }
        BaseFragment host() {
            final BaseFragment host = owner.get();
            return !closed && host != null && host.getParentActivity() != null && !host.isPaused()
                    && PurpleRecordingSendAs.sameUser(account, user) ? host : null;
        }
        void prepare(boolean circles) {
            final BaseFragment host = host();
            if (host == null || arguments[0] == null) { finish(false); return; }
            final AlertDialog spinner = new AlertDialog(host.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, host.getResourceProvider());
            progress = spinner;
            spinner.setMessage(text(R.string.PurpleLongRoundPreparing));
            spinner.setOnCancelListener(dialog -> finish(false));
            if (!showOwnedDialog(host, spinner, dialog -> { if (progress == spinner) finish(false); })) return;
            spinner.setCanceledOnTouchOutside(false);
            preparing = true;
            worker.execute(() -> {
                List<PurpleLongRoundMedia.Output> result = null;
                boolean invalid = false;
                try {
                    work.discard();
                    if (!PurpleRecordingSendAs.sameUser(account, user)) throw new IllegalStateException();
                    result = PurpleLongRoundMedia.prepare(new File(item.media), FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT), circles, startUs, endUs, work);
                } catch (Exception failure) {
                    invalid = failure instanceof PurpleLongRoundSelection.Invalid;
                    PurpleDiagLog.event("recordings", "long_prepare", PurpleDiagLog.reason("io_failed"));
                }
                final List<PurpleLongRoundMedia.Output> prepared = result;
                final boolean invalidSelection = invalid;
                AndroidUtilities.runOnUIThread(() -> {
                    preparing = false;
                    if (progress == spinner) { progress = null; spinner.dismiss(); }
                    if (closed) { worker.execute(work::discard); return; }
                    if (host() == null) { finish(false); return; }
                    if (prepared == null) { preparationFailed(circles, invalidSelection); return; }
                    outputs = prepared; queue = new PurpleLongRoundQueue(outputs.size());
                    confirm();
                });
            });
        }
        void preparationFailed(boolean circles, boolean invalidSelection) {
            final BaseFragment host = host();
            if (host == null) { finish(false); return; }
            final AtomicBoolean retry = new AtomicBoolean();
            final AlertDialog.Builder builder = new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider());
            builder.setTitle(text(R.string.PurpleLongRoundTitle));
            builder.setMessage(text(invalidSelection ? R.string.PurpleLongRoundSelectionInvalid : R.string.PurpleLongRoundPrepareFailed));
            builder.setNegativeButton(text(R.string.Cancel), null);
            if (circles && !invalidSelection) builder.setPositiveButton(text(R.string.PurpleLongRoundVideo), (dialog, which) -> {
                if (retry.compareAndSet(false, true)) prepare(false);
            });
            showOwnedDialog(host, builder.create(), dialog -> { if (!retry.get()) finish(false); });
        }

        void confirm() {
            final BaseFragment host = host();
            if (host == null) { finish(false); return; }
            final AtomicBoolean approved = new AtomicBoolean();
            final AlertDialog.Builder builder = new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider());
            builder.setTitle(text(R.string.PurpleLongRoundTitle));
            builder.setMessage(LocaleController.formatString(R.string.PurpleLongRoundConfirm, outputs.size()));
            builder.setNegativeButton(text(R.string.Cancel), null);
            final boolean circles = outputs.get(0).circle;
            builder.setNeutralButton(text(circles ? R.string.PurpleLongRoundVideo : R.string.PurpleLongRoundCircles), (dialog, which) -> {
                if (!approved.compareAndSet(false, true)) return;
                outputs = null; queue = null;
                prepare(!circles);
            });
            builder.setPositiveButton(circles ? LocaleController.formatString(R.string.PurpleLongRoundSendParts, outputs.size())
                    : text(R.string.PurpleLongRoundVideo), (dialog, which) -> {
                if (!approved.compareAndSet(false, true)) return;
                if (host() == null) { finish(false); return; }
                awaitingPayment = true;
                final int generation = ++paymentGeneration;
                AlertsCreator.ensurePaidMessageConfirmation(account, this.dialog, outputs.size(), paid -> {
                    if (closed || !awaitingPayment || generation != paymentGeneration || active.get(item.id) != this) return;
                    awaitingPayment = false;
                    if (host() == null) { finish(false); return; }
                    if (!queue.confirm()) return;
                    enqueue(paid);
                }, 0);
            });
            showOwnedDialog(host, builder.create(), dialog -> { if (!approved.get()) finish(false); });
        }
        boolean showOwnedDialog(BaseFragment host, AlertDialog dialog, DialogInterface.OnDismissListener onDismiss) {
            dialog.setOnDismissListener(onDismiss);
            try {
                if (host.showDialog(dialog, onDismiss) != null) return !closed;
            } catch (Exception failure) {
                PurpleDiagLog.event("recordings", "long_dialog", PurpleDiagLog.reason("io_failed"));
            }
            finish(false);
            try { dialog.dismiss(); }
            catch (Exception failure) { PurpleDiagLog.event("recordings", "long_dialog", PurpleDiagLog.reason("io_failed")); }
            return false;
        }
        boolean replaceablePayment(int account, long user) {
            return !closed && awaitingPayment && this.account == account && this.user == user
                    && queue != null && queue.attempted == 0 && queue.queued == 0;
        }
        void enqueue(long paid) {
            if (host() == null) { finish(true); return; }
            if (queue.queued == queue.count) { done.run(); finish(false); return; }
            final int index = queue.begin();
            if (index < 0) return;
            final PurpleLongRoundMedia.Output output = outputs.get(index);
            PurpleRecordingDelivery.sendRetained(item.id, account, user, dialog, arguments[index], () -> {
                if (closed || !queue.waiting(index) || !PurpleRecordingSendAs.sameUser(account, user)) { finish(true); return; }
                work.handed(output.file);
                try { sender.send(output, arguments[index], paid); }
                catch (Exception failure) { finish(true); }
            }, () -> {
                if (!closed && queue.acknowledge(index)) enqueue(paid);
            }, () -> finish(true));
            AndroidUtilities.runOnUIThread(() -> { if (!closed && queue.waiting(index)) finish(true); }, 60_000);
        }
        void finish(boolean error) {
            if (closed) return;
            final BaseFragment host = host();
            closed = true; awaitingPayment = false; paymentGeneration++; work.cancelled.set(true);
            if (queue != null) queue.stop();
            PurpleRecordingDelivery.revoke(item.id);
            active.remove(item.id, this);
            if (progress != null) { progress.dismiss(); progress = null; }
            if (!preparing) worker.execute(work::discard);
            if (error && host != null) {
                final int count = queue == null ? 0 : queue.queued;
                new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                        .setTitle(text(R.string.PurpleLongRoundTitle))
                        .setMessage(queue == null || queue.attempted == 0 ? text(R.string.PurpleLongRoundPrepareFailed)
                                : LocaleController.formatString(R.string.PurpleLongRoundFailed, count))
                        .setPositiveButton(text(R.string.OK), null).show();
            }
        }
    }
    private static String text(int resource) { return LocaleController.getString(resource); }
}
