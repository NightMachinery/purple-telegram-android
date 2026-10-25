package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleAccountRowWrite;
import org.telegram.messenger.purple.PurpleGlobalAccountChoice;
import org.telegram.messenger.purple.PurpleGlobalChoiceDialog;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

final class PurpleGlobalAccountChoiceUI {
    private final WeakReference<BaseFragment> fragment;
    private final WeakReference<Activity> activity;
    private final PurpleAccountRowWrite.Current origin;
    private final PurpleGlobalChoiceDialog lifetime = new PurpleGlobalChoiceDialog();
    private PurpleGlobalAccountChoice.Session session;
    private PurpleGlobalAccountChoiceUI(BaseFragment view, PurpleAccountRowWrite.Current origin) {
        fragment = new WeakReference<>(view); activity = new WeakReference<>(view.getParentActivity()); this.origin = origin;
    }
    private boolean current() {
        BaseFragment view = fragment.get(); Activity owner = activity.get();
        return view != null && owner != null && view.getParentActivity() == owner && origin.current();
    }
    static void open(BaseFragment view, PurpleAccountRowWrite.Current origin) {
        PurpleGlobalAccountChoiceUI choice = new PurpleGlobalAccountChoiceUI(view, origin);
        choice.show();
    }
    private void show() {
        if (!current()) return;
        PurpleGlobalChoiceDialog.Token token = lifetime.picker();
        if (token == null) return;
        session = PurpleGlobalAccountChoice.begin(fragment.get().getCurrentAccount(), this::current);
        if (session == null) { lifetime.dismissed(token); error(false); return; }
        ArrayList<String> keys = new ArrayList<>(); ArrayList<CharSequence> names = new ArrayList<>();
        for (PurpleAccountFiles.Identity id : session.accounts) {
            keys.add(id.key);
            String name = PurpleSettings.accountLabel(id.slot);
            if (name == null) name = text(R.string.PurpleAccountUnnamed);
            if (id.key.startsWith("1-")) name += " " + text(R.string.PurpleAccountTestServers);
            names.add(name);
        }
        if (!keys.contains(session.global)) {
            keys.add(session.global);
            names.add(LocaleController.formatString(R.string.PurpleGlobalChoiceSignedOut, session.global
                    + (session.global.startsWith("1-") ? " " + text(R.string.PurpleAccountTestServers) : "")));
        }
        AlertDialog.Builder picker = new AlertDialog.Builder(activity.get());
        picker.setTitle(text(R.string.PurpleAccountGlobal));
        picker.setItems(names.toArray(new CharSequence[0]), (dialog, index) -> {
            if (!lifetime.current(token) || !current() || !session.current()) return;
            String key = keys.get(index);
            if (session.global.equals(key)) { lifetime.dismissed(token); return; }
            PurpleGlobalAccountChoice.Choice selected = session.preview(key, () -> lifetime.current() && current());
            if (selected == null) { lifetime.dismissed(token); error(false); return; }
            if (!lifetime.handoff(token)) return;
            confirm(selected, names.get(index).toString());
        });
        picker.setNegativeButton(text(R.string.Cancel), null);
        display(picker, token);
    }
    private void confirm(PurpleGlobalAccountChoice.Choice choice, String name) {
        if (!current()) return;
        PurpleGlobalChoiceDialog.Token token = lifetime.confirmation();
        if (token == null) return;
        String message = LocaleController.formatString(R.string.PurpleGlobalChoicePreview, name,
                choice.before("lists"), choice.after("lists"), choice.before("presets"), choice.after("presets"),
                onOff(choice.before("schedule")), onOff(choice.after("schedule")),
                onOff(choice.before("premium")), onOff(choice.after("premium")))
                + "\n\n" + LocaleController.formatString(R.string.PurpleGlobalChoicePaths, choice.currentPath(), choice.path());
        if (choice.flag("noAppWide")) message += "\n\n" + text(R.string.PurpleGlobalChoiceNoAppWide);
        if (choice.flag("defaults")) message += "\n" + text(R.string.PurpleGlobalChoiceDefaults);
        if (choice.flag("good")) message += "\n" + text(R.string.PurpleSettingsFileLastGood);
        if (choice.before("good").equals("true")) message += "\n" + text(R.string.PurpleGlobalChoicePreviousGood);
        if (choice.flag("normal")) message += "\n\n" + LocaleController.formatString(R.string.PurpleGlobalChoiceNormal, choice.preset());
        AlertDialog.Builder confirmation = new AlertDialog.Builder(activity.get());
        confirmation.setTitle(text(R.string.PurpleGlobalChoiceConfirm));
        confirmation.setMessage(message);
        confirmation.setPositiveButton(text(R.string.PurpleGlobalChoiceConfirm), (dialog, button) -> {
            if (!lifetime.current(token) || !current()) return;
            if (!choice.current()) { lifetime.dismissed(token); error(false); return; }
            PurplePeekControls.changeSource(fragment.get(), choice.selectedKey());
            lifetime.dismissed(token);
        });
        confirmation.setNegativeButton(text(R.string.Cancel), null);
        AlertDialog dialog = display(confirmation, token);
        if (dialog != null && !choice.current()) {
            lifetime.dismissed(token);
            dialog.dismiss();
            error(false);
        }
    }
    private AlertDialog display(AlertDialog.Builder builder, PurpleGlobalChoiceDialog.Token token) {
        builder.setOnPreDismissListener(dialog -> lifetime.dismissed(token));
        AlertDialog dialog = builder.create();
        WeakReference<AlertDialog> weak = new WeakReference<>(dialog);
        lifetime.bind(token, dialog, () -> {
            AlertDialog live = weak.get();
            BaseFragment view = fragment.get();
            return live != null && view != null && current() && live.isShowing() && !live.isDismissed()
                    && view.getVisibleDialog() == live;
        });
        Dialog shown = current() ? fragment.get().showDialog(dialog, ignored -> lifetime.dismissed(token)) : null;
        if (!lifetime.shown(token, shown)) {
            if (dialog.isShowing()) dialog.dismiss();
            return null;
        }
        return dialog;
    }
    private void error(boolean saved) {
        if (!current()) return;
        AlertDialog.Builder message = new AlertDialog.Builder(activity.get());
        message.setMessage(text(saved ? R.string.PurpleGlobalChoiceSavedIncomplete : R.string.PurpleGlobalChoiceUnavailable));
        message.setPositiveButton(text(R.string.OK), null);
        if (current()) fragment.get().showDialog(message.create());
    }
    private static String onOff(String value) { return text(value.equals("true") ? R.string.PurpleGlobalChoiceOn : R.string.PurpleGlobalChoiceOff); }
    private static String text(int key) { return LocaleController.getString(key); }
}
