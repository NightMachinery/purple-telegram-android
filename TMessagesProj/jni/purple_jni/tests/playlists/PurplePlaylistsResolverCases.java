package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public final class PurplePlaylistsResolverCases {
    private static int checks;
    private static final long SELF = 101;
    private static void check(boolean ok, String name) {
        ++checks;
        if (!ok) throw new AssertionError(name);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static PurplePlaylistsView.Source source(String peer, int id) throws Exception {
        Constructor<PurplePlaylistsView.Source> ctor = PurplePlaylistsView.Source.class
                .getDeclaredConstructor(byte[].class, int.class, byte[].class);
        ctor.setAccessible(true);
        return ctor.newInstance(bytes(peer), id, bytes("source_name"));
    }
    private static PurplePlaylistsView.Song song(String id, PurplePlaylistsView.Source primary,
            PurplePlaylistsView.Source... alternatives) throws Exception {
        Constructor<PurplePlaylistsView.Song> ctor = PurplePlaylistsView.Song.class.getDeclaredConstructor(
                byte[].class, byte[].class, byte[].class, int.class,
                PurplePlaylistsView.Source.class, PurplePlaylistsView.Source[].class);
        ctor.setAccessible(true);
        return ctor.newInstance(bytes(id), bytes("Song"), bytes("Artist"), 10, primary, alternatives);
    }
    private static TLRPC.TL_message message(String peer, int id, String document) {
        TLRPC.TL_message result = new TLRPC.TL_message(); result.id = id;
        if (peer.equals("self") || peer.startsWith("user:")) {
            result.peer_id = new TLRPC.TL_peerUser();
            result.peer_id.user_id = peer.equals("self") ? SELF : Long.parseLong(peer.substring(5));
        } else if (peer.startsWith("chat:")) {
            result.peer_id = new TLRPC.TL_peerChat(); result.peer_id.chat_id = Long.parseLong(peer.substring(5));
        } else {
            result.peer_id = new TLRPC.TL_peerChannel(); result.peer_id.channel_id = Long.parseLong(peer.substring(8));
        }
        result.media = new TLRPC.TL_messageMediaDocument(); result.media.document = new TLRPC.TL_document();
        result.media.document.id = Long.parseUnsignedLong(document);
        result.media.document.attributes.add(new TLRPC.TL_documentAttributeAudio());
        return result;
    }
    private static PurplePlaylistsResolver.Reply reply(TLRPC.Message message) {
        return new PurplePlaylistsResolver.Reply(message, null, 0, false, false);
    }
    private static final class Port implements PurplePlaylistsResolver.Port {
        boolean live = true, closed;
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final ArrayList<PurplePlaylistsView.Source> reads = new ArrayList<>();
        final ArrayList<Consumer<PurplePlaylistsResolver.Reply>> callbacks = new ArrayList<>();
        public boolean current() { return live; }
        public void defer(Runnable task) { tasks.add(task); }
        public void read(PurplePlaylistsView.Source source, Consumer<PurplePlaylistsResolver.Reply> done) {
            reads.add(source); callbacks.add(done);
        }
        public void close() { closed = true; }
        void flush() { int n = 0; while (!tasks.isEmpty()) { check(++n < 1000, "finite resolver work"); tasks.remove().run(); } }
        void answer(PurplePlaylistsResolver.Reply reply) { callbacks.get(callbacks.size() - 1).accept(reply); flush(); }
    }
    private static PurplePlaylistsResolver.Status qualify(TLRPC.Message message, boolean unrestricted) throws Exception {
        PurplePlaylistsView.Source source = source("channel:77", 8);
        return PurplePlaylistsResolver.qualify(song("18446744073709551615", source), source,
                reply(message), SELF, unrestricted);
    }
    private static void qualification() throws Exception {
        check(qualify(message("channel:77", 8, "18446744073709551615"), false) == PurplePlaylistsResolver.Status.Ready,
                "unsigned document matches exact main audio");
        check(qualify(message("channel:77", 8, "9223372036854775808"), false) == PurplePlaylistsResolver.Status.Changed,
                "different unsigned document refused");
        check(qualify(message("channel:78", 8, "18446744073709551615"), false) == PurplePlaylistsResolver.Status.Changed,
                "same message ID in another channel refused");
        check(qualify(message("channel:77", 9, "18446744073709551615"), false) == PurplePlaylistsResolver.Status.Changed,
                "different message refused");
        check(qualify(null, false) == PurplePlaylistsResolver.Status.Deleted, "authoritative absent source deleted");
        check(qualify(new TLRPC.TL_messageEmpty(), false) == PurplePlaylistsResolver.Status.Deleted, "empty source deleted");
        for (int mode = 0; mode != 11; ++mode) {
            TLRPC.TL_message m = message("channel:77", 8, "18446744073709551615");
            if (mode == 0) m.media.document.attributes.get(0).voice = true;
            if (mode == 1) m.media.document.attributes.clear();
            if (mode == 2) m.media.document.attributes.add(new TLRPC.TL_documentAttributeVideo());
            if (mode == 3) m.media.ttl_seconds = 1;
            if (mode == 4) m.ttl = 1;
            if (mode == 5) m.destroyTime = 1;
            if (mode == 6) m.destroyTimeMillis = 1;
            if (mode == 7) m.send_state = 1;
            if (mode == 8) m.quick_reply_shortcut_id = 1;
            if (mode == 9) m.media = new TLRPC.TL_messageMediaWebPage();
            if (mode == 10) m.noforwards = true;
            check(qualify(m, false) == PurplePlaylistsResolver.Status.Restricted, "nonqualifying audio refused " + mode);
        }
        TLRPC.TL_message protectedMessage = message("channel:77", 8, "18446744073709551615");
        protectedMessage.noforwards = true;
        check(qualify(protectedMessage, true) == PurplePlaylistsResolver.Status.Ready, "explicit save policy honored");
        protectedMessage.media.ttl_seconds = 1;
        check(qualify(protectedMessage, true) == PurplePlaylistsResolver.Status.Restricted, "save policy never bypasses TTL");
        for (String platform : new String[] {"all", "android", "android-all", "ios"}) {
            TLRPC.TL_message m = message("channel:77", 8, "18446744073709551615");
            TLRPC.RestrictionReason reason = new TLRPC.RestrictionReason(); reason.platform = platform;
            m.restriction_reason.add(reason);
            check(qualify(m, true) == (platform.equals("ios") ? PurplePlaylistsResolver.Status.Ready
                    : PurplePlaylistsResolver.Status.Restricted), "restriction platform " + platform);
        }
        PurplePlaylistsView.Source s = source("self", 1);
        check(PurplePlaylistsResolver.matches(s, message("self", 1, "1").peer_id, SELF), "self resolves captured user");
        check(!PurplePlaylistsResolver.matches(s, message("user:102", 1, "1").peer_id, SELF), "self never retargets another user");
        for (String peer : new String[] {"user:5", "chat:5", "channel:5"})
            check(PurplePlaylistsResolver.matches(source(peer, 1), message(peer, 1, "1").peer_id, SELF), "peer kind exact " + peer);
    }
    private static void sequencing() throws Exception {
        PurplePlaylistsView.Source first = source("channel:77", 8), alt = source("chat:5", 4);
        PurplePlaylistsView.Song song = song("18446744073709551615", first, alt);
        Port port = new Port(); List<List<PurplePlaylistsResolver.Result>> completed = new ArrayList<>();
        PurplePlaylistsResolver resolver = new PurplePlaylistsResolver(port, SELF, false, Arrays.asList(song, song), completed::add);
        try {
            resolver.start(); resolver.start(); check(port.reads.isEmpty(), "start yields before transport"); port.flush();
            check(port.reads.size() == 1 && port.reads.get(0) == first, "one primary in flight");
            port.answer(reply(null)); check(port.reads.size() == 2 && port.reads.get(1) == alt, "permanent absence tries alternate");
            Consumer<PurplePlaylistsResolver.Reply> old = port.callbacks.get(0);
            old.accept(reply(message("channel:77", 8, "18446744073709551615"))); port.flush();
            check(port.reads.size() == 2 && completed.isEmpty(), "duplicate earlier request cannot win");
            port.answer(reply(message("chat:5", 4, "18446744073709551615")));
            check(port.reads.size() == 3 && port.reads.get(2) == first, "next song retains saved order");
            port.answer(reply(message("channel:77", 8, "18446744073709551615")));
            check(completed.size() == 1 && completed.get(0).size() == 2 && port.closed, "single final immutable completion closes port");
            check(completed.get(0).get(0).status == PurplePlaylistsResolver.Status.EarlierCopy
                    && completed.get(0).get(0).source == alt, "alternate provenance retained");
            check(completed.get(0).get(1).status == PurplePlaylistsResolver.Status.Ready, "primary provenance retained");
            boolean immutable = false; try { completed.get(0).clear(); } catch (UnsupportedOperationException e) { immutable = true; }
            check(immutable, "result list immutable");
        } finally { resolver.close(); }
        for (PurplePlaylistsResolver.Status failure : new PurplePlaylistsResolver.Status[] {
                PurplePlaylistsResolver.Status.Failed, PurplePlaylistsResolver.Status.Wait,
                PurplePlaylistsResolver.Status.NoAccess, PurplePlaylistsResolver.Status.Restricted,
                PurplePlaylistsResolver.Status.Changed}) {
            Port p = new Port(); List<List<PurplePlaylistsResolver.Result>> out = new ArrayList<>();
            PurplePlaylistsResolver r = new PurplePlaylistsResolver(p, SELF, false, Arrays.asList(song, song), out::add);
            try {
                r.start(); p.flush(); p.answer(PurplePlaylistsResolver.Reply.failed(failure, 17));
                if (failure == PurplePlaylistsResolver.Status.Wait) {
                    check(p.reads.size() == 1 && out.size() == 1, "wait halts whole request sequence");
                    check(out.get(0).get(1).status == failure && out.get(0).get(1).waitSeconds == 17, "wait retained for unattempted songs");
                } else if (failure == PurplePlaylistsResolver.Status.Failed) {
                    check(p.reads.size() == 2 && p.reads.get(1) == first, "transient failure never falls back");
                    p.answer(reply(message("channel:77", 8, "18446744073709551615")));
                    check(out.get(0).get(0).message == null, "failure grants no playable message");
                } else check(p.reads.size() == 2 && p.reads.get(1) == alt, "permanent refusal tries alternate " + failure);
            } finally { r.close(); }
        }
        for (boolean changed : new boolean[] {false, true}) {
            Port p = new Port(); List<List<PurplePlaylistsResolver.Result>> out = new ArrayList<>();
            PurplePlaylistsResolver r = new PurplePlaylistsResolver(p, SELF, false, Collections.singletonList(song), out::add);
            r.start(); p.flush(); if (changed) p.live = false; else r.close();
            p.answer(reply(message("channel:77", 8, "18446744073709551615")));
            check(out.isEmpty() && p.closed, "closed or replaced account cannot publish " + changed);
        }
        for (String id : new String[] {"0", "01", "-1", "18446744073709551616", "+1", ""}) {
            Port p = new Port(); List<List<PurplePlaylistsResolver.Result>> out = new ArrayList<>();
            PurplePlaylistsResolver r = new PurplePlaylistsResolver(p, SELF, false,
                    Collections.singletonList(song(id, first)), out::add);
            try { r.start(); p.flush(); check(p.reads.isEmpty() && out.get(0).get(0).status
                    == PurplePlaylistsResolver.Status.InvalidInput, "malformed document has no network authority " + id); }
            finally { r.close(); }
        }
    }
    private static TLRPC.TL_error error(String text) { TLRPC.TL_error e = new TLRPC.TL_error(); e.text = text; return e; }
    private static void telegram() throws Exception {
        for (String text : new String[] {"FLOOD_WAIT_17", "FLOOD_PREMIUM_WAIT_17", "SLOWMODE_WAIT_17"}) {
            PurplePlaylistsResolver.Reply result = PurplePlaylistsTelegram.failure(error(text));
            check(result.failure == PurplePlaylistsResolver.Status.Wait && result.waitSeconds == 17, "server wait parsed " + text);
        }
        check(PurplePlaylistsTelegram.failure(error("FLOOD_WAIT_999999999999999999999999")).failure
                == PurplePlaylistsResolver.Status.Failed, "overflow wait stays refusal");
        check(PurplePlaylistsTelegram.failure(error("INTERNAL")).failure == PurplePlaylistsResolver.Status.Failed, "transient distinct from absence");
        check(PurplePlaylistsTelegram.failure(error("CHANNEL_PRIVATE")).failure == PurplePlaylistsResolver.Status.NoAccess, "channel refusal typed");
        check(PurplePlaylistsTelegram.failure(error("MESSAGE_ID_INVALID")).failure == PurplePlaylistsResolver.Status.Deleted, "invalid message typed");
        PurplePlaylistsAccount.Captured account = new PurplePlaylistsAccount.Captured();
        ConnectionsManager cm = ConnectionsManager.getInstance(0); cm.requests.clear(); cm.replies.clear(); cm.cancelled.clear();
        MessagesController mc = MessagesController.getInstance(0); mc.chats.clear(); mc.writes = 0;
        List<PurplePlaylistsResolver.Reply> replies = new ArrayList<>();
        PurplePlaylistsTelegram adapter = new PurplePlaylistsTelegram(account, () -> true);
        try {
            adapter.read(source("channel:77", 8), replies::add);
            check(cm.requests.size() == 1 && cm.requests.get(0) instanceof TLRPC.TL_contacts_resolveUsername, "missing hash resolves username");
            TLRPC.TL_contacts_resolvedPeer resolved = new TLRPC.TL_contacts_resolvedPeer();
            resolved.peer = new TLRPC.TL_peerChannel(); resolved.peer.channel_id = 77;
            TLRPC.TL_channel chat = new TLRPC.TL_channel(); chat.id = 77; chat.access_hash = 99; resolved.chats.add(chat);
            cm.replies.get(0).run(resolved, null); AndroidUtilities.flush();
            check(cm.requests.size() == 2 && cm.requests.get(1) instanceof TLRPC.TL_channels_getMessages, "verified resolved identity reads original channel");
            TLRPC.TL_channels_getMessages get = (TLRPC.TL_channels_getMessages) cm.requests.get(1);
            check(get.channel.channel_id == 77 && get.channel.access_hash == 99 && get.id.equals(Collections.singletonList(8)), "exact channel and ID preserved");
            TLRPC.TL_messages_channelMessages page = new TLRPC.TL_messages_channelMessages();
            page.messages.add(message("channel:77", 8, "1")); cm.replies.get(1).run(page, null); AndroidUtilities.flush();
            check(replies.size() == 1 && replies.get(0).message == page.messages.get(0), "actual page message published once");
            cm.replies.get(1).run(page, null); AndroidUtilities.flush();
            check(replies.size() == 1, "duplicate transport response consumed once");
            adapter.read(source("self", 9), replies::add);
            check(cm.requests.get(2) instanceof TLRPC.TL_messages_getMessages, "self exact request uses captured connection");
            adapter.close(); check(cm.cancelled.equals(Collections.singletonList(3)), "close cancels exact in-flight request");
            cm.replies.get(2).run(new TLRPC.TL_messages_messages(), null); AndroidUtilities.flush();
            check(replies.size() == 1, "late cancelled response suppressed");
        } finally { adapter.close(); }
        for (int mode = 0; mode != 7; ++mode) {
            account = new PurplePlaylistsAccount.Captured(); mc.chats.clear(); cm.requests.clear(); cm.replies.clear(); replies.clear();
            final boolean[] scope = {true}; adapter = new PurplePlaylistsTelegram(account, () -> scope[0]);
            try {
                int writes = mc.writes;
                adapter.read(source("channel:77", 8), replies::add);
                TLRPC.TL_contacts_resolvedPeer resolved = new TLRPC.TL_contacts_resolvedPeer();
                resolved.peer = new TLRPC.TL_peerChannel(); resolved.peer.channel_id = mode == 0 ? 78 : 77;
                TLRPC.Chat chat = mode == 6 ? new TLRPC.TL_chat() : new TLRPC.TL_channel();
                chat.id = 77; chat.access_hash = mode == 1 ? 0 : 99;
                chat.min = mode == 2; resolved.chats.add(chat);
                if (mode == 3) account.live = false;
                if (mode == 4) scope[0] = false;
                if (mode == 5) resolved.peer = new TLRPC.TL_peerUser();
                cm.replies.get(0).run(resolved, null); AndroidUtilities.flush();
                check(mc.writes == writes, "unverified username reply never publishes peer metadata " + mode);
                check(cm.requests.size() == 1, "unusable or stale username reply cannot read channel " + mode);
                check(mode == 3 || mode == 4 ? replies.isEmpty()
                        : replies.size() == 1 && replies.get(0).failure == PurplePlaylistsResolver.Status.NoAccess,
                        "username refusal never silently retargets " + mode);
            } finally { adapter.close(); }
        }
        for (int mode = 0; mode != 3; ++mode) {
            account = new PurplePlaylistsAccount.Captured(); cm.requests.clear(); cm.replies.clear(); replies.clear();
            adapter = new PurplePlaylistsTelegram(account, () -> true);
            try {
                adapter.read(source("user:5", 8), replies::add);
                TLObject value = new TLRPC.TL_messages_messages();
                if (mode == 0) value = new TLRPC.TL_messages_messagesNotModified();
                else if (mode == 1) ((TLRPC.messages_Messages) value).messages.add(message("user:5", 9, "1"));
                else {
                    ((TLRPC.messages_Messages) value).messages.add(message("user:5", 8, "1"));
                    ((TLRPC.messages_Messages) value).messages.add(message("user:5", 8, "1"));
                }
                cm.replies.get(0).run(value, null); AndroidUtilities.flush();
                check(replies.size() == 1 && replies.get(0).failure == PurplePlaylistsResolver.Status.Failed,
                        "ambiguous or non-exact page refused " + mode);
            } finally { adapter.close(); }
        }
    }
    public static void main(String[] args) throws Exception {
        qualification(); sequencing(); telegram();
        System.out.println("PLAYLIST_RESOLVER_CHECKS=" + checks);
    }
}
