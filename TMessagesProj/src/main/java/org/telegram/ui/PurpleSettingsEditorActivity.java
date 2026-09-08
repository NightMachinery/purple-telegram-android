/*
 * This is the source code of Purple Telegram for Android.
 *
 * Editing settings.toml on the phone. The file lives in app-private storage,
 * so without this screen the only way to change a preset from a phone is to
 * send yourself a new file - which is fine for a rewrite and absurd for fixing
 * a typo in a list name.
 *
 * The point of the file being TOML is that a person writes it, so this is a
 * plain monospaced text box and not a form: everything the format can say is
 * editable here, including the things no screen in this app has a control for.
 * What the screen adds is the one thing a text box cannot - being told, as you
 * type, whether the core can still read what you have written.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;

public class PurpleSettingsEditorActivity extends BaseFragment {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final int done_button = 1;
    private static final int revert_button = 2;

    /** Long enough that typing a word does not start a parse per keystroke. */
    private static final long VALIDATE_DELAY = 300L;

    private EditTextBoldCursor field;
    private TextView status;

    /** The text as opened, for Revert and for "has this been edited". */
    private String openedText = "";

    /**
     * What the file was when it was opened. Length and mtime rather than a
     * hash: this is not a security question, it is "did something else write
     * here while I was typing", and the two together answer it for every writer
     * this app has - each of which replaces the file rather than editing it.
     */
    private long openedLength;
    private long openedModified;

    /** The most recent parse, or null while one is outstanding. */
    private PurpleCore.ParseResult parsed;

    /**
     * Which validation run is current. A parse runs off the UI thread and the
     * text can move on while it does, so a result arriving for text that has
     * since changed is dropped rather than drawn.
     */
    private int validateGeneration;

    private Runnable validate;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PurpleEditorTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == done_button) {
                    save(false);
                } else if (id == revert_button) {
                    field.setText(openedText);
                    field.setSelection(field.length());
                }
            }
        });

        final ActionBarMenu menu = actionBar.createMenu();
        final ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(revert_button, getString(R.string.PurpleEditorRevert));
        final View done = menu.addItemWithWidth(done_button, R.drawable.ic_ab_done, AndroidUtilities.dp(56));
        done.setContentDescription(getString(R.string.Done));

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10),
                AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        status.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        // The one place in this screen where tapping does something other than
        // move the cursor: a parse error names a line and a column, and the
        // useful thing to do with it is go there.
        status.setOnClickListener(v -> jumpToError());
        layout.addView(status, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        field.setTypeface(Typeface.MONOSPACE);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setCursorSize(AndroidUtilities.dp(18));
        field.setCursorWidth(1.5f);
        field.setBackgroundDrawable(null);
        field.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        field.setGravity(Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        // No maxLines, no newline filter and no IME_ACTION_DONE: a return key
        // that saved instead of breaking the line would make the file
        // unwritable, and every line of a TOML file is one the user typed.
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setVerticalScrollBarEnabled(true);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleValidate();
            }
        });
        layout.addView(field, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 0, 1f));

        fragmentView = layout;

        open();
        return fragmentView;
    }

    // The two branches of the same intent. With animations on, focusing during
    // the slide-in fights it, so the keyboard waits for the transition to end;
    // with them off there is no transition to wait for and onResume is the only
    // moment there is. Both are needed, and neither on its own works.
    @Override
    public void onResume() {
        super.onResume();
        if (!org.telegram.messenger.MessagesController.getGlobalMainSettings()
                .getBoolean("view_animations", true)) {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        if (isOpen) {
            field.requestFocus();
            AndroidUtilities.showKeyboard(field);
        }
    }

    /** Reads the file fresh, and remembers what it was. */
    private void open() {
        final File file = PurpleSettings.settingsFile();
        String text = "";
        if (file.exists()) {
            final byte[] bytes = read(file);
            if (bytes != null) {
                text = new String(bytes, UTF_8);
            }
        }
        openedText = text;
        openedLength = file.exists() ? file.length() : -1L;
        openedModified = file.exists() ? file.lastModified() : -1L;
        field.setText(text);
        field.setSelection(0);
        scheduleValidate();
    }

    // ---- validation ----------------------------------------------------------

    private void scheduleValidate() {
        if (validate != null) {
            AndroidUtilities.cancelRunOnUIThread(validate);
        }
        parsed = null;
        setStatus(getString(R.string.PurpleEditorChecking), Theme.key_windowBackgroundWhiteGrayText2);
        validate = this::runValidate;
        AndroidUtilities.runOnUIThread(validate, VALIDATE_DELAY);
    }

    /**
     * Parses off the UI thread.
     *
     * PurpleCore.parse is pure and touches no file, so it can be asked about
     * text that is not on disk and may never be - which is the whole reason
     * this screen can say "12:5: expected ..." before anything is written.
     */
    private void runValidate() {
        final int generation = ++validateGeneration;
        final byte[] bytes = field.getText().toString().getBytes(UTF_8);
        Utilities.globalQueue.postRunnable(() -> {
            PurpleCore.ParseResult result;
            try {
                result = PurpleCore.parse(bytes);
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                FileLog.e(e);
                result = null;
            }
            final PurpleCore.ParseResult answer = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (generation != validateGeneration) {
                    // The text moved on while this ran. Drawing this answer
                    // would put a stale error under text that no longer has it.
                    return;
                }
                parsed = answer;
                drawStatus();
            });
        });
    }

    private void drawStatus() {
        if (parsed == null) {
            setStatus(getString(R.string.PurpleCoreUnavailable), Theme.key_text_RedRegular);
            return;
        }
        if (!parsed.ok) {
            setStatus(parsed.error, Theme.key_text_RedRegular);
            return;
        }
        final int warnings = parsed.warnings.size();
        if (warnings == 0) {
            setStatus(getString(R.string.PurpleEditorOk), Theme.key_windowBackgroundWhiteGreenText);
        } else {
            setStatus(formatPluralString("PurpleEditorWarnings", warnings), Theme.key_color_orange);
        }
    }

    private void setStatus(CharSequence text, int colorKey) {
        if (status != null) {
            status.setText(text);
            status.setTextColor(Theme.getColor(colorKey));
        }
    }

    /**
     * Moves the cursor to where the parser stopped.
     *
     * The error reads "LINE:COLUMN: text", one-based on both counts, which is
     * what the core writes and what every other reader of that string assumes.
     */
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

    // ---- saving --------------------------------------------------------------

    /**
     * @param overwrite true once the user has been told the file moved and said
     *                  to write anyway
     */
    private void save(boolean overwrite) {
        if (getParentActivity() == null) {
            return;
        }
        final byte[] bytes = field.getText().toString().getBytes(UTF_8);
        if (bytes.length > PurpleSettings.MAX_SIZE) {
            error(getString(R.string.PurpleEditorTooBig));
            return;
        }
        // Parsed here rather than trusting the status line, which is debounced
        // and may still be describing the text as it was a keystroke ago. A
        // parse of at most 64 KB is a few milliseconds and this is a button
        // press, so doing it on this thread costs nothing and removes the race
        // outright.
        final PurpleCore.ParseResult result;
        try {
            result = PurpleCore.parse(bytes);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            error(getString(R.string.PurpleCoreUnavailable));
            return;
        }
        if (!result.ok) {
            // Writing a file the core cannot read would leave the app running
            // its cached resolution with no way back except this screen - which
            // would then be showing the file that broke it.
            parsed = result;
            drawStatus();
            error(getString(R.string.PurpleEditorSaveBroken));
            return;
        }
        final File file = PurpleSettings.settingsFile();
        final long length = file.exists() ? file.length() : -1L;
        final long modified = file.exists() ? file.lastModified() : -1L;
        if (!overwrite && (length != openedLength || modified != openedModified)) {
            // An import, a list edit from a chat menu, or the desktop's copy
            // arriving. Saving over it silently is the one outcome nobody wants.
            final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(getString(R.string.PurpleEditorMovedTitle));
            builder.setMessage(getString(R.string.PurpleEditorMoved));
            builder.setPositiveButton(getString(R.string.PurpleEditorOverwrite),
                    (dialog, which) -> save(true));
            builder.setNegativeButton(getString(R.string.PurpleEditorReload),
                    (dialog, which) -> open());
            builder.show();
            return;
        }
        if (!PurpleSettings.save(bytes, "editor")) {
            error(getString(R.string.PurpleEditorSaveFailed));
            return;
        }
        openedText = field.getText().toString();
        openedLength = file.exists() ? file.length() : -1L;
        openedModified = file.exists() ? file.lastModified() : -1L;

        // Warnings are the parser saying it read something it could not use -
        // a list nothing names, a preset naming a list that is not there. The
        // import path throws them away, which is exactly the wrong place: the
        // person who can act on them is the one who just typed the line.
        final List<String> warnings = result.warnings;
        if (warnings.isEmpty()) {
            finishFragment();
            return;
        }
        final StringBuilder message = new StringBuilder();
        for (int a = 0, n = warnings.size(); a < n; ++a) {
            if (a > 0) {
                message.append("\n\n");
            }
            message.append(warnings.get(a));
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PurpleEditorWarningsTitle));
        builder.setMessage(message.toString());
        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> finishFragment());
        builder.show();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (field == null || openedText.equals(field.getText().toString())) {
            return super.onBackPressed(invoked);
        }
        if (!invoked || getParentActivity() == null) {
            return false;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PurpleEditorDiscardTitle));
        builder.setMessage(getString(R.string.PurpleEditorDiscard));
        builder.setPositiveButton(getString(R.string.Discard), (dialog, which) -> {
            openedText = field.getText().toString();
            finishFragment();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.show();
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        if (validate != null) {
            AndroidUtilities.cancelRunOnUIThread(validate);
            validate = null;
        }
        super.onFragmentDestroy();
    }

    private void error(CharSequence text) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.PurpleEditorTitle));
        builder.setMessage(text);
        builder.setPositiveButton(getString(R.string.OK), null);
        builder.show();
    }

    private static byte[] read(File file) {
        try {
            final java.io.InputStream in = new java.io.FileInputStream(file);
            try {
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }
}
