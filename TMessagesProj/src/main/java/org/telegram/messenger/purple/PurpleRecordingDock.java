package org.telegram.messenger.purple;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.widget.Toast;
import org.telegram.ui.Components.AlertsCreator;
import android.os.SystemClock;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.LaunchActivity;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class PurpleRecordingDock {
    public interface Host {
        long[] target();
        boolean scheduled();
        void release();
        default void roundStarted() { }
        default void roundFinishing() { }
        default boolean roundVisible() { return false; }
        default void roundPause() { }
        default void roundPreview() { }
        default void roundSend(boolean notify, int date, long stars) { }
        default void roundDiscard() { }
        default void roundAbandon() { }
        default void roundPreviewReady(Object[] args) { }
        default void roundRetarget(long dialog, MessageObject top) { }

    }
    public static final PurpleRecordingDockState state = new PurpleRecordingDockState();
    private static final Map<Integer, WeakReference<Host>> hosts = new HashMap<>();
    private static final ArrayList<WeakReference<Runnable>> listeners = new ArrayList<>();
    private static boolean started;
    private static boolean previewPending;
    private static boolean targetPickerPending;
    private static final PurpleRecordingPreviewHandoff nativePreview = new PurpleRecordingPreviewHandoff();
    private static boolean nativePreviewScheduled;
    private static boolean serviceReady;
    private static long foregroundSend = -1;
    private static long foregroundTarget = -1;
    private static boolean roundFinalization;
    private static boolean pendingNotify;
    private static int pendingDate;
    private static long pendingStars;
    private static final PurpleRecordingTarget targetSnapshot = new PurpleRecordingTarget();
    private static final int[] EVENTS = {NotificationCenter.recordStarted, NotificationCenter.recordStopped,
            NotificationCenter.recordPaused, NotificationCenter.recordResumed, NotificationCenter.audioDidSent,
            NotificationCenter.audioRecordTooShort, NotificationCenter.recordStartError, NotificationCenter.recordProgressChanged};

    private PurpleRecordingDock() { }

    public static void watch(int guid, Host host) {
        hosts.put(guid, new WeakReference<>(host));
        if (started) return;
        started = true;
        Context context = ApplicationLoader.applicationContext;
        if (context instanceof Application) ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle saved) { }
            public void onActivityStarted(Activity activity) { }
            public void onActivityResumed(Activity activity) { AndroidUtilities.runOnUIThread(PurpleRecordingDock::foreground, 100); }
            public void onActivityPaused(Activity activity) { }
            public void onActivityStopped(Activity activity) { }
            public void onActivitySaveInstanceState(Activity activity, Bundle out) { }
            public void onActivityDestroyed(Activity activity) { }
        });
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            for (int event : EVENTS) NotificationCenter.getInstance(a).addObserver(PurpleRecordingDock::event, event);
        }
    }

    public static void unwatch(int guid) { hosts.remove(guid); }
    public static void listen(Runnable listener) { listeners.add(new WeakReference<>(listener)); }
    private static Host host(int guid) {
        WeakReference<Host> ref = hosts.get(guid);
        return ref == null ? null : ref.get();
    }

    private static void event(int event, int account, Object... args) {
        if (event == NotificationCenter.recordStarted) {
            if (args.length < 2) return;
            int guid = (Integer) args[0];
            boolean round = !(Boolean) args[1];
            nativePreview.clear();
            Host host = host(guid);
            long[] target = host == null ? null : host.target();
            if (target == null || target.length < 6 || target[0] != account) return;
            if ((target[5] & PurpleRecordingSessions.FLAG_QUICK_REPLY) != 0) return;
            if (state.begin(account, guid, target[4], target[1], target[2],
                    (target[5] & PurpleRecordingSessions.FLAG_ONCE) != 0, host.scheduled(), SystemClock.elapsedRealtime())) {
                state.round = round;
                targetSnapshot.capture(guid, target);
                if (round) host.roundStarted();
                else if (PurpleRecordingSettings.read().background) PurpleRecordingService.start();
                changed();
            }
            return;
        }
        if (!state.active() && event == NotificationCenter.audioDidSent && args.length > 1
                && args[0] instanceof Integer && args[1] instanceof TLRPC.Document) {
            int guid = (Integer) args[0];
            Host host = host(guid);
            long[] target = host == null ? null : host.target();
            if (target != null && target.length >= 6 && target[0] == account
                    && (target[5] & PurpleRecordingSessions.FLAG_QUICK_REPLY) == 0) {
                long duration = 0;
                for (TLRPC.DocumentAttribute attribute : ((TLRPC.Document) args[1]).attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeAudio) duration = Math.round(attribute.duration * 1000);
                }
                state.begin(account, guid, target[4], target[1], target[2],
                        (target[5] & PurpleRecordingSessions.FLAG_ONCE) != 0, host.scheduled(), SystemClock.elapsedRealtime() - duration);
                targetSnapshot.capture(guid, target);
                state.pause(true, SystemClock.elapsedRealtime());
                if (!ApplicationLoader.mainInterfacePaused && PurpleRecordingSettings.read().background) PurpleRecordingService.start();
            }
        }
        if (!state.active() || account != state.account) return;
        if (event == NotificationCenter.recordProgressChanged) {
            if (args.length > 1 && args[0] instanceof Integer && state.matches(account, (Integer) args[0]) && args[1] instanceof Number) state.amplitude(((Number) args[1]).doubleValue());
            return;
        }
        if (event == NotificationCenter.recordPaused || event == NotificationCenter.recordResumed) {
            state.pause(event == NotificationCenter.recordPaused, SystemClock.elapsedRealtime());
            if (event == NotificationCenter.recordResumed && !state.round && !ApplicationLoader.mainInterfacePaused && PurpleRecordingSettings.read().background) PurpleRecordingService.start();
            if (event == NotificationCenter.recordResumed && state.sendPending) {
                state.sendPending = false;
                sendApproved(state.generation, pendingNotify, pendingDate, pendingStars);
            }
            if (event == NotificationCenter.recordResumed && !state.finishing && ApplicationLoader.mainInterfacePaused
                    && !PurpleRecordingCore.keepOnPause(false, true, serviceReady) && state.requestPause()) {
                MediaController.getInstance().toggleRecordingPause(state.once);
            }
            changed();
            return;
        }
        if (args.length == 0 || !(args[0] instanceof Integer) || !state.matches(account, (Integer) args[0])) return;
        if (event == NotificationCenter.audioDidSent) {
            if (args.length > 1 && args[1] != null) {
                state.pause(true, SystemClock.elapsedRealtime());
                if (state.round) { Host host = host(state.guid); if (host != null) host.roundPreviewReady(args); changed(); if (targetPickerPending) { targetPickerPending = false; chooseTarget(state.generation); } }
                else if (previewPending) finishPreview();
                else changed();
            } else clear();
        } else if (event == NotificationCenter.audioRecordTooShort || event == NotificationCenter.recordStartError) {
            if (state.round) { Host host = host(state.guid); if (host != null) host.roundAbandon(); }
            clear();
        } else if (event == NotificationCenter.recordStopped) {
            syncOnce();
            int reason = args.length > 1 ? (Integer) args[1] : 0;
            if (state.round && reason == 2) { state.pause(true, SystemClock.elapsedRealtime()); state.transitioning = true; changed(); }
            else if (state.round && reason == 5) { state.finishing = true; changed(); }
            else if (reason != 1) {
                if (state.round) { Host host = host(state.guid); if (host != null) host.roundAbandon(); }
                clear();
            }
        }
    }

    public static boolean dock(int account, int guid) {
        if (state.round || !state.matches(account, guid) || !PurpleRecordingSettings.read().keepAcrossChats || !state.canDock()) return false;
        syncOnce();
        Host host = host(guid);
        if (host != null) host.release();
        state.docked = true;
        targetSnapshot.dock();
        changed();
        return true;
    }

    public static boolean keepsOnChatPause(ChatActivity chat, boolean fromChat) {
        if (!fromChat || !state.active() || state.round) return false;
        if (state.paused) {
            if (state.docked) return true;
            if (state.matches(chat.getCurrentAccount(), chat.getClassGuid())) clear();
            return false;
        }
        boolean background = ApplicationLoader.mainInterfacePaused;
        if (!PurpleRecordingCore.keepOnPause(false, background, serviceReady)) {
            if (!state.docked && state.matches(chat.getCurrentAccount(), chat.getClassGuid())) clear();
            return false;
        }
        if (state.matches(chat.getCurrentAccount(), chat.getClassGuid())) {
            if (!state.docked) {
                syncOnce();
                Host host = host(state.guid);
                if (host != null) host.release();
                state.docked = true;
                targetSnapshot.dock();
                changed();
            }
        }
        return true;
    }

    private static void syncOnce() {
        if (!state.active()) return;
        Host host = host(state.guid);
        if (!state.docked && host != null) {
            long[] target = host.target();
            if (target != null && target.length > 5) state.once = (target[5] & PurpleRecordingSessions.FLAG_ONCE) != 0;
        }
        targetSnapshot.once(state.once);
    }

    public static long[] targetFor(int guid, boolean terminal) {
        return targetSnapshot.forEvent(guid, state.active() && state.docked, terminal);
    }

    public static void draftCleared(int account, int draftKey) {
        if (state.active() && state.account == account && state.paused && !state.docked
                && java.util.Objects.hash(state.dialog, state.topic) == draftKey) clear();
    }

    public static boolean adoptNativePreview(MediaController controller, int account, long dialog, MessageObject top,
            int guid, MediaDataController.DraftVoice draft) {
        long user = UserConfig.getInstance(account).getClientUserId();
        long topic = top == null ? 0 : MessageObject.getTopicId(account, top.messageOwner, false);
        if (state.active() || draft == null || !nativePreview.matches(account, user, dialog, topic)
                || !controller.purpleNativePreviewAvailable(draft.path)) return false;
        long[][] handoff = nativePreview.take(account, user, dialog, topic);
        if (!state.begin(account, guid, user, dialog, topic, (handoff[0][5] & 1) != 0, nativePreviewScheduled, SystemClock.elapsedRealtime() - draft.recordTimeCount)) return false;
        state.pause(true, SystemClock.elapsedRealtime());
        targetSnapshot.capture(guid, handoff[1]); targetSnapshot.dock();
        controller.purpleAdoptNativePreview(guid, draft);
        changed(); return true;
    }

    public static boolean preventsDraftRestore() { return state.active() && state.docked; }

    public static boolean ignoreEvent(int event, int account, int guid) {
        boolean recordingEvent = false;
        for (int id : EVENTS) if (id == event) recordingEvent = true;
        recordingEvent |= event == NotificationCenter.recordProgressChanged;
        return recordingEvent && state.active() && (state.docked || !state.matches(account, guid));
    }

    public static boolean refuseStart() {
        if (!state.active()) return false;
        LaunchActivity activity = LaunchActivity.instance;
        if (activity != null) {
            final long generation = state.generation;
            new AlertDialog.Builder(activity).setTitle(LocaleController.getString(R.string.DiscardVoiceMessageTitle))
                    .setMessage(LocaleController.getString(R.string.DiscardVoiceMessageDescription))
                    .setPositiveButton(LocaleController.getString(R.string.DiscardVoiceMessageAction), (d, w) -> discard(generation))
                    .setNegativeButton(LocaleController.getString(R.string.Continue), null).show();
        }
        return true;
    }

    public static void pause(long generation) {
        if (!valid(generation)) return;
        syncOnce();
        if (state.round && (host(state.guid) == null || !host(state.guid).roundVisible())) return;
        if (state.requestPause()) {
            if (state.round) { Host host = host(state.guid); if (host != null) host.roundPause(); }
            else MediaController.getInstance().toggleRecordingPause(state.once);
        }
    }

    public static void preview(long generation) {
        if (!valid(generation)) return;
        if (state.round) { Host host = host(state.guid); if (host != null) host.roundPreview(); return; }
        if (state.paused) finishPreview();
        else {
            if (state.requestPause()) {
                previewPending = true;
                MediaController.getInstance().toggleRecordingPause(state.once);
            }
        }
    }

    private static void finishPreview() {
        long[] capture = targetSnapshot.forEvent(state.guid, true, true);
        if (capture != null) {
            nativePreview.arm(new long[]{state.account, state.dialog, state.topic, 0, state.user, state.once ? 1 : 0}, capture);
            nativePreviewScheduled = state.scheduled;
        }
        Intent intent = openIntent();
        clear();
        ApplicationLoader.applicationContext.startActivity(intent);
    }

    public static boolean beginRoundFinish(int account, int guid) {
        if (!state.round || !state.matches(account, guid)) return true;
        if (roundFinalization) return false;
        Host host = host(guid);
        if (host != null) host.roundFinishing();
        state.finishing = true;
        roundFinalization = true;
        changed();
        return true;
    }

    public static int roundErrorReason(int account, int guid) {
        return roundFinalizing(account, guid) ? 7 : 6;
    }

    public static boolean roundFinalizing(int account, int guid) {
        return state.round && state.matches(account, guid) && roundFinalization;
    }

    public static void send(long generation) {
        if (!valid(generation) || state.transitioning) return;
        syncOnce();
        if (state.round && (host(state.guid) == null || !host(state.guid).roundVisible())) return;
        if (unavailable()) { chooseTarget(generation); return; }
        MessagesController controller = MessagesController.getInstance(state.account);
        TLRPC.Chat chat = state.dialog < 0 ? controller.getChat(-state.dialog) : null;
        TLRPC.ChatFull full = state.dialog < 0 ? controller.getChatFull(-state.dialog) : null;
        TLRPC.UserFull user = state.dialog > 0 ? controller.getUserFull(state.dialog) : null;
        boolean restricted = chat != null && (!(state.round ? ChatObject.canSendRoundVideo(chat) : ChatObject.canSendVoice(chat)) || ChatObject.isNotInChat(chat));
        restricted |= user != null && user.voice_messages_forbidden;
        boolean slow = full != null && full.slowmode_next_send_date > ConnectionsManager.getInstance(state.account).getCurrentTime();
        if (restricted || slow) {
            Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.PurpleRecordingSendUnavailable), Toast.LENGTH_LONG).show();
            return;
        }
        if (state.scheduled || controller.getSendPaidMessagesStars(state.dialog) > 0) {
            if (ApplicationLoader.mainInterfacePaused || LaunchActivity.instance == null) {
                foregroundSend = generation;
                ApplicationLoader.applicationContext.startActivity(openIntent());
                return;
            }
            if (state.scheduled) {
                AlertsCreator.createScheduleDatePickerDialog(LaunchActivity.instance, state.dialog,
                        (notify, date, repeat) -> paidSend(generation, notify, date), () -> { }, null);
            } else paidSend(generation, true, 0);
            return;
        }
        sendApproved(generation, true, 0, 0);
    }

    public static void foreground() {
        if (ApplicationLoader.mainInterfacePaused) return;
        if (foregroundTarget >= 0) {
            long generation = foregroundTarget;
            foregroundTarget = -1;
            if (valid(generation)) chooseTarget(generation);
        }
        if (foregroundSend < 0) return;
        long generation = foregroundSend;
        foregroundSend = -1;
        if (valid(generation)) send(generation);
    }

    private static void paidSend(long generation, boolean notify, int date) {
        if (!valid(generation)) return;
        AlertsCreator.ensurePaidMessageConfirmation(state.account, state.dialog, 1,
                stars -> sendApproved(generation, notify, date, stars));
    }

    private static void sendApproved(long generation, boolean notify, int date, long stars) {
        if (!valid(generation) || state.transitioning) return;
        if (state.round) {
            Host host = host(state.guid);
            if (host != null) { host.roundSend(notify, date, stars); changed(); }
            return;
        }
        boolean wasPaused = state.paused;
        pendingNotify = notify;
        pendingDate = date;
        pendingStars = stars;
        if (!state.requestSend()) {
            if (wasPaused && state.sendPending) MediaController.getInstance().toggleRecordingPause(state.once);
            return;
        }
        MediaController.getInstance().stopRecording(1, notify, date, state.once, stars);
        changed();
    }

    public static void discard(long generation) {
        if (!valid(generation)) return;
        state.finishing = true;
        if (state.round) {
            Host host = host(state.guid); if (host != null) host.roundDiscard();
            clear(); return;
        }
        if (state.paused) {
            MediaController.getInstance().cleanRecording(true);
            MediaDataController.getInstance(state.account).pushDraftVoiceMessage(state.dialog, state.topic, null);
        } else {
            MediaController.getInstance().stopRecording(0, false, 0, false, 0);
            changed();
            return;
        }
        clear();
    }

    public static boolean valid(long generation) {
        if (!state.accepts(generation)) return false;
        if (UserConfig.getInstance(state.account).getClientUserId() != state.user) {
            if (state.round) { Host host = host(state.guid); if (host != null) host.roundDiscard(); }
            else MediaController.getInstance().stopRecording(0, false, 0, false, 0);
            clear();
            return false;
        }
        return true;
    }

    public static Intent openIntent() {
        Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        intent.setAction("com.tmessages.openchat.purple.recording");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("currentAccount", state.account);
        if (DialogObject.isEncryptedDialog(state.dialog)) intent.putExtra("encId", DialogObject.getEncryptedChatId(state.dialog));
        else if (state.dialog > 0) intent.putExtra("userId", state.dialog);
        else intent.putExtra("chatId", -state.dialog);
        if (state.topic != 0) intent.putExtra("topicId", state.topic);
        return intent;
    }

    public static String title() {
        if (!state.active() || PurpleGate.hiddenEverywhere(state.account, state.dialog)) return LocaleController.getString(R.string.PurpleRecording);
        MessagesController controller = MessagesController.getInstance(state.account);
        if (DialogObject.isEncryptedDialog(state.dialog)) {
            TLRPC.EncryptedChat chat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(state.dialog));
            return chat == null ? LocaleController.getString(R.string.PurpleRecording) : UserObject.getUserName(controller.getUser(chat.user_id));
        }
        if (state.dialog > 0) return UserObject.getUserName(controller.getUser(state.dialog));
        TLRPC.Chat chat = controller.getChat(-state.dialog);
        return chat == null ? LocaleController.getString(R.string.PurpleRecording) : chat.title;
    }

    public static void floatRound(int account, int guid, boolean small) {
        if (!state.round || !state.matches(account, guid)) return;
        syncOnce();
        state.docked = small;
        if (small) { targetSnapshot.dock(); Host host = host(guid); if (host != null) host.release(); }
        changed();
    }

    public static void roundEnded(int account, int guid) {
        if (state.round && state.matches(account, guid)) clear();
    }

    public static void open() {
        if (!state.active()) return;
        if (state.round) { Host host = host(state.guid); if (host != null) host.roundPreview(); }
        else ApplicationLoader.applicationContext.startActivity(openIntent());
    }

    public static boolean shown() {
        return state.active() && state.docked && (!state.round || host(state.guid) != null && host(state.guid).roundVisible());
    }

    public static boolean unavailable() {
        if (!state.active()) return false;
        MessagesController controller = MessagesController.getInstance(state.account);
        if (DialogObject.isEncryptedDialog(state.dialog)) {
            TLRPC.EncryptedChat chat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(state.dialog));
            return chat == null || chat instanceof TLRPC.TL_encryptedChatDiscarded;
        }
        if (state.dialog > 0) { TLRPC.User user = controller.getUser(state.dialog); return user == null || user.deleted; }
        TLRPC.Chat chat = controller.getChat(-state.dialog);
        if (chat == null || ChatObject.isNotInChat(chat)) return true;
        if (state.topic != 0) {
            TLRPC.TL_forumTopic topic = controller.getTopicsController().findTopic(-state.dialog, state.topic);
            return topic == null || topic instanceof TLRPC.TL_forumTopicDeleted || topic.closed || topic.hidden;
        }
        return false;
    }

    public static void chooseTarget(long generation) {
        if (!valid(generation)) return;
        if (ApplicationLoader.mainInterfacePaused || LaunchActivity.instance == null) {
            foregroundTarget = generation;
            ApplicationLoader.applicationContext.startActivity(openIntent());
            return;
        }
        if (DialogObject.isEncryptedDialog(state.dialog) || (!state.round && !state.canReplaceTarget(false))) {
            if (DialogObject.isEncryptedDialog(state.dialog)) Toast.makeText(ApplicationLoader.applicationContext,
                    LocaleController.getString(R.string.PurpleRecordingSendUnavailable), Toast.LENGTH_LONG).show();
            return;
        }
        if (state.round && (!state.paused || state.transitioning)) {
            targetPickerPending = true;
            if (!state.paused && !state.transitioning) pause(generation);
            return;
        }
        org.telegram.ui.ActionBar.BaseFragment parent = LaunchActivity.getLastFragment();
        if (parent == null) return;
        Toast.makeText(parent.getContext(), LocaleController.getString(R.string.PurpleRecordingSendUnavailable), Toast.LENGTH_LONG).show();
        android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true); args.putInt("dialogsType", org.telegram.ui.DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        org.telegram.ui.DialogsActivity picker = new org.telegram.ui.DialogsActivity(args);
        picker.setCurrentAccount(state.account);
        picker.setDelegate((fragment, dids, message, param, notify, date, repeat, topics) -> {
            if (!valid(generation) || state.transitioning || dids.size() != 1) return false;
            MessagesStorage.TopicKey key = dids.get(0);
            MessageObject top = null;
            if (key.topicId != 0) {
                TLRPC.TL_forumTopic topic = MessagesController.getInstance(state.account).getTopicsController().findTopic(-key.dialogId, key.topicId);
                if (topic == null || topic.topicStartMessage == null || topic.closed || topic.hidden) return false;
                top = new MessageObject(state.account, topic.topicStartMessage, false, false);
            }
            long oldDialog = state.dialog, oldTopic = state.topic;
            if (!state.retarget(key.dialogId, key.topicId)) return false;
            if (state.round) { Host host = host(state.guid); if (host != null) host.roundRetarget(key.dialogId, top); }
            else {
                MediaController.getInstance().purpleRetargetRecording(key.dialogId, top);
                MediaDataController.getInstance(state.account).pushDraftVoiceMessage(oldDialog, oldTopic, null);
            }
            fragment.finishFragment(); changed(); send(state.generation);
            return true;
        });
        parent.showAsSheet(picker);
    }

    public static void serviceReady(boolean ready) { serviceReady = ready; }
    public static void serviceLost() {
        serviceReady = false;
        if (state.active() && !state.paused && !state.finishing && state.requestPause()) MediaController.getInstance().toggleRecordingPause(state.once);
    }
    private static void clear() {
        state.clear();
        previewPending = false;
        targetPickerPending = false;
        foregroundSend = -1;
        foregroundTarget = -1;
        roundFinalization = false;
        serviceReady = false;
        PurpleRecordingService.stop();
        changed();
    }
    public static void changed() {
        for (WeakReference<Runnable> ref : new ArrayList<>(listeners)) {
            Runnable r = ref.get();
            if (r == null) listeners.remove(ref); else r.run();
        }
        PurpleRecordingService.refresh();
    }
}
