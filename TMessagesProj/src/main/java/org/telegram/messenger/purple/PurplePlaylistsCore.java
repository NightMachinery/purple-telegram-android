package org.telegram.messenger.purple;

import androidx.annotation.Keep;

@Keep
public final class PurplePlaylistsCore {
    @Keep
    public enum Status { Valid, NewerFormat, Invalid }
    @Keep
    public enum Input { None, Local, Remote }

    @Keep
    public static final class Result {
        public final Status status;
        public final String error;
        public final Input input;
        public final Boolean contains;
        private final byte[] canonical;

        private Result(String status, String error, String input, byte[] canonical, int contains) {
            this.status = Status.valueOf(status);
            this.error = error;
            this.input = Input.valueOf(input);
            if (contains < -1 || contains > 1 || error == null || error.isEmpty())
                throw new IllegalStateException("Invalid playlist bridge result");
            this.contains = contains < 0 ? null : contains != 0;
            this.canonical = canonical;
            if (valid()) {
                if (!"None".equals(error) || this.input != Input.None
                        || (canonical == null) == (this.contains == null))
                    throw new IllegalStateException("Incomplete playlist bridge result");
            } else if (canonical != null || this.contains != null) {
                throw new IllegalStateException("Playlist refusal has a result");
            }
        }

        public boolean valid() { return status == Status.Valid; }
        public byte[] canonicalBytes() { return canonical == null ? null : canonical.clone(); }
    }

    public static Result canonicalize(byte[] local, long selfUserId) {
        PurpleCore.ensureLoaded();
        return require(canonicalizeNative(local, selfUserId));
    }

    public static Result join(byte[] local, byte[] remote, long selfUserId) {
        PurpleCore.ensureLoaded();
        return require(joinNative(local, remote, selfUserId));
    }

    public static Result contains(byte[] outer, byte[] inner, long selfUserId) {
        PurpleCore.ensureLoaded();
        return require(containsNative(outer, inner, selfUserId));
    }

    private static Result require(Result result) {
        if (result == null) throw new IllegalStateException("Missing playlist bridge result");
        return result;
    }

    private static native Result canonicalizeNative(byte[] local, long selfUserId);
    private static native Result joinNative(byte[] local, byte[] remote, long selfUserId);
    private static native Result containsNative(byte[] outer, byte[] inner, long selfUserId);

    private PurplePlaylistsCore() { }
}
