package org.telegram.messenger.purple;

import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

public final class PurplePlaylistsPlayerCases {
    private static int checks;
    private static void check(boolean ok, String name) {
        ++checks;
        if (!ok) throw new AssertionError(name);
    }
    private static byte[] text(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static PurplePlaylistsView.Song song(int index) throws Exception {
        Constructor<PurplePlaylistsView.Source> source = PurplePlaylistsView.Source.class
                .getDeclaredConstructor(byte[].class, int.class, byte[].class);
        source.setAccessible(true);
        Constructor<PurplePlaylistsView.Song> ctor = PurplePlaylistsView.Song.class.getDeclaredConstructor(
                byte[].class, byte[].class, byte[].class, int.class,
                PurplePlaylistsView.Source.class, PurplePlaylistsView.Source[].class);
        ctor.setAccessible(true);
        return ctor.newInstance(text(Long.toUnsignedString(Long.MIN_VALUE + index)), text("Song"), text("Artist"), 20,
                source.newInstance(text("channel:" + (70 + index)), 100 - index, text("")), new PurplePlaylistsView.Source[0]);
    }
    private static TLRPC.TL_message wire(PurplePlaylistsView.Song song) {
        TLRPC.TL_message value = new TLRPC.TL_message(); value.id = song.source.messageId;
        value.peer_id = new TLRPC.TL_peerChannel(); value.peer_id.channel_id = Long.parseLong(song.source.peer.substring(8));
        value.media = new TLRPC.TL_messageMediaDocument(); value.media.document = new TLRPC.TL_document();
        value.media.document.id = Long.parseUnsignedLong(song.documentId);
        value.media.document.attributes.add(new TLRPC.TL_documentAttributeAudio());
        return value;
    }
    private static final class Fixture implements AutoCloseable {
        final List<PurplePlaylistsView.Song> songs = new ArrayList<>();
        final MediaController media;
        final PurplePlaylistsPlayer player;
        final ConnectionsManager network;
        final int slot;
        int answered;
        Fixture(int slot) throws Exception { this(slot, 3); }
        Fixture(int slot, int count) throws Exception {
            this.slot = slot;
            UserConfig.selectedAccount = slot;
            for (int i = 0; i < count; ++i) songs.add(song(i));
            PurpleSleepTimer.get().rules = new PurpleSleepTimerRules(); PurpleSleepTimer.get().local = false;
            SharedConfig.shuffleMusic = SharedConfig.playOrderReversed = false; SharedConfig.repeatMode = 0;
            AndroidUtilities.tasks.clear();
            for (int i = 0; i != 2; ++i) {
                PurplePlaylistsAccount.slots[i].live = true;
                MessagesController.slots[i] = new MessagesController();
                ConnectionsManager.slots[i] = new ConnectionsManager();
                for (PurplePlaylistsView.Song song : songs) {
                    TLRPC.TL_channel chat = new TLRPC.TL_channel(); chat.id = Long.parseLong(song.source.peer.substring(8)); chat.access_hash = 90;
                    MessagesController.slots[i].chats.put(chat.id, chat);
                }
            }
            media = new MediaController(); player = media.purplePlaylists(); network = ConnectionsManager.getInstance(slot);
        }
        void flush() { AndroidUtilities.flush(); }
        void start(int selected) { check(player.start(slot, songs, selected), "native start accepted"); flush(); }
        void reply() { reply(null, null); }
        void reply(String error, Runnable beforeDelivery) {
            if (answered == network.requests.size()) return;
            check(answered < network.requests.size(), "one pending captured network request");
            int index = answered;
            for (int candidate = answered; candidate < network.requests.size(); ++candidate) {
                if (!network.cancelled.contains(candidate + 1)) { index = candidate; break; }
            }
            answered = index + 1;
            replyAt(index, error, beforeDelivery);
        }
        void replyAt(int index, String error, Runnable beforeDelivery) {
            TLObject request = network.requests.get(index);
            int messageId = ((TLRPC.TL_channels_getMessages) request).id.get(0);
            PurplePlaylistsView.Song song = songs.stream().filter(row -> row.source.messageId == messageId).findFirst().get();
            TLRPC.TL_messages_messages response = new TLRPC.TL_messages_messages(); response.messages.add(wire(song));
            TLRPC.TL_error failure = null;
            if (error != null) { failure = new TLRPC.TL_error(); failure.text = error; }
            network.replies.get(index).run(response, failure);
            if (beforeDelivery != null) beforeDelivery.run();
            flush();
        }
        void expire() { PurpleSleepTimer.get().rules.manualPause(Slot.AUDIO); media.paused = true; }
        void selected(int index, String name) {
            check(media.playing != null && media.playing.currentAccount == slot
                    && media.playing.getDocument().id == Long.parseUnsignedLong(songs.get(index).documentId), name);
        }
        public void close() { player.leave(); AndroidUtilities.tasks.clear(); }
    }

    private static void traversal() throws Exception {
        try (Fixture f = new Fixture(1)) {
            f.media.playlist.add(new MessageObject(1, wire(f.songs.get(0)), false, false));
            f.start(1); check(f.media.starts == 0, "resolve before native start");
            f.reply(); f.selected(1, "captured account selected row plays first");
            check(f.media.playlist.size() == 1 && f.media.shuffledPlaylist.size() == 1
                    && f.media.playlist.get(0) == f.media.playing, "native bridge contains actual current message only");
            check(f.media.playing.currentAccount == 1 && ConnectionsManager.getInstance(0).requests.isEmpty(), "resolution never retargets global account");
            check(f.player.next(false), "native Next handled"); f.flush(); f.reply(); f.selected(2, "saved order overrides message id sort");
            check(f.player.next(false), "manual final boundary handled"); f.flush(); f.reply();
            f.selected(0, "native Next wraps from the final track");
            check(f.player.previous(), "native Previous handled"); f.flush(); f.reply();
            f.selected(2, "native Previous wraps from the first track");
            check(f.player.previous(), "native Previous keeps saved order"); f.flush(); f.reply();
            f.selected(1, "saved previous original row");
            f.media.playing.audioProgressSec = 12;
            int beforeSeek = f.network.requests.size();
            check(f.player.previous() && f.media.seeks == 1 && f.network.requests.size() == beforeSeek, "previous after ten seconds seeks without resolution");
            SharedConfig.repeatMode = 2;
            f.media.playing.audioProgressSec = 9;
            check(f.player.next(true), "automatic Repeat One handled"); f.flush(); f.reply();
            f.selected(1, "Repeat One restarts same row");
            check(f.media.playing.audioProgressSec == 0 && f.media.cleanups == 1, "Repeat One resets native progress and decoder");
        }
        try (Fixture f = new Fixture(0)) {
            SharedConfig.repeatMode = 1; f.start(2); f.reply(); f.player.next(true); f.flush(); f.reply();
            f.selected(0, "Repeat All wraps saved order");
            f.media.loadMoreMusic(); f.media.prefetch(); for (int i = 0; i != 3; ++i) f.media.page(i);
            check(f.media.history == 0 && f.media.prefetch == 0 && f.media.pages == 0, "owned queue suppresses stock history pages and prefetch");
            f.player.leave(); f.media.loadMoreMusic(); f.media.prefetch(); f.media.page(0);
            check(f.media.history == 1 && f.media.prefetch == 1 && f.media.pages == 1, "unowned stock loading unchanged");
        }
    }

    private static void presentedQueue() throws Exception {
        try (Fixture f = new Fixture(1)) {
            f.start(1);
            check(f.player.displayedQueue(1) == null, "no native playlist sheet before first resolution");
            f.reply();
            PurplePlaylistsPlayer.Queue list = f.player.displayedQueue(1);
            check(list != null && list.account == 1 && list.songs.equals(f.songs), "captured sheet preserves saved order");
            check(f.player.displayedQueue(0) == null, "native queue is hidden from other account");
            boolean immutable = false;
            try { list.songs.clear(); } catch (UnsupportedOperationException e) { immutable = true; }
            check(immutable, "native queue snapshot cannot be edited by the sheet");
            int reads = f.network.requests.size();
            f.media.playing.audioProgressSec = 11;
            check(f.player.selectQueueSong(list, 1) && f.media.paused, "current sheet row pauses native music");
            check(f.player.selectQueueSong(list, 1) && !f.media.paused, "current sheet row resumes native music");
            check(f.media.playing.audioProgressSec == 11 && f.network.requests.size() == reads,
                    "sheet resume keeps the current position without rereading source");
            UserConfig.selectedAccount = 0;
            check(!f.player.selectQueueSong(list, 0) && f.player.displayedQueue(1) == null,
                    "account switch blocks old sheet controls");
            UserConfig.selectedAccount = 1;
            check(!f.player.selectQueueSong(list, -1) && !f.player.selectQueueSong(list, list.songs.size()),
                    "out-of-range native row selection is refused");
            check(f.player.selectQueueSong(list, 0), "sheet row routes through the saved queue");
            f.flush(); f.reply(); f.selected(0, "sheet row resolves its own saved source");
            f.start(2); f.reply();
            check(!f.player.selectQueueSong(list, 1), "replaced playlist rejects clicks from its old sheet");
        }
    }

    private static void fences() throws Exception {
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.media.cleanupPlayer(true, true); f.reply();
            check(f.media.starts == 0 && !f.player.active(), "Stop during initial resolution prevents native start");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); f.player.next(true); f.flush(); f.expire(); f.reply();
            check(f.media.starts == 1, "expiry while automatic next resolves cannot start native player");
            f.media.audio.automaticRun(f.media.audio.capture(), () -> f.media.playMessage(f.media.playing));
            check(f.media.starts == 1, "expired focus does not resume old native item");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); f.player.next(false); f.flush(); f.media.pauseMessage(); f.reply();
            check(f.media.starts == 1, "Pause revokes explicit Next receipt");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.expire(); SharedConfig.playOrderReversed = true; f.player.modes(); f.flush();
            while (f.answered < f.network.requests.size()) f.reply();
            check(f.media.starts == 0, "mode rebuild retains pending Play receipt after expiry");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.expire(); f.reply();
            check(f.media.starts == 0, "explicit native Play receipt is revoked by expiry");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); PurplePlaylistsAccount.slots[0].live = false;
            check(!f.player.active() && f.media.playing == null && f.media.playlist.isEmpty(), "retired account releases owned native queue without stock fallback");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); PurplePlaylistsAccount.slots[0].live = false; f.reply();
            check(f.media.starts == 0 && !f.player.active(), "account lifetime revocation prevents native start");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(null, () -> MessagesController.getInstance(0).getChat(70).min = true);
            check(f.media.starts == 0, "peer metadata revalidated at native start");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); MessageObject old = f.media.playing;
            MessageObject other = new MessageObject(1, old.messageOwner, false, false);
            check(!PurplePlaylistsPlayer.same(old, other), "cross-account same message and document are distinct");
            MessageObject changed = new MessageObject(0, wire(f.songs.get(0)), false, false);
            changed.getDocument().id++;
            check(!PurplePlaylistsPlayer.same(old, changed), "same message changed document is distinct");
            check(f.media.playMessage(other) && !f.player.active(), "external account Play retires saved queue");
        }
    }

    private static void failures() throws Exception {
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); f.player.next(true); f.flush(); f.reply("FLOOD_WAIT_19", null);
            int reads = f.network.requests.size();
            check(f.player.next(true), "Wait still owns automatic end"); f.flush();
            check(f.network.requests.size() == reads && f.media.starts == 1, "Wait never auto-retries or falls into chat queue");
            f.media.audio.automaticRun(f.media.audio.capture(), () -> f.media.playMessage(f.media.playing));
            check(f.media.starts == 1, "focus cannot bypass Wait with old item");
            check(f.player.retry(), "Wait offers explicit native Retry"); f.flush(); f.reply();
            f.selected(1, "explicit Retry resumes requested Wait row");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); f.player.failed(f.media.playing);
            int reads = f.network.requests.size(); f.player.next(true); f.flush();
            check(f.network.requests.size() == reads && f.media.starts == 1, "native error cannot automatically retry");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(2); f.reply(); check(f.player.next(true), "saved exhaustion handled"); f.flush();
            check(f.media.cleanups == 1 && f.media.playing == null && f.player.active(), "exhaustion stops native decoder without unrelated fallback");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.media.accept = false; f.reply();
            check(f.media.starts == 0 && f.player.next(true), "native refusal keeps owned Retry");
            check(f.network.requests.size() == 1, "native refusal does not auto-retry");
        }
    }

    private static void interruptions() throws Exception {
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); MessageObject music = f.media.playing;
            f.player.next(false); f.flush();
            MessageObject voice = new MessageObject(0, wire(f.songs.get(2)), false, false); voice.voice = true;
            check(f.media.playMessage(voice) && !f.player.controls() && f.player.active(), "voice interruption retains queue without controlling voice");
            f.reply(); check(f.media.playing == voice && f.media.starts == 2, "old music resolution cannot replace voice");
            f.media.audio.automaticRun(f.media.audio.capture(), () -> f.media.playMessage(music));
            f.player.restored(music);
            check(f.player.controls() && f.media.playing == music, "automatic voice restoration recovers owned queue");
            f.player.next(false); f.flush(); f.reply(); f.selected(1, "restored Next continues original saved order");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); MessageObject music = f.media.playing;
            MessageObject voice = new MessageObject(0, wire(f.songs.get(2)), false, false); voice.voice = true;
            f.media.playMessage(voice); f.expire();
            f.media.playing = music; f.media.paused = true; f.player.restored(music);
            check(f.player.controls() && f.media.starts == 2 && f.media.paused, "expired voice restore adopts music paused");
            int beforeNext = f.network.requests.size();
            f.player.next(true); f.flush(); check(f.network.requests.size() == beforeNext, "paused restored music has no automatic advance");
        }
        try (Fixture f = new Fixture(0)) {
            f.start(0); f.reply(); f.expire();
            check(f.player.resumeAfterEnd(), "explicit transport consumes owned pending end"); f.flush(); f.reply();
            f.selected(1, "fresh explicit end Play advances saved order");
        }
    }

    private static final class Watch implements PurplePlaylistsPlayer.Listener {
        final ArrayList<PurplePlaylistsPlayer.State> states = new ArrayList<>();
        public void playbackChanged(PurplePlaylistsPlayer.State state) { states.add(state); }
        PurplePlaylistsPlayer.State last() { return states.get(states.size() - 1); }
    }

    private static Object member(Object object, String name) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(object);
    }
    private static Map<?, ?> cache(Fixture f) throws Exception {
        return (Map<?, ?>) member(member(member(f.player, "session"), "ahead"), "cache");
    }
    private static int requested(Fixture f, int index) {
        return ((TLRPC.TL_channels_getMessages) f.network.requests.get(index)).id.get(0);
    }
    private static void lookAhead() throws Exception {
        try (Fixture f = new Fixture(0, 7)) {
            ArrayList<PurplePlaylistsView.Song> reads = new ArrayList<>();
            ArrayList<java.util.function.Consumer<PurplePlaylistsResolver.Result>> replies = new ArrayList<>();
            ArrayList<Boolean> closed = new ArrayList<>();
            PurplePlaylistsLookAhead ahead = new PurplePlaylistsLookAhead(f.songs, new PurplePlaylistsLookAhead.Port() {
                public boolean current() { return true; }
                public void defer(Runnable action) { AndroidUtilities.runOnUIThread(action, 1); }
                public AutoCloseable resolve(PurplePlaylistsView.Song song, java.util.function.Consumer<PurplePlaylistsResolver.Result> reply) {
                    int index = reads.size(); reads.add(song); replies.add(reply); closed.add(false);
                    return () -> closed.set(index, true);
                }
            });
            PurplePlaylistsView.Song current = f.songs.get(3), obsolete = f.songs.get(4);
            PurplePlaylistsResolver.Result ready = new PurplePlaylistsResolver.Result(current, current.source,
                    PurplePlaylistsResolver.Status.Ready, wire(current), 0);
            ahead.playing(ready, Arrays.asList(0, 1, 2, 3, 4, 5, 6), 3, PurplePlaylistsPlayback.Repeat.None); f.flush();
            check(reads.size() == 1 && reads.get(0) == obsolete, "one deferred look-ahead resolution admitted");
            replies.get(0).accept(new PurplePlaylistsResolver.Result(obsolete, obsolete.source,
                    PurplePlaylistsResolver.Status.Ready, wire(obsolete), 0));
            ahead.playing(ready, Arrays.asList(6, 5, 4, 3, 2, 1, 0), 3, PurplePlaylistsPlayback.Repeat.None); f.flush();
            check(reads.size() == 2 && reads.get(1) == f.songs.get(2)
                    && !((Map<?, ?>) member(ahead, "cache")).containsKey(obsolete),
                    "queued look-ahead completion cannot cross plan generation");
            check(closed.get(0), "replanning closes the owned pending resolver");
            ahead.close();
            PurplePlaylistsView.Song pending = reads.get(1);
            replies.get(1).accept(new PurplePlaylistsResolver.Result(pending, pending.source,
                    PurplePlaylistsResolver.Status.Ready, wire(pending), 0)); f.flush();
            check(closed.get(1) && ((Map<?, ?>) member(ahead, "cache")).isEmpty() && reads.size() == 2,
                    "closed look-ahead releases cache and rejects queued completions");
        }
        try (Fixture f = new Fixture(0, 7)) {
            f.start(0); f.reply();
            check(f.media.starts == 1 && f.network.requests.size() == 2,
                    "native current starts before first look-ahead reply");
            f.reply(); f.reply(); f.reply();
            check(f.network.requests.size() == 4 && cache(f).size() == 4,
                    "look-ahead resolves at most three following rows and retains four results");
            check(f.media.starts == 1 && f.player.snapshot().selectedDocumentId.equals(f.songs.get(0).documentId),
                    "prefetch neither starts audio nor changes selected row");
            int before = f.network.requests.size();
            f.player.next(false);
            check(f.network.requests.size() == before, "cached Next needs no foreground source read");
            f.flush(); f.selected(1, "cached Next keeps saved traversal");
            check(f.network.requests.size() == before + 1 && cache(f).size() <= 4,
                    "rolling look-ahead reads only the newly entering row");
            f.reply(); check(cache(f).size() == 4 && !cache(f).containsKey(f.songs.get(0)),
                    "rolling window evicts old current result");
        }
        try (Fixture f = new Fixture(0, 7)) {
            f.start(0); f.reply(); f.reply("FLOOD_WAIT_17", null);
            check(f.media.starts == 1 && f.network.requests.size() == 2,
                    "prefetch Wait halts later reads while current song continues");
            f.player.next(true); f.flush();
            PurplePlaylistsPlayer.State wait = f.player.snapshot();
            check(wait.status == PurplePlaylistsPlayer.Status.Wait && wait.waitSeconds == 17
                    && f.network.requests.size() == 2, "advancing to cached Wait reports it without a retry");
            f.player.next(true); f.flush();
            check(f.network.requests.size() == 2, "cached Wait never schedules automatic follow-up");
            check(f.player.retry(wait), "cached Wait permits fresh displayed explicit Retry"); f.flush();
            check(f.network.requests.size() == 3 && requested(f, 2) == f.songs.get(1).source.messageId,
                    "manual Retry discards cached Wait and reads the same row afresh");
            f.reply(); f.selected(1, "manual Wait Retry starts only requested saved row");
        }
        try (Fixture f = new Fixture(0, 7)) {
            f.start(0); f.reply(); f.reply(); f.reply(); f.reply();
            f.expire(); f.player.next(true); f.flush();
            check(f.media.starts == 1 && f.player.snapshot().status == PurplePlaylistsPlayer.Status.Retry,
                    "prefetched result cannot mint fresh automatic Play authority after expiry");
        }
        try (Fixture f = new Fixture(0, 7)) {
            f.start(0); f.reply(); f.reply(); f.reply(); f.reply();
            MessagesController.getInstance(0).getChat(71).min = true;
            f.player.next(false); f.flush();
            check(f.media.starts == 1 && f.player.snapshot().status == PurplePlaylistsPlayer.Status.Retry,
                    "cached wire still requires current peer metadata at native admission");
        }
        try (Fixture f = new Fixture(0, 7)) {
            f.start(0); f.reply();
            check(f.network.requests.size() == 2, "one owned look-ahead read is in flight");
            f.player.leave(); f.replyAt(1, null, null);
            check(f.network.cancelled.contains(2) && f.network.requests.size() == 2
                    && f.media.starts == 1 && f.player.snapshot().status == PurplePlaylistsPlayer.Status.Idle,
                    "retirement cancels look-ahead and late reply cannot continue reading");
        }
        try (Fixture f = new Fixture(0, 7)) {
            f.start(3); f.reply();
            int old = f.network.requests.size() - 1;
            SharedConfig.playOrderReversed = true; f.player.modes(); f.flush();
            int before = f.network.requests.size(); f.replyAt(old, null, null);
            check(f.network.requests.size() == before && !cache(f).containsKey(f.songs.get(4)),
                    "old look-ahead reply cannot seed a rebuilt traversal");
            f.reply(); f.reply(); f.reply();
            check(requested(f, before - 1) == f.songs.get(2).source.messageId && cache(f).size() == 4,
                    "reverse mode rebuild prefetches the actual following saved rows");
        }
        try (Fixture f = new Fixture(0, 7)) {
            SharedConfig.repeatMode = 1; f.start(6); f.reply(); f.reply(); f.reply(); f.reply();
            check(requested(f, 1) == f.songs.get(0).source.messageId && requested(f, 3) == f.songs.get(2).source.messageId,
                    "Repeat All look-ahead wraps without duplicating current");
        }
        try (Fixture f = new Fixture(0, 7)) {
            SharedConfig.repeatMode = 2; f.start(2); f.reply();
            check(f.network.requests.size() == 1 && cache(f).size() == 1, "Repeat One keeps only current resolved song");
            f.player.next(true); f.flush();
            check(f.network.requests.size() == 1 && f.media.starts == 2, "Repeat One restarts cached current without look-ahead reads");
        }
        try (Fixture f = new Fixture(0, 7)) {
            SharedConfig.shuffleMusic = true; f.start(3); f.reply(); f.reply(); f.reply(); f.reply();
            PurplePlaylistsPlayback queue = (PurplePlaylistsPlayback) member(member(f.player, "session"), "queue");
            List<Integer> traversal = queue.state().traversal;
            check(traversal.get(0) == 3 && requested(f, 1) == f.songs.get(traversal.get(1)).source.messageId
                    && requested(f, 3) == f.songs.get(traversal.get(3)).source.messageId,
                    "shuffle look-ahead follows the captured permutation without reshuffling");
        }
    }

    private static void observation() throws Exception {
        try (Fixture f = new Fixture(1)) {
            Watch watch = new Watch(), other = new Watch();
            PurplePlaylistsPlayer.Observation subscription = f.player.observe(1, watch);
            PurplePlaylistsPlayer.Observation different = f.player.observe(0, other);
            check(watch.states.isEmpty(), "observer delivery yields before touching screen");
            f.flush();
            check(watch.last().status == PurplePlaylistsPlayer.Status.Idle, "new observer receives immutable idle");
            f.start(1);
            PurplePlaylistsPlayer.State resolving = watch.last();
            check(resolving.status == PurplePlaylistsPlayer.Status.Resolving && resolving.account == 1
                    && resolving.selectedDocumentId.equals(f.songs.get(1).documentId) && resolving.playingDocumentId.isEmpty(),
                    "selected resolving row visible before native playback");
            check(!f.player.retry(resolving) && f.network.requests.size() == 1, "Retry unavailable during resolution");
            f.reply();
            PurplePlaylistsPlayer.State playing = watch.last();
            check(playing.status == PurplePlaylistsPlayer.Status.Playing && playing.sessionId == resolving.sessionId
                    && playing.revision > resolving.revision && playing.playingDocumentId.equals(resolving.selectedDocumentId),
                    "same session publishes actual playing row with increasing revision");
            check(resolving.status == PurplePlaylistsPlayer.Status.Resolving && resolving.playingDocumentId.isEmpty(),
                    "older playback snapshot is immutable");
            check(other.last().account == -1 && other.last().title.isEmpty() && other.last().sessionId == 0,
                    "observer of another account cannot see song metadata");
            f.media.pauseMessage(); f.flush();
            check(watch.last().status == PurplePlaylistsPlayer.Status.Paused && !watch.last().canRetry,
                    "manual Pause publishes paused without Retry");
            f.player.failed(f.media.playing); f.flush();
            PurplePlaylistsPlayer.State failed = watch.last();
            check(failed.canRetry && failed.status == PurplePlaylistsPlayer.Status.Retry
                    && failed.sourceStatus.equals("Failed"), "native failure is visible and explicitly retryable");
            check(f.player.retry(failed), "exact displayed failure admits fresh explicit Retry"); f.flush();
            check(!f.player.retry(failed), "same Retry snapshot cannot start a second command");
            f.reply("FLOOD_WAIT_23", null);
            PurplePlaylistsPlayer.State wait = watch.last();
            check(wait.status == PurplePlaylistsPlayer.Status.Wait && wait.canRetry && wait.waitSeconds == 23
                    && wait.sourceStatus.equals("Wait"), "server Wait exposes reported duration and manual Retry");
            int requests = f.network.requests.size(), notifications = watch.states.size(); f.flush();
            check(f.network.requests.size() == requests && watch.states.size() == notifications,
                    "observing Wait schedules no automatic retry or periodic notifications");
            f.player.leave();
            check(!f.player.retry(wait), "retired session refuses stale displayed Retry"); f.flush();
            check(watch.last().status == PurplePlaylistsPlayer.Status.Idle && watch.last().title.isEmpty(),
                    "retirement clears visible song metadata");
            subscription.close(); different.close();
        }
        try (Fixture f = new Fixture(0)) {
            Watch watch = new Watch(); PurplePlaylistsPlayer.Observation subscription = f.player.observe(0, watch);
            f.start(0); f.reply("FLOOD_WAIT_5", null);
            PurplePlaylistsPlayer.State stale = watch.last();
            f.start(0); f.reply("FLOOD_WAIT_5", null);
            check(stale.sessionId != watch.last().sessionId && !f.player.retry(stale),
                    "same row in replacement queue cannot inherit displayed Retry");
            PurplePlaylistsPlayer.State current = watch.last();
            PurplePlaylistsAccount.slots[0].live = false;
            check(!f.player.retry(current) && !f.player.active(), "retired account refuses displayed Retry");
            f.flush(); check(watch.last().status == PurplePlaylistsPlayer.Status.Idle,
                    "identity retirement is observed without leaking old account metadata");
            subscription.close();
        }
        try (Fixture f = new Fixture(0)) {
            Watch watch = new Watch(); PurplePlaylistsPlayer.Observation subscription = f.player.observe(0, watch);
            subscription.close(); f.start(0); f.reply();
            check(watch.states.isEmpty(), "closing observation suppresses even already queued delivery");
            check(f.player.active() && f.media.starts == 1, "screen observation lifetime never stops playback");
            subscription.close();
        }
        try (Fixture f = new Fixture(0)) {
            Watch watch = new Watch(); PurplePlaylistsPlayer.Observation subscription = f.player.observe(0, watch);
            f.start(0); f.reply();
            MessageObject music = f.media.playing;
            MessageObject voice = new MessageObject(0, wire(f.songs.get(2)), false, false); voice.voice = true;
            f.media.playMessage(voice); f.flush();
            check(watch.last().interrupted && !watch.last().canRetry
                    && watch.last().playingDocumentId.equals(f.songs.get(0).documentId),
                    "voice interruption publishes retained music without granting Retry");
            f.media.playing = music; f.media.paused = true; f.player.restored(music); f.flush();
            check(!watch.last().interrupted && watch.last().status == PurplePlaylistsPlayer.Status.Paused,
                    "voice restore publishes adopted paused music");
            int before = watch.states.size();
            f.player.failed(music); f.player.leave(); f.flush();
            check(watch.states.size() == before + 1 && watch.last().status == PurplePlaylistsPlayer.Status.Idle,
                    "queued obsolete failure is suppressed after retirement");
            subscription.close();
        }
        try (Fixture f = new Fixture(0)) {
            final PurplePlaylistsPlayer.Observation[] subscription = new PurplePlaylistsPlayer.Observation[1];
            Watch watch = new Watch();
            PurplePlaylistsPlayer.Listener listener = value -> { watch.playbackChanged(value); subscription[0].close(); };
            subscription[0] = f.player.observe(0, listener); f.flush(); f.start(0); f.reply();
            check(watch.states.size() == 1 && f.media.starts == 1, "listener may close itself without altering queue");
        }
    }

    private static void offlineRetained() throws Exception {
        ApplicationLoader.online = false;
        PurplePlaylistsRetained.offlineResolutions = 0;
        try {
            try (Fixture f = new Fixture(0, 1)) {
                PurplePlaylistsRetained.offlineMessage = wire(f.songs.get(0));
                PurplePlaylistsRetained.fileAvailable = true;
                check(f.player.start(PurplePlaylistsAccount.capture(0), f.songs, 0, "retained"),
                        "offline saved playlist accepts play");
                f.flush();
                f.selected(0, "offline selection opens the retained song");
                check(PurplePlaylistsRetained.offlineResolutions == 1 && f.network.requests.isEmpty(),
                        "offline saved playlist resolves locally without remote lookup");
            }
            try (Fixture f = new Fixture(0, 1)) {
                PurplePlaylistsRetained.offlineMessage = wire(f.songs.get(0));
                PurplePlaylistsRetained.fileAvailable = false;
                check(f.player.start(PurplePlaylistsAccount.capture(0), f.songs, 0, "retained"),
                        "offline request enters the saved playback actor with a missing binding");
                f.flush();
                check(f.media.starts == 0 && f.network.requests.isEmpty(),
                        "offline start refuses an unbound file without a network fallback");
                check(f.player.snapshot().canRetry, "unbound offline audio leaves explicit Retry available");
            }
        } finally {
            ApplicationLoader.online = true;
            PurplePlaylistsRetained.offlineMessage = null;
            PurplePlaylistsRetained.fileAvailable = false;
        }
    }

    public static void main(String[] args) throws Exception {
        offlineRetained();
        lookAhead();
        traversal(); presentedQueue(); fences(); failures(); interruptions();
        observation();
        android.os.Looper.off = true;
        boolean refused = false;
        try { new MediaController().purplePlaylists().active(); } catch (IllegalStateException e) { refused = true; }
        finally { android.os.Looper.off = false; }
        check(refused, "native playlist commands require main thread");
        System.out.println("PLAYLIST_NATIVE_PLAYER_CHECKS=" + checks);
    }
}
