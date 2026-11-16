package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

/** Deferred explicit Play receipts on the production rules, for both player slots. */
public final class PurpleSleepTimerPlayRequestCases {
    private static int checks;
    private static void need(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }

    private static Item song(int id) {
        return new Item(1, 2, id, id + 100, Kind.SONG);
    }

    public static void main(String[] args) {
        for (Slot slot : Slot.values()) {
            final Slot other = slot == Slot.AUDIO ? Slot.VIEWER : Slot.AUDIO;
            PurpleSleepTimerRules rules = new PurpleSleepTimerRules();
            PlayRequest empty = rules.requestPlay(slot);
            need(rules.acceptPlay(empty), "explicit selection can finish from an empty player");
            need(!rules.acceptPlay(empty), "completion cannot replay");
            rules.loaded(slot, song(1), true);
            final Ticket automatic = rules.ticket(slot);
            PlayRequest quiet = rules.requestPlay(slot);
            need(rules.autoStart(automatic) && !rules.blocked(slot), "requesting leaves automatic receipts and the fence alone");
            need(rules.acceptPlay(quiet), "untouched request completes");
            PlayRequest paused = rules.requestPlay(slot);
            rules.manualPause(slot);
            need(!rules.acceptPlay(paused), "Pause during load wins over its earlier Play");
            PlayRequest expired = rules.requestPlay(slot);
            need(rules.timed(0, MINUTE), "loaded player permits timer");
            rules.expire(null);
            need(!rules.acceptPlay(expired), "expiry revokes pending Play");
            need(rules.blocked(slot), "stale completion leaves expiry fence intact");
            PlayRequest afterExpiry = rules.requestPlay(slot);
            need(rules.blocked(slot), "a request alone does not lift the expiry fence");
            need(rules.acceptPlay(afterExpiry), "deliberate Play requested after expiry completes");
            rules.manualPlay(slot);
            need(!rules.blocked(slot), "the stock explicit Play that follows lifts the fence");
            PlayRequest older = rules.requestPlay(slot);
            PlayRequest newer = rules.requestPlay(slot);
            need(!rules.acceptPlay(older), "newer Play supersedes an old load");
            need(rules.acceptPlay(newer), "latest Play remains usable");
            PlayRequest elsewhere = rules.requestPlay(slot);
            rules.requestPlay(other);
            need(rules.acceptPlay(elsewhere), "a request on the other player does not revoke this one");
            PlayRequest played = rules.requestPlay(slot);
            rules.manualPlay(slot);
            need(!rules.acceptPlay(played), "a direct Play in between revokes the deferred one");
            PlayRequest replaced = rules.requestPlay(slot);
            rules.replaceIncarnation(slot);
            need(!rules.acceptPlay(replaced), "same-item player replacement revokes callback");
            PlayRequest changed = rules.requestPlay(slot);
            rules.loaded(slot, song(2), true);
            need(!rules.acceptPlay(changed), "unrelated selection revokes callback");
            PlayRequest removed = rules.requestPlay(slot);
            need(rules.unload(rules.ticket(slot)), "selection can unload normally");
            need(!rules.acceptPlay(removed), "unload revokes pending callback");
            PlayRequest foreign = rules.requestPlay(slot);
            need(!new PurpleSleepTimerRules().acceptPlay(foreign), "another timer owner cannot consume authority");
            need(rules.acceptPlay(foreign), "foreign refusal does not consume owner's request");
            rules.loaded(slot, song(3), true);
            PlayRequest extended = rules.requestPlay(slot);
            need(rules.timed(0, MINUTE), "timer may start during load");
            need(rules.add(1, MINUTE), "timer may extend during load");
            need(rules.acceptPlay(extended), "extending a timer does not cancel user Play");
            PlayRequest cancelled = rules.requestPlay(slot);
            rules.cancel();
            need(rules.acceptPlay(cancelled), "cancel timer retains user Play intent");
            PlayRequest transferred = rules.requestPlay(slot);
            need(rules.handoff(rules.ticket(slot), other), "native ownership can transfer");
            need(!rules.acceptPlay(transferred), "old slot cannot complete after ownership transfer");
        }
        need(!new PurpleSleepTimerRules().acceptPlay(null), "missing request grants nothing");
        System.out.println("SLEEP_TIMER_PLAY_REQUEST_CHECKS=" + checks);
    }
}
