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
import android.view.View;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.messenger.purple.PurpleSyncOffer;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

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

        items.add(UItem.asButton(ROW_NOTIFICATIONS, getString(R.string.PurpleNotificationsRow)));
        items.add(UItem.asShadow(getString(R.string.PurpleNotificationsInfo)));
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
        case ROW_EDIT:
            presentFragment(new PurpleSettingsEditorActivity());
            break;
        case ROW_SEND:
            sendToSaved();
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

    // ---- the file, in and out ------------------------------------------------

    /**
     * Puts a copy in Saved Messages, so the desktop and the next phone can pick
     * it up through the same offer this build already answers.
     *
     * Refused when the file does not parse, which is the desktop's rule too:
     * sending a broken file is how you break the machine you meant to sync.
     */
    private void sendToSaved() {
        final Activity activity = getParentActivity();
        final File source = PurpleSettings.settingsFile();
        if (activity == null) {
            return;
        }
        if (!source.exists()) {
            error(getString(R.string.PurpleFileMissing));
            return;
        }
        final byte[] bytes = read(source);
        if (bytes == null) {
            error(getString(R.string.PurpleImportFailed));
            return;
        }
        final PurpleCore.ParseResult parsed;
        try {
            parsed = PurpleCore.parse(bytes);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            error(getString(R.string.PurpleCoreUnavailable));
            return;
        }
        if (!parsed.ok) {
            error(getString(R.string.PurpleSendRefused));
            return;
        }
        // Staged under the exact name, because the name is what the launch
        // offer and the chat's own menu item match on. Sending the real file
        // would work too, but it would hand an uploader a path the gate is
        // writing to.
        final File staged = new File(
                FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE),
                PurpleSettings.FILE_NAME);
        if (!writeBytes(staged, bytes)) {
            error(getString(R.string.PurpleImportFailed));
            return;
        }
        final String caption = "Purple settings · schema v" + parsed.version + " · "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())
                + " · Android";
        SendMessagesHelper.prepareSendingDocument(
                AccountInstance.getInstance(currentAccount),
                staged.getAbsolutePath(),
                staged.getAbsolutePath(),
                null,
                caption,
                "text/plain",
                UserConfig.getInstance(currentAccount).getClientUserId(),
                null,
                null,
                null,
                null,
                null,
                true,
                0,
                null,
                null,
                false);
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

    private static byte[] read(File file) {
        try {
            final InputStream in = new FileInputStream(file);
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

    private static boolean writeBytes(File file, byte[] bytes) {
        try {
            final OutputStream out = new FileOutputStream(file);
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
