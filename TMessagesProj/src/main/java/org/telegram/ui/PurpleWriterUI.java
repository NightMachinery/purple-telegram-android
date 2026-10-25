package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountOwner;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

final class PurpleWriterUI {
    private static final WeakHashMap<BaseFragment, Long> lifetimes = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Long> activities = new WeakHashMap<>();
    private static final WeakHashMap<View, long[]> attachments = new WeakHashMap<>();
    private static long accountEpoch;
    private static boolean observing;
    private static boolean offlineNoticeShown;
    private static void observe(Activity owner) {
        if (owner == null || observing) return;
        observing = true;
        org.telegram.messenger.NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> ++accountEpoch,
                org.telegram.messenger.NotificationCenter.activeAccountChanged);
        owner.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, android.os.Bundle state) { }
            public void onActivityStarted(Activity activity) { }
            public void onActivityResumed(Activity activity) { }
            public void onActivityPaused(Activity activity) { activities.put(activity, activities.getOrDefault(activity, 0L) + 1); }
            public void onActivityStopped(Activity activity) { }
            public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) { }
            public void onActivityDestroyed(Activity activity) { activities.put(activity, activities.getOrDefault(activity, 0L) + 1); }
        });
    }
    private static long[] attachment(View view) {
        if (view == null) return new long[]{0};
        long[] found = attachments.get(view);
        if (found != null) return found;
        long[] epoch = {0};
        attachments.put(view, epoch);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { }
            public void onViewDetachedFromWindow(View view) { ++epoch[0]; }
        });
        return epoch;
    }
    static void retire(BaseFragment fragment) { lifetimes.put(fragment, lifetimes.getOrDefault(fragment, 0L) + 1); }
    static PurpleWriter.Session capture(BaseFragment fragment) { return capture(fragment, true); }
    static PurpleWriter.Current current(BaseFragment fragment) {
        observe(fragment.getParentActivity());
        long activityEpoch = activities.getOrDefault(fragment.getParentActivity(), 0L);
        long activeEpoch = accountEpoch;
        long[] attachment = attachment(fragment.getFragmentView());
        long attachedEpoch = attachment[0];
        WeakReference<BaseFragment> view = new WeakReference<>(fragment);
        WeakReference<Activity> activity = new WeakReference<>(fragment.getParentActivity());
        WeakReference<View> content = new WeakReference<>(fragment.getFragmentView());
        long lifetime = lifetimes.getOrDefault(fragment, 0L);
        int account = fragment.getCurrentAccount();
        return () -> {
            BaseFragment f = view.get();
            return f != null && activity.get() != null && content.get() != null
                    && f.getParentActivity() == activity.get() && f.getFragmentView() == content.get()
                    && !f.isFinished && !f.isPaused() && f.getCurrentAccount() == account
                    && lifetimes.getOrDefault(f, 0L) == lifetime && attachment[0] == attachedEpoch
                    && accountEpoch == activeEpoch && activities.getOrDefault(activity.get(), 0L) == activityEpoch;
        };
    }
    static PurpleWriter.Session capture(BaseFragment fragment, boolean appWide) {
        return captureOnce(fragment, appWide).session;
    }
    private static PurpleWriter.Capture captureOnce(BaseFragment fragment, boolean appWide) {
        PurpleWriter.Current current = current(fragment);
        WeakReference<BaseFragment> view = new WeakReference<>(fragment);
        String key = PurpleAccountOwner.global();
        return PurpleWriter.captureResult(fragment.getCurrentAccount(), appWide, current,
                () -> offlineEdit(view.get(), key, current));
    }
    /**
     * {@link #capture(BaseFragment, boolean)}, saying why when it refuses.
     * The settings owner is planned once every account slot has loaded (see
     * PurpleAccountOwner.onAccountsLoaded), so a held owner here is the
     * running answer and is not planned again.
     */
    static PurpleWriter.Capture captureResult(BaseFragment fragment, boolean appWide) {
        return captureOnce(fragment, appWide);
    }

    /** What a refused capture means, for a reason line, followed by its code so a report can quote it. */
    static String reasonText(String code) {
        final int text;
        switch (code == null ? "" : code) {
            case "owner_not_ready": text = R.string.PurpleWriteReasonOwner; break;
            case "write_in_progress": text = R.string.PurpleWriteReasonBusy; break;
            case "account_signed_out": text = R.string.PurpleWriteReasonSignedOut; break;
            case "route_missing":
            case "local_choice_invalid": text = R.string.PurpleWriteReasonLocal; break;
            case "recovery_required": text = R.string.PurpleWriteReasonRecovery; break;
            case "unsafe_file":
            case "file_too_large":
            case "file_not_utf8":
            case "file_unreadable": text = R.string.PurpleWriteReasonFile; break;
            case "native_unavailable": text = R.string.PurpleWriteReasonNative; break;
            case "accounts_loading":
            case "gate_not_loaded": text = R.string.PurpleWriteReasonLoading; break;
            case "lists_unreadable": text = R.string.PurpleWriteReasonLists; break;
            case "view_not_current":
            case "choice_applying":
            case "choice_changed":
            case "choice_stale":
            case "files_changed":
            case "route_changed":
            case "session_stale": text = R.string.PurpleWriteReasonChanged; break;
            default: text = R.string.PurpleWriteReasonOther; break;
        }
        return LocaleController.formatString(R.string.PurpleWriteReasonCoded, LocaleController.getString(text),
                code == null ? "unknown" : code);
    }

    static final int REMEDY_RETRY = 0, REMEDY_OWNER = 1, REMEDY_SETTINGS = 2;

    /** What can be done about a refusal from where it was met. */
    static int remedy(String code) {
        switch (code == null ? "" : code) {
            case "owner_not_ready": return REMEDY_OWNER;
            case "route_missing":
            case "local_choice_invalid":
            case "recovery_required":
            case "unsafe_file":
            case "file_too_large":
            case "file_not_utf8":
            case "file_unreadable":
            case "native_unavailable":
            case "lists_unreadable": return REMEDY_SETTINGS;
            default: return REMEDY_RETRY;
        }
    }

    static String remedyLabel(int remedy) {
        final int label = remedy == REMEDY_OWNER ? R.string.PurpleWriteActionOwner
                : remedy == REMEDY_SETTINGS ? R.string.PurpleWriteActionSettings : R.string.PurpleWriteActionRetry;
        return LocaleController.getString(label);
    }

    /**
     * A write session for a tap, or null after {@link #explain} has said why
     * there is none. Screens call this before they open a dialog or write, so
     * a held write is answered with its reason and remedy at the tap, never
     * dropped silently or refused only after a dialog was filled in.
     */
    static PurpleWriter.Session captureOrExplain(BaseFragment fragment, boolean appWide, Runnable retry) {
        PurpleWriter.Capture result = captureResult(fragment, appWide);
        if (result.session == null) explain(fragment, result.reason, retry);
        return result.session;
    }

    /**
     * Shows a write helper's error as the refusal box when the helper was
     * handed no session ({@link PurpleWriter#heldReason}), and returns false
     * for any other error, which the caller shows as before.
     */
    static boolean explainHeld(BaseFragment fragment, String error, Runnable retry) {
        String reason = PurpleWriter.heldReason(error);
        if (reason == null) return false;
        explain(fragment, reason, retry);
        return true;
    }

    /** The refusal box for a held settings write: why, and the button for what fixes it. */
    static void explain(BaseFragment fragment, String reason, Runnable retry) {
        explain(fragment, LocaleController.getString(R.string.PurpleWriteHeldTitle),
                LocaleController.formatString(R.string.PurpleWriteHeld, reasonText(reason)), reason, retry, null);
    }

    /**
     * The refusal box the lists box and every settings screen share: a title,
     * the reason line, the remedy button ({@link #remedy}) and Close. The
     * remedy is left out when it has nothing to do here: Try again without a
     * {@code retry}, or Open Purple settings from Purple settings itself.
     */
    static void explain(BaseFragment fragment, String title, String message, String reason, Runnable retry, Runnable closed) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        final int remedy = remedy(reason);
        final boolean offered = remedy == REMEDY_OWNER || remedy == REMEDY_RETRY && retry != null
                || remedy == REMEDY_SETTINGS && !(fragment instanceof PurpleSettingsActivity);
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(title);
        builder.setMessage(message);
        if (offered) builder.setPositiveButton(remedyLabel(remedy), (dialog, which) ->
                AndroidUtilities.runOnUIThread(() -> runRemedy(fragment, remedy, retry)));
        builder.setNegativeButton(LocaleController.getString(R.string.Close),
                closed == null ? null : (dialog, which) -> closed.run());
        // Posted, because it is often asked for from another dialog's button,
        // and a fragment holds one dialog: shown in the same turn, the box
        // would be dismissed with the dialog that asked for it.
        AndroidUtilities.runOnUIThread(() -> {
            if (fragment.getParentActivity() != null && !fragment.isFinished) fragment.showDialog(builder.create());
        });
    }

    /** Runs a remedy; {@code retry} is what trying again means at the caller. */
    static void runRemedy(BaseFragment fragment, int remedy, Runnable retry) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        if (remedy == REMEDY_OWNER) PurpleAccountSettingsRows.chooseOwner(fragment);
        else if (remedy == REMEDY_SETTINGS) fragment.presentFragment(new PurpleSettingsActivity());
        else if (retry != null) retry.run();
    }
    private static void offlineEdit(BaseFragment fragment, String key, PurpleWriter.Current current) {
        if (offlineNoticeShown || key == null || !current.current() || !PurpleAccountOwner.ready()
                || !key.equals(PurpleAccountOwner.global()) || !BulletinFactory.canShowBulletin(fragment)) return;
        for (org.telegram.messenger.purple.PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
            if (key.equals(id.key)) return;
        }
        offlineNoticeShown = true;
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info,
                LocaleController.formatString(R.string.PurpleAppWideOfflineEdit, PurpleAccountSettingsRows.globalLabel(key)),
                LocaleController.getString(R.string.Change), Bulletin.DURATION_PROLONG,
                () -> { if (current.current()) PurpleGlobalAccountChoiceUI.open(fragment, current::current); }).show();
    }
    static void editorSaved(BaseFragment editor, String key,
            org.telegram.messenger.purple.PurpleAccountFiles.Identity origin, boolean staying) {
        BaseFragment target = staying ? editor : editor.getFragmentForAlert(0);
        if (target == null || !staying && target == editor || target.getFragmentView() == null) return;
        PurpleWriter.Current view = current(target);
        long role = PurpleAccountOwner.roleEpoch();
        long revision = PurpleAccountOwner.revision();
        PurpleWriter.Current guard = () -> view.current() && origin != null && origin.current()
                && role == PurpleAccountOwner.roleEpoch() && revision == PurpleAccountOwner.revision();
        WeakReference<BaseFragment> fragment = new WeakReference<>(target);
        Runnable notice = () -> offlineEdit(fragment.get(), key, guard);
        if (staying) notice.run();
        else org.telegram.messenger.AndroidUtilities.doOnPreDraw(target.getFragmentView(), notice);
    }
    static void bind(PurpleWriter.Session session, BaseFragment fragment) {
        if (session == null) return;
        WeakReference<Dialog> dialog = new WeakReference<>(fragment.getVisibleDialog());
        session.guard(() -> dialog.get() != null && dialog.get().isShowing());
    }
    static void show(BaseFragment fragment, Dialog dialog, PurpleWriter.Session session) {
        fragment.showDialog(dialog);
        bind(session, fragment);
    }
    private PurpleWriterUI() { }
}
