package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import org.telegram.messenger.purple.PurplePeekSnapshot;
import org.telegram.messenger.purple.PurplePeekLocalSettings;
import org.telegram.messenger.purple.PurplePeekForeground;
import org.telegram.messenger.purple.PurpleArchiveViewerModel;
import org.telegram.messenger.purple.PurpleRecent;
import org.telegram.messenger.purple.PurpleState;
import org.telegram.messenger.purple.PurpleWaitGate;
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
        PRESET("preset_change"), MODE("mode_change"), SOURCE("source_change"), LOCAL_POLICY("peek_focus_policy");
        final String code;
        Action(String code) { this.code = code; }
        boolean workMode() { return this == PRESET || this == MODE || this == SOURCE; }
        boolean workModeTitle() { return workMode() || this == LOCAL_POLICY; }
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

    private static void request(BaseFragment fragment, Action action, int value, String target,
            PurpleGate.PeekSettings opening) {
        if (pending != null) {
            cancel();
            if (action != Action.LOCAL_POLICY || value == 0) return;
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
        if (action == Action.LOCAL_POLICY) {
            if (!operation.prepare()) {
                operation.dismiss();
                PurpleWriterUI.explain(fragment, "session_stale", null);
                return;
            }
            if (operation.noOp()) { operation.dismiss(); return; }
            int seconds = operation.waits() ? Math.max(0, settings.state.clock.peekStartDelay) : 0;
            operation.gate = new PurpleWaitGate(SystemClock.elapsedRealtime(), seconds);
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
        int seconds = operation.waits() ? PurplePeekSnapshot.waitSeconds(settings.state.clock.peekStartDelay,
                action == Action.DELAY ? value : null) : 0;
        operation.gate = new PurpleWaitGate(SystemClock.elapsedRealtime(), seconds);
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
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { cancelFor(activity); }
            @Override public void onActivityStopped(Activity activity) { cancelFor(activity); }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { cancelFor(activity); }
        });
    }

    private static void cancelFor(Activity activity) {
        if (pending != null && pending.activity.get() == activity) cancel();
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
        Dialog dialog;
        boolean alive = true;
        TextView remaining;
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
                    && identity != null && identity.current() && !locked(owner);
        }

        boolean prepare() {
            if (action == Action.LOCAL_POLICY) return local.current() && contextCurrent()
                    && (value == 1 || PurplePeekForeground.canDisable() && settings != null && PurpleGate.currentPeekSettings(settings));
            if (!writer.current() || !PurpleGate.currentPeekSettings(settings)) return false;
            if (action == Action.START || action == Action.EXTEND) {
                return value >= 0 && !settings.state.normal && (action != Action.EXTEND || settings.state.clock.peeking);
            }
            if (action == Action.DELAY) return value >= 0 && PurpleGate.currentPeekEditSettings(settings);
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
            if (action == Action.MODE) return value == writer.mode;
            if (action == Action.DELAY) return value == settings.state.clock.peekStartDelay;
            if (action == Action.SOURCE) return target.equals(writer.globalKey);
            if (action != Action.PRESET || settings.state.activeMissing) return false;
            return settings.state.normal ? target == null || target.isEmpty() || target.equalsIgnoreCase("normal")
                    : settings.state.preset.equalsIgnoreCase(target == null ? "normal" : target);
        }

        boolean waits() {
            if (action == Action.LOCAL_POLICY) return local.enabled && value == 0;
            if (action == Action.MODE) return PurpleWaitGate.modeWait(writer.mode, value);
            if (action == Action.PRESET) return PurpleWaitGate.presetWait(settings.state.normal, settings.state.preset, target);
            if (action == Action.SOURCE) return PurpleWaitGate.sourceWait(settings.state.normal, choice.flag("normal"));
            return true;
        }

        boolean valid() {
            if (pending != this || !contextCurrent()
                    || (action == Action.LOCAL_POLICY ? !local.current() || selection != PurpleArchiveViewerModel.selectionGeneration()
                        || value == 0 && (!PurplePeekForeground.canDisable() || !PurpleGate.currentPeekSettings(settings))
                        : writer == null || !writer.current() || !PurpleGate.currentPeekSettings(settings))
                    || !Arrays.equals(stateBytes, PurpleState.read())
                    || choice != null && !choice.current()) return false;
            if (action.workMode()) return true;
            BaseFragment fragment = origin.get();
            if (fragment == null || fragment.isFinished || fragment.isPaused()
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
            if (gate.remaining(SystemClock.elapsedRealtime()) == 0) { complete(); return; }
            Activity owner = activity.get();
            dialog = new Dialog(owner);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCanceledOnTouchOutside(false);
            LinearLayout content = new LinearLayout(owner);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(24), dp(24), dp(24), dp(24));
            content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            TextView title = new TextView(owner);
            title.setGravity(Gravity.CENTER);
            title.setTextSize(24);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setText(getString(action.workModeTitle() ? R.string.PurpleWaitWorkMode
                    : action == Action.EXTEND ? R.string.PurpleWaitExtending
                    : action == Action.DELAY ? R.string.PurplePeekChangingDelay : R.string.PurplePeekStarting));
            content.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            remaining = new TextView(owner);
            remaining.setGravity(Gravity.CENTER);
            remaining.setTextSize(80);
            remaining.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            remaining.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            content.addView(remaining, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Button abort = new Button(owner);
            abort.setText(getString(action == Action.START ? R.string.PurpleAbortPeek : R.string.PurpleWaitAbort));
            abort.setTextSize(20);
            abort.setOnClickListener(view -> cancel());
            content.addView(abort, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
            dialog.setContentView(content);
            dialog.setOnDismissListener(ignored -> { if (pending == this) cancel(); });
            Window window = dialog.getWindow();
            if (window == null) { cancel(); return; }
            window.setBackgroundDrawable(new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite)));
            try {
                dialog.show();
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                tick();
            } catch (RuntimeException e) { cancel(); }
        }

        void tick() {
            PurpleWaitGate.Outcome decision = gate.poll(SystemClock.elapsedRealtime(), valid(), true, false);
            if (decision == PurpleWaitGate.Outcome.ABORTED) { cancel(); return; }
            if (decision == PurpleWaitGate.Outcome.READY) { complete(); return; }
            if (decision != PurpleWaitGate.Outcome.WAITING) return;
            long left = gate.remaining(SystemClock.elapsedRealtime());
            remaining.setText(String.valueOf((left + 999) / 1000));
            AndroidUtilities.runOnUIThread(tick, Math.min(left, 100));
        }

        void complete() {
            if (!valid()) { cancel(); return; }
            if (action == Action.LOCAL_POLICY) {
                if (gate.complete(SystemClock.elapsedRealtime(), valid(), true, false, true) != PurpleWaitGate.Outcome.READY) { cancel(); return; }
                pending = null;
                closeDialog();
                boolean saved = PurplePeekLocalSettings.commitFocusPolicy(local, value == 1);
                log(saved ? "applied" : "apply_failed", saved ? null : "session_failed");
                dismiss();
                if (saved) PurplePeekForeground.policyChanged();
                else PurpleWriterUI.explain(origin.get(), "session_failed", null);
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
                else if (action == Action.SOURCE) {
                    PurpleGlobalAccountChoice.Outcome result = choice.apply();
                    error = result.ok ? null : "session_stale";
                    sourceSaved = result.changed;
                } else if (action == Action.EXTEND) {
                    PurpleCore.PeekChange result = PurpleGate.extendPeek(value, settings);
                    if (result == null) error = "session_stale";
                    else if (!result.extended) {
                        PurpleGate.stopPeek();
                        Toast.makeText(activity.get(), getString(settings.state.clock.peekUntilStopped
                                ? R.string.PurplePeekOver : R.string.PurplePeekOverCapped), Toast.LENGTH_SHORT).show();
                    }
                } else if (PurpleGate.startPeek(value, settings) == null) error = "session_stale";
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
                applying.retire();
                dismiss();
                updateEndButtons();
            }
        }

        void log(String event, String reason) {
            PurpleDiagLog.event("wait", event, PurpleDiagLog.slot(account),
                    PurpleDiagLog.code("action", action.code), reason == null ? null : PurpleDiagLog.reason(reason));
        }

        void closeDialog() {
            AndroidUtilities.cancelRunOnUIThread(tick);
            if (dialog == null) return;
            Dialog old = dialog;
            dialog = null;
            old.setOnDismissListener(null);
            try { old.dismiss(); } catch (RuntimeException e) { org.telegram.messenger.FileLog.e(e); }
        }

        void dismiss() {
            alive = false;
            if (writer != null) writer.retire();
            closeDialog();
        }
    }
}
