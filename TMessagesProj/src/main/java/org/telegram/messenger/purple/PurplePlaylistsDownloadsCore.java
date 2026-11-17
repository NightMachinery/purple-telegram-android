package org.telegram.messenger.purple;

import androidx.annotation.Keep;

@Keep
public final class PurplePlaylistsDownloadsCore {
    @Keep
    public enum Status { Ready, Invalid, Newer, Changed, Busy, IoError, Unsupported }
    public enum Preference { Inherit, On, Off }
    public enum Phase { Downloaded, Unavailable, Failed, Busy }

    @Keep
    public static final class Result {
        public final Status status;
        private final byte[] local, inventory;
        private Result(String status, byte[] local, byte[] inventory) {
            this.status = Status.valueOf(status);
            if (ready() ? local == null || inventory == null : local != null || inventory != null)
                throw new IllegalStateException("Invalid download result");
            this.local = local;
            this.inventory = inventory;
        }
        public boolean ready() { return status == Status.Ready; }
        public byte[] local() { return local == null ? null : local.clone(); }
        public byte[] inventory() { return inventory == null ? null : inventory.clone(); }
        Preference preference(String playlist) {
            if (!ready() || playlist == null) throw new IllegalStateException("Missing validated download preference");
            try {
                org.json.JSONObject values = new org.json.JSONObject(new String(inventory, java.nio.charset.StandardCharsets.UTF_8))
                        .getJSONObject("preferences");
                if (!values.has(playlist)) return Preference.Inherit;
                String value = values.getString(playlist);
                if (value.equals("on")) return Preference.On;
                if (value.equals("off")) return Preference.Off;
                throw new IllegalStateException("Invalid validated download preference");
            } catch (org.json.JSONException error) { throw new IllegalStateException("Invalid validated inventory", error); }
        }
    }

    @Keep
    static final class RemovalPlan {
        final Status status;
        final boolean present, pending, cleanupSelected, cleanupPrune;
        final int protectedFiles;
        private final long[] candidates;
        private final String[] intents;
        @Keep
        private RemovalPlan(String status, boolean present, boolean pending, int protectedFiles, long[] candidates, String[] intents, boolean cleanupSelected, boolean cleanupPrune) {
            this.status = Status.valueOf(status); this.present = present; this.pending = pending;
            this.protectedFiles = protectedFiles; this.candidates = candidates; this.intents = intents;
            this.cleanupSelected = cleanupSelected; this.cleanupPrune = cleanupPrune;
            if (protectedFiles < 0 || candidates == null || intents == null
                    || this.status != Status.Ready && (candidates.length != 0 || intents.length != 0))
                throw new IllegalStateException("Invalid removal plan");
        }
        long[] candidates() { return candidates.clone(); }
        String[] intents() { return intents.clone(); }
    }
    static RemovalPlan removal(byte[] library, byte[] local, long self, String playlist) {
        PurpleCore.ensureLoaded();
        RemovalPlan value = removalNative(library, local, self, playlist);
        if (value == null) throw new IllegalStateException("Missing removal projection");
        return value;
    }
    private static native RemovalPlan removalNative(byte[] library, byte[] local, long self, String playlist);

    static Result cleanup(byte[] local, long self, String playlist, boolean pending) {
        return transition(local, self, playlist == null ? (pending ? 9 : 10) : (pending ? 11 : 12),
                playlist, null, 0, null, 0, null, null);
    }
    static Result normalizeCleanup(byte[] library, byte[] local, long self) {
        PurpleCore.ensureLoaded();
        Result value = normalizeCleanupNative(library, local, self);
        if (value == null) throw new IllegalStateException("Missing cleanup normalization");
        return value;
    }
    private static native Result normalizeCleanupNative(byte[] library, byte[] local, long self);

    public static Result read(byte[] local, long self) {
        return transition(local, self, 0, null, null, 0, null, 0, null, null);
    }
    public static Result preference(byte[] local, long self, String playlist, Preference value) {
        if (value == null) throw new IllegalArgumentException("Missing preference");
        return transition(local, self, 1 + value.ordinal(), playlist, null, 0, null, 0, null, null);
    }
    public static Result reserve(byte[] local, long self, String playlist, String token,
            long document, String extension, long size) {
        return transition(local, self, 4, playlist, token, document, extension, size, "", "");
    }
    public static Result checkpoint(byte[] local, long self, String playlist, String token,
            long document, String extension, long size, String identity, String sha256) {
        return transition(local, self, 5, playlist, token, document, extension, size, identity, sha256);
    }
    public static Result complete(byte[] local, long self, String playlist, String token,
            long document, String extension, long size, String identity, String sha256) {
        return transition(local, self, 6, playlist, token, document, extension, size, identity, sha256);
    }
    public static Result forgetIntent(byte[] local, long self, String playlist, String token,
            long document, String extension, long size, String identity, String sha256) {
        return transition(local, self, 7, playlist, token, document, extension, size, identity, sha256);
    }
    public static Result forgetFile(byte[] local, long self, long document, String extension,
            long size, String identity, String sha256) {
        return transition(local, self, 8, null, null, document, extension, size, identity, sha256);
    }
    private static Result transition(byte[] local, long self, int operation, String playlist, String token,
            long document, String extension, long size, String identity, String hash) {
        PurpleCore.ensureLoaded();
        Result result = transitionNative(local, self, operation, playlist, token, document, extension,
                size, identity, hash);
        if (result == null) throw new IllegalStateException("Missing download bridge result");
        return result;
    }

    @Keep
    public static final class Record {
        public final boolean complete;
        public final String playlist, token, extension, identity, sha256;
        public final long document, size;
        @Keep
        private Record(boolean complete, String playlist, String token, long document,
                String extension, long size, String identity, String sha256) {
            this.complete = complete; this.playlist = playlist; this.token = token;
            this.document = document; this.extension = extension; this.size = size;
            this.identity = identity; this.sha256 = sha256;
        }
    }
    public static Record[] records(byte[] local, long self) {
        PurpleCore.ensureLoaded();
        Record[] values = recordsNative(local, self);
        if (values == null) throw new IllegalStateException("Missing inventory projection");
        return values;
    }
    private static native Record[] recordsNative(byte[] local, long self);

    public static final class Ticket {
        private final Job owner;
        private final long generation;
        public final int index;
        private Ticket(Job owner, long generation, int index) {
            this.owner = owner; this.generation = generation; this.index = index;
        }
    }
    public static final class Progress {
        public final long total, downloaded, unavailable, failed, busy, active;
        public final boolean terminal, canceled;
        private Progress(long[] values) {
            if (values == null || values.length != 8) throw new IllegalStateException("Missing job progress");
            total = values[0]; downloaded = values[1]; unavailable = values[2];
            failed = values[3]; busy = values[4]; active = values[5];
            terminal = values[6] != 0; canceled = values[7] != 0;
        }
    }
    public static final class Job implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private long handle;
        public Job(int count) {
            PurpleCore.ensureLoaded();
            handle = openNative(count);
            if (handle == 0) throw new IllegalStateException("Missing job handle");
        }
        private long handle() {
            if (Thread.currentThread() != thread || handle == 0)
                throw new IllegalStateException("Closed or wrong-thread download job");
            return handle;
        }
        public Ticket[] admit() {
            long[] values = admitNative(handle());
            if (values == null || values.length % 2 != 0) throw new IllegalStateException("Invalid job tickets");
            Ticket[] result = new Ticket[values.length / 2];
            for (int i = 0; i < result.length; ++i) result[i] = new Ticket(this, values[2*i], (int) values[2*i+1]);
            return result;
        }
        public boolean finish(Ticket ticket, Phase phase) {
            long value = handle();
            return ticket != null && ticket.owner == this && phase != null
                    && finishNative(value, ticket.generation, ticket.index, 2 + phase.ordinal());
        }
        public boolean retry() { return controlNative(handle(), 0); }
        public void cancel() { controlNative(handle(), 1); }
        public Progress progress() { return new Progress(progressNative(handle())); }
        @Override public void close() {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Wrong job thread");
            if (handle == 0) return;
            controlNative(handle(), 2);
            handle = 0;
        }
    }

    private static native Result transitionNative(byte[] local, long self, int operation,
            String playlist, String token, long document, String extension, long size, String identity, String hash);
    private static native long openNative(int count);
    private static native long[] admitNative(long handle);
    private static native boolean finishNative(long handle, long generation, int index, int phase);
    private static native boolean controlNative(long handle, int operation);
    private static native long[] progressNative(long handle);
    private PurplePlaylistsDownloadsCore() { }
}
