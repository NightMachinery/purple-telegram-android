package org.telegram.messenger.purple;

import androidx.annotation.Keep;

@Keep
public final class PurplePlaylistsLocalCore {
    @Keep
    public enum Status { Valid, NewerFormat, Invalid }

    @Keep
    public static final class Result {
        public final Status status;
        public final String error;
        public final String playlistError;
        private final byte[] canonical;

        private Result(String status, String error, String playlistError, byte[] canonical) {
            this.status = Status.valueOf(status);
            if (error == null || error.isEmpty() || playlistError == null || playlistError.isEmpty())
                throw new IllegalStateException("Missing local playlist diagnostic");
            this.error = error;
            this.playlistError = playlistError;
            this.canonical = canonical;
            if (valid() ? canonical == null || !"None".equals(error) || !"None".equals(playlistError)
                    : canonical != null)
                throw new IllegalStateException("Invalid local playlist result");
        }

        public boolean valid() { return status == Status.Valid; }
        public byte[] canonicalBytes() { return canonical == null ? null : canonical.clone(); }
    }

    public static Result canonicalize(byte[] local, long selfUserId) {
        PurpleCore.ensureLoaded();
        return require(canonicalizeNative(local, selfUserId));
    }

    public static Result defaults(long selfUserId) {
        PurpleCore.ensureLoaded();
        return require(defaultsNative(selfUserId));
    }

    public static Result setSync(byte[] local, boolean enabled, long selfUserId) {
        PurpleCore.ensureLoaded();
        return require(setSyncNative(local, enabled, selfUserId));
    }

    static Result checkpoint(byte[] local, boolean synced, long now, long selfUserId) {
        PurpleCore.ensureLoaded();
        return require(checkpointNative(local, synced, now, selfUserId));
    }

    enum SyncResult { Idle, Synced, Offline, Wait, Problem, Failed, Oversized, NewerFormat }
    static Result checkpoint(byte[] local, SyncResult result, long now, long selfUserId) {
        return checkpoint(local, result, now, 0, selfUserId);
    }
    static Result checkpoint(byte[] local, SyncResult result, long now, long retryAt, long selfUserId) {
        if (result == null || retryAt < 0) throw new IllegalArgumentException("MissingPlaylistSyncStatus");
        PurpleCore.ensureLoaded();
        Result changed = require(outcomeNative(local, result.ordinal(), now, selfUserId));
        if (!changed.valid()) return changed;
        try {
            org.json.JSONObject state = new org.json.JSONObject(new String(changed.canonicalBytes(), java.nio.charset.StandardCharsets.UTF_8));
            org.json.JSONObject status = state.getJSONObject("status");
            status.remove("retry_at_ms");
            if (retryAt > now && (result == SyncResult.Wait || result == SyncResult.Offline))
                status.put("retry_at_ms", Long.toString(retryAt));
            return canonicalize(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), selfUserId);
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("InvalidPlaylistSyncStatus", error); }
    }
    static long retryAt(byte[] local) {
        try {
            org.json.JSONObject status = new org.json.JSONObject(new String(local, java.nio.charset.StandardCharsets.UTF_8)).optJSONObject("status");
            if (status == null || !("wait".equals(status.optString("result")) || "offline".equals(status.optString("result")))) return 0;
            Object value = status.opt("retry_at_ms");
            if (!(value instanceof String) || !((String) value).matches("[0-9]{1,19}")) return 0;
            long deadline = Long.parseLong((String) value);
            return deadline > 0 ? deadline : 0;
        } catch (org.json.JSONException | NumberFormatException error) { return 0; }
    }
    static Result recoverEdit(byte[] local, long earliest, long selfUserId) {
        PurpleCore.ensureLoaded();
        return require(recoverEditNative(local, earliest, selfUserId));
    }

    private static native Result recoverEditNative(byte[] local, long earliest, long selfUserId);
    private static native Result outcomeNative(byte[] local, int result, long now, long selfUserId);

    private static Result require(Result result) {
        if (result == null) throw new IllegalStateException("Missing local playlist bridge result");
        return result;
    }

    private static native Result checkpointNative(byte[] local, boolean synced, long now, long selfUserId);
    private static native Result canonicalizeNative(byte[] local, long selfUserId);
    private static native Result defaultsNative(long selfUserId);
    private static native Result setSyncNative(byte[] local, boolean enabled, long selfUserId);

    private PurplePlaylistsLocalCore() { }
}
