package org.telegram.messenger.purple;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;

public final class PurplePlaylistsMediaCases {
    private static int checks;
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C = "cccccccccccccccccccccccccccccccc";
    private static final PurplePlaylistsStore.Current YES = () -> true;
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
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    private static final class Fixture {
        final Path anchor = Files.createTempDirectory("purple-media-jni-");
        final Path directory = anchor.resolve("account/playlists");
        boolean current = true;
        Fixture() throws Exception { Files.setPosixFilePermissions(anchor, PosixFilePermissions.fromString("rwx------")); }
        PurplePlaylistsPhysical open() throws Exception {
            return new PurplePlaylistsPhysical(anchor.toString(), "account/playlists", () -> current, true);
        }
        Path part(String token) { return directory.resolve("files/.incoming/" + token + ".part"); }
        Path target(long document) { return directory.resolve("files/" + document + ".mp3"); }
    }
    private static PurplePlaylistsPhysical.MediaFile full(PurplePlaylistsPhysical p, String token, byte[] bytes)
            throws Exception {
        PurplePlaylistsPhysical.MediaFile start = p.beginMedia(token, bytes.length, "", YES);
        p.appendMedia(token, start.identity, 0, bytes, YES);
        return p.sealMedia(token, start.identity, YES);
    }
    private static void roundtrip() throws Exception {
        Fixture f = new Fixture(); byte[] content = bytes("kept music \u0645\u0648\u0633\u06cc\u0642\u06cc");
        PurplePlaylistsPhysical.MediaFile sealed;
        try (PurplePlaylistsPhysical p = f.open()) {
            check(p.read(PurplePlaylistsStore.Name.Local).absent(), "JSON and media share the same native session");
            PurplePlaylistsPhysical.MediaFile start = p.beginMedia(A, content.length, "", YES);
            check(start.size == 0 && !start.identity.isEmpty() && start.sha256.isEmpty(), "new writer returns exact empty checkpoint");
            check(Files.exists(f.part(A)), "begin creates only owned staging file");
            check(Files.getPosixFilePermissions(f.part(A)).equals(PosixFilePermissions.fromString("rw-------")), "staging private mode");
            check(p.appendMedia(A, start.identity, 0, Arrays.copyOfRange(content, 0, 3), YES) == 3, "first chunk advances actual offset");
            check(refuses(() -> p.sealMedia(A, start.identity, YES)), "partial file cannot seal");
            check(p.appendMedia(A, start.identity, 3, Arrays.copyOfRange(content, 3, content.length), YES) == content.length,
                    "next chunk advances to full declared size");
            sealed = p.sealMedia(A, start.identity, YES);
            check(sealed.size == content.length && sealed.identity.equals(start.identity) && sealed.sha256.equals(hash(content)),
                    "seal returns actual identity size independent digest");
            check(refuses(() -> p.appendMedia(A, start.identity, content.length, bytes("x"), YES)), "sealed writer refuses more bytes");
            check(!Files.exists(f.target(123)), "seal never publishes target");
            PurplePlaylistsPhysical.MediaFile wrong = new PurplePlaylistsPhysical.MediaFile(sealed.identity, sealed.size, hash(bytes("wrong")));
            check(refuses(() -> p.publishMedia(A, 123, "mp3", wrong, YES)), "publication binds exact sealed digest across JNI");
            PurplePlaylistsPhysical.MediaFile published = p.publishMedia(A, 123, "mp3", sealed, YES);
            check(published.identity.equals(sealed.identity) && published.sha256.equals(sealed.sha256), "publish returns exact retained proof");
            check(!Files.exists(f.part(A)) && Arrays.equals(Files.readAllBytes(f.target(123)), content), "exclusive publication moves bytes once");
            check(p.inspectMedia(123, "mp3", published, YES).sha256.equals(hash(content)), "published file validates durably through JNI");
            check(refuses(() -> p.removeMedia(123, "mp3", published, () -> false)), "caller reference authority refuses deletion");
            check(Files.exists(f.target(123)), "denied removal preserves completed file");
            check(p.replace(PurplePlaylistsStore.Name.Local, p.read(PurplePlaylistsStore.Name.Local), bytes("inventory"), YES).durable,
                    "media operations preserve JSON publication on same handle");
        }
        try (PurplePlaylistsPhysical p = f.open()) {
            check(p.inspectMedia(123, "mp3", sealed, YES).size == content.length, "persisted completion proof survives session reopen");
            check(p.read(PurplePlaylistsStore.Name.Local).content(bytes("inventory")), "media reopen preserves inventory");
            p.removeMedia(123, "mp3", sealed, YES);
            check(!Files.exists(f.target(123)), "explicit proven removal crosses JNI");
            check(refuses(() -> p.inspectMedia(123, "mp3", sealed, YES)), "removed proof cannot revive file");
        }
    }
    private static void boundsAndResume() throws Exception {
        Fixture f = new Fixture(); PurplePlaylistsPhysical.MediaFile start;
        try (PurplePlaylistsPhysical p = f.open()) {
            check(refuses(() -> p.beginMedia("../outside", 3, "", YES)), "path tokens refused");
            check(refuses(() -> p.beginMedia(A, -1, "", YES)), "negative size never becomes unsigned");
            check(refuses(() -> p.beginMedia(A, 0, "", YES)), "zero size refused");
            check(refuses(() -> p.beginMedia(A, 3, null, YES)), "null checkpoint is not first use");
            check(refuses(() -> p.beginMedia(A, 3, "", null)), "missing current guard refused");
            start = p.beginMedia(A, 262145, "", YES);
            check(refuses(() -> p.appendMedia(A, start.identity, -1, bytes("x"), YES)), "negative offset refused");
            check(refuses(() -> p.appendMedia(A, start.identity, 0, null, YES)), "null chunk refused");
            check(refuses(() -> p.appendMedia(A, start.identity, 0, new byte[0], YES)), "empty chunk refused");
            check(refuses(() -> p.appendMedia(A, start.identity, 0, new byte[262145], YES)), "oversized JNI chunk refused");
            check(Files.size(f.part(A)) == 0, "invalid chunks never change file");
            check(refuses(() -> p.appendMedia(A, "0:0", 0, bytes("x"), YES)), "wrong checkpoint identity refused");
            check(p.appendMedia(A, start.identity, 0, new byte[262144], YES) == 262144, "maximum bounded chunk accepted");
            check(refuses(() -> p.appendMedia(A, start.identity, 0, bytes("x"), YES)), "duplicate offset refused");
            p.closeMedia(A);
            check(Files.size(f.part(A)) == 262144, "close releases writer without deleting bytes");
            check(refuses(() -> p.beginMedia(A, 262145, "", YES)), "first use never adopts existing partial file");
            check(refuses(() -> p.beginMedia(A, 262145, "0:0", YES)), "reopen binds persisted identity");
        }
        try (PurplePlaylistsPhysical p = f.open()) {
            PurplePlaylistsPhysical.MediaFile resumed = p.beginMedia(A, 262145, start.identity, YES);
            check(resumed.size == 262144 && resumed.identity.equals(start.identity), "partial exact identity resumes after session close");
            check(p.appendMedia(A, start.identity, 262144, bytes("x"), YES) == 262145, "resumed append never truncates");
            p.closeMedia(A);
            p.discardMedia(A, start.identity, YES);
            check(!Files.exists(f.part(A)), "exact staging discard crosses JNI");
            PurplePlaylistsPhysical.MediaFile a = p.beginMedia(A, 3, "", YES);
            PurplePlaylistsPhysical.MediaFile b = p.beginMedia(B, 3, "", YES);
            check(refuses(() -> p.beginMedia(C, 3, "", YES)), "same physical session caps active writers at two");
            p.discardMedia(A, a.identity, YES); p.discardMedia(B, b.identity, YES);
            check(!Files.exists(f.part(A)) && !Files.exists(f.part(B)), "discard frees both owned slots");
            p.beginMedia(C, 3, "", YES);
            f.current = false;
            p.closeMedia(C);
            check(Files.exists(f.part(C)), "close remains legal after route revoked and preserves file");
        }
    }
    private static void guardsAndFailures() throws Exception {
        Fixture f = new Fixture();
        try (PurplePlaylistsPhysical p = f.open()) {
            boolean[] close = {false}, nested = {false}, release = {false};
            PurplePlaylistsPhysical.MediaFile start = p.beginMedia(A, 3, "", () -> {
                try { p.close(); } catch (IOException e) { close[0] = true; }
                try { p.beginMedia(B, 3, "", YES); } catch (IOException e) { nested[0] = true; }
                try { p.closeMedia(A); } catch (IOException e) { release[0] = true; }
                return true;
            });
            check(close[0] && nested[0] && release[0], "guard reentry cannot close session release writer or start nested write");
            check(!Files.exists(f.part(B)), "nested media admission has no file effect");
            boolean[] wrongThread = {false};
            Thread thread = new Thread(() -> { try { p.closeMedia(A); } catch (IOException e) { wrongThread[0] = true; } });
            thread.start(); thread.join();
            check(wrongThread[0], "wrong thread cannot release media writer");
            RuntimeException sentinel = new IllegalStateException("guard sentinel");
            boolean exact = false;
            try { p.appendMedia(A, start.identity, 0, bytes("abc"), () -> { throw sentinel; }); }
            catch (RuntimeException e) { exact = e == sentinel; }
            check(exact && Files.size(f.part(A)) == 0, "guard exception crosses unchanged before write");
            boolean changed = false;
            try { p.appendMedia(A, start.identity, 0, bytes("abc"), () -> {
                try { return Files.size(f.part(A)) == 0; } catch (IOException e) { return false; }
            }); } catch (PurplePlaylistsPhysical.Failure failure) { changed = failure.mayHaveChanged; }
            check(changed && Files.size(f.part(A)) == 3, "post-write authority failure preserves mayHaveChanged through JNI");
            check(refuses(() -> p.appendMedia(A, start.identity, 3, bytes("x"), YES)), "failed writer retired across JNI");
            p.beginMedia(A, 3, start.identity, YES);
            PurplePlaylistsPhysical.MediaFile sealed = p.sealMedia(A, start.identity, YES);
            changed = false;
            try { p.publishMedia(A, 77, "mp3", sealed, () -> Files.exists(f.part(A))); }
            catch (PurplePlaylistsPhysical.Failure failure) { changed = failure.mayHaveChanged; }
            check(changed && Files.exists(f.target(77)) && !Files.exists(f.part(A)), "post-rename authority failure reports mutation and never restores");
            check(p.inspectMedia(77, "mp3", sealed, YES).size == 3, "published partial outcome recovers only by exact proof");
            changed = false;
            try { p.removeMedia(77, "mp3", sealed, () -> Files.exists(f.target(77))); }
            catch (PurplePlaylistsPhysical.Failure failure) { changed = failure.mayHaveChanged; }
            check(changed && !Files.exists(f.target(77)), "post-unlink authority failure reports exact mutation");
            check(p.current(), "failed operations leave physical session usable");
        }
    }
    private static void replacementAndCollision() throws Exception {
        Fixture f = new Fixture();
        try (PurplePlaylistsPhysical p = f.open()) {
            PurplePlaylistsPhysical.MediaFile a = full(p, A, bytes("aaa"));
            p.publishMedia(A, 1, "mp3", a, YES);
            PurplePlaylistsPhysical.MediaFile b = full(p, B, bytes("bbb"));
            check(refuses(() -> p.publishMedia(B, 1, "mp3", b, YES)), "existing completed target never overwritten");
            check(Arrays.equals(Files.readAllBytes(f.target(1)), bytes("aaa")) && Files.exists(f.part(B)), "collision preserves completed and partial bytes");
            check(refuses(() -> p.publishMedia(B, -1, "mp3", b, YES)), "negative document refused before unsigned cast");
            check(refuses(() -> p.publishMedia(B, 2, "../mp3", b, YES)), "extension never names another path");
            check(refuses(() -> p.publishMedia(B, 2, "mp3", null, YES)), "publication requires actual proof");
            check(refuses(() -> p.inspectMedia(1, "mp3", new PurplePlaylistsPhysical.MediaFile(a.identity, 4, a.sha256), YES)), "inspection checks exact persisted size");
            Path foreign = f.directory.resolve("replacement"); Files.write(foreign, bytes("aaa"));
            Files.setPosixFilePermissions(foreign, PosixFilePermissions.fromString("rw-------"));
            Files.move(foreign, f.target(1), StandardCopyOption.REPLACE_EXISTING);
            check(refuses(() -> p.inspectMedia(1, "mp3", a, YES)), "same-byte replacement revokes completion proof");
            check(refuses(() -> p.removeMedia(1, "mp3", a, YES)), "stale proof cannot unlink replacement");
            check(Files.exists(f.target(1)), "stale deletion retains replacement");
            p.discardMedia(B, b.identity, YES);
        }
    }
    private static Object nativeCall(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = PurplePlaylistsPhysical.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private static void nativeBoundary() throws Exception {
        Fixture f = new Fixture(); PurplePlaylistsPhysical p = f.open();
        java.lang.reflect.Field handleField = PurplePlaylistsPhysical.class.getDeclaredField("handle");
        handleField.setAccessible(true); long handle = handleField.getLong(p);
        java.lang.reflect.Constructor<?> constructor = Class.forName(PurplePlaylistsPhysical.class.getName() + "$Guard")
                .getDeclaredConstructor(PurplePlaylistsStore.Current.class);
        constructor.setAccessible(true);
        Class<?> guardType = constructor.getDeclaringClass();
        boolean[] closeRefused = {false};
        Object guard = constructor.newInstance((PurplePlaylistsStore.Current) () -> {
            try { nativeCall("closeNative", new Class<?>[] {long.class}, handle); }
            catch (IOException e) { closeRefused[0] = true; }
            catch (Exception e) { throw new RuntimeException(e); }
            return true;
        });
        Object start = null;
        try { start = nativeCall("beginMediaNative", new Class<?>[] {long.class, String.class, long.class, String.class, guardType},
                handle, A, 3L, "", guard); }
        catch (IOException e) { }
        check(start instanceof PurplePlaylistsPhysical.MediaFile && closeRefused[0] && p.current(), "native guard blocks close even bypassing Java facade");
        boolean[] wrong = {false};
        Thread thread = new Thread(() -> {
            try { nativeCall("closeMediaNative", new Class<?>[] {long.class, String.class}, handle, A); }
            catch (IOException e) { wrong[0] = true; }
            catch (Exception e) { throw new RuntimeException(e); }
        }); thread.start(); thread.join();
        check(wrong[0], "native media handle checks creator thread without facade");
        p.close();
        check(refuses(() -> nativeCall("closeMediaNative", new Class<?>[] {long.class, String.class}, handle, A)), "closed native handle cannot release or reach successor");
        check(Files.exists(f.part(A)), "session close never deletes uncompleted media");
        try (PurplePlaylistsPhysical next = f.open()) {
            check(next.current(), "closed media session releases sole account writer lock");
        }
    }
    public static void main(String[] args) throws Exception {
        roundtrip(); boundsAndResume(); guardsAndFailures(); replacementAndCollision(); nativeBoundary();
        System.out.println("PLAYLIST_MEDIA_JNI_CHECKS=" + checks);
        System.out.println("PLAYLIST_MEDIA_JNI_OK");
    }
}
