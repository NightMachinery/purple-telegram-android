package org.telegram.messenger.purple;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;

public final class PurpleRecordingDelivery {
    private static final Map<String, Ticket> current = new HashMap<>();
    private static final Map<SendMessageChatArguments, Request> requests = new WeakHashMap<>();
    private static final class Ticket {
        String id;
        int account;
        long user, dialog;
        boolean bound, acknowledged, keepInBin;
    }
    private static final class Request {
        Ticket ticket;
        Runnable queued;
    }
    private PurpleRecordingDelivery() { }

    public static synchronized void send(String id, int account, long user, long dialog, SendMessageChatArguments arguments,
            Runnable prepare, Runnable queued) {
        send(id, account, user, dialog, arguments, prepare, queued, false);
    }

    public static synchronized void sendCopy(String id, int account, long user, long dialog, SendMessageChatArguments arguments,
            Runnable prepare, Runnable queued) {
        send(id, account, user, dialog, arguments, prepare, queued, true);
    }

    private static synchronized void send(String id, int account, long user, long dialog, SendMessageChatArguments arguments,
            Runnable prepare, Runnable queued, boolean keepInBin) {
        if (id == null || arguments == null || !PurpleRecordingSendAs.sameUser(account, user)) return;
        final Ticket previous = current.get(id);
        if (previous != null && previous.bound) return;
        final Ticket ticket = new Ticket();
        ticket.id = id; ticket.account = account; ticket.dialog = dialog;
        ticket.user = user; ticket.keepInBin = keepInBin;
        final Request request = new Request(); request.ticket = ticket; request.queued = queued;
        current.put(id, ticket); requests.put(arguments, request);
        PurpleRecordingRegistry.pendingRound(id, present -> {
            if (present && allowed(arguments)) prepare.run();
        });
    }

    public static synchronized boolean allowed(SendMessageChatArguments arguments) {
        final Request request = requests.get(arguments);
        if (request == null) return true;
        final Ticket ticket = request.ticket;
        return current.get(ticket.id) == ticket && !ticket.bound && !ticket.acknowledged
                && PurpleRecordingSendAs.sameUser(ticket.account, ticket.user);
    }

    public static synchronized void revoke(String id) { current.remove(id); }
    public static synchronized void revokeAll() { current.clear(); }

    public static synchronized void queued(int account, SendMessagesHelper.SendMessageParams params, TLRPC.Message message) {
        final Request request = requests.get(params.sendMessageChatArguments);
        if (request == null || !allowed(params.sendMessageChatArguments)) return;
        final Ticket ticket = request.ticket;
        if (ticket.bound || ticket.account != account || ticket.dialog != params.peer) return;
        ticket.bound = true;
        final int localId = message.local_id != 0 ? message.local_id : message.id;
        final String table = params.scheduleDate != 0 ? "scheduled_messages_v2" : "messages_v2";
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            boolean stored = false;
            try {
                if (PurpleRecordingSendAs.sameUser(account, ticket.user)) {
                    final SQLiteCursor cursor = storage.getDatabase().queryFinalized(
                            "SELECT mid FROM " + table + " WHERE mid = ? AND uid = ?", localId, ticket.dialog);
                    try { stored = cursor.next(); } finally { cursor.dispose(); }
                }
            } catch (Exception failure) {
                PurpleDiagLog.event("recordings", "queue_ack", PurpleDiagLog.reason("io_failed"));
            }
            final boolean present = stored;
            AndroidUtilities.runOnUIThread(() -> {
                synchronized (PurpleRecordingDelivery.class) {
                    if (current.get(ticket.id) != ticket || ticket.acknowledged) return;
                    ticket.bound = false;
                    if (!present || !PurpleRecordingSendAs.sameUser(account, ticket.user)) return;
                    ticket.acknowledged = true;
                    current.remove(ticket.id);
                }
                final Runnable done = request.queued;
                request.queued = null;
                if (ticket.keepInBin) done.run();
                else PurpleRecordingRegistry.queuedRound(ticket.id, done);
            });
        });
    }
}
