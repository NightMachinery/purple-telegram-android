package org.telegram.messenger.purple;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import org.telegram.messenger.AndroidUtilities;

public final class PurpleSyncAutoEdits {
    private static final int TOMBSTONE_LIMIT = 128;
    private static final IdentityHashMap<Object, Entry> entries = new IdentityHashMap<>();
    private static final WeakHashMap<Object, Tombstone> tombstones = new WeakHashMap<>();
    private static long sequence;
    private static final class Tombstone {
        final int account;
        final long order;
        final PurpleAccountFiles.Identity origin;
        Tombstone(int account, long order) { this.account = account; this.order = order; origin = PurpleAccountFiles.capture(account); }
        void reconcile() { if (origin != null) PurpleSyncAuto.reconcile(origin); }
    }
    private static final class Entry {
        final Tombstone tombstone;
        final long token;
        BooleanSupplier current;
        PurpleSyncClient.Reply<PurpleSyncAutoCore.Outcome> reply;
        Runnable timeout;
        Entry(int account, BooleanSupplier current, PurpleSyncClient.Reply<PurpleSyncAutoCore.Outcome> reply) {
            token = ++sequence; tombstone = new Tombstone(account, token); this.current = current; this.reply = reply;
        }
        void deliver(PurpleSyncAutoCore.Outcome outcome) {
            PurpleSyncClient.Reply<PurpleSyncAutoCore.Outcome> callback = reply;
            reply = null; current = null;
            AndroidUtilities.cancelRunOnUIThread(timeout); timeout = null;
            if (callback != null) callback.onReply(outcome);
        }
    }
    private static void remember(Object message, Tombstone tombstone) {
        tombstones.put(message, tombstone);
        while (tombstones.size() > TOMBSTONE_LIMIT) {
            Object oldest = null; long order = Long.MAX_VALUE;
            for (java.util.Map.Entry<Object, Tombstone> row : tombstones.entrySet())
                if (row.getValue().order < order) { oldest = row.getKey(); order = row.getValue().order; }
            if (oldest == null) break;
            tombstones.remove(oldest);
        }
    }
    static synchronized void register(int account, Object message, BooleanSupplier current,
        PurpleSyncClient.Reply<PurpleSyncAutoCore.Outcome> reply) {
        Entry entry = new Entry(account, current, reply);
        Entry previous = entries.put(message, entry);
        if (previous != null) previous.deliver(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
        tombstones.remove(message);
        WeakReference<Object> weak = new WeakReference<>(message);
        long token = entry.token;
        entry.timeout = () -> expire(weak, token);
        AndroidUtilities.runOnUIThread(entry.timeout, 180000);
    }
    private static synchronized void expire(WeakReference<Object> weak, long token) {
        Object message = weak.get();
        Entry entry = message != null ? entries.get(message) : null;
        if (entry == null || entry.token != token) return;
        entries.remove(message); remember(message, entry.tombstone);
        entry.deliver(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
    }
    public static synchronized boolean finished(int account, Object message, boolean failed, String error) {
        Entry entry = entries.get(message);
        if (entry == null || entry.tombstone.account != account) {
            Tombstone tombstone = tombstones.get(message);
            if (tombstone == null || tombstone.account != account) return false;
            tombstone.reconcile(); return true;
        }
        entries.remove(message); remember(message, entry.tombstone);
        boolean current = entry.current != null && entry.current.getAsBoolean();
        entry.deliver(!current ? PurpleSyncAutoCore.Outcome.OutcomeUnknown
            : !failed ? PurpleSyncAutoCore.Outcome.Sent
            : error != null && (error.equals("MESSAGE_EDIT_TIME_EXPIRED") || error.equals("MESSAGE_AUTHOR_REQUIRED")
                || error.equals("MESSAGE_ID_INVALID") || error.equals("MEDIA_NEW_INVALID"))
            ? PurpleSyncAutoCore.Outcome.EditRefused : PurpleSyncAutoCore.Outcome.OutcomeUnknown);
        return true;
    }
    static synchronized boolean busy(int account) {
        for (Entry entry : entries.values())
            if (entry.tombstone.account == account && entry.reply != null) return true;
        return false;
    }
    static synchronized void cleanup(int account) {
        for (Object message : new java.util.ArrayList<>(entries.keySet())) {
            Entry entry = entries.get(message);
            if (entry != null && entry.tombstone.account == account) {
                entries.remove(message); remember(message, entry.tombstone);
                entry.deliver(PurpleSyncAutoCore.Outcome.OutcomeUnknown);
            }
        }
    }
    private PurpleSyncAutoEdits() {}
}
