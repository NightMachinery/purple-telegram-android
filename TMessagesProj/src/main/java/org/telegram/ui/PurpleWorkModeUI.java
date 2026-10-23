package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleAccountOwner;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.messenger.purple.PurpleWorkMode;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import java.util.ArrayList;
import java.util.HashSet;

final class PurpleWorkModeUI {
    private static final int ROW = 0x707500b0;
    static void add(ArrayList<UItem> items, int account) {
        HashSet<String> accounts = new HashSet<>();
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) accounts.add(id.key);
        if (PurpleAccountOwner.global() != null) accounts.add(PurpleAccountOwner.global());
        if (!PurpleAccountOwner.ready() || accounts.size() < 2) return;
        items.add(UItem.asButton(ROW, text(R.string.PurpleWorkModeDevice), value(account)));
    }
    private static String value(int account) {
        int mode = PurpleWorkMode.mode(account);
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(account);
        if (mode == 0 && id != null && id.key.equals(PurpleAccountOwner.global())) return text(R.string.PurpleWorkModeOn);
        return mode == 2 ? text(R.string.PurpleWorkModeStock) : mode == 1
                ? LocaleController.formatString(R.string.PurpleWorkModeFollow, PurpleAccountSettingsRows.globalLabel())
                : LocaleController.formatString(R.string.PurpleWorkModeOwn, PurpleSettings.accountLabel(account), PurpleAccountSettingsRows.globalLabel());
    }
    static boolean click(BaseFragment fragment, int row) {
        if (row != ROW) return false;
        PurpleWriter.Session writer = PurpleWriterUI.capture(fragment, false);
        if (writer == null) return true;
        String own = PurpleSettings.accountLabel(fragment.getCurrentAccount());
        String global = PurpleAccountSettingsRows.globalLabel(writer.globalKey);
        boolean isGlobal = writer.key.equals(writer.globalKey);
        int[] modes = isGlobal ? new int[]{0, 2} : new int[]{0, 1, 2};
        CharSequence[] labels = isGlobal ? new CharSequence[]{text(R.string.PurpleWorkModeOn), text(R.string.PurpleWorkModeStock)}
                : new CharSequence[]{LocaleController.formatString(R.string.PurpleWorkModeOwn, own, global),
                    LocaleController.formatString(R.string.PurpleWorkModeFollow, global), text(R.string.PurpleWorkModeStock)};
        for (int i = 0; i < modes.length; ++i) if (modes[i] == writer.mode) labels[i] = LocaleController.formatString(R.string.PurpleWorkModeSelected, labels[i]);
        AlertDialog.Builder dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        dialog.setTitle(LocaleController.formatString(isGlobal ? R.string.PurpleWorkModeGlobalTitle : R.string.PurpleWorkModeListsTitle, own));
        dialog.setItems(labels, (selected, index) -> {
            if (!writer.current()) { failed(fragment); return; }
            if (writer.setMode(modes[index]) != null) failed(fragment);
        });
        dialog.setNegativeButton(text(R.string.Cancel), null);
        PurpleWriterUI.show(fragment, dialog.create(), writer);
        return true;
    }
    private static void failed(BaseFragment fragment) {
        if (fragment.getParentActivity() != null) android.widget.Toast.makeText(fragment.getParentActivity(),
                text(R.string.PurpleAccountWriteHeld), android.widget.Toast.LENGTH_LONG).show();
    }
    private static String text(int id) { return LocaleController.getString(id); }
    private PurpleWorkModeUI() { }
}
