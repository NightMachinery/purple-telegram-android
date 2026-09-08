/*
 * This is the source code of Purple Telegram for Android.
 *
 * The Work Mode settings screen. Everything the fork adds that is a setting
 * rather than a gesture lives here, and everything it writes goes through the
 * splice, so a hand-written settings.toml keeps its comments and its shape.
 *
 * Two things deliberately are not on this screen. PurpleDefaults values are
 * defaults, not settings - changing one would only disagree with the build.
 * And hide_archive_p is per preset, so it belongs in the file next to the
 * preset it qualifies rather than as one switch for all of them.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleDevice;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleLastSeen;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.messenger.purple.PurpleSync;
import org.telegram.messenger.purple.PurpleSyncOffer;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PurpleSettingsActivity extends UniversalFragment
        implements NotificationCenter.NotificationCenterDelegate {

    private static final int ROW_PRESET = 1;
    private static final int ROW_SCHEDULE = 2;
    private static final int ROW_PREMIUM = 3;
    private static final int ROW_SUGGESTIONS = 4;
    private static final int ROW_EDIT = 5;
    private static final int ROW_SEND = 6;
    private static final int ROW_CHECK = 7;
    private static final int ROW_IMPORT = 8;
    private static final int ROW_SHARE = 9;
    private static final int ROW_PATH = 10;
    private static final int ROW_NOTIFICATIONS = 11;
    private static final int ROW_FOCUS = 12;
    private static final int ROW_DEVICE = 13;
    private static final int ROW_AUTOSEND = 14;
    private static final int ROW_LAST_SEEN_REASONS = 15;
    private static final int ROW_LAST_SEEN_TRADE = 16;

    /**
     * Where the trade log's rows start numbering.
     *
     * Far above the fixed rows so a log of any length can never collide with
     * one, and read-only: onClick has no case for them on purpose. A row here
     * is a record of something that already happened, and there is nothing to
     * do to it.
     */
    private static final int ROW_TRADE_FIRST = 1000;

    /**
     * The file picker's request code. Its result arrives at
     * onActivityResultFragment, which LaunchActivity delivers only to the
     * fragment on top - which is why the picker is started from here and never
     * from a sheet floating over this screen.
     */
    private static final int REQUEST_IMPORT = 4711;

    private boolean checkingSaved;


    // Every reload the gate does ends in this signal, whichever account-wide
    // thing caused it - the preset picker, a schedule boundary, a peek ending,
    // the watcher noticing that settings.toml changed on disk. Rebuilding on it
    // is what keeps this screen agreeing with the file when the change did not
    // come from a row on it.
    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogFiltersUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogFiltersUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogFiltersUpdated && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PurpleSettingsTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // Read once per rebuild rather than remembered in a field. Every write
        // below ends in a reload, and re-running this is what makes a refused
        // write leave the switch exactly where the file is with no extra code.
        final PurpleCore.Loaded state = PurpleGate.state();

        items.add(UItem.asHeader(getString(R.string.PurpleWorkMode)));
        items.add(UItem.asButton(ROW_PRESET, getString(R.string.PurpleWorkMode),
                state == null || state.normal
                        ? getString(R.string.PurplePresetNormal)
                        : state.title));
        items.add(UItem.asButton(ROW_SCHEDULE, getString(R.string.PurpleScheduleRow),
                PurpleScheduleActivity.summary(state)));

        final UItem premium = UItem.asCheck(ROW_PREMIUM, getString(R.string.PurplePremiumLocal));
        // From the file, never from a preference: this client is the only thing
        // enforcing those gates, and a second store for the same answer is a
        // second thing that can disagree with settings.toml.
        premium.checked = (state == null || state.premium);
        items.add(premium);
        items.add(UItem.asShadow(getString(R.string.PurplePremiumLocalInfo)));

        final UItem suggestions =
                UItem.asCheck(ROW_SUGGESTIONS, getString(R.string.PurpleHideSuggestions));
        suggestions.checked = (state == null || state.hideInvisibleSuggestions);
        items.add(suggestions);
        items.add(UItem.asShadow(getString(R.string.PurpleHideSuggestionsInfo)));

        final UItem focus = UItem.asCheck(ROW_FOCUS, getString(R.string.PurpleFollowDnd));
        // The parser's answer rather than the raw key: it turns focus sync off
        // itself when enter_preset names no preset that exists, so a switch
        // that showed the key would sit on over a file that does nothing.
        focus.checked = (state != null && state.focusSyncEnabled);
        items.add(focus);
        items.add(UItem.asShadow(focusShadow(state)));

        // Not part of a preset, for the same reason [peek] and [recent] are not:
        // this is a decision about how a status line reads, not about what gets
        // through. Two switches rather than one because they are two questions -
        // the explanation is drawn either way, and only the offer under it needs
        // the privacy call.
        items.add(UItem.asHeader(getString(R.string.PurpleLastSeenHeader)));
        final UItem reasons =
                UItem.asCheck(ROW_LAST_SEEN_REASONS, getString(R.string.PurpleLastSeenReasonsRow));
        reasons.checked = (state == null || state.lastSeenReasons);
        items.add(reasons);
        final UItem trade =
                UItem.asCheck(ROW_LAST_SEEN_TRADE, getString(R.string.PurpleLastSeenTradeRow));
        trade.checked = (state == null || state.lastSeenTrade);
        items.add(trade);
        items.add(UItem.asShadow(getString(R.string.PurpleLastSeenInfo)));

        // The log. Read out of state.toml on every rebuild rather than kept in a
        // field, exactly like the switches above: a trade made from a chat
        // header while this screen was in the back stack has to show up when it
        // comes forward again.
        items.add(UItem.asHeader(getString(R.string.PurpleTradesHeader)));
        final List<PurpleCore.Trade> trades = PurpleLastSeen.trades();
        if (trades.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.PurpleTradesEmpty)));
        } else {
            for (int i = 0; i < trades.size(); ++i) {
                final PurpleCore.Trade entry = trades.get(i);
                items.add(UItem.asButton(ROW_TRADE_FIRST + i, tradeName(entry.peer),
                        tradeValue(entry)));
            }
            items.add(UItem.asShadow(getString(R.string.PurpleTradesInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.PurpleSettingsFileHeader)));
        items.add(UItem.asButton(ROW_EDIT, getString(R.string.PurpleEditSettings)));
        items.add(UItem.asButton(ROW_SEND, getString(R.string.PurpleSendToSaved)));
        items.add(UItem.asButton(ROW_CHECK, getString(R.string.PurpleCheckSaved)));
        items.add(UItem.asButton(ROW_IMPORT, getString(R.string.PurpleImportFromFile)));
        items.add(UItem.asButton(ROW_SHARE, getString(R.string.PurpleShareFile)));
        items.add(UItem.asButton(ROW_PATH, getString(R.string.PurpleFilePath),
                PurpleSettings.settingsFile().getAbsolutePath()));
        items.add(UItem.asShadow(fileStatus(state) + "\n"
                + getString(R.string.PurpleFilePathInfo)));

        // Its own block under the file rather than a line beside the Send
        // button, because it is a standing decision and the button is one
        // action: turning it on means every later save posts a document, which
        // is a different kind of thing to agree to than pressing Send once.
        final UItem autoSend =
                UItem.asCheck(ROW_AUTOSEND, getString(R.string.PurpleSendAfterSave));
        autoSend.checked = (state != null && state.sendAfterSave);
        items.add(autoSend);
        items.add(UItem.asShadow(getString(R.string.PurpleSendAfterSaveInfo)));

        // Under the file rather than under Work Mode: the label is a line in
        // settings.toml like any other, and the id beside it is the string a
        // ruleset in that file names to mean this phone.
        items.add(UItem.asButton(ROW_DEVICE, getString(R.string.PurpleThisDevice),
                deviceValue(state)));
        items.add(UItem.asShadow(getString(R.string.PurpleThisDeviceInfo)));

        items.add(UItem.asButton(ROW_NOTIFICATIONS, getString(R.string.PurpleNotificationsRow)));
        items.add(UItem.asShadow(getString(R.string.PurpleNotificationsInfo)));
    }

    /**
     * Who a trade was with.
     *
     * The bare id when the app has never heard of the user, which is the honest
     * answer rather than a blank: state.toml outlives a logout, and a record
     * whose person this install cannot name is still a record of a trade.
     */
    private CharSequence tradeName(long peer) {
        final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(peer);
        return (user == null) ? String.valueOf(peer) : UserObject.getUserName(user);
    }

    /**
     * What one trade read, and how long ago it read it.
     *
     * A trade that read nothing is still shown, and says so: the exposure
     * happened either way, and hiding the ones that came back empty would make
     * the log say the trade always works.
     */
    private static CharSequence tradeValue(PurpleCore.Trade trade) {
        final CharSequence what = (trade.wasOnlineUnix > 0)
                ? LocaleController.formatDateOnline(trade.wasOnlineUnix, null)
                : getString(R.string.PurpleTradeRowNothing);
        return formatString(R.string.PurpleTradeRowValue, what,
                PurpleLastSeen.ago(trade.readAtUnix));
    }

    /**
     * What this device is called, and what it is.
     *
     * Both, because they answer different questions: the label is what a person
     * reads on the ruleset rows, and the id is what has to be typed into the
     * file on the OTHER machine to aim a ruleset at this one. An unlabelled
     * device shows as itself, which is also what the core does with it.
     */
    private static CharSequence deviceValue(PurpleCore.Loaded state) {
        final String label = PurpleDevice.labelFor(state, PurpleDevice.id());
        return (label == null)
                ? PurpleDevice.id()
                : (label + " · " + PurpleDevice.id());
    }

    /**
     * What the file is doing right now, in one line.
     *
     * Four states, and the order matters: an unreadable file that left the last
     * good copy running is a different thing to say than a parse error, and a
     * warning count only means anything about a file that was read.
     */
    private static CharSequence fileStatus(PurpleCore.Loaded state) {
        if (state == null) {
            return getString(R.string.PurpleFileMissing);
        }
        if (PurpleGate.usedLastGood()) {
            return getString(R.string.PurpleSettingsFileLastGood);
        }
        if (!state.ok && !TextUtils.isEmpty(state.error)) {
            return formatString(R.string.PurpleSettingsFileError, state.error);
        }
        final int warnings = state.warnings.size();
        if (warnings == 0) {
            return getString(R.string.PurpleSettingsFileOk);
        }
        return formatPluralString("PurpleSettingsFileWarnings", warnings);
    }

    /**
     * Which presets Do Not Disturb moves between, from the file.
     *
     * A file that names no enter_preset is the one case worth a sentence of its
     * own: the parser has already turned focus sync off over it, so a row that
     * only said "off" would leave the user flipping a switch that snaps back.
     */
    private static CharSequence focusShadow(PurpleCore.Loaded state) {
        final String enter = state == null ? "" : state.focusSyncEnter;
        if (TextUtils.isEmpty(enter)) {
            return getString(R.string.PurpleFollowDndUnset);
        }
        final String exit = state == null ? "" : state.focusSyncExit;
        return formatString(R.string.PurpleFollowDndInfo, enter, exitLabel(exit));
    }

    /**
     * What leaving a focus mode goes back to.
     *
     * "previous" is a keyword and not a preset name, so it is spelled out
     * rather than quoted; an empty exit_preset means Normal, the same
     * fall-through the core takes.
     */
    private static String exitLabel(String exit) {
        if (TextUtils.isEmpty(exit)) {
            return getString(R.string.PurplePresetNormal);
        }
        return "previous".equalsIgnoreCase(exit)
                ? getString(R.string.PurpleFollowDndPrevious)
                : exit;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
        case ROW_PRESET:
            PurplePresetPicker.show(this);
            break;
        case ROW_SCHEDULE:
            presentFragment(new PurpleScheduleActivity());
            break;
        case ROW_PREMIUM:
            write(PurpleWriter.setTableBool("premium", "enabled_p", !item.checked, "premium switch"));
            break;
        case ROW_SUGGESTIONS:
            write(PurpleWriter.setTableBool(
                    "suggestions", "hide_invisible_p", !item.checked, "suggestions switch"));
            break;
        case ROW_FOCUS:
            write(PurpleWriter.setTableBool(
                    "focus_sync", "enabled_p", !item.checked, "focus sync switch"));
            break;
        case ROW_EDIT:
            presentFragment(new PurpleSettingsEditorActivity());
            break;
        case ROW_SEND:
            sendToSaved();
            break;
        case ROW_AUTOSEND:
            write(PurpleWriter.setTableBool(
                    "sync", "send_after_save_p", !item.checked, "auto-send switch"));
            break;
        case ROW_LAST_SEEN_REASONS:
            write(PurpleWriter.setTableBool(
                    "last_seen", "reasons_p", !item.checked, "last seen reasons switch"));
            break;
        case ROW_LAST_SEEN_TRADE:
            write(PurpleWriter.setTableBool(
                    "last_seen", "trade_p", !item.checked, "last seen trade switch"));
            break;
        case ROW_CHECK:
            checkSaved();
            break;
        case ROW_IMPORT:
            startImportPicker();
            break;
        case ROW_SHARE:
            shareFile();
            break;
        case ROW_PATH:
            if (AndroidUtilities.addToClipboard(PurpleSettings.settingsFile().getAbsolutePath())
                    && BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this)
                        .createCopyBulletin(getString(R.string.TextCopied))
                        .show();
            }
            break;
        case ROW_DEVICE:
            nameDevice();
            break;
        case ROW_NOTIFICATIONS:
            presentFragment(new NotificationsSettingsActivity());
            break;
        default:
            break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /**
     * Redraws after a write, and says why when there was not one.
     *
     * Rebuilding is what puts a switch back where the file is: fillItems reads
     * the gate again, so a refused write needs no undo of its own.
     */
    private void write(String error) {
        if (error != null && BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this)
                    .createErrorBulletin(formatString(R.string.PurpleWriteFailed, error))
                    .show();
        }
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    /**
     * Names this device, into {@code [devices]}.
     *
     * The table is {@code "<id>" = "<label>"}, so the id is the KEY, and
     * {@code setTableString} writes a key it has to add bare. That works
     * because an id is letters, digits and a hyphen, all of which a bare TOML
     * key allows; the core reads a key the user quoted just the same.
     *
     * An empty name writes an empty label rather than removing the line, which
     * the core and this screen both read as no name at all.
     */
    private void nameDevice() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final PurpleCore.Loaded state = PurpleGate.state();
        final String known = PurpleDevice.labelFor(state, PurpleDevice.id());

        final EditTextBoldCursor field = new EditTextBoldCursor(activity);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setBackgroundDrawable(null);
        field.setSingleLine(true);
        field.setHint(getString(R.string.PurpleThisDevice));
        field.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        if (known != null) {
            field.setText(known);
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PurpleThisDeviceTitle));
        builder.setMessage(PurpleDevice.id());
        builder.setView(field);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> write(
                PurpleWriter.setTableString("devices", PurpleDevice.id(),
                        field.getText().toString().trim(), "device label")));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // ---- the file, in and out ------------------------------------------------

    /**
     * Puts a copy in Saved Messages, so the desktop and the next phone can pick
     * it up through the same offer this build already answers.
     *
     * The send itself is {@link PurpleSync}'s, shared with the switch below it:
     * the caption, the staged name and the refusal of a file that does not
     * parse are what the other client matches on, so there is one copy of them.
     * All that is left here is saying how it went.
     */
    private void sendToSaved() {
        final String refusal = PurpleSync.sendToSavedMessages(
                currentAccount, PurpleGate.settingsBytes());
        if (refusal != null) {
            error(refusal);
            return;
        }
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.contact_check, getString(R.string.PurpleSentToSaved))
                    .show();
        }
    }

    /** The launch offer, asked for on purpose instead of waiting for a launch. */
    private void checkSaved() {
        if (checkingSaved) {
            return;
        }
        checkingSaved = true;
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.info, getString(R.string.PurpleCheckingSaved))
                    .show();
        }
        PurpleSyncOffer.checkNow(this, currentAccount, offered -> {
            checkingSaved = false;
            if (!offered && BulletinFactory.canShowBulletin(this)) {
                // Silence would read as a broken button, so the "no" is said
                // out loud - which is the whole difference from the launch
                // check, where nobody asked.
                BulletinFactory.of(this)
                        .createSimpleBulletin(R.raw.info, getString(R.string.PurpleNothingNewer))
                        .show();
            }
        });
    }

    private void startImportPicker() {
        try {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Exception e) {
            FileLog.e(e);
            error(getString(R.string.PurpleImportFailed));
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT || data == null || data.getData() == null) {
            return;
        }
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            String path = AndroidUtilities.getPath(data.getData());
            if (path == null || path.startsWith("content://")) {
                // Not a real path - a document provider's handle. Copying it
                // out is the only way to hand the parser a file.
                path = MediaController.copyFileToCache(data.getData(), "toml");
            }
            if (path == null) {
                error(getString(R.string.PurpleImportFailed));
                return;
            }
            // importFrom validates, shows the confirmation, keeps a backup and
            // refuses a file that does not parse - all of which this screen
            // would otherwise be repeating. The date names the file in the
            // question it asks, and a file off the disk has only now.
            PurpleSettings.importFrom(activity, new File(path),
                    (int) (System.currentTimeMillis() / 1000L));
        } catch (Exception e) {
            FileLog.e(e);
            error(getString(R.string.PurpleImportFailed));
        }
    }

    /** Hands the file to whatever else on the phone can take a text document. */
    private void shareFile() {
        final Activity activity = getParentActivity();
        final File source = PurpleSettings.settingsFile();
        if (activity == null) {
            return;
        }
        if (!source.exists()) {
            error(getString(R.string.PurpleFileMissing));
            return;
        }
        try {
            final Uri uri = (Build.VERSION.SDK_INT >= 24)
                    ? FileProvider.getUriForFile(
                            activity, ApplicationLoader.getApplicationId() + ".provider", source)
                    : Uri.fromFile(source);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            if (Build.VERSION.SDK_INT >= 24) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            activity.startActivity(Intent.createChooser(
                    intent, getString(R.string.PurpleShareFile)));
        } catch (Exception e) {
            FileLog.e(e);
            error(getString(R.string.PurpleImportFailed));
        }
    }

    private void error(CharSequence text) {
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createErrorBulletin(text).show();
        }
    }
}
