package org.telegram.messenger.purple;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

final class PurplePlaylistsStore implements AutoCloseable {
    enum Status {
        Ready, WrongThread, Busy, Closed, Changed, IoError,
        InvalidLibrary, NewerLibrary, InvalidState, NewerState, InvalidWrite,
        BackupFailed, LibraryWriteFailed, StateWriteFailed, EditCheckpointFailed
    }
    enum Name { Library, Local, Backup, SyncPublication, EditCheckpoint }
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
        private final File editCheckpoint;
        private final byte[] library;
        private final byte[] state;
        private Read(Status status, String detail, File libraryFile, File stateFile,
                File editCheckpoint, byte[] library, byte[] state) {
            this.status = status;
            this.detail = detail;
            this.libraryFile = libraryFile;
            this.stateFile = stateFile;
            this.editCheckpoint = editCheckpoint;
            this.library = library == null ? null : library.clone();
            this.state = state == null ? null : state.clone();
        }
        boolean ready() { return status == Status.Ready; }
        boolean displayable() { return library != null; }
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

    private static final byte[] NO_EDIT_CHECKPOINT = new byte[0];

    private static final class EditCheckpoint {
        private static final int FORMAT_1 = 0x504c4531;
        private static final int DIGEST_BYTES = 32;
        private static final int BYTES = Integer.BYTES + 2 * Long.BYTES + 6 * DIGEST_BYTES;
        final long earliest;
        final byte[] priorLibrary, nextLibrary, priorState, nextState, recoveredState;

        private EditCheckpoint(ByteBuffer data) {
            earliest = data.getLong();
            priorLibrary = digestFrom(data);
            nextLibrary = digestFrom(data);
            priorState = digestFrom(data);
            nextState = digestFrom(data);
            recoveredState = digestFrom(data);
        }

        static EditCheckpoint read(byte[] bytes, long self) {
            if (bytes.length != BYTES) return null;
            ByteBuffer data = ByteBuffer.wrap(bytes);
            if (data.getInt() != FORMAT_1 || data.getLong() != self
                    || !Arrays.equals(hash(Arrays.copyOf(bytes, BYTES - DIGEST_BYTES)),
                            Arrays.copyOfRange(bytes, BYTES - DIGEST_BYTES, BYTES))) return null;
            EditCheckpoint checkpoint = new EditCheckpoint(data);
            return checkpoint.earliest > 0 && !Arrays.equals(checkpoint.priorLibrary, checkpoint.nextLibrary)
                    ? checkpoint : null;
        }

        static byte[] prepare(Read before, byte[] library, byte[] local, long self) throws JSONException {
            long earliest = new JSONObject(new String(local, StandardCharsets.UTF_8)).getLong("unsynced_since");
            PurplePlaylistsLocalCore.Result recovered = PurplePlaylistsLocalCore.recoverEdit(before.state, earliest, self);
            if (!recovered.valid()) return null;
            ByteBuffer record = ByteBuffer.allocate(BYTES);
            record.putInt(FORMAT_1).putLong(self).putLong(earliest);
            record.put(fileDigest(before.libraryFile.bytes)).put(fileDigest(library));
            record.put(fileDigest(before.stateFile.bytes)).put(fileDigest(local));
            record.put(fileDigest(recovered.canonicalBytes()));
            record.put(hash(Arrays.copyOf(record.array(), record.position())));
            return record.array();
        }

        private static byte[] digestFrom(ByteBuffer data) {
            byte[] value = new byte[DIGEST_BYTES];
            data.get(value);
            return value;
        }

        private static MessageDigest sha256() {
            try { return MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        }

        private static byte[] hash(byte[] bytes) {
            return sha256().digest(bytes);
        }

        static byte[] fileDigest(byte[] bytes) {
            MessageDigest hash = sha256();
            hash.update((byte) (bytes == null ? 0 : 1));
            return bytes == null ? hash.digest() : hash.digest(bytes);
        }
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
        return expected != null && expected.ready() && observed(expected);
    }
    private boolean observed(Read expected) {
        return current() && matches(Name.EditCheckpoint, expected.editCheckpoint)
                && matches(Name.Library, expected.libraryFile) && matches(Name.Local, expected.stateFile)
                && current();
    }
    private static Read failure(Status status, String detail) {
        return new Read(status, detail, null, null, null, null, null);
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
            File editCheckpoint = physical.read(Name.EditCheckpoint);
            if (libraryFile == null || stateFile == null || editCheckpoint == null)
                return failure(Status.IoError, "MissingSnapshot");
            PurplePlaylistsCore.Result library = PurplePlaylistsCore.canonicalize(
                    libraryFile.absent() ? EMPTY_LIBRARY : libraryFile.bytes(), selfUserId);
            if (!library.valid()) return failure(library.status == PurplePlaylistsCore.Status.NewerFormat
                    ? Status.NewerLibrary : Status.InvalidLibrary, library.error);
            PurplePlaylistsLocalCore.Result state = stateFile.absent()
                    ? PurplePlaylistsLocalCore.defaults(selfUserId)
                    : PurplePlaylistsLocalCore.canonicalize(stateFile.bytes(), selfUserId);
            if (!state.valid()) return failure(state.status == PurplePlaylistsLocalCore.Status.NewerFormat
                    ? Status.NewerState : Status.InvalidState, state.error);
            Read result = new Read(Status.Ready, "None", libraryFile, stateFile, editCheckpoint,
                    library.canonicalBytes(), state.canonicalBytes());
            if (!bound(result)) return failure(Status.Changed, "ReadChanged");
            if (!editCheckpoint.absent() && !editCheckpoint.content(NO_EDIT_CHECKPOINT)) {
                result = recoverEdit(result);
                if (!result.ready()) return result;
            }
            last = result;
            return result;
        } catch (Exception e) {
            return failure(Status.IoError, "ReadFailed");
        } finally {
            busy = false;
        }
    }

    private Read pendingEdit(Read before, File stateFile, byte[] state) {
        return new Read(Status.EditCheckpointFailed, "EditCheckpointPending", before.libraryFile,
                stateFile, before.editCheckpoint, before.library, state);
    }

    private Read recoveryFailure(Read pending, String detail) {
        return pending != null && observed(pending) ? pending : failure(Status.EditCheckpointFailed, detail);
    }

    private Read recoverEdit(Read before) {
        Read pending = null;
        try {
            EditCheckpoint record = EditCheckpoint.read(before.editCheckpoint.bytes, selfUserId);
            if (record == null) return failure(Status.EditCheckpointFailed, "InvalidEditCheckpoint");
            byte[] libraryDigest = EditCheckpoint.fileDigest(before.libraryFile.bytes);
            Current initial = () -> bound(before);
            boolean priorLibrary = Arrays.equals(libraryDigest, record.priorLibrary);
            if (!priorLibrary && !Arrays.equals(libraryDigest, record.nextLibrary))
                return failure(Status.EditCheckpointFailed, "EditCheckpointLibraryChanged");
            if (!before.libraryFile.absent()) {
                Publication library = physical.confirm(Name.Library, before.libraryFile, initial);
                if (!confirmed(Name.Library, library, before.libraryFile.bytes, initial))
                    return failure(Status.EditCheckpointFailed, "EditCheckpointLibraryUnconfirmed");
            }
            File localFile = before.stateFile;
            byte[] local = before.state;
            if (!priorLibrary) {
                if (before.libraryFile.absent())
                    return failure(Status.EditCheckpointFailed, "EditCheckpointLibraryAbsent");
                byte[] actual = EditCheckpoint.fileDigest(localFile.bytes);
                if (Arrays.equals(actual, record.nextState) || Arrays.equals(actual, record.recoveredState)) {
                    pending = pendingEdit(before, localFile, local);
                    Publication state = physical.confirm(Name.Local, localFile, initial);
                    if (!confirmed(Name.Local, state, localFile.bytes, initial))
                        return recoveryFailure(pending, "EditCheckpointStateUnconfirmed");
                } else {
                    if (!Arrays.equals(actual, record.priorState))
                        return failure(Status.EditCheckpointFailed, "EditCheckpointStateChanged");
                    PurplePlaylistsLocalCore.Result recovered = PurplePlaylistsLocalCore.recoverEdit(local, record.earliest, selfUserId);
                    if (!recovered.valid())
                        return failure(Status.EditCheckpointFailed, "EditCheckpointInvalidLocalState");
                    local = recovered.canonicalBytes();
                    if (!Arrays.equals(EditCheckpoint.fileDigest(local), record.recoveredState))
                        return failure(Status.EditCheckpointFailed, "EditCheckpointUnexpectedRecovery");
                    pending = pendingEdit(before, localFile, local);
                    Current guard = () -> current() && matches(Name.Library, before.libraryFile)
                            && matches(Name.EditCheckpoint, before.editCheckpoint);
                    Publication state = physical.replace(Name.Local, localFile, local, guard);
                    if (!confirmed(Name.Local, state, local, guard))
                        return recoveryFailure(pending, "EditCheckpointStateUnconfirmed");
                    localFile = state.file;
                    pending = pendingEdit(before, localFile, local);
                }
            }
            File completeLocal = localFile;
            Current guard = () -> current() && matches(Name.Library, before.libraryFile)
                    && matches(Name.Local, completeLocal);
            Publication retired = physical.replace(Name.EditCheckpoint, before.editCheckpoint, NO_EDIT_CHECKPOINT, guard);
            if (!confirmed(Name.EditCheckpoint, retired, NO_EDIT_CHECKPOINT, guard))
                return recoveryFailure(pending, "EditCheckpointRetirementUnconfirmed");
            Read result = new Read(Status.Ready, "None", before.libraryFile, localFile, retired.file,
                    before.library, local);
            return bound(result) ? result : failure(Status.Changed, "ReadChanged");
        } catch (Exception e) {
            return recoveryFailure(pending, "EditCheckpointRecoveryFailed");
        }
    }

    Read revalidate(Read expected) {
        if (unavailable() != null || expected == null || !expected.displayable()) return null;
        if (expected.ready()) {
            if (!bound(expected)) return null;
            if (expected == last) return expected;
            Read current = read();
            return current.ready() && bound(expected) ? current : null;
        }
        if (!current() || !matches(Name.Library, expected.libraryFile)) return null;
        EditCheckpoint record = EditCheckpoint.read(expected.editCheckpoint.bytes, selfUserId);
        if (record == null) return null;
        Read latest = read();
        if (!latest.displayable() || !matches(Name.Library, expected.libraryFile)) return null;
        if (!latest.ready()) {
            return latest.editCheckpoint.content(expected.editCheckpoint.bytes) ? latest : null;
        }
        byte[] actual = EditCheckpoint.fileDigest(latest.stateFile.bytes);
        return Arrays.equals(actual, record.recoveredState) || Arrays.equals(actual, record.nextState)
                ? latest : null;
    }

    private boolean confirmed(Name name, Publication publication, byte[] bytes, Current guard) {
        return publication != null && publication.durable && publication.file != null
                && publication.file.content(bytes) && matches(name, publication.file) && guard.current();
    }

    Write write(Read expected, byte[] libraryBytes, byte[] stateBytes, Kind kind) {
        return write(expected, libraryBytes, stateBytes, kind, () -> true);
    }
    Write write(Read expected, byte[] libraryBytes, byte[] stateBytes, Kind kind, Current scope) {
        return write(expected, libraryBytes, stateBytes, kind, scope, false);
    }

    Write writeEdit(Read expected, PurplePlaylistsEdits.Result edit) {
        return write(expected, edit.library(), edit.state(), Kind.LocalEdit, () -> true, edit.changed);
    }

    private Write write(Read expected, byte[] libraryBytes, byte[] stateBytes, Kind kind,
            Current scope, boolean recoverableEdit) {
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
            Current original = () -> bound(expected) && scope.current();
            if (!original.current()) { result.status = Status.Changed; return result; }
            boolean writeLibrary = !expected.libraryFile.content(encoded);
            boolean prepareCheckpoint = recoverableEdit && writeLibrary && !expected.stateFile.content(local);
            File recordFile = expected.editCheckpoint;
            if (prepareCheckpoint) {
                result.status = Status.EditCheckpointFailed;
                byte[] record = EditCheckpoint.prepare(expected, encoded, local, selfUserId);
                if (record == null) return result;
                Current guard = () -> scope.current() && current()
                        && matches(Name.Library, expected.libraryFile) && matches(Name.Local, expected.stateFile);
                Publication prepared = physical.replace(Name.EditCheckpoint, recordFile, record, guard);
                if (!confirmed(Name.EditCheckpoint, prepared, record, guard)) return result;
                recordFile = prepared.file;
            }
            File checkpointRecord = recordFile;
            Current recordCurrent = () -> current() && scope.current() && matches(Name.EditCheckpoint, checkpointRecord);
            Current initial = () -> recordCurrent.current()
                    && matches(Name.Library, expected.libraryFile) && matches(Name.Local, expected.stateFile);
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
                Current afterLibrary = () -> recordCurrent.current() && matches(Name.Local, expected.stateFile);
                Publication saved = physical.replace(Name.Library, expected.libraryFile, encoded, afterLibrary);
                if (!confirmed(Name.Library, saved, encoded, afterLibrary)) return result;
                result.libraryCommitted = true;
            }
            result.status = Status.LibraryWriteFailed;
            File observed = physical.read(Name.Library);
            Current libraryGuard = () -> recordCurrent.current() && matches(Name.Local, expected.stateFile) && current();
            if (observed == null || observed.absent() || !observed.content(encoded) || !libraryGuard.current())
                return result;
            Publication durable = physical.confirm(Name.Library, observed, libraryGuard);
            if (!confirmed(Name.Library, durable, encoded, libraryGuard)) return result;
            File checkpoint = durable.file;
            result.status = Status.StateWriteFailed;
            Current stateGuard = () -> libraryGuard.current() && matches(Name.Library, checkpoint);
            if (!stateGuard.current()) return result;
            File finalState = expected.stateFile;
            if (!expected.stateFile.content(local)) {
                result.stateMayHaveChanged = true;
                Current afterState = () -> recordCurrent.current() && matches(Name.Library, checkpoint) && current();
                Publication saved = physical.replace(Name.Local, expected.stateFile, local, afterState);
                if (!confirmed(Name.Local, saved, local, afterState)) return result;
                result.stateCommitted = true;
                finalState = saved.file;
            } else if (!stateGuard.current()) return result;
            if (prepareCheckpoint) {
                result.status = Status.EditCheckpointFailed;
                File completeLocal = finalState;
                Current guard = () -> current() && scope.current()
                        && matches(Name.Library, checkpoint) && matches(Name.Local, completeLocal);
                Publication retired = physical.replace(Name.EditCheckpoint, checkpointRecord, NO_EDIT_CHECKPOINT, guard);
                if (!confirmed(Name.EditCheckpoint, retired, NO_EDIT_CHECKPOINT, guard)) return result;
            }
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
            Current afterState = () -> current() && matches(Name.EditCheckpoint, expected.editCheckpoint)
                    && matches(Name.Library, expected.libraryFile) && current();
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
