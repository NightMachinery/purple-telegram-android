/*
 * This is the source code of Purple Telegram for Android.
 *
 * The Work Mode list-membership box: which lists a chat is in, and the only way
 * to change that without editing settings.toml by hand. Mirrors the desktop
 * fork's purple_list_menu.cpp - see docs/purple/work_mode.md, "Putting a chat
 * in a list".
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleListMenu;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.EditTextBoldCursor;

import java.util.List;

public final class PurpleListBox {

    private PurpleListBox() {
    }

    /**
     * Shows the box for one chat.
     *
     * Every list is offered, including one that matches by {@code kinds}:
     * adding a chat to a rule-based list writes an explicit member id, which is
     * how you pull one chat out of a rule that would otherwise sweep it up
     * somewhere else.
     *
     * Membership is global rather than per preset. A chat is in a list or it is
     * not; what changes between presets is what that list <i>does</i>, and
     * whether the preset names it at all.
     */
    public static void show(BaseFragment fragment, int currentAccount, long dialogId) {
        if (fragment == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        PurpleGate.ensureLoaded();
        final List<PurpleCore.ListEntry> lists = PurpleListMenu.listsFor(currentAccount, dialogId);
        if (lists.isEmpty()) {
            // Reached when the file has gone missing between the menu being
            // offered and this opening. Naming the file beats an empty box,
            // which would read as "your lists are gone".
            final AlertDialog.Builder empty = new AlertDialog.Builder(activity, resourcesProvider);
            empty.setTitle(getString(R.string.PurpleLists));
            empty.setMessage(formatString(R.string.PurplePresetNotConfigured,
                    PurpleSettings.settingsFile().getAbsolutePath()));
            empty.setPositiveButton(getString(R.string.OK), null);
            fragment.showDialog(empty.create());
            return;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(getString(R.string.PurpleLists));

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        builder.setView(layout);

        // What is deciding this chat right now, above the lists that could change
        // it. Until this line existed, a chat missing from the view and a chat
        // sitting in it under a mode looked identical from here.
        final String verdict = PurpleListMenu.verdictLine(currentAccount, dialogId);
        final TextView verdictView = verdict == null ? null
                : note(activity, verdict,
                        Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        if (verdictView != null) {
            layout.addView(verdictView);
        }

        // The checkbox rows live in a container of their own so they can be
        // replaced wholesale rather than only updated: the row below creates a
        // list, which changes how many there are, and rebuilding the cells
        // inside the open box is what keeps that from being a dismiss and a
        // fresh trip through the chat menu.
        final LinearLayout listRows = new LinearLayout(activity);
        listRows.setOrientation(LinearLayout.VERTICAL);
        layout.addView(listRows);

        final Rows rows = new Rows(activity, resourcesProvider, listRows, verdictView,
                builder, currentAccount, dialogId);
        rows.build(lists);

        // After the lists and before the "until" decisions, because that is
        // where it belongs in both directions: making a list is still a
        // statement about which lists there are, and everything below is a
        // statement about this afternoon.
        final TextCell create = new TextCell(activity, resourcesProvider);
        create.setText(getString(R.string.PurpleNewList), true);
        create.setOnClickListener(v -> showNewList(
                fragment, activity, resourcesProvider, rows, currentAccount, dialogId));
        layout.addView(create);

        // The "until" decisions, set apart from the lists above: a list is a
        // standing rule, and these are a thing you are doing this afternoon.
        // Only under a preset, because an override is a statement about one -
        // there would be nothing for it to outrank under Normal.
        if (PurpleGate.filtering()) {
            addUntilRow(fragment, activity, layout, resourcesProvider, builder,
                    currentAccount, dialogId, PurpleCore.OVERRIDE_SHOW, R.string.PurpleShowUntil);
            addUntilRow(fragment, activity, layout, resourcesProvider, builder,
                    currentAccount, dialogId, PurpleCore.OVERRIDE_HIDE, R.string.PurpleHideUntil);
            addUntilRow(fragment, activity, layout, resourcesProvider, builder,
                    currentAccount, dialogId, PurpleCore.OVERRIDE_NOTIFY, R.string.PurpleNotifyUntil);

            final int running = PurpleGate.overrideKind(currentAccount, dialogId);
            if (running != PurpleCore.OVERRIDE_NONE) {
                // Offered only when there is one, so the box does not carry a
                // permanently inert row for something most chats never use.
                final TextCell cancel = new TextCell(activity, resourcesProvider);
                cancel.setText(formatString(R.string.PurpleCancelUntil, kindName(running)), false);
                cancel.setOnClickListener(v -> {
                    builder.getDismissRunnable().run();
                    PurpleGate.setOverride(currentAccount, dialogId, running, 0);
                });
                layout.addView(cancel);
            }
        }

        layout.addView(note(activity, getString(R.string.PurpleListsInfo),
                Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider)));

        builder.setNegativeButton(getString(R.string.Close), null);
        fragment.showDialog(builder.create());
    }

    /**
     * The checkbox rows, and the one thing that puts the file's answer back
     * into them.
     *
     * A holder rather than a static method with six arguments because the rows
     * are no longer fixed: creating a list changes how many there are, so what
     * a tick has to update is not a known set of cells but whatever the file
     * now says. Read again rather than flipped in place, for the reason the
     * write itself rereads: a list can match by {@code kinds} as well as by
     * member id, so filing a chat in one list can change what another says
     * about it, and the verdict line above them is a statement about the
     * running resolution rather than about any single row.
     */
    private static final class Rows {
        private final Activity activity;
        private final Theme.ResourcesProvider resourcesProvider;
        private final LinearLayout container;
        private final TextView verdictView;
        private final AlertDialog.Builder builder;
        private final int currentAccount;
        private final long dialogId;

        private PurpleCore.ListEntry[] entries = new PurpleCore.ListEntry[0];
        private CheckBoxCell[] cells = new CheckBoxCell[0];

        Rows(Activity activity, Theme.ResourcesProvider resourcesProvider,
                LinearLayout container, TextView verdictView, AlertDialog.Builder builder,
                int currentAccount, long dialogId) {
            this.activity = activity;
            this.resourcesProvider = resourcesProvider;
            this.container = container;
            this.verdictView = verdictView;
            this.builder = builder;
            this.currentAccount = currentAccount;
            this.dialogId = dialogId;
        }

        /** One cell per list, replacing whatever was there. */
        void build(List<PurpleCore.ListEntry> lists) {
            container.removeAllViews();
            final int count = lists.size();
            entries = lists.toArray(new PurpleCore.ListEntry[0]);
            cells = new CheckBoxCell[count];
            for (int a = 0; a < count; ++a) {
                final int index = a;
                final CheckBoxCell cell = new CheckBoxCell(activity, 1, resourcesProvider);
                cells[a] = cell;
                cell.setText(titleOf(entries[a]), null, entries[a].member, a < count - 1);
                cell.setPadding(dp(4), 0, dp(4), 0);
                cell.setBackground(Theme.createSelectorDrawable(
                        Theme.getColor(Theme.key_listSelector, resourcesProvider),
                        Theme.RIPPLE_MASK_ALL));
                cell.setOnClickListener(v -> {
                    // The box stays open. Filing a chat is usually more than one
                    // decision - out of one list and into another - and closing
                    // on the first made the second a fresh trip through the chat
                    // menu. The chat list rebuilding underneath is not a reason
                    // to close: nothing here reads it, and the rows are
                    // refreshed from the file, which is the only thing they were
                    // ever showing.
                    //
                    // The checkbox and not the captured entry says which way this
                    // goes, so a row that has been ticked once already asks for
                    // the opposite of what is on screen rather than of what the
                    // box was built with.
                    final boolean wanted = !cell.isChecked();
                    final String error = PurpleListMenu.toggle(
                            currentAccount, dialogId, entries[index], wanted);
                    if (error != null) {
                        // The file is unchanged and so is the running
                        // resolution; the detail is in the log, which is where a
                        // TOML problem belongs.
                        Toast.makeText(activity,
                                getString(R.string.PurpleListsFailed),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    refresh();
                });
                container.addView(cell);
            }
        }

        /** Puts the file's answer back into the box after a tick or a new list. */
        void refresh() {
            final List<PurpleCore.ListEntry> fresh =
                    PurpleListMenu.listsFor(currentAccount, dialogId);
            if (fresh.isEmpty()) {
                // The file has gone since the box opened. There is nothing left
                // for these rows to be about, and an empty box would read as
                // "your lists are gone" rather than "the file is".
                builder.getDismissRunnable().run();
                return;
            }
            if (moved(fresh)) {
                // A list added here, or renamed, added to or reordered by an
                // outside edit between two taps. Either way row three would be
                // ticking a list it no longer names, so the cells are rebuilt
                // rather than reused. This used to close the box instead; it no
                // longer has to, since making a list is now one of the ways it
                // happens.
                build(fresh);
            } else {
                for (int a = 0; a < cells.length; ++a) {
                    entries[a] = fresh.get(a);
                    cells[a].setChecked(entries[a].member, true);
                }
            }
            if (verdictView != null) {
                // Only when there is still one to show. It goes away with the
                // preset and not with a list edit, so a null here means the
                // answer is unchanged rather than empty.
                final String verdict = PurpleListMenu.verdictLine(currentAccount, dialogId);
                if (verdict != null) {
                    verdictView.setText(verdict);
                }
            }
        }

        /** Whether the file's lists are no longer the ones these cells were built from. */
        private boolean moved(List<PurpleCore.ListEntry> fresh) {
            if (fresh.size() != entries.length) {
                return true;
            }
            for (int a = 0; a < entries.length; ++a) {
                if (!TextUtils.equals(fresh.get(a).name, entries[a].name)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Asks for a name, and makes a list of it.
     *
     * One field and no title: the box asks the one question that cannot be
     * answered anywhere else on a phone, and a title that only repeated the
     * name would be a line in the file saying nothing.
     */
    private static void showNewList(BaseFragment fragment, Activity activity,
            Theme.ResourcesProvider resourcesProvider, Rows rows,
            int currentAccount, long dialogId) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(getString(R.string.PurpleNewListTitle));

        final EditTextBoldCursor field = new EditTextBoldCursor(activity);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        field.setHintColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        field.setHintText(getString(R.string.PurpleNewListHint));
        field.setCursorSize(dp(20));
        field.setCursorWidth(1.5f);
        field.setBackgroundDrawable(null);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setPadding(dp(24), dp(4), dp(24), 0);
        builder.setView(field);

        // Save closes this box only. The one behind it stays where it was and
        // rebuilds, so the new list is a row with a tick in it rather than a
        // reason to go back through the chat menu.
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> createList(
                activity, rows, currentAccount, dialogId, field.getText().toString()));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /**
     * Writes the list, puts this chat in it, and rebuilds the rows.
     *
     * The chat goes in because a list made from this box is being made for the
     * chat the box is about; anything else would leave the user ticking the row
     * they had just created. Two writes rather than one, since the core creates
     * a list empty - and that is the right shape: a splice that also added a
     * member would be a second thing for it to refuse halfway through.
     */
    private static void createList(Activity activity, Rows rows,
            int currentAccount, long dialogId, String typed) {
        final String name = typed == null ? "" : typed.trim();
        final String error = PurpleWriter.addList(name, "new list");
        if (error != null) {
            // The core's own words - "a list needs a name", "a list name cannot
            // start with '*'", "there is already a list called 'x'" - because
            // each one names the thing to fix, which a generic failure does not.
            Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
            return;
        }
        final PurpleCore.ListEntry created =
                find(PurpleListMenu.listsFor(currentAccount, dialogId), name);
        if (created != null
                && PurpleListMenu.toggle(currentAccount, dialogId, created, true) != null) {
            // The list exists and the chat is not in it, which the rebuilt rows
            // below will show honestly: an empty checkbox on a row that is there.
            Toast.makeText(activity, getString(R.string.PurpleListsFailed),
                    Toast.LENGTH_SHORT).show();
        }
        rows.refresh();
    }

    /** The list the file now calls {@code name}, or null when it does not. */
    private static PurpleCore.ListEntry find(List<PurpleCore.ListEntry> lists, String name) {
        for (int a = 0, n = lists.size(); a < n; ++a) {
            if (lists.get(a).name.equalsIgnoreCase(name)) {
                return lists.get(a);
            }
        }
        return null;
    }

    /**
     * How long an "until" decision lasts, in the spans the desktop offers.
     *
     * Four, and no free-form entry: this is a decision about the rest of the
     * afternoon, and a picker that asked for a time would be asking for more
     * precision than anybody has about that.
     */
    private static final int[] SPANS = { 30 * 60, 2 * 3600, 8 * 3600, 24 * 3600 };
    private static final int[] SPAN_NAMES = {
            R.string.PurpleSpan30Minutes,
            R.string.PurpleSpan2Hours,
            R.string.PurpleSpan8Hours,
            R.string.PurpleSpan24Hours,
    };

    /**
     * One "Show/Hide/Notify until..." row, which opens the spans.
     *
     * Two dialogs rather than the desktop's submenu, because an AlertDialog has
     * no submenus and fifteen flat rows would be worse than one extra tap.
     */
    private static void addUntilRow(BaseFragment fragment, Activity activity,
            LinearLayout layout, Theme.ResourcesProvider resourcesProvider,
            AlertDialog.Builder builder, int currentAccount, long dialogId,
            int kind, int label) {
        final TextCell cell = new TextCell(activity, resourcesProvider);
        cell.setText(getString(label), true);
        cell.setOnClickListener(v -> {
            // Dismissed first, the way the list rows are: the write behind the
            // span reloads and rebuilds the chat list underneath.
            builder.getDismissRunnable().run();
            showSpans(fragment, activity, resourcesProvider, currentAccount, dialogId, kind, label);
        });
        layout.addView(cell);
    }

    /** The four spans, as a second box titled with the decision being made. */
    private static void showSpans(BaseFragment fragment, Activity activity,
            Theme.ResourcesProvider resourcesProvider, int currentAccount, long dialogId,
            int kind, int label) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(getString(label));

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        builder.setView(layout);

        for (int a = 0; a < SPANS.length; ++a) {
            final int seconds = SPANS[a];
            final TextCell cell = new TextCell(activity, resourcesProvider);
            cell.setText(getString(SPAN_NAMES[a]), a < SPANS.length - 1);
            cell.setOnClickListener(v -> {
                builder.getDismissRunnable().run();
                if (!PurpleGate.setOverride(currentAccount, dialogId, kind, seconds)) {
                    Toast.makeText(activity,
                            getString(R.string.PurpleListsFailed),
                            Toast.LENGTH_SHORT).show();
                }
            });
            layout.addView(cell);
        }
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** What a running decision is called, for the row that cancels it. */
    private static String kindName(int kind) {
        switch (kind) {
        case PurpleCore.OVERRIDE_HIDE: return getString(R.string.PurpleUntilHide);
        case PurpleCore.OVERRIDE_NOTIFY: return getString(R.string.PurpleUntilNotify);
        default: return getString(R.string.PurpleUntilShow);
        }
    }

    /**
     * The list's own title, falling back to its TOML key.
     *
     * Same rule as the preset picker: a box offering "keep" above a preset
     * describing "Keep" is one thing with two names.
     */
    private static String titleOf(PurpleCore.ListEntry list) {
        return (list.title == null || list.title.length() == 0) ? list.name : list.title;
    }

    /** One line of note under the rows, matching the preset picker's. */
    private static TextView note(Activity activity, CharSequence text, int color) {
        final TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTextColor(color);
        view.setText(text);
        view.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
        view.setPadding(dp(24), dp(8), dp(24), 0);
        return view;
    }
}
