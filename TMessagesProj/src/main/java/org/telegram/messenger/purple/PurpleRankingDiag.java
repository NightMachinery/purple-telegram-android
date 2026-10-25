package org.telegram.messenger.purple;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.PurpleRankingAccess;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

public final class PurpleRankingDiag {
    private static final class Pending {
        final PurpleRankingReport.Account report = new PurpleRankingReport.Account();
        final ArrayList<Long> dialogs = new ArrayList<>();
        long login;
        long loginGeneration;
        MessagesStorage storage;
        MediaDataController media;
    }

    private PurpleRankingDiag() {
    }

    public static int rank(List<TLRPC.TL_topPeer> hints, long dialogId) {
        for (int i = 0; i < hints.size(); ++i) {
            if (DialogObject.getPeerDialogId(hints.get(i).peer) == dialogId) return i + 1;
        }
        return 0;
    }

    public static void event(int account, PurpleHintHistory.Source source, int slots) {
        PurpleHintHistory.record(account, System.currentTimeMillis() / 1000, source, slots, 0, 0, 0);
    }

    public static void reset(int account) {
        PurpleHintHistory.reset(account, System.currentTimeMillis() / 1000);
    }

    public static void serverResult(int account, TLObject response, int slots) {
        PurpleHintHistory.Source source = response instanceof TLRPC.TL_contacts_topPeers
                ? PurpleHintHistory.Source.SERVER_REPLACE : response instanceof TLRPC.TL_contacts_topPeersDisabled
                ? PurpleHintHistory.Source.SERVER_DISABLED : PurpleHintHistory.Source.SERVER_NOT_MODIFIED_OR_ERROR;
        event(account, source, slots);
    }

    public static void increment(int account, int before, List<TLRPC.TL_topPeer> hints, long dialogId,
            double dt, double decay) {
        PurpleHintHistory.record(account, System.currentTimeMillis() / 1000,
                PurpleHintHistory.Source.LOCAL_INCREMENT, hints.size(), before, rank(hints, dialogId),
                PurpleRankingReport.increment(dt, decay));
    }

    public static void export(PurpleDiagLog.Exported done) {
        long generation = PurpleHintHistory.generation();
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ArrayList<Pending> pending = new ArrayList<>();
                long now = System.currentTimeMillis() / 1000;
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; ++account) {
                    UserConfig config = UserConfig.getInstance(account);
                    if (config.isClientActivated()) pending.add(capture(account, config, now));
                }
                collect(pending, 0, PurpleHintHistory.snapshot(), generation, done);
            } catch (Exception ignored) {
                if (generation == PurpleHintHistory.generation()) {
                    PurpleDiagLog.export(done, "Frequent contacts\nsnapshot unavailable\n----\n");
                } else {
                    done.run(null);
                }
            }
        });
    }

    private static Pending capture(int account, UserConfig config, long now) {
        Pending pending = new Pending();
        PurpleRankingReport.Account report = pending.report;
        report.index = account;
        pending.login = config.getClientUserId();
        pending.loginGeneration = PurpleHintHistory.loginGeneration(account);
        pending.storage = MessagesStorage.getExistingInstance(account);
        pending.media = MediaDataController.getExistingInstance(account);
        report.selected = account == UserConfig.selectedAccount;
        report.suggestContacts = config.suggestContacts;
        report.wall = now;
        report.sync = config.lastHintsSyncTime;
        report.anchor = config.ratingLoadTime;
        report.serverInFlight = PurpleHintHistory.inFlight(account);
        MediaDataController media = pending.media;
        ConnectionsManager connection = ConnectionsManager.getExistingInstance(account);
        MessagesController messages = MessagesController.getExistingInstance(account);
        report.controllerReady = media != null && connection != null && messages != null;
        if (!report.controllerReady) return pending;
        report.loading = PurpleRankingAccess.loading(media);
        report.server = connection.getCurrentTime();
        report.timeDifference = connection.getTimeDifference();
        report.decay = messages.ratingDecay;
        ArrayList<MessagesController.DialogFilter> folders = new ArrayList<>();
        if (PurpleGate.hidingFromSuggestions(account)) {
            ArrayList<MessagesController.DialogFilter> shown =
                    PurpleGate.shownFiltersQuiet(account, messages.getDialogFiltersUnrestricted());
            if (shown != null) folders = shown;
        }
        for (TLRPC.TL_topPeer peer : media.hints) {
            PurpleRankingReport.Slot slot = new PurpleRankingReport.Slot();
            long dialogId = DialogObject.getPeerDialogId(peer.peer);
            slot.rank = report.slots.size() + 1;
            slot.rating = peer.rating;
            slot.visible = !PurpleGate.hiddenEverywhere(account, dialogId)
                    && !PurpleGate.hiddenFromSuggestions(account, dialogId, folders);
            TLRPC.User user = DialogObject.isUserDialog(dialogId) ? messages.getUser(dialogId) : null;
            slot.userKnown = user != null;
            if (user != null) {
                slot.contact = user.contact;
                slot.mutual = user.mutual_contact;
                slot.bot = user.bot;
                slot.deleted = user.deleted;
                slot.eligible = config.suggestContacts && !user.bot && !user.self;
            }
            report.slots.add(slot);
            pending.dialogs.add(dialogId);
        }
        return pending;
    }

    private static boolean sameLogin(Pending pending) {
        int account = pending.report.index;
        UserConfig config = UserConfig.getInstance(account);
        return config.isClientActivated() && config.getClientUserId() == pending.login
                && PurpleHintHistory.loginGeneration(account) == pending.loginGeneration
                && MediaDataController.getExistingInstance(account) == pending.media
                && MessagesStorage.getExistingInstance(account) == pending.storage;
    }

    private static void changed(Pending pending) {
        pending.report.accountChanged = true;
        pending.report.slots.clear();
        pending.dialogs.clear();
    }

    private static void collect(ArrayList<Pending> pending, int position,
            List<PurpleHintHistory.Event> events, long generation, PurpleDiagLog.Exported done) {
        if (generation != PurpleHintHistory.generation()) {
            done.run(null);
            return;
        }
        if (position == pending.size()) {
            ArrayList<PurpleRankingReport.Account> reports = new ArrayList<>();
            for (Pending each : pending) {
                if (!sameLogin(each)) changed(each);
                reports.add(each.report);
            }
            ArrayList<PurpleHintHistory.Event> retained = new ArrayList<>();
            for (PurpleHintHistory.Event event : events) {
                for (Pending each : pending) {
                    if (event.account == each.report.index && !each.report.accountChanged) retained.add(event);
                }
            }
            PurpleDiagLog.export(done, PurpleRankingReport.format(reports, retained));
            return;
        }
        Pending current = pending.get(position);
        if (!sameLogin(current)) changed(current);
        if (current.dialogs.isEmpty()) {
            collect(pending, position + 1, events, generation, done);
            return;
        }
        MessagesStorage storage = current.storage;
        if (storage == null) {
            current.dialogs.clear();
            collect(pending, position + 1, events, generation, done);
            return;
        }
        storage.getStorageQueue().postRunnable(() -> {
            for (int i = 0; i < current.dialogs.size(); ++i) {
                if (!sameLogin(current)) {
                    changed(current);
                    break;
                }
                SQLiteCursor cursor = null;
                try {
                    long now = current.report.server;
                    cursor = storage.getDatabase().queryFinalized(
                            "SELECT SUM(CASE WHEN date >= ? THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN date >= ? THEN 1 ELSE 0 END), "
                            + "SUM(CASE WHEN date >= ? THEN 1 ELSE 0 END), MAX(mid), MAX(date) "
                            + "FROM messages_v2 WHERE uid = ? AND out = 1",
                            now - 86400, now - 7 * 86400, now - 30 * 86400, current.dialogs.get(i));
                    if (cursor.next() && sameLogin(current)) {
                        PurpleRankingReport.Slot slot = current.report.slots.get(i);
                        slot.day = cursor.longValue(0);
                        slot.week = cursor.longValue(1);
                        slot.month = cursor.longValue(2);
                        slot.hasOutgoing = !cursor.isNull(4);
                        slot.lastOutgoing = cursor.intValue(4);
                        slot.dt = PurpleRankingReport.incrementDt(cursor.intValue(3), (int) slot.lastOutgoing,
                                (int) current.report.anchor);
                        slot.next = PurpleRankingReport.increment(slot.dt, current.report.decay);
                        slot.databaseRead = true;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (cursor != null) cursor.dispose();
                }
            }
            if (!sameLogin(current)) changed(current);
            current.dialogs.clear();
            AndroidUtilities.runOnUIThread(() -> collect(pending, position + 1, events, generation, done));
        });
    }
}
