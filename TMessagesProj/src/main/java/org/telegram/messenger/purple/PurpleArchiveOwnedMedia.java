package org.telegram.messenger.purple;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

final class PurpleArchiveOwnedMedia {
    private static final int CHUNK = 1024 * 1024;
    static final class Prepared {
        final String stagingId, blobToken, hash;
        final PurpleArchiveBinding binding;
        final long bytes, chunks;
        private Prepared(PurpleArchiveBinding binding, String stagingId, String blobToken, String hash, long bytes) {
            this.binding = binding;
            this.stagingId = stagingId; this.blobToken = blobToken; this.hash = hash; this.bytes = bytes;
            chunks = bytes == 0 ? 1 : 1 + (bytes - 1) / CHUNK;
        }
    }
    static Prepared stage(PurpleArchiveStore store, InputStream alreadyDownloaded, long length, String hash) throws Exception {
        if (alreadyDownloaded == null || length < 0 || hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Archive owned media identity");
        store.requireCurrent();
        JSONObject token = PurpleArchiveCore.call("blobToken", store.arguments().put("blob", hash), store.crypto());
        if (!token.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive blob derivation refused");
        Prepared result = new Prepared(store.binding, PurpleArchiveBinding.token(), token.getString("token"), hash, length);
        PurpleArchiveFiles.Directory staging = store.directory.child("staging", true).exclusiveChild(result.stagingId);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (long index = 0; index < result.chunks; ++index) {
            store.requireCurrent();
            int expected = (int) Math.min(CHUNK, length - index * CHUNK);
            byte[] plaintext = new byte[expected];
            try {
                int offset = 0;
                while (offset < plaintext.length) {
                    int read = alreadyDownloaded.read(plaintext, offset, plaintext.length - offset);
                    if (read < 0) throw new PurpleArchiveStore.Failure("Archive media source truncated");
                    if (read == 0) { int one = alreadyDownloaded.read(); if (one < 0) throw new PurpleArchiveStore.Failure("Archive media source truncated"); plaintext[offset++] = (byte) one; }
                    else offset += read;
                }
                digest.update(plaintext);
                byte[] frame = PurpleArchiveCore.mediaNative(store.binding.account, store.binding.store, store.binding.key,
                    result.blobToken, length, index, plaintext, true, store.crypto());
                if (frame == null) throw new PurpleArchiveStore.Failure("Archive media encryption unavailable");
                synchronized (store.environment.publicationLock()) {
                    store.requireCurrent(); String name = chunk(index);
                    staging.publish(name, new PurpleArchiveFiles.Snapshot(null, null), frame);
                }
            } finally { Arrays.fill(plaintext, (byte) 0); }
        }
        if (alreadyDownloaded.read() != -1 || !hex(digest.digest()).equals(hash)) throw new PurpleArchiveStore.Failure("Archive media source changed");
        verify(store, staging, result, null);
        return result;
    }
    static void publish(PurpleArchiveStore store, Prepared prepared) throws Exception {
        bound(store, prepared);
        PurpleArchiveFiles.Directory staging = new PurpleArchiveFiles.Directory(store.directory.child("staging", true).file(prepared.stagingId), true);
        verify(store, staging, prepared, null);
        PurpleArchiveFiles.Directory blobs = store.directory.child("blobs", true);
        synchronized (store.environment.publicationLock()) {
            store.requireCurrent();
            if (PurpleArchiveFiles.markAny(blobs.file(prepared.blobToken)) != null)
                throw new PurpleArchiveStore.Failure("Archive immutable blob already exists");
            PurpleArchiveFiles.Directory owned = blobs.exclusiveChild(prepared.blobToken);
            for (long i = 0; i < prepared.chunks; ++i) {
                store.requireCurrent(); String name = chunk(i);
                byte[] frame = staging.read(name, CHUNK + 65536).bytes;
                owned.publish(name, new PurpleArchiveFiles.Snapshot(null, null), frame);
            }
            verify(store, owned, prepared, null);
        }
    }
    static void readVerified(PurpleArchiveStore store, Prepared authenticatedBlob, OutputStream destination) throws Exception {
        if (destination == null) throw new IllegalArgumentException("Archive media destination");
        bound(store, authenticatedBlob);
        try (PurpleArchiveStore.Reader lease = store.reader()) {
            PurpleArchiveFiles.Directory owned = new PurpleArchiveFiles.Directory(store.directory.child("blobs", true).file(authenticatedBlob.blobToken), true);
            verify(store, owned, authenticatedBlob, null);
            if (!lease.current()) throw new PurpleArchiveStore.Failure("Archive media reader revoked");
            verify(store, owned, authenticatedBlob, destination);
        }
    }
    private static void verify(PurpleArchiveStore store, PurpleArchiveFiles.Directory directory, Prepared blob, OutputStream output) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
        for (long i = 0; i < blob.chunks; ++i) {
            store.requireCurrent();
            byte[] frame = directory.read(chunk(i), CHUNK + 65536).bytes;
            if (frame == null) throw new PurpleArchiveStore.Failure("Archive owned chunk missing");
            byte[] plain = PurpleArchiveCore.mediaNative(store.binding.account, store.binding.store, store.binding.key,
                blob.blobToken, blob.bytes, i, frame, false, store.crypto());
            if (plain == null) throw new PurpleArchiveStore.Failure("Archive owned chunk authentication failed");
            try {
                digest.update(plain); count = Math.addExact(count, plain.length);
                if (output != null) { store.requireCurrent(); output.write(plain); }
            } finally { Arrays.fill(plain, (byte) 0); }
        }
        if (count != blob.bytes || !hex(digest.digest()).equals(blob.hash)) throw new PurpleArchiveStore.Failure("Archive owned file verification failed");
        store.requireCurrent();
    }
    private static void bound(PurpleArchiveStore store, Prepared prepared) throws Exception {
        store.requireCurrent();
        if (prepared == null || !PurpleArchiveStore.same(store.binding, prepared.binding))
            throw new PurpleArchiveStore.Failure("Archive media store epoch mismatch");
    }
    private static String chunk(long index) { return "chunk-" + Long.toString(index); }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return text.toString();
    }
    private PurpleArchiveOwnedMedia() {}
}
