package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.messenger.purple.PurplePeekSnapshot;
import org.telegram.messenger.purple.PurpleRecent;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Objects;

public final class PurplePeekControls {
    private static final int END_PEEK = 0x70750009;
    private static final ArrayList<WeakReference<ActionBarMenuItem>> endButtons = new ArrayList<>();
    private static WeakReference<ChatActivity> currentChat = new WeakReference<>(null);
    private static Pending pending;
    private static boolean observingLifecycle;

    private PurplePeekControls() {
    }

    public static void addEndButton(ActionBarMenu menu) {
        ActionBarMenuItem item = menu.addItem(END_PEEK, getString(R.string.PurpleEndPeek));
        item.setContentDescription(getString(R.string.PurpleEndPeek));
        item.setOnClickListener(view -> {
            cancel();
            PurpleGate.stopPeek();
        });
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

    static void chatResumed(ChatActivity chat) {
        currentChat = new WeakReference<>(chat);
    }

    static void chatDestroyed(ChatActivity chat) {
        if (currentChat.get() == chat) currentChat.clear();
    }

    public static boolean isPending() {
        return pending != null;
    }

    public static void toggle(BaseFragment fragment, int seconds) {
        if (pending != null) {
            cancel();
        } else if (PurpleGate.peeking()) {
            PurpleGate.stopPeek();
        } else {
            start(fragment, seconds);
        }
    }

    public static void start(BaseFragment fragment, int seconds) {
        if (pending != null) {
            cancel();
            return;
        }
        PurpleGate.PeekSettings settings = PurpleGate.peekSettings();
        if (settings == null || settings.state.normal || seconds < 0 || pending != null) return;
        if (settings.state.clock.peeking) {
            PurpleGate.stopPeek();
            settings = PurpleGate.peekSettings();
            if (settings == null || pending != null) return;
        }
        begin(fragment, seconds, null, settings);
    }

    static void changeDelay(BaseFragment fragment, int seconds) {
        if (seconds < 0) return;
        if (pending != null) {
            cancel();
            return;
        }
        PurpleGate.PeekSettings settings = PurpleGate.peekSettings();
        if (settings == null || settings.state.clock.peekStartDelay == seconds || pending != null) return;
        begin(fragment, 0, seconds, settings);
    }

    private static void begin(
            BaseFragment fragment, int seconds, Integer setting, PurpleGate.PeekSettings settings) {
        Activity activity = fragment == null ? null : fragment.getParentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || fragment.isFinished || fragment.isPaused() || SharedConfig.appLocked) return;
        if (!observingLifecycle) {
            observingLifecycle = true;
            NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> cancel(), NotificationCenter.activeAccountChanged);
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
        Pending operation = new Pending(fragment, seconds, setting, settings);
        pending = operation;
        updateEndButtons();
        operation.show();
    }

    private static void cancelFor(Activity activity) {
        if (pending != null && pending.activity.get() == activity) cancel();
    }

    public static void cancel() {
        Pending operation = pending;
        pending = null;
        if (operation != null) operation.dismiss();
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
                && ((dialog.folder_id != 0 && !PurpleGate.hidingArchive())
                    || PurpleGate.reachableElsewhere(account, dialogId, null))) return;
        PurpleRecent.suppressForPeekExit(account, dialogId);
        chat.finishFragment();
    }

    private static final class Pending {
        final WeakReference<BaseFragment> origin;
        final WeakReference<Activity> activity;
        final View fragmentView;
        final int account;
        final long userId;
        final String preset;
        final boolean normal;
        final int generation;
        final PurpleGate.PeekSettings settings;
        final int seconds;
        final Integer setting;
        final long deadline;
        Dialog dialog;
        TextView remaining;
        final Runnable tick = this::tick;

        Pending(
                BaseFragment fragment, int seconds, Integer setting, PurpleGate.PeekSettings settings) {
            origin = new WeakReference<>(fragment);
            activity = new WeakReference<>(fragment.getParentActivity());
            fragmentView = fragment.getFragmentView();
            account = fragment.getCurrentAccount();
            userId = UserConfig.getInstance(account).getClientUserId();
            PurpleCore.Loaded state = settings.state;
            preset = state.preset;
            normal = state.normal;
            generation = settings.generation;
            this.settings = settings;
            this.seconds = seconds;
            this.setting = setting;
            deadline = SystemClock.elapsedRealtime()
                    + PurplePeekSnapshot.waitSeconds(state.clock.peekStartDelay, setting) * 1000L;
        }

        boolean valid() {
            BaseFragment fragment = origin.get();
            Activity owner = activity.get();
            PurpleCore.Loaded state = PurpleGate.state();
            return pending == this && fragment != null && !fragment.isFinished && !fragment.isPaused()
                    && owner != null && !owner.isFinishing() && !owner.isDestroyed()
                    && fragment.getParentActivity() == owner && !SharedConfig.appLocked
                    && fragmentView != null && fragment.getFragmentView() == fragmentView && fragmentView.isAttachedToWindow()
                    && isCurrent(fragment)
                    && account == UserConfig.selectedAccount && fragment.getCurrentAccount() == account
                    && UserConfig.getInstance(account).isClientActivated()
                    && UserConfig.getInstance(account).getClientUserId() == userId
                    && state != null && state.normal == normal && Objects.equals(state.preset, preset)
                    && PurpleGate.generation() == generation && state == settings.state;
        }

        boolean isCurrent(BaseFragment fragment) {
            if (fragment.getParentLayout() == null) return false;
            BaseFragment top = fragment.getParentLayout().getSafeLastFragment();
            return top == fragment || (top instanceof MainTabsActivity
                    && ((MainTabsActivity) top).getCurrentVisibleFragment() == fragment);
        }

        void show() {
            if (!valid()) {
                cancelIfCurrent();
                return;
            }
            if (deadline <= SystemClock.elapsedRealtime()) {
                complete();
                return;
            }
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
            title.setText(getString(setting == null ? R.string.PurplePeekStarting : R.string.PurplePeekChangingDelay));
            content.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            remaining = new TextView(owner);
            remaining.setGravity(Gravity.CENTER);
            remaining.setTextSize(80);
            remaining.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            remaining.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            content.addView(remaining, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Button abort = new Button(owner);
            abort.setText(getString(R.string.PurpleAbortPeek));
            abort.setTextSize(20);
            abort.setOnClickListener(view -> cancel());
            content.addView(abort, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
            dialog.setContentView(content);
            dialog.setOnDismissListener(ignored -> {
                if (pending == this) cancelIfCurrent();
            });
            Window window = dialog.getWindow();
            if (window == null) {
                cancelIfCurrent();
                return;
            }
            window.setBackgroundDrawable(new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundWhite)));
            try {
                dialog.show();
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                tick();
            } catch (RuntimeException e) {
                cancelIfCurrent();
            }
        }

        void tick() {
            if (!valid()) {
                cancelIfCurrent();
                return;
            }
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) {
                complete();
                return;
            }
            remaining.setText(String.valueOf((left + 999) / 1000));
            AndroidUtilities.runOnUIThread(tick, Math.min(left, 100));
        }

        void complete() {
            if (!valid()) {
                cancelIfCurrent();
                return;
            }
            PurpleGate.PeekSettings refreshed = PurpleGate.peekSettings();
            if (pending != this) return;
            if (!valid() || refreshed == null || !PurpleGate.currentPeekSettings(settings)
                    || !settings.snapshot.sameAs(refreshed.snapshot)) {
                cancelIfCurrent();
                return;
            }
            BaseFragment fragment = origin.get();
            pending = null;
            dismiss();
            if (setting == null) {
                PurpleGate.startPeek(seconds, settings);
            } else {
                String error = PurpleWriter.setPeekStartDelay(settings, setting);
                if (error != null && fragment != null && BulletinFactory.canShowBulletin(fragment)) {
                    BulletinFactory.of(fragment).createErrorBulletin(error).show();
                }
            }
            updateEndButtons();
        }

        void cancelIfCurrent() {
            if (pending == this) cancel();
            else dismiss();
        }

        void dismiss() {
            AndroidUtilities.cancelRunOnUIThread(tick);
            if (dialog != null) {
                Dialog old = dialog;
                dialog = null;
                old.setOnDismissListener(null);
                try {
                    old.dismiss();
                } catch (RuntimeException e) {
                    org.telegram.messenger.FileLog.e(e);
                }
            }
        }
    }
}
