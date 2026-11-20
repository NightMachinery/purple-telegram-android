package org.telegram.messenger.purple;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class PurplePlaylistsOfflineMusic {
    private static final String DOWNLOADS = "android_playlist_downloads";
    private static final String PLAYBACK = "offline_playback";
    private static final int FORMAT = 1, MAX_SOURCES = 8;

    static final class Descriptor {
        final int dc;
        final String mime;
        final boolean protectedPeer, protectedMessage;

        private Descriptor(int dc, String mime, boolean protectedPeer, boolean protectedMessage) {
            this.dc = dc;
            this.mime = mime;
            this.protectedPeer = protectedPeer;
            this.protectedMessage = protectedMessage;
        }

        private JSONObject encode() throws JSONException {
            return new JSONObject().put("dc", dc).put("mime", mime)
                    .put("protected_peer", protectedPeer).put("protected_message", protectedMessage);
        }
    }

    static Descriptor capture(PurplePlaylistsResolver.Result result) {
        TLRPC.Document document = result.message.media.document;
        if (!(document instanceof TLRPC.TL_document) || document.dc_id <= 0 || document.key != null
                || document.iv != null || (document.localPath != null && !document.localPath.isEmpty())
                || !validMime(document.mime_type)) return null;
        return new Descriptor(document.dc_id, document.mime_type, result.protectedPeer, result.message.noforwards);
    }

    private static boolean validMime(String mime) {
        return mime != null && mime.length() <= 80 && mime.matches("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+");
    }

    private static String key(PurplePlaylistsView.Source source) {
        return source.peer + "/" + source.messageId;
    }

    private static JSONObject file(JSONObject state, long document) throws JSONException {
        return state.getJSONObject(DOWNLOADS).getJSONObject("files").getJSONObject(Long.toString(document));
    }

    static byte[] record(byte[] local, long self, long document, PurplePlaylistsView.Source source,
            Descriptor descriptor) throws IOException {
        if (descriptor == null) return local;
        try {
            JSONObject state = new JSONObject(new String(local, StandardCharsets.UTF_8));
            JSONObject record = file(state, document);
            JSONObject playback = record.optJSONObject(PLAYBACK);
            if (record.has(PLAYBACK) && (playback == null || playback.optInt("format") != FORMAT
                    || !Long.toString(self).equals(playback.optString("owner")))) return local;
            JSONObject sources = playback != null ? playback.optJSONObject("sources") : new JSONObject();
            if (sources == null || sources.length() > MAX_SOURCES) return local;
            String key = key(source);
            JSONObject old = sources.optJSONObject(key);
            if (old != null && old.optInt("dc") == descriptor.dc && descriptor.mime.equals(old.opt("mime"))
                    && Boolean.valueOf(descriptor.protectedPeer).equals(old.opt("protected_peer"))
                    && Boolean.valueOf(descriptor.protectedMessage).equals(old.opt("protected_message"))) return local;
            if (old == null && sources.length() >= MAX_SOURCES) return local;
            sources.put(key, descriptor.encode());
            if (playback == null)
                record.put(PLAYBACK, new JSONObject().put("format", FORMAT).put("owner", Long.toString(self))
                        .put("sources", sources));
            PurplePlaylistsLocalCore.Result normalized = PurplePlaylistsLocalCore.canonicalize(
                    state.toString().getBytes(StandardCharsets.UTF_8), self);
            if (!normalized.valid()) throw new IOException("OfflinePlaybackStateInvalid");
            return normalized.canonicalBytes();
        } catch (JSONException error) {
            throw new IOException("OfflinePlaybackStateInvalid", error);
        }
    }

    static PurplePlaylistsResolver.Result restore(byte[] state, long self,
            PurplePlaylistsDownloadsCore.Record record, PurplePlaylistsView.Song song, int account) {
        try {
            JSONObject file = file(new JSONObject(new String(state, StandardCharsets.UTF_8)), record.document);
            JSONObject playback = file.optJSONObject(PLAYBACK);
            if (file.has(PLAYBACK) && (playback == null || playback.optInt("format") != FORMAT
                    || !Long.toString(self).equals(playback.optString("owner")))) return null;
            JSONObject sources = playback != null ? playback.getJSONObject("sources") : new JSONObject();
            if (sources.length() > MAX_SOURCES) return null;
            List<PurplePlaylistsView.Source> requested = new ArrayList<>(1 + song.alternatives.size());
            requested.add(song.source);
            requested.addAll(song.alternatives);
            for (PurplePlaylistsView.Source source : requested) {
                JSONObject entry = sources.optJSONObject(key(source));
                if (sources.has(key(source)) && entry == null) continue;
                try {
                    PurplePlaylistsResolver.Result restored = entry != null
                            ? fromDescriptor(entry, self, record, song, source)
                            : fromHistory(account, self, record, song, source);
                    if (restored != null) return restored;
                } catch (JSONException | IllegalArgumentException error) {
                    continue;
                }
            }
        } catch (JSONException | IllegalArgumentException error) {
            return null;
        }
        return null;
    }

    private static PurplePlaylistsResolver.Result fromDescriptor(JSONObject entry, long self,
            PurplePlaylistsDownloadsCore.Record record, PurplePlaylistsView.Song song,
            PurplePlaylistsView.Source source) throws JSONException {
        int dc = entry.getInt("dc");
        String mime = entry.getString("mime");
        boolean protectedPeer = entry.getBoolean("protected_peer");
        if (dc <= 0 || !validMime(mime)) return null;
        return build(new Descriptor(dc, mime, protectedPeer, entry.getBoolean("protected_message")),
                self, record, song, source);
    }

    private static PurplePlaylistsResolver.Result build(Descriptor descriptor, long self,
            PurplePlaylistsDownloadsCore.Record record, PurplePlaylistsView.Song song,
            PurplePlaylistsView.Source source) {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = record.document;
        document.size = record.size;
        document.dc_id = descriptor.dc;
        document.mime_type = descriptor.mime;
        document.file_reference = new byte[0];

        TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
        audio.title = song.title;
        audio.performer = song.performer;
        audio.duration = song.duration;
        document.attributes.add(audio);

        TLRPC.TL_messageMediaDocument media = new TLRPC.TL_messageMediaDocument();
        media.document = document;
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = source.messageId;
        message.peer_id = peer(source, self);
        message.message = "";
        message.media = media;
        message.noforwards = descriptor.protectedMessage;
        return qualify(self, song, source, message, descriptor.protectedPeer, false);
    }

    private static PurplePlaylistsResolver.Result fromHistory(int account, long self,
            PurplePlaylistsDownloadsCore.Record record, PurplePlaylistsView.Song song,
            PurplePlaylistsView.Source source) {
        TLRPC.Peer peer = peer(source, self);
        long chatId = Math.max(peer.chat_id, peer.channel_id);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        TLRPC.Chat chat = chatId == 0 ? null : storage.getChatSync(chatId);
        if (chatId != 0 && (chat == null || chat.min)) return null;
        TLRPC.Message cached = storage.getMessage(chatId == 0 ? peer.user_id : -chatId, source.messageId);
        if (cached == null || cached.media == null || !(cached.media.document instanceof TLRPC.TL_document)) return null;
        TLRPC.Document document = cached.media.document;
        if (document.size != record.size || document.dc_id <= 0 || document.key != null || document.iv != null) return null;
        boolean restricted = chat != null
                && MessagesController.getInstance(account).getRestrictionReason(chat.restriction_reason) != null;
        PurplePlaylistsResolver.Result validated = qualify(self, song, source, cached, chat != null && chat.noforwards, restricted);
        Descriptor descriptor = validated == null ? null : capture(validated);
        return descriptor != null ? build(descriptor, self, record, song, source) : null;
    }

    private static PurplePlaylistsResolver.Result qualify(long self, PurplePlaylistsView.Song song,
            PurplePlaylistsView.Source source, TLRPC.Message message, boolean protectedPeer, boolean restricted) {
        PurplePlaylistsResolver.Reply reply = new PurplePlaylistsResolver.Reply(message, null, 0, protectedPeer, restricted);
        if (PurplePlaylistsResolver.qualify(song, source, reply, self, PurpleCopyPolicy.unrestricted())
                != PurplePlaylistsResolver.Status.Ready) return null;
        return PurplePlaylistsResolver.Result.offline(song, source, message, protectedPeer);
    }

    private static TLRPC.Peer peer(PurplePlaylistsView.Source source, long self) {
        String peer = source.peer;
        if ("self".equals(peer) || peer.startsWith("user:")) {
            TLRPC.TL_peerUser result = new TLRPC.TL_peerUser();
            result.user_id = "self".equals(peer) ? self : Long.parseLong(peer.substring(5));
            return result;
        }
        if (peer.startsWith("chat:")) {
            TLRPC.TL_peerChat result = new TLRPC.TL_peerChat();
            result.chat_id = Long.parseLong(peer.substring(5));
            return result;
        }
        if (peer.startsWith("channel:")) {
            TLRPC.TL_peerChannel result = new TLRPC.TL_peerChannel();
            result.channel_id = Long.parseLong(peer.substring(8));
            return result;
        }
        throw new IllegalArgumentException("OfflinePlaybackPeerInvalid");
    }

    private PurplePlaylistsOfflineMusic() { }
}
