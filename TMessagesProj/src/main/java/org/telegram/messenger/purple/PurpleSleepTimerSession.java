package org.telegram.messenger.purple;

import java.util.Objects;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

/**
 * Media session commands that finish after the callback returns. Prepare selects silently and
 * never lifts the expiry fence; a later Play plays the prepared target. A lazily loaded Play
 * keeps the receipt taken when the command arrived. The newest command, an account switch or
 * session release revokes older ones. Main thread only, like the session callback.
 */
public final class PurpleSleepTimerSession {
    public interface Target { void play(String mediaId); }

    private static final class Command {
        final long generation;
        final Ticket before;
        PlayRequest play;
        boolean prepare, loading;
        Command(long generation, Ticket before, PlayRequest play) {
            this.generation = generation;
            this.before = before;
            this.play = play;
        }
    }

    private static final class Prepared {
        final String mediaId;
        final long generation;
        final Ticket before;
        Prepared(String mediaId, Command command) {
            this.mediaId = mediaId;
            generation = command.generation;
            before = command.before;
        }
    }

    private final PurpleSleepTimer timer = PurpleSleepTimer.get();
    private long generation;
    private Command latest, entering;
    private Prepared prepared;

    /** Runs a prepare callback; the selection it reaches, now or after a lazy load, stays silent. */
    public void prepare(Runnable stock) {
        final Command command = supersede(false);
        command.prepare = true;
        run(command, stock);
    }

    /** Binds a lazy-load continuation to the command that started it, creating a Play receipt for a direct command. */
    public Runnable defer(Runnable stock) {
        final Command command = entering != null ? entering : supersede(true);
        command.loading = true;
        return () -> {
            if (command != latest || command.generation != generation || !command.loading) return;
            command.loading = false;
            if (command.play != null && !timer.acceptPlay(command.play)) {
                if (!command.prepare) return;
                command.play = null;
            }
            run(command, stock);
        };
    }

    /** Called where stock starts the selected playlist. False keeps a prepare silent and remembers its target. */
    public boolean select(String mediaId) {
        final Command command = entering;
        if (command == null) {
            supersede(false);
            return true;
        }
        if (command.play != null) return true;
        prepared = new Prepared(mediaId, command);
        return false;
    }

    /** Plain Play: finishes a pending or completed prepare, otherwise lets stock Play run. */
    public boolean play(Target target) {
        final Command command = latest;
        if (command != null && command.loading && command.prepare && command.generation == generation) {
            command.play = timer.requestPlay(Slot.AUDIO);
            return true;
        }
        final Prepared selected = prepared;
        supersede(false);
        if (selected == null || selected.generation != generation || !unchanged(selected.before)) return false;
        target.play(selected.mediaId);
        return true;
    }

    /** Account switch or session release: no earlier command may complete. */
    public void replace() {
        generation++;
        latest = null;
        prepared = null;
    }

    private Command supersede(boolean play) {
        final Command command = new Command(generation, timer.ticket(Slot.AUDIO), play ? timer.requestPlay(Slot.AUDIO) : null);
        latest = command;
        prepared = null;
        return command;
    }

    private boolean unchanged(Ticket before) {
        final Ticket now = timer.ticket(Slot.AUDIO);
        return now.incarnation == before.incarnation && Objects.equals(now.item, before.item);
    }

    private void run(Command command, Runnable stock) {
        final Command outer = entering;
        entering = command;
        try {
            stock.run();
        } finally {
            entering = outer;
        }
    }
}
