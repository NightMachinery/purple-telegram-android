/*
 * This is the source code of Purple Telegram for Android.
 *
 * The launch-time offer to import a newer settings.toml out of Saved Messages -
 * phase 2 of docs/purple/sync.md. Nothing here writes settings: it notices that
 * a fresher file is sitting in the chat and puts one dismissible line on the
 * screen about it, and the Import button hands the bytes to the same
 * PurpleSettings.importFrom the chat's own menu item uses.
 *
 * The rule that stops it nagging is "one offer per message, ever". The id of
 * the newest settings.toml we have already had an opinion about is written to
 * the account's preferences *before* the offer is drawn, so a machine you never
 * sync sees each posted file exactly once and then never again - and a crash
 * between drawing the line and answering it cannot turn into a loop.
 */

package org.telegram.messenger.purple;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public final class PurpleSyncOffer {

    /** Newest settings.toml message this account has already been offered. */
    private static final String OFFERED_KEY = "purple_sync_offered_id";

    /** How many of the chat's newest documents we are willing to look through. */
    private static final int SEARCH_LIMIT = 20;

    /** How long a tapped Import waits for the 64 KB to arrive before giving up. */
    private static final long DOWNLOAD_TIMEOUT = 60 * 1000L;

    /** Accounts already looked at in this process. */
    private static final Set<Integer> checked = new HashSet<>();

    private PurpleSyncOffer() {
    }

    /**
     * Looks Saved Messages up once, on the first chat list this process builds.
     *
     * Costs one small request per account per process and nothing at all after
     * that, which is why it is safe to hang off the chat list rather than off
     * some deliberate "check for settings" action nobody would ever press.
     */
    public static void checkOnLaunch(BaseFragment fragment, int currentAccount) {
        if (fragment == null || currentAccount < 0) {
            return;
        }
        final Bundle arguments = fragment.getArguments();
        if (arguments != null && arguments.getBoolean("onlySelect", false)) {
            // A chat picker, not the chat list - the same fragment class serves
            // both, and an offer on top of a share sheet would be nonsense.
            return;
        }
        if (!UserConfig.getInstance(currentAccount).isClientActivated()) {
            return;
        }
        synchronized (checked) {
            if (!checked.add(currentAccount)) {
                return;
            }
        }
        search(currentAccount, PurpleSettings.FILE_NAME, first -> {
            if (first != null) {
                consider(fragment, currentAccount, first);
                return;
            }
            // Searching by filename leans on the server indexing document names,
            // which is what the shared-files search box relies on too. If that
            // ever stops being true the feature would silently never fire, so
            // the empty answer is worth one more request: no query at all, just
            // the document filter, and the newest few sorted out here.
            search(currentAccount, "", second -> consider(fragment, currentAccount, second));
        });
    }

    /** What a Saved Messages lookup came back with, or null. */
    private interface Found {
        void run(TLRPC.Message message);
    }

    private static void search(int account, String query, Found callback) {
        final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(account).getInputPeer(UserConfig.getInstance(account).getClientUserId());
        req.q = query;
        req.filter = new TLRPC.TL_inputMessagesFilterDocument();
        req.limit = SEARCH_LIMIT;
        if (req.peer == null) {
            callback.run(null);
            return;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                // Nothing about this is worth a dialog: the user did not ask for
                // it and the manual import is still there.
                FileLog.d("Purple: sync offer: Saved Messages search failed"
                        + (error != null ? " (" + error.text + ")" : "") + ".");
                callback.run(null);
                return;
            }
            callback.run(newest((TLRPC.messages_Messages) response));
        }));
    }

    /** The highest-id message in {@code res} that actually carries a settings.toml. */
    private static TLRPC.Message newest(TLRPC.messages_Messages res) {
        TLRPC.Message best = null;
        for (int a = 0, n = res.messages.size(); a < n; a++) {
            final TLRPC.Message message = res.messages.get(a);
            if (documentOf(message) == null) {
                continue;
            }
            if (best == null || message.id > best.id) {
                best = message;
            }
        }
        return best;
    }

    /**
     * The message's settings.toml, or null if it is carrying something else.
     *
     * Case-insensitive on the name, and size-capped, to agree with
     * {@code MessageObject.isPurpleSettings()} - the two have to say the same
     * thing about the same message or the offer would point at a document the
     * chat's own menu refuses to import.
     */
    private static TLRPC.Document documentOf(TLRPC.Message message) {
        if (message == null || message instanceof TLRPC.TL_messageService || message instanceof TLRPC.TL_messageEmpty) {
            return null;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(message);
        final TLRPC.Document document = media != null ? media.document : null;
        if (document == null || document.size > PurpleSettings.MAX_SIZE) {
            return null;
        }
        for (int a = 0, n = document.attributes.size(); a < n; a++) {
            final TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeFilename
                    && PurpleSettings.FILE_NAME.equalsIgnoreCase(((TLRPC.TL_documentAttributeFilename) attribute).file_name)) {
                return document;
            }
        }
        return null;
    }

    private static void consider(BaseFragment fragment, int account, TLRPC.Message message) {
        if (message == null) {
            FileLog.d("Purple: sync offer: no settings.toml in Saved Messages.");
            return;
        }
        // importFrom writes the bytes and records nothing else - it takes the
        // message date only to name it in the confirmation - so the file's own
        // mtime is the whole of what we know about how fresh the local copy is.
        // That is enough: the write happens after the message was sent, so an
        // imported file always stamps later than the message it came from.
        final File local = PurpleSettings.settingsFile();
        final long localStamp = local.exists() ? local.lastModified() / 1000L : 0L;
        final SharedPreferences prefs = MessagesController.getMainSettings(account);
        final int lastOffered = prefs.getInt(OFFERED_KEY, 0);

        final String head = "Purple: sync offer: newest settings.toml is msg " + message.id
                + " (date " + message.date + "), local stamp " + localStamp
                + ", last offered " + lastOffered + " -> ";

        if (message.id <= lastOffered) {
            FileLog.d(head + "skipped (already offered).");
            return;
        }
        if (message.date <= localStamp) {
            // Older than what is already on disk, so not worth offering now -
            // and it will not become worth offering later either, which is why
            // the id moves on rather than being left to come back tomorrow.
            prefs.edit().putInt(OFFERED_KEY, message.id).apply();
            FileLog.d(head + "skipped (older than the local file).");
            return;
        }
        if (!BulletinFactory.canShowBulletin(fragment)) {
            FileLog.d(head + "skipped (no window to show it in).");
            return;
        }

        // Written before the line is drawn. That covers the crash, and it also
        // covers both ways of saying no - swiping the bulletin away and letting
        // it time out - neither of which then needs a listener of its own.
        prefs.edit().putInt(OFFERED_KEY, message.id).apply();
        FileLog.d(head + "offering.");

        BulletinFactory.of(fragment)
                .createSimpleBulletin(
                        R.raw.info,
                        LocaleController.getString(R.string.PurpleSyncOffer),
                        LocaleController.getString(R.string.Import),
                        Bulletin.DURATION_PROLONG,
                        () -> startImport(fragment, account, message))
                .show();
    }

    private static void startImport(BaseFragment fragment, int account, TLRPC.Message message) {
        // Again after the choice. The id is already down from the line above;
        // repeating it here keeps the record beside the decision it records,
        // so neither half can be moved without the other being noticed.
        MessagesController.getMainSettings(account).edit().putInt(OFFERED_KEY, message.id).apply();

        final Activity activity = fragment.getParentActivity();
        final TLRPC.Document document = documentOf(message);
        if (activity == null || document == null) {
            return;
        }
        final File cached = FileLoader.getInstance(account).getPathToMessage(message);
        if (cached != null && cached.exists()) {
            PurpleSettings.importFrom(activity, cached, message.date);
            return;
        }
        // Not downloaded yet. 64 KB at most, over the connection the search has
        // just used, so waiting for it here is friendlier than the chat menu's
        // "tap again once it has arrived".
        new Download(fragment, account, message).start(document);
    }

    /** Waits for one settings.toml to land, then hands it to the import path. */
    private static final class Download implements NotificationCenter.NotificationCenterDelegate {

        private final BaseFragment fragment;
        private final int account;
        private final TLRPC.Message message;
        private final Runnable giveUp;
        private String fileName;
        private boolean finished;

        Download(BaseFragment fragment, int account, TLRPC.Message message) {
            this.fragment = fragment;
            this.account = account;
            this.message = message;
            this.giveUp = () -> finish(null);
        }

        void start(TLRPC.Document document) {
            fileName = FileLoader.getAttachFileName(document);
            final NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(this, NotificationCenter.fileLoaded);
            center.addObserver(this, NotificationCenter.fileLoadFailed);
            // Nothing else would ever take the observers off again if the file
            // neither arrives nor fails - a cancelled download, say.
            AndroidUtilities.runOnUIThread(giveUp, DOWNLOAD_TIMEOUT);
            FileLoader.getInstance(account).loadFile(
                    document,
                    new MessageObject(account, message, false, false),
                    FileLoader.PRIORITY_NORMAL_UP,
                    0);
        }

        @Override
        public void didReceivedNotification(int id, int notifiedAccount, Object... args) {
            if (args.length == 0 || !(args[0] instanceof String) || !args[0].equals(fileName)) {
                return;
            }
            if (id == NotificationCenter.fileLoaded) {
                finish(args.length > 1 && args[1] instanceof File ? (File) args[1] : null);
            } else if (id == NotificationCenter.fileLoadFailed) {
                finish(null);
            }
        }

        private void finish(File file) {
            if (finished) {
                return;
            }
            finished = true;
            AndroidUtilities.cancelRunOnUIThread(giveUp);
            final NotificationCenter center = NotificationCenter.getInstance(account);
            center.removeObserver(this, NotificationCenter.fileLoaded);
            center.removeObserver(this, NotificationCenter.fileLoadFailed);

            if (file == null || !file.exists()) {
                file = FileLoader.getInstance(account).getPathToMessage(message);
            }
            final Activity activity = fragment.getParentActivity();
            if (file != null && file.exists() && activity != null) {
                PurpleSettings.importFrom(activity, file, message.date);
                return;
            }
            FileLog.d("Purple: sync offer: could not download the settings.toml behind msg " + message.id + ".");
            if (BulletinFactory.canShowBulletin(fragment)) {
                BulletinFactory.of(fragment)
                        .createErrorBulletin(LocaleController.getString(R.string.PurpleImportFailed))
                        .show();
            }
        }
    }
}
