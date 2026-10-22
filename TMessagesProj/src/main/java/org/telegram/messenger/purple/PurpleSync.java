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
    private static final PurpleSaveDebounce saves = new PurpleSaveDebounce(new PurpleSaveDebounce.Scheduler() {
        public boolean currentThread() { return android.os.Looper.myLooper() == android.os.Looper.getMainLooper(); }
        public void post(Runnable callback, long delay) { AndroidUtilities.runOnUIThread(callback, delay); }
        public void cancel(Runnable callback) { AndroidUtilities.cancelRunOnUIThread(callback); }
    });

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final ArrayList<PendingSend> pendingSends = new ArrayList<>();

    private static final class PendingSend {
        final int account;
        final byte[] bytes;
        final PurpleAccountFiles.Identity origin;

        PendingSend(int account, byte[] bytes) {
            this.account = account;
            this.bytes = bytes;
            origin = PurpleAccountFiles.capture(account);
        }
    }

    private PurpleSync() {
    }

    static void refreshFromWatcher(Runnable reload) { saves.refresh(reload); }

    /**
     * Called after every write of settings.toml the app itself made, except
     * an account sync restore or undo, which stays on this device as it does
     * on desktop.
     *
     * Every other writer funnels through here - the splices in
     * {@link PurpleWriter}, the editor's whole-file save, and the import -
     * because the question the core answers is about bytes on disk and not
     * about which screen put them there.
     *
     * @param reason what asked for the write, for the log line
     * @param wroteFromImport whether this write IS an import installing the
     *                        file the other device sent. That never sends -
     *                        returning bytes to whoever just handed them over
     *                        is the ping-pong - and it is also the moment the
     *                        imported fingerprint is written down.
     */
    public static void afterWrite(final String reason, final boolean wroteFromImport) {
        afterWrite(PurpleAccountFiles.capture(UserConfig.selectedAccount), null, null, reason, wroteFromImport);
    }

    static void afterWrite(PurpleSyncFileContext context, byte[] expected, String reason, boolean wroteFromImport) {
        if (context == null || expected == null) return;
        afterWrite(context.origin, context, expected.clone(), reason, wroteFromImport);
    }

    private static void afterWrite(final PurpleAccountFiles.Identity origin,
            final PurpleSyncFileContext context, final byte[] expected,
            final String reason, final boolean wroteFromImport) {
        if (origin == null || !origin.current() || !PurpleAccountOwner.legacyOperation(origin.slot)
                || (context != null && !context.current())) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!origin.current() || !PurpleAccountOwner.legacyOperation(origin.slot)
                    || (context != null && !context.current())) return;
            saves.cancel();
            // Read from disk rather than handed in: the splice, the editor and
            // the import all end in a file, and the file is the only thing all
            // three agree about.
            final byte[] bytes = PurpleGate.settingsBytes();
            if (bytes == null || (expected != null && !java.util.Arrays.equals(expected, bytes))) {
                return;
            }
            if (wroteFromImport) {
                // Recorded whether or not the switch is on. It costs one write
                // of state.toml on a rare event, and it means turning the
                // switch on later does not immediately post back the file this
                // device was given.
                if (context == null) noteImported(bytes);
                else noteImported(context, bytes);
                return;
            }
            if (!PurpleCore.shouldAutoSend(bytes, PurpleState.read(), bytes, false)) {
                return;
            }
            FileLog.d("Purple: auto-send armed by " + reason + ".");
            final long roleEpoch = PurpleAccountOwner.roleEpoch();
            final String owner = PurpleAccountOwner.owner();
            final String global = PurpleAccountOwner.global();
            saves.replace(() -> origin.current() && UserConfig.selectedAccount == origin.slot
                    && PurpleAccountOwner.legacyOperation(origin.slot)
                    && roleEpoch == PurpleAccountOwner.roleEpoch()
                    && java.util.Objects.equals(owner, PurpleAccountOwner.owner())
                    && java.util.Objects.equals(global, PurpleAccountOwner.global())
                    && (context == null || context.current())
                    && Arrays.equals(bytes, PurpleGate.settingsBytes()), PurpleSync::fire);
        });
    }

    /**
     * The debounce running out.
     *
     * Everything is asked again rather than remembered from five seconds ago,
     * because five seconds is long enough for all of it to have moved: the
     * write that armed this may have been followed by one that turned the
     * switch off, or by an import that has since claimed these very bytes.
     */
    static void afterAccountRowWrite(PurpleAccountFiles.Identity origin, File file,
            byte[] bytes, PurpleSaveDebounce.Receipt receipt, boolean allowSend) {
        byte[] expected = bytes.clone();
        saves.admit(receipt, () -> {
            if (!receipt.current() || !origin.current()
                    || UserConfig.selectedAccount != origin.slot
                    || !PurpleAccountOwner.legacyOperation(origin.slot)
                    || !file.equals(PurpleAccountFiles.rootSettings())
                    || !Arrays.equals(expected, PurpleAccountFileSnapshot.read(file).bytes)
                    || !PurpleAutoSendRules.postsFrom(origin.slot, activatedAccounts())
                    || !PurpleCore.shouldAutoSend(expected, PurpleState.read(), expected, false)) return;
            sendToSavedMessages(origin.slot, expected);
        }, allowSend);
    }

    private static void fire() {
        if (!PurpleAccountOwner.legacyOperation(UserConfig.selectedAccount)) return;
        final byte[] bytes = PurpleGate.settingsBytes();
        if (bytes == null) {
            return;
        }
        if (!PurpleCore.shouldAutoSend(bytes, PurpleState.read(), bytes, false)) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            // Nobody to send to. The fingerprint stays unwritten, so the send
            // happens on the next save after a login rather than never.
            return;
        }
        if (!PurpleAutoSendRules.postsFrom(account, activatedAccounts())) {
            FileLog.d("Purple: auto-send skipped: the selected account is not the first account.");
            return;
        }
        final String error = sendToSavedMessages(account, bytes);
        if (error != null) {
            // Said in the log and nowhere else: nobody asked for this one, so a
            // bulletin over whatever screen happens to be open would be the app
            // interrupting to report on something it started itself.
            FileLog.e("Purple: auto-send: " + error);
        }
    }

    private static boolean[] activatedAccounts() {
        final boolean[] activated = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
        for (int account = 0; account < activated.length; ++account) {
            activated[account] = UserConfig.getInstance(account).isClientActivated();
        }
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
        File staged = new File(directory, PurpleSettings.FILE_NAME);
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
        if (!parsed.ok || parsed.version > 1) {
            return LocaleController.getString(R.string.PurpleSendRefused);
        }
        final PendingSend pendingSend = new PendingSend(account, sentBytes);
        synchronized (pendingSends) {
            for (PendingSend pending : pendingSends) {
                if (pending.account == account && Arrays.equals(pending.bytes, sentBytes)) {
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
        final File staged = new File(stagingDir, PurpleSettings.FILE_NAME);
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
                    (serverId, preparationFailed) -> {
                        clearPending(pendingSend);
                        if (serverId > 0 && origin.current() && PurpleAccountOwner.legacyOperation(account)) {
                            noteConfirmedSend(account, sentBytes, serverId);
                            if (!staged.exists()) {
                                stagingDir.delete();
                            }
                        } else if (preparationFailed) {
                            PurpleSettingsStaging.remove(staged);
                        }
                    });
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
                || !PurpleSettings.FILE_NAME.equals(
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
            if (!parsed.ok || parsed.version > 1 || !PurpleAccountOwner.manualBackupAllowed(account, sentBytes)) return null;
            final PurpleAccountFiles.Identity retryOrigin = PurpleAccountFiles.capture(account);
            return (serverId, preparationFailed) -> {
                if (serverId > 0 && retryOrigin != null && retryOrigin.current()
                        && PurpleAccountOwner.legacyOperation(account)) {
                    noteConfirmedSend(account, sentBytes, serverId);
                }
            };
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void noteConfirmedSend(int account, byte[] sentBytes, int serverId) {
        if (!PurpleAccountOwner.legacyOperation(account)) return;
        if (Arrays.equals(PurpleGate.settingsBytes(), sentBytes)) {
            noteSent(sentBytes);
        }
        PurpleSyncOffer.noteSentMessage(account, serverId);
    }

    /** Remembers the file this device just sent. */
    private static void noteSent(byte[] bytes) {
        write(PurpleCore.noteSent(PurpleState.read(), bytes));
    }

    /** Remembers the file this device just wrote because the other one sent it. */
    private static void noteImported(byte[] bytes) {
        write(PurpleCore.noteImported(PurpleState.read(), bytes));
    }

    private static void noteImported(PurpleSyncFileContext context, byte[] bytes) {
        PurpleAccountFileSnapshot state = PurpleAccountFileSnapshot.read(
                PurpleState.stateFile(), (int) PurpleState.MAX_SIZE);
        if (!context.current() || state.status == PurpleAccountFileSnapshot.Status.ReadError) return;
        String text = PurpleCore.noteImported(state.bytes, bytes);
        if (text != null && context.current() && java.util.Arrays.equals(bytes, PurpleGate.settingsBytes())) {
            PurpleAccountFileSnapshot.replace(PurpleState.stateFile(), state, text.getBytes(UTF_8));
        }
    }

    /**
     * Puts a fingerprint into state.toml, and reloads nothing.
     *
     * Neither fingerprint is part of the resolution - no preset, no folder and
     * no row reads them - so a reload here would rebuild every chat list to
     * record a fact about a document.
     */
    private static void write(String text) {
        if (text != null) {
            PurpleState.write(text.getBytes(UTF_8));
        }
    }

    private static void clearPending(PendingSend pendingSend) {
        synchronized (pendingSends) {
            pendingSends.remove(pendingSend);
        }
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
