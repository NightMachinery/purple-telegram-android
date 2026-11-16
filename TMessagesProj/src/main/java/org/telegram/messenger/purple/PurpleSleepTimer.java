package org.telegram.messenger.purple;

import android.os.Looper;
import android.os.SystemClock;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.ui.Components.VideoPlayer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

/** Main-thread owner. Deadlines are volatile process state; only choices enter local preferences. */
public final class PurpleSleepTimer {
    public interface Port {
        boolean eligibleEnd();
        boolean playing();
        void fenceAutomaticResumes();
        void pauseLocal();
        void finishVoiceLocally();
        default void refreshReceipt() {}
        default boolean prepareEnd(Runnable accept) { return false; }
    }
    public interface Listener { void changed(); }
    public interface Feedback { void show(int event); }
    public static final int STARTED = 1, CHANGED = 2, CANCELLED = 3, EXPIRED = 4, NOTHING = 5;
    private static final PurpleSleepTimer INSTANCE = new PurpleSleepTimer();
    public static PurpleSleepTimer get() { return INSTANCE; }

    private static final class Entry {
        final Port port;
        final VideoPlayer player;
        final Object authority;
        Ticket ticket;
        Entry(Port port, VideoPlayer player, Ticket ticket, Object authority) {
            this.port = port;
            this.player = player;
            this.ticket = ticket;
            this.authority = authority;
        }
    }
    private static final class StateDispatch {
        final VideoPlayer owner;
        final androidx.media3.common.Player nativePlayer;
        final Object nativeItem;
        final EndEvent event;
        StateDispatch(VideoPlayer owner, EndEvent event) {
            this.owner = owner;
            nativePlayer = owner.player;
            nativeItem = nativePlayer == null ? null : nativePlayer.getCurrentMediaItem();
            this.event = event;
        }
    }
    public static final class Deferred {
        private final VideoPlayer owner;
        private final androidx.media3.common.Player nativePlayer;
        private final EndEvent event;
        private Deferred(VideoPlayer owner, EndEvent event) {
            this.owner = owner;
            nativePlayer = owner.player;
            this.event = event;
        }
    }

    public Deferred defer(VideoPlayer player) {
        final Entry entry = registered.get(player);
        return entry == null ? null : new Deferred(player, rules.captureEnd(entry.ticket.slot));
    }

    public boolean allowDeferred(VideoPlayer player, Deferred deferred) {
        if (deferred == null) return true;
        main(); check();
        return player == deferred.owner && player != null && player.player == deferred.nativePlayer
                && deferred.event.generation == rules.generation() && rules.autoStart(deferred.event.ticket);
    }

    private StateDispatch dispatch;

    private final PurpleSleepTimerRules rules = new PurpleSleepTimerRules();
    private final EnumMap<Slot, Entry> entries = new EnumMap<>(Slot.class);
    private final Map<VideoPlayer, Entry> registered = java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private final ArrayList<WeakReference<Listener>> listeners = new ArrayList<>();
    private Runnable wakeup;
    private Feedback feedback;
    private boolean foreground, pendingExpiryToast, checking;
    private int localPauseDepth;

    private PurpleSleepTimer() {}

    private static void main() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Sleep timer requires UI thread");
    }

    public static Item item(MessageObject message) {
        if (message == null || message.getDocument() == null) return null;
        final Kind kind = message.isMusic() ? Kind.SONG : message.isRoundVideo() ? Kind.ROUND
                : message.isVoice() ? Kind.VOICE : message.isVideo() ? Kind.VIDEO : null;
        return kind == null ? null : new Item(message.currentAccount, message.getDialogId(), message.getId(),
                message.getDocument().id, kind);
    }

    public void feedback(Feedback value) { main(); feedback = value; }
    public void listen(Listener listener) { main(); listeners.add(new WeakReference<>(listener)); }
    public void unlisten(Listener listener) {
        main();
        listeners.removeIf(ref -> ref.get() == null || ref.get() == listener);
    }
    public Mode mode() { return rules.mode(); }
    public boolean endBound(Slot slot) { return rules.matchesEnd(rules.captureEnd(slot), true); }
    public long generation() { return rules.generation(); }
    public long remaining() { return rules.remaining(SystemClock.elapsedRealtime()); }
    public boolean loaded() { return rules.loaded(); }
    public boolean eligible(Slot slot) {
        final Entry entry = entries.get(slot);
        return entry != null && rules.eligible(slot) && entry.port.eligibleEnd();
    }
    public Item current(Slot slot) { return rules.current(slot); }
    public Item boundItem() { return rules.boundItem(); }
    public Ticket ticket(Slot slot) { return rules.ticket(slot); }
    public boolean blocked(Slot slot) { return rules.blocked(slot); }
    public boolean nativeCommand(androidx.media3.common.Player player, boolean play, boolean explicit) {
        final Slot slot = nativeSlot(player);
        if (slot == null) return true;
        main(); check();
        if (!rules.transport(slot, play, explicit, ticket(slot))) return false;
        if (!explicit) return true;
        refreshTickets();
        final Entry entry = entries.get(slot);
        if (entry != null && entry.player != null) {
            if (play) entry.player.play(); else entry.player.pause();
            return false;
        }
        return true;
    }

    public Slot nativeSlot(androidx.media3.common.Player player) {
        if (player == null) return null;
        for (Map.Entry<Slot, Entry> entry : entries.entrySet()) {
            if (entry.getValue().player != null && entry.getValue().player.player == player) return entry.getKey();
        }
        return null;
    }
    public boolean owns(Slot slot, VideoPlayer player) {
        final Entry entry = entries.get(slot);
        return entry != null && entry.player == player;
    }

    public void attach(Slot slot, Item item, boolean eligible, VideoPlayer player, Port port) {
        attach(slot, item, eligible, player, port, player);
    }

    public void attachBackend(Item item, boolean eligible, Object authority, Port port) {
        attach(Slot.VIEWER, item, eligible, null, port, authority);
    }

    private void attach(Slot slot, Item item, boolean eligible, VideoPlayer player, Port port, Object authority) {
        main();
        final Entry old = entries.remove(slot);
        if (old != null && old.player != null) registered.remove(old.player);
        final Mode previousMode = rules.mode();
        final Item previousItem = rules.current(slot);
        rules.loaded(slot, item, eligible);
        if (old != null && old.authority != authority && java.util.Objects.equals(previousItem, item)) rules.replaceIncarnation(slot);
        if (previousMode != Mode.OFF && rules.mode() == Mode.OFF && rules.loaded()) tell(CANCELLED);
        final Entry entry = new Entry(port, player, rules.ticket(slot), authority);
        entries.put(slot, entry);
        if (player != null) registered.put(player, entry);
        check();
        publish();
    }

    public void detach(Ticket ticket) {
        main();
        final Mode previousMode = rules.mode();
        if (!rules.unload(ticket)) return;
        if (previousMode != Mode.OFF && rules.mode() == Mode.OFF && rules.loaded()) tell(CANCELLED);
        final Entry old = entries.remove(ticket.slot);
        if (old != null && old.player != null) registered.remove(old.player);
        publish();
    }

    public boolean handoff(Ticket ticket, Slot to, Port port) {
        main();
        if (!rules.handoff(ticket, to)) return false;
        final Entry source = entries.remove(ticket.slot);
        final Entry previous = entries.remove(to);
        if (previous != null && previous.player != null) registered.remove(previous.player);
        if (source != null) {
            final Entry moved = new Entry(port, source.player, rules.ticket(to), source.authority);
            entries.put(to, moved);
            if (source.player != null) registered.put(source.player, moved);
        }
        publish();
        return true;
    }

    public boolean startMinutes(int minutes, boolean custom) {
        main();
        check();
        if (!rules.timed(SystemClock.elapsedRealtime(), minutes * MINUTE)) { tell(NOTHING); return false; }
        final android.content.SharedPreferences.Editor edit = MessagesController.getGlobalMainSettings().edit();
        edit.putInt("purple-sleep-last-choice", custom ? -1 : minutes);
        if (custom) edit.putInt("purple-sleep-custom-minutes", minutes);
        edit.apply();
        tell(STARTED);
        publish();
        return true;
    }

    public boolean startEnd(Slot slot) {
        main(); check();
        if (!eligible(slot)) { tell(NOTHING); return false; }
        final Entry entry = entries.get(slot);
        if (entry != null && entry.port.prepareEnd(() -> {
            if (entries.get(slot) == entry) startEndPrepared(slot);
        })) return true;
        return startEndPrepared(slot);
    }

    private boolean startEndPrepared(Slot slot) {
        main();
        check();
        if (!eligible(slot) || !rules.end(slot)) { tell(NOTHING); return false; }
        MessagesController.getGlobalMainSettings().edit().putInt("purple-sleep-last-choice", 0).apply();
        tell(STARTED);
        publish();
        return true;
    }

    public void addMinutes(int minutes) {
        main();
        check();
        if (rules.add(SystemClock.elapsedRealtime(), minutes * MINUTE)) {
            tell(CHANGED);
            publish();
        }
    }

    public int customMinutes() {
        if (rules.mode() == Mode.TIMED) return (int) Math.max(1, (remaining() + MINUTE - 1) / MINUTE);
        return Math.max(1, Math.min(720, MessagesController.getGlobalMainSettings().getInt("purple-sleep-custom-minutes", 45)));
    }

    public void toggleRemembered() {
        main(); check();
        if (rules.mode() != Mode.OFF) { cancel(true); return; }
        final int choice = MessagesController.getGlobalMainSettings().getInt("purple-sleep-last-choice", 30);
        if (choice != 0) { startMinutes(choice == -1 ? customMinutes() : choice, choice == -1); return; }
        final Item audio = rules.current(Slot.AUDIO);
        if (audio != null && (audio.kind == Kind.VOICE || audio.kind == Kind.ROUND) && eligible(Slot.AUDIO)) {
            startEnd(Slot.AUDIO);
        } else if (eligible(Slot.VIEWER)) {
            startEnd(Slot.VIEWER);
        } else {
            startEnd(Slot.AUDIO);
        }
    }

    public void cancel(boolean announce) {
        main();
        final boolean active = rules.mode() != Mode.OFF;
        rules.cancel();
        if (active && announce) tell(CANCELLED);
        publish();
    }

    public void foreground(boolean active) {
        main();
        foreground = active;
        check();
        if (active && pendingExpiryToast) { pendingExpiryToast = false; tell(EXPIRED); }
    }

    public void check() {
        main();
        if (checking || !rules.due(SystemClock.elapsedRealtime())) return;
        checking = true;
        try { expire(null, null); } finally { checking = false; }
    }

    public void dispatchState(VideoPlayer player, Runnable delegate) {
        final Entry entry = registered.get(player);
        if (entry == null) { delegate.run(); return; }
        main();
        final StateDispatch previous = dispatch;
        dispatch = new StateDispatch(player, rules.captureEnd(entry.ticket.slot));
        try { delegate.run(); } finally { dispatch = previous; }
    }

    public boolean realEnd(VideoPlayer player) {
        main(); check();
        final Entry entry = registered.get(player);
        final StateDispatch event = dispatch;
        if (entry == null || event == null || event.owner != player || event.nativePlayer == null
                || event.nativePlayer != player.player
                || !java.util.Objects.equals(event.nativeItem, player.player.getCurrentMediaItem())
                || player.player.getPlaybackState() != androidx.media3.common.Player.STATE_ENDED
                || !rules.matchesEnd(event.event, true)) return false;
        final Item ended = event.event.ticket.item;
        expire(ended.kind == Kind.SONG ? ended : null,
                ended.kind == Kind.VOICE || ended.kind == Kind.ROUND ? entry.port : null);
        return true;
    }

    private void expire(Item endedSong, Port voiceFinish) {
        boolean audible = false;
        final ArrayList<Entry> snapshot = new ArrayList<>(entries.values());
        for (Entry entry : snapshot) audible |= entry.port.playing();
        rules.expire(endedSong);
        refreshTickets();
        for (Entry entry : snapshot) entry.port.fenceAutomaticResumes();
        localPauseDepth++;
        try {
            for (Entry entry : snapshot) entry.port.pauseLocal();
            if (voiceFinish != null) voiceFinish.finishVoiceLocally();
        } finally { localPauseDepth--; }
        if (audible || endedSong != null || voiceFinish != null) {
            if (foreground) tell(EXPIRED); else pendingExpiryToast = true;
        }
        publish();
    }

    public static final class BackendReceipt {
        private final Object authority;
        private final EndEvent event;
        private BackendReceipt(Object authority, EndEvent event) { this.authority = authority; this.event = event; }
    }
    public BackendReceipt captureBackend(Object authority) {
        final Entry entry = entries.get(Slot.VIEWER);
        return entry == null || entry.authority != authority ? null : new BackendReceipt(authority, rules.captureEnd(Slot.VIEWER));
    }
    public boolean validBackend(BackendReceipt receipt) {
        main(); check();
        final Entry entry = entries.get(Slot.VIEWER);
        return receipt != null && entry != null && entry.authority == receipt.authority
                && receipt.event.generation == rules.generation() && rules.valid(receipt.event.ticket);
    }
    public boolean startBackend(BackendReceipt receipt) {
        return validBackend(receipt) && rules.autoStart(receipt.event.ticket);
    }
    public boolean realBackendEnd(BackendReceipt receipt, boolean actualEnd) {
        if (!validBackend(receipt) || !rules.matchesEnd(receipt.event, actualEnd)) return false;
        expire(null, null);
        return true;
    }

    public boolean localPause() { return localPauseDepth != 0; }

    public boolean locally(java.util.function.BooleanSupplier action) {
        main();
        localPauseDepth++;
        try { return action.getAsBoolean(); } finally { localPauseDepth--; }
    }

    public boolean beforeStart(VideoPlayer player) {
        if (registered.get(player) == null) return true;
        main(); check();
        final Entry entry = registered.get(player);
        return entry == null || rules.autoStart(entry.ticket);
    }

    public boolean valid(Ticket ticket) {
        main(); check();
        return rules.valid(ticket);
    }

    public boolean automatic(Ticket ticket) {
        main(); check();
        return rules.autoStart(ticket);
    }

    public void explicitPlay(Slot slot) {
        main(); check();
        rules.manualPlay(slot);
        refreshTickets();
    }

    public void routePlay(Slot slot) {
        main(); check();
        if (rules.expiryBlocked(slot)) return;
        rules.manualPlay(slot);
        refreshTickets();
    }

    public void explicitPause(Slot slot) {
        main(); check();
        rules.manualPause(slot);
        refreshTickets();
    }

    public PlayRequest requestPlay(Slot slot) {
        main(); check();
        return rules.requestPlay(slot);
    }

    public boolean acceptPlay(PlayRequest request) {
        main(); check();
        return rules.acceptPlay(request);
    }

    /** Wraps delayed stock work ending in explicit Play: it runs once, only while the request made now is current. */
    public Runnable deferPlay(MessageObject message, Runnable stock) {
        final PlayRequest request = requestPlay(message != null && message.isVideo() ? Slot.VIEWER : Slot.AUDIO);
        return () -> {
            if (acceptPlay(request)) stock.run();
        };
    }

    public void seek(Slot slot) { main(); rules.seek(slot); }
    public boolean takePendingAdvance(Item item, boolean explicitItemPlay) {
        main(); check();
        return rules.takePendingAdvance(item, explicitItemPlay) != null;
    }

    private void refreshTickets() {
        for (Map.Entry<Slot, Entry> entry : entries.entrySet()) entry.getValue().ticket = rules.ticket(entry.getKey());
        for (Entry entry : new ArrayList<>(entries.values())) entry.port.refreshReceipt();
    }

    private void tell(int event) { if (feedback != null) feedback.show(event); }

    private void publish() {
        for (Entry entry : new ArrayList<>(entries.values())) entry.port.refreshReceipt();
        if (wakeup != null) AndroidUtilities.cancelRunOnUIThread(wakeup);
        wakeup = null;
        final long generation = rules.generation();
        if (rules.mode() == Mode.TIMED) {
            final long delay = Math.max(1, Math.min(remaining(), remaining() <= MINUTE ? 1000 : MINUTE));
            wakeup = () -> {
                if (generation != rules.generation()) return;
                check();
                if (generation == rules.generation()) publish();
            };
            AndroidUtilities.runOnUIThread(wakeup, delay);
        }
        final ArrayList<WeakReference<Listener>> snapshot = new ArrayList<>(listeners);
        listeners.removeIf(ref -> ref.get() == null);
        for (WeakReference<Listener> ref : snapshot) {
            final Listener listener = ref.get();
            if (listener != null) listener.changed();
        }
    }
}
