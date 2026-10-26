package org.telegram.ui;

import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleArchive;
import org.telegram.messenger.purple.PurpleArchivePurgeModel;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalFragment;

final class PurpleArchivePurgeUI {
    static final int TRASH = 0x70750480, EDITS = 0x70750481, RETRY = 0x70750482;
    static void add(ArrayList<UItem> items, UniversalFragment fragment) {
        PurpleArchive.Status status = PurpleArchive.status(fragment.getCurrentAccount());
        if (status.state.startsWith("Maintenance")) items.add(UItem.asButton(RETRY, text(R.string.PurpleArchivePurgeRetry)));
        else if (status.state.equals("Ready")) {
            items.add(UItem.asButton(TRASH, text(R.string.PurpleArchiveEmptyTrash)));
            items.add(UItem.asButton(EDITS, text(R.string.PurpleArchiveClearEdits)));
        }
    }
    static boolean click(UniversalFragment fragment, int row) {
        if (row != TRASH && row != EDITS && row != RETRY) return false;
        try { open(fragment, PurpleArchivePurgeModel.Scope.account(fragment.getCurrentAccount()), row == EDITS ? 1 : 0); }
        catch (Exception | LinkageError e) { unavailable(fragment); }
        return true;
    }
    static void open(BaseFragment fragment, PurpleArchivePurgeModel.Scope scope, int kind) {
        if (fragment.isFinished || fragment.getParentActivity() == null) return;
        try { new Dialog(fragment, scope, kind).show(); }
        catch (Exception | LinkageError e) { unavailable(fragment); }
    }
    private static void unavailable(BaseFragment fragment) {
        PurpleWriterUI.explain(fragment, text(R.string.PurpleArchiveTitle), text(R.string.PurpleArchivePurgeStale), "session_stale", null, null);
    }
    private static final class Dialog implements Runnable {
        final WeakReference<BaseFragment> owner;
        final View openingView;
        final int account, kind;
        final PurpleArchivePurgeModel.Scope scope;
        final String notice;
        final AlertDialog dialog;
        PurpleArchivePurgeModel model;
        PurpleArchivePurgeModel.Preview preview;
        boolean waiting, started, resume, closed;
        Dialog(BaseFragment fragment, PurpleArchivePurgeModel.Scope scope, int kind) {
            owner = new WeakReference<>(fragment); openingView = fragment.getFragmentView(); account = fragment.getCurrentAccount(); this.scope = scope; this.kind = kind;
            notice = LocaleController.formatString(R.string.PurpleArchivePurgeScope, scope.account, scope.title.isEmpty() ? text(R.string.PurpleArchivePurgeAllChats) : scope.title)
                + (scope.aliases.isEmpty() ? "" : "\n" + LocaleController.formatString(R.string.PurpleArchivePurgeAliases, scope.aliases))
                + "\n\n" + text(kind == 0 ? R.string.PurpleArchiveEmptyTrashNotice : R.string.PurpleArchiveClearEditsNotice)
                + "\n\n" + text(R.string.PurpleArchivePurgeScopeNotice);
            dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(text(kind == 0 ? R.string.PurpleArchiveEmptyTrash : R.string.PurpleArchiveClearEdits)).setMessage(notice)
                .setPositiveButton(text(R.string.PurpleArchivePurgeConfirm), (d, which) -> submit())
                .setNeutralButton(text(R.string.PurpleArchivePurgeReview), (d, which) -> review())
                .setNegativeButton(text(R.string.Close), (d, which) -> dialogDismiss())
                .setOnDismissListener(d -> close()).create();
            dialog.setDismissDialogByButtons(false);
        }
        void show() { BaseFragment fragment = owner.get(); if (fragment == null) return; fragment.showDialog(dialog); review(); AndroidUtilities.runOnUIThread(this, 1000); }
        boolean current() { BaseFragment fragment = owner.get(); return !closed && fragment != null && !fragment.isFinished && fragment.getParentActivity() != null
            && fragment.getCurrentAccount() == account && fragment.getFragmentView() == openingView && (model == null || model.available()); }
        void dialogDismiss() { dialog.dismiss(); }
        void close() { closed = true; AndroidUtilities.cancelRunOnUIThread(this); if (model != null) model.close(); preview = null; }
        void buttons() {
            View positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE), review = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (positive != null) { positive.setEnabled(current() && !waiting && !started && (resume || preview != null)); if (positive instanceof TextView) ((TextView)positive).setText(text(resume ? R.string.PurpleArchivePurgeRetry : R.string.PurpleArchivePurgeConfirm)); }
            if (review != null) review.setEnabled(current() && !waiting && !started);
        }
        void message(String value) { dialog.setMessage(notice + "\n\n" + value); buttons(); }
        void review() {
            if (!current() || waiting || started) return;
            if (model != null) model.close(); preview = null; resume = false;
            try {
                model = new PurpleArchivePurgeModel(scope, kind); waiting = true; message(text(R.string.PurpleArchiveOpening));
                if (!model.prepare(this::received)) { waiting = false; message(text(R.string.PurpleArchivePurgeStale)); }
            } catch (Exception | LinkageError e) { waiting = false; message(text(R.string.PurpleArchivePurgeStale)); }
        }
        void submit() {
            if (!current() || waiting || started || model == null) return;
            waiting = true; buttons();
            boolean accepted = resume ? model.retry(this::received) : model.confirm(preview, this::received);
            preview = null;
            if (!accepted) { waiting = false; message(text(R.string.PurpleArchivePurgeStale)); }
        }
        void received(PurpleArchivePurgeModel.State state) {
            if (!current()) return; waiting = false; preview = state.preview; resume = state.state.equals("Maintenance");
            if (state.state.equals("Committed")) { started = true; message(text(R.string.PurpleArchivePurgeRunning)); }
            else if (preview != null) message(LocaleController.formatString(R.string.PurpleArchivePurgeCounts, preview.counts.get(0).toString(), preview.counts.get(1).toString(), preview.counts.get(2).toString(), preview.counts.get(3).toString()));
            else message(text(resume ? R.string.PurpleArchivePurgeResumeNotice : state.state.equals("Stale") ? R.string.PurpleArchivePurgeStale
                : state.state.equals("Busy") ? R.string.PurpleArchiveQueryBusy : state.state.equals("SchemaMismatch") ? R.string.PurpleArchiveSchemaMismatch
                : state.state.equals("ReadOnly") ? R.string.PurpleArchiveReadOnly : R.string.PurpleArchivePurgeRefused));
        }
        @Override public void run() {
            if (!current()) { dialog.dismiss(); close(); return; }
            if (started) {
                PurpleArchive.Status state = PurpleArchive.status(account);
                if (state.state.equals("Ready")) message(text(R.string.PurpleArchivePurgeReady));
                else if (state.state.equals("Maintenance held")) message(text(state.reason.toLowerCase(java.util.Locale.US).contains("admission") ? R.string.PurpleArchivePurgeAdmissionHeld : R.string.PurpleArchivePurgeHeld));
                else if (state.state.equals("Maintenance")) message(text(R.string.PurpleArchivePurgeRunning));
                else message(text(R.string.PurpleArchivePurgeRefused));
            }
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    }
    private static String text(int id) { return LocaleController.getString(id); }
    private PurpleArchivePurgeUI() {}
}
