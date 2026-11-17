package org.telegram.messenger.purple;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

@Keep
final class PurplePlaylistsEdits implements AutoCloseable {
    enum Kind { Create, Copy, Rename, Delete, Restore, DeleteForever, AddSongs, RemoveSongs, ReorderSongs, Undo }

    static final class Song {
        private final byte[][] text;
        private final int message, duration;
        Song(String documentId, String peer, int message, String username,
                String title, String performer, int duration) {
            text = new byte[][] { utf8(documentId), utf8(peer), utf8(username), utf8(title), utf8(performer) };
            this.message = message;
            this.duration = duration;
        }
    }

    static final class Command {
        final Kind kind;
        final long now;
        private final boolean currentCopy;
        private final byte[] id, title, entropy, source, sourceId;
        private final byte[][] ids, songs;
        private final int[] numbers;
        private Command(Kind kind, String id, String title, byte[] entropy, byte[] source, String sourceId,
                String[] ids, Song[] songs, long now) {
            this(kind, id, title, entropy, source, sourceId, ids, songs, now, false);
        }
        private Command(Kind kind, String id, String title, byte[] entropy, byte[] source, String sourceId,
                String[] ids, Song[] songs, long now, boolean currentCopy) {
            this.currentCopy = currentCopy;
            this.kind = kind; this.now = now;
            this.id = utf8(id); this.title = utf8(title); this.sourceId = utf8(sourceId);
            this.entropy = entropy == null ? new byte[0] : entropy.clone();
            this.source = source == null ? new byte[0] : source.clone();
            this.ids = new byte[ids == null ? 0 : ids.length][];
            for (int i = 0; i < this.ids.length; ++i) this.ids[i] = utf8(ids[i]);
            int count = songs == null ? 0 : songs.length;
            this.songs = new byte[Math.multiplyExact(count, 5)][];
            numbers = new int[Math.multiplyExact(count, 2)];
            for (int i = 0; i < count; ++i) {
                if (songs[i] == null) throw new IllegalArgumentException("Missing playlist song");
                for (int j = 0; j < 5; ++j) this.songs[i * 5 + j] = songs[i].text[j].clone();
                numbers[i * 2] = songs[i].message; numbers[i * 2 + 1] = songs[i].duration;
            }
        }
        static Command create(String title, long now) {
            byte[] entropy = new byte[10]; new SecureRandom().nextBytes(entropy);
            return create(title, entropy, now);
        }
        static Command create(String title, byte[] entropy, long now) {
            return new Command(Kind.Create, "", title, entropy, null, "", null, null, now);
        }
        static Command copy(byte[] source, String sourceId, long now) {
            byte[] entropy = new byte[10]; new SecureRandom().nextBytes(entropy);
            return new Command(Kind.Copy, "", "", entropy, source, sourceId, null, null, now);
        }
        static Command copyCurrent(String sourceId, long now) {
            byte[] entropy = new byte[10]; new SecureRandom().nextBytes(entropy);
            return new Command(Kind.Copy, "", "", entropy, null, sourceId, null, null, now, true);
        }
        static Command rename(String id, String title, long now) {
            return new Command(Kind.Rename, id, title, null, null, "", null, null, now);
        }
        static Command playlist(Kind kind, String id, long now) {
            if (kind != Kind.Delete && kind != Kind.Restore && kind != Kind.DeleteForever)
                throw new IllegalArgumentException("Not a playlist action");
            return new Command(kind, id, "", null, null, "", null, null, now);
        }
        static Command add(String id, Song[] songs, long now) {
            return new Command(Kind.AddSongs, id, "", null, null, "", null, songs, now);
        }
        static Command songs(Kind kind, String id, String[] ids, long now) {
            if (kind != Kind.RemoveSongs && kind != Kind.ReorderSongs)
                throw new IllegalArgumentException("Not a song action");
            return new Command(kind, id, "", null, null, "", ids, null, now);
        }
        static Command undo(long now) {
            return new Command(Kind.Undo, "", "", null, null, "", null, null, now);
        }
    }

    @Keep
    static final class Result {
        final String error, localError, playlistId;
        final boolean changed, stateChanged;
        final int added, relinked, removed, unchanged;
        private final byte[] library, state;
        private Result(String error, String localError, byte[] id, boolean changed, boolean stateChanged,
                byte[] library, byte[] state, int[] counts) {
            this.error = error; this.localError = localError;
            playlistId = new String(id, StandardCharsets.UTF_8);
            this.changed = changed; this.stateChanged = stateChanged;
            this.library = library; this.state = state;
            added = counts[0]; relinked = counts[1]; removed = counts[2]; unchanged = counts[3];
            if (valid() != (library != null && state != null))
                throw new IllegalStateException("Invalid typed playlist edit result");
        }
        boolean valid() { return "None".equals(error) && "None".equals(localError); }
        byte[] library() { return library == null ? null : library.clone(); }
        byte[] state() { return state == null ? null : state.clone(); }
    }

    private final Thread thread = Thread.currentThread();
    private long handle;
    PurplePlaylistsEdits(long selfUserId) {
        PurpleCore.ensureLoaded();
        handle = openNative(selfUserId);
        if (handle == 0) throw new IllegalStateException("Missing playlist editor");
    }
    private void require() {
        if (Thread.currentThread() != thread || handle == 0)
            throw new IllegalStateException("Playlist editor unavailable");
    }
    Result prepare(byte[] library, byte[] state, Command command) {
        require();
        if (command == null) throw new IllegalArgumentException("Missing playlist edit");
        Result result = prepareNative(handle, library, state, command.kind.ordinal(), command.id, command.title,
                command.entropy, command.currentCopy ? library : command.source, command.sourceId, command.ids, command.songs, command.numbers, command.now);
        if (result == null) throw new IllegalStateException("Missing typed playlist result");
        return result;
    }
    boolean finish(boolean libraryConfirmed, boolean stateConfirmed, boolean uncertain) {
        require(); return finishNative(handle, libraryConfirmed, stateConfirmed, uncertain);
    }
    void forgetUndo() { require(); forgetNative(handle); }
    @Override public void close() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Wrong playlist editor thread");
        if (handle != 0) { closeNative(handle); handle = 0; }
    }
    private static byte[] utf8(String value) {
        if (value == null) throw new IllegalArgumentException("Missing playlist text");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid playlist text", e); }
    }
    private static native long openNative(long selfUserId);
    private static native Result prepareNative(long handle, byte[] library, byte[] state, int kind,
            byte[] id, byte[] title, byte[] entropy, byte[] source, byte[] sourceId,
            byte[][] ids, byte[][] songs, int[] numbers, long now);
    private static native boolean finishNative(long handle, boolean libraryConfirmed, boolean stateConfirmed, boolean uncertain);
    private static native void forgetNative(long handle);
    private static native void closeNative(long handle);
}
