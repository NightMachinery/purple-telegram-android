package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class PurplePlaylistsEditsCases {
    private static int checks;
    private static void check(boolean value, String name) { ++checks; if (!value) throw new AssertionError(name); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject json(byte[] value) throws Exception { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
    private static final String HIGH = "18446744073709551615";
    private static final class Session implements AutoCloseable {
        final PurplePlaylistsEdits editor = new PurplePlaylistsEdits(101);
        byte[] library = bytes("{\"format\":1,\"playlists\":{},\"purged\":{}}");
        byte[] state = PurplePlaylistsLocalCore.defaults(101).canonicalBytes();
        PurplePlaylistsEdits.Result prepare(PurplePlaylistsEdits.Command command) { return editor.prepare(library, state, command); }
        PurplePlaylistsEdits.Result apply(PurplePlaylistsEdits.Command command) {
            PurplePlaylistsEdits.Result result = prepare(command);
            check(result.valid(), "shared typed edit " + command.kind + " succeeded: " + result.error + ":" + result.localError);
            editor.finish(true, true, false); library = result.library(); state = result.state(); return result;
        }
        String create(String title, int entropy, long now) {
            byte[] seed = new byte[10]; Arrays.fill(seed, (byte) entropy);
            return apply(PurplePlaylistsEdits.Command.create(title, seed, now)).playlistId;
        }
        @Override public void close() { editor.close(); }
    }
    private static PurplePlaylistsEdits.Song song(String id, int message) {
        return new PurplePlaylistsEdits.Song(id, "user:101", message, "", "曲 🎵", "演奏者", 23);
    }
    private static void operations() throws Exception {
        try (Session s = new Session()) {
            String id = s.create("音楽 🎧", 1, 100);
            JSONObject playlist = json(s.library).getJSONObject("playlists").getJSONObject(id);
            check(playlist.toString().contains("音楽 🎧"), "UTF8 title survives real core serialization");
            check(json(s.state).getLong("unsynced_since") == 100, "create marks earliest unsynced age");
            check(json(s.state).getJSONObject("recent_use").getLong(id) == 100, "create records recent use");
            PurplePlaylistsEdits.Result added = s.apply(PurplePlaylistsEdits.Command.add(id,
                    new PurplePlaylistsEdits.Song[] { song(HIGH, 9), song("7", 10) }, 200));
            check(added.added == 2 && added.relinked == 0 && added.removed == 0, "actual core edit counts preserved");
            check(new String(s.library, StandardCharsets.UTF_8).contains(HIGH), "unsigned document id above Java long survives");
            check(json(s.state).getLong("unsynced_since") == 100, "later edit retains earliest unsynced age");
            check(json(s.state).getJSONObject("recent_use").getLong(id) == 200, "add updates recent use");
            byte[] previous = s.library.clone();
            PurplePlaylistsEdits.Result noop = s.apply(PurplePlaylistsEdits.Command.add(id,
                    new PurplePlaylistsEdits.Song[] { song(HIGH, 9) }, 300));
            check(!noop.changed && noop.stateChanged && noop.unchanged == 1, "noop add records use without library edit");
            check(Arrays.equals(previous, s.library), "noop add leaves library bytes identical");
            check(json(s.state).getJSONObject("recent_use").getLong(id) == 300, "noop add advances recent use");
            s.apply(PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.ReorderSongs, id, new String[] { "7", HIGH }, 400));
            check(new String(s.library, StandardCharsets.UTF_8).contains("[\"7\",\"" + HIGH + "\"]"), "real core reorders high IDs");
            s.apply(PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.RemoveSongs, id, new String[] { HIGH }, 500));
            s.apply(PurplePlaylistsEdits.Command.undo(600));
            check(new String(s.library, StandardCharsets.UTF_8).contains(HIGH), "structured Undo restores removed high ID");
            s.apply(PurplePlaylistsEdits.Command.rename(id, "Renamed", 700));
            s.apply(PurplePlaylistsEdits.Command.undo(800));
            check(new String(s.library, StandardCharsets.UTF_8).contains("音楽 🎧"), "Undo restores prior Unicode title");
            s.apply(PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete, id, 900));
            s.apply(PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Restore, id, 1000));
            PurplePlaylistsEdits.Result copy = s.apply(PurplePlaylistsEdits.Command.copy(s.library, id, 1100));
            check(!copy.playlistId.equals(id) && copy.added == 2, "Copy uses validated actual source and distinct entropy ID");
            check(new String(s.library, StandardCharsets.UTF_8).contains(" (copy)"), "shared Copy title policy preserved");
            s.apply(PurplePlaylistsEdits.Command.undo(1200));
            check(!json(s.library).getJSONObject("playlists").has(copy.playlistId), "Undo Copy removes untouched copied playlist");
            s.apply(PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete, id, 1300));
            s.apply(PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.DeleteForever, id, 1400));
            check(json(s.library).getJSONObject("purged").has(id), "DeleteForever uses real purge marker");
            check("NothingToUndo".equals(s.prepare(PurplePlaylistsEdits.Command.undo(1500)).error), "purge grants no invented Undo");
        }
    }
    private static void refusals() throws Exception {
        try (Session s = new Session()) {
            String id = s.create("Valid", 2, 100);
            byte[] original = s.library.clone();
            check("InvalidTitle".equals(s.prepare(PurplePlaylistsEdits.Command.rename(id, "", 200)).error), "typed title error mapped");
            check("InvalidId".equals(s.prepare(PurplePlaylistsEdits.Command.rename("bad", "x", 200)).error), "typed ID error mapped");
            check("InvalidStamp".equals(s.prepare(PurplePlaylistsEdits.Command.rename(id, "x", 0)).error), "zero time refused");
            check("InvalidStamp".equals(s.prepare(PurplePlaylistsEdits.Command.rename(id, "x", Long.MAX_VALUE)).error), "out of core stamp range refused");
            check("InvalidEntropy".equals(s.prepare(PurplePlaylistsEdits.Command.create("x", new byte[9], 200)).error), "wrong entropy refused");
            check("IdCollision".equals(s.prepare(PurplePlaylistsEdits.Command.create("x", new byte[] {2,2,2,2,2,2,2,2,2,2}, 200)).error), "ID collision preserved");
            check("InvalidDocumentId".equals(s.prepare(PurplePlaylistsEdits.Command.add(id,
                    new PurplePlaylistsEdits.Song[] { song("18446744073709551616", 1) }, 200)).error), "uint64 overflow refused");
            check("InvalidDocumentId".equals(s.prepare(PurplePlaylistsEdits.Command.add(id,
                    new PurplePlaylistsEdits.Song[] { song("0", 1) }, 200)).error), "zero doc refused");
            check("DuplicateDocumentId".equals(s.prepare(PurplePlaylistsEdits.Command.add(id,
                    new PurplePlaylistsEdits.Song[] { song("7", 1), song("7", 2) }, 200)).error), "duplicate song input refused by core");
            check("InvalidPeerToken".equals(s.prepare(PurplePlaylistsEdits.Command.add(id,
                    new PurplePlaylistsEdits.Song[] { new PurplePlaylistsEdits.Song("7", "bad", 1, "", "", "", 0) }, 200)).error), "bad peer refused");
            s.library = bytes("{\"format\":2}");
            PurplePlaylistsEdits.Result newer = s.prepare(PurplePlaylistsEdits.Command.rename(id, "x", 200));
            check("NewerFormat".equals(newer.error) && newer.library() == null && newer.state() == null, "newer library grants no replacement bytes");
            s.library = bytes("bad");
            check("InvalidLibrary".equals(s.prepare(PurplePlaylistsEdits.Command.rename(id, "x", 200)).error), "malformed library refused");
            s.library = original; s.state = bytes("{\"format\":2}");
            check("NewerFormat".equals(s.prepare(PurplePlaylistsEdits.Command.rename(id, "x", 200)).localError), "newer local state refused");
            s.state = bytes("bad");
            check(!s.prepare(PurplePlaylistsEdits.Command.rename(id, "x", 200)).valid(), "invalid local state refused");
        }
        boolean rejected = false;
        try { PurplePlaylistsEdits.Command.create("\uD800", 1); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "unpaired UTF16 rejected instead of lossy title replacement");
    }
    private static void publicationAndOwnership() throws Exception {
        try (Session s = new Session(); Session foreign = new Session()) {
            JSONObject local = json(s.state); local.put("future_flag", new JSONObject().put("v", 7));
            s.state = bytes(local.toString());
            String id = s.create("First", 3, 100);
            check(json(s.state).getJSONObject("future_flag").getInt("v") == 7, "unknown local members preserved through edit");
            PurplePlaylistsEdits.Result pending = s.prepare(PurplePlaylistsEdits.Command.rename(id, "Partial", 200));
            check(pending.valid() && s.editor.finish(true, false, false), "confirmed library with failed checkpoint retains Undo");
            s.library = pending.library();
            local = json(s.state); local.remove("unsynced_since"); s.state = bytes(local.toString());
            PurplePlaylistsEdits.Result repair = s.prepare(PurplePlaylistsEdits.Command.rename(id, "Partial", 900));
            check(!repair.changed && repair.stateChanged && json(repair.state()).getLong("unsynced_since") == 200,
                    "failed checkpoint age retained for next valid edit, not reset to retry time");
            s.editor.finish(true, true, false); s.state = repair.state();
            s.apply(PurplePlaylistsEdits.Command.undo(1000));
            check(new String(s.library, StandardCharsets.UTF_8).contains("First"), "checkpoint repair does not replace pending edit Undo");
            s.apply(PurplePlaylistsEdits.Command.rename(id, "Confirmed", 1050));
            pending = s.prepare(PurplePlaylistsEdits.Command.rename(id, "Unconfirmed", 1100));
            check(pending.valid(), "candidate produced before publication");
            s.editor.finish(false, false, true);
            check("NothingToUndo".equals(s.prepare(PurplePlaylistsEdits.Command.undo(1200)).error), "uncertain library publication revokes Undo");
            check("NothingToUndo".equals(foreign.prepare(PurplePlaylistsEdits.Command.undo(1200)).error), "editor session cannot borrow foreign Undo");
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread wrong = new Thread(() -> { try { s.prepare(PurplePlaylistsEdits.Command.rename(id, "Wrong", 1200)); }
                catch (Throwable e) { error.set(e); } });
            wrong.start(); wrong.join();
            check(error.get() instanceof IllegalStateException, "wrong thread cannot use editor");
            java.lang.reflect.Field field = PurplePlaylistsEdits.class.getDeclaredField("handle");
            field.setAccessible(true); long nativeHandle = field.getLong(s.editor);
            java.lang.reflect.Method forget = PurplePlaylistsEdits.class.getDeclaredMethod("forgetNative", long.class);
            forget.setAccessible(true);
            error.set(null);
            Thread nativeWrong = new Thread(() -> { try { forget.invoke(null, nativeHandle); }
                catch (java.lang.reflect.InvocationTargetException e) { error.set(e.getCause()); }
                catch (Throwable e) { error.set(e); } });
            nativeWrong.start(); nativeWrong.join();
            check(error.get() instanceof IllegalStateException, "native creator-thread registry independently refuses foreign thread");
            s.editor.close(); s.editor.close();
            boolean removed = false;
            try { forget.invoke(null, nativeHandle); }
            catch (java.lang.reflect.InvocationTargetException e) { removed = e.getCause() instanceof IllegalStateException; }
            check(removed, "creator-thread close removes native Undo registry entry");
            boolean closed = false;
            try { s.prepare(PurplePlaylistsEdits.Command.rename(id, "Closed", 1200)); } catch (IllegalStateException e) { closed = true; }
            check(closed, "closed native editor cannot be reused");
        }
        try (Session s = new Session()) {
            String id = s.create("Unpublished", 5, 100);
            check(s.prepare(PurplePlaylistsEdits.Command.rename(id, "Never published", 200)).valid(), "unconfirmed candidate prepared");
            check(s.editor.finish(false, false, false), "refused publication retains prior confirmed Undo");
            s.apply(PurplePlaylistsEdits.Command.undo(300));
            check(json(s.library).getJSONObject("purged").has(id), "unconfirmed edit cannot replace confirmed Undo");
        }
        try (Session s = new Session(); Session external = new Session()) {
            String id = s.create("Conflict", 4, 100);
            external.library = s.library;
            PurplePlaylistsEdits.Result changed = external.apply(PurplePlaylistsEdits.Command.rename(id, "External", 200));
            s.library = changed.library();
            check("UndoConflict".equals(s.prepare(PurplePlaylistsEdits.Command.undo(300)).error), "shared Undo conflict refuses externally edited create");
        }
    }
    public static void main(String[] args) throws Exception {
        operations(); refusals(); publicationAndOwnership();
        System.out.println("PLAYLIST_EDIT_CHECKS=" + checks);
    }
}
