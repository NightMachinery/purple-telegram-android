package org.telegram.ui;

import android.app.Activity;
import android.text.InputType;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurplePeekLocalSettings;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.UItem;
import java.util.ArrayList;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.messenger.LocaleController.formatString;

final class PurplePeekWaitOptions {
    private static final int ENABLED = 0x70750022, STEP = 0x70750023, MAX = 0x70750024, RESET = 0x70750025;
    static void fill(ArrayList<UItem> items) {
        PurplePeekLocalSettings.Snapshot policy = PurplePeekLocalSettings.snapshot();
        items.add(UItem.asHeader(getString(R.string.PurplePeekWaitHeader)));
        UItem enabled = UItem.asCheck(ENABLED, getString(R.string.PurplePeekEscalation));
        enabled.checked = policy.escalation; items.add(enabled);
        items.add(UItem.asButton(STEP, getString(R.string.PurplePeekWaitStep), formatString(R.string.PurplePeekSeconds, policy.step)));
        items.add(UItem.asButton(MAX, getString(R.string.PurplePeekWaitMax), formatString(R.string.PurplePeekSeconds, policy.maxExtra)));
        items.add(UItem.asButton(RESET, getString(R.string.PurplePeekWaitReset), formatString(R.string.PurplePeekWaitMinutes, policy.resetMinutes)));
        items.add(UItem.asShadow(getString(R.string.PurplePeekWaitHelp)));
    }
    static boolean click(BaseFragment fragment, UItem item, Runnable rebuild) {
        if (item.id < ENABLED || item.id > RESET) return false;
        PurplePeekLocalSettings.Snapshot opening = PurplePeekLocalSettings.snapshot();
        if (item.id == ENABLED) {
            PurplePeekControls.changeWaitOption(fragment, "escalation", opening.escalation ? 0 : 1, opening);
            rebuild.run(); return true;
        }
        org.telegram.messenger.purple.PurpleAccountFiles.Identity identity = org.telegram.messenger.purple.PurpleAccountFiles.capture(fragment.getCurrentAccount());
        long selection = org.telegram.messenger.purple.PurpleArchiveViewerModel.selectionGeneration();
        Activity activity = fragment.getParentActivity();
        if (activity == null) return true;
        String option = item.id == STEP ? "step" : item.id == MAX ? "max_extra" : "reset_minutes";
        int title = item.id == STEP ? R.string.PurplePeekWaitStep : item.id == MAX ? R.string.PurplePeekWaitMax : R.string.PurplePeekWaitReset;
        EditTextBoldCursor field = new EditTextBoldCursor(activity);
        field.setTextSize(16); field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setSingleLine(true); field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        field.setText(String.valueOf(opening.value(option))); field.selectAll();
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        dialog.setTitle(getString(title)); dialog.setView(field);
        dialog.setMessage(getString(R.string.PurplePeekWaitBounds));
        dialog.setPositiveButton(getString(R.string.Save), (ignored, which) -> {
            try {
                if (identity == null || !identity.current() || fragment.getParentActivity() != activity
                        || selection != org.telegram.messenger.purple.PurpleArchiveViewerModel.selectionGeneration()) {
                    PurpleWriterUI.explain(fragment, "session_stale", null); return;
                }
                String text = field.getText().toString().trim();
                if (!text.matches("[0-9]+")) throw new NumberFormatException();
                int value = Integer.parseInt(text);
                if (!opening.accepts(option, value)) throw new NumberFormatException();
                PurplePeekControls.changeWaitOption(fragment, option, value, opening);
                rebuild.run();
            } catch (NumberFormatException e) {
                PurpleWriterUI.explain(fragment, getString(R.string.PurplePeekWaitBounds), null);
            }
        });
        dialog.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(dialog.create()); return true;
    }
    private PurplePeekWaitOptions() { }
}
