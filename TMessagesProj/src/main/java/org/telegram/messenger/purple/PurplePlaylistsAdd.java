package org.telegram.messenger.purple;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class PurplePlaylistsAdd {
    public static final class Selection {
        public final int account, accepted, skipped;
        final PurplePlaylistsAccount.Captured captured;
        private final PurplePlaylistsEdits.Song[] songs;
        private final PurplePlaylistsView.Song[] sources;
        private final boolean[] protectedMessages;
        private boolean submitted;
        private Selection(PurplePlaylistsAccount.Captured captured, List<PurplePlaylistsEdits.Song> songs,
                List<PurplePlaylistsView.Song> sources, List<Boolean> protectedMessages, int skipped) {
            this.captured = captured; account = captured.identity.slot; accepted = songs.size(); this.skipped = skipped;
            this.songs = songs.toArray(new PurplePlaylistsEdits.Song[0]);
            this.sources = sources.toArray(new PurplePlaylistsView.Song[0]);
            this.protectedMessages = new boolean[protectedMessages.size()];
            for (int i = 0; i < this.protectedMessages.length; ++i) this.protectedMessages[i] = protectedMessages.get(i);
        }
        public boolean available() {
            if (submitted || accepted == 0 || UserConfig.selectedAccount != account || !captured.current()) return false;
            MessagesController controller = MessagesController.getInstance(account);
            for (int i = 0; i < sources.length; ++i) {
                PurplePlaylistsView.Source source = sources[i].source;
                long id = source.peer.startsWith("channel:") ? Long.parseLong(source.peer.substring(8))
                        : source.peer.startsWith("chat:") ? Long.parseLong(source.peer.substring(5)) : 0;
                TLRPC.Chat chat = id == 0 ? null : controller.getChat(id);
                if (id != 0 && (chat == null || chat.min)) return false;
                if (chat != null && controller.getRestrictionReason(chat.restriction_reason) != null) return false;
                if (!PurpleCopyPolicy.unrestricted() && (protectedMessages[i] || chat != null && chat.noforwards)) return false;
            }
            return captured.current();
        }
        public boolean submitted() { return submitted; }
        PurplePlaylistsEdits.Command command(String id, long now) { return PurplePlaylistsEdits.Command.add(id, songs, now); }
        void consume() { submitted = true; }
    }

    public static Selection capture(int account, List<MessageObject> messages) {
        if (UserConfig.selectedAccount != account || messages == null || messages.isEmpty() || messages.size() > 100) return null;
        PurplePlaylistsAccount.Captured captured = PurplePlaylistsAccount.capture(account);
        if (captured == null) return null;
        MessagesController controller = MessagesController.getInstance(account);
        ArrayList<PurplePlaylistsEdits.Song> songs = new ArrayList<>();
        ArrayList<PurplePlaylistsView.Song> sources = new ArrayList<>();
        ArrayList<Boolean> protectedMessages = new ArrayList<>();
        HashSet<String> documents = new HashSet<>();
        int skipped = 0;
        for (MessageObject object : messages) {
            if (object == null || object.currentAccount != account || object.eventId != 0 || object.scheduled
                    || !object.isMusic() || object.messageOwner == null || object.getDocument() == null) { ++skipped; continue; }
            TLRPC.Message message = object.messageOwner;
            TLRPC.Document document = object.getDocument();
            TLRPC.Peer peer = message.peer_id;
            String token = peer instanceof TLRPC.TL_peerUser ? (peer.user_id == captured.identity.userId ? "self" : "user:" + peer.user_id)
                    : peer instanceof TLRPC.TL_peerChannel ? "channel:" + peer.channel_id
                    : peer instanceof TLRPC.TL_peerChat ? "chat:" + peer.chat_id : "";
            long chatId = peer instanceof TLRPC.TL_peerChannel ? peer.channel_id
                    : peer instanceof TLRPC.TL_peerChat ? peer.chat_id : 0;
            TLRPC.Chat chat = chatId == 0 ? null : controller.getChat(chatId);
            if (token.isEmpty() || chatId != 0 && (chat == null || chat.min)) { ++skipped; continue; }
            String username = chat == null || chat.username == null ? "" : chat.username;
            PurplePlaylistsView.Source source = new PurplePlaylistsView.Source(bytes(token), message.id, bytes(username));
            String title = "", performer = "";
            int duration = 0;
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                    title = attribute.title == null ? "" : attribute.title;
                    performer = attribute.performer == null ? "" : attribute.performer;
                    duration = (int) Math.max(0, Math.min(Integer.MAX_VALUE, attribute.duration));
                }
            }
            String id = Long.toUnsignedString(document.id);
            PurplePlaylistsView.Song song = new PurplePlaylistsView.Song(bytes(id), bytes(title), bytes(performer), duration,
                    source, new PurplePlaylistsView.Source[0]);
            PurplePlaylistsResolver.Reply reply = new PurplePlaylistsResolver.Reply(message, null, 0,
                    chat != null && chat.noforwards, chat != null && controller.getRestrictionReason(chat.restriction_reason) != null);
            if (document.id == 0 || message.id <= 0 || !positive(peer)
                    || PurplePlaylistsResolver.qualify(song, source, reply, captured.identity.userId, PurpleCopyPolicy.unrestricted())
                            != PurplePlaylistsResolver.Status.Ready || !documents.add(id)) { ++skipped; continue; }
            try {
                songs.add(new PurplePlaylistsEdits.Song(id, token, message.id, username, title, performer, duration));
            } catch (IllegalArgumentException e) { documents.remove(id); ++skipped; continue; }
            sources.add(song); protectedMessages.add(message.noforwards);
        }
        Selection result = new Selection(captured, songs, sources, protectedMessages, skipped);
        return result.available() ? result : null;
    }
    private static boolean positive(TLRPC.Peer peer) {
        return peer instanceof TLRPC.TL_peerUser ? peer.user_id > 0 : peer instanceof TLRPC.TL_peerChannel ? peer.channel_id > 0
                : peer instanceof TLRPC.TL_peerChat && peer.chat_id > 0;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private PurplePlaylistsAdd() { }
}
