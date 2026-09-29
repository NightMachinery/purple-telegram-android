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
import java.util.UUID;

public final class PurpleSync {

    /**
     * How long after the last write the document goes out.
     *
     * Long enough that a run of checkbox taps is one document rather than six,
     * short enough that somebody who changed a switch and put the phone down
     * has the file on the other machine before they pick the other one up.
     */
    private static final long DEBOUNCE_MS = 5000L;

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final long STAGING_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final ArrayList<PendingSend> pendingSends = new ArrayList<>();

    private static final class PendingSend {
        final int account;
        final byte[] bytes;

        PendingSend(int account, byte[] bytes) {
            this.account = account;
            this.bytes = bytes;
        }
    }

    private PurpleSync() {
    }

    /**
     * The armed send. One instance, so re-arming is a cancel and a post of the
     * same object rather than a queue of runnables each holding a stale copy of
     * the file it was armed for.
     */
    private static final Runnable pending = PurpleSync::fire;

    /**
     * Called after every write of settings.toml the app itself made.
     *
     * Every writer funnels through here - the splices in {@link PurpleWriter},
     * the editor's whole-file save, and the import - because the question the
     * core answers is about bytes on disk and not about which screen put them
     * there.
     *
     * @param reason what asked for the write, for the log line
     * @param wroteFromImport whether this write IS an import installing the
     *                        file the other device sent. That never sends -
     *                        returning bytes to whoever just handed them over
     *                        is the ping-pong - and it is also the moment the
     *                        imported fingerprint is written down.
     */
    public static void afterWrite(final String reason, final boolean wroteFromImport) {
        AndroidUtilities.runOnUIThread(() -> {
            // Read from disk rather than handed in: the splice, the editor and
            // the import all end in a file, and the file is the only thing all
            // three agree about.
            final byte[] bytes = PurpleGate.settingsBytes();
            if (bytes == null) {
                return;
            }
            if (wroteFromImport) {
                // Recorded whether or not the switch is on. It costs one write
                // of state.toml on a rare event, and it means turning the
                // switch on later does not immediately post back the file this
                // device was given.
                noteImported(bytes);
                return;
            }
            if (!PurpleCore.shouldAutoSend(bytes, PurpleState.read(), bytes, false)) {
                return;
            }
            FileLog.d("Purple: auto-send armed by " + reason + ".");
            AndroidUtilities.cancelRunOnUIThread(pending);
            AndroidUtilities.runOnUIThread(pending, DEBOUNCE_MS);
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
    private static void fire() {
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
        final String error = sendToSavedMessages(account, bytes);
        if (error != null) {
            // Said in the log and nowhere else: nobody asked for this one, so a
            // bulletin over whatever screen happens to be open would be the app
            // interrupting to report on something it started itself.
            FileLog.e("Purple: auto-send: " + error);
        }
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
    public static String sendToSavedMessages(int account, byte[] bytes) {
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
        if (!parsed.ok) {
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
        final File stagingRoot = new File(
                FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "purple-sync");
        pruneStaging(stagingRoot);
        final File stagingDir = new File(stagingRoot, UUID.randomUUID().toString());
        if (!stagingDir.mkdirs()) {
            clearPending(pendingSend);
            return LocaleController.getString(R.string.PurpleImportFailed);
        }
        final File staged = new File(stagingDir, PurpleSettings.FILE_NAME);
        if (!writeBytes(staged, sentBytes)) {
            clearPending(pendingSend);
            removeStaging(staged);
            return LocaleController.getString(R.string.PurpleImportFailed);
        }
        final String caption = "Purple settings · schema v" + parsed.version + " · "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())
                + " · Android";
        try {
            SendMessagesHelper.prepareSendingPurpleDocument(
                    AccountInstance.getInstance(account),
                    staged.getAbsolutePath(),
                    caption,
                    UserConfig.getInstance(account).getClientUserId(),
                    (serverId, preparationFailed) -> {
                        clearPending(pendingSend);
                        if (serverId > 0) {
                            noteConfirmedSend(account, sentBytes, serverId);
                            if (!staged.exists()) {
                                stagingDir.delete();
                            }
                        } else if (preparationFailed) {
                            removeStaging(staged);
                        }
                    });
        } catch (RuntimeException e) {
            FileLog.e(e);
            clearPending(pendingSend);
            removeStaging(staged);
            return LocaleController.getString(R.string.PurpleImportFailed);
        }
        return null;
    }

    public static SendMessagesHelper.SendReceipt receiptForRetry(
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
        final File stagingRoot = new File(
                FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "purple-sync");
        final File stagingDir = staged.getParentFile();
        if (stagingDir == null
                || !PurpleSettings.FILE_NAME.equals(staged.getName())
                || !stagingRoot.equals(stagingDir.getParentFile())) {
            return null;
        }
        try {
            final String uuid = stagingDir.getName();
            if (!UUID.fromString(uuid).toString().equals(uuid)
                    || !staged.getCanonicalFile().equals(new File(
                            new File(stagingRoot.getCanonicalFile(), uuid),
                            PurpleSettings.FILE_NAME))
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
            if (!PurpleCore.parse(sentBytes).ok) {
                return null;
            }
            return (serverId, preparationFailed) -> {
                if (serverId > 0) {
                    noteConfirmedSend(account, sentBytes, serverId);
                }
            };
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void noteConfirmedSend(int account, byte[] sentBytes, int serverId) {
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

    private static void removeStaging(File staged) {
        if (staged.delete()) {
            staged.getParentFile().delete();
        }
    }

    private static void pruneStaging(File root) {
        final File[] dirs = root.listFiles();
        if (dirs == null) {
            return;
        }
        final long oldest = System.currentTimeMillis() - STAGING_MAX_AGE_MS;
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.lastModified() >= oldest) {
                continue;
            }
            final File staged = new File(dir, PurpleSettings.FILE_NAME);
            if (staged.isFile() && staged.lastModified() < oldest) {
                removeStaging(staged);
            } else if (!staged.exists()) {
                dir.delete();
            }
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
