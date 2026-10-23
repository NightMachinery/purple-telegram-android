package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleAccountSettings;
import org.telegram.messenger.purple.PurpleAccountRowWrite;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.purple.PurpleAccountOwner;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.UItem;

import java.util.ArrayList;
import java.util.List;

public final class PurpleAccountSettingsRows {
    private static final int OWNER = 0x70750080;
    private static final int GLOBAL = 0x70750081;

    public static void add(ArrayList<UItem> items, int account) {
        PurpleAccountOwner.ensureStarted();
        items.add(UItem.asButton(OWNER, text(R.string.PurpleAccountOwner),
                PurpleAccountOwner.ready() ? ownerLabel()
                        : text(R.string.PurpleAccountOwnerChoose)));
        if (PurpleAccountOwner.ready() && globalChoiceVisible()) items.add(UItem.asButton(GLOBAL, text(R.string.PurpleAccountGlobal), globalLabel()));
        if (PurpleAccountOwner.recovered()) items.add(UItem.asShadow(text(R.string.PurpleAccountOwnerRecovered)));
        if (PurpleAccountOwner.localWarning()) items.add(UItem.asShadow(text(R.string.PurpleAccountLocalWarning)));
        items.add(UItem.asShadow(text(R.string.PurpleAccountRoles)));
        PurpleWorkModeUI.add(items, account);
        String listNotes = org.telegram.messenger.purple.PurpleListDiagnostics.accountNotes(account);
        if (!listNotes.isEmpty()) items.add(UItem.asShadow(listNotes));
        PurpleAccountSettings.Effective effective = PurpleAccountSettings.forAccount(account);
        if (effective != null) {
            if (effective.globalReadError || effective.globalGoodReadError
                    || !effective.globalError.isEmpty() || !effective.globalGoodError.isEmpty()) {
                items.add(UItem.asShadow(text(R.string.PurpleAccountGlobalFileError)));
            }
            if (effective.accountReadError || effective.accountGoodReadError
                    || !effective.accountError.isEmpty() || !effective.accountGoodError.isEmpty()) {
                items.add(UItem.asShadow(text(R.string.PurpleAccountOwnFileError)));
            }
            if (effective.globalGood || effective.accountGood) {
                items.add(UItem.asShadow(text(R.string.PurpleSettingsFileLastGood)));
            }
        }
        if (!PurpleAccountOwner.legacyEditorAllowed(account)) {
            items.add(UItem.asShadow(text(R.string.PurpleAccountWriteHeld)));
        }
    }

    private static boolean multipleRoles() {
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) keys.add(id.key);
        if (PurpleAccountOwner.global() != null) keys.add(PurpleAccountOwner.global());
        return keys.size() > 1;
    }

    public static String resetLabel() {
        return LocaleController.formatString(R.string.PurpleRowUseGlobal, globalLabel());
    }

    public static String scope(PurpleAccountRowWrite.Intent intent) {
        if (intent == null || !multipleRoles()) return null;
        String own = PurpleSettings.accountLabel(intent.account);
        if (own == null) own = text(R.string.PurpleAccountUnnamed);
        if (intent.fileLocal) return LocaleController.formatString(R.string.PurpleRowFileLocal, own);
        if (!intent.globalAccount) return LocaleController.formatString(intent.pinned
                ? R.string.PurpleRowOnlyAccount : R.string.PurpleRowSameGlobal,
                intent.pinned ? own : globalLabel());
        String caption = LocaleController.formatString(R.string.PurpleRowGlobalInherited, globalLabel());
        ArrayList<String> overrides = new ArrayList<>();
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
            if (intent.overriddenBy(id.slot)) overrides.add(label(id));
        }
        if (!overrides.isEmpty()) caption += "\n" + LocaleController.formatString(
                R.string.PurpleRowOtherAccountsOwn, android.text.TextUtils.join(", ", overrides));
        return caption;
    }

    public static String appWideScope(int account) {
        if (!multipleRoles()) return null;
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (origin != null && origin.key.equals(PurpleAccountOwner.global())) return null;
        boolean globalSignedIn = false;
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
            if (id.key.equals(PurpleAccountOwner.global())) globalSignedIn = true;
        }
        return globalSignedIn ? LocaleController.formatString(R.string.PurpleRowAppWide, globalLabel())
                : text(R.string.PurpleRowAppWideSignedOut);
    }

    private static String ownerLabel() {
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
            if (id.key.equals(PurpleAccountOwner.owner())) return label(id);
        }
        return text(R.string.PurpleAccountOwnerKnown);
    }

    static String globalLabel() { return globalLabel(PurpleAccountOwner.global()); }

    static String globalLabel(String key) {
        if (key == null) return text(R.string.PurpleAccountOwnerChoose);
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
            if (key.equals(id.key)) return label(id);
        }
        return LocaleController.formatString(R.string.PurpleGlobalChoiceSignedOut, key
                + (key.startsWith("1-") ? " " + text(R.string.PurpleAccountTestServers) : ""));
    }

    private static boolean globalChoiceVisible() {
        List<PurpleAccountFiles.Identity> accounts = PurpleAccountOwner.signedIn();
        boolean signedIn = false;
        for (PurpleAccountFiles.Identity id : accounts) if (id.key.equals(PurpleAccountOwner.global())) signedIn = true;
        return accounts.size() >= 2 || !signedIn;
    }

    public static boolean click(BaseFragment fragment, int row) {
        return click(fragment, row, () -> false);
    }

    public static boolean click(BaseFragment fragment, int row, PurpleAccountRowWrite.Current current) {
        if (PurpleWorkModeUI.click(fragment, row)) return true;
        if (row == GLOBAL) { PurpleGlobalAccountChoiceUI.open(fragment, current); return true; }
        if (row != OWNER && row != GLOBAL) return false;
        if (fragment.getParentActivity() == null) return true;
        if (row == OWNER && PurpleAccountOwner.ready()) return true;
        if (row == GLOBAL && !PurpleAccountOwner.ready()) return true;
        List<PurpleAccountFiles.Identity> choices = row == OWNER
                ? PurpleAccountOwner.eligible() : PurpleAccountOwner.signedIn();
        if (choices.isEmpty()) {
            refused(fragment, text(R.string.PurpleAccountNoEligible));
            return true;
        }
        if (row == OWNER) {
            for (int i = 0; i < choices.size(); ++i) {
                PurpleAccountFiles.Identity id = choices.get(i);
                if (id.slot == UserConfig.selectedAccount && id.key.startsWith("0-")) {
                    choices.remove(i);
                    choices.add(0, id);
                    break;
                }
            }
        }
        CharSequence[] labels = new CharSequence[choices.size()];
        for (int i = 0; i < labels.length; ++i) labels[i] = label(choices.get(i));
        AlertDialog.Builder dialog = new AlertDialog.Builder(fragment.getParentActivity());
        dialog.setTitle(text(row == OWNER ? R.string.PurpleAccountOwnerChoose : R.string.PurpleAccountGlobal));
        String bindingLabel = text(R.string.PurpleAccountBindingUnmatched);
        String binding = PurpleAccountOwner.bindingAccount();
        if (binding != null && !binding.isEmpty()) {
            for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
                if (id.key.equals(binding)) bindingLabel = LocaleController.formatString(R.string.PurpleAccountBindingMatches, label(id));
            }
        }
        dialog.setMessage(row == OWNER ? text(R.string.PurpleAccountOwnerQuestion) + "\n\n" + bindingLabel
                : text(R.string.PurpleAccountGlobalQuestion));
        dialog.setItems(labels, (whichDialog, index) -> {
            PurpleAccountFiles.Identity id = choices.get(index);
            if (!id.current() || fragment.getParentActivity() == null) { refused(fragment, text(R.string.PurpleAccountWriteHeld)); return; }
            AlertDialog.Builder confirmation = new AlertDialog.Builder(fragment.getParentActivity());
            confirmation.setTitle(label(id));
            confirmation.setMessage(text(R.string.PurpleAccountOwnerQuestion));
            confirmation.setPositiveButton(text(R.string.OK), (confirmed, button) -> {
                boolean changed = id.current() && PurpleAccountOwner.chooseOwner(id.key);
                if (changed) {
                    org.telegram.messenger.purple.PurpleAccountSettings.invalidate();
                    org.telegram.messenger.purple.PurpleWatcher.rebind();
                    PurpleGate.reload("account file choice");
                } else refused(fragment, text(R.string.PurpleAccountWriteHeld));
            });
            confirmation.setNegativeButton(text(R.string.Cancel), null);
            fragment.showDialog(confirmation.create());
        });
        dialog.setNegativeButton(text(R.string.Cancel), null);
        fragment.showDialog(dialog.create());
        return true;
    }

    public static void requestOwner(android.content.Context context) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null && fragment.getParentActivity() == context) click(fragment, OWNER);
    }

    private static void refused(BaseFragment fragment, String message) {
        if (fragment.getParentActivity() == null) return;
        AlertDialog.Builder refused = new AlertDialog.Builder(fragment.getParentActivity());
        refused.setMessage(message);
        refused.setPositiveButton(text(R.string.OK), null);
        fragment.showDialog(refused.create());
    }

    static String label(PurpleAccountFiles.Identity id) {
        String name = PurpleSettings.accountLabel(id.slot);
        if (name == null) name = text(R.string.PurpleAccountUnnamed);
        return id.key.startsWith("1-") ? name + " " + text(R.string.PurpleAccountTestServers) : name;
    }

    private static String text(int id) { return LocaleController.getString(id); }
    private PurpleAccountSettingsRows() { }
}
