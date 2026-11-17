package org.telegram.messenger.purple;

import androidx.annotation.Keep;

import java.io.IOException;

@Keep
final class PurplePlaylistsPhysical implements PurplePlaylistsStore.Physical {
    @Keep
    private static final class Guard {
        final PurplePlaylistsStore.Current delegate;
        Guard(PurplePlaylistsStore.Current delegate) { this.delegate = delegate; }
        @Keep
        public boolean current() { return delegate.current(); }
    }
    @Keep
    private static final class NativeSnapshot {
        final long token;
        final byte[] bytes;
        @Keep
        private NativeSnapshot(long token, byte[] bytes) { this.token = token; this.bytes = bytes; }
    }
    private static final class Token {
        final PurplePlaylistsPhysical owner;
        final PurplePlaylistsStore.Name name;
        final long value;
        final byte[] bytes;
        Token(PurplePlaylistsPhysical owner, PurplePlaylistsStore.Name name, long value, byte[] bytes) {
            this.owner = owner; this.name = name; this.value = value; this.bytes = bytes;
        }
    }
    @Keep
    static final class Failure extends IOException {
        final boolean mayHaveChanged;
        @Keep
        private Failure(String status, boolean mayHaveChanged) {
            super(status); this.mayHaveChanged = mayHaveChanged;
        }
    }

    private final Thread thread = Thread.currentThread();
    private final PurplePlaylistsStore.Current route;
    private final java.io.File directory;
    private long handle;
    private int active;
    private boolean writing;

    PurplePlaylistsPhysical(String anchor, String relativeDirectory, PurplePlaylistsStore.Current route)
            throws IOException {
        this(anchor, relativeDirectory, route, false);
    }

    PurplePlaylistsPhysical(String anchor, String relativeDirectory, PurplePlaylistsStore.Current route,
            boolean createParents) throws IOException {
        if (anchor == null || relativeDirectory == null || route == null || !route.current())
            throw new IOException("InvalidRoute");
        this.route = route;
        directory = new java.io.File(anchor, relativeDirectory);
        PurpleCore.ensureLoaded();
        handle = openNative(anchor, relativeDirectory, new Guard(route), createParents);
        if (handle == 0) throw new IOException("OpenFailed");
    }

    private void enter() throws IOException {
        if (Thread.currentThread() != thread) throw new IOException("WrongThread");
        if (handle == 0) throw new IOException("Closed");
        ++active;
    }
    private void leave() { --active; }
    private long token(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File file) throws IOException {
        if (name == null || file == null || !(file.token instanceof Token)) throw new IOException("Changed");
        Token token = (Token) file.token;
        if (token.owner != this || token.name != name || token.value <= 0 || !file.content(token.bytes))
            throw new IOException("Changed");
        return token.value;
    }
    private PurplePlaylistsStore.File file(PurplePlaylistsStore.Name name, NativeSnapshot value) throws IOException {
        if (value == null || value.token <= 0) throw new IOException("MissingSnapshot");
        return new PurplePlaylistsStore.File(new Token(this, name, value.token, value.bytes), value.bytes);
    }

    @Override public boolean current() {
        return Thread.currentThread() == thread && handle != 0 && route.current() && currentNative(handle);
    }

    @Override public PurplePlaylistsStore.File read(PurplePlaylistsStore.Name name) throws IOException {
        enter();
        try {
            if (name == null || !route.current()) throw new IOException("Changed");
            return file(name, readNative(handle, name.ordinal()));
        } finally { leave(); }
    }

    @Override public boolean matches(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File expected)
            throws IOException {
        enter();
        try {
            long identity = token(name, expected);
            return route.current() && matchesNative(handle, name.ordinal(), identity);
        }
        finally { leave(); }
    }

    private PurplePlaylistsStore.Publication publish(PurplePlaylistsStore.Name name,
            PurplePlaylistsStore.File expected, byte[] bytes, PurplePlaylistsStore.Current current,
            boolean confirmation) throws IOException {
        enter();
        try {
            if (writing) throw new IOException("Busy");
            writing = true;
            try {
                if (current == null || !route.current() || !current.current()) throw new IOException("Changed");
                long identity = token(name, expected);
                if (!confirmation && bytes == null) throw new IOException("InvalidWrite");
                Guard guard = new Guard(current);
                NativeSnapshot value = confirmation ? confirmNative(handle, name.ordinal(), identity, guard)
                        : replaceNative(handle, name.ordinal(), identity, bytes, guard);
                PurplePlaylistsStore.File actual = file(name, value);
                if (!route.current() || !current.current() || !matches(name, actual))
                    throw new Failure("PublicationNotConfirmed", !confirmation);
                return new PurplePlaylistsStore.Publication(true, actual);
            } finally { writing = false; }
        } finally { leave(); }
    }

    @Override public PurplePlaylistsStore.Publication confirm(PurplePlaylistsStore.Name name,
            PurplePlaylistsStore.File expected, PurplePlaylistsStore.Current current) throws IOException {
        return publish(name, expected, null, current, true);
    }

    @Override public PurplePlaylistsStore.Publication replace(PurplePlaylistsStore.Name name,
            PurplePlaylistsStore.File expected, byte[] bytes, PurplePlaylistsStore.Current current) throws IOException {
        return publish(name, expected, bytes, current, false);
    }

    @Override public void close() throws IOException {
        if (Thread.currentThread() != thread) throw new IOException("WrongThread");
        if (active != 0 || writing) throw new IOException("Busy");
        if (handle == 0) return;
        long value = handle;
        handle = 0;
        closeNative(value);
    }

    @Keep
    static final class MediaFile {
        final String identity;
        final long size;
        final String sha256;
        @Keep
        MediaFile(String identity, long size, String sha256) {
            if (identity == null || identity.length() > 64 || !identity.matches("[0-9]+:[0-9]+")
                    || size < 0 || sha256 == null || !(sha256.isEmpty() || sha256.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("InvalidProof");
            this.identity = identity; this.size = size; this.sha256 = sha256;
        }
    }
    private interface MediaAction<T> { T run() throws IOException; }

    private <T> T media(PurplePlaylistsStore.Current current, MediaAction<T> action) throws IOException {
        enter();
        try {
            if (writing) throw new IOException("Busy");
            writing = true;
            try {
                if (current != null && (!route.current() || !current.current())) throw new IOException("Changed");
                return action.run();
            } finally { writing = false; }
        } finally { leave(); }
    }
    private Guard mediaGuard(PurplePlaylistsStore.Current current) throws IOException {
        if (current == null) throw new IOException("InvalidGuard");
        return new Guard(current);
    }

    java.io.File mediaDirectory() throws IOException {
        enter();
        try {
            if (!route.current()) throw new IOException("Changed");
            return new java.io.File(directory, "files");
        } finally { leave(); }
    }

    MediaFile beginMedia(String token, long size, String identity, PurplePlaylistsStore.Current current)
            throws IOException {
        Guard guard = mediaGuard(current);
        return media(current, () -> beginMediaNative(handle, token, size, identity, guard));
    }
    long appendMedia(String token, String identity, long offset, byte[] bytes,
            PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        return media(current, () -> appendMediaNative(handle, token, identity, offset, bytes, guard));
    }
    long appendMediaOverlap(String token, String identity, long offset, byte[] bytes,
            PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        return media(current, () -> appendMediaOverlapNative(handle, token, identity, offset, bytes, guard));
    }
    private static native long appendMediaOverlapNative(long handle, String token, String identity, long offset,
            byte[] bytes, Guard current) throws IOException;

    MediaFile sealMedia(String token, String identity, PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        return media(current, () -> sealMediaNative(handle, token, identity, guard));
    }
    MediaFile publishMedia(String token, long document, String extension, MediaFile expected,
            PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        return media(current, () -> {
            if (expected == null) throw new IOException("InvalidProof");
            return publishMediaNative(handle, token, document, extension, expected.identity,
                    expected.size, expected.sha256, guard);
        });
    }
    MediaFile inspectMedia(long document, String extension, MediaFile expected,
            PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        return media(current, () -> {
            if (expected == null) throw new IOException("InvalidProof");
            return inspectMediaNative(handle, document, extension, expected.identity,
                    expected.size, expected.sha256, guard);
        });
    }
    void confirmMediaAbsent(long document, String extension, PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        media(current, () -> { confirmMediaAbsentNative(handle, document, extension, guard); return null; });
    }
    private static native void confirmMediaAbsentNative(long handle, long document, String extension, Guard current) throws IOException;

    void removeMedia(long document, String extension, MediaFile expected,
            PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        media(current, () -> {
            if (expected == null) throw new IOException("InvalidProof");
            removeMediaNative(handle, document, extension, expected.identity, expected.size, expected.sha256, guard);
            return null;
        });
    }
    void confirmMediaPartAbsent(String token, PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        media(current, () -> { confirmMediaPartAbsentNative(handle, token, guard); return null; });
    }
    private static native void confirmMediaPartAbsentNative(long handle, String token, Guard current) throws IOException;

    void discardMedia(String token, String identity, PurplePlaylistsStore.Current current) throws IOException {
        Guard guard = mediaGuard(current);
        media(current, () -> { discardMediaNative(handle, token, identity, guard); return null; });
    }
    void closeMedia(String token) throws IOException {
        media(null, () -> { closeMediaNative(handle, token); return null; });
    }

    private static native MediaFile beginMediaNative(long handle, String token, long size, String identity, Guard current)
            throws IOException;
    private static native long appendMediaNative(long handle, String token, String identity, long offset,
            byte[] bytes, Guard current) throws IOException;
    private static native MediaFile sealMediaNative(long handle, String token, String identity, Guard current)
            throws IOException;
    private static native MediaFile publishMediaNative(long handle, String token, long document, String extension,
            String identity, long size, String sha256, Guard current) throws IOException;
    private static native MediaFile inspectMediaNative(long handle, long document, String extension,
            String identity, long size, String sha256, Guard current) throws IOException;
    private static native void removeMediaNative(long handle, long document, String extension,
            String identity, long size, String sha256, Guard current) throws IOException;
    private static native void discardMediaNative(long handle, String token, String identity, Guard current)
            throws IOException;
    private static native void closeMediaNative(long handle, String token) throws IOException;

    private static native long openNative(String anchor, String relativeDirectory, Guard current, boolean createParents)
            throws IOException;
    private static native boolean currentNative(long handle);
    private static native NativeSnapshot readNative(long handle, int name) throws IOException;
    private static native boolean matchesNative(long handle, int name, long token) throws IOException;
    private static native NativeSnapshot confirmNative(long handle, int name, long token, Guard current)
            throws IOException;
    private static native NativeSnapshot replaceNative(long handle, int name, long token, byte[] bytes,
            Guard current) throws IOException;
    private static native void closeNative(long handle) throws IOException;
}
