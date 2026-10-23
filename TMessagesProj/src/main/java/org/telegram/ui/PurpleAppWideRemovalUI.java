package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountRowWrite;
import org.telegram.messenger.purple.PurpleAppWideRemoval;
import org.telegram.messenger.purple.PurpleGlobalChoiceDialog;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

final class PurpleAppWideRemovalUI {
    private static final int REMOVE = 0x70750082;
    private final WeakReference<BaseFragment> fragment;
    private final WeakReference<Activity> activity;
    private final PurpleAccountRowWrite.Current origin;
    private final PurpleGlobalChoiceDialog lifetime = new PurpleGlobalChoiceDialog();
    private PurpleAppWideRemovalUI(BaseFragment view, PurpleAccountRowWrite.Current current) {
        fragment = new WeakReference<>(view); activity = new WeakReference<>(view.getParentActivity()); origin = current;
    }
    static void add(ArrayList<UItem> items, int account, PurpleAccountRowWrite.Current current) {
        PurpleAppWideRemoval.Preview preview = PurpleAppWideRemoval.preview(account, current);
        if (preview == null || !preview.current()) return;
        String own = accountName(preview);
        String summary = LocaleController.formatString(R.string.PurpleAppWideInactive, preview.count(),
                PurpleAccountSettingsRows.globalLabel(preview.globalKey), own);
        for (int i = 0; i < preview.groupCount(); ++i) summary += "\n" + LocaleController.formatString(
                R.string.PurpleAppWideInactiveGroup, preview.groupLabel(i), preview.groupSize(i));
        items.add(UItem.asShadow(summary));
        items.add(UItem.asButton(REMOVE, LocaleController.formatString(R.string.PurpleAppWideRemoveFrom, own)));
    }
    static boolean click(BaseFragment view, int row, PurpleAccountRowWrite.Current current) {
        if (row != REMOVE) return false;
        new PurpleAppWideRemovalUI(view, current).show();
        return true;
    }
    private boolean current() {
        BaseFragment view = fragment.get(); Activity owner = activity.get();
        return view != null && owner != null && view.getParentActivity() == owner && origin.current();
    }
    private void show() {
        if (!current()) return;
        PurpleAppWideRemoval.Preview preview = PurpleAppWideRemoval.preview(fragment.get().getCurrentAccount(), this::current);
        if (preview == null) { outcome(false, false); return; }
        PurpleGlobalChoiceDialog.Token token = lifetime.picker();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity.get());
        builder.setTitle(LocaleController.formatString(R.string.PurpleAppWideRemoveFrom, accountName(preview)));
        builder.setMessage(LocaleController.formatString(R.string.PurpleAppWideRemoveQuestion, accountName(preview),
                PurpleAccountSettingsRows.globalLabel(preview.globalKey)) + "\n\n" + LocaleController.formatString(
                R.string.PurpleAppWideRemovePaths, preview.path(), preview.backupPath()));
        builder.setPositiveButton(text(R.string.PurpleAppWideRemoveAction), (dialog, button) -> {
            if (!current() || !lifetime.current(token)) return;
            PurpleAppWideRemoval.Outcome result = preview.apply(() -> current() && lifetime.current(token));
            if (current() && lifetime.current(token)) {
                lifetime.dismissed(token);
                if (!result.ok) outcome(result.changed, result.backupChanged);
            }
        });
        builder.setNegativeButton(text(R.string.Cancel), null);
        builder.setOnPreDismissListener(dialog -> lifetime.dismissed(token));
        AlertDialog dialog = builder.create();
        WeakReference<AlertDialog> weak = new WeakReference<>(dialog);
        lifetime.bind(token, dialog, () -> {
            AlertDialog live = weak.get(); BaseFragment view = fragment.get();
            return current() && live != null && view != null && live.isShowing() && !live.isDismissed()
                    && view.getVisibleDialog() == live;
        });
        Dialog returned = current() ? fragment.get().showDialog(dialog, ignored -> lifetime.dismissed(token)) : null;
        if (!lifetime.shown(token, returned) || !preview.current()) {
            lifetime.dismissed(token);
            if (dialog.isShowing()) dialog.dismiss();
            return;
        }
        View cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (cancel == null) { lifetime.dismissed(token); dialog.dismiss(); return; }
        cancel.setFocusableInTouchMode(true);
        if (!cancel.requestFocus()) { lifetime.dismissed(token); dialog.dismiss(); }
    }
    private void outcome(boolean saved, boolean backup) {
        if (!current()) return;
        AlertDialog.Builder message = new AlertDialog.Builder(activity.get());
        message.setMessage(text(saved ? R.string.PurpleAppWideRemoveSavedContext : backup
                ? R.string.PurpleAppWideRemoveBackupOnly : R.string.PurpleAppWideRemoveUnavailable));
        message.setPositiveButton(text(R.string.OK), null);
        fragment.get().showDialog(message.create());
    }
    private static String accountName(PurpleAppWideRemoval.Preview preview) {
        String value = PurpleSettings.accountLabel(preview.account);
        if (value == null) value = text(R.string.PurpleAccountUnnamed);
        return preview.accountKey.startsWith("1-") ? value + " " + text(R.string.PurpleAccountTestServers) : value;
    }
    private static String text(int key) { return LocaleController.getString(key); }
}
