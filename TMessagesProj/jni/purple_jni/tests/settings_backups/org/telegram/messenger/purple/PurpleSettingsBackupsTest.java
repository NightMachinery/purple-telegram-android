package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class PurpleSettingsBackupsTest {
    private static final String CURRENT = "current = 1\n";
    private static final String OLD_BACKUP = "backup = 1\n";
    private static final String OLD_IMPORT_BACKUP = "import_backup = 1\n";

    private static int checks;
    private static int failures;
    private static String section = "";
    private static File scratch;

    private PurpleSettingsBackupsTest() {
    }

    private static void check(boolean ok) {
        ++checks;
        if (!ok) {
            ++failures;
            final StackTraceElement where = new Throwable().getStackTrace()[1];
            System.out.println("  FAIL  " + section + ":" + where.getLineNumber());
        }
    }

    private static void begin(String name) {
        section = name;
    }

    /** A settings directory holding a current file and both backups from earlier writes. */
    private static final class Dir {
        final File target;
        final File backup;
        final File importBackup;

        Dir() throws IOException {
            final File base = Files.createTempDirectory(scratch.toPath(), "purple").toFile();
            target = new File(base, "settings.toml");
            backup = new File(base, "settings.toml.bak");
            importBackup = new File(base, "settings.toml.import.bak");
            write(target, CURRENT);
            write(backup, OLD_BACKUP);
            write(importBackup, OLD_IMPORT_BACKUP);
        }

        File importTemp() {
            return new File(importBackup.getParentFile(), importBackup.getName() + ".tmp");
        }
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean holds(File file, String text) throws IOException {
        return file.isFile() && Arrays.equals(Files.readAllBytes(file.toPath()),
                text.getBytes(StandardCharsets.UTF_8));
    }

    /** A directory where a file must go, with something inside so a delete cannot clear it. */
    private static void block(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not clear " + file);
        }
        if (!file.mkdirs()) {
            throw new IOException("Could not block " + file);
        }
        write(new File(file, "keep"), "x");
    }

    private static boolean takes(Dir dir, boolean fromImport) {
        try {
            PurpleSettingsBackups.take(dir.target, dir.backup, dir.importBackup, fromImport);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void testImportTakesBoth() throws IOException {
        begin("import takes both backups");
        final Dir dir = new Dir();
        check(takes(dir, true));
        check(holds(dir.target, CURRENT));
        check(holds(dir.backup, CURRENT));
        check(holds(dir.importBackup, CURRENT));
        check(!dir.importTemp().exists());
    }

    private static void testLocalWriteTakesOne() throws IOException {
        begin("local write takes only the ordinary backup");
        final Dir dir = new Dir();
        check(takes(dir, false));
        check(holds(dir.backup, CURRENT));
        check(holds(dir.importBackup, OLD_IMPORT_BACKUP));
    }

    private static void testNoCurrentFile() throws IOException {
        begin("no current file");
        for (boolean fromImport : new boolean[] { true, false }) {
            final Dir dir = new Dir();
            check(dir.target.delete());
            check(takes(dir, fromImport));
            check(holds(dir.backup, OLD_BACKUP));
            check(holds(dir.importBackup, OLD_IMPORT_BACKUP));
        }
    }

    private static void testFailedImportTemp() throws IOException {
        begin("import backup cannot write its temporary file");
        final Dir dir = new Dir();
        block(dir.importTemp());
        check(!takes(dir, true));
        check(holds(dir.target, CURRENT));
        check(holds(dir.backup, OLD_BACKUP));
        check(holds(dir.importBackup, OLD_IMPORT_BACKUP));
    }

    private static void testFailedImportRename() throws IOException {
        begin("import backup cannot replace the previous one");
        final Dir dir = new Dir();
        block(dir.importBackup);
        check(!takes(dir, true));
        check(holds(dir.target, CURRENT));
        check(holds(dir.backup, OLD_BACKUP));
        check(!dir.importTemp().exists());
    }

    private static void testLocalWriteIgnoresImportBackup() throws IOException {
        begin("local write ignores a blocked import backup");
        final Dir dir = new Dir();
        block(dir.importTemp());
        check(takes(dir, false));
        check(holds(dir.backup, CURRENT));
        check(holds(dir.importBackup, OLD_IMPORT_BACKUP));
    }

    private static void testFailedOrdinaryBackup() throws IOException {
        begin("ordinary backup fails after the import backup");
        final Dir dir = new Dir();
        block(dir.backup);
        check(!takes(dir, true));
        check(holds(dir.target, CURRENT));
        // The import backup went first, so it already holds the current file.
        check(holds(dir.importBackup, CURRENT));
        final Dir local = new Dir();
        block(local.backup);
        check(!takes(local, false));
        check(holds(local.importBackup, OLD_IMPORT_BACKUP));
    }

    public static void main(String[] args) throws Exception {
        scratch = new File(System.getProperty("scratch",
                Files.createTempDirectory("purple-settings-backups").toString()));
        check(scratch.isDirectory() || scratch.mkdirs());
        testImportTakesBoth();
        testLocalWriteTakesOne();
        testNoCurrentFile();
        testFailedImportTemp();
        testFailedImportRename();
        testLocalWriteIgnoresImportBackup();
        testFailedOrdinaryBackup();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
