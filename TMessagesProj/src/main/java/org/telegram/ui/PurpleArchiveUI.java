package org.telegram.ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleArchive;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalFragment;

final class PurpleArchiveUI {
    private static final int ROW = 0x70750320;
    private static final WeakHashMap<UniversalFragment, Boolean> requested = new WeakHashMap<>();
    static void add(ArrayList<UItem> items, UniversalFragment fragment) {
        PurpleArchive.Status status = PurpleArchive.status(fragment.getCurrentAccount());
        items.add(UItem.asButton(ROW, text(R.string.PurpleArchiveTitle), summary(status)));
        if (status.gap) items.add(UItem.asShadow(text(R.string.PurpleArchiveGap)));
        if (!requested.containsKey(fragment)) { requested.put(fragment, true); refresh(fragment); }
    }
    private static String summary(PurpleArchive.Status status) {
        switch (status.state) {
            case "Ready": return LocaleController.formatString(R.string.PurpleArchiveReady, status.bytes);
            case "Opening": case "Busy": return text(R.string.PurpleArchiveOpening);
            case "Close pending": return text(R.string.PurpleArchiveClosePending);
            case "Recovery unavailable": return text(R.string.PurpleArchiveRecoveryUnavailable);
            case "LoginUnavailable": return text(R.string.PurpleArchiveLoginUnavailable);
            case "LoginRecordUnavailable": return text(R.string.PurpleArchiveLoginRecordUnavailable);
            case "CatalogueUnavailable": return text(R.string.PurpleArchiveCatalogueUnavailable);
            case "CaptureRecoveryUnavailable": return text(R.string.PurpleArchiveCaptureRecoveryUnavailable);
            case "StorageUnavailable": return text(R.string.PurpleArchiveStorageUnavailable);
            case "DatabaseUnavailable": return text(R.string.PurpleArchiveDatabaseUnavailable);
            case "MetadataUnavailable": return text(R.string.PurpleArchiveMetadataUnavailable);
            case "NativeUnavailable": return text(R.string.PurpleArchiveNativeUnavailable);
            case "KeyUnavailable": return text(R.string.PurpleArchiveKeyUnavailable);
            case "SchemaMismatch": return text(R.string.PurpleArchiveSchemaMismatch);
            case "ReadOnly": return text(R.string.PurpleArchiveReadOnly);
            case "Removal pending": return text(R.string.PurpleArchiveRemovalPending);
            case "Not prepared": case "Removed": case "Closed": return text(R.string.PurpleArchiveNotPrepared);
            case "Signed out": return text(R.string.PurpleArchiveSignedOut);
            case "Requires Android 6 (API 23)": return text(R.string.PurpleArchiveRequires23);
            default: return text(R.string.PurpleArchiveUnavailable);
        }
    }
    static boolean click(UniversalFragment fragment, int row) {
        if (row != ROW) return false;
        if (fragment.getParentActivity() == null) return true;
        PurpleArchive.Status status = PurpleArchive.status(fragment.getCurrentAccount());
        AlertDialog.Builder dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
            .setTitle(text(R.string.PurpleArchiveTitle))
            .setMessage(summary(status) + "\n\n" + text(R.string.PurpleArchivePolicy) + "\n\n" + text(R.string.PurpleArchiveStorageOnly))
            .setPositiveButton(text(R.string.PurpleArchiveRefresh), (d, which) -> refresh(fragment));
        if (!status.state.equals("Signed out") && !status.state.equals("Requires Android 6 (API 23)")) {
            PurpleArchive.RemovalRequest request = PurpleArchive.prepareRemoval(fragment.getCurrentAccount());
            int account = fragment.getCurrentAccount();
            android.view.View view = fragment.getFragmentView();
            dialog.setNeutralButton(text(R.string.PurpleArchiveRemove), (d, which) -> {
                if (fragment.isFinished || fragment.getParentActivity() == null || fragment.getCurrentAccount() != account || fragment.getFragmentView() != view) return;
                fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                    .setTitle(text(R.string.PurpleArchiveRemove)).setMessage(text(R.string.PurpleArchiveRemoveConfirm))
                    .setPositiveButton(text(R.string.PurpleArchiveRemove), (confirmation, button) -> {
                        if (!fragment.isFinished && fragment.getParentActivity() != null && fragment.getCurrentAccount() == account && fragment.getFragmentView() == view)
                            if (!PurpleArchive.remove(request, () -> {
                                if (!request.completed) PurpleWriterUI.explain(fragment, text(R.string.PurpleArchiveTitle), text(R.string.PurpleArchiveUnavailable), "session_stale", () -> refresh(fragment), null);
                                refresh(fragment);
                            }))
                                PurpleWriterUI.explain(fragment, text(R.string.PurpleArchiveTitle), text(R.string.PurpleArchiveUnavailable), "session_stale", () -> refresh(fragment), null);
                    }).setNegativeButton(text(R.string.Cancel), null).create());
            });
        }
        fragment.showDialog(dialog.setNegativeButton(text(R.string.Close), null).create());
        return true;
    }
    private static void refresh(UniversalFragment fragment) {
        int account = fragment.getCurrentAccount();
        android.view.View openingView = fragment.getFragmentView();
        WeakReference<UniversalFragment> weak = new WeakReference<>(fragment);
        PurpleArchive.refresh(account, () -> {
            UniversalFragment current = weak.get();
            if (current != null && !current.isFinished && current.getCurrentAccount() == account && (openingView == null || current.getFragmentView() == openingView) && current.listView != null)
                current.listView.adapter.update(true);
        });
    }
    private static String text(int id) { return LocaleController.getString(id); }
    private PurpleArchiveUI() {}
}
