package org.telegram.messenger.purple;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;
public final class PurpleSleepTimer {
 static PurpleSleepTimer instance = new PurpleSleepTimer();
 public PurpleSleepTimerRules rules = new PurpleSleepTimerRules(); public boolean local;
 public static PurpleSleepTimer get() { return instance; }
 public PlayRequest requestPlay(Slot slot) { return rules.requestPlay(slot); }
 public boolean acceptPlay(PlayRequest request) { return rules.acceptPlay(request); }
 public boolean automatic(Ticket ticket) { return rules.autoStart(ticket); }
 public boolean localPause() { return local; }
}
