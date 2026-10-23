package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountOwner;
import org.telegram.messenger.purple.PurpleWriter;
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
    private static PurpleWriter.Current current(BaseFragment fragment) {
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
        PurpleWriter.Current current = current(fragment);
        WeakReference<BaseFragment> view = new WeakReference<>(fragment);
        String key = PurpleAccountOwner.global();
        return PurpleWriter.capture(fragment.getCurrentAccount(), appWide, current,
                () -> offlineEdit(view.get(), key, current));
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
