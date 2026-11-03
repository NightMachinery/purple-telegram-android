/*
 * This is the source code of Purple Telegram for Android.
 *
 * The "Media recording" settings: the [recording] keys of
 * docs/media-recording-design.md, "Settings". This build carries the F10
 * rows (the Recording bin, bin_keep and keep_discarded_p, and the round video
 * journal); the other features' rows join it as they land.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleRecordingSettings;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class PurpleRecordingSettingsActivity extends UniversalFragment {

    // The media recording block, 0x70750500-0x7075050f (0x70750500 is the
    // seek step row in PurpleSettingsActivity).
    static final int ROW_BIN = 0x70750503;
    static final int ROW_BIN_KEEP = 0x70750504;
    static final int ROW_KEEP_DISCARDED = 0x70750505;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PurpleMediaRecording);
    }

    @Override
    public void onFragmentDestroy() {
        PurpleWriterUI.retire(this);
        super.onFragmentDestroy();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // Read once per rebuild, so a refused write leaves each row where the file is.
        final PurpleRecordingSettings settings = PurpleRecordingSettings.read();
        items.add(UItem.asHeader(getString(R.string.PurpleRecordingBin)));
        items.add(UItem.asButton(ROW_BIN, getString(R.string.PurpleRecordingBin)));
        items.add(UItem.asButton(ROW_BIN_KEEP, getString(R.string.PurpleRecordingBinKeep), binKeepLabel(settings.binKeepSeconds)));
        final UItem discarded = UItem.asCheck(ROW_KEEP_DISCARDED, getString(R.string.PurpleRecordingKeepDiscarded));
        discarded.checked = settings.keepDiscarded;
        items.add(discarded);
        items.add(UItem.asShadow(getString(R.string.PurpleRecordingKeepDiscardedInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
        case ROW_BIN:
            presentFragment(new PurpleRecordingBinActivity());
            break;
        case ROW_BIN_KEEP:
            chooseBinKeep();
            break;
        case ROW_KEEP_DISCARDED: {
            final PurpleWriter.Session session = PurpleWriterUI.captureOrExplain(this, true, this::rebuild);
            if (session == null) return;
            write(PurpleWriter.setTableBool(session, "recording", "keep_discarded_p", !item.checked, "keep discarded switch"));
            break;
        }
        default:
            break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    static String binKeepLabel(int seconds) {
        if (seconds <= 0) return getString(R.string.PurpleRecordingBinKeepNever);
        if (seconds % (24 * 3600) == 0 && seconds >= 7 * 24 * 3600) {
            return formatString(R.string.PurpleRecordingBinKeepDays, seconds / (24 * 3600));
        }
        return formatString(R.string.PurpleRecordingBinKeepHours, seconds / 3600);
    }

    private void chooseBinKeep() {
        if (getParentActivity() == null) return;
        final int[] choices = PurpleRecordingSettings.BIN_KEEP_CHOICES;
        final int current = PurpleRecordingSettings.read().binKeepSeconds;
        final CharSequence[] labels = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) labels[i] = binKeepLabel(choices[i]);
        final AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity());
        dialog.setTitle(getString(R.string.PurpleRecordingBinKeep));
        dialog.setItems(labels, (d, which) -> {
            if (choices[which] == current) return;
            final PurpleWriter.Session session = PurpleWriterUI.captureOrExplain(this, true, this::rebuild);
            if (session == null) return;
            write(PurpleWriter.setTableString(session, "recording", "bin_keep",
                    PurpleRecordingSettings.binKeepValue(choices[which]), "bin keep chooser"));
        });
        dialog.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(dialog.create());
    }

    /** Redraws after a write, and says why when there was not one. */
    void write(String error) {
        if (error != null && !PurpleWriterUI.explainHeld(this, error, this::rebuild)
                && BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this)
                    .createErrorBulletin(formatString(R.string.PurpleWriteFailed, error))
                    .show();
        }
        rebuild();
    }

    void rebuild() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }
}
