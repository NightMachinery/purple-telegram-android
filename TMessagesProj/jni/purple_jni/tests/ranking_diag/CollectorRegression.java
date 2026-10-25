package org.telegram.messenger.purple;

import java.io.File;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;

public final class CollectorRegression {
    private static int checks, callbacks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }
    private static void peer(int account, double rating) {
        TLRPC.TL_topPeer peer = new TLRPC.TL_topPeer();
        peer.peer.user_id = 9876543210123L;
        peer.rating = rating;
        MediaDataController.all[account].hints.add(peer);
    }
    private static void setup(int accounts) {
        AndroidUtilities.ui.clear();
        PurpleHintHistory.clear();
        PurpleDiagLog.section = null;
        PurpleDiagLog.exports = callbacks = PurpleGate.quietCalls = PurpleGate.logCalls = 0;
        PurpleGate.hidden = false;
        int now = (int) (System.currentTimeMillis() / 1000);
        for (int i = 0; i < 4; ++i) {
            UserConfig.all[i] = new UserConfig();
            UserConfig.all[i].active = i < accounts;
            UserConfig.all[i].login = 1000000000011L + i;
            UserConfig.all[i].ratingLoadTime = now;
            MediaDataController.all[i] = new MediaDataController();
            MessagesController.all[i] = new MessagesController();
            ConnectionsManager.all[i] = new ConnectionsManager();
            ConnectionsManager.all[i].now = now - 172800;
            MessagesStorage.all[i] = new MessagesStorage();
            MessagesStorage.all[i].db.date = now - 172860;
            if (i < accounts) peer(i, i == 0 ? 1.25 : 321.25);
        }
    }
    private static void begin() {
        PurpleRankingDiag.export(file -> ++callbacks);
        AndroidUtilities.drain();
    }
    private static void finish() {
        for (int i = 0; i < 4; ++i) {
            for (MessagesStorage storage : MessagesStorage.all) storage.queue.drain();
            AndroidUtilities.drain();
        }
        check(callbacks == 1 && PurpleDiagLog.exports == 1, "one export callback");
    }
    private static void replace(int account, boolean sameUser) {
        if (!sameUser) ++UserConfig.all[account].login;
        PurpleRankingDiag.reset(account);
        MediaDataController.all[account].hints.clear();
        peer(account, 999.25);
        MessagesStorage.all[account].db.date = ConnectionsManager.all[account].now - 60;
    }
    private static void dropped(int account, double oldRating) {
        String section = PurpleDiagLog.section;
        check(section.contains("account=" + account + " account changed during export"), "changed account note");
        check(!section.contains("rating=" + oldRating) && !section.contains("rating=999.25"), "no old/new login mixture");
    }
    public static void main(String[] args) throws Exception {
        File expected = new File(args[0]).getCanonicalFile();
        for (Class<?> type : new Class<?>[]{PurpleRankingDiag.class, PurpleRankingReport.class, PurpleHintHistory.class}) {
            check(expected.equals(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile()), "real AGP class");
        }
        setup(1);
        PurpleGate.hidden = true;
        begin();
        finish();
        String section = PurpleDiagLog.section;
        check(section.contains("outgoing1d=1 outgoing7d=1 outgoing30d=1 lastOutgoingAgeSeconds=60"), "two-day wall skew uses server windows/age");
        check(section.contains("dt=-172860.0"), "upstream wall-anchor rating math unchanged");
        check(section.contains("wall=") && section.contains("server=") && section.contains("wallMinusServer="), "both clocks disclosed");
        check(section.contains("visibleNow=false") && PurpleGate.quietCalls == 1 && PurpleGate.logCalls == 0, "quiet visibility projection");
        for (String secret : new String[]{"PRIVATE_NAME_ABC", "PRIVATE_LAST_ABC", "PRIVATE_HANDLE_ABC",
                "+4915123456789", "9876543210123", "1000000000011"}) check(!section.contains(secret), "no fixture identifier");
        setup(1);
        MessagesStorage.all[0].db.fail = true;
        begin(); finish();
        check(PurpleDiagLog.section.contains("databaseRead=false") && !PurpleDiagLog.section.contains("PRIVATE_"), "DB exception text discarded");
        setup(2);
        begin();
        replace(1, false);
        finish();
        dropped(1, 321.25);
        check(MessagesStorage.all[1].db.queries == 0, "slot reused before enqueue, no replacement reads");
        check(PurpleDiagLog.section.contains("account=0 selected=true"), "other account retained");
        setup(2);
        begin();
        MessagesStorage.all[0].queue.drain(); AndroidUtilities.drain();
        check(MessagesStorage.all[1].queue.queue.size() == 1, "second account deferred");
        replace(1, false);
        finish(); dropped(1, 321.25);
        check(MessagesStorage.all[1].db.queries == 0, "queued worker validates login");
        setup(2);
        begin();
        MessagesStorage.all[1].db.afterQuery = () -> replace(1, false);
        finish(); dropped(1, 321.25);
        check(MessagesStorage.all[1].db.queries == 1, "result refused after login changes during query");
        setup(2);
        begin();
        MessagesStorage.all[0].queue.drain(); AndroidUtilities.drain();
        MessagesStorage.all[1].queue.drain();
        replace(0, false);
        AndroidUtilities.drain();
        check(callbacks == 1, "format completion");
        dropped(0, 1.25);
        check(PurpleDiagLog.section.contains("rating=321.25"), "valid second account retained");
        setup(2);
        begin(); replace(1, true); finish(); dropped(1, 321.25);
        check(MessagesStorage.all[1].db.queries == 0, "same-user new login refused by generation");
        setup(2);
        begin();
        MessagesStorage.all[1] = new MessagesStorage();
        finish(); dropped(1, 321.25);
        check(MessagesStorage.all[1].db.queries == 0, "storage instance replacement refused");
        setup(1);
        PurpleRankingDiag.export(file -> ++callbacks);
        PurpleHintHistory.clear(); AndroidUtilities.drain();
        check(callbacks == 1 && PurpleDiagLog.exports == 0, "Clear before UI capture cancels export");
        System.out.println("RANKING_COLLECTOR_CHECKS=" + checks);
    }
}
