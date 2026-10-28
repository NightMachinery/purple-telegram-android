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
        Unchanged, CarryPending, MissingLocalVersion, InvalidRetention, NewerRetention, RetentionFull
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
        default Reply checkCommit(byte[] state,byte[] next,PurpleSyncChatCore.Peer chat) { return checkCommit(state,next); }
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
        final String chat;

        Reply(String status, String error, String verdict, String device,
                long seq, long pendingSeq, boolean changed, byte[] state) {
            this(status,error,verdict,device,seq,pendingSeq,changed,state,"self");
        }

        private Reply(String status,String error,String verdict,String device,long seq,long pendingSeq,
                boolean changed,byte[] state,String chat) {
            this.chat=chat;
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
                    value.changed, value.state,chatOf(value.state));
        }

        static Reply from(PurpleSyncCore.CommitCheck value) {
            return new Reply(value.isValid() ? "Valid" : "BridgeError",
                    value.isValid() ? "None" : value.error,
                    value.status.name(), "", 0, 0, false, value.state,chatOf(value.state));
        }

        private static String chatOf(byte[] state) {
            if(state==null)return "";
            try {
                org.json.JSONObject value=new org.json.JSONObject(new String(state,java.nio.charset.StandardCharsets.UTF_8));
                return value.optInt("version")==1 && !value.has("chat") ? "self"
                        : value.optInt("version")==2 && value.opt("chat") instanceof String ? value.getString("chat") : "";
            } catch(Exception error) { return ""; }
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
        @Override public Reply checkCommit(byte[] state,byte[] next,PurpleSyncChatCore.Peer chat) {
            return Reply.from(PurpleSyncCore.checkCommit(chat,state,next));
        }
    };

    private final File root;
    private final File pending;
    private final File stateFile;
    private final File stageFile;
    private final Core core;
    private final PurpleGlobalChoiceFiles.Physical physical;
    private final PurpleEditorDirectories.Creator directoryCreator;
    private final java.util.LinkedHashMap<File,PurpleGlobalChoiceFiles.Mark> createdDirectories = new java.util.LinkedHashMap<>();
    private int account;
    private String device;
    private String ownershipKey;
    private RandomAccessFile lockStream;
    private FileLock lock;
    private byte[] state;
    private Status status = Status.Disabled;
    private boolean opened;
    private boolean carryOnly, terminalCarry;
    private boolean carryBlocks(){return PurpleSyncChatMoveStore.artifacts(root)&&!terminalCarry;}
    private boolean production;
    private PurpleAccountFiles.Identity origin;
    private PurpleSyncFileContext context;
    private PurpleSyncChatCore.Peer chat=PurpleSyncChatCore.Peer.self();

    PurpleAccountSyncStore(PurpleSyncFileContext context) {
        this(context,PurpleSyncChatCore.Peer.self());
    }

    PurpleAccountSyncStore(PurpleSyncFileContext context,PurpleSyncChatCore.Peer chat) {
        this(requireFiles(context).syncDirectory, NATIVE, PurpleGlobalAccountChoice.PHYSICAL, PurpleEditorDirectories.NATIVE);
        this.chat=java.util.Objects.requireNonNull(chat);
        this.context = context;
        origin = context.origin;
        production = true;
    }

    PurpleAccountSyncStore(File root, Core core) { this(root, core, null, null); }
    PurpleAccountSyncStore(File root,Core core,PurpleSyncChatCore.Peer chat) {
        this(root,core,null,null);this.chat=java.util.Objects.requireNonNull(chat);
    }
    PurpleAccountSyncStore(File root, Core core, PurpleGlobalChoiceFiles.Physical physical, PurpleEditorDirectories.Creator directoryCreator) {
        this.physical = physical; this.directoryCreator = directoryCreator;
        this.root = root;
        this.pending = new File(root, "pending");
        this.stateFile = new File(root, "state.json");
        this.stageFile = new File(pending, "config.json");
        this.core = core;
    }

    synchronized Result openForCarry(int currentAccount, String currentDevice) {
        if (opened) return result(Status.InvalidTransition);
        carryOnly = true;
        return open(true, currentAccount, currentDevice);
    }
    synchronized boolean carryLockCurrent() {
        if (!carryOnly || lock == null || !lock.isValid() || !routeCurrent()) return false;
        try {
            android.system.StructStat opened = android.system.Os.fstat(lockStream.getFD());
            android.system.StructStat named = android.system.Os.lstat(new File(root, "lock").getPath());
            return opened.st_dev == named.st_dev && opened.st_ino == named.st_ino;
        } catch (Exception e) { return false; }
    }

    public synchronized Result open(boolean optedIn, int currentAccount,
            String currentDevice) {
        if (opened) {
            return result(Status.InvalidTransition);
        }
        opened = true;
        if (production) {
            if (context == null) origin = PurpleAccountFiles.capture(currentAccount);
            if (!routeCurrent()) return fail(Status.AccountUnbound);
        }
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
            if (parent == null || !prepareDirectory(parent)
                    || !prepareDirectory(root)) {
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
            boolean newLock = !lockFile.exists();
            lockStream = new RandomAccessFile(lockFile, "rw");
            if (newLock) {
                try { android.system.Os.chmod(lockFile.getPath(), 0600); }
                catch (android.system.ErrnoException e) { return fail(Status.IoError); }
            }
            try {
                lock = lockStream.getChannel().tryLock();
            } catch (OverlappingFileLockException e) {
                return fail(Status.LockBusy);
            }
            if (lock == null) {
                return fail(Status.LockBusy);
            }
            if (carryOnly) return result(Status.Ready);
            if (PurpleSyncChatMoveStore.artifacts(root)) {terminalCarry=PurpleSyncChatMoveStore.completed(root,production&&origin!=null?origin.key:null);if(!terminalCarry)return fail(Status.CarryPending);}
            if (!PurpleSyncRetainedFiles.valid(root)) return fail(Status.InvalidRetention);
            if (!prepareDirectory(pending)) {
                return fail(Status.IoError);
            }
            if (!safeAutoPaths())return fail(Status.IoError);
            for(File candidate:new File[]{stateFile,new File(stateFile.getPath()+".bak"),new File(stateFile.getPath()+".new")}) {
                if(plainFile(candidate) && candidate.length()>0) {
                    Reply inspected=core.inspectState(read(candidate,STATE_LIMIT));
                    if(inspected.valid() && !chat.token().equals(inspected.chat))return fail(Status.AccountUnbound);
                }
            }
            if(!recover(stateFile))return fail(Status.IoError);
            if(stateFile.exists()) {
                byte[] opening=read(stateFile,STATE_LIMIT);
                Reply inspected=core.inspectState(opening);
                if(inspected.valid() && !scopeMatches(opening))return fail(Status.AccountUnbound);
            }
            if (!recover(stageFile) || !recoverAuto(new File(root,"auto.json"),PurpleAccountFiles.capture(account)!=null?PurpleAccountFiles.capture(account).key:"")) {
                return fail(Status.IoError);
            }
            if (!knownFiles(root, "lock", "state.json", "pending", "auto.json", "sync-chat-setup.json", "sync-chat-setup-create.json", "sync-chat-setup-mark.json", "sync-chat-move-create.json", "retained-bases", "carry.json", "carry-cache.json", "carry-objects", "carry-history")
                    || !knownFiles(pending, "config.json")) {
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
                boolean removeStage = false;
                if (!checked.valid()) {
                    if (inspected.pendingSeq != 0) {
                        return fail(Status.PendingMismatch);
                    }
                    if (!core.reserve(account, state, bytes).valid()) {
                        return fail(Status.OrphanStage);
                    }
                    removeStage = true;
                } else if ("Confirmed".equals(checked.verdict)) {
                    removeStage = true;
                } else if (!"Pending".equals(checked.verdict)) {
                    return fail(Status.PendingMismatch);
                }
                if (removeStage) {
                    Result retained = pinConfigBase(new byte[][]{bytes});
                    if (retained.status != Status.Ready) return fail(retained.status);
                    if (!bound() || !Arrays.equals(state, read(stateFile, STATE_LIMIT))
                            || !Arrays.equals(bytes, read(stageFile, RECORD_LIMIT))) return fail(Status.InvalidTransition);
                    if (!stageFile.delete()) return fail(Status.IoError);
                }
            } else if (inspected.pendingSeq != 0) {
                return fail(Status.PendingMissing);
            }
            if (core == NATIVE && (android.os.Looper.myLooper() == null
                    || android.os.Looper.myLooper() != android.os.Looper.getMainLooper())) {
                Result checkpoint = checkpointRetainedBases();
                if (checkpoint.status != Status.Ready && checkpoint.status != Status.Unchanged) return fail(checkpoint.status);
            }
            return result(Status.Ready);
        } catch (IOException | SecurityException e) {
            return fail(Status.IoError);
        }
    }

    public synchronized Result initialize(byte[] boundState) {
        if (carryOnly || carryBlocks()) return result(Status.CarryPending);
        if (!routeCurrent()) return fail(Status.AccountUnbound);
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

    public synchronized Result stageConfig(byte[] canonicalRecord,byte[] reservedState){return stageConfig(canonicalRecord,reservedState,()->true);}
    synchronized Result stageConfig(byte[] canonicalRecord,
            byte[] reservedState,java.util.function.BooleanSupplier admitted) {
        if (!admitted.getAsBoolean() || status != Status.Ready || canonicalRecord == null
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
            if(!admitted.getAsBoolean())return result(Status.InvalidTransition);
            write(stageFile, canonicalRecord);
            if(!admitted.getAsBoolean()){dropUnreservedStage();return result(Status.InvalidTransition);}
            write(stateFile, reservedState);
            state = reservedState.clone();
            if (!bound()) {
                return fail(Status.AccountUnbound);
            }
            return new Result(Status.Ready, canonicalRecord.clone(),
                    reserved.seq, "");
        } catch (IOException e) {
            dropUnreservedStage();
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
        return confirmConfigReadBack(PurpleSyncChatCore.Peer.self(),serverRecord,messageId);
    }

    synchronized Result confirmConfigReadBack(PurpleSyncChatCore.Peer observedChat,byte[] serverRecord,int messageId) {
        if(!chat.equals(observedChat))return result(Status.Unconfirmed);
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
            byte[] retained = prepareRetention(confirmed.state, new byte[][]{pendingResult.staged});
            write(stateFile, retained);
            state = retained.clone();
            if (!routeCurrent())return fail(Status.AccountUnbound);
            if (!stageFile.delete()) {
                return fail(Status.IoError);
            }
            return new Result(Status.Ready, null, checked.seq, "");
        } catch (IOException e) {
            return fail(e.getMessage() != null && (e.getMessage().contains("Retention") || e.getMessage().equals("MissingLocalVersion")) ? retentionStatus(e) : Status.IoError);
        }
    }

    public synchronized Result commitConfigState(byte[] next) { return commitConfigState(next, new byte[0][]); }

    synchronized Result commitConfigState(byte[] next, byte[][] records) {
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
        if(!scopeMatches(next))return result(Status.AccountUnbound);
        final Reply checked = core.checkCommit(state, next,chat);
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
            byte[] retained = prepareRetention(next, records);
            write(stateFile, retained);
            state = retained.clone();
            if (!bound()) {
                return fail(Status.AccountUnbound);
            }
            return result(Status.Ready);
        } catch (IOException e) {
            return fail(e.getMessage() != null && (e.getMessage().contains("Retention") || e.getMessage().equals("MissingLocalVersion")) ? retentionStatus(e) : Status.IoError);
        }
    }

    private static Status retentionStatus(Exception error) {
        try { return Status.valueOf(error.getMessage()); } catch (Exception ignored) { return Status.InvalidRetention; }
    }
    private byte[] prepareRetention(byte[] next, byte[][] candidates) throws IOException {
        if (core != NATIVE) return next.clone();
        byte[][] files = new byte[0][];
        if (android.os.Looper.myLooper() == null || android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            files = PurpleSyncRetainedFiles.records(root);
        PurpleSyncRetainedBases.Result prepared = PurpleSyncRetainedBases.request("prepare", state, next,
                PurpleSyncRetainedFiles.combine(candidates, files));
        if (!prepared.ready()) throw new IOException(prepared.status);
        return prepared.state;
    }
    synchronized Result pinConfigBase(byte[][] candidates) {
        if (status != Status.Ready || !bound()) return result(Status.InvalidTransition);
        if (core != NATIVE) return result(Status.Ready);
        try {
            PurpleAccountFileSnapshot.worker();
            PurpleSyncRetainedBases.Result base = PurpleSyncRetainedBases.request("find", state, null,
                    PurpleSyncRetainedFiles.combine(candidates, PurpleSyncRetainedFiles.records(root)));
            if (!base.ready()) throw new IOException(base.status);
            if (base.record.length != 0) PurpleSyncRetainedFiles.pin(root, base.record);
            return bound() ? result(Status.Ready) : fail(Status.AccountUnbound);
        } catch (Exception e) { return result(retentionStatus(e)); }
    }
    synchronized Result checkpointRetainedBases() {
        if (status != Status.Ready || !bound()) return result(Status.InvalidTransition);
        try {
            PurpleAccountFileSnapshot.worker();
            Reply inspected = core.inspectState(state);
            PurpleSyncRetainedBases.Result compact = PurpleSyncRetainedBases.request("compact", state, null);
            if (!compact.ready()) throw new IOException(compact.status);
            if (Arrays.equals(state, compact.state) || inspected.pendingSeq != 0 || stageFile.exists())
                return result(Status.Unchanged);
            byte[] automatic = autoBytes();
            if (automatic != null) {
                try {
                    PurpleAccountFiles.Identity identity = origin != null ? origin : PurpleAccountFiles.capture(account);
                    PurpleSyncAutoCore.Record record = identity == null ? null : PurpleSyncAutoCore.decodeRecord(automatic, identity.key);
                    if (record == null || record.value.getBoolean("active") || !record.value.optString("expectedState").isEmpty()
                            || !record.value.optString("record").isEmpty()) return result(Status.Unchanged);
                } catch (Exception invalid) { return result(Status.Unchanged); }
            }
            PurpleSyncRetainedFiles.materialize(root, state);
            PurpleAccountFileSnapshot.Durable expected = PurpleAccountFileSnapshot.durable("read", stateFile, null, null, STATE_LIMIT, 0);
            if (!Arrays.equals(expected.bytes, state) || !bound()) return result(Status.InvalidTransition);
            PurpleAccountFileSnapshot.durable("write", stateFile, expected, compact.state, STATE_LIMIT, 0);
            state = compact.state.clone();
            return bound() ? result(Status.Ready) : fail(Status.AccountUnbound);
        } catch (Exception e) { return result(retentionStatus(e)); }
    }

    String directoryIdentity() throws Exception {
        if(physical==null)throw new IOException("automatic store identity unavailable");
        PurpleGlobalChoiceFiles.Mark mark=physical.mark(root,true);
        if(mark.missing())throw new IOException("automatic store missing");
        return mark.identity;
    }
    synchronized byte[] autoBytes() throws IOException {
        if(status!=Status.Ready||!bound())throw new IOException("auto store unavailable");
        File file=new File(root,"auto.json");
        if(!file.exists())return null;
        return read(file,2*1024*1024);
    }
    synchronized boolean compareAuto(byte[] expected,byte[] next,java.util.function.BooleanSupplier current) {
        try {
            if(next==null||next.length>2*1024*1024||!current.getAsBoolean()||!Arrays.equals(expected,autoBytes()))return false;
            write(new File(root,"auto.json"),next);
            return current.getAsBoolean()&&Arrays.equals(next,autoBytes());
        }catch(IOException e){return false;}
    }
    synchronized boolean commitAutomatic(byte[] expected,byte[] next,byte[] checkpoint,boolean clearStage,java.util.function.BooleanSupplier current) {
        try {
            if(status!=Status.Ready||!bound()||!current.getAsBoolean()||!Arrays.equals(expected,state)
                    ||!Arrays.equals(checkpoint,autoBytes())||!Arrays.equals(state,read(stateFile,STATE_LIMIT)))return false;
            org.json.JSONObject record=new org.json.JSONObject(new String(checkpoint,java.nio.charset.StandardCharsets.UTF_8));
            if(!record.getBoolean("active")||!Arrays.equals(next,PurpleSyncAutoCore.decode(record.getString("expectedState"))))return false;
            int action=record.getInt("pending");
            if(action!=PurpleSyncAutoCore.Action.AutoApply.ordinal()&&action!=PurpleSyncAutoCore.Action.Retire.ordinal()
                    &&!(action==PurpleSyncAutoCore.Action.Reconcile.ordinal()&&record.getBoolean("commitOnly")))return false;
            Reply inspected=core.inspectState(next),previous=core.inspectState(state);
            if(!inspected.valid()||!device.equals(inspected.device)||!Arrays.equals(next,inspected.state)||!bindingMatches(next))return false;
            PurpleAccountSyncCore.Result old=PurpleAccountSyncCore.inspectState(state),replacement=PurpleAccountSyncCore.inspectState(next);
            if(!old.isValid()||!replacement.isValid()||!old.install.equals(replacement.install)||!old.space.equals(replacement.space)
                    ||!old.bindingToken.equals(replacement.bindingToken))return false;
            if(!current.getAsBoolean())return false;
            write(stateFile,next);state=next.clone();
            if(clearStage&&stageFile.exists()&&!stageFile.delete())return false;
            return current.getAsBoolean()&&bound()&&Arrays.equals(next,read(stateFile,STATE_LIMIT));
        }catch(Exception e){return false;}
    }
    private boolean safeAutoPaths() {
        try {
            if(physical!=null)for(String name:new String[]{"auto.json","auto.json.new","auto.json.bak"})physical.mark(new File(root,name),false);
            return true;
        }catch(Exception e){return false;}
    }
    private static boolean recoverAuto(File file,String accountKey)throws IOException {
        File backup=new File(file.getPath()+".bak"),temporary=new File(file.getPath()+".new");
        for(File entry:new File[]{file,backup,temporary})if(entry.exists()&&(!plainFile(entry)||!entry.isFile()))return false;
        try {
            if(backup.exists()) {
                if(PurpleSyncAutoCore.decodeRecord(read(backup,2*1024*1024),accountKey)==null||!backup.renameTo(file))return false;
            }
            if(temporary.exists()) {
                if(!file.exists()||PurpleSyncAutoCore.decodeRecord(read(file,2*1024*1024),accountKey)==null)return false;
                return temporary.delete();
            }
            return true;
        }catch(Exception e){return false;}
    }
    public synchronized byte[] stateBytes() {
        return (routeCurrent() && status == Status.Ready && state != null) ? state.clone() : null;
    }

    public synchronized Status status() {
        return status;
    }

    static String claimRecovery(File root) throws IOException {
        String key = root.getCanonicalPath();
        synchronized (OWNED_ROOTS) { return OWNED_ROOTS.add(key) ? key : null; }
    }
    static void releaseRecovery(String key) { synchronized (OWNED_ROOTS) { OWNED_ROOTS.remove(key); } }

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
                if (PurpleSyncRecoveryFiles.archiveName(file.getName()) && file.isDirectory() && !isLink(file)) continue;
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
        carryOnly = false;terminalCarry=false;
    }

    private static PurpleAccountFiles requireFiles(PurpleSyncFileContext context) {
        if (context == null || !context.current()) throw new IllegalArgumentException("sync account unavailable");
        return context.files;
    }

    private boolean bound() {
        return !carryOnly && !carryBlocks() && state != null && bindingMatches(state);
    }

    private java.util.function.BooleanSupplier admission=()->true;
    PurpleAccountSyncStore require(java.util.function.BooleanSupplier guard) { admission=guard;return this; }
    private boolean routeCurrent() {
        return admission.getAsBoolean() && (!production || ((context == null || context.current()) && origin != null && origin.current()
                && PurpleAccountOwner.modernOperation(origin.slot)));
    }

    private boolean scopeMatches(byte[] bytes) {
        Reply inspected=core.inspectState(bytes);
        return inspected.valid() && chat.token().equals(inspected.chat);
    }

    private boolean bindingMatches(byte[] bytes) {
        if (!routeCurrent() || !scopeMatches(bytes)) return false;
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

    java.util.Map<File,PurpleGlobalChoiceFiles.Mark> createdDirectories() { return new java.util.LinkedHashMap<>(createdDirectories); }
    private boolean prepareDirectory(File directory) throws IOException {
        if(physical==null||directoryCreator==null)return privateDirectory(directory,true);
        try {
            PurpleGlobalChoiceFiles.Mark mark=physical.mark(directory,true);
            if(!mark.missing())return true;
            PurpleGlobalChoiceFiles.Mark parent=physical.mark(directory.getParentFile(),true);
            if(parent.missing())return false;
            PurpleEditorDirectories.Receipt receipt=directoryCreator.create(directory,parent);
            if(receipt==null||!receipt.created||!receipt.owned||!receipt.parentIdentity.equals(parent.identity)
                    ||!receipt.mark.same(physical.mark(directory,true)))return false;
            createdDirectories.put(directory,receipt.mark);return true;
        } catch(Exception e) { throw new IOException("sync directory creation failed",e); }
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
            boolean archive = file.getName().matches("orphaned-[0-9]{8}-[0-9]{6}") && file.isDirectory()
                    && Arrays.asList(expected).contains("state.json");
            if ((!Arrays.asList(expected).contains(file.getName()) && !archive) || isLink(file)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLink(File file) throws IOException {
        return !file.getCanonicalFile().equals(new File(
                file.getParentFile().getCanonicalFile(), file.getName()));
    }

    private void dropUnreservedStage() {
        try {
            if (!carryOnly && !carryBlocks() && Arrays.equals(read(stateFile, STATE_LIMIT), state)) {
                stageFile.delete();
            }
        } catch (IOException | SecurityException ignored) {
        }
    }

    static boolean recoverInterrupted(File appRoot,File directory,String accountKey,PurpleGlobalChoiceFiles.Physical physical,
            PurpleSyncRecoveryModel.Current current) {
        try {
            if(PurpleSyncChatMoveStore.artifacts(directory) || !current.current())return false;
            PurpleSyncRecoveryFiles.Tree inputs=new PurpleSyncRecoveryFiles.Tree(appRoot,physical);
            PurpleSyncRecoveryFiles.Snapshot folder=inputs.capture(directory,true);
            if(folder.mark.missing())return true;
            File state=new File(directory,"state.json"),stage=new File(directory,"pending/config.json"),auto=new File(directory,"auto.json");
            boolean repair=false;
            for(File file:new File[]{state,stage,auto})for(String suffix:new String[]{".new",".bak"})
                if(!physical.mark(new File(file.getPath()+suffix),false).missing())repair=true;
            for(File file:new File[]{state,stage,auto}) {
                PurpleGlobalChoiceFiles.Mark mark=physical.mark(file,false);
                if(!mark.missing()&&file.length()==0)repair=true;
            }
            if(!repair)return true;
            for(String name:folder.entries) {
                File file=new File(directory,name);
                if(name.equals("pending")||PurpleSyncRecoveryFiles.archiveName(name))inputs.capture(file,true);
                else if(java.util.Arrays.asList("lock","state.json","state.json.new","state.json.bak","auto.json","auto.json.new","auto.json.bak").contains(name))inputs.capture(file,false);
                else return false;
            }
            PurpleSyncRecoveryFiles.Snapshot pending=inputs.capture(new File(directory,"pending"),true);
            if(pending.entries!=null)for(String name:pending.entries) {
                if(!java.util.Arrays.asList("config.json","config.json.new","config.json.bak").contains(name))return false;
                inputs.capture(new File(pending.file,name),false);
            }
            if(!current.current()||!inputs.current())return false;
            try(PurpleSyncRecoveryFiles.Lock lock=new PurpleSyncRecoveryFiles.Lock(directory,folder.mark.identity)) {
                if(!current.current()||!inputs.currentExcept(directory)
                        ||!folder.mark.same(physical.mark(directory,true)))return false;
                for(PurpleSyncRecoveryFiles.Snapshot input:inputs.files.values())
                    if(!input.directory&&!input.file.getName().equals("lock")&&!input.current(physical))return false;
                java.util.TreeSet<String> expected=new java.util.TreeSet<>(java.util.Arrays.asList(folder.entries));expected.add("lock");
                if(!expected.equals(new java.util.TreeSet<>(java.util.Arrays.asList(PurpleSyncRecoveryFiles.names(directory)))))return false;
                return !PurpleSyncChatMoveStore.artifacts(directory)&&current.current()&&recover(state)&&current.current()&&recover(stage)&&current.current()&&recoverAuto(auto,accountKey)&&current.current();
            }
        } catch(Exception|LinkageError e){return false;}
    }

    private static boolean recover(File file) throws IOException {
        final File backup = new File(file.getPath() + ".bak");
        final File temporary = new File(file.getPath() + ".new");
        if (plainFile(backup) && !backup.renameTo(file)) {
            return false;
        }
        if (plainFile(temporary) && !temporary.delete()) {
            return false;
        }
        return !plainFile(file) || file.length() != 0 || file.delete();
    }

    private static boolean plainFile(File file) throws IOException {
        return file.isFile() && !isLink(file);
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

    private void write(File file, byte[] bytes) throws IOException {
        final AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        boolean committed = false;
        try {
            if(carryOnly || carryBlocks() || !admission.getAsBoolean())throw new IOException("Sync operation retired");
            stream = atomic.startWrite();
            stream.write(bytes);
            if(carryOnly || carryBlocks() || !admission.getAsBoolean())throw new IOException("Sync operation retired");
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
