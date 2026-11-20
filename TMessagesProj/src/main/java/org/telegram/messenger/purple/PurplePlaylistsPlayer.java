package org.telegram.messenger.purple;

import android.os.Looper;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class PurplePlaylistsPlayer {
    private final MediaController controller;
    private final PurpleSleepTimerAudio audio;
    private Session session;
    private int nativeDepth;
    private long sequence, revision;
    private State published = new State(null, null, 0, 0);
    private final ArrayList<Observation> observers = new ArrayList<>();

    public enum Status { Idle, Resolving, Playing, Paused, Retry, Wait, Exhausted, Closed }
    public interface Listener { void playbackChanged(State state); }

    public static final class State {
        public final Status status;
        public final int account, waitSeconds;
        public final long sessionId, revision;
        public final String playlistId, selectedDocumentId, playingDocumentId, title, sourceStatus;
        public final boolean interrupted, canRetry;

        private State(Session session, PurplePlaylistsPlayback.State state, long sessionId, long revision) {
            this.sessionId = sessionId; this.revision = revision;
            account = session == null ? -1 : session.account.identity.slot;
            playlistId = session == null ? "" : session.playlist;
            status = state == null ? Status.Idle : Status.valueOf(state.status.name());
            selectedDocumentId = state == null || state.selected < 0 ? "" : session.songs.get(state.selected).documentId;
            playingDocumentId = state == null || state.playing < 0 ? "" : session.songs.get(state.playing).documentId;
            title = state == null || state.selected < 0 ? "" : session.songs.get(state.selected).title;
            sourceStatus = state == null || state.sourceStatus == null ? "" : state.sourceStatus.name();
            waitSeconds = state == null ? 0 : state.waitSeconds;
            interrupted = session != null && session.interrupted;
            canRetry = !interrupted && (status == Status.Retry || status == Status.Wait);
        }
    }

    public static final class Queue {
        public final int account;
        public final List<PurplePlaylistsView.Song> songs;
        private Queue(int account, List<PurplePlaylistsView.Song> songs) {
            this.account = account;
            this.songs = songs;
        }
    }

    public Queue displayedQueue(int account) {
        main();
        Session owner = current();
        return owner != null && owner.account.identity.slot == account && UserConfig.selectedAccount == account
                && !owner.interrupted && same(owner.message, controller.getPlayingMessageObject()) ? owner.presentation : null;
    }

    public boolean ownsQueue(Queue expected) {
        main();
        Session owner = current();
        return owner != null && expected != null && owner.presentation == expected
                && UserConfig.selectedAccount == expected.account && !owner.interrupted;
    }

    public boolean selectQueueSong(Queue expected, int index) {
        if (!ownsQueue(expected) || index < 0 || index >= expected.songs.size()) return false;
        Session owner = session;
        PurplePlaylistsPlayback.State state = owner.queue.state();
        if (state.playing == index && state.selected == index
                && same(owner.message, controller.getPlayingMessageObject())) {
            if (state.status == PurplePlaylistsPlayback.Status.Paused)
                return controller.playMessage(owner.message);
            if (state.status == PurplePlaylistsPlayback.Status.Playing)
                return controller.pauseMessage(owner.message);
        }
        owner.modes();
        return owner.queue.play(index);
    }

    public final class Observation implements AutoCloseable {
        private final int account;
        private final WeakReference<Listener> listener;
        private boolean closed;

        private Observation(int account, Listener listener) {
            this.account = account; this.listener = new WeakReference<>(listener);
        }
        private void deliver(State expected) {
            if (closed || snapshot() != expected) return;
            Listener target = listener.get();
            if (target == null) { close(); return; }
            target.playbackChanged(expected.account == account ? expected : new State(null, null, 0, expected.revision));
        }
        @Override public void close() {
            main(); closed = true; listener.clear(); observers.remove(this);
        }
    }

    public Observation observe(int account, Listener listener) {
        main();
        if (listener == null) throw new IllegalArgumentException("Missing playback listener");
        Observation observation = new Observation(account, listener);
        observers.add(observation);
        State initial = snapshot();
        AndroidUtilities.runOnUIThread(() -> observation.deliver(initial), 1);
        return observation;
    }

    public State snapshot() { main(); current(); return published; }

    public boolean retry(State expected) {
        main();
        return expected != null && snapshot() == expected && expected.canRetry && controls()
                && session.id == expected.sessionId && session.queue.retry();
    }

    private void publish(Session owner, PurplePlaylistsPlayback.State state) {
        if (owner != session) return;
        State next = new State(owner, state, owner == null ? 0 : owner.id, ++revision);
        published = next;
        for (Observation observation : new ArrayList<>(observers)) {
            if (observation.listener.get() == null) observation.close();
            else AndroidUtilities.runOnUIThread(() -> observation.deliver(next), 1);
        }
    }

    public PurplePlaylistsPlayer(MediaController controller, PurpleSleepTimerAudio audio) {
        this.controller = controller; this.audio = audio;
    }

    private static void main() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Playlist playback requires main thread");
    }

    public boolean start(int account, List<PurplePlaylistsView.Song> songs, int selected) {
        main();
        PurplePlaylistsAccount.Captured capture = PurplePlaylistsAccount.capture(account);
        return start(capture, songs, selected);
    }

    boolean start(PurplePlaylistsAccount.Captured capture, List<PurplePlaylistsView.Song> songs, int selected) {
        main();
        if (capture == null || !capture.current() || songs == null || selected < 0 || selected >= songs.size()) return false;
        return start(capture, songs, selected, "");
    }

    boolean start(PurplePlaylistsAccount.Captured capture, List<PurplePlaylistsView.Song> songs, int selected, String playlist) {
        main();
        if (capture == null || !capture.current() || songs == null || selected < 0 || selected >= songs.size()) return false;
        Session next = new Session(capture, songs, playlist);
        Session previous = session;
        leave();
        if (previous != null && previous.message != null)
            nativeCall(() -> { controller.purplePlaylistRelease(same(controller.getPlayingMessageObject(), previous.message)); return true; });
        session = next;
        return next.queue.play(selected);
    }

    public void leave() {
        main();
        Session previous = session; session = null;
        if (previous != null) { previous.close(); publish(null, null); }
    }

    private Session current() {
        Session value = session;
        if (value != null && !value.current()) {
            leave();
            if (value.message != null)
                nativeCall(() -> { controller.purplePlaylistRelease(same(controller.getPlayingMessageObject(), value.message)); return true; });
            return null;
        }
        return value;
    }

    public boolean active() { main(); return current() != null; }

    public boolean controls() {
        main();
        Session value = current();
        return value != null && !value.interrupted;
    }

    public boolean next(boolean ended) {
        if (!controls()) return false;
        Session value = session; value.modes(); return value.queue.next(ended);
    }

    public boolean resumeAfterEnd() {
        if (!controls()) return false;
        Session value = session; value.modes();
        PurplePlaylistsPlayback.State state = value.queue.state();
        int position = state.traversal.indexOf(state.selected);
        if (repeat() == PurplePlaylistsPlayback.Repeat.One
                || (repeat() != PurplePlaylistsPlayback.Repeat.All && position == state.traversal.size() - 1))
            value.queue.play(state.selected);
        else value.queue.next(false);
        return true;
    }

    public boolean previous() {
        if (!controls()) return false;
        MessageObject playing = controller.getPlayingMessageObject();
        if (same(playing, session.message) && playing.audioProgressSec > 10) {
            controller.seekToProgress(playing, 0); return true;
        }
        Session value = session; value.modes(); return value.queue.previous();
    }

    public boolean index(int index) {
        if (!controls()) return false;
        if (index == 0 && session.queue.state().selected >= 0)
            session.queue.play(session.queue.state().selected);
        return true;
    }

    public boolean retry() {
        if (!controls()) return false;
        return session.queue.retry();
    }

    public boolean modes() {
        if (!controls()) return false;
        session.modes(); return true;
    }

    public void pause(MessageObject message) {
        main();
        Session value = current();
        if (nativeDepth == 0 && value != null && !value.interrupted
                && (same(message, value.message) || value.message == null)) value.queue.paused();
    }

    public void stopped(boolean stopService, boolean transfer) {
        main();
        if (nativeDepth == 0 && stopService && !transfer && !audio.retiring()
                && !PurpleSleepTimer.get().localPause()) leave();
    }

    public boolean play(MessageObject message, BooleanSupplier stock) {
        main();
        if (nativeDepth != 0) return stock.getAsBoolean();
        Session value = current();
        if (value == null || message == null) return stock.getAsBoolean();
        if (same(message, value.message)) {
            PurplePlaylistsPlayback.Status state = value.queue.state().status;
            if (audio.automaticContext() && state != PurplePlaylistsPlayback.Status.Playing
                    && state != PurplePlaylistsPlayback.Status.Paused) return false;
            if (!value.valid(value.result, message)) return false;
            boolean played = stock.getAsBoolean();
            if (played && value.current()) {
                value.interrupted = false;
                if (!audio.automaticContext() || value.queue.state().status == PurplePlaylistsPlayback.Status.Paused)
                    value.queue.resumed();
            }
            return played;
        }
        if (message.isVoice() || message.isRoundVideo() || message.isVideo()) {
            value.interrupted = true; value.queue.paused();
        } else {
            if (audio.automaticContext()) return false;
            leave();
        }
        boolean played = stock.getAsBoolean();
        if (!played && value.current() && value.interrupted) {
            value.interrupted = false;
            if (!controller.isMessagePaused()) value.queue.resumed();
            else publish(value, value.queue.state());
        }
        return played;
    }

    public void restored(MessageObject message) {
        main();
        Session value = current();
        if (value == null || !same(message, value.message)) return;
        value.interrupted = false;
        if (controller.isMessagePaused()) value.queue.paused(); else value.queue.resumed();
    }

    public void failed(MessageObject message) {
        main();
        Session value = current();
        if (value != null && same(message, value.message)) value.queue.nativeFailed();
    }

    public static boolean same(MessageObject first, MessageObject second) {
        if (first == null || second == null || first.currentAccount != second.currentAccount
                || first.getDialogId() != second.getDialogId() || first.getId() != second.getId()
                || (first.eventId == 0) != (second.eventId == 0)) return false;
        if (!first.isMusic() && !second.isMusic()) return true;
        return first.isMusic() && second.isMusic() && first.getDocument() != null
                && second.getDocument() != null && first.getDocument().id == second.getDocument().id;
    }

    private boolean nativeCall(BooleanSupplier action) {
        ++nativeDepth;
        try { return action.getAsBoolean(); } finally { --nativeDepth; }
    }

    private final class Session implements PurplePlaylistsPlayback.Port {
        final long id = ++sequence;
        final String playlist;
        final List<PurplePlaylistsView.Song> songs;
        final PurplePlaylistsAccount.Captured account;
        final PurplePlaylistsPlayback queue;
        final PurplePlaylistsLookAhead ahead;
        MessageObject message;
        PurplePlaylistsResolver.Result result;
        boolean interrupted;

        final Queue presentation;
        Session(PurplePlaylistsAccount.Captured account, List<PurplePlaylistsView.Song> songs, String playlist) {
            this.playlist = playlist; this.account = account; this.songs = Collections.unmodifiableList(new ArrayList<>(songs));
            presentation = new Queue(account.identity.slot, this.songs);
            ahead = new PurplePlaylistsLookAhead(this.songs, new PurplePlaylistsLookAhead.Port() {
                @Override public boolean current() { return Session.this.current(); }
                @Override public void defer(Runnable action) { Session.this.defer(action); }
                @Override public AutoCloseable resolve(PurplePlaylistsView.Song song, Consumer<PurplePlaylistsResolver.Result> done) {
                    return rawResolve(song, done);
                }
            });
            queue = new PurplePlaylistsPlayback(songs, order(), repeat(), this, new Random());
        }

        @Override public boolean current() {
            return session == this && account.current() && MediaController.getInstance() == controller;
        }
        void close() { ahead.close(); queue.close(); }
        void modes() {
            queue.modes(order(), repeat());
            updateAhead(queue.state());
        }
        void updateAhead(PurplePlaylistsPlayback.State state) {
            if (state.status == PurplePlaylistsPlayback.Status.Playing && !interrupted)
                ahead.playing(result, state.traversal, state.selected, repeat());
            else {
                ahead.stop();
                if ((state.status == PurplePlaylistsPlayback.Status.Retry || state.status == PurplePlaylistsPlayback.Status.Wait)
                        && state.selected >= 0) ahead.forget(songs.get(state.selected));
            }
        }
        @Override public void defer(Runnable action) { AndroidUtilities.runOnUIThread(action, 1); }
        @Override public AutoCloseable resolve(PurplePlaylistsView.Song song,
                Consumer<PurplePlaylistsResolver.Result> done) {
            PurplePlaylistsResolver.Result cached = ahead.take(song);
            if (cached != null && (playlist.isEmpty() || cached.offline == !ApplicationLoader.isNetworkOnline())) {
                done.accept(cached); return () -> {};
            }
            return rawResolve(song, done);
        }
        AutoCloseable rawResolve(PurplePlaylistsView.Song song, Consumer<PurplePlaylistsResolver.Result> done) {
            if (!playlist.isEmpty() && !ApplicationLoader.isNetworkOnline())
                return PurplePlaylistsRetained.prepareOffline(account, playlist, song, this::current, done);
            java.util.concurrent.atomic.AtomicBoolean canceled = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<AutoCloseable> retained = new java.util.concurrent.atomic.AtomicReference<>();
            PurplePlaylistsResolver resolver = new PurplePlaylistsResolver(
                    new PurplePlaylistsTelegram(account, this::current), account.identity.userId,
                    PurpleCopyPolicy.unrestricted(), Collections.singletonList(song), rows -> {
                        if (canceled.get() || !current()) return;
                        PurplePlaylistsResolver.Result result = rows.get(0);
                        if (playlist.isEmpty() || !result.playable()) done.accept(result);
                        else retained.set(PurplePlaylistsRetained.prepare(account, playlist, result,
                                () -> !canceled.get() && current(), done));
                    });
            resolver.start();
            return () -> {
                canceled.set(true); resolver.close();
                AutoCloseable pending = retained.getAndSet(null);
                if (pending != null) pending.close();
            };
        }
        @Override public PurplePlaylistsPlayback.Gate gate(boolean automatic) {
            if (automatic) {
                PurpleSleepTimerRules.Ticket ticket = audio.capture();
                return action -> audio.playlistAutomatic(ticket, action);
            }
            PurpleSleepTimerRules.PlayRequest request = PurpleSleepTimer.get().requestPlay(PurpleSleepTimerRules.Slot.AUDIO);
            return action -> current() && PurpleSleepTimer.get().acceptPlay(request) && action.getAsBoolean();
        }

        boolean valid(PurplePlaylistsResolver.Result candidate, MessageObject actual) {
            if (!current() || candidate == null || !candidate.playable() || actual == null
                    || actual.currentAccount != account.identity.slot
                    || actual.eventId != 0 || actual.scheduled || !actual.isMusic()) return false;
            MessagesController metadata = MessagesController.getInstance(account.identity.slot);
            TLRPC.Peer peer = actual.messageOwner.peer_id;
            if (peer == null) return false;
            long chatId = peer instanceof TLRPC.TL_peerChannel ? peer.channel_id
                    : peer instanceof TLRPC.TL_peerChat ? peer.chat_id : 0;
            TLRPC.Chat chat = chatId == 0 ? null : metadata.getChat(chatId);
            if (candidate.offline) {
                if (PurplePlaylistsRetained.path(actual.currentAccount, actual.getDocument(), false) == null) return false;
            } else if (chatId != 0 && (chat == null || chat.min)) {
                return false;
            }
            boolean restricted = chat != null && metadata.getRestrictionReason(chat.restriction_reason) != null;
            PurplePlaylistsResolver.Reply reply = new PurplePlaylistsResolver.Reply(actual.messageOwner,
                    null, 0, (candidate.offline && candidate.protectedPeer) || (chat != null && chat.noforwards), restricted);
            return PurplePlaylistsResolver.qualify(candidate.song, candidate.source, reply,
                    account.identity.userId, PurpleCopyPolicy.unrestricted())
                    == PurplePlaylistsResolver.Status.Ready;
        }

        @Override public boolean start(PurplePlaylistsResolver.Result value, boolean restart) {
            if (value.offline && ApplicationLoader.isNetworkOnline()) return false;
            MessageObject next = new MessageObject(account.identity.slot, value.message, false, false);
            if (!valid(value, next)) return false;
            MessageObject previous = message;
            PurplePlaylistsResolver.Result previousResult = result;
            message = next; result = value; interrupted = false;
            boolean started = nativeCall(() -> controller.purplePlaylistStart(next, restart));
            if (!started && current()) { message = previous; result = previousResult; }
            return started;
        }
        @Override public void exhausted(boolean automatic) {
            nativeCall(() -> { controller.cleanupPlayer(true, true); return true; });
        }
        @Override public void changed(PurplePlaylistsPlayback.State state) { publish(this, state); updateAhead(state); }
    }

    private static PurplePlaylistsPlayback.Order order() {
        return SharedConfig.shuffleMusic ? PurplePlaylistsPlayback.Order.Shuffle
                : SharedConfig.playOrderReversed ? PurplePlaylistsPlayback.Order.Reverse : PurplePlaylistsPlayback.Order.Normal;
    }
    private static PurplePlaylistsPlayback.Repeat repeat() {
        return SharedConfig.repeatMode == 2 ? PurplePlaylistsPlayback.Repeat.One
                : SharedConfig.repeatMode == 1 ? PurplePlaylistsPlayback.Repeat.All : PurplePlaylistsPlayback.Repeat.None;
    }
}
