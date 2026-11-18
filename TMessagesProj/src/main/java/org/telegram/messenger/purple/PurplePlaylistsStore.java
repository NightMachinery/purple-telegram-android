package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PurplePlaylistsStore implements AutoCloseable {
    enum Status {
        Ready, WrongThread, Busy, Closed, Changed, IoError,
        InvalidLibrary, NewerLibrary, InvalidState, NewerState, InvalidWrite,
        BackupFailed, LibraryWriteFailed, StateWriteFailed
    }
    enum Name { Library, Local, Backup, SyncPublication }
    enum Kind { LocalEdit, Merge }
    interface Current { boolean current(); }
    interface Physical extends AutoCloseable {
        boolean current();
        File read(Name name) throws Exception;
        boolean matches(Name name, File expected) throws Exception;
        Publication confirm(Name name, File expected, Current current) throws Exception;
        Publication replace(Name name, File expected, byte[] bytes, Current current) throws Exception;
        void close() throws Exception;
    }
    static final class File {
        final Object token;
        private final byte[] bytes;
        File(Object token, byte[] bytes) {
            if (token == null) throw new IllegalArgumentException("Missing file identity");
            this.token = token;
            this.bytes = bytes == null ? null : bytes.clone();
        }
        boolean absent() { return bytes == null; }
        byte[] bytes() { return bytes == null ? null : bytes.clone(); }
        boolean content(byte[] value) { return Arrays.equals(bytes, value); }
    }
    static final class Publication {
        final boolean durable;
        final File file;
        Publication(boolean durable, File file) { this.durable = durable; this.file = file; }
    }
    static final class Read {
        final Status status;
        final String detail;
        private final File libraryFile;
        private final File stateFile;
        private final byte[] library;
        private final byte[] state;
        private Read(Status status, String detail, File libraryFile, File stateFile, byte[] library, byte[] state) {
            this.status = status;
            this.detail = detail;
            this.libraryFile = libraryFile;
            this.stateFile = stateFile;
            this.library = library == null ? null : library.clone();
            this.state = state == null ? null : state.clone();
        }
        boolean ready() { return status == Status.Ready; }
        byte[] library() { return library == null ? null : library.clone(); }
        byte[] state() { return state == null ? null : state.clone(); }
    }
    static final class Write {
        Status status = Status.InvalidWrite;
        boolean libraryCommitted;
        boolean stateCommitted;
        boolean libraryMayHaveChanged;
        boolean stateMayHaveChanged;
        boolean ready() { return status == Status.Ready; }
    }

    static final class Durable {
        private final PurplePlaylistsStore owner;
        private final Read read;
        private final Current scope;
        private Durable(PurplePlaylistsStore owner, Read read, Current scope) {
            this.owner = owner; this.read = read; this.scope = scope;
        }
        boolean current() { return owner.stillCurrent(read) && scope.current(); }
        byte[] library() { return current() ? read.library() : null; }
    }
    Durable confirm(Read expected, Current scope) {
        if (unavailable() != null || scope == null || expected != last || !bound(expected)) return null;
        busy = true;
        try {
            Current guard = () -> bound(expected) && scope.current();
            if (!guard.current() || expected.libraryFile.absent() || expected.stateFile.absent()) return null;
            Publication library = physical.confirm(Name.Library, expected.libraryFile, guard);
            if (!confirmed(Name.Library, library, expected.libraryFile.bytes(), guard)) return null;
            Publication state = physical.confirm(Name.Local, expected.stateFile, guard);
            if (!confirmed(Name.Local, state, expected.stateFile.bytes(), guard)) return null;
            return guard.current() ? new Durable(this, expected, scope) : null;
        } catch (Exception error) { return null; }
        finally { busy = false; }
    }

    private static final byte[] EMPTY_LIBRARY =
            "{\"format\":1,\"playlists\":{},\"purged\":{}}".getBytes(StandardCharsets.UTF_8);
    private final Thread thread = Thread.currentThread();
    private final Physical physical;
    private final long selfUserId;
    private Read last;
    private boolean busy;
    private boolean closed;

    PurplePlaylistsStore(long selfUserId, Physical physical) {
        if (selfUserId <= 0 || physical == null) throw new IllegalArgumentException("Invalid account store");
        this.selfUserId = selfUserId;
        this.physical = physical;
    }

    private Status unavailable() {
        if (Thread.currentThread() != thread) return Status.WrongThread;
        if (closed) return Status.Closed;
        return busy ? Status.Busy : null;
    }
    private boolean current() {
        try { return !closed && physical.current(); }
        catch (RuntimeException e) { return false; }
    }
    private boolean matches(Name name, File file) {
        try { return file != null && physical.matches(name, file); }
        catch (Exception e) { return false; }
    }
    private boolean bound(Read expected) {
        return expected != null && expected.ready() && current()
                && matches(Name.Library, expected.libraryFile) && matches(Name.Local, expected.stateFile)
                && current();
    }
    private static Read failure(Status status, String detail) {
        return new Read(status, detail, null, null, null, null);
    }

    Read read() {
        Status blocked = unavailable();
        if (blocked != null) return failure(blocked, "StoreUnavailable");
        last = null;
        if (!current()) return failure(Status.Changed, "RouteChanged");
        busy = true;
        try {
            File libraryFile = physical.read(Name.Library);
            File stateFile = physical.read(Name.Local);
            if (libraryFile == null || stateFile == null) return failure(Status.IoError, "MissingSnapshot");
            PurplePlaylistsCore.Result library = PurplePlaylistsCore.canonicalize(
                    libraryFile.absent() ? EMPTY_LIBRARY : libraryFile.bytes(), selfUserId);
            if (!library.valid()) return failure(library.status == PurplePlaylistsCore.Status.NewerFormat
                    ? Status.NewerLibrary : Status.InvalidLibrary, library.error);
            PurplePlaylistsLocalCore.Result state = stateFile.absent()
                    ? PurplePlaylistsLocalCore.defaults(selfUserId)
                    : PurplePlaylistsLocalCore.canonicalize(stateFile.bytes(), selfUserId);
            if (!state.valid()) return failure(state.status == PurplePlaylistsLocalCore.Status.NewerFormat
                    ? Status.NewerState : Status.InvalidState, state.error);
            Read result = new Read(Status.Ready, "None", libraryFile, stateFile,
                    library.canonicalBytes(), state.canonicalBytes());
            if (!bound(result)) return failure(Status.Changed, "ReadChanged");
            last = result;
            return result;
        } catch (Exception e) {
            return failure(Status.IoError, "ReadFailed");
        } finally {
            busy = false;
        }
    }

    Read revalidate(Read expected) {
        if (unavailable() != null || !bound(expected)) return null;
        if (expected == last) return expected;
        Read current = read();
        return current.ready() && bound(expected) ? current : null;
    }

    private boolean confirmed(Name name, Publication publication, byte[] bytes, Current guard) {
        return publication != null && publication.durable && publication.file != null
                && publication.file.content(bytes) && matches(name, publication.file) && guard.current();
    }

    Write write(Read expected, byte[] libraryBytes, byte[] stateBytes, Kind kind) {
        return write(expected, libraryBytes, stateBytes, kind, () -> true);
    }
    Write write(Read expected, byte[] libraryBytes, byte[] stateBytes, Kind kind, Current scope) {
        Write result = new Write();
        Status blocked = unavailable();
        if (blocked != null) { result.status = blocked; return result; }
        if (expected != last || !bound(expected)) { result.status = Status.Changed; return result; }
        if (kind == null || scope == null || !scope.current()) return result;
        PurplePlaylistsCore.Result library = PurplePlaylistsCore.canonicalize(libraryBytes, selfUserId);
        PurplePlaylistsLocalCore.Result state = PurplePlaylistsLocalCore.canonicalize(stateBytes, selfUserId);
        if (!library.valid() || !state.valid()) return result;
        byte[] encoded = library.canonicalBytes(), local = state.canonicalBytes();
        busy = true;
        last = null;
        result.status = Status.Ready;
        try {
            Current initial = () -> bound(expected) && scope.current();
            if (!initial.current()) { result.status = Status.Changed; return result; }
            boolean writeLibrary = !expected.libraryFile.content(encoded);
            if (writeLibrary && !expected.libraryFile.absent() && kind == Kind.Merge) {
                result.status = Status.BackupFailed;
                File backup = physical.read(Name.Backup);
                if (backup == null || !initial.current()) return result;
                Publication saved = physical.replace(Name.Backup, backup, expected.libraryFile.bytes(), initial);
                if (!confirmed(Name.Backup, saved, expected.libraryFile.bytes(), initial)) return result;
            }
            if (writeLibrary) {
                result.status = Status.LibraryWriteFailed;
                if (!initial.current()) return result;
                result.libraryMayHaveChanged = true;
                Current afterLibrary = () -> current() && scope.current() && matches(Name.Local, expected.stateFile);
                Publication saved = physical.replace(Name.Library, expected.libraryFile, encoded, afterLibrary);
                if (!confirmed(Name.Library, saved, encoded, afterLibrary)) return result;
                result.libraryCommitted = true;
            }
            result.status = Status.LibraryWriteFailed;
            File observed = physical.read(Name.Library);
            Current libraryGuard = () -> current() && scope.current() && matches(Name.Local, expected.stateFile) && current();
            if (observed == null || observed.absent() || !observed.content(encoded) || !libraryGuard.current())
                return result;
            Publication durable = physical.confirm(Name.Library, observed, libraryGuard);
            if (!confirmed(Name.Library, durable, encoded, libraryGuard)) return result;
            File checkpoint = durable.file;
            result.status = Status.StateWriteFailed;
            Current stateGuard = () -> current() && scope.current() && matches(Name.Library, checkpoint)
                    && matches(Name.Local, expected.stateFile) && current();
            if (!stateGuard.current()) return result;
            if (!expected.stateFile.content(local)) {
                result.stateMayHaveChanged = true;
                Current afterState = () -> current() && scope.current() && matches(Name.Library, checkpoint) && current();
                Publication saved = physical.replace(Name.Local, expected.stateFile, local, afterState);
                if (!confirmed(Name.Local, saved, local, afterState)) return result;
                result.stateCommitted = true;
            } else if (!stateGuard.current()) return result;
            result.status = Status.Ready;
            return result;
        } catch (Exception e) {
            if (result.status == Status.Ready) result.status = Status.IoError;
            return result;
        } finally {
            busy = false;
        }
    }

    Write setSync(Read expected, boolean enabled) {
        Write result = new Write();
        Status blocked = unavailable();
        if (blocked != null) { result.status = blocked; return result; }
        if (expected != last || !bound(expected)) { result.status = Status.Changed; return result; }
        PurplePlaylistsLocalCore.Result state = PurplePlaylistsLocalCore.setSync(expected.state(), enabled, selfUserId);
        if (!state.valid()) return result;
        byte[] local = state.canonicalBytes();
        busy = true;
        last = null;
        try {
            Current guard = () -> bound(expected);
            if (!guard.current()) { result.status = Status.Changed; return result; }
            result.status = Status.Ready;
            if (expected.stateFile.content(local)) return result;
            result.status = Status.StateWriteFailed;
            result.stateMayHaveChanged = true;
            Current afterState = () -> current() && matches(Name.Library, expected.libraryFile) && current();
            Publication saved = physical.replace(Name.Local, expected.stateFile, local, afterState);
            if (!confirmed(Name.Local, saved, local, afterState)) return result;
            result.stateCommitted = true;
            result.status = Status.Ready;
            return result;
        } catch (Exception e) {
            return result;
        } finally {
            busy = false;
        }
    }

    PurplePlaylistsPhysical media() throws java.io.IOException {
        if (unavailable() != null || !current() || !(physical instanceof PurplePlaylistsPhysical))
            throw new java.io.IOException("DownloadPhysicalUnavailable");
        return (PurplePlaylistsPhysical) physical;
    }
    boolean stillCurrent(Read expected) {
        return Thread.currentThread() == thread && !closed && bound(expected);
    }

    @Override public void close() throws Exception {
        if (Thread.currentThread() != thread || busy) throw new IllegalStateException("StoreUnavailable");
        if (closed) return;
        closed = true;
        last = null;
        physical.close();
    }
}
