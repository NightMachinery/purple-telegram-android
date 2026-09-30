package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

public final class PurpleSettingsStagingTest {
    private static final String NAME = "settings.toml";
    private static final String LEGACY_DIRECTORY = "purple-sync";
    private static final long NOW = 1_800_000_000_000L;

    private static int checks;
    private static int failures;
    private static String section = "";
    private static File scratch;

    private PurpleSettingsStagingTest() {
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

    private static final class Dirs {
        final File base;
        final File files;
        final File cache;
        final File root;
        final File former;

        Dirs() throws IOException {
            base = Files.createTempDirectory(scratch.toPath(), "dirs").toFile();
            files = new File(base, "files");
            cache = new File(base, "cache");
            check(files.mkdirs() && cache.mkdirs());
            root = new File(files, LEGACY_DIRECTORY);
            former = new File(cache, LEGACY_DIRECTORY);
        }

        PurpleSettingsStaging staging() {
            return new PurpleSettingsStaging(NAME, files, cache);
        }
    }

    private static int count(File directory) {
        final String[] names = directory.list();
        return names != null ? names.length : -1;
    }

    private static File stage(File root, String directory, String name)
            throws IOException {
        final File parent = new File(root, directory);
        check(parent.isDirectory() || parent.mkdirs());
        final File file = new File(parent, name);
        Files.write(file.toPath(), "version = 1\n".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static File stage(File root) throws IOException {
        return stage(root, UUID.randomUUID().toString(), NAME);
    }

    private static File[] seed(File root, boolean young) throws IOException {
        final long old = NOW - PurpleSettingsStaging.MAX_AGE_MS - 1000;
        final long recent = NOW - 1000;
        check(root.mkdirs());
        final File stale = new File(root, UUID.randomUUID().toString());
        final File empty = new File(root, UUID.randomUUID().toString());
        check(stale.mkdirs() && empty.mkdirs());
        final File staleFile = new File(stale, NAME);
        Files.write(staleFile.toPath(), new byte[] { 'x' });
        check(staleFile.setLastModified(old) && stale.setLastModified(old));
        check(empty.setLastModified(old));
        if (!young) {
            return new File[] { stale, empty, null, null };
        }
        final File freshFile = stage(root);
        final File touchedFile = stage(root);
        check(freshFile.setLastModified(recent)
                && freshFile.getParentFile().setLastModified(recent));
        check(touchedFile.setLastModified(recent)
                && touchedFile.getParentFile().setLastModified(old));
        return new File[] { stale, empty, freshFile, touchedFile };
    }

    private static void testStagesInFilesDirectory() throws IOException {
        begin("stages under the external files directory");
        final Dirs dirs = new Dirs();
        final PurpleSettingsStaging staging = dirs.staging();
        final File directory = staging.newDirectory(NOW);
        check(directory != null && directory.isDirectory());
        check(directory != null && directory.getParentFile().equals(dirs.root));
        check(directory != null
                && UUID.fromString(directory.getName()).toString().equals(directory.getName()));
        check(new File(dirs.root, PurpleSettingsStaging.NO_MEDIA).isFile());
        check(!dirs.former.exists() && count(dirs.cache) == 0);
        final File second = staging.newDirectory(NOW);
        check(second != null && !second.equals(directory)
                && second.getParentFile().equals(dirs.root));
        check(new File(dirs.root, PurpleSettingsStaging.NO_MEDIA).isFile());
        check(count(dirs.cache) == 0);
    }

    private static void testNoFilesDirectory() throws IOException {
        begin("no external files directory");
        final Dirs dirs = new Dirs();
        seed(dirs.former, false);
        final PurpleSettingsStaging staging =
                new PurpleSettingsStaging(NAME, null, dirs.cache);
        check(staging.newDirectory(NOW) == null);
        check(count(dirs.cache) == 0 && count(dirs.files) == 0);

        begin("no directories at all");
        check(new PurpleSettingsStaging(NAME, null, null).newDirectory(NOW) == null);
    }

    private static void testPrune() throws IOException {
        begin("prune both roots");
        final Dirs dirs = new Dirs();
        final File[] current = seed(dirs.root, true);
        final File[] former = seed(dirs.former, true);
        final File stray = new File(dirs.root, "stray");
        Files.write(stray.toPath(), new byte[] { 'x' });
        check(stray.setLastModified(NOW - PurpleSettingsStaging.MAX_AGE_MS - 1000));
        check(dirs.staging().newDirectory(NOW) != null);
        for (File[] seeded : new File[][] { current, former }) {
            check(!seeded[0].exists() && !seeded[1].exists());
            check(seeded[2].isFile() && seeded[3].isFile());
        }
        check(dirs.former.isDirectory());
        check(stray.isFile());

        begin("prune removes the emptied cache root");
        final Dirs emptied = new Dirs();
        seed(emptied.former, false);
        final File directory = emptied.staging().newDirectory(NOW);
        check(!emptied.former.exists() && count(emptied.cache) == 0);
        check(directory != null && directory.getParentFile().equals(emptied.root));

        begin("prune keeps a directory holding something else");
        final Dirs other = new Dirs();
        final File foreign = stage(other.former, UUID.randomUUID().toString(), "other.txt");
        final long old = NOW - PurpleSettingsStaging.MAX_AGE_MS - 1000;
        check(foreign.setLastModified(old) && foreign.getParentFile().setLastModified(old));
        other.staging().newDirectory(NOW);
        check(foreign.isFile() && other.former.isDirectory());
    }

    private static void testReceiptRoots() throws IOException {
        begin("receipt accepts both roots");
        final Dirs dirs = new Dirs();
        final PurpleSettingsStaging staging = dirs.staging();
        final File current = stage(dirs.root);
        final File legacy = stage(dirs.former);
        check(dirs.root.equals(staging.rootOf(current)));
        check(staging.isCanonical(current, dirs.root));
        check(dirs.former.equals(staging.rootOf(legacy)));
        check(staging.isCanonical(legacy, dirs.former));

        begin("receipt with one directory missing");
        final PurpleSettingsStaging cacheOnly =
                new PurpleSettingsStaging(NAME, null, dirs.cache);
        check(dirs.former.equals(cacheOnly.rootOf(legacy)));
        check(cacheOnly.rootOf(current) == null);
        final PurpleSettingsStaging filesOnly =
                new PurpleSettingsStaging(NAME, dirs.files, null);
        check(dirs.root.equals(filesOnly.rootOf(current)));
        check(filesOnly.rootOf(legacy) == null);

        begin("receipt rejects other paths");
        check(staging.rootOf(stage(dirs.root, UUID.randomUUID().toString(), "other.toml")) == null);
        check(staging.rootOf(new File(dirs.root, NAME)) == null);
        check(staging.rootOf(new File(dirs.former, NAME)) == null);
        check(staging.rootOf(stage(new File(dirs.root, UUID.randomUUID().toString()),
                UUID.randomUUID().toString(), NAME)) == null);
        check(staging.rootOf(stage(new File(dirs.base, "elsewhere"))) == null);
        check(staging.rootOf(stage(new File(dirs.files, "purple-sync-records"))) == null);
        check(staging.rootOf(stage(new File(dirs.cache, "purple-sync-records"))) == null);
        check(staging.rootOf(stage(dirs.files)) == null);
        check(staging.rootOf(new File(NAME)) == null);

        begin("receipt rejects a directory that is not a canonical UUID");
        final String upper = UUID.randomUUID().toString().toUpperCase(Locale.US);
        final File shouting = stage(dirs.root, upper, NAME);
        check(dirs.root.equals(staging.rootOf(shouting)));
        check(!staging.isCanonical(shouting, dirs.root));
        final File named = stage(dirs.former, "not-a-uuid", NAME);
        check(dirs.former.equals(staging.rootOf(named)));
        boolean threw = false;
        try {
            staging.isCanonical(named, dirs.former);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw);

        begin("receipt rejects a symlinked staging directory");
        for (File root : new File[] { dirs.root, dirs.former }) {
            final String uuid = UUID.randomUUID().toString();
            final File target = stage(new File(dirs.base, "outside"), uuid, NAME);
            final File link = new File(root, uuid);
            Files.createSymbolicLink(link.toPath(), target.getParentFile().toPath());
            final File linked = new File(link, NAME);
            check(linked.isFile());
            check(root.equals(staging.rootOf(linked)));
            check(!staging.isCanonical(linked, root));
        }
    }

    private static void testRemove() throws IOException {
        begin("remove");
        final Dirs dirs = new Dirs();
        final File staged = stage(dirs.root);
        final File directory = staged.getParentFile();
        PurpleSettingsStaging.remove(staged);
        check(!staged.exists() && !directory.exists());
        check(directory.mkdirs());
        PurpleSettingsStaging.remove(new File(directory, NAME));
        check(directory.isDirectory());
    }

    public static void main(String[] args) throws Exception {
        scratch = new File(System.getProperty("scratch",
                Files.createTempDirectory("purple-settings-staging").toString()));
        check(scratch.isDirectory() || scratch.mkdirs());
        testStagesInFilesDirectory();
        testNoFilesDirectory();
        testPrune();
        testReceiptRoots();
        testRemove();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
