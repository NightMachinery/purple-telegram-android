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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleDiagLog;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleListMenu;
import org.telegram.messenger.purple.PurpleListSource;
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
     * Every live list is offered, including one that matches by {@code kinds}:
     * adding a chat to a rule-based list writes an explicit member id, which is
     * how you pull one chat out of a rule that would otherwise sweep it up
     * somewhere else.
     *
     * A non-Normal resolution can keep filtering from saved list definitions
     * while the primary file has no live lists. The box still explains the
     * chat and offers temporary overrides and explicit New list creation;
     * saved definitions never become editable checkbox rows.
     *
     * When no write session can be had, the rows are still shown, read-only,
     * from the file the running resolution uses, under a line saying why they
     * cannot be ticked and a button for what fixes it. The box used to read
     * its rows only through the session, so a refused one left it with no
     * rows and a New list row that did nothing, with nothing said anywhere.
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
        final PurpleCore.Loaded state = PurpleGate.state();
        final boolean filtering = PurpleGate.filtering(currentAccount);
        final Source source = Source.read(fragment, currentAccount, dialogId);
        final PurpleWriter.Session writer = source.writer;
        final List<PurpleCore.ListEntry> lists = source.lists;
        source.log(currentAccount, "box_opened");
        if (lists.isEmpty() && !filtering && source.reason == null) {
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
        final String verdict = listVerdict(writer, lists, currentAccount, dialogId);
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
        final LinearLayout notice = new LinearLayout(activity);
        notice.setOrientation(LinearLayout.VERTICAL);
        layout.addView(notice);

        final LinearLayout listRows = new LinearLayout(activity);
        listRows.setOrientation(LinearLayout.VERTICAL);
        layout.addView(listRows);

        final Rows rows = new Rows(activity, resourcesProvider, listRows, notice, verdictView,
                builder, fragment, currentAccount, dialogId, writer);
        rows.reason = source.reason;
        rows.membership = source.membership;
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
        if (filtering) {
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
        PurpleWriterUI.show(fragment, builder.create(), writer);
    }

    /**
     * The rows for one chat and how they were read: through a write session,
     * so they can be ticked, or read-only with the code of the refusal. A
     * read-only source may have the list names without their membership,
     * when the running resolution used a file this read cannot reproduce.
     */
    private static final class Source {
        final PurpleWriter.Session writer;
        final List<PurpleCore.ListEntry> lists;
        final String reason;
        final String origin;
        final boolean membership;

        private Source(PurpleWriter.Session writer, List<PurpleCore.ListEntry> lists, String reason, String origin,
                boolean membership) {
            this.writer = writer; this.lists = lists; this.reason = reason; this.origin = origin;
            this.membership = membership;
        }

        static Source read(BaseFragment fragment, int account, long dialogId) {
            final long peer = PurpleGate.bareIdOf(account, dialogId);
            final PurpleWriter.Capture capture = PurpleWriterUI.captureResult(fragment, false);
            String reason = capture.reason;
            if (capture.session != null) {
                final List<PurpleCore.ListEntry> lists = capture.session.listsOrNull(peer);
                if (lists != null) return new Source(capture.session, lists, null, PurpleListSource.ROUTED, true);
                reason = "lists_unreadable";
            }
            final PurpleListSource.Lists fallback = PurpleListSource.read(account, peer, PurpleGate.usedLastGood());
            return new Source(null, fallback.entries, reason, fallback.source, fallback.membership());
        }

        void log(int account, String event) {
            PurpleDiagLog.event("lists", event, PurpleDiagLog.slot(account), PurpleDiagLog.flag("editable", writer != null),
                    reason == null ? null : PurpleDiagLog.reason(reason), PurpleDiagLog.count("lists", lists.size()),
                    PurpleDiagLog.code("source", origin), PurpleDiagLog.mode(writer == null ? -1 : writer.mode));
        }
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
        private final LinearLayout notice;
        private final TextView verdictView;
        private final AlertDialog.Builder builder;
        private final int currentAccount;
        private final BaseFragment fragment;
        private PurpleWriter.Session writer;
        /** Why the rows are read-only, or null when {@link #writer} can tick them. */
        private String reason;
        /** Whether the rows' checkmarks say which lists hold the chat, or only the names are known. */
        private boolean membership = true;
        private final long dialogId;

        private PurpleCore.ListEntry[] entries = new PurpleCore.ListEntry[0];
        private CheckBoxCell[] cells = new CheckBoxCell[0];

        Rows(Activity activity, Theme.ResourcesProvider resourcesProvider,
                LinearLayout container, LinearLayout notice, TextView verdictView, AlertDialog.Builder builder,
                BaseFragment fragment, int currentAccount, long dialogId, PurpleWriter.Session writer) {
            this.fragment = fragment; this.writer = writer;
            this.activity = activity;
            this.resourcesProvider = resourcesProvider;
            this.container = container;
            this.notice = notice;
            this.verdictView = verdictView;
            this.builder = builder;
            this.currentAccount = currentAccount;
            this.dialogId = dialogId;
        }

        /** The reason line and its button, or nothing while the rows can be ticked. */
        private void buildNotice() {
            notice.removeAllViews();
            if (reason == null) return;
            notice.addView(note(activity, formatString(R.string.PurpleListsReadOnly, PurpleWriterUI.reasonText(reason)),
                    Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)));
            if (!membership) {
                // The names are app-wide in every mode, but the checkmarks
                // would come from a file the chat list is not using.
                notice.addView(note(activity, getString(R.string.PurpleListsNoMembership),
                        Theme.getColor(Theme.key_text_RedRegular, resourcesProvider)));
            }
            final int remedy = PurpleWriterUI.remedy(reason);
            final TextCell action = new TextCell(activity, resourcesProvider);
            action.setText(PurpleWriterUI.remedyLabel(remedy), true);
            action.setColors(Theme.key_windowBackgroundWhiteBlueText4, Theme.key_windowBackgroundWhiteBlueText4);
            action.setOnClickListener(v -> {
                builder.getDismissRunnable().run();
                AndroidUtilities.runOnUIThread(() -> PurpleWriterUI.runRemedy(fragment, remedy,
                        () -> show(fragment, currentAccount, dialogId)));
            });
            notice.addView(action);
        }

        /** One cell per list, replacing whatever was there. */
        void build(List<PurpleCore.ListEntry> lists) {
            buildNotice();
            container.removeAllViews();
            final int count = lists.size();
            final PurpleWriter.Session captured = writer;
            final String readOnly = captured == null ? reason : null;
            entries = lists.toArray(new PurpleCore.ListEntry[0]);
            cells = new CheckBoxCell[count];
            for (int a = 0; a < count; ++a) {
                final CheckBoxCell cell = new CheckBoxCell(activity, 1, resourcesProvider);
                cells[a] = cell;
                final PurpleCore.ListEntry entry = entries[a];
                final String own = PurpleSettings.accountLabel(currentAccount);
                final String global = captured == null ? "" : PurpleAccountSettingsRows.globalLabel(captured.globalKey);
                final String subtitle = captured == null ? getString(membership ? R.string.PurpleListReadOnlyRow
                        : R.string.PurpleListUnknownRow)
                        : entry.ownMembers || captured.key.equals(captured.globalKey)
                        ? formatString(R.string.PurpleListOwn, own) : formatString(R.string.PurpleListSame, global);
                String title = titleOf(entry);
                android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(title + "\n" + subtitle);
                label.setSpan(new android.text.style.RelativeSizeSpan(0.8125f), title.length() + 1, label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                label.setSpan(new android.text.style.ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider)),
                        title.length() + 1, label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cell.setMultiline(true);
                cell.getTextView().setLines(2);
                cell.setText(label, null, membership && entry.member, a < count - 1);
                if (entry.deleteOwn) cell.setOnLongClickListener(v -> {
                    if (captured == null || !captured.current()) return true;
                    AlertDialog.Builder confirmation = new AlertDialog.Builder(activity, resourcesProvider);
                    confirmation.setTitle(formatString(R.string.PurpleListDeleteOwn, own, titleOf(entry)));
                    confirmation.setMessage(formatString(R.string.PurpleListDeleteOwnConfirm, own, titleOf(entry), global));
                    confirmation.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                        String error = captured.deleteOwn(entry.name);
                        if (error != null) Toast.makeText(activity, getString(R.string.PurpleListsFailed), Toast.LENGTH_LONG).show();
                        AndroidUtilities.runOnUIThread(() -> { if (captured.routeCurrent()) show(fragment, currentAccount, dialogId); });
                    });
                    confirmation.setNegativeButton(getString(R.string.Cancel), (dialog, which) ->
                            AndroidUtilities.runOnUIThread(() -> { if (captured.routeCurrent()) show(fragment, currentAccount, dialogId); }));
                    PurpleWriterUI.show(fragment, confirmation.create(), captured);
                    return true;
                });
                cell.setPadding(dp(4), dp(8), dp(4), dp(8));
                cell.setBackground(Theme.createSelectorDrawable(
                        Theme.getColor(Theme.key_listSelector, resourcesProvider),
                        Theme.RIPPLE_MASK_ALL));
                if (readOnly != null) {
                    // Shown so the user can see which lists hold the chat;
                    // a tap says why it cannot change rather than nothing.
                    cell.setAlpha(0.6f);
                    cell.setOnClickListener(v -> Toast.makeText(activity, formatString(R.string.PurpleListsReadOnly,
                            PurpleWriterUI.reasonText(readOnly)), Toast.LENGTH_LONG).show());
                    container.addView(cell);
                    continue;
                }
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
                            captured, currentAccount, dialogId, entry, wanted);
                    if (error != null) {
                        // The file is unchanged and so is the running
                        // resolution; the detail is in the log, which is where a
                        // TOML problem belongs. The rows are read again so the
                        // next tap uses a fresh session, or says why there is none.
                        PurpleDiagLog.event("lists", "tick_failed", PurpleDiagLog.slot(currentAccount),
                                PurpleDiagLog.flag("session_current", captured != null && captured.current()));
                        Toast.makeText(activity,
                                getString(R.string.PurpleListsFailed),
                                Toast.LENGTH_SHORT).show();
                        refresh();
                        return;
                    }
                    if (captured != null) {
                        String notice = captured.mode == 1 ? formatString(wanted ? R.string.PurpleListFollowAdded : R.string.PurpleListFollowRemoved,
                                global, titleOf(entry), own) : captured.mode == 2
                                ? formatString(R.string.PurpleListStockEdited, own, titleOf(entry))
                                : !captured.appWide && !entry.ownMembers ? formatString(R.string.PurpleListForked, titleOf(entry), own, global) : null;
                        if (notice != null) Toast.makeText(activity, notice, Toast.LENGTH_LONG).show();
                    }
                    refresh();
                });
                container.addView(cell);
            }
        }

        /**
         * Puts live membership and the running verdict back after a tick or a
         * new list. Rechecks the current resolution so filtering can retain a
         * box with zero editable rows; this is not a general reload observer.
         */
        void refresh() {
            final Source source = Source.read(fragment, currentAccount, dialogId);
            writer = source.writer;
            reason = source.reason;
            membership = source.membership;
            PurpleWriterUI.bind(writer, fragment);
            if (reason != null) source.log(currentAccount, "box_refreshed");
            final List<PurpleCore.ListEntry> fresh = source.lists;
            final PurpleCore.Loaded state = PurpleGate.state();
            final boolean filtering = PurpleGate.filtering(currentAccount);
            if (fresh.isEmpty() && !filtering && reason == null) {
                builder.getDismissRunnable().run();
                return;
            }
            build(fresh);
            if (verdictView != null) {
                // Only when there is still one to show. It goes away with the
                // preset and not with a list edit, so a null here means the
                // answer is unchanged rather than empty.
                final String verdict = listVerdict(writer, fresh, currentAccount, dialogId);
                if (verdict != null) {
                    verdictView.setText(verdict);
                }
            }
        }

        /** Whether the file's lists are no longer the ones these cells were built from. */

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
        // A session of its own, opened at the tap: the box's may have gone
        // stale while it was open, and a list is app-wide structure, so the
        // box's account-routed session was never the one this needed. When
        // none can be had, the reason is the answer to the tap.
        final PurpleWriter.Capture capture = PurpleWriterUI.captureResult(fragment, true);
        final PurpleWriter.Session writer = capture.session;
        if (writer == null) {
            PurpleDiagLog.event("lists", "new_list_refused", PurpleDiagLog.slot(currentAccount),
                    PurpleDiagLog.reason(capture.reason));
            explainBlocked(fragment, activity, resourcesProvider, currentAccount, dialogId, capture.reason);
            return;
        }
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

        // A fragment holds one dialog: showing this one dismissed the lists
        // box behind it (seen on the emulator - the box was gone after Save).
        // So the box is opened again afterwards, on Save and on Cancel alike,
        // and comes back rebuilt from the file with the new list ticked, which
        // is what the user was looking at and what they came here to change.
        final Runnable reopen = () -> AndroidUtilities.runOnUIThread(
                () -> { if (writer.routeCurrent()) show(fragment, currentAccount, dialogId); });
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            createList(fragment, writer, activity, rows, currentAccount, dialogId, field.getText().toString());
            reopen.run();
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> reopen.run());
        PurpleWriterUI.show(fragment, builder.create(), writer);
    }

    /**
     * Says why a new list cannot be made, with the button for what fixes it.
     * Showing it closes the lists box, as the name box does, so Close opens
     * the lists box again.
     */
    private static void explainBlocked(BaseFragment fragment, Activity activity,
            Theme.ResourcesProvider resourcesProvider, int currentAccount, long dialogId, String reason) {
        final Runnable reopen = () -> AndroidUtilities.runOnUIThread(() -> show(fragment, currentAccount, dialogId));
        final int remedy = PurpleWriterUI.remedy(reason);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
        builder.setTitle(getString(R.string.PurpleNewListTitle));
        builder.setMessage(formatString(R.string.PurpleListsNewBlocked, PurpleWriterUI.reasonText(reason)));
        builder.setPositiveButton(PurpleWriterUI.remedyLabel(remedy), (dialog, which) -> AndroidUtilities.runOnUIThread(
                () -> PurpleWriterUI.runRemedy(fragment, remedy, () -> show(fragment, currentAccount, dialogId))));
        builder.setNegativeButton(getString(R.string.Close), (dialog, which) -> reopen.run());
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
    private static void createList(BaseFragment fragment, PurpleWriter.Session writer, Activity activity, Rows rows,
            int currentAccount, long dialogId, String typed) {
        final String name = typed == null ? "" : typed.trim();
        final String error = PurpleWriter.addList(writer, name, "new list");
        if (error != null) {
            // The core's own words - "a list needs a name", "a list name cannot
            // start with '*'", "there is already a list called 'x'" - because
            // each one names the thing to fix, which a generic failure does not.
            Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
            return;
        }
        if (writer == null || !writer.forAccount(currentAccount) || !writer.sameRoute()) return;
        final PurpleCore.ListEntry created =
                find(PurpleListMenu.listsFor(currentAccount, dialogId), name);
        if (created != null
                && PurpleListMenu.toggle(PurpleWriterUI.capture(fragment, false), currentAccount, dialogId, created, true) != null) {
            // The list exists and the chat is not in it, which the rebuilt rows
            // below will show honestly: an empty checkbox on a row that is there.
            Toast.makeText(activity, getString(R.string.PurpleListsFailed),
                    Toast.LENGTH_SHORT).show();
        }
        Toast.makeText(activity, getString(R.string.PurpleListStructureAppWide), Toast.LENGTH_LONG).show();
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

    private static String listVerdict(PurpleWriter.Session writer, List<PurpleCore.ListEntry> lists, int account, long dialogId) {
        String verdict = PurpleListMenu.verdictLine(account, dialogId);
        if (writer == null || writer.mode == 2 || writer.key.equals(writer.globalKey)) return verdict;
        String global = PurpleAccountSettingsRows.globalLabel(writer.globalKey);
        String summary;
        if (writer.mode == 1) summary = formatString(R.string.PurpleListSame, global);
        else {
            int own = 0;
            for (PurpleCore.ListEntry list : lists) if (list.ownMembers) ++own;
            summary = formatString(R.string.PurpleListOwnSummary, own, PurpleSettings.accountLabel(account), global);
        }
        return verdict == null ? summary : summary + "\n\n" + verdict;
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
