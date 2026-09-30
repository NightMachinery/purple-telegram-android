/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import android.util.AtomicFile;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class PurpleAccountSyncStore implements AutoCloseable {
    private static final int STATE_LIMIT = 4 * 1024 * 1024;
    private static final int RECORD_LIMIT = 256 * 1024;
    private static final Set<String> OWNED_ROOTS = new HashSet<>();

    public enum Status {
        Disabled, Uninitialized, Ready, NoPending, InvalidTransition,
        InvalidState, NewerState, InvalidRecord, AccountUnbound,
        DeviceMismatch, CloneDetected, PendingMissing, PendingMismatch,
        OrphanStage, AmbiguousFiles, LockBusy, IoError, Unconfirmed,
        Unchanged
    }

    public static final class Result {
        public final Status status;
        public final byte[] staged;
        public final long seq;
        public final String detail;

        private Result(Status status, byte[] staged, long seq, String detail) {
            this.status = status;
            this.staged = staged;
            this.seq = seq;
            this.detail = detail;
        }
    }

    interface Core {
        Reply inspectState(byte[] state);
        Reply checkBinding(int account, byte[] state);
        Reply reserve(int account, byte[] state, byte[] record);
        Reply checkStage(byte[] state, byte[] record);
        Reply confirm(byte[] state, byte[] staged, String device,
                byte[] serverRecord, int messageId);
        Reply checkCommit(byte[] state, byte[] next);
    }

    static final class Reply {
        final String status;
        final String error;
        final String verdict;
        final String device;
        final long seq;
        final long pendingSeq;
        final boolean changed;
        final byte[] state;

        Reply(String status, String error, String verdict, String device,
                long seq, long pendingSeq, boolean changed, byte[] state) {
            this.status = status;
            this.error = error;
            this.verdict = verdict;
            this.device = device;
            this.seq = seq;
            this.pendingSeq = pendingSeq;
            this.changed = changed;
            this.state = state;
        }

        static Reply from(PurpleAccountSyncCore.Result value) {
            return new Reply(value.status, value.error, value.verdict,
                    value.device, value.seq, value.pendingSeq,
                    value.changed, value.state);
        }

        static Reply from(PurpleSyncCore.CommitCheck value) {
            return new Reply(value.isValid() ? "Valid" : "BridgeError",
                    value.isValid() ? "None" : value.error,
                    value.status.name(), "", 0, 0, false, value.state);
        }

        boolean valid() {
            return "Valid".equals(status);
        }
    }

    static final Core NATIVE = new Core() {
        @Override
        public Reply inspectState(byte[] state) {
            return Reply.from(PurpleAccountSyncCore.inspectState(state));
        }

        @Override
        public Reply checkBinding(int account, byte[] state) {
            return Reply.from(PurpleAccountSyncCore.checkAccountBinding(account, state));
        }

        @Override
        public Reply reserve(int account, byte[] state, byte[] record) {
            return Reply.from(PurpleAccountSyncCore.reserveConfigRecord(
                    account, state, record));
        }

        @Override
        public Reply checkStage(byte[] state, byte[] record) {
            return Reply.from(PurpleAccountSyncCore.checkLocalStage(state, record));
        }

        @Override
        public Reply confirm(byte[] state, byte[] staged, String device,
                byte[] serverRecord, int messageId) {
            return Reply.from(PurpleAccountSyncCore.confirmConfigReadBack(
                    state, staged, device,
                    PurpleAccountSyncCore.OBSERVATION_PRESENT,
                    serverRecord, messageId));
        }

        @Override
        public Reply checkCommit(byte[] state, byte[] next) {
            return Reply.from(PurpleSyncCore.checkCommit(state, next));
        }
    };

    private final File root;
    private final File pending;
    private final File stateFile;
    private final File stageFile;
    private final Core core;
    private int account;
    private String device;
    private String ownershipKey;
    private RandomAccessFile lockStream;
    private FileLock lock;
    private byte[] state;
    private Status status = Status.Disabled;
    private boolean opened;

    public PurpleAccountSyncStore() {
        this(defaultRoot(), NATIVE);
    }

    PurpleAccountSyncStore(File root, Core core) {
        this.root = root;
        this.pending = new File(root, "pending");
        this.stateFile = new File(root, "state.json");
        this.stageFile = new File(pending, "config.json");
        this.core = core;
    }

    public synchronized Result open(boolean optedIn, int currentAccount,
            String currentDevice) {
        if (opened) {
            return result(Status.InvalidTransition);
        }
        opened = true;
        if (!optedIn) {
            return result(Status.Disabled);
        }
        if (currentDevice == null || currentDevice.isEmpty()) {
            return fail(Status.DeviceMismatch);
        }
        account = currentAccount;
        device = currentDevice;
        try {
            final File parent = root.getParentFile();
            if (parent == null || !privateDirectory(parent, true)
                    || !privateDirectory(root, true)) {
                return fail(Status.IoError);
            }
            ownershipKey = root.getCanonicalPath();
            synchronized (OWNED_ROOTS) {
                if (!OWNED_ROOTS.add(ownershipKey)) {
                    ownershipKey = null;
                    return fail(Status.LockBusy);
                }
            }
            final File lockFile = new File(root, "lock");
            if (lockFile.exists() && (isLink(lockFile) || !lockFile.isFile())) {
                return fail(Status.AmbiguousFiles);
            }
            lockStream = new RandomAccessFile(lockFile, "rw");
            try {
                lock = lockStream.getChannel().tryLock();
            } catch (OverlappingFileLockException e) {
                return fail(Status.LockBusy);
            }
            if (lock == null) {
                return fail(Status.LockBusy);
            }
            if (!privateDirectory(pending, true)) {
                return fail(Status.IoError);
            }
            if (!knownFiles(root, "lock", "state.json", "pending")
                    || !knownFiles(pending, "config.json")) {
                return fail(Status.AmbiguousFiles);
            }
            if (hasAtomicArtifacts(stateFile) || hasAtomicArtifacts(stageFile)) {
                return fail(Status.AmbiguousFiles);
            }
            if (!stateFile.exists()) {
                return fail(stageFile.exists()
                        ? Status.OrphanStage : Status.Uninitialized);
            }
            state = read(stateFile, STATE_LIMIT);
            final Reply inspected = core.inspectState(state);
            if (!inspected.valid() || inspected.state == null
                    || !Arrays.equals(state, inspected.state)) {
                return fail("NewerVersion".equals(inspected.status)
                        ? Status.NewerState : Status.InvalidState);
            }
            if (!device.equals(inspected.device)) {
                return fail(Status.DeviceMismatch);
            }
            if (!bound()) {
                return fail(Status.AccountUnbound);
            }
            status = Status.Ready;
            if (stageFile.exists()) {
                final byte[] bytes = read(stageFile, RECORD_LIMIT);
                final Reply checked = core.checkStage(state, bytes);
                if (!checked.valid()) {
                    return fail(inspected.pendingSeq == 0
                            ? Status.OrphanStage : Status.PendingMismatch);
                }
                if ("Confirmed".equals(checked.verdict)) {
                    if (!stageFile.delete()) {
                        return fail(Status.IoError);
                    }
                } else if (!"Pending".equals(checked.verdict)) {
                    return fail(Status.PendingMismatch);
                }
            } else if (inspected.pendingSeq != 0) {
                return fail(Status.PendingMissing);
            }
            return result(Status.Ready);
        } catch (IOException | SecurityException e) {
            return fail(Status.IoError);
        }
    }

    public synchronized Result initialize(byte[] boundState) {
        if (status != Status.Uninitialized || stateFile.exists()
                || stageFile.exists() || boundState == null
                || boundState.length > STATE_LIMIT) {
            return result(Status.InvalidTransition);
        }
        final Reply inspected = core.inspectState(boundState);
        if (!inspected.valid() || inspected.state == null
                || !Arrays.equals(boundState, inspected.state)
                || inspected.seq != 0 || inspected.pendingSeq != 0) {
            return result(Status.InvalidState);
        }
        if (!device.equals(inspected.device)) {
            return result(Status.DeviceMismatch);
        }
        if (!bindingMatches(boundState)) {
            return result(Status.AccountUnbound);
        }
        try {
            write(stateFile, boundState);
            state = boundState.clone();
            status = Status.Ready;
            return result(Status.Ready);
        } catch (IOException e) {
            return fail(Status.IoError);
        }
    }

    public synchronized Result stageConfig(byte[] canonicalRecord,
            byte[] reservedState) {
        if (status != Status.Ready || canonicalRecord == null
                || reservedState == null || stageFile.exists()
                || canonicalRecord.length > RECORD_LIMIT
                || reservedState.length > STATE_LIMIT) {
            return result(Status.InvalidTransition);
        }
        if (!bound()) {
            return fail(Status.AccountUnbound);
        }
        final Reply current = core.inspectState(state);
        if (!current.valid() || current.pendingSeq != 0) {
            return result(Status.InvalidTransition);
        }
        final Reply reserved = core.reserve(account, state, canonicalRecord);
        if (!reserved.valid() || reserved.state == null
                || !Arrays.equals(reserved.state, reservedState)) {
            return result(Status.InvalidRecord);
        }
        final Reply inspected = core.inspectState(reservedState);
        final Reply checkedStage = core.checkStage(reservedState,
                canonicalRecord);
        if (!inspected.valid() || !device.equals(inspected.device)
                || inspected.pendingSeq == 0 || inspected.pendingSeq != reserved.seq
                || !Arrays.equals(inspected.state, reservedState)
                || !checkedStage.valid()
                || !"Pending".equals(checkedStage.verdict)) {
            return result(Status.InvalidRecord);
        }
        try {
            write(stageFile, canonicalRecord);
            write(stateFile, reservedState);
            state = reservedState.clone();
            if (!bound()) {
                return fail(Status.AccountUnbound);
            }
            return new Result(Status.Ready, canonicalRecord.clone(),
                    reserved.seq, "");
        } catch (IOException e) {
            return fail(Status.IoError);
        }
    }

    public synchronized Result readPendingConfig() {
        if (status != Status.Ready) {
            return result(status);
        }
        if (!bound()) {
            return fail(Status.AccountUnbound);
        }
        final Reply inspected = core.inspectState(state);
        if (!inspected.valid()) {
            return fail(Status.InvalidState);
        }
        if (inspected.pendingSeq == 0) {
            return stageFile.exists()
                    ? fail(Status.PendingMismatch) : result(Status.NoPending);
        }
        if (!stageFile.exists()) {
            return fail(Status.PendingMissing);
        }
        try {
            final byte[] bytes = read(stageFile, RECORD_LIMIT);
            final Reply checked = core.checkStage(state, bytes);
            return checked.valid() && "Pending".equals(checked.verdict)
                    ? new Result(Status.Ready, bytes, checked.seq, "")
                    : fail(Status.PendingMismatch);
        } catch (IOException e) {
            return fail(Status.IoError);
        }
    }

    public synchronized Result confirmConfigReadBack(byte[] serverRecord,
            int messageId) {
        final Result pendingResult = readPendingConfig();
        if (pendingResult.status != Status.Ready) {
            return pendingResult;
        }
        if (serverRecord == null || messageId <= 0
                || !Arrays.equals(pendingResult.staged, serverRecord)) {
            return result(Status.Unconfirmed);
        }
        final Reply confirmed = core.confirm(state, pendingResult.staged,
                device, serverRecord, messageId);
        if (!confirmed.valid()) {
            return result(Status.Unconfirmed);
        }
        if (!"NoClone".equals(confirmed.verdict)) {
            return fail(Status.CloneDetected);
        }
        if (!confirmed.changed || confirmed.state == null
                || confirmed.state.length > STATE_LIMIT
                || confirmed.pendingSeq != 0) {
            return result(Status.Unconfirmed);
        }
        final Reply inspected = core.inspectState(confirmed.state);
        final Reply checked = core.checkStage(confirmed.state,
                pendingResult.staged);
        if (!inspected.valid() || !device.equals(inspected.device)
                || !Arrays.equals(inspected.state, confirmed.state)
                || !checked.valid() || !"Confirmed".equals(checked.verdict)) {
            return fail(Status.InvalidState);
        }
        if (!bound()) {
            return fail(Status.AccountUnbound);
        }
        try {
            write(stateFile, confirmed.state);
            state = confirmed.state.clone();
            if (!stageFile.delete()) {
                return fail(Status.IoError);
            }
            return new Result(Status.Ready, null, checked.seq, "");
        } catch (IOException e) {
            return fail(Status.IoError);
        }
    }

    public synchronized Result commitConfigState(byte[] next) {
        if (status != Status.Ready || next == null
                || next.length > STATE_LIMIT) {
            return result(Status.InvalidTransition);
        }
        if (!bound()) {
            return fail(Status.AccountUnbound);
        }
        final Reply inspected = core.inspectState(next);
        if (!inspected.valid() || inspected.state == null
                || !Arrays.equals(next, inspected.state)) {
            return result(Status.InvalidState);
        }
        if (!device.equals(inspected.device)) {
            return result(Status.DeviceMismatch);
        }
        final Reply checked = core.checkCommit(state, next);
        if (!checked.valid()) {
            return result(Status.InvalidState);
        }
        if ("Unchanged".equals(checked.verdict)) {
            return result(Status.Unchanged);
        }
        if (!"Ready".equals(checked.verdict)) {
            return result("InvalidTransition".equals(checked.verdict)
                    ? Status.InvalidTransition : Status.InvalidState);
        }
        if (checked.state == null || !Arrays.equals(checked.state, next)) {
            return result(Status.InvalidState);
        }
        try {
            write(stateFile, next);
            state = next.clone();
            if (!bound()) {
                return fail(Status.AccountUnbound);
            }
            return result(Status.Ready);
        } catch (IOException e) {
            return fail(Status.IoError);
        }
    }

    public synchronized byte[] stateBytes() {
        return (status == Status.Ready && state != null) ? state.clone() : null;
    }

    public synchronized Status status() {
        return status;
    }

    public static boolean hasState() {
        return hasState(defaultRoot());
    }

    static boolean hasState(File root) {
        try {
            if (!root.exists()) {
                return false;
            }
            final File[] files = root.listFiles();
            if (files == null) {
                return true;
            }
            for (File file : files) {
                if (file.getName().equals("lock") && file.isFile()
                        && !isLink(file)) {
                    continue;
                }
                if (file.getName().equals("pending") && file.isDirectory()
                        && !isLink(file)) {
                    final String[] staged = file.list();
                    if (staged != null && staged.length == 0) {
                        continue;
                    }
                }
                return true;
            }
            return false;
        } catch (IOException | SecurityException e) {
            return true;
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
        }
        try {
            if (lockStream != null) {
                lockStream.close();
            }
        } catch (IOException ignored) {
        }
        synchronized (OWNED_ROOTS) {
            if (ownershipKey != null) {
                OWNED_ROOTS.remove(ownershipKey);
            }
        }
        lock = null;
        lockStream = null;
        ownershipKey = null;
        state = null;
        status = Status.Disabled;
        opened = false;
    }

    private static File defaultRoot() {
        return new File(new File(ApplicationLoader.getFilesDirFixed(),
                "purple"), "sync");
    }

    private boolean bound() {
        return state != null && bindingMatches(state);
    }

    private boolean bindingMatches(byte[] bytes) {
        final Reply checked = core.checkBinding(account, bytes);
        return checked.valid() && "Bound".equals(checked.verdict);
    }

    private Result fail(Status failure) {
        status = failure;
        return result(failure);
    }

    private static Result result(Status value) {
        return new Result(value, null, 0, "");
    }

    private static boolean privateDirectory(File dir, boolean create)
            throws IOException {
        if (!dir.exists() && (!create || !dir.mkdir())) {
            return false;
        }
        return dir.isDirectory()
                && !isLink(dir);
    }

    private static boolean knownFiles(File dir, String... expected)
            throws IOException {
        final File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (!Arrays.asList(expected).contains(file.getName())
                    || !!isLink(file)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLink(File file) throws IOException {
        return !file.getCanonicalFile().equals(new File(
                file.getParentFile().getCanonicalFile(), file.getName()));
    }

    private static boolean hasAtomicArtifacts(File file) {
        return new File(file.getPath() + ".new").exists()
                || new File(file.getPath() + ".bak").exists();
    }

    private static byte[] read(File file, int limit) throws IOException {
        if (!file.isFile() || file.length() > limit
                || !!isLink(file)) {
            throw new IOException("Invalid sync file");
        }
        try (FileInputStream stream = new FileInputStream(file)) {
            final byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                final int count = stream.read(bytes, offset,
                        bytes.length - offset);
                if (count < 0) {
                    throw new IOException("Short sync read");
                }
                offset += count;
            }
            if (stream.read() != -1) {
                throw new IOException("Changed sync file");
            }
            return bytes;
        }
    }

    private static void write(File file, byte[] bytes) throws IOException {
        final AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        boolean committed = false;
        try {
            stream = atomic.startWrite();
            stream.write(bytes);
            atomic.finishWrite(stream);
            committed = true;
            if (!Arrays.equals(bytes, read(file, bytes.length))) {
                throw new IOException("Sync write mismatch");
            }
        } catch (IOException e) {
            if (stream != null && !committed) {
                atomic.failWrite(stream);
            }
            throw e;
        }
    }
}
