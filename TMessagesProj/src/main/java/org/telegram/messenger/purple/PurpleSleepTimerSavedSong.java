package org.telegram.messenger.purple;

import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

public final class PurpleSleepTimerSavedSong {
    public static final class Selection {
        public final Item item;
        public final float progress;
        public final int progressMs, progressSec;
        public Selection(Item item, float progress, int progressMs, int progressSec) {
            this.item = item;
            this.progress = progress;
            this.progressMs = progressMs;
            this.progressSec = progressSec;
        }
    }
    public static final class Retirement {
        private boolean active;
        public boolean active() { return active; }
        public boolean run(boolean needed, Runnable retire, java.util.function.BooleanSupplier restore) {
            if (!needed || active) return false;
            active = true;
            try {
                retireThenRestore(retire, restore);
                return true;
            } finally { active = false; }
        }
    }

    public static boolean retireThenRestore(Runnable retirePlayer, java.util.function.BooleanSupplier restoreModel) {
        retirePlayer.run();
        return restoreModel.getAsBoolean();
    }

    private PurpleSleepTimerSavedSong() {}
    public static boolean restore(Selection saved, Item selected, Consumer<Selection> adoptPausedModel) {
        if (saved == null || saved.item == null || saved.item.kind != Kind.SONG || !saved.item.equals(selected)) return false;
        adoptPausedModel.accept(saved);
        return true;
    }
}
