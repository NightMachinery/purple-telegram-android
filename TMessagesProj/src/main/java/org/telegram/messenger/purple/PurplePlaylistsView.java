package org.telegram.messenger.purple;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Keep
public final class PurplePlaylistsView {
    public enum Status { Valid, NewerFormat, Invalid }
    public enum Input { None, Library, Local }

    @Keep
    public static final class Source {
        public final String peer, username;
        public final int messageId;
        Source(byte[] peer, int messageId, byte[] username) {
            this.peer = text(peer); this.messageId = messageId; this.username = text(username);
        }
    }

    @Keep
    public static final class Song {
        public final String documentId, title, performer;
        public final int duration;
        public final Source source;
        public final List<Source> alternatives;
        Song(byte[] documentId, byte[] title, byte[] performer, int duration,
                Source source, Source[] alternatives) {
            this.documentId = text(documentId); this.title = text(title); this.performer = text(performer);
            this.duration = duration; this.source = source; this.alternatives = immutable(alternatives);
        }
    }

    @Keep
    public static final class Row {
        public final String id, title;
        public final boolean deleted, partial;
        public final long recentUseMs;
        public final int songCount;
        private Row(byte[] id, byte[] title, boolean deleted, boolean partial, long recentUseMs, int songCount) {
            this.id = text(id); this.title = text(title); this.deleted = deleted;
            this.partial = partial; this.recentUseMs = recentUseMs; this.songCount = songCount;
        }
    }

    @Keep
    public static final class Detail {
        public final Row row;
        public final List<Song> songs;
        private Detail(Row row, Song[] songs) { this.row = row; this.songs = immutable(songs); }
    }

    public final Status status;
    public final Input input;
    public final String error, selectedId, syncResult;
    public final List<Row> playlists, deleted;
    public final Detail detail;
    public final boolean syncEnabled;
    public final long unsyncedSinceMs;
    public final int incompleteCount, hiddenCount;

    private PurplePlaylistsView(int status, int input, String error, byte[] selectedId,
            Row[] playlists, Row[] deleted, Detail detail, boolean syncEnabled, String syncResult,
            long unsyncedSinceMs, int incompleteCount, int hiddenCount) {
        this.status = Status.values()[status]; this.input = Input.values()[input]; this.error = error;
        this.selectedId = text(selectedId); this.detail = detail;
        this.playlists = playlists == null ? null : immutable(playlists);
        this.deleted = deleted == null ? null : immutable(deleted);
        this.syncEnabled = syncEnabled; this.syncResult = syncResult; this.unsyncedSinceMs = unsyncedSinceMs;
        this.incompleteCount = incompleteCount; this.hiddenCount = hiddenCount;
        if (valid() != (this.playlists != null && this.deleted != null)
                || (!valid() && detail != null) || (valid() && this.input != Input.None))
            throw new IllegalStateException("Invalid playlist view result");
    }

    public boolean valid() { return status == Status.Valid; }

    static PurplePlaylistsView project(byte[] library, byte[] state, long selfUserId, String selectedId) {
        PurpleCore.ensureLoaded();
        PurplePlaylistsView result = projectNative(library, state, selfUserId, utf8(selectedId));
        if (result == null) throw new IllegalStateException("Missing playlist projection");
        return result;
    }

    private static <T> List<T> immutable(T[] values) {
        return Collections.unmodifiableList(Arrays.asList(values.clone()));
    }
    private static String text(byte[] value) {
        if (value == null) throw new IllegalStateException("Missing playlist projection text");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException e) { throw new IllegalStateException("Invalid playlist projection text", e); }
    }
    private static byte[] utf8(String value) {
        if (value == null) throw new IllegalArgumentException("Missing playlist selection");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] result = new byte[bytes.remaining()]; bytes.get(result); return result;
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid playlist selection", e); }
    }
    private static native PurplePlaylistsView projectNative(byte[] library, byte[] state, long selfUserId, byte[] selectedId);
}
