package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleAccountOwner;
import org.telegram.messenger.purple.PurpleAccountRowWrite;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;
import java.util.ArrayList;

final class PurpleAccountEditorUI {
    private static final int OWN = 0x70750086;
    private static final int GLOBAL = 0x70750087;
    static void add(ArrayList<UItem> items, int account) {
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        String global = PurpleAccountOwner.global();
        if (origin == null || global == null || !PurpleAccountOwner.ready()) return;
        boolean distinct = !origin.key.equals(global);
        PurpleAccountFiles files = PurpleAccountFiles.resolve(origin.key, PurpleAccountOwner.owner());
        if (files == null) return;
        items.add(distinct ? UItem.asButton(OWN, LocaleController.formatString(R.string.PurpleEditorAccountTitle,
                PurpleAccountSettingsRows.label(origin)), files.settings.getPath())
                : UItem.asButton(OWN, LocaleController.getString(R.string.PurpleEditSettings)));
        if (distinct) items.add(UItem.asButton(GLOBAL, LocaleController.formatString(R.string.PurpleEditorAppWideTitle,
                PurpleAccountSettingsRows.globalLabel(global))));
    }
    static boolean click(BaseFragment fragment, int id, PurpleAccountRowWrite.Current current) {
        if (id != OWN && id != GLOBAL) return false;
        if (current != null && current.current() && PurpleAccountOwner.ready()) {
            fragment.presentFragment(new PurpleSettingsEditorActivity(fragment.getCurrentAccount(), id == GLOBAL));
        }
        return true;
    }
    private PurpleAccountEditorUI() { }
}
