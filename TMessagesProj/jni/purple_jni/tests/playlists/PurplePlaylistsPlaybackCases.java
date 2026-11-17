package org.telegram.messenger.purple;

import org.telegram.tgnet.TLRPC;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurplePlaylistsPlayback.*;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

public final class PurplePlaylistsPlaybackCases {
    private static int checks;
    private static void check(boolean ok, String name) {
        ++checks;
        if (!ok) throw new AssertionError(name);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static PurplePlaylistsView.Song song(int index) throws Exception {
        Constructor<PurplePlaylistsView.Source> source = PurplePlaylistsView.Source.class
                .getDeclaredConstructor(byte[].class, int.class, byte[].class);
        source.setAccessible(true);
        Constructor<PurplePlaylistsView.Song> ctor = PurplePlaylistsView.Song.class.getDeclaredConstructor(
                byte[].class, byte[].class, byte[].class, int.class,
                PurplePlaylistsView.Source.class, PurplePlaylistsView.Source[].class);
        ctor.setAccessible(true);
        return ctor.newInstance(bytes(Long.toUnsignedString(Long.MIN_VALUE + index)), bytes("Song"), bytes("Artist"), 10,
                source.newInstance(bytes("channel:" + (70 + index)), 100 - index, bytes("")),
                new PurplePlaylistsView.Source[0]);
    }
    private static List<PurplePlaylistsView.Song> songs() throws Exception {
        return Arrays.asList(song(0), song(1), song(2), song(3));
    }
    private static TLRPC.Message message(PurplePlaylistsView.Song song) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = song.source.messageId;
        message.peer_id = new TLRPC.TL_peerChannel();
        message.peer_id.channel_id = Long.parseLong(song.source.peer.substring(8));
        message.media = new TLRPC.TL_messageMediaDocument();
        message.media.document = new TLRPC.TL_document();
        message.media.document.id = Long.parseUnsignedLong(song.documentId);
        message.media.document.attributes.add(new TLRPC.TL_documentAttributeAudio());
        return message;
    }
    private static final class Read {
        final PurplePlaylistsView.Song song;
        final Consumer<PurplePlaylistsResolver.Reply> callback;
        Read(PurplePlaylistsView.Song song, Consumer<PurplePlaylistsResolver.Reply> callback) {
            this.song = song; this.callback = callback;
        }
        void answer(PurplePlaylistsResolver.Status status) {
            callback.accept(status == PurplePlaylistsResolver.Status.Ready
                    ? new PurplePlaylistsResolver.Reply(message(song), null, 0, false, false)
                    : PurplePlaylistsResolver.Reply.failed(status, status == PurplePlaylistsResolver.Status.Wait ? 19 : 0));
        }
    }
    private static final class Endpoint implements Port {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final ArrayList<Read> reads = new ArrayList<>();
        final ArrayList<String> starts = new ArrayList<>();
        final ArrayList<Boolean> restarts = new ArrayList<>();
        final ArrayList<Runnable> deliveries = new ArrayList<>();
        final HashMap<String, PurplePlaylistsResolver.Status> replies = new HashMap<>();
        final PurpleSleepTimerRules timer = new PurpleSleepTimerRules();
        boolean live = true, hold, admit = true, doubleGate, throwResolve, tamper, holdDelivery;
        int canceled, exhausted, gateCalls;
        Runnable insideStart, changed;
        Endpoint() { timer.loaded(Slot.AUDIO, new Item(0, 70, 100, Long.MIN_VALUE, Kind.SONG), true); }
        public boolean current() { return live; }
        public void defer(Runnable action) { tasks.add(action); }
        public Gate gate(boolean automatic) {
            ++gateCalls;
            PlayRequest explicit = automatic ? null : timer.requestPlay(Slot.AUDIO);
            Ticket ticket = timer.ticket(Slot.AUDIO);
            return action -> {
                if (!(automatic ? timer.autoStart(ticket) : timer.acceptPlay(explicit))) return false;
                if (!automatic) timer.manualPlay(Slot.AUDIO);
                boolean result = action.getAsBoolean();
                if (doubleGate) action.getAsBoolean();
                return result;
            };
        }
        public AutoCloseable resolve(PurplePlaylistsView.Song song, Consumer<PurplePlaylistsResolver.Result> done) {
            if (throwResolve) throw new IllegalStateException("Inert endpoint refusal");
            PurplePlaylistsResolver resolver = new PurplePlaylistsResolver(new PurplePlaylistsResolver.Port() {
                boolean closed;
                public boolean current() { return live && !closed; }
                public void defer(Runnable task) { tasks.add(task); }
                public void read(PurplePlaylistsView.Source source, Consumer<PurplePlaylistsResolver.Reply> callback) {
                    Read read = new Read(song, callback); reads.add(read);
                    if (!hold) read.answer(replies.getOrDefault(song.documentId, PurplePlaylistsResolver.Status.Ready));
                }
                public void close() { if (!closed) { closed = true; ++canceled; } }
            }, 101, false, Collections.singletonList(song), result -> {
                PurplePlaylistsResolver.Result value = result.get(0);
                if (tamper) value = new PurplePlaylistsResolver.Result(null, value.source, value.status, value.message, 0);
                final PurplePlaylistsResolver.Result captured = value;
                if (holdDelivery) deliveries.add(() -> done.accept(captured)); else done.accept(captured);
            });
            resolver.start(); return resolver;
        }
        public boolean start(PurplePlaylistsResolver.Result result, boolean restart) {
            if (!admit) return false;
            check(PurplePlaylistsResolver.qualify(result.song, result.source,
                    new PurplePlaylistsResolver.Reply(result.message, null, 0, false, false), 101, false)
                    == PurplePlaylistsResolver.Status.Ready, "endpoint revalidates actual wire before start");
            starts.add(result.song.documentId); restarts.add(restart);
            if (insideStart != null) { Runnable action = insideStart; insideStart = null; action.run(); }
            return true;
        }
        public void exhausted(boolean automatic) { ++exhausted; }
        public void changed(State state) { if (changed != null) { Runnable action = changed; changed = null; action.run(); } }
        void flush() {
            int remaining = 2000;
            while (!tasks.isEmpty()) {
                if (--remaining == 0) throw new AssertionError("finite yielded queue work");
                tasks.remove().run();
            }
        }
        void answer(int index) { reads.get(index).answer(PurplePlaylistsResolver.Status.Ready); flush(); }
    }
    private static PurplePlaylistsPlayback queue(List<PurplePlaylistsView.Song> songs, Order order, Repeat repeat, Endpoint port) {
        return new PurplePlaylistsPlayback(songs, order, repeat, port, new Random(41));
    }
    private static void orderAndRepeat() throws Exception {
        List<PurplePlaylistsView.Song> songs = songs();
        for (Order order : Order.values()) for (Repeat repeat : Repeat.values()) {
            Endpoint p = new Endpoint();
            try (PurplePlaylistsPlayback q = queue(songs, order, repeat, p)) {
                check(q.play(2), "selected row admitted " + order + repeat); p.flush();
                check(q.state().playing == 2, "selected row first regardless of message IDs " + order + repeat);
                List<Integer> traversal = q.state().traversal;
                check(traversal.size() == 4 && new java.util.HashSet<>(traversal).size() == 4, "traversal is permutation");
                if (order == Order.Shuffle) check(traversal.get(0) == 2, "shuffle anchors selected row");
                if (order == Order.Normal) check(traversal.equals(Arrays.asList(0, 1, 2, 3)), "normal saved order");
                if (order == Order.Reverse) check(traversal.equals(Arrays.asList(3, 2, 1, 0)), "reverse saved order");
                int expected = repeat == Repeat.One ? 2 : traversal.get(traversal.indexOf(2) + 1);
                q.next(true); p.flush();
                check(q.state().playing == expected, "automatic repeat direction " + order + repeat);
                check(p.restarts.get(1) == (repeat == Repeat.One), "only automatic Repeat One restarts");
                q.play(traversal.get(3)); p.flush();
                int before = p.starts.size();
                if (order == Order.Shuffle) q.previous(); else q.next(false);
                p.flush();
                check(p.starts.size() == before + (repeat == Repeat.All ? 1 : 0), "manual boundary only wraps All");
                if (repeat == Repeat.All) check(q.state().playing == q.state().traversal.get(order == Order.Shuffle ? 3 : 0), "all wraps boundary");
            }
        }
        Endpoint p = new Endpoint();
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.None, p)) {
            q.play(-1); p.flush(); check(q.state().playing == 0, "unselected play uses traversal head");
            int before = p.starts.size(); q.previous(); p.flush();
            check(p.starts.size() == before, "previous has nonwrapping boundary");
            q.play(3); p.flush(); q.next(true); p.flush();
            check(q.state().status == Status.Exhausted && p.exhausted == 1, "natural end exhausts without chat fallback");
        }
        for (Repeat repeat : Repeat.values()) {
            p = new Endpoint();
            try (PurplePlaylistsPlayback q = queue(Collections.singletonList(songs.get(0)), Order.Normal, repeat, p)) {
                q.play(0); p.flush(); q.next(true); p.flush();
                check(p.starts.size() == (repeat == Repeat.None ? 1 : 2), "single-song repeat " + repeat);
            }
        }
    }
    private static void failures() throws Exception {
        List<PurplePlaylistsView.Song> songs = songs();
        for (PurplePlaylistsResolver.Status result : new PurplePlaylistsResolver.Status[] {
                PurplePlaylistsResolver.Status.Deleted, PurplePlaylistsResolver.Status.NoAccess,
                PurplePlaylistsResolver.Status.Changed, PurplePlaylistsResolver.Status.Restricted }) {
            Endpoint p = new Endpoint(); p.replies.put(songs.get(0).documentId, result);
            try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
                q.play(0); p.flush();
                check(q.state().playing == 1 && p.reads.size() == 2, "permanent skip in saved direction " + result);
            }
        }
        for (PurplePlaylistsResolver.Status result : new PurplePlaylistsResolver.Status[] {
                PurplePlaylistsResolver.Status.Failed, PurplePlaylistsResolver.Status.Wait,
                PurplePlaylistsResolver.Status.InvalidInput }) {
            Endpoint p = new Endpoint(); p.replies.put(songs.get(0).documentId, result);
            try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
                q.play(0); p.flush();
                check(p.reads.size() == 1 && p.starts.isEmpty(), "transient never skips " + result);
                q.next(true); p.flush();
                check(p.reads.size() == 1, "automatic end never retries failed row " + result);
                q.modes(Order.Reverse, Repeat.All); p.flush();
                check(p.reads.size() == 1, "mode change never retries failed row " + result);
                if (result == PurplePlaylistsResolver.Status.Wait) check(q.state().waitSeconds == 19, "server wait retained");
                p.replies.clear(); q.retry(); p.flush(); check(q.state().playing == 0, "explicit retry resolves retained row");
            }
        }
        Endpoint p = new Endpoint();
        for (PurplePlaylistsView.Song song : songs) p.replies.put(song.documentId, PurplePlaylistsResolver.Status.Deleted);
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush();
            check(p.reads.size() == songs.size() && q.state().status == Status.Exhausted, "all unavailable cycle is bounded");
        }
        p = new Endpoint(); p.throwResolve = true;
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); check(q.state().status == Status.Retry, "resolve exception requires retry");
        }
        p = new Endpoint(); p.admit = false;
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); check(q.state().status == Status.Retry && p.reads.size() == 1, "native refusal never skips");
        }
    }
    private static void fencing() throws Exception {
        List<PurplePlaylistsView.Song> songs = songs();
        for (int mode = 0; mode < 6; ++mode) {
            Endpoint p = new Endpoint(); p.hold = true;
            try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
                q.play(0); p.flush(); check(p.reads.size() == 1, "only selected source read before first audio");
                if (mode == 0) q.paused();
                if (mode == 1) q.close();
                if (mode == 2) p.live = false;
                if (mode == 3) p.timer.manualPause(Slot.AUDIO);
                if (mode == 4) p.timer.replaceIncarnation(Slot.AUDIO);
                if (mode == 5) p.timer.expire(null);
                p.answer(0);
                check(p.starts.isEmpty(), "stale loaded reply cannot play " + mode);
            }
        }
        Endpoint p = new Endpoint(); p.hold = true;
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); q.play(2); p.flush(); p.answer(0);
            check(p.starts.isEmpty(), "superseded selection cannot play");
            p.answer(1); p.answer(1);
            check(p.starts.size() == 1 && q.state().playing == 2, "reply delivered once to newest selection");
        }
        p = new Endpoint(); p.holdDelivery = true;
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); q.play(2); p.flush();
            p.deliveries.get(0).run(); p.flush();
            check(p.starts.isEmpty(), "completed old resolver delivery cannot play newer selection");
            p.deliveries.get(1).run(); p.flush();
            check(q.state().playing == 2 && p.starts.size() == 1, "completed current delivery starts once");
        }
        p = new Endpoint(); p.doubleGate = true;
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); check(p.starts.size() == 1, "gate cannot start same admission twice");
        }
        p = new Endpoint(); p.tamper = true;
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); check(p.starts.isEmpty() && q.state().status == Status.Retry, "wrong snapshot result refused");
        }
        p = new Endpoint();
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            p.changed = q::close; q.play(0); p.flush();
            check(p.reads.isEmpty(), "listener replacement before resolve revokes admission");
        }
        p = new Endpoint();
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            p.insideStart = q::close; q.play(0); p.flush();
            check(q.state().status == Status.Closed, "native callback cannot resurrect closed queue");
        }
        p = new Endpoint(); p.hold = true;
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); q.modes(Order.Reverse, Repeat.All); p.flush();
            check(p.gateCalls == 1 && p.reads.size() == 2, "mode rebuild keeps original pending Play authority");
            p.timer.manualPause(Slot.AUDIO); p.answer(1);
            check(p.starts.isEmpty(), "order rebuild cannot lift pause or expiry fence");
        }
        p = new Endpoint(); p.hold = true;
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); p.timer.expire(null);
            q.modes(Order.Reverse, Repeat.All); p.flush(); p.answer(1);
            check(p.starts.isEmpty(), "order change after expiry cannot mint fresh Play authority");
        }
        p = new Endpoint();
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(0); p.flush(); p.hold = true;
            q.next(true); p.flush(); p.timer.expire(null); p.answer(1);
            check(p.starts.size() == 1 && q.state().status == Status.Retry,
                    "expiry during automatic source load cannot resume");
            check(p.timer.blocked(Slot.AUDIO), "rejected continuation preserves timer block");
            p.hold = false; q.retry(); p.flush();
            check(p.starts.size() == 2 && !p.timer.blocked(Slot.AUDIO), "fresh explicit Retry may resume after expiry");
        }
        p = new Endpoint();
        try (PurplePlaylistsPlayback q = queue(songs, Order.Normal, Repeat.All, p)) {
            q.play(2); p.flush(); int before = p.starts.size();
            q.modes(Order.Shuffle, Repeat.None); p.flush();
            check(p.starts.size() == before && q.state().playing == 2 && q.state().traversal.get(0) == 2,
                    "live shuffle anchored without native restart");
            boolean immutable = false;
            try { q.state().traversal.add(9); } catch (UnsupportedOperationException e) { immutable = true; }
            check(immutable, "published traversal is immutable");
        }
    }
    public static void main(String[] args) throws Exception {
        orderAndRepeat(); failures(); fencing();
        System.out.println("PLAYLIST_PLAYBACK_CHECKS=" + checks);
    }
}
