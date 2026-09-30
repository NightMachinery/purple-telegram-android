/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleSyncCore;
import org.telegram.messenger.purple.PurpleSyncHistory;
import org.telegram.messenger.purple.PurpleSyncRunner;
import org.telegram.messenger.purple.PurpleSyncSettingsFile;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PurpleSyncHistoryActivity extends UniversalFragment {
    private static final int ROW_ENTRY_FIRST = 1000;

    private final PurpleSyncActivity host;
    private List<PurpleSyncHistory.Entry> entries = Collections.emptyList();
    private boolean loaded;

    public PurpleSyncHistoryActivity(PurpleSyncActivity host) {
        this.host = host;
        setCurrentAccount(host.getCurrentAccount());
    }

    @Override
    public boolean onFragmentCreate() {
        final PurpleSyncRunner runner = host.runner();
        if (runner == null || !runner.history(list -> {
            entries = list;
            loaded = true;
            if (listView != null) {
                listView.adapter.update(true);
            }
        })) {
            loaded = true;
        }
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PurpleSyncHistoryTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        for (int i = 0; i != entries.size(); ++i) {
            items.add(UItem.asButton(ROW_ENTRY_FIRST + i,
                    PurpleSyncText.historyRow(entries.get(i))));
        }
        final String intro = getString(R.string.PurpleSyncHistoryIntro);
        items.add(UItem.asShadow(loaded && entries.isEmpty()
                ? PurpleSyncText.joined(getString(R.string.PurpleSyncHistoryEmpty), intro)
                : intro));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final int index = item.id - ROW_ENTRY_FIRST;
        if (index < 0 || index >= entries.size()) {
            return;
        }
        final PurpleSyncHistory.Entry entry = entries.get(index);
        final PurpleSyncRunner runner = host.runner();
        if (runner != null) {
            runner.preview(entry.id, preview -> showPreview(entry, preview));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void showPreview(PurpleSyncHistory.Entry entry, PurpleSyncRunner.Preview preview) {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        boolean restorable = false;
        if (!entry.existed) {
            PurpleSyncReviewDialog.label(activity, layout,
                    getString(R.string.PurpleSyncEntryNoFile), false);
        } else if (preview == null) {
            PurpleSyncReviewDialog.label(activity, layout,
                    getString(R.string.PurpleSyncEntryUnreadable), false);
        } else if (!PurpleSyncCore.isSettingsTextWritable(preview.text)) {
            PurpleSyncReviewDialog.label(activity, layout,
                    getString(R.string.PurpleSyncEntryNotText), false);
        } else if (preview.current == null
                || preview.current.status == PurpleSyncSettingsFile.Status.Invalid) {
            PurpleSyncReviewDialog.label(activity, layout,
                    getString(R.string.PurpleSyncEntryCurrentInvalid), false);
        } else {
            PurpleSyncReviewDialog.label(activity, layout, getString(
                    preview.current.status == PurpleSyncSettingsFile.Status.Absent
                            ? R.string.PurpleSyncEntryCreates
                            : R.string.PurpleSyncEntryChanges), false);
            PurpleSyncReviewDialog.ChangePreview.add(activity, layout, host.runner())
                    .show(preview.diff);
            restorable = true;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PurpleSyncSavedSettings));
        builder.setMessage(PurpleSyncText.historyRow(entry));
        builder.setView(layout);
        if (restorable) {
            builder.setPositiveButton(getString(R.string.PurpleSyncRestore), null);
        }
        builder.setNegativeButton(getString(R.string.Close), null);
        final AlertDialog dialog = builder.create();
        if (restorable) {
            dialog.setDismissDialogByButtons(false);
            dialog.setPositiveButtonListener((d, which) -> confirmRestore(dialog, entry));
            dialog.setNegativeButton(getString(R.string.Close), (d, which) -> d.dismiss());
        }
        showDialog(dialog);
    }

    private void confirmRestore(AlertDialog previewDialog, PurpleSyncHistory.Entry entry) {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PurpleSyncRestore));
        builder.setMessage(formatString(R.string.PurpleSyncRestoreQuestion,
                PurpleSyncText.moment(entry.createdMs)));
        builder.setPositiveButton(getString(R.string.PurpleSyncRestore), (dialog, which) -> {
            if (host.restoreFromHistory(entry)) {
                previewDialog.dismiss();
                finishFragment();
            } else {
                Toast.makeText(activity, getString(R.string.PurpleSyncWait),
                        Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.create().show();
    }
}
