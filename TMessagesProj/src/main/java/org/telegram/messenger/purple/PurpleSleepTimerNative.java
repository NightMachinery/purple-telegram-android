package org.telegram.messenger.purple;

import androidx.media3.common.Player;
import androidx.media3.common.ForwardingPlayer;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

/** Isolates native PiP session transport from the automatic stash restoration path. */
public final class PurpleSleepTimerNative {
    private static final WeakHashMap<Player, Ticket> stashed = new WeakHashMap<>();
    private static final WeakHashMap<Player, WeakReference<Player>> wrappers = new WeakHashMap<>();
    private PurpleSleepTimerNative() {}

    public static void stash(Player player) {
        final PurpleSleepTimer timer = PurpleSleepTimer.get();
        final Slot slot = timer.nativeSlot(player);
        if (slot != null) stashed.put(player, timer.ticket(slot));
    }

    public static boolean unstash(Player player) {
        final Ticket ticket = stashed.remove(player);
        return PurpleSleepTimer.get().nativeSlot(player) == null || PurpleSleepTimer.get().automatic(ticket);
    }

    public static Player transport(Player player, java.util.function.Supplier<androidx.media3.session.MediaSession> session) {
        if (player == null || PurpleSleepTimer.get().nativeSlot(player) == null) return player;
        final WeakReference<Player> ref = wrappers.get(player);
        final Player existing = ref == null ? null : ref.get();
        if (existing != null) return existing;
        final Player wrapper = new ForwardingPlayer(player) {
            private boolean forward(boolean play) {
                final androidx.media3.session.MediaSession current = session.get();
                return PurpleSleepTimer.get().nativeCommand(player, play,
                        current != null && current.getControllerForCurrentRequest() != null);
            }
            @Override public void play() { if (forward(true)) super.play(); }
            @Override public void pause() { if (forward(false)) super.pause(); }
            @Override public void setPlayWhenReady(boolean value) { if (forward(value)) super.setPlayWhenReady(value); }
        };
        wrappers.put(player, new WeakReference<>(wrapper));
        return wrapper;
    }
}
