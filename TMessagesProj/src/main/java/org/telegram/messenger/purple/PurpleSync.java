/*
 * This is the source code of Purple Telegram for Android.
 *
 * settings.toml on its way to the other machine. Both halves of the exchange
 * live here: the one send - the manual button and the automatic one are the
 * same code - and the debounce that decides when a save deserves it.
 *
 * The rule about WHETHER to send is not here. It is in the core
 * (ShouldAutoSend, purple_state.h), because the ping-pong it prevents is a
 * claim about two devices agreeing, and this class - which has a network, a
 * Saved Messages history and a file watcher around it - is the worst place to
 * prove anything about that. What is here is the timing, which is the part
 * that is genuinely about Android.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public final class PurpleSync {

    /**
     * How long after the last write the document goes out.
     *
     * Long enough that a run of checkbox taps is one document rather than six,
     * short enough that somebody who changed a switch and put the phone down
     * has the file on the other machine before they pick the other one up.
     */
    private static final PurpleSaveDebounce.Scheduler scheduler = new PurpleSaveDebounce.Scheduler() {
        public boolean currentThread() { return android.os.Looper.myLooper() == android.os.Looper.getMainLooper(); }
        public void post(Runnable callback, long delay) { AndroidUtilities.runOnUIThread(callback, delay); }
        public void cancel(Runnable callback) { AndroidUtilities.cancelRunOnUIThread(callback); }
    };
    private static final PurpleSaveDebounces saves = new PurpleSaveDebounces(scheduler);
    private static PurpleSaveDebounce saves(String key) { return saves.of(key); }

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final ArrayList<PendingSend> pendingSends = new ArrayList<>();

    private static final class PendingSend {
        final byte[] bytes;
        final PurpleAccountFiles.Identity origin;

        PendingSend(PurpleAccountFiles.Identity origin, byte[] bytes) {
            this.bytes = bytes;
            this.origin = origin;
        }
    }

    private PurpleSync() {
    }

    static void refreshFromWatcher(Runnable reload) { saves.refresh(reload); }

    public static byte[] accountBytes(int account) {
        if (!PurpleAccountOwner.legacyTransferOperation(account)) return null;
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        PurpleAccountFiles files = origin == null ? null : PurpleAccountFiles.resolve(origin.key, PurpleAccountOwner.owner());
        if (files == null) return null;
        PurpleAccountFileSnapshot file = PurpleAccountFileSnapshot.read(files.settings);
        return origin.current() && file.status == PurpleAccountFileSnapshot.Status.Present ? file.bytes : null;
    }

    public static void afterWrite(String reason, boolean imported) {
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(UserConfig.selectedAccount);
        if (origin == null || !PurpleAccountOwner.legacyOperation(origin.slot)) return;
        byte[] bytes = accountBytes(origin.slot);
        if (bytes != null) afterFileWrite(origin, PurpleAccountFiles.rootSettings(), bytes, imported, !imported);
    }
    static void afterWrite(PurpleSyncFileContext context, byte[] bytes, String reason, boolean imported) {
        if (context == null || bytes == null) return;
        byte[] expected = bytes.clone();
        AndroidUtilities.runOnUIThread(() -> {
            if (context.current()) afterFileWrite(context.origin, context.files.settings, expected, imported, false);
        });
    }
    static void afterRoutedWrite(PurpleAccountFiles.Identity actor, File file, byte[] bytes, String reason) {
        if (actor == null || !actor.current()) return;
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
            PurpleAccountFiles files = PurpleAccountFiles.resolve(id.key, PurpleAccountOwner.owner());
            if (files != null && files.settings.equals(file)) { afterFileWrite(id, file, bytes, false, true); return; }
        }
    }
    static void afterAccountRowWrite(PurpleAccountFiles.Identity origin, File file,
            byte[] bytes, PurpleSaveDebounce.Receipt receipt, boolean allowSend) {
        if (receipt.current()) afterFileWrite(origin, file, bytes, false, allowSend);
    }
    static boolean afterFileWrite(PurpleAccountFiles.Identity origin, File file, byte[] bytes,
            boolean imported, boolean allowSend) {
        if (origin == null || bytes == null || !origin.current() || !PurpleAccountOwner.legacyTransferOperation(origin.slot)) return false;
        byte[] expected = bytes.clone();
        String owner = PurpleAccountOwner.owner();
        String global = PurpleAccountOwner.global();
        long role = PurpleAccountOwner.roleEpoch();
        PurpleAccountFiles files = PurpleAccountFiles.resolve(origin.key, owner);
        if (files == null || !file.equals(files.settings)) return false;
        PurpleAccountFileSnapshot written = PurpleAccountFileSnapshot.read(file);
        PurpleAccountFileSnapshot local = PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("local.toml"));
        PurpleAccountFileSnapshot record = PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("accounts/root.toml"));
        if (!Arrays.equals(expected, written.bytes)) return false;
        PurpleSaveDebounce.Current current = () -> origin.current() && PurpleAccountOwner.ready()
                && PurpleAutoSendRules.postsFrom(origin.slot, activatedAccounts())
                && role == PurpleAccountOwner.roleEpoch() && owner.equals(PurpleAccountOwner.owner())
                && global.equals(PurpleAccountOwner.global()) && local.matches(PurpleAccountFiles.file("local.toml"))
                && record.matches(PurpleAccountFiles.file("accounts/root.toml")) && written.matches(file);
        if (imported) {
            saves(origin.key).cancel();
            return current.current() && PurpleLegacySendState.note(origin, file, expected, true);
        }
        saves(origin.key).admit(current::current, () -> {
            byte[] state = PurpleLegacySendState.read(files);
            if (current.current() && state != null && PurpleCore.shouldAutoSend(expected, state, expected, false)) {
                String error = sendToSavedMessages(origin.slot, expected);
                if (error != null) FileLog.e("Purple: auto-send: " + error);
            }
        }, allowSend);
        return true;
    }

    private static boolean[] activatedAccounts() {
        boolean[] activated = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
        for (int i = 0; i < activated.length; ++i) activated[i] = UserConfig.getInstance(i).isClientActivated();
        return activated;
    }

    /**
     * Puts a copy of settings.toml in Saved Messages.
     *
     * The one send, shared by the button on the settings screen and by the
     * debounce above. Sharing it is the point: the caption, the staged name and
     * the refusal of a file that does not parse are what the other client
     * matches on, and two copies of that would be two chances to drift.
     *
     * Refused when the file does not parse, which is the desktop's rule too:
     * sending a broken file is how you break the machine you meant to sync.
     *
     * @param bytes the file, or null when there is none
     * @return null when the document is on its way, or a reason to show
     */
    public static File manualShareFile(int account, byte[] bytes) {
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (origin == null || !PurpleAccountOwner.manualBackupAllowed(account, bytes)) return null;
        File directory = staging().newDirectory(System.currentTimeMillis());
        if (directory == null) return null;
        File staged = new File(directory, PurpleSettings.exportName(PurpleCore.parse(bytes).version));
        if (!writeBytes(staged, bytes) || !origin.current()
                || !PurpleAccountOwner.manualBackupAllowed(account, bytes)) {
            PurpleSettingsStaging.remove(staged);
            return null;
        }
        return staged;
    }

    public static String sendToSavedMessages(int account, byte[] bytes) {
        if (!PurpleAccountOwner.manualBackupAllowed(account, bytes)) {
            return LocaleController.getString(R.string.PurpleAccountWriteHeld);
        }
        final PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (bytes == null) {
            return LocaleController.getString(R.string.PurpleFileMissing);
        }
        final byte[] sentBytes = bytes.clone();
        final PurpleCore.ParseResult parsed;
        try {
            parsed = PurpleCore.parse(sentBytes);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return LocaleController.getString(R.string.PurpleCoreUnavailable);
        }
        if (!parsed.ok || parsed.version > PurpleSettings.SUPPORTED_SCHEMA_VERSION) {
            return LocaleController.getString(R.string.PurpleSendRefused);
        }
        if (origin == null || !origin.current()) return LocaleController.getString(R.string.PurpleAccountWriteHeld);
        final PendingSend pendingSend = new PendingSend(origin, sentBytes);
        synchronized (pendingSends) {
            for (PendingSend pending : pendingSends) {
                if (pending.origin.key.equals(origin.key) && pending.origin.lifetime == origin.lifetime && Arrays.equals(pending.bytes, sentBytes)) {
                    return null;
                }
            }
            pendingSends.add(pendingSend);
        }
        final File stagingDir = staging().newDirectory(System.currentTimeMillis());
        if (stagingDir == null) {
            clearPending(pendingSend);
            return LocaleController.getString(R.string.PurpleSendFailed);
        }
        final File staged = new File(stagingDir, PurpleSettings.exportName(parsed.version));
        if (!writeBytes(staged, sentBytes)) {
            clearPending(pendingSend);
            PurpleSettingsStaging.remove(staged);
            return LocaleController.getString(R.string.PurpleSendFailed);
        }
        final String caption = "Purple settings · schema v" + parsed.version + " · "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())
                + " · Android";
        try {
            if (origin == null || !origin.current() || !PurpleAccountOwner.manualBackupAllowed(account, sentBytes)) {
                clearPending(pendingSend);
                PurpleSettingsStaging.remove(staged);
                return LocaleController.getString(R.string.PurpleAccountWriteHeld);
            }
            SendMessagesHelper.prepareSendingPurpleDocument(
                    AccountInstance.getInstance(account),
                    staged.getAbsolutePath(),
                    caption,
                    UserConfig.getInstance(account).getClientUserId(),
                    null,
                    PurpleSendReceipt.guarded(origin::current, (serverId, preparationFailed) -> {
                        clearPending(pendingSend);
                        if (serverId > 0 && origin.current() && PurpleAccountOwner.legacyTransferOperation(account)) {
                            noteConfirmedSend(origin, sentBytes, serverId);
                            if (!staged.exists()) {
                                stagingDir.delete();
                            }
                        } else if (preparationFailed) {
                            PurpleSettingsStaging.remove(staged);
                        }
                    }));
        } catch (RuntimeException e) {
            FileLog.e(e);
            clearPending(pendingSend);
            PurpleSettingsStaging.remove(staged);
            return LocaleController.getString(R.string.PurpleSendFailed);
        }
        return null;
    }

    public static boolean registerRetry(int account, MessageObject messageObject, boolean unsent) {
        PurpleSendReceipt receipt = receiptForRetry(account, messageObject, unsent);
        if (receipt != null) {
            PurpleSendReceipts.retry(account, messageObject.getId(), messageObject.getDialogId(), messageObject.messageOwner.attachPath, messageObject.messageOwner, receipt);
        } else if (messageObject != null && messageObject.messageOwner != null
                && messageObject.messageOwner.attachPath != null
                && staging().rootOf(new File(messageObject.messageOwner.attachPath)) != null) {
            return false;
        }
        return true;
    }

    private static PurpleSendReceipt receiptForRetry(
            int account, MessageObject messageObject, boolean unsent) {
        if (messageObject == null
                || messageObject.currentAccount != account
                || messageObject.messageOwner == null
                || !messageObject.messageOwner.out
                || !(messageObject.messageOwner.send_state == MessageObject.MESSAGE_SEND_STATE_SEND_ERROR
                        || (unsent && messageObject.messageOwner.send_state
                                == MessageObject.MESSAGE_SEND_STATE_SENDING))
                || messageObject.getDialogId() != UserConfig.getInstance(account).getClientUserId()
                || !UserConfig.getInstance(account).isClientActivated()
                || !(messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaDocument)
                || messageObject.messageOwner.media.document == null
                || !PurpleSettings.fileName(
                        FileLoader.getDocumentFileName(messageObject.messageOwner.media.document))) {
            return null;
        }
        final String path = messageObject.messageOwner.attachPath;
        if (path == null) {
            return null;
        }
        final File staged = new File(path);
        final PurpleSettingsStaging staging = staging();
        final File stagingRoot = staging.rootOf(staged);
        if (stagingRoot == null) {
            return null;
        }
        try {
            if (!staging.isCanonical(staged, stagingRoot)
                    || !staged.isFile()
                    || staged.length() == 0
                    || staged.length() > PurpleSettings.MAX_SIZE) {
                return null;
            }
            final byte[] sentBytes = new byte[(int) staged.length()];
            try (DataInputStream input = new DataInputStream(new FileInputStream(staged))) {
                input.readFully(sentBytes);
                if (input.read() != -1) {
                    return null;
                }
            }
            PurpleCore.ParseResult parsed = PurpleCore.parse(sentBytes);
            if (!parsed.ok || parsed.version > PurpleSettings.SUPPORTED_SCHEMA_VERSION
                    || !PurpleSettings.exportName(parsed.version).equals(staged.getName())
                    || !PurpleAccountOwner.manualBackupAllowed(account, sentBytes)) return null;
            final PurpleAccountFiles.Identity retryOrigin = PurpleAccountFiles.capture(account);
            if (retryOrigin == null) return null;
            return PurpleSendReceipt.guarded(retryOrigin::current, (serverId, preparationFailed) -> {
                if (serverId > 0 && retryOrigin != null && retryOrigin.current()
                        && PurpleAccountOwner.legacyTransferOperation(account)) {
                    noteConfirmedSend(retryOrigin, sentBytes, serverId);
                }
            });
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void noteConfirmedSend(PurpleAccountFiles.Identity origin, byte[] sentBytes, int serverId) {
        synchronized (PurpleAccountOwner.class) {
            synchronized (PurpleAccountFiles.class) {
                if (origin == null || !origin.current()) return;
                PurpleConfirmedSend.note(origin::current, () -> {
                    PurpleAccountFiles files = PurpleAccountFiles.resolve(origin.key, PurpleAccountOwner.owner());
                    return files != null && PurpleLegacySendState.note(origin, files.settings, sentBytes, false);
                }, () -> PurpleSyncOffer.noteSentMessage(origin.slot, serverId));
            }
        }
    }

    private static void clearPending(PendingSend pendingSend) {
        synchronized (pendingSends) {
            pendingSends.remove(pendingSend);
        }
    }

    static boolean stagedLegacyFile(String path) {
        if (path == null) return false;
        File file = new File(path);
        PurpleSettingsStaging staging = staging();
        File root = staging.rootOf(file);
        try { return root != null && staging.isCanonical(file, root); }
        catch (IOException | RuntimeException e) { return false; }
    }

    private static PurpleSettingsStaging staging() {
        return new PurpleSettingsStaging(PurpleSettings.FILE_NAME,
                externalFilesDirectory(),
                FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE));
    }

    private static File externalFilesDirectory() {
        try {
            return ApplicationLoader.applicationContext.getExternalFilesDir(null);
        } catch (RuntimeException e) {
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
