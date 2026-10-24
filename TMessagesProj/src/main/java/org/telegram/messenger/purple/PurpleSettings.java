package org.telegram.messenger.purple;

import android.content.Context;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Imports a Purple Work Mode settings.toml that arrived as a Saved Messages document.
 *
 * Validation is done by the native purplecore library; this class only consumes its
 * JSON answer, confirms with the user and swaps the stored file.
 */
public final class PurpleSettings {

    /** Schema version this build was written against. */
    public static final int SUPPORTED_SCHEMA_VERSION = 2;

    public static final String FILE_NAME = "settings.toml";
    public static final String ACCOUNT_FILE_NAME = "account-settings.toml";
    public static boolean fileName(String name) { return PurpleTransferRules.fileName(name); }
    public static String exportName(int version) { return PurpleTransferRules.exportName(version); }
    public static final String BACKUP_NAME = "settings.toml.bak";
    public static final String IMPORT_BACKUP_NAME = "settings.toml.import.bak";

    /**
     * The last settings.toml that parsed and resolved.
     *
     * Distinct from the backups above, which may never have worked. This one
     * is only written after the core has accepted the file, so it is the thing
     * to fall back to when the real one is missing or broken.
     */
    public static final String LAST_GOOD_NAME = "settings.toml.good";

    /** Largest settings.toml we are willing to look at. */
    public static final long MAX_SIZE = 4 * 1024 * 1024;

    private PurpleSettings() {
    }

    public static File dir() {
        return PurpleAccountFiles.root();
    }

    public static File settingsFile() {
        return PurpleAccountFiles.rootSettings();
    }

    public static File backupFile() {
        return PurpleAccountFiles.rootBackup();
    }

    public static File importBackupFile() {
        return PurpleAccountFiles.rootImportBackup();
    }

    public static File lastGoodFile() {
        return PurpleAccountFiles.rootGood();
    }

    public static void importFrom(Context context, File source, int messageDate) {
        importFrom(context, source, messageDate, PurpleSyncOfferRules.NO_ACCOUNT);
    }

    public static void importFrom(Context context, File source, MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return;
        }
        importFrom(context, source, message.messageOwner.date, message.currentAccount);
    }

    /**
     * @param account whose Saved Messages the file is in, or NO_ACCOUNT; the
     *                question names it and the import needs it to be active
     */
    public static void importFrom(Context context, File source, int messageDate, int account) {
        importFrom(context, source, messageDate, account, false);
    }
    static void importFrom(Context context, File source, int messageDate, int account, boolean activeOnly) {
        importFrom(context, source, messageDate, PurpleAccountFiles.capture(
                account == PurpleSyncOfferRules.NO_ACCOUNT ? UserConfig.selectedAccount : account), activeOnly);
    }
    public static void importFrom(Context context, File source, int messageDate, PurpleAccountFiles.Identity origin) {
        importFrom(context, source, messageDate, origin, false);
    }
    private static void importFrom(Context context, File source, int messageDate, PurpleAccountFiles.Identity origin, boolean activeOnly) {
        if (origin == null || !origin.current()) return;
        if (context == null || source == null || !source.exists()) {
            return;
        }
        if (source.length() > MAX_SIZE) {
            showError(context, LocaleController.getString(R.string.PurpleImportFailed));
            return;
        }

        final byte[] bytes;
        try {
            try (InputStream input = new FileInputStream(source)) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while (output.size() <= MAX_SIZE && (count = input.read(buffer, 0,
                        (int) Math.min(buffer.length, MAX_SIZE + 1 - output.size()))) > 0) output.write(buffer, 0, count);
                bytes = output.toByteArray();
            }
        } catch (IOException e) {
            FileLog.e(e);
            showError(context, LocaleController.getString(R.string.PurpleImportFailed));
            return;
        }
        importFrom(context, bytes, messageDate, origin, activeOnly);
    }

    public static void importFrom(Context context, byte[] bytes, int messageDate) {
        importFrom(context, bytes, messageDate, PurpleSyncOfferRules.NO_ACCOUNT);
    }

    public static void importFrom(Context context, byte[] bytes, int messageDate, int account) {
        importFrom(context, bytes, messageDate, account, false);
    }
    private static void importFrom(Context context, byte[] bytes, int messageDate, int account, boolean activeOnly) {
        importFrom(context, bytes, messageDate, PurpleAccountFiles.capture(
                account == PurpleSyncOfferRules.NO_ACCOUNT ? UserConfig.selectedAccount : account), activeOnly);
    }
    public static void importFrom(Context context, byte[] bytes, int messageDate, PurpleAccountFiles.Identity origin) {
        importFrom(context, bytes, messageDate, origin, false);
    }
    private static void importFrom(Context context, byte[] bytes, int messageDate, PurpleAccountFiles.Identity origin, boolean activeOnly) {
        if (context == null) return;
        if (bytes == null || bytes.length > MAX_SIZE) {
            showError(context, LocaleController.getString(R.string.PurpleImportFailed));
            return;
        }
        if (origin == null || !origin.current()) {
            showError(context, LocaleController.getString(R.string.PurpleImportAccountChanged));
            return;
        }
        final int destination = origin.slot;
        final PurpleAccountEditor.Session session = PurpleAccountEditor.open(destination, false,
                () -> origin != null && origin.current() && (!activeOnly || destination == UserConfig.selectedAccount));
        final PurpleAccountEditor.Preview preview = session == null ? null : session.previewImport(bytes.clone());
        if (preview == null) {
            showError(context, LocaleController.getString(R.string.PurpleImpactHeld));
            if (!PurpleAccountOwner.ready()) org.telegram.ui.PurpleAccountSettingsRows.requestOwner(context);
            return;
        }
        String message = preview.impactNotes() + "\n\n" + LocaleController.formatString(
                R.string.PurpleImportQuestionFrom, LocaleController.formatDateTime(messageDate, true), accountLabel(destination));
        if (!preview.warnings().isEmpty()) message += "\n\n" + preview.warnings();
        message += "\n\n" + LocaleController.getString(R.string.PurpleImportReplaces);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.formatString(R.string.PurpleImpactTitle, accountLabel(destination)));
        builder.setMessage(message);
        final AlertDialog[] shown = new AlertDialog[1];
        builder.setPositiveButton(LocaleController.getString(R.string.Import), (dialog, which) -> {
            PurpleAccountEditor.Outcome result = session.save(preview,
                    () -> origin.current() && shown[0] != null && shown[0].isShowing() && !shown[0].isDismissed());
            if (result.ok) {
                PurpleGate.reloadAccountFiles("account import");
                Toast.makeText(context, LocaleController.getString(R.string.PurpleSettingsImported), Toast.LENGTH_SHORT).show();
            } else showError(context, LocaleController.getString(result.changed ? R.string.PurpleImportCompletionUnverified
                    : R.string.PurpleImportAccountChanged));
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        shown[0] = builder.create();
        shown[0].setOnDismissListener(dialog -> session.retire());
        shown[0].show();
        android.view.View cancel = shown[0].getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
        if (cancel != null) { cancel.setFocusableInTouchMode(true); cancel.requestFocus(); }
    }

    public static String accountLabel(int account) {
        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null) {
            return null;
        }
        return PurpleSyncOfferRules.accountLabel(
                ContactsController.formatName(user.first_name, user.last_name),
                UserObject.getPublicUsername(user),
                LocaleController.getString(R.string.PurpleSyncAccountWithUsername));
    }

    /**
     * Replaces settings.toml with bytes the caller has already parsed.
     *
     * The one writer that is not a splice: the editor hands over the whole file
     * because the whole file is what it was editing. Public so that screen can
     * reach it without widening {@link #writeAtomic}, which stays package-only
     * on purpose - it writes any bytes anywhere and keeps no backup.
     *
     * @param reason what asked for it, for the reload's log line
     */
    public static boolean save(byte[] bytes, String reason) {
        return store(bytes, reason, false);
    }

    /** Backs the current file up, then atomically replaces it with {@code bytes}. */
    private static boolean store(byte[] bytes) {
        return store(bytes, "import", true);
    }

    /**
     * The same replacement for account sync, which checks the result itself
     * through {@link PurpleSyncSettingsFile}.
     *
     * @param fromImport true for another device's settings, which the
     *                   auto-send must not post back; false for a restore or
     *                   undo, which stays on this device and never arms the
     *                   auto-send, as on desktop
     */
    static boolean storeForSync(PurpleSyncFileContext context, PurpleSyncSettingsFile.Contents expected,
            byte[] bytes, String reason, boolean fromImport) {
        if (context == null || !context.current() || expected == null || expected.mutation == null
                || !expected.current() || !PurpleSyncSettingsFile.read(context).sameAs(expected)) return false;
        boolean written = expected.mutation.replace(bytes, fromImport);
        if (written && context.current()) PurpleGate.reloadAccountFiles(reason);
        return written;
    }

    /**
     * @param fromImport whether these bytes came from the other device rather
     *                   than from something done on this one. It is the whole
     *                   difference the auto-send cares about: an import is
     *                   never sent back, and it is what writes down the
     *                   fingerprint that keeps it from being.
     */
    private static boolean store(byte[] bytes, String reason, boolean fromImport) {
        if (!replace(bytes, reason, fromImport)) {
            return false;
        }
        PurpleSync.afterWrite(reason, fromImport);
        return true;
    }

    private static boolean replace(byte[] bytes, String reason, boolean fromImport) {
        if (!PurpleAccountOwner.legacyEditorAllowed(UserConfig.selectedAccount)) return false;
        try { PurpleCore.ParseResult parsed = PurpleCore.parse(bytes); if (!parsed.ok || parsed.version > 1) return false; }
        catch (RuntimeException | UnsatisfiedLinkError e) { return false; }
        final File target = settingsFile();
        try {
            PurpleSettingsBackups.take(target, backupFile(), importBackupFile(), fromImport);
        } catch (IOException e) {
            FileLog.e(e);
            return false;
        }
        if (!writeAtomic(target, bytes)) {
            return false;
        }
        // The file the gate reads has just changed under it, so resolve again
        // rather than leave the new settings waiting for the next restart.
        PurpleGate.reload(reason);
        return true;
    }

    /**
     * Writes {@code bytes} to {@code target} through a sibling ".tmp" and a
     * rename, so an interrupted write leaves the previous file behind rather
     * than half of a new one.
     *
     * Shared with {@link PurpleState} rather than copied into it because both
     * files are read by the native core, which has no way to tell a truncated
     * file from a badly written one. Nothing sets a mode here: everything under
     * the app's files directory is already private to the app.
     *
     * @return whether the replacement went through
     */
    static boolean writeAtomic(File target, byte[] bytes) {
        final File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            final OutputStream out = new FileOutputStream(temp);
            try {
                out.write(bytes);
                out.flush();
            } finally {
                out.close();
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            temp.delete();
            return false;
        }
    }

    /** The whole file, for the callers that hand it straight to the core. */
    static byte[] readAll(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void showError(Context context, CharSequence text) {
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ImportPurpleSettings));
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.show();
    }
}
