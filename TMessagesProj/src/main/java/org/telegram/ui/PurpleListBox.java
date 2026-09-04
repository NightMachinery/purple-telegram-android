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
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleListMenu;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCell;

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
        if (verdict != null) {
            layout.addView(note(activity, verdict,
                    Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider)));
        }

        for (int a = 0, n = lists.size(); a < n; ++a) {
            final PurpleCore.ListEntry list = lists.get(a);
            final CheckBoxCell cell = new CheckBoxCell(activity, 1, resourcesProvider);
            cell.setText(titleOf(list), null, list.member, a < n - 1);
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_ALL));
            cell.setOnClickListener(v -> {
                // Dismiss first, the way a menu item does: the write reloads
                // and rebuilds the chat list underneath, and a box left open
                // over that would be showing membership it no longer knows.
                builder.getDismissRunnable().run();
                final String error = PurpleListMenu.toggle(
                        currentAccount, dialogId, list, !list.member);
                if (error != null) {
                    // The file is unchanged and so is the running resolution;
                    // the detail is in the log, which is where a TOML problem
                    // belongs.
                    Toast.makeText(activity,
                            getString(R.string.PurpleListsFailed),
                            Toast.LENGTH_SHORT).show();
                }
            });
            layout.addView(cell);
        }

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
