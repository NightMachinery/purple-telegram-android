package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.purple.PurpleAccountEditor;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleGlobalChoiceDialog;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

public class PurpleSettingsEditorActivity extends BaseFragment {
    private static final int DONE = 1;
    private static final int REVERT = 2;
    private static final long VALIDATE_DELAY = 300L;
    private EditTextBoldCursor field;
    private TextView status;
    private TextView banner;
    private PurpleAccountEditor.Session session;
    private PurpleAccountFiles.Identity origin;
    private Activity window;
    private boolean appWide;
    private volatile boolean alive;
    private volatile boolean resumed;
    private volatile int targetGeneration;
    private int validationGeneration;
    private Runnable validation;
    private AlertDialog activeDialog;
    private AlertDialog savingDialog;
    private String openedText = "";
    private PurpleCore.ParseResult parsed;

    public PurpleSettingsEditorActivity() { this(org.telegram.messenger.UserConfig.selectedAccount, false); }
    public PurpleSettingsEditorActivity(int account, boolean appWide) {
        setCurrentAccount(account);
        this.appWide = appWide;
    }
    @Override public boolean onFragmentCreate() {
        origin = PurpleAccountFiles.capture(currentAccount);
        alive = origin != null;
        return alive && super.onFragmentCreate();
    }
    private boolean live() {
        return alive && resumed && !isFinished && origin != null && origin.current()
                && window != null && window == getParentActivity()
                && (savingDialog == null || savingDialog == activeDialog && savingDialog.isShowing());
    }
    @Override public View createView(Context context) {
        window = getParentActivity(); resumed = true;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PurpleEditorTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1 && onBackPressed(true)) finishFragment();
                else if (id == DONE) save(null);
                else if (id == REVERT && live() && session != null) { field.setText(openedText); field.setSelection(field.length()); }
            }
        });
        ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(REVERT, getString(R.string.PurpleEditorRevert));
        actionBar.createMenu().addItemWithWidth(DONE, R.drawable.ic_ab_done, AndroidUtilities.dp(56))
                .setContentDescription(getString(R.string.Done));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        banner = label(context); layout.addView(banner, LayoutHelper.createLinear(-1, -2));
        status = label(context); status.setOnClickListener(v -> jumpToError());
        layout.addView(status, LayoutHelper.createLinear(-1, -2));
        field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        field.setTypeface(Typeface.MONOSPACE);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorSize(AndroidUtilities.dp(18)); field.setCursorWidth(1.5f);
        field.setBackgroundDrawable(null);
        field.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        field.setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setVerticalScrollBarEnabled(true);
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable s) { scheduleValidation(); }
        });
        layout.addView(field, LayoutHelper.createLinear(-1, 0, 1f));
        fragmentView = layout; open(appWide); return layout;
    }
    private TextView label(Context context) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        return view;
    }
    private void open(boolean target) {
        if (!live()) return;
        ++targetGeneration; ++validationGeneration;
        if (session != null) session.retire();
        appWide = target;
        int generation = targetGeneration;
        session = PurpleAccountEditor.open(currentAccount, target, () -> live() && generation == targetGeneration);
        if (session == null) {
            field.setEnabled(false); status.setText(getString(R.string.PurpleEditorContextChanged)); return;
        }
        field.setEnabled(true);
        openedText = session.openedText;
        field.setText(openedText); field.setSelection(0);
        String own = PurpleAccountSettingsRows.label(origin);
        String global = PurpleAccountSettingsRows.globalLabel(session.globalKey);
        boolean distinct = !origin.key.equals(session.globalKey);
        actionBar.setTitle(!distinct ? getString(R.string.PurpleEditorTitle)
                : LocaleController.formatString(session.global ? R.string.PurpleEditorAppWideTitle : R.string.PurpleEditorAccountTitle,
                session.global ? global : own));
        actionBar.setTitleActionRunnable(distinct ? this::chooseTarget : null);
        banner.setVisibility(session.global ? View.GONE : View.VISIBLE);
        banner.setText(LocaleController.formatString(R.string.PurpleEditorInactiveBanner, global, own));
        scheduleValidation();
    }
    private final class DialogPermit {
        final PurpleGlobalChoiceDialog authority = new PurpleGlobalChoiceDialog();
        final PurpleGlobalChoiceDialog.Token token = authority.picker();
        AlertDialog dialog;
        boolean current() { return authority.current(token); }
        void show(AlertDialog.Builder builder) {
            builder.setOnPreDismissListener(ignored -> authority.dismissed(token));
            dialog = builder.create();
            authority.bind(token, dialog, () -> live() && dialog.isShowing() && !dialog.isDismissed()
                    && getVisibleDialog() == dialog);
            android.app.Dialog shown = live() ? showDialog(dialog, ignored -> authority.dismissed(token)) : null;
            if (!authority.shown(token, shown) && dialog.isShowing()) dialog.dismiss();
        }
    }
    private void chooseTarget() {
        if (!live() || session == null || !session.current()) return;
        DialogPermit permit = new DialogPermit();
        AlertDialog.Builder builder = new AlertDialog.Builder(window);
        builder.setTitle(getString(R.string.PurpleEditorChooseTarget));
        builder.setItems(new CharSequence[]{LocaleController.formatString(R.string.PurpleEditorAccountTitle,
                        PurpleAccountSettingsRows.label(origin)), LocaleController.formatString(R.string.PurpleEditorAppWideTitle,
                        PurpleAccountSettingsRows.globalLabel(session.globalKey))}, (dialog, which) -> {
            if (permit.current() && live() && session != null && session.current()) switchTarget(which == 1);
        });
        permit.show(builder);
    }
    private void switchTarget(boolean target) {
        if (target == appWide || !live()) return;
        if (!dirty()) { open(target); return; }
        int generation = targetGeneration;
        DialogPermit permit = new DialogPermit();
        AlertDialog.Builder builder = new AlertDialog.Builder(window);
        builder.setTitle(getString(R.string.PurpleEditorDirtySwitchTitle));
        builder.setMessage(getString(R.string.PurpleEditorDirtySwitch));
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            if (permit.current() && live() && generation == targetGeneration) save(() -> open(target));
        });
        builder.setNegativeButton(getString(R.string.Discard), (dialog, which) -> {
            if (permit.current() && live() && generation == targetGeneration) open(target);
        });
        builder.setNeutralButton(getString(R.string.Cancel), null);
        permit.show(builder);
    }
    private boolean dirty() { return field != null && !openedText.equals(field.getText().toString()); }
    private void scheduleValidation() {
        if (validation != null) AndroidUtilities.cancelRunOnUIThread(validation);
        ++validationGeneration; parsed = null;
        if (status != null) status.setText(getString(R.string.PurpleEditorChecking));
        validation = this::validateText;
        AndroidUtilities.runOnUIThread(validation, VALIDATE_DELAY);
    }
    private void validateText() {
        if (!live() || session == null) return;
        int generation = validationGeneration;
        PurpleAccountEditor.Session opened = session;
        String text = field.getText().toString();
        Utilities.globalQueue.postRunnable(() -> {
            PurpleCore.ParseResult answer = opened.parse(text);
            boolean supported = answer != null && answer.ok && opened.preview(text) != null;
            AndroidUtilities.runOnUIThread(() -> {
                if (!live() || generation != validationGeneration || session != opened || !text.equals(field.getText().toString())) return;
                parsed = answer;
                status.setText(answer == null ? getString(R.string.PurpleEditorContextChanged)
                        : !answer.ok ? answer.error : !supported ? getString(R.string.PurpleEditorValidationHeld)
                        : answer.warnings.isEmpty() ? getString(R.string.PurpleEditorOk)
                        : android.text.TextUtils.join("\n", answer.warnings));
            });
        });
    }
    private void jumpToError() {
        if (parsed == null || parsed.ok || TextUtils.isEmpty(parsed.error)) {
            return;
        }
        final int firstColon = parsed.error.indexOf(':');
        final int secondColon = firstColon < 0 ? -1 : parsed.error.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return;
        }
        final int line;
        final int column;
        try {
            line = Integer.parseInt(parsed.error.substring(0, firstColon).trim());
            column = Integer.parseInt(parsed.error.substring(firstColon + 1, secondColon).trim());
        } catch (NumberFormatException e) {
            return;
        }
        final String text = field.getText().toString();
        int offset = 0;
        for (int a = 1; a < line; ++a) {
            final int next = text.indexOf('\n', offset);
            if (next < 0) {
                offset = text.length();
                break;
            }
            offset = next + 1;
        }
        offset = Math.min(text.length(), offset + Math.max(0, column - 1));
        field.requestFocus();
        field.setSelection(offset);
        AndroidUtilities.showKeyboard(field);
    }

    private void save(Runnable afterSave) {
        if (!live() || session == null || !session.current()) { error(getString(R.string.PurpleEditorContextChanged)); return; }
        ++validationGeneration;
        if (validation != null) AndroidUtilities.cancelRunOnUIThread(validation);
        String text = field.getText().toString();
        int generation = targetGeneration;
        PurpleAccountEditor.Session opened = session;
        Utilities.globalQueue.postRunnable(() -> {
            PurpleAccountEditor.Preview preview = opened.preview(text);
            AndroidUtilities.runOnUIThread(() -> {
                if (!live() || session != opened || generation != targetGeneration || !text.equals(field.getText().toString())) return;
                if (preview == null) { error(getString(R.string.PurpleEditorValidationHeld)); return; }
                String message = opened.path + "\n\n" + getString(R.string.PurpleEditorLocalSaveOnly);
                if (opened.needsDirectory) message += "\n\n" + getString(R.string.PurpleEditorCreatesDirectory);
                if (!preview.inactiveNotes().isEmpty()) message += "\n\n" + getString(R.string.PurpleEditorInactiveNotes) + "\n" + preview.inactiveNotes();
                if (!preview.warnings().isEmpty()) message += "\n\n" + getString(R.string.PurpleEditorPreviewWarnings) + "\n" + preview.warnings();
                AlertDialog.Builder builder = new AlertDialog.Builder(window);
                builder.setTitle(getString(R.string.PurpleEditorSavePreview)); builder.setMessage(message);
                DialogPermit permit = new DialogPermit();
                builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
                    if (!live() || !permit.current() || session != opened
                            || generation != targetGeneration || !text.equals(field.getText().toString())) return;
                    ++validationGeneration;
                    if (validation != null) AndroidUtilities.cancelRunOnUIThread(validation);
                    activeDialog = permit.dialog; savingDialog = permit.dialog;
                    PurpleAccountEditor.Outcome result;
                    try { result = opened.save(preview, () -> permit.current() && live() && generation == targetGeneration
                            && text.equals(field.getText().toString())); }
                    finally { savingDialog = null; }
                    if (!live() || generation != targetGeneration) return;
                    activeDialog = null;
                    if (!result.ok) {
                        error(getString(result.changed ? R.string.PurpleEditorSavedContextChanged
                                : result.backupChanged ? R.string.PurpleEditorBackupOnly
                                : result.directoryAttempted ? R.string.PurpleEditorDirectoryPartial : R.string.PurpleEditorSaveStopped));
                        return;
                    }
                    if (opened.global && result.changed) {
                        PurpleWriterUI.editorSaved(this, opened.accountKey, origin, afterSave != null);
                    }
                    openedText = text;
                    if (afterSave != null) afterSave.run();
                    else finishFragment();
                });
                builder.setNegativeButton(getString(R.string.Cancel), null);
                permit.show(builder);
            });
        });
    }
    @Override public boolean onBackPressed(boolean invoked) {
        if (!dirty()) return super.onBackPressed(invoked);
        if (!invoked || !live()) return false;
        DialogPermit permit = new DialogPermit();
        AlertDialog.Builder builder = new AlertDialog.Builder(window);
        builder.setTitle(getString(R.string.PurpleEditorDiscardTitle)); builder.setMessage(getString(R.string.PurpleEditorDiscard));
        builder.setPositiveButton(getString(R.string.Discard), (dialog, which) -> { if (permit.current() && live()) finishFragment(); });
        builder.setNegativeButton(getString(R.string.Cancel), null); permit.show(builder); return false;
    }
    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (field != null && !org.telegram.messenger.MessagesController.getGlobalMainSettings()
                .getBoolean("view_animations", true)) { field.requestFocus(); AndroidUtilities.showKeyboard(field); }
    }
    @Override public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen && field != null && live()) { field.requestFocus(); AndroidUtilities.showKeyboard(field); }
    }
    @Override public void onPause() { resumed = false; ++validationGeneration; super.onPause(); }
    @Override public void onFragmentDestroy() {
        alive = false; ++targetGeneration; ++validationGeneration;
        if (session != null) session.retire();
        if (validation != null) AndroidUtilities.cancelRunOnUIThread(validation);
        super.onFragmentDestroy();
    }
    private void error(CharSequence text) {
        if (!live()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(window);
        builder.setTitle(getString(R.string.PurpleEditorTitle)); builder.setMessage(text);
        builder.setPositiveButton(getString(R.string.OK), null); showDialog(builder.create());
    }
}
