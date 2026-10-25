package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import org.json.JSONObject;

final class PurpleArchiveRemoval {
    private final PurpleArchivePaths paths;
    private final PurpleArchiveBinding binding;
    private JSONObject plan;
    private PurpleArchiveRemoval(PurpleArchivePaths paths, PurpleArchiveBinding binding, JSONObject plan) {
        this.paths = paths; this.binding = binding; this.plan = plan;
    }
    static PurpleArchiveRemoval discard(PurpleArchivePaths paths) throws Exception { return beginAccount(paths); }
    static PurpleArchiveRemoval begin(PurpleArchivePaths paths) throws Exception {
        PurpleArchiveFiles.Snapshot pending;
        try { pending = paths.metadata.read("removing.json", 65536); }
        catch (Exception | LinkageError e) { return beginAccount(paths); }
        if (pending.bytes != null) {
            try { return resume(paths, pending.bytes); }
            catch (Exception | LinkageError e) { return beginAccount(paths); }
        }
        PurpleArchiveFiles.Snapshot bound, creating;
        try {
            bound = paths.metadata.read("binding.json", 65536);
            creating = paths.metadata.read("creating.json", 65536);
        } catch (Exception | LinkageError e) { return beginAccount(paths); }
        if (bound.bytes == null && creating.bytes == null || PurpleArchiveFiles.markAny(paths.metadata.file("recovery.json")) != null)
            return beginAccount(paths);
        PurpleArchiveBinding binding;
        try {
            binding = new PurpleArchiveBinding(paths.account, bound.bytes != null
                ? PurpleArchiveBinding.json(bound.bytes) : PurpleArchiveBinding.json(creating.bytes).getJSONObject("binding"));
            if (bound.bytes != null && creating.bytes != null && !PurpleArchiveStore.same(binding,
                new PurpleArchiveBinding(paths.account, PurpleArchiveBinding.json(creating.bytes).getJSONObject("binding")))) return beginAccount(paths);
            if (PurpleArchiveCatalogue.hasUnexpectedStore(paths, binding.store)) return beginAccount(paths);
        } catch (Exception | LinkageError e) { return beginAccount(paths); }
        JSONObject plan = PurpleArchiveCore.call("removeBegin", binding.arguments().put("generation", binding.generation)
            .put("operationId", PurpleArchiveBinding.token()), null);
        if (!plan.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive removal refused");
        JSONObject durable = new JSONObject().put("binding", binding.value).put("tombstone", plan.getString("tombstone"));
        paths.metadata.publish("removing.json", pending, PurpleArchiveBinding.canonical(durable.toString().getBytes(StandardCharsets.UTF_8)));
        PurpleArchiveRemoval removal = new PurpleArchiveRemoval(paths, binding, plan);
        removal.advance();
        removal.fence();
        return removal;
    }
    static PurpleArchiveRemoval resume(PurpleArchivePaths paths, byte[] bytes) throws Exception {
        JSONObject durable = PurpleArchiveBinding.json(bytes);
        if (durable.optString("format").equals("purple-archive-account-removal")) {
            if (durable.getInt("version") != 1 || !durable.getString("account").equals(paths.account))
                throw new PurpleArchiveStore.Failure("Archive removal account changed");
            return new PurpleArchiveRemoval(paths, null, durable);
        }
        PurpleArchiveBinding binding = new PurpleArchiveBinding(paths.account, durable.getJSONObject("binding"));
        JSONObject plan = PurpleArchiveCore.call("removeResume", binding.arguments().put("tombstone", durable.getString("tombstone")), null);
        if (!plan.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Unreadable archive removal intent");
        PurpleArchiveRemoval removal = new PurpleArchiveRemoval(paths, binding, plan);
        removal.fence();
        return removal;
    }
    private static PurpleArchiveRemoval beginAccount(PurpleArchivePaths paths) throws Exception {
        JSONObject durable = new JSONObject().put("format", "purple-archive-account-removal").put("version", 1)
            .put("account", paths.account).put("operation", PurpleArchiveBinding.token());
        paths.metadata.check();
        PurpleArchiveFiles.Snapshot old = new PurpleArchiveFiles.Snapshot(PurpleArchiveFiles.mark(paths.metadata.file("removing.json"), false), null);
        paths.metadata.publish("removing.json", old, PurpleArchiveCatalogue.bytes(durable));
        return new PurpleArchiveRemoval(paths, null, durable);
    }
    private boolean finishAccount(PurpleArchiveStore store, PurpleArchiveKeys.Provider keys) throws Exception {
        if (store != null) {
            if (!store.binding.account.equals(paths.account)) throw new PurpleArchiveStore.Failure("Archive removal account changed");
            store.drainReaders(); store.closeDatabase();
        }
        byte[] retainedFence = null;
        try {
            byte[] value = paths.metadata.read("fence.json", 65536).bytes;
            if (value != null) { new PurpleArchiveBinding(paths.account, PurpleArchiveBinding.json(value)); retainedFence = value; }
        } catch (Exception | LinkageError e) {}
        PurpleArchiveCatalogue.removeKeys(paths.account, keys);
        removeTree(paths.accountsRoot, paths.account);
        try (PurpleArchiveFiles.Listing listing = paths.metadata.listing()) {
            for (;;) {
                String[] page = listing.next(); if (page.length == 0) break;
                for (String name : page) {
                    if (name.equals("lock") || name.equals("removing.json") || name.equals("fence.json") && retainedFence != null) continue;
                    PurpleArchiveFiles.Mark mark = PurpleArchiveFiles.markAny(paths.metadata.file(name));
                    if (mark == null) continue;
                    if ((mark.mode & 0170000) == 0040000) removeTree(paths.metadata, name);
                    else paths.metadata.remove(name, false);
                }
            }
        }
        if (PurpleArchiveCatalogue.hasUnexpectedStore(paths, null) || PurpleArchiveCatalogue.hasUnexpectedKey(paths.account, keys, null))
            throw new PurpleArchiveStore.Failure("Archive account cleanup incomplete");
        PurpleArchiveCatalogue.acknowledgeCleared(paths);
        paths.metadata.remove("removing.json", false);
        return true;
    }
    private void advance() throws Exception {
        JSONObject arguments = binding.arguments().put("tombstone", plan.getString("tombstone"))
            .put("operationId", plan.getString("operation")).put("generation", plan.getString("generation"))
            .put("action", plan.getInt("action")).put("success", true).put("durable", true)
            .put("readers", "0").put("writers", "0").put("closed", true);
        JSONObject next = PurpleArchiveCore.call("removeAdvance", arguments, null);
        if (!next.optString("status").equals("Ready") || next.getInt("advance") != 0
            || next.getInt("action") != plan.getInt("action") + 1) throw new PurpleArchiveStore.Failure("Archive removal barrier refused");
        plan = next;
    }
    private void fence() throws Exception {
        if (plan.getInt("action") != 1) throw new PurpleArchiveStore.Failure("Archive removal fence order");
        PurpleArchiveFiles.Snapshot old = paths.metadata.read("fence.json", 65536);
        JSONObject next = new JSONObject(binding.value.toString()).put("generation", plan.getString("generation"));
        PurpleArchiveBinding fence = new PurpleArchiveBinding(binding.account, next);
        if (old.bytes != null) {
            PurpleArchiveBinding existing = new PurpleArchiveBinding(binding.account, PurpleArchiveBinding.json(old.bytes));
            if (!PurpleArchiveStore.same(existing, binding) && !PurpleArchiveStore.same(existing, fence))
                throw new PurpleArchiveStore.Failure("Archive removal fence changed");
        }
        paths.metadata.publish("fence.json", old, fence.bytes());
        advance();
    }
    void finish(PurpleArchiveStore store, PurpleArchiveKeys.Provider keys) throws Exception {
        if (binding != null && (PurpleArchiveCatalogue.hasUnexpectedStore(paths, binding.store)
            || PurpleArchiveCatalogue.hasUnexpectedKey(paths.account, keys, binding.alias()))) {
            PurpleArchiveRemoval account = beginAccount(paths); account.finishAccount(store, keys); return;
        }
        while (!step(store, keys)) {}
    }
    boolean step(PurpleArchiveStore store, PurpleArchiveKeys.Provider keys) throws Exception {
        if (binding == null) return finishAccount(store, keys);
        if (plan.getInt("action") == 12) return true;
        if (plan.getInt("action") == 2) {
        if (store != null) {
            if (!PurpleArchiveStore.same(store.binding, binding)) throw new PurpleArchiveStore.Failure("Archive removal store mismatch");
            store.drainReaders();
            store.closeDatabase();
        }
        advance(); return false;
        }
        {
            int action = plan.getInt("action");
            PurpleArchiveFiles.Directory directory = existingStore();
            if (action >= 3 && action <= 6) {
                if (directory != null) directory.remove(new String[]{"archive.sqlite", "archive.sqlite-wal", "archive.sqlite-shm", "archive.sqlite-journal"}[action - 3], false);
            } else if (action == 7 || action == 8) {
                if (directory != null) removeTree(directory, action == 7 ? "blobs" : "staging");
            } else if (action == 9) {
                if (directory != null) directory.remove("wrapped.key", false);
                keys.remove(binding.alias());
                if (keys.find(binding.alias()) != null) throw new PurpleArchiveStore.Failure("Archive key removal incomplete");
            } else if (action == 10) {
                if (directory != null) {
                    removeTree(directory, "causal");
                    try (PurpleArchiveFiles.Listing listing = directory.listing()) {
                        for (;;) {
                            String[] page = listing.next(); if (page.length == 0) break;
                            for (String name : page) {
                                if (!name.matches("\\.new-[0-9a-fA-F-]{36}")) throw new PurpleArchiveStore.Failure("Unknown archive artifact");
                                directory.remove(name, false);
                            }
                        }
                    }
                    new PurpleArchiveFiles.Directory(paths.accountsRoot.file(binding.account), true).remove(binding.store, true);
                }
                removeBinding("binding.json", false); removeBinding("creating.json", true);
                PurpleArchiveFiles.Snapshot gap = paths.metadata.read("gap-outbox.json", 65536);
                if (gap.bytes != null) {
                    JSONObject value = PurpleArchiveBinding.json(gap.bytes);
                    if (!value.getString("account").equals(binding.account) || !value.getString("store").equals(binding.store))
                        throw new PurpleArchiveStore.Failure("Archive gap removal scope changed");
                    paths.metadata.remove("gap-outbox.json", false);
                }
            } else if (action == 11) {
                if (PurpleArchiveCatalogue.hasUnexpectedStore(paths, null) || PurpleArchiveCatalogue.hasUnexpectedKey(paths.account, keys, null))
                    throw new PurpleArchiveStore.Failure("Archive account cleanup incomplete");
                paths.metadata.remove("recovery.json", false);
                paths.metadata.remove("login.json", false);
                paths.metadata.remove("capture-gap.json", false);
                paths.metadata.remove("capture-inflight.json", false);
                PurpleArchiveCatalogue.acknowledgeCleared(paths);
                paths.metadata.remove("removing.json", false);
            } else throw new PurpleArchiveStore.Failure("Unknown archive removal action");
            advance();
        }
        return plan.getInt("action") == 12;
    }
    private void removeBinding(String name, boolean creating) throws Exception {
        PurpleArchiveFiles.Snapshot old = paths.metadata.read(name, 65536);
        if (old.bytes == null) return;
        JSONObject value = PurpleArchiveBinding.json(old.bytes);
        PurpleArchiveBinding existing = new PurpleArchiveBinding(binding.account, creating ? value.getJSONObject("binding") : value);
        if (!PurpleArchiveStore.same(binding, existing)) throw new PurpleArchiveStore.Failure("Archive binding replaced during removal");
        paths.metadata.remove(name, false);
    }
    private PurpleArchiveFiles.Directory existingStore() throws PurpleArchiveStore.Failure {
        if (PurpleArchiveFiles.mark(paths.accountsRoot.file(binding.account), true) == null) return null;
        PurpleArchiveFiles.Directory account = new PurpleArchiveFiles.Directory(paths.accountsRoot.file(binding.account), true);
        try (PurpleArchiveFiles.Listing listing = account.listing()) {
            for (;;) {
                String[] page = listing.next(); if (page.length == 0) break;
                for (String name : page) if (!name.equals(binding.store))
                    throw new PurpleArchiveStore.Failure("Unknown account archive store");
            }
        }
        if (PurpleArchiveFiles.mark(account.file(binding.store), true) == null) return null;
        return paths.store(binding.store, false);
    }
    private static final class Frame implements AutoCloseable {
        final PurpleArchiveFiles.Directory parent, directory;
        final String name;
        final PurpleArchiveFiles.Listing listing;
        String[] page = new String[0]; int position;
        Frame(PurpleArchiveFiles.Directory parent, String name) {
            this.parent = parent; this.name = name; directory = new PurpleArchiveFiles.Directory(parent.file(name), true); listing = directory.listing();
        }
        public void close() { listing.close(); }
    }
    static void removeTree(PurpleArchiveFiles.Directory parent, String name) throws Exception {
        if (PurpleArchiveFiles.mark(parent.file(name), true) == null) return;
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        try {
            stack.push(new Frame(parent, name));
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (frame.position == frame.page.length) {
                    frame.page = frame.listing.next(); frame.position = 0;
                    if (frame.page.length == 0) {
                        frame.close(); frame.parent.remove(frame.name, true); stack.pop(); continue;
                    }
                }
                String child = frame.page[frame.position++];
                PurpleArchiveFiles.Mark mark = PurpleArchiveFiles.markAny(frame.directory.file(child));
                if (mark == null) continue;
                if ((mark.mode & 0170000) == 0100000) frame.directory.remove(child, false);
                else {
                    if (stack.size() >= 8) throw new PurpleArchiveStore.Failure("Archive directory depth refused");
                    stack.push(new Frame(frame.directory, child));
                }
            }
        } finally { while (!stack.isEmpty()) stack.pop().close(); }
    }
}
