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
 *
 * Only the active account's Saved Messages are offered, and the offer names
 * that account and the message's date. A file in another signed-in account,
 * a test account say, is not this one's settings: an answer that arrives after
 * the user switched away is dropped without being remembered, so the account
 * is looked at again once it is back in front.
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
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public final class PurpleSyncOffer {

    /** Newest settings.toml message this account has already been offered. */
    private static final String OFFERED_KEY = "purple_sync_offered_id";

    /** How many of the chat's newest documents we are willing to look through. */
    private static final int SEARCH_LIMIT = 100;

    /** How long a tapped Import waits for a document before giving up. */
    private static final long DOWNLOAD_TIMEOUT = 5 * 60 * 1000L;

    /** Accounts already looked at in this process. */
    private static final Set<String> checked = new HashSet<>();

    private PurpleSyncOffer() {
    }

    public static void noteSentMessage(int account, int serverId) {
        if (serverId <= 0) {
            return;
        }
        final SharedPreferences prefs = MessagesController.getMainSettings(account);
        if (serverId > prefs.getInt(OFFERED_KEY, 0)) {
            prefs.edit().putInt(OFFERED_KEY, serverId).apply();
        }
    }

    /**
     * Looks Saved Messages up once, on the first chat list this process builds.
     *
     * Costs at most two searches per account per process and nothing after
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
        if (!UserConfig.getInstance(currentAccount).isClientActivated()
                || currentAccount != UserConfig.selectedAccount) {
            return;
        }
        synchronized (checked) {
            if (!checked.add(checkKey(currentAccount))) {
                return;
            }
        }
        searchSettings(currentAccount, found -> consider(fragment, currentAccount, found));
    }

    /**
     * The same lookup, asked for by hand from the Work Mode settings screen.
     *
     * Two rules differ from the launch check. "Already offered" does not apply:
     * somebody who presses a button called "check" has asked again on purpose,
     * and answering nothing would read as broken. "Newer than the local file"
     * still does - offering to replace settings.toml with an older copy of
     * itself is the one thing this must never do - and when it holds, the
     * caller is told so rather than left with silence.
     *
     * @param done runs on the UI thread with true when an import was offered
     */
    public static void checkNow(BaseFragment fragment, int currentAccount, Answer done) {
        if (fragment == null || currentAccount < 0
                || !UserConfig.getInstance(currentAccount).isClientActivated()
                || currentAccount != UserConfig.selectedAccount) {
            done.run(false);
            return;
        }
        // Marked as looked at either way, so the next launch does not repeat by
        // itself what has just been done by hand.
        synchronized (checked) {
            checked.add(checkKey(currentAccount));
        }
        searchSettings(currentAccount, found -> done.run(offerNow(fragment, currentAccount, found)));
    }

    /** Whether the caller's request found something worth offering. */
    public interface Answer {
        void run(boolean offered);
    }

    private static boolean offerNow(BaseFragment fragment, int account, TLRPC.Message message) {
        if (message == null) {
            return false;
        }
        final PurpleSyncOfferRules.Verdict verdict = PurpleSyncOfferRules.judge(
                account, UserConfig.selectedAccount, true,
                message.id, message.date, 0, localStamp(account));
        if (verdict == PurpleSyncOfferRules.Verdict.INACTIVE_ACCOUNT) {
            forget(account);
            FileLog.d("Purple: sync check: account " + account + " is no longer the active one.");
            return false;
        }
        if (verdict == PurpleSyncOfferRules.Verdict.NOT_NEWER) {
            // Not newer, so nothing to offer. The id still moves on for the
            // same reason the launch check moves it: this message will not
            // become worth offering later either.
            noteSentMessage(account, message.id);
            FileLog.d("Purple: sync check: msg " + message.id + " is not newer than the local file.");
            return false;
        }
        return show(fragment, account, message);
    }

    private static boolean show(BaseFragment fragment, int account, TLRPC.Message message) {
        final PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (origin == null || !PurpleAccountOwner.legacyTransferOperation(account)) return false;
        final String from = PurpleSettings.accountLabel(account);
        if (from == null || !BulletinFactory.canShowBulletin(fragment)) {
            return false;
        }
        // Written before the line is drawn. That covers the crash, and it also
        // covers both ways of saying no - swiping the bulletin away and letting
        // it time out - neither of which then needs a listener of its own.
        noteSentMessage(account, message.id);
        BulletinFactory.of(fragment)
                .createSimpleBulletin(
                        R.raw.info,
                        LocaleController.getString(R.string.PurpleSyncOffer),
                        LocaleController.formatString(R.string.PurpleSyncOfferFrom,
                                from, LocaleController.formatDateTime(message.date, true)) + "\n\n"
                                + LocaleController.formatString(origin.key.equals(PurpleAccountOwner.global())
                                ? R.string.PurpleImpactGlobalScope : R.string.PurpleImpactOwnScope, from),
                        LocaleController.getString(R.string.Import),
                        () -> { if (origin.current()) startImport(fragment, account, message); })
                .show();
        return true;
    }

    private static void forget(int account) {
        synchronized (checked) {
            checked.remove(checkKey(account));
        }
    }

    private static long localStamp(int account) {
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(account);
        PurpleAccountFiles files = id == null ? null : PurpleAccountFiles.resolve(id.key, PurpleAccountOwner.owner());
        PurpleAccountFiles global = PurpleAccountOwner.globalFiles();
        if (files == null || global == null) return Long.MAX_VALUE;
        return PurpleSyncOfferRules.localStamp(files.settings.isFile(), files.settings.lastModified() / 1000L,
                global.settings.lastModified() / 1000L);
    }

    /** What a Saved Messages lookup came back with, or null. */
    private interface Found {
        void run(TLRPC.Message message);
    }

    private static String checkKey(int account) {
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        return origin == null ? "signed-out:" + account : origin.key + ":" + origin.lifetime;
    }
    private static void searchSettings(int account, Found callback) {
        final PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (origin == null) { callback.run(null); return; }
        search(account, PurpleSettings.FILE_NAME, whole -> {
            if (!origin.current()) { callback.run(null); return; }
            search(account, PurpleSettings.ACCOUNT_FILE_NAME, layered -> {
                if (!origin.current()) { callback.run(null); return; }
                TLRPC.Message best = whole == null ? layered : layered == null || whole.id >= layered.id ? whole : layered;
                if (best != null) callback.run(best);
                else search(account, "", found -> callback.run(origin.current() ? found : null));
            });
        });
    }

    private static void search(int account, String query, Found callback) {
        final PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (origin == null || !PurpleAccountOwner.legacyTransferOperation(account)) { callback.run(null); return; }
        final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = MessagesController.getInstance(account).getInputPeer(UserConfig.getInstance(account).getClientUserId());
        req.q = query;
        req.filter = new TLRPC.TL_inputMessagesFilterDocument();
        req.limit = SEARCH_LIMIT;
        if (req.peer == null) {
            callback.run(null);
            return;
        }
        PurpleRpcRequests.send(account,"legacy-offer",()->origin.current()&&PurpleAccountOwner.legacyTransferOperation(account),req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!origin.current() || !PurpleAccountOwner.legacyTransferOperation(account)) { callback.run(null); return; }
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
                    && PurpleSettings.fileName(((TLRPC.TL_documentAttributeFilename) attribute).file_name)) {
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
        final long localStamp = localStamp(account);
        final SharedPreferences prefs = MessagesController.getMainSettings(account);
        final int lastOffered = prefs.getInt(OFFERED_KEY, 0);

        final String head = "Purple: sync offer: newest settings.toml is msg " + message.id
                + " (date " + message.date + "), local stamp " + localStamp
                + ", last offered " + lastOffered + " -> ";

        switch (PurpleSyncOfferRules.judge(account, UserConfig.selectedAccount, false,
                message.id, message.date, lastOffered, localStamp)) {
            case INACTIVE_ACCOUNT:
                forget(account);
                FileLog.d(head + "skipped (account " + account + " is no longer the active one).");
                return;
            case ALREADY_OFFERED:
                FileLog.d(head + "skipped (already offered).");
                return;
            case NOT_NEWER:
                // Older than what is already on disk, so not worth offering now -
                // and it will not become worth offering later either, which is why
                // the id moves on rather than being left to come back tomorrow.
                noteSentMessage(account, message.id);
                FileLog.d(head + "skipped (older than the local file).");
                return;
            case OFFER:
            default:
                FileLog.d(head + (show(fragment, account, message)
                        ? "offering." : "skipped (no window to show it in, or no account name)."));
        }
    }

    private static void startImport(BaseFragment fragment, int account, TLRPC.Message message) {
        if (!PurpleAccountOwner.legacyTransferOperation(account)) return;
        // Again after the choice. The id is already down from the line above;
        // repeating it here keeps the record beside the decision it records,
        // so neither half can be moved without the other being noticed.
        noteSentMessage(account, message.id);

        if (!PurpleSyncOfferRules.mayImport(account, UserConfig.selectedAccount)) {
            FileLog.d("Purple: sync offer: account " + account + " is no longer the active one, so msg "
                    + message.id + " was not imported.");
            return;
        }
        final Activity activity = fragment.getParentActivity();
        final TLRPC.Document document = documentOf(message);
        if (activity == null || document == null) {
            return;
        }
        final File cached = FileLoader.getInstance(account).getPathToMessage(message);
        if (cached != null && cached.exists()) {
            PurpleSettings.importFrom(activity, cached, message.date, account, true);
            return;
        }
        // Not downloaded yet. Up to 4 MiB over the connection the search has
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
        private final PurpleAccountFiles.Identity origin;
        private String fileName;
        private boolean finished;

        Download(BaseFragment fragment, int account, TLRPC.Message message) {
            this.fragment = fragment;
            this.account = account;
            this.message = message;
            this.origin = PurpleAccountFiles.capture(account);
            this.giveUp = () -> finish(null);
        }

        void start(TLRPC.Document document) {
            if (origin == null || !origin.current() || !PurpleAccountOwner.legacyTransferOperation(account)) return;
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

            if (origin == null || !origin.current() || account != UserConfig.selectedAccount
                    || !PurpleAccountOwner.legacyTransferOperation(account)) return;
            if (file == null || !file.exists()) {
                file = FileLoader.getInstance(account).getPathToMessage(message);
            }
            final Activity activity = fragment.getParentActivity();
            if (file != null && file.exists() && activity != null) {
                PurpleSettings.importFrom(activity, file, message.date, account, true);
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
