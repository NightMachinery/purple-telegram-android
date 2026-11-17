package org.telegram.messenger.purple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

public final class PurplePlaylistsPhysicalCases {
    private static int checks;
    private static final PurplePlaylistsStore.Name LIBRARY = PurplePlaylistsStore.Name.Library;
    private static final PurplePlaylistsStore.Name LOCAL = PurplePlaylistsStore.Name.Local;
    private static final PurplePlaylistsStore.Name BACKUP = PurplePlaylistsStore.Name.Backup;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String reason) {
        ++checks;
        if (!value) throw new AssertionError(reason);
    }
    private static boolean refuses(Action action) throws Exception {
        try { action.run(); return false; }
        catch (IOException e) { return true; }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static final class Fixture {
        final Path anchor = Files.createTempDirectory("purple-playlists-\u0645\u0648\u0633\u06cc\u0642\u06cc-");
        final Path account = Files.createDirectory(anchor.resolve("account"));
        final Path directory = account.resolve("playlists");
        boolean current = true;
        Fixture() throws Exception {
            Files.setPosixFilePermissions(anchor, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(account, PosixFilePermissions.fromString("rwx------"));
        }
        PurplePlaylistsPhysical open() throws Exception {
            return new PurplePlaylistsPhysical(anchor.toString(), "account/playlists", () -> current);
        }
    }

    private static void snapshotsAndPublication() throws Exception {
        Fixture fixture = new Fixture();
        try (PurplePlaylistsPhysical physical = fixture.open()) {
            check(physical.current(), "opened route current");
            PurplePlaylistsStore.File missing = physical.read(LIBRARY);
            check(missing.absent() && !Files.exists(fixture.directory.resolve("library.json")), "native absence never writes defaults");
            PurplePlaylistsStore.Publication written = physical.replace(LIBRARY, missing, bytes("\u0645\u0648\u0633\u06cc\u0642\u06cc"), () -> true);
            check(written.durable && written.file.content(bytes("\u0645\u0648\u0633\u06cc\u0642\u06cc")), "UTF8 publication and byte-exact native return");
            check(physical.matches(LIBRARY, written.file), "returned publication matches native inode and bytes");
            check(refuses(() -> physical.replace(LIBRARY, missing, bytes("stale"), () -> true)), "stale native token cannot replace target");
            PurplePlaylistsStore.File current = physical.read(LIBRARY);
            check(physical.matches(LIBRARY, current), "read after failed publication recovers exact current snapshot");
            byte[] exposed = current.bytes(); exposed[0] = 0;
            check(current.content(bytes("\u0645\u0648\u0633\u06cc\u0642\u06cc")), "facade exposes no mutable native snapshot bytes");
            PurplePlaylistsStore.File forged = new PurplePlaylistsStore.File(current.token, bytes("forged"));
            check(refuses(() -> physical.matches(LIBRARY, forged)), "native receipt cannot be attached to different bytes");
            check(refuses(() -> physical.matches(LOCAL, current)), "receipt binds target name");
            check(physical.confirm(LIBRARY, current, () -> true).durable, "existing file durability confirmed through JNI");
            check(refuses(() -> physical.confirm(LOCAL, physical.read(LOCAL), () -> true)), "missing library cannot acquire durability confirmation");
            PurplePlaylistsStore.File backup = physical.read(BACKUP);
            check(physical.replace(BACKUP, backup, current.bytes(), () -> true).file.content(current.bytes()), "backup preserves raw bytes");
            check(physical.replace(LOCAL, physical.read(LOCAL), bytes("state"),
                    () -> physical.current()).durable, "route guard can check current during native publication");
            check(physical.matches(LIBRARY, current), "local publication leaves library snapshot intact");
            check(refuses(() -> physical.replace(LOCAL, physical.read(LOCAL), null, () -> true)), "null write is not an empty file");
            check(physical.replace(LOCAL, physical.read(LOCAL), new byte[0], () -> true).file.content(new byte[0]), "empty bytes remain distinct from absence");
        }
        try (PurplePlaylistsPhysical reopened = fixture.open()) {
            check(reopened.read(LIBRARY).content(bytes("\u0645\u0648\u0633\u06cc\u0642\u06cc")), "closed native session releases writer lock and preserves files");
        }
    }

    private static void identitiesAndExceptions() throws Exception {
        Fixture first = new Fixture(), second = new Fixture();
        PurplePlaylistsPhysical physical = first.open();
        try (PurplePlaylistsPhysical other = second.open()) {
            check(refuses(() -> first.open()), "same-process competing native open refused");
            PurplePlaylistsStore.File foreign = other.read(LIBRARY);
            check(refuses(() -> physical.replace(LIBRARY, foreign, bytes("foreign"), () -> true)), "another physical session cannot lend receipt authority");
            PurplePlaylistsStore.File absent = physical.read(LIBRARY);
            RuntimeException sentinel = new IllegalStateException("guard sentinel");
            boolean exact = false;
            int[] calls = new int[1];
            try { physical.replace(LIBRARY, absent, bytes("denied"), () -> {
                if (++calls[0] > 1) throw sentinel;
                return true;
            }); }
            catch (RuntimeException failure) { exact = failure == sentinel; }
            check(exact && calls[0] > 1, "Java callback exception survives native boundary unchanged");
            check(!Files.exists(first.directory.resolve("library.json")), "throwing guard has no publication");
            PurplePlaylistsStore.File fresh = physical.read(LIBRARY);
            check(physical.replace(LIBRARY, fresh, bytes("healthy"), () -> true).durable, "callback exception does not poison native handle");
            boolean[] rejected = new boolean[1];
            Thread thread = new Thread(() -> {
                try { physical.read(LIBRARY); }
                catch (IOException e) { rejected[0] = true; }
            });
            thread.start(); thread.join();
            check(rejected[0], "wrong Java thread cannot access native session");
            first.current = false;
            check(!physical.current() && refuses(() -> physical.read(LIBRARY)), "account route revocation refuses reads");
        } finally { physical.close(); }
        check(!physical.current(), "closed handle never reports current");
        check(refuses(() -> physical.read(LIBRARY)), "closed handle cannot read");
        physical.close();
        first.current = true;
        try (PurplePlaylistsPhysical reopened = first.open()) {
            check(reopened.current(), "failed operations and repeated close release one session");
        }
    }

    private static void staleNativeSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        try (PurplePlaylistsPhysical physical = fixture.open()) {
            PurplePlaylistsStore.File current = physical.replace(LIBRARY, physical.read(LIBRARY), bytes("first"), () -> true).file;
            Path replacement = fixture.directory.resolve("replacement");
            Files.write(replacement, bytes("second"));
            Files.setPosixFilePermissions(replacement, PosixFilePermissions.fromString("rw-------"));
            Files.move(replacement, fixture.directory.resolve("library.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            check(!physical.matches(LIBRARY, current), "same byte path with replaced inode revokes old snapshot");
            check(refuses(() -> physical.replace(LIBRARY, current, bytes("stale"), () -> true)), "native CAS checks real current bytes and identity");
            check(Arrays.equals(Files.readAllBytes(fixture.directory.resolve("library.json")), bytes("second")), "stale snapshot preserves foreign replacement");
            PurplePlaylistsStore.File latest = physical.read(LIBRARY);
            check(latest.content(bytes("second")) && physical.matches(LIBRARY, latest), "new snapshot captures replacement");
        }
    }

    private static void reentryAndLargeFiles() throws Exception {
        Fixture fixture = new Fixture();
        try (PurplePlaylistsPhysical physical = fixture.open()) {
            boolean[] closeRefused = new boolean[1], writeRefused = new boolean[1];
            PurplePlaylistsStore.File missing = physical.read(LIBRARY);
            PurplePlaylistsStore.Publication first = physical.replace(LIBRARY, missing, bytes("first"), () -> {
                try { physical.close(); }
                catch (IOException e) { closeRefused[0] = true; }
                try { physical.replace(LOCAL, physical.read(LOCAL), bytes("reentrant"), () -> true); }
                catch (IOException e) { writeRefused[0] = true; }
                return true;
            });
            check(first.durable && closeRefused[0] && writeRefused[0], "reentrant close and write refused while outer publication succeeds");
            check(!Files.exists(fixture.directory.resolve("local.json")), "nested write has no target effect");
            byte[] large = new byte[25 * 1024 * 1024]; Arrays.fill(large, (byte) 'x');
            PurplePlaylistsStore.Publication saved = physical.replace(LIBRARY, physical.read(LIBRARY), large, () -> true);
            check(saved.durable && saved.file.content(large), "native JNI storage does not inherit 4MiB sync cap");
            check(physical.read(LIBRARY).content(large), "large snapshot roundtrip");
            check(physical.confirm(LIBRARY, physical.read(LIBRARY), () -> true).durable, "large unchanged library can be confirmed");
        }
    }

    private static void firstUseParents() throws Exception {
        Path anchor = Files.createTempDirectory("purple-playlists-first-use-");
        Files.setPosixFilePermissions(anchor, PosixFilePermissions.fromString("rwx------"));
        String relative = "accounts/0-101/playlists";
        check(refuses(() -> new PurplePlaylistsPhysical(anchor.toString(), relative, () -> true)),
                "default open never creates absent account parents");
        check(!Files.exists(anchor.resolve("accounts")), "refused default leaves parents absent");
        PurplePlaylistsPhysical created = null;
        try { created = new PurplePlaylistsPhysical(anchor.toString(), relative, () -> true, true); }
        catch (IOException e) { }
        check(created != null, "explicit first-use parent creation crosses JNI");
        try (PurplePlaylistsPhysical physical = created) {
            check(physical.current() && physical.read(LIBRARY).absent(), "explicit first-use parent creation crosses JNI");
            check(!Files.exists(anchor.resolve(relative).resolve("library.json")), "first-use open never materializes defaults");
            for (String part : new String[] {"accounts", "accounts/0-101", relative}) {
                check(Files.getPosixFilePermissions(anchor.resolve(part)).equals(PosixFilePermissions.fromString("rwx------")),
                        "created account ancestor is private");
            }
        }
        try (PurplePlaylistsPhysical reopened = new PurplePlaylistsPhysical(anchor.toString(), relative, () -> true)) {
            check(reopened.current(), "explicit first use permits later existing-parent open");
        }
        Path unsafe = Files.createTempDirectory("purple-playlists-unsafe-parent-");
        Files.setPosixFilePermissions(unsafe, PosixFilePermissions.fromString("rwx------"));
        Path shared = Files.createDirectory(unsafe.resolve("accounts"));
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"));
        check(refuses(() -> new PurplePlaylistsPhysical(unsafe.toString(), relative, () -> true, true)),
                "explicit creation never repairs foreign permissive ancestor");
        check(!Files.exists(shared.resolve("0-101")), "unsafe ancestor receives no child");
    }

    public static void main(String[] args) throws Exception {
        snapshotsAndPublication(); identitiesAndExceptions(); staleNativeSnapshot(); reentryAndLargeFiles(); firstUseParents();
        System.out.println("PLAYLIST_PHYSICAL_JNI_CHECKS=" + checks);
        System.out.println("PLAYLIST_PHYSICAL_JNI_OK");
    }
}
