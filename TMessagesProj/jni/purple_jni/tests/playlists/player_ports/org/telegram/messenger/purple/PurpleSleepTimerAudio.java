package org.telegram.messenger.purple;
import org.telegram.messenger.MessageObject;
import java.util.function.BooleanSupplier;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;
public final class PurpleSleepTimerAudio {
 private final PurpleSleepTimer timer = PurpleSleepTimer.get(); private int automatic, replacing;
 public Ticket capture() { return timer.rules.ticket(Slot.AUDIO); }
 public boolean retiring() { return false; }
 public boolean play(MessageObject message, BooleanSupplier action) {
   if (automatic == 0) timer.rules.manualPlay(Slot.AUDIO);
   else if (timer.rules.blocked(Slot.AUDIO)) return false;
   Item item = new Item(message.currentAccount, message.getDialogId(), message.getId(), message.getDocument().id,
       message.isMusic() ? Kind.SONG : Kind.VOICE);
   if (!item.equals(timer.rules.current(Slot.AUDIO))) timer.rules.loaded(Slot.AUDIO, item, true);
   return action.getAsBoolean();
 }

/* EXTRACT:automatic */
/* END:automatic */
}
