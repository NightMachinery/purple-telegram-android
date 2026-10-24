package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public final class PurpleSendReceipts {
    private static final ConcurrentHashMap<Integer, Account> accounts = new ConcurrentHashMap<>();

    private static final class Entry {
        final long generation;
        final PurpleSendReceipt receipt;
        final String path;
        final PurpleAccountFiles.Identity origin;
        final boolean legacyStaged;
        long dialogId;
        boolean completed;
        Object owner;

        Entry(int account, long generation, String path, PurpleSendReceipt receipt) {
            this.generation = generation;
            this.receipt = receipt;
            this.path = path;
            origin = PurpleAccountFiles.capture(account);
            legacyStaged = PurpleSync.stagedLegacyFile(path);
        }

        boolean allowed() {
            String owner = PurpleAccountOwner.owner();
            if (origin == null || !origin.current() || receipt == null || !receipt.current()) return false;
            PurpleAccountFiles.Identity modern = receipt.modernOrigin();
            if (modern != null) return modern.key.equals(origin.key) && modern.lifetime == origin.lifetime
                    && PurpleAccountOwner.modernOperation(origin.slot);
            return PurpleTransferRules.receiptAllowed(owner, origin.key, legacyStaged, legacyStaged && PurpleAccountOwner.ready());
        }
    }

    private static final class Account {
        long generation;
        final HashMap<String, Entry> pending = new HashMap<>();
        final HashMap<Integer, Entry> bound = new HashMap<>();
    }

    private PurpleSendReceipts() {
    }

    private static Account account(int account) {
        return accounts.computeIfAbsent(account, ignored -> new Account());
    }

    public static void prepare(int account, String path, PurpleSendReceipt receipt, int unsupported, IntSupplier preparation, IntConsumer handleError) {
        Account state = account(account);
        Entry entry;
        boolean registered;
        synchronized (state) {
            entry = new Entry(account, state.generation, path, receipt);
            registered = path != null && receipt != null && !state.pending.containsKey(path);
            if (registered) {
                state.pending.put(path, entry);
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (state) {
                if (entry.generation != state.generation || !entry.allowed()) {
                    boolean notify = !entry.completed;
                    entry.completed = true;
                    AndroidUtilities.runOnUIThread(() -> {
                        discard(state, path, entry);
                        if (notify && receipt != null) deliver(receipt, 0, true);
                    });
                    return;
                }
            }
            int error = unsupported;
            if (registered) {
                try {
                    error = preparation.getAsInt();
                } catch (RuntimeException e) {
                    FileLog.e(e);
                }
            }
            final int result = error;
            try {
                if (result != 0) {
                    handleError.accept(result);
                }
            } finally {
                AndroidUtilities.runOnUIThread(() -> {
                    discard(state, path, entry);
                    boolean failed;
                    synchronized (state) {
                        failed = result != 0 && entry.generation == state.generation && !entry.completed;
                        if (failed) {
                            entry.completed = true;
                        }
                    }
                    if (failed && receipt != null) {
                        deliver(receipt, 0, true);
                    }
                });
            }
        });
    }

    private static void discard(Account state, String path, Entry entry) {
        synchronized (state) {
            if (state.pending.get(path) == entry) {
                state.pending.remove(path);
            }
        }
    }

    static boolean hasModernWork(int account) {
        Account state=accounts.get(account);
        if(state==null)return false;
        synchronized(state) {
            for(Entry entry:state.pending.values())if(!entry.completed&&entry.receipt!=null&&entry.receipt.modernOrigin()!=null&&entry.allowed())return true;
            for(Entry entry:state.bound.values())if(!entry.completed&&entry.receipt!=null&&entry.receipt.modernOrigin()!=null&&entry.allowed())return true;
            return false;
        }
    }

    public static boolean pending(int account, String path) {
        Account state = accounts.get(account);
        if (state == null || path == null) {
            return false;
        }
        synchronized (state) {
            Entry entry = state.pending.get(path);
            return entry != null && entry.generation == state.generation && !entry.completed && entry.allowed();
        }
    }

    public static boolean allowPrepared(int account, String path) {
        Account state = accounts.get(account);
        if (state == null || path == null) {
            return true;
        }
        synchronized (state) {
            Entry entry = state.pending.get(path);
            return entry == null || (entry.generation == state.generation && !entry.completed && entry.allowed());
        }
    }

    public static void bind(int account, String path, int localId, long dialogId, Object owner) {
        Account state = accounts.get(account);
        if (state == null || path == null) {
            return;
        }
        synchronized (state) {
            Entry entry = state.pending.get(path);
            if (entry == null || entry.generation != state.generation || entry.completed || !entry.allowed()) {
                return;
            }
            Entry existing = state.bound.get(localId);
            if (existing != null && existing.generation == state.generation && !existing.completed) {
                return;
            }
            state.pending.remove(path);
            entry.owner = owner;
            entry.dialogId = dialogId;
            state.bound.put(localId, entry);
        }
    }

    public static void retry(int account, int localId, long dialogId, String path, Object owner, PurpleSendReceipt receipt) {
        Account state = account(account);
        synchronized (state) {
            Entry entry = new Entry(account, state.generation, path, receipt);
            entry.owner = owner;
            entry.dialogId = dialogId;
            state.bound.put(localId, entry);
        }
    }

    public static void finish(int account, int localId, Object owner, int serverId) {
        Account state = accounts.get(account);
        if (state == null) {
            return;
        }
        Entry entry;
        synchronized (state) {
            entry = state.bound.get(localId);
            if (entry == null || entry.generation != state.generation || entry.completed || !entry.allowed() || owner == null || entry.owner != owner) {
                return;
            }
            state.bound.remove(localId);
            entry.completed = true;
        }
        deliver(entry.receipt, serverId, false);
    }

    public static void cancel(int account, int localId, long dialogId, String path) {
        Account state = accounts.get(account);
        if (state == null || localId >= 0 || dialogId == 0 || path == null || path.isEmpty()) {
            return;
        }
        Entry entry;
        synchronized (state) {
            entry = state.bound.get(localId);
            if (entry == null || entry.generation != state.generation || entry.completed || !entry.allowed()
                    || entry.dialogId != dialogId || !path.equals(entry.path)) {
                return;
            }
            state.bound.remove(localId);
            entry.completed = true;
        }
        deliver(entry.receipt, 0, false);
    }

    public static void cleanup(int account) {
        Account state = accounts.get(account);
        if (state == null) {
            return;
        }
        ArrayList<PurpleSendReceipt> cancelled = new ArrayList<>();
        synchronized (state) {
            state.generation++;
            for (Entry entry : state.pending.values()) {
                if (!entry.completed) {
                    entry.completed = true;
                    cancelled.add(entry.receipt);
                }
            }
            for (Entry entry : state.bound.values()) {
                if (!entry.completed) {
                    entry.completed = true;
                    cancelled.add(entry.receipt);
                }
            }
            state.bound.clear();
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (PurpleSendReceipt receipt : cancelled) {
                deliver(receipt, 0, false);
            }
        });
    }

    private static void deliver(PurpleSendReceipt receipt, int serverId, boolean preparationFailed) {
        try {
            receipt.onResult(serverId, preparationFailed);
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
    }
}
