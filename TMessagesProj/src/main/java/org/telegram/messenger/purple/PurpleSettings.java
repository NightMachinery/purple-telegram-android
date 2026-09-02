package org.telegram.messenger.purple;

import android.content.Context;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
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
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public static final String FILE_NAME = "settings.toml";
    public static final String BACKUP_NAME = "settings.toml.bak";

    /** Largest settings.toml we are willing to look at. */
    public static final long MAX_SIZE = 64 * 1024;

    private PurpleSettings() {
    }

    public static File dir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "purple");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File settingsFile() {
        return new File(dir(), FILE_NAME);
    }

    public static File backupFile() {
        return new File(dir(), BACKUP_NAME);
    }

    public static void importFrom(Context context, File source, int messageDate) {
        if (context == null || source == null || !source.exists()) {
            return;
        }
        if (source.length() > MAX_SIZE) {
            showError(context, LocaleController.getString(R.string.PurpleImportFailed));
            return;
        }

        final byte[] bytes;
        try {
            bytes = readAll(source);
        } catch (IOException e) {
            FileLog.e(e);
            showError(context, LocaleController.getString(R.string.PurpleImportFailed));
            return;
        }

        final PurpleCore.ParseResult parsed;
        try {
            parsed = PurpleCore.parse(bytes);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            showError(context, LocaleController.getString(R.string.PurpleCoreUnavailable));
            return;
        }
        final boolean ok = parsed.ok;
        final int version = parsed.version;
        final String error = parsed.error;
        final int warnings = parsed.warnings.size();
        if (!ok) {
            showError(context, LocaleController.formatString(R.string.PurpleSettingsInvalid, error));
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append(LocaleController.formatString(R.string.PurpleImportQuestion, LocaleController.formatDateTime(messageDate, true)));
        message.append("\n\n");
        message.append(LocaleController.formatString(R.string.PurpleImportSchema, version));
        if (version > SUPPORTED_SCHEMA_VERSION) {
            message.append(" ");
            message.append(LocaleController.getString(R.string.PurpleImportSchemaNewer));
        }
        message.append("\n");
        if (warnings == 0) {
            message.append(LocaleController.getString(R.string.PurpleImportNoWarnings));
        } else if (warnings == 1) {
            message.append(LocaleController.getString(R.string.PurpleImportOneWarning));
        } else {
            message.append(LocaleController.formatString(R.string.PurpleImportManyWarnings, warnings));
        }
        message.append("\n\n");
        message.append(LocaleController.getString(R.string.PurpleImportReplaces));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.ImportPurpleSettings));
        builder.setMessage(message.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.Import), (dialog, which) -> {
            if (store(bytes)) {
                Toast.makeText(context, LocaleController.getString(R.string.PurpleSettingsImported), Toast.LENGTH_SHORT).show();
            } else {
                showError(context, LocaleController.getString(R.string.PurpleImportFailed));
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    /** Backs the current file up, then atomically replaces it with {@code bytes}. */
    private static boolean store(byte[] bytes) {
        final File target = settingsFile();
        try {
            if (target.exists()) {
                copy(target, backupFile());
            }
        } catch (IOException e) {
            FileLog.e(e);
            return false;
        }
        if (!writeAtomic(target, bytes)) {
            return false;
        }
        // The file the gate reads has just changed under it, so resolve again
        // rather than leave the imported settings waiting for the next restart.
        PurpleGate.reload("import");
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

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            in.close();
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
