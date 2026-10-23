package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleAccountOwner;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

public final class PurpleListWarningsUI {
    public static void show(PurpleAccountFiles.Identity id, String global, String message) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || !id.current() || !global.equals(PurpleAccountOwner.global())
                || !BulletinFactory.canShowBulletin(fragment)) return;
        PurpleWriter.Current current = PurpleWriterUI.current(fragment);
        long role = PurpleAccountOwner.roleEpoch();
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, message,
                LocaleController.formatString(R.string.PurpleOpenAccountSettings, PurpleSettings.accountLabel(id.slot)),
                Bulletin.DURATION_PROLONG, () -> {
                    if (current.current() && id.current() && role == PurpleAccountOwner.roleEpoch()
                            && global.equals(PurpleAccountOwner.global())) fragment.presentFragment(new PurpleSettingsEditorActivity(id.slot, false));
                }).show();
    }
    private PurpleListWarningsUI() { }
}
