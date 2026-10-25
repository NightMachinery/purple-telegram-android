package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PurpleRankingReport {
    public static final class Slot {
        public int rank;
        public double rating;
        public boolean visible, userKnown, contact, mutual, bot, deleted, eligible;
        public boolean databaseRead;
        public long day, week, month;
        public boolean hasOutgoing;
        public long lastOutgoing;
        public double dt;
        public double next;
    }

    public static final class Account {
        public int index;
        public boolean selected, suggestContacts, controllerReady, loading, serverInFlight, accountChanged;
        public long wall, server, sync, anchor;
        public int timeDifference;
        public double decay;
        public final List<Slot> slots = new ArrayList<>();
    }

    private PurpleRankingReport() {
    }

    public static double incrementDt(int maxMid, int lastDate, int anchor) {
        return maxMid > 0 && anchor != 0 ? (double) (lastDate - anchor) : 0;
    }

    public static double increment(double dt, double decay) {
        return Math.exp(dt / decay);
    }

    private static String age(long now, long then) {
        return then == 0 ? "unset" : Long.toString(now - then);
    }

    public static String format(List<Account> accounts, List<PurpleHintHistory.Event> events) {
        StringBuilder out = new StringBuilder("Frequent contacts\n");
        out.append("snapshot: local data only; ranks are raw order, 1-based; 0 means absent\n")
                .append("counts: locally retained outgoing messages, not lifetime totals\n")
                .append("next: exp(dt/decay) using current DB MAX(mid), MAX(date) and anchor; future sends may differ\n");
        for (Account account : accounts) {
            if (account.accountChanged) {
                out.append(String.format(Locale.US, "account=%d account changed during export\n", account.index));
                continue;
            }
            out.append(String.format(Locale.US,
                    "account=%d selected=%b suggestContacts=%b hints=%s controllerReady=%b loading=%b serverInFlight=%b\n",
                    account.index, account.selected, account.suggestContacts,
                    account.controllerReady ? Integer.toString(account.slots.size()) : "unavailable",
                    account.controllerReady, account.loading, account.serverInFlight));
            out.append(String.format(Locale.US,
                    " wall=%d server=%s wallMinusServer=%s timeDifference=%s lastHintsSyncTime=%d syncAgeSeconds=%s ratingLoadTime=%d anchorAgeSeconds=%s ratingDecay=%s\n",
                    account.wall, account.controllerReady ? Long.toString(account.server) : "unavailable",
                    account.controllerReady ? Long.toString(account.wall - account.server) : "unavailable",
                    account.controllerReady ? Integer.toString(account.timeDifference) : "unavailable",
                    account.sync, age(account.wall, account.sync), account.anchor, age(account.wall, account.anchor),
                    account.controllerReady ? Double.toString(account.decay) : "unavailable"));
            for (Slot slot : account.slots) {
                out.append(String.format(Locale.US,
                        " rank=%d rating=%s visibleNow=%b userKnown=%b contact=%b mutual=%b bot=%b deleted=%b eligible=%b databaseRead=%b outgoing1d=%d outgoing7d=%d outgoing30d=%d lastOutgoingAgeSeconds=%s dt=%s nextIncrement=%s\n",
                        slot.rank, Double.toString(slot.rating), slot.visible, slot.userKnown, slot.contact,
                        slot.mutual, slot.bot, slot.deleted, slot.eligible, slot.databaseRead,
                        slot.day, slot.week, slot.month, slot.hasOutgoing ? Long.toString(account.server - slot.lastOutgoing) : "unset",
                        slot.databaseRead ? Double.toString(slot.dt) : "unavailable",
                        slot.databaseRead ? Double.toString(slot.next) : "unavailable"));
            }
        }
        out.append("hint events: last 50 across accounts, memory only since process start or Clear\n");
        for (PurpleHintHistory.Event event : events) {
            out.append(String.format(Locale.US, " account=%d time=%d source=%s slots=%d",
                    event.account, event.time, event.source.name().toLowerCase(Locale.US), event.slots));
            if (event.source == PurpleHintHistory.Source.LOCAL_INCREMENT) {
                out.append(String.format(Locale.US, " rankBefore=%d rankAfter=%d added=%s",
                        event.before, event.after, Double.toString(event.added)));
            }
            out.append('\n');
        }
        return out.append("----\n").toString();
    }
}
