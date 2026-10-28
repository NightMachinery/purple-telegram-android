package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.KeyEvent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleDiagLog;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleGlobalAccountChoice;
import org.telegram.messenger.purple.PurplePeekStreak;
import org.telegram.messenger.purple.PurplePeekLocalSettings;
import org.telegram.messenger.purple.PurplePeekForeground;
import org.telegram.messenger.purple.PurpleArchiveViewerModel;
import org.telegram.messenger.purple.PurpleRecent;
import org.telegram.messenger.purple.PurpleState;
import org.telegram.messenger.purple.PurpleWaitGate;
import org.telegram.messenger.purple.PurpleWaitPresence;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;

public final class PurplePeekControls {
    private static final int END_PEEK = 0x70750009;
    private static final ArrayList<WeakReference<ActionBarMenuItem>> endButtons = new ArrayList<>();
    private static WeakReference<ChatActivity> currentChat = new WeakReference<>(null);
    private static Pending pending;
    private static boolean observingLifecycle;

    private enum Action {
        START("peek_start"), END("peek_end"), EXTEND("peek_extend"), DELAY("delay_edit"),
        READY_TIMEOUT("ready_timeout_edit"), PRESET("preset_change"), MODE("mode_change"), SOURCE("source_change"), LOCAL_POLICY("peek_focus_policy"), LOCAL_OPTION("peek_wait_option"), PEEK_EXIT("peek_exit_option");
        final String code;
        Action(String code) { this.code = code; }
        boolean workMode() { return this == PRESET || this == MODE || this == SOURCE; }
        boolean local() { return this == LOCAL_POLICY || this == LOCAL_OPTION; }
        boolean workModeTitle() { return workMode() || local() || this == PEEK_EXIT; }
    }

    private PurplePeekControls() { }

    public static void addEndButton(ActionBarMenu menu) {
        ActionBarMenuItem item = menu.addItem(END_PEEK, getString(R.string.PurpleEndPeek));
        item.setContentDescription(getString(R.string.PurpleEndPeek));
        item.setOnClickListener(view -> request(null, Action.END, 0, null, null));
        endButtons.add(new WeakReference<>(item));
        updateEndButtons();
    }

    private static void updateEndButtons() {
        boolean visible = pending == null && PurpleGate.peeking();
        for (int i = endButtons.size() - 1; i >= 0; i--) {
            ActionBarMenuItem item = endButtons.get(i).get();
            if (item == null) endButtons.remove(i);
            else item.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    static void chatResumed(ChatActivity chat) { currentChat = new WeakReference<>(chat); }
    static void chatDestroyed(ChatActivity chat) { if (currentChat.get() == chat) currentChat.clear(); }
    public static boolean isPending() { return pending != null; }

    public static void toggle(BaseFragment fragment, int seconds) {
        if (pending != null) cancel();
        else if (PurpleGate.peeking()) end(fragment);
        else start(fragment, seconds);
    }

    public static void end(BaseFragment fragment) {
        request(fragment, Action.END, 0, null, null);
    }

    public static void start(BaseFragment fragment, int seconds) {
        request(fragment, Action.START, seconds, null, null);
    }

    public static void extend(BaseFragment fragment, int seconds) {
        request(fragment, Action.EXTEND, seconds, null, null);
    }

    static void changeFocusPolicy(BaseFragment fragment, boolean enabled) {
        request(fragment, Action.LOCAL_POLICY, enabled ? 1 : 0, null, null);
    }

    static void changeWaitOption(BaseFragment fragment, String option, int value, PurplePeekLocalSettings.Snapshot opening) {
        if (opening == null || !opening.current() || !opening.accepts(option, value)) {
            PurpleWriterUI.explain(fragment, "session_stale", null); return;
        }
        request(fragment, Action.LOCAL_OPTION, value, option, null);
    }

    static void changeExitHidden(BaseFragment fragment, boolean enabled) {
        request(fragment, Action.PEEK_EXIT, enabled ? 1 : 0, null, null);
    }

    static void changePreset(BaseFragment fragment, String preset) {
        request(fragment, Action.PRESET, 0, preset, null);
    }

    static void changeMode(BaseFragment fragment, int mode) {
        request(fragment, Action.MODE, mode, null, null);
    }

    static void changeSource(BaseFragment fragment, String key) {
        request(fragment, Action.SOURCE, 0, key, null);
    }

    static void changeDelay(BaseFragment fragment, int seconds,
            PurpleWriter.Session writer, PurpleGate.PeekSettings settings) {
        if (seconds < 0) return;
        if (writer == null || !writer.current() || !PurpleGate.currentPeekEditSettings(settings)) {
            String held = PurpleWriter.ownerRefusal();
            PurpleWriterUI.explain(fragment, writer != null ? "session_stale" : held != null ? held : "session_failed", null);
            return;
        }
        request(fragment, Action.DELAY, seconds, null, settings);
    }

    static void changeReadyTimeout(BaseFragment fragment, int seconds,
            PurpleWriter.Session writer, PurpleGate.PeekSettings settings) {
        if (seconds < 10) return;
        if (writer == null || !writer.current() || !PurpleGate.currentPeekEditSettings(settings)) {
            PurpleWriterUI.explain(fragment, "session_stale", null);
            return;
        }
        request(fragment, Action.READY_TIMEOUT, seconds, null, settings);
    }

    private static void request(BaseFragment fragment, Action action, int value, String target,
            PurpleGate.PeekSettings opening) {
        if (pending != null) {
            cancel();
            PurpleGate.PeekSettings current = PurpleGate.peekSettings();
            boolean tightening = action == Action.END || action == Action.LOCAL_POLICY && value == 1
                    || action == Action.LOCAL_OPTION && value >= PurplePeekLocalSettings.snapshot().value(target)
                    || action == Action.PEEK_EXIT && value == 1
                    || action == Action.DELAY && current != null && value >= current.state.clock.peekStartDelay
                    || action == Action.READY_TIMEOUT && current != null && value <= current.state.clock.peekReadyTimeout;
            if (!tightening) return;
        }
        if (action == Action.END) {
            if (PurpleGate.peeking()) {
                PurpleDiagLog.event("wait", "instant", PurpleDiagLog.slot(fragment == null
                        ? UserConfig.selectedAccount : fragment.getCurrentAccount()), PurpleDiagLog.code("action", action.code));
                PurpleGate.stopPeek();
                updateEndButtons();
            }
            return;
        }
        Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || fragment.isFinished || fragment.isPaused() || locked(activity)) return;
        PurpleGate.PeekSettings settings = PurpleGate.peekSettings();
        observe(activity);
        Pending operation = new Pending(fragment, action, value, target, settings);
        if (action.local()) {
            if (!operation.prepare()) {
                operation.dismiss();
                PurpleWriterUI.explain(fragment, "session_stale", null);
                return;
            }
            if (operation.noOp()) { operation.dismiss(); return; }
            operation.delay = settings == null ? null : PurplePeekStreak.delay(settings.state.clock.peekStartDelay, settings.state);
            if (operation.waits() && operation.delay == null) {
                operation.dismiss(); PurpleWriterUI.explain(fragment, "session_failed", null); return;
            }
            int seconds = operation.waits() ? operation.delay.seconds : 0;
            operation.gate = new PurpleWaitGate(SystemClock.elapsedRealtime(), seconds, settings == null ? 600 : settings.state.clock.peekReadyTimeout);
            pending = operation;
            operation.log(seconds == 0 ? "instant" : "waiting", null);
            updateEndButtons();
            operation.show();
            return;
        }
        PurpleWriter.Capture capture = operation.capture();
        if (capture.session == null) {
            operation.log("held", capture.reason);
            operation.dismiss();
            PurpleWriterUI.explain(fragment, capture.reason, null);
            return;
        }
        operation.writer = capture.session;
        operation.log("requested", null);
        if (settings == null || opening != null && !PurpleGate.currentPeekSettings(opening)) {
            operation.log("held", "gate_not_loaded");
            operation.dismiss();
            PurpleWriterUI.explain(fragment, "gate_not_loaded", null);
            return;
        }
        if (!operation.prepare()) {
            operation.log("held", "session_stale");
            operation.dismiss();
            PurpleWriterUI.explain(fragment, "session_stale", null);
            return;
        }
        if (operation.noOp()) {
            operation.log("instant", null);
            operation.dismiss();
            return;
        }
        operation.delay = PurplePeekStreak.delay(settings.state.clock.peekStartDelay, settings.state);
        if (operation.waits() && operation.delay == null) {
            operation.dismiss(); PurpleWriterUI.explain(fragment, "session_failed", null); return;
        }
        int seconds = operation.waits() ? operation.delay.seconds : 0;
        operation.gate = new PurpleWaitGate(SystemClock.elapsedRealtime(), seconds, settings == null ? 600 : settings.state.clock.peekReadyTimeout);
        pending = operation;
        operation.log(seconds == 0 ? "instant" : "waiting", null);
        updateEndButtons();
        operation.show();
    }

    private static boolean locked(Activity activity) {
        PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        return SharedConfig.appLocked || power == null || !power.isInteractive()
                || keyguard == null || keyguard.isKeyguardLocked();
    }

    private static void observe(Activity activity) {
        if (observingLifecycle) return;
        observingLifecycle = true;
        NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> {
            if (pending != null && !pending.action.workMode()) cancel();
        }, NotificationCenter.activeAccountChanged);
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) {
                Pending operation = pending;
                if (operation == null) return;
                if (operation.activity.get() == activity) { operation.resumed = true; operation.screenOff = false; operation.windowFocused |= activity.hasWindowFocus(); operation.presenceChanged(true, false); }
                else if (!locked(activity)) cancel();
            }
            @Override public void onActivityPaused(Activity activity) {
                Pending operation = pending;
                if (operation != null && operation.activity.get() == activity) { operation.resumed = false; operation.presenceChanged(false, true); }
            }
            @Override public void onActivityStopped(Activity activity) {
                Pending operation = pending;
                if (operation != null && operation.activity.get() == activity) { operation.resumed = false; operation.presenceChanged(); }
            }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { cancelFor(activity); }
        });
    }

    private static void cancelFor(Activity activity) {
        if (pending != null && pending.activity.get() == activity) cancel();
    }

    public static void presenceChanged() {
        Pending operation = pending;
        if (operation != null) operation.presenceChanged();
    }

    public static void cancel() {
        Pending operation = pending;
        pending = null;
        if (operation != null) {
            if (operation.gate != null) operation.gate.abort();
            operation.log("aborted", null);
            operation.dismiss();
        }
        updateEndButtons();
    }

    public static void onGateReload(PurpleCore.Loaded previous, PurpleCore.Loaded next) {
        PurplePeekStreak.observe(next);
        if (pending != null && !pending.valid()) cancel();
        updateEndButtons();
        if (previous == null || !previous.clock.peeking || next.clock.peeking) return;
        PurpleCore.Loaded restored = PurpleGate.state();
        ChatActivity chat = currentChat.get();
        if (restored == null || restored.normal || restored.clock.peeking || !restored.clock.peekExitHiddenChats
                || chat == null || chat.isFinished || chat.getParentActivity() == null
                || chat.getCurrentAccount() != UserConfig.selectedAccount) return;
        int account = chat.getCurrentAccount();
        long dialogId = chat.getDialogId();
        TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
        if (dialog == null || PurpleGate.shownWithoutRecent(account, dialog)) return;
        if (!PurpleGate.hiddenEverywhereWithoutRecent(account, dialog)
                && ((dialog.folder_id != 0 && !PurpleGate.hidingArchive(account))
                    || PurpleGate.reachableElsewhere(account, dialogId, null))) return;
        PurpleRecent.suppressForPeekExit(account, dialogId);
        chat.finishFragment();
    }

    private static final class Pending {
        final WeakReference<BaseFragment> origin;
        final WeakReference<Activity> activity;
        final View fragmentView;
        final int account;
        final PurpleAccountFiles.Identity identity;
        final PurpleGate.PeekSettings settings;
        final byte[] stateBytes;
        final Action action;
        final PurplePeekLocalSettings.Snapshot local;
        final long selection;
        final int value;
        final String target;
        PurpleWriter.Session writer;
        PurpleGlobalAccountChoice.Choice choice;
        PurpleWaitGate gate;
        PurplePeekStreak.Delay delay;
        Dialog dialog;
        boolean alive = true;
        PurpleWaitCover cover;
        final PurpleWaitPresence presence = new PurpleWaitPresence();
        boolean resumed = true, windowFocused = true, screenOff, suspended, ticking;
        static final long REVEALED_FOCUS_MILLIS = 300;
        long revealedFocusUntil;
        BroadcastReceiver receiver;
        final Runnable tick = this::tick;

        Pending(BaseFragment fragment, Action action, int value, String target, PurpleGate.PeekSettings settings) {
            origin = new WeakReference<>(fragment);
            activity = new WeakReference<>(fragment.getParentActivity());
            fragmentView = fragment.getFragmentView();
            account = fragment.getCurrentAccount();
            identity = PurpleAccountFiles.capture(account);
            this.settings = settings;
            local = PurplePeekLocalSettings.snapshot();
            selection = PurpleArchiveViewerModel.selectionGeneration();
            this.action = action;
            this.value = value;
            this.target = target;
            stateBytes = PurpleState.read();
        }

        PurpleWriter.Capture capture() {
            return PurpleWriter.captureResult(account, action != Action.MODE, this::contextCurrent, null);
        }

        boolean contextCurrent() {
            Activity owner = activity.get();
            return alive && owner != null && !owner.isFinishing() && !owner.isDestroyed()
                    && identity != null && identity.current();
        }

        boolean prepare() {
            if (action.local()) return local.current() && contextCurrent()
                    && (action == Action.LOCAL_POLICY ? value == 1 || PurplePeekForeground.canDisable()
                        && settings != null && PurpleGate.currentPeekSettings(settings)
                        : local.accepts(target, value) && settings != null && PurpleGate.currentPeekSettings(settings));
            if (!writer.current() || !PurpleGate.currentPeekSettings(settings)) return false;
            if (action == Action.START || action == Action.EXTEND) {
                return value >= 0 && !settings.state.normal && (action != Action.EXTEND || settings.state.clock.peeking);
            }
            if (action == Action.DELAY || action == Action.PEEK_EXIT || action == Action.READY_TIMEOUT) return value >= (action == Action.READY_TIMEOUT ? 10 : 0) && PurpleGate.currentPeekEditSettings(settings);
            if (action == Action.MODE) return value >= 0 && value <= 2;
            if (action == Action.PRESET) {
                if (target == null || target.isEmpty() || target.equalsIgnoreCase("normal")) return true;
                for (PurpleCore.PresetInfo preset : settings.state.presets) {
                    if (preset.name.equalsIgnoreCase(target)) return preset.resolves;
                }
                return false;
            }
            PurpleGlobalAccountChoice.Session session = PurpleGlobalAccountChoice.begin(account, this::contextCurrent);
            choice = session == null ? null : session.preview(target, this::contextCurrent);
            return choice != null && choice.current();
        }

        boolean noOp() {
            if (action == Action.LOCAL_POLICY) return local.enabled == (value == 1);
            if (action == Action.LOCAL_OPTION) return local.value(target) == value;
            if (action == Action.PEEK_EXIT) return settings.state.clock.peekExitHiddenChats == (value == 1);
            if (action == Action.MODE) return value == writer.mode;
            if (action == Action.DELAY) return value == settings.state.clock.peekStartDelay;
            if (action == Action.READY_TIMEOUT) return value == settings.state.clock.peekReadyTimeout;
            if (action == Action.SOURCE) return target.equals(writer.globalKey);
            if (action != Action.PRESET || settings.state.activeMissing) return false;
            return settings.state.normal ? target == null || target.isEmpty() || target.equalsIgnoreCase("normal")
                    : settings.state.preset.equalsIgnoreCase(target == null ? "normal" : target);
        }

        boolean waits() {
            if (action == Action.LOCAL_POLICY) return local.enabled && value == 0;
            if (action == Action.LOCAL_OPTION) return value < local.value(target);
            if (action == Action.PEEK_EXIT) return value == 0;
            if (action == Action.DELAY) return value < settings.state.clock.peekStartDelay;
            if (action == Action.READY_TIMEOUT) return PurpleWaitGate.readyTimeoutWait(settings.state.clock.peekReadyTimeout, value);
            if (action == Action.MODE) return PurpleWaitGate.modeWait(writer.mode, value);
            if (action == Action.PRESET) return PurpleWaitGate.presetWait(settings.state.normal, settings.state.preset, target);
            if (action == Action.SOURCE) return PurpleWaitGate.sourceWait(settings.state.normal, choice.flag("normal"));
            return true;
        }

        boolean valid() {
            if (pending != this || !contextCurrent()
                    || !local.current() || delay != null && !delay.current()
                    || (action.local() ? selection != PurpleArchiveViewerModel.selectionGeneration()
                        || (action == Action.LOCAL_OPTION || value == 0) && (!PurpleGate.currentPeekSettings(settings)
                            || action == Action.LOCAL_POLICY && !PurplePeekForeground.canDisable())
                        : writer == null || !writer.current() || !PurpleGate.currentPeekSettings(settings))
                    || !Arrays.equals(stateBytes, PurpleState.read())
                    || choice != null && !choice.current()) return false;
            if (action.workMode()) return true;
            BaseFragment fragment = origin.get();
            if (fragment == null || fragment.isFinished || fragment.isPaused() && !presence.suspended()
                    || fragment.getParentActivity() != activity.get() || fragment.getCurrentAccount() != account
                    || account != UserConfig.selectedAccount || fragmentView == null
                    || fragment.getFragmentView() != fragmentView || !fragmentView.isAttachedToWindow()
                    || fragment.getParentLayout() == null) return false;
            BaseFragment top = fragment.getParentLayout().getSafeLastFragment();
            return top == fragment || top instanceof MainTabsActivity
                    && ((MainTabsActivity) top).getCurrentVisibleFragment() == fragment;
        }

        void show() {
            if (!valid()) { cancel(); return; }
            if (!gate.delayed()) { complete(); return; }
            Activity owner = activity.get();
            dialog = new Dialog(owner) {
                @Override public boolean dispatchKeyEvent(KeyEvent event) {
                    if (pending != Pending.this) return super.dispatchKeyEvent(event);
                    if (presence.suspended()) return super.dispatchKeyEvent(event);
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        tick();
                        if (pending != Pending.this) return true;
                        PurpleWaitGate.Key key = gate.key(SystemClock.elapsedRealtime(),
                                event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER,
                                event.getRepeatCount() != 0);
                        if (key == PurpleWaitGate.Key.CONFIRM) complete();
                        else if (key == PurpleWaitGate.Key.ABORT) PurplePeekControls.cancel();
                    }
                    return true;
                }
                @Override public void onWindowFocusChanged(boolean focused) {
                    super.onWindowFocusChanged(focused);
                    if (pending != Pending.this) return;
                    windowFocused = focused;
                    revealedFocusUntil = 0;
                    presenceChanged();
                }
            };
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(false);
            String heading = getString(action.workModeTitle() || action == Action.READY_TIMEOUT ? R.string.PurpleWaitWorkMode
                    : action == Action.EXTEND ? R.string.PurpleWaitExtending
                    : action == Action.DELAY ? R.string.PurplePeekChangingDelay : R.string.PurplePeekStarting);
            String breakdown = delay == null ? null : LocaleController.formatString(R.string.PurplePeekWaitBreakdown,
                    delay.seconds, delay.base, delay.extra, delay.resetMinutes);
            cover = new PurpleWaitCover(owner, heading, breakdown, actionName(), this::complete, () -> { if (pending == this) cancel(); });
            dialog.setContentView(cover);
            dialog.setOnDismissListener(ignored -> { if (pending == this) cancel(); });
            Window window = dialog.getWindow();
            if (window == null) { cancel(); return; }
            window.setBackgroundDrawable(new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite)));
            try {
                observeScreen(owner);
                dialog.show();
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                tick();
            } catch (RuntimeException e) { if (pending == this) cancel(); }
        }

        String actionName() {
            if (action == Action.START) return getString(R.string.PurpleWaitStartAction);
            if (action == Action.EXTEND) return getString(R.string.PurpleWaitExtendAction);
            if (action != Action.PRESET) return getString(R.string.PurpleWaitApplyAction);
            String title = getString(R.string.PurplePresetNormal);
            if (target != null && !target.isEmpty() && !target.equalsIgnoreCase("normal")) {
                title = target;
                for (PurpleCore.PresetInfo preset : settings.state.presets) {
                    if (preset.name.equalsIgnoreCase(target)) { title = preset.title == null || preset.title.isEmpty() ? preset.name : preset.title; break; }
                }
            }
            return LocaleController.formatString(R.string.PurpleWaitPresetAction, title);
        }

        PurpleWaitPresence.Lock lockState() {
            Activity owner = activity.get();
            if (owner == null) return PurpleWaitPresence.Lock.UNKNOWN;
            try {
                if (screenOff || SharedConfig.appLocked) return PurpleWaitPresence.Lock.LOCKED;
                PowerManager power = (PowerManager) owner.getSystemService(Context.POWER_SERVICE);
                KeyguardManager keyguard = (KeyguardManager) owner.getSystemService(Context.KEYGUARD_SERVICE);
                if (power == null || keyguard == null) return PurpleWaitPresence.Lock.UNKNOWN;
                return !power.isInteractive() || keyguard.isKeyguardLocked()
                        ? PurpleWaitPresence.Lock.LOCKED : PurpleWaitPresence.Lock.UNLOCKED;
            } catch (RuntimeException e) { return PurpleWaitPresence.Lock.UNKNOWN; }
        }

        void observeScreen(Activity owner) {
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (pending != Pending.this) return;
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) screenOff = true;
                    else screenOff = false;
                    presenceChanged();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) owner.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else owner.registerReceiver(receiver, filter);
        }

        void presenceChanged() {
            if (pending != this) return;
            tick();
        }

        void presenceChanged(boolean ownerResumed, boolean ownerDeparted) {
            if (pending != this) return;
            tick(ownerResumed, ownerDeparted);
        }

        boolean foreground() {
            Activity owner = activity.get();
            return resumed && (windowFocused || revealedFocusUntil != 0 && SystemClock.elapsedRealtime() < revealedFocusUntil
                    || suspended && presence.returningFromLock()
                    && owner != null && owner.hasWindowFocus());
        }

        void tick() { tick(false, false); }

        void tick(boolean ownerResumed, boolean ownerDeparted) {
            if (pending != this || !alive || ticking) return;
            ticking = true;
            try {
                AndroidUtilities.cancelRunOnUIThread(tick);
                long now = SystemClock.elapsedRealtime();
                if (!presence.update(now, foreground(), lockState(), ownerResumed, ownerDeparted)) { cancel(); return; }
                boolean hidden = presence.suspended();
                PurpleWaitGate.Outcome decision = gate.poll(now, valid(), !hidden, hidden);
                if (decision == PurpleWaitGate.Outcome.ABORTED || decision == PurpleWaitGate.Outcome.EXPIRED) { cancel(); return; }
                if (decision != PurpleWaitGate.Outcome.WAITING && decision != PurpleWaitGate.Outcome.READY) return;
                if (dialog != null && hidden != suspended) {
                    suspended = hidden;
                    if (hidden) { revealedFocusUntil = 0; windowFocused = false; dialog.hide(); }
                    else {
                        gate.guardInput(now);
                        windowFocused = false;
                        revealedFocusUntil = now + REVEALED_FOCUS_MILLIS;
                        dialog.show();
                    }
                }
                if (pending != this || !alive) return;
                if (cover != null) cover.update(gate.remaining(now), gate.ready(), !hidden && !gate.guarded(now));
                AndroidUtilities.runOnUIThread(tick, 100);
            } catch (RuntimeException e) {
                if (pending == this) cancel();
            } finally { ticking = false; }
        }

        void complete() {
            if (pending != this || !alive) return;
            tick();
            if (pending != this || !alive) return;
            long now = SystemClock.elapsedRealtime();
            if (!presence.update(now, foreground(), lockState())) { cancel(); return; }
            PurpleWaitGate.Outcome polled = gate.poll(now, valid(), !presence.suspended(), presence.suspended());
            if (polled == PurpleWaitGate.Outcome.ABORTED || polled == PurpleWaitGate.Outcome.EXPIRED) { cancel(); return; }
            if (presence.suspended() || polled != PurpleWaitGate.Outcome.READY || gate.guarded(now)) return;
            if (!valid()) { cancel(); return; }
            if (action.local()) {
                if (gate.complete(SystemClock.elapsedRealtime(), valid(), true, false, true) != PurpleWaitGate.Outcome.READY) { cancel(); return; }
                pending = null;
                closeDialog();
                boolean saved = action == Action.LOCAL_POLICY
                        ? PurplePeekLocalSettings.commitFocusPolicy(local, value == 1)
                        : PurplePeekLocalSettings.commitWaitPolicy(local, target, value);
                log(saved ? "applied" : "apply_failed", saved ? null : "session_failed");
                dismiss();
                if (saved && action == Action.LOCAL_POLICY) PurplePeekForeground.policyChanged();
                else if (!saved) PurpleWriterUI.explain(origin.get(), "session_failed", null);
                updateEndButtons();
                return;
            }
            PurpleWriter.Capture fresh = capture();
            PurpleWaitGate.Outcome decision = gate.complete(SystemClock.elapsedRealtime(), valid(), true, false,
                    fresh.session != null);
            if (decision != PurpleWaitGate.Outcome.READY) {
                if (fresh.session != null) fresh.session.retire();
                if (decision == PurpleWaitGate.Outcome.HELD) {
                    log("held", fresh.reason);
                    BaseFragment fragment = origin.get();
                    cancel();
                    PurpleWriterUI.explain(fragment, fresh.reason, null);
                } else cancel();
                return;
            }
            PurpleWriter.Session applying = fresh.session;
            if (!applying.forAccount(account) || !applying.current()) {
                applying.retire();
                cancel();
                return;
            }
            pending = null;
            closeDialog();
            String error = null;
            boolean sourceSaved = false;
            try {
                if (action == Action.MODE) error = applying.setMode(value);
                else if (action == Action.PRESET) error = PurpleGate.setPreset(target) ? null : "session_stale";
                else if (action == Action.DELAY) error = PurpleWriter.setPeekStartDelay(applying, settings, value);
                else if (action == Action.READY_TIMEOUT) error = PurpleWriter.setPeekReadyTimeout(applying, settings, value);
                else if (action == Action.PEEK_EXIT) error = PurpleWriter.setPeekExitHidden(applying, settings, value == 1);
                else if (action == Action.SOURCE) {
                    PurpleGlobalAccountChoice.Outcome result = choice.apply();
                    error = result.ok ? null : "session_stale";
                    sourceSaved = result.changed;
                } else if (action == Action.EXTEND) {
                    PurpleCore.PeekChange result = PurplePeekStreak.activatePeek(value, settings, true);
                    if (result == null || result.refused) error = "session_stale";
                    else if (!result.extended) {
                        PurpleGate.stopPeek();
                        Toast.makeText(activity.get(), getString(settings.state.clock.peekUntilStopped
                                ? R.string.PurplePeekOver : R.string.PurplePeekOverCapped), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    PurpleCore.PeekChange result = PurplePeekStreak.activatePeek(value, settings, false);
                    if (result == null || result.refused || !result.peeking) error = "session_stale";
                }
                log(error == null ? "applied" : "apply_failed", error == null ? null : "session_failed");
                if (error != null) {
                    BaseFragment fragment = origin.get();
                    if (action == Action.SOURCE && fragment != null && fragment.getParentActivity() == activity.get()) {
                        AlertDialog.Builder failed = new AlertDialog.Builder(activity.get());
                        failed.setMessage(getString(sourceSaved ? R.string.PurpleGlobalChoiceSavedIncomplete
                                : R.string.PurpleGlobalChoiceUnavailable));
                        failed.setPositiveButton(getString(R.string.OK), null);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!fragment.isFinished && fragment.getParentActivity() == activity.get()) fragment.showDialog(failed.create());
                        });
                    } else if (!PurpleWriterUI.explainHeld(fragment, error, null)) {
                        Toast.makeText(activity.get(), LocaleController.formatString(R.string.PurpleWorkModeFailed, error),
                                Toast.LENGTH_LONG).show();
                    }
                }
            } finally {
                try { applying.retire(); } finally {
                    try { dismiss(); } finally { updateEndButtons(); }
                }
            }
        }

        void log(String event, String reason) {
            PurpleDiagLog.event("wait", event, PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("action", action.code), reason == null ? null : PurpleDiagLog.reason(reason));
        }

        void closeDialog() {
            AndroidUtilities.cancelRunOnUIThread(tick);
            if (receiver != null) {
                BroadcastReceiver oldReceiver = receiver;
                receiver = null;
                Activity owner = activity.get();
                if (owner != null) try { owner.unregisterReceiver(oldReceiver); } catch (RuntimeException e) { org.telegram.messenger.FileLog.e(e); }
            }
            if (dialog == null) return;
            Dialog old = dialog;
            dialog = null;
            try { old.setOnDismissListener(null); old.dismiss(); } catch (RuntimeException e) { org.telegram.messenger.FileLog.e(e); }
        }

        void dismiss() {
            alive = false;
            try { if (writer != null) writer.retire(); } finally { closeDialog(); }
        }
    }
}
