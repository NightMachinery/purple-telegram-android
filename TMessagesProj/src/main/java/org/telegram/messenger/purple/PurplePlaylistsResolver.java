package org.telegram.messenger.purple;

import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

final class PurplePlaylistsResolver implements AutoCloseable {
    enum Status { Ready, EarlierCopy, Deleted, NoAccess, Changed, Restricted, Failed, Wait, InvalidInput }

    static final class Result {
        final PurplePlaylistsView.Song song;
        final PurplePlaylistsView.Source source;
        final Status status;
        final TLRPC.Message message;
        final int waitSeconds;
        Result(PurplePlaylistsView.Song song, PurplePlaylistsView.Source source,
                Status status, TLRPC.Message message, int waitSeconds) {
            this.song = song; this.source = source; this.status = status;
            this.message = message; this.waitSeconds = waitSeconds;
        }
        boolean playable() { return status == Status.Ready || status == Status.EarlierCopy; }
    }

    static final class Reply {
        final TLRPC.Message message;
        final Status failure;
        final int waitSeconds;
        final boolean protectedPeer, restrictedPeer;
        Reply(TLRPC.Message message, Status failure, int waitSeconds,
                boolean protectedPeer, boolean restrictedPeer) {
            this.message = message; this.failure = failure; this.waitSeconds = waitSeconds;
            this.protectedPeer = protectedPeer; this.restrictedPeer = restrictedPeer;
        }
        static Reply failed(Status status, int waitSeconds) {
            return new Reply(null, status, waitSeconds, false, false);
        }
    }

    interface Port extends AutoCloseable {
        boolean current();
        void defer(Runnable task);
        void read(PurplePlaylistsView.Source source, Consumer<Reply> done);
        void close();
    }

    private final Port port;
    private final long selfUserId;
    private final boolean unrestricted;
    private final List<PurplePlaylistsView.Song> songs;
    private final ArrayList<Result> results = new ArrayList<>();
    private Consumer<List<Result>> done;
    private int index, sourceIndex;
    private long generation;
    private boolean started, closed, reading;

    PurplePlaylistsResolver(Port port, long selfUserId, boolean unrestricted,
            List<PurplePlaylistsView.Song> songs, Consumer<List<Result>> done) {
        if (port == null || songs == null || done == null || selfUserId <= 0)
            throw new IllegalArgumentException("Missing captured resolver input");
        this.port = port; this.selfUserId = selfUserId; this.unrestricted = unrestricted;
        this.songs = Collections.unmodifiableList(new ArrayList<>(songs)); this.done = done;
    }

    void start() {
        if (started || closed) return;
        started = true;
        port.defer(this::next);
    }

    private boolean current() {
        if (closed) return false;
        if (port.current()) return true;
        close();
        return false;
    }

    private void next() {
        if (!current() || reading) return;
        if (index == songs.size()) {
            Consumer<List<Result>> callback = done;
            List<Result> result = Collections.unmodifiableList(new ArrayList<>(results));
            close();
            callback.accept(result);
            return;
        }
        PurplePlaylistsView.Song song = songs.get(index);
        PurplePlaylistsView.Source source = song == null ? null
                : sourceIndex == 0 ? song.source : song.alternatives.get(sourceIndex - 1);
        if (!valid(song, source)) {
            accept(song, source, Reply.failed(Status.InvalidInput, 0));
            return;
        }
        reading = true;
        long request = ++generation;
        try {
            port.read(source, reply -> port.defer(() -> {
                if (!current() || !reading || request != generation) return;
                reading = false;
                accept(song, source, reply == null ? Reply.failed(Status.Failed, 0) : reply);
            }));
        } catch (RuntimeException e) {
            if (current() && reading && request == generation) {
                reading = false;
                accept(song, source, Reply.failed(Status.Failed, 0));
            }
        }
    }

    private void accept(PurplePlaylistsView.Song song, PurplePlaylistsView.Source source, Reply reply) {
        if (!current()) return;
        Status status = reply.failure == null ? qualify(song, source, reply, selfUserId, unrestricted) : reply.failure;
        if (status == Status.Ready && sourceIndex != 0) status = Status.EarlierCopy;
        boolean fallback = status == Status.Deleted || status == Status.NoAccess
                || status == Status.Changed || status == Status.Restricted;
        if (fallback && sourceIndex < song.alternatives.size()) {
            ++sourceIndex;
        } else {
            results.add(new Result(song, source, status,
                    status == Status.Ready || status == Status.EarlierCopy ? reply.message : null,
                    status == Status.Wait ? Math.max(1, reply.waitSeconds) : 0));
            ++index; sourceIndex = 0;
            if (status == Status.Wait) {
                while (index < songs.size()) {
                    PurplePlaylistsView.Song remaining = songs.get(index++);
                    results.add(new Result(remaining, remaining == null ? null : remaining.source,
                            Status.Wait, null, Math.max(1, reply.waitSeconds)));
                }
            }
        }
        port.defer(this::next);
    }

    private static boolean valid(PurplePlaylistsView.Song song, PurplePlaylistsView.Source source) {
        if (song == null || source == null || source.messageId <= 0 || source.peer == null) return false;
        try {
            if (song.documentId == null || song.documentId.isEmpty() || song.documentId.charAt(0) == '0'
                    || !song.documentId.matches("[1-9][0-9]*") || Long.parseUnsignedLong(song.documentId) == 0) return false;
            if (source.peer.equals("self")) return true;
            int colon = source.peer.indexOf(':');
            String kind = colon < 0 ? "" : source.peer.substring(0, colon);
            String id = colon < 0 ? "" : source.peer.substring(colon + 1);
            return (kind.equals("user") || kind.equals("chat") || kind.equals("channel"))
                    && id.matches("[1-9][0-9]*") && Long.parseLong(id) > 0;
        } catch (NumberFormatException e) { return false; }
    }

    static boolean matches(PurplePlaylistsView.Source source, TLRPC.Peer peer, long self) {
        if (peer == null) return false;
        String actual = peer instanceof TLRPC.TL_peerUser ? (peer.user_id == self ? "self" : "user:" + peer.user_id)
                : peer instanceof TLRPC.TL_peerChat ? "chat:" + peer.chat_id
                : peer instanceof TLRPC.TL_peerChannel ? "channel:" + peer.channel_id : "";
        return source.peer.equals(actual);
    }

    static Status qualify(PurplePlaylistsView.Song song, PurplePlaylistsView.Source source,
            Reply reply, long self, boolean unrestricted) {
        TLRPC.Message message = reply.message;
        if (message == null || message instanceof TLRPC.TL_messageEmpty) return Status.Deleted;
        if (!(message instanceof TLRPC.TL_message) || message.id != source.messageId
                || !matches(source, message.peer_id, self)) return Status.Changed;
        if (message.send_state != 0 || message.id <= 0 || message.quick_reply_shortcut_id != 0
                || !(message.media instanceof TLRPC.TL_messageMediaDocument)) return Status.Restricted;
        TLRPC.Document document = message.media.document;
        if (document == null || document instanceof TLRPC.TL_documentEmpty
                || !Long.toUnsignedString(document.id).equals(song.documentId)) return Status.Changed;
        if (message.media.ttl_seconds != 0 || message.ttl != 0 || message.destroyTime != 0
                || message.destroyTimeMillis != 0 || reply.restrictedPeer) return Status.Restricted;
        if (!unrestricted && (message.noforwards || reply.protectedPeer)) return Status.Restricted;
        if (message.restriction_reason != null) {
            for (TLRPC.RestrictionReason reason : message.restriction_reason) {
                if (reason == null || "all".equals(reason.platform) || ("android".equals(reason.platform) || "android-all".equals(reason.platform)))
                    return Status.Restricted;
            }
        }
        boolean audio = false;
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                if (attribute.voice) return Status.Restricted;
                audio = true;
            }
            if (attribute instanceof TLRPC.TL_documentAttributeVideo) return Status.Restricted;
        }
        return audio ? Status.Ready : Status.Restricted;
    }

    @Override public void close() {
        if (closed) return;
        closed = true; ++generation; reading = false; done = null;
        port.close();
    }
}
