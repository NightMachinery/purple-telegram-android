package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import org.json.JSONObject;

final class PurpleArchiveCatalogue {
    static void requireAdmission(PurpleArchivePaths paths, PurpleArchiveKeys.Provider keys) throws Exception {
        try {
            if (paths.metadata.read("recovery.json", 65536).bytes != null) throw new PurpleArchiveStore.Failure("Archive catalogue recovery required");
            PurpleArchiveFiles.Snapshot bound = paths.metadata.read("binding.json", 65536);
            PurpleArchiveFiles.Snapshot creating = paths.metadata.read("creating.json", 65536);
            PurpleArchiveBinding authority = bound.bytes != null ? new PurpleArchiveBinding(paths.account, PurpleArchiveBinding.json(bound.bytes))
                : creating.bytes != null ? new PurpleArchiveBinding(paths.account, PurpleArchiveBinding.json(creating.bytes).getJSONObject("binding")) : null;
            if (bound.bytes != null && !PurpleArchiveStore.matches(authority, paths.metadata.read("fence.json", 65536).bytes))
                throw new PurpleArchiveStore.Failure("Archive catalogue fence ambiguous");
            if (authority != null && !hasUnexpectedStore(paths, authority.store) && !hasUnexpectedKey(paths.account, keys, authority.alias())) return;
            if (authority == null && !hasUnexpectedStore(paths, null) && !hasUnexpectedKey(paths.account, keys, null) && cleared(paths)) return;
            throw new PurpleArchiveStore.Failure("Archive catalogue authority ambiguous");
        } catch (Exception | LinkageError e) {
            requireRecovery(paths);
            throw e;
        }
    }
    static void requireRecovery(PurpleArchivePaths paths) throws Exception {
        PurpleArchiveFiles.Snapshot old = paths.metadata.read("recovery.json", 65536);
        if (old.bytes == null) paths.metadata.publish("recovery.json", old, bytes(new JSONObject()
            .put("format", "purple-archive-recovery").put("version", 1).put("account", paths.account)));
    }
    static boolean cleared(PurpleArchivePaths paths) throws Exception {
        byte[] fence = paths.metadata.read("fence.json", 65536).bytes;
        byte[] receipt = paths.metadata.read("cleared.json", 65536).bytes;
        if (fence != null && receipt == null) return false;
        if (receipt != null) {
            JSONObject value = PurpleArchiveBinding.json(receipt);
            if (!value.getString("account").equals(paths.account)) return false;
            byte[] recorded = value.isNull("fence") ? null : PurpleArchiveCore.unbase64(value.getString("fence"));
            if (!java.util.Arrays.equals(recorded, fence)) return false;
        }
        try (PurpleArchiveFiles.Listing listing = paths.metadata.listing()) {
            for (;;) {
                String[] page = listing.next(); if (page.length == 0) break;
                for (String name : page) if (!name.equals("lock") && !name.equals("fence.json") && !name.equals("cleared.json") && !name.equals("login.json")) return false;
            }
        }
        return true;
    }
    static void acknowledgeCleared(PurpleArchivePaths paths) throws Exception {
        byte[] fence = paths.metadata.read("fence.json", 65536).bytes;
        JSONObject value = new JSONObject().put("account", paths.account)
            .put("fence", fence == null ? JSONObject.NULL : PurpleArchiveCore.base64(fence));
        paths.metadata.publish("cleared.json", paths.metadata.read("cleared.json", 65536), bytes(value));
    }
    static boolean hasUnexpectedStore(PurpleArchivePaths paths, String expected) throws Exception {
        if (PurpleArchiveFiles.mark(paths.accountsRoot.file(paths.account), true) == null) return false;
        PurpleArchiveFiles.Directory account = new PurpleArchiveFiles.Directory(paths.accountsRoot.file(paths.account), true);
        try (PurpleArchiveFiles.Listing listing = account.listing()) {
            for (;;) {
                String[] page = listing.next(); if (page.length == 0) break;
                for (String name : page) if (!name.equals(expected)) return true;
            }
        }
        return false;
    }
    static boolean hasUnexpectedKey(String account, PurpleArchiveKeys.Provider keys, String expected) throws Exception {
        Enumeration<String> aliases = keys.aliases(); String prefix = PurpleArchiveKeys.prefix(account);
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (alias.startsWith(prefix) && !alias.equals(expected)) return true;
        }
        return false;
    }
    static void removeKeys(String account, PurpleArchiveKeys.Provider keys) throws Exception {
        String prefix = PurpleArchiveKeys.prefix(account);
        for (;;) {
            ArrayList<String> page = new ArrayList<>(); Enumeration<String> aliases = keys.aliases();
            while (aliases.hasMoreElements() && page.size() < 128) {
                String alias = aliases.nextElement(); if (alias.startsWith(prefix)) page.add(alias);
            }
            if (page.isEmpty()) return;
            for (String alias : page) {
                keys.remove(alias);
                if (keys.find(alias) != null) throw new PurpleArchiveStore.Failure("Archive key removal incomplete");
            }
        }
    }
    static byte[] bytes(JSONObject value) throws Exception { return PurpleArchiveBinding.canonical(value.toString().getBytes(StandardCharsets.UTF_8)); }
    private PurpleArchiveCatalogue() {}
}
