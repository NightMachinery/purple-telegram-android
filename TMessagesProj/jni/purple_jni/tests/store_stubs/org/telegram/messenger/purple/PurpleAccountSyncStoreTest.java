package org.telegram.messenger.purple;

import android.util.AtomicFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class PurpleAccountSyncStoreTest {
    private static final class FakeCore implements PurpleAccountSyncStore.Core {
        boolean bound = true;
        boolean cloneDetected;
        boolean unbindAfterReserve;
        boolean invalidStageStatus;
        int commitChecks;
        private byte[] bytes(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
        private String text(byte[] value) {
            return new String(value, StandardCharsets.UTF_8);
        }
        private PurpleAccountSyncStore.Reply reply(String status, String verdict,
                long seq, long pending, boolean changed, byte[] state) {
            return new PurpleAccountSyncStore.Reply(status, "", verdict,
                    "device", seq, pending, changed, state);
        }
        @Override
        public PurpleAccountSyncStore.Reply inspectState(byte[] state) {
            final String[] parts = text(state).split(":");
            if ((parts.length != 4 && parts.length != 5)
                    || !parts[0].equals("v1")) {
                return reply(parts[0].equals("v2") ? "NewerVersion" : "Invalid",
                        "", 0, 0, false, null);
            }
            try {
                if (parts.length == 5) {
                    Long.parseLong(parts[4]);
                }
                return new PurpleAccountSyncStore.Reply("Valid", "", "",
                        parts[3], Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]), false, state);
            } catch (NumberFormatException e) {
                return reply("Invalid", "", 0, 0, false, null);
            }
        }
        private long seen(byte[] state) {
            final String[] parts = text(state).split(":");
            return parts.length == 5 ? Long.parseLong(parts[4]) : 0;
        }
        private String head(byte[] state) {
            final String[] parts = text(state).split(":");
            return parts[0] + ":" + parts[1] + ":" + parts[3];
        }
        @Override
        public PurpleAccountSyncStore.Reply checkCommit(byte[] state,
                byte[] next) {
            ++commitChecks;
            final PurpleAccountSyncStore.Reply current = inspectState(state);
            final PurpleAccountSyncStore.Reply proposed = inspectState(next);
            if (!current.valid() || !proposed.valid()) {
                return reply("Valid", "InvalidState", 0, 0, false, null);
            }
            if (current.pendingSeq != 0 || proposed.pendingSeq != 0
                    || seen(next) < seen(state)
                    || !head(state).equals(head(next))) {
                return reply("Valid", "InvalidTransition", 0, 0, false, null);
            }
            return seen(next) == seen(state)
                    ? reply("Valid", "Unchanged", 0, 0, false, null)
                    : reply("Valid", "Ready", 0, 0, false, next);
        }
        @Override
        public PurpleAccountSyncStore.Reply checkBinding(int account,
                byte[] state) {
            return reply("Valid", bound && account == 0 ? "Bound" : "Mismatch",
                    0, 0, false, null);
        }
        @Override
        public PurpleAccountSyncStore.Reply reserve(int account, byte[] state,
                byte[] record) {
            final long next = inspectState(state).seq + 1;
            if (!bound || !text(record).equals("record:" + next)) {
                return reply("Invalid", "", 0, 0, false, null);
            }
            if (unbindAfterReserve) {
                bound = false;
            }
            return reply("Valid", "", next, next, false,
                    bytes("v1:" + next + ":" + next + ":device"));
        }
        @Override
        public PurpleAccountSyncStore.Reply checkStage(byte[] state,
                byte[] record) {
            final PurpleAccountSyncStore.Reply parsed = inspectState(state);
            if (!text(record).equals("record:" + parsed.seq)) {
                return reply("Invalid", "", 0, 0, false, null);
            }
            return reply(invalidStageStatus ? "Invalid" : "Valid",
                    parsed.pendingSeq == 0 ? "Confirmed" : "Pending", parsed.seq,
                    parsed.pendingSeq, false, null);
        }
        @Override
        public PurpleAccountSyncStore.Reply confirm(byte[] state,
                byte[] staged, String device, byte[] server, int messageId) {
            if (!Arrays.equals(staged, server) || messageId <= 0) {
                return reply("Invalid", "", 0, 0, false, null);
            }
            final long seq = inspectState(state).seq;
            if (cloneDetected) {
                return reply("Valid", "RemoteAhead", seq, seq, false, state);
            }
            return reply("Valid", "NoClone", seq, 0, true,
                    bytes("v1:" + seq + ":0:device"));
        }
    }

    public static void main(String[] args) throws Exception {
        final File base = Files.createTempDirectory("purple-store-test").toFile();
        final File offRoot = new File(base, "off/purple/sync");
        final FakeCore core = new FakeCore();
        try (PurpleAccountSyncStore off = new PurpleAccountSyncStore(offRoot, core)) {
            expect(off.open(false, 0, "device"), PurpleAccountSyncStore.Status.Disabled);
        }
        if (offRoot.getParentFile().exists()) throw new AssertionError("off created files");

        final File root = new File(base, "on/purple/sync");
        new File(base, "on").mkdir();
        final byte[] initial = "v1:0:0:device".getBytes(StandardCharsets.UTF_8);
        final byte[] record = "record:1".getBytes(StandardCharsets.UTF_8);
        final byte[] reserved = "v1:1:1:device".getBytes(StandardCharsets.UTF_8);
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core);
                PurpleAccountSyncStore second = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Uninitialized);
            expect(second.open(true, 0, "device"), PurpleAccountSyncStore.Status.LockBusy);
            expect(store.initialize(initial), PurpleAccountSyncStore.Status.Ready);
            expect(store.stageConfig(record, reserved), PurpleAccountSyncStore.Status.Ready);
            expect(store.readPendingConfig(), PurpleAccountSyncStore.Status.Ready);
            expect(store.confirmConfigReadBack("wrong".getBytes(StandardCharsets.UTF_8), 5),
                    PurpleAccountSyncStore.Status.Unconfirmed);
            core.cloneDetected = true;
            expect(store.confirmConfigReadBack(record, 5),
                    PurpleAccountSyncStore.Status.CloneDetected);
        }
        core.cloneDetected = false;
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
            expect(store.confirmConfigReadBack(record, 5), PurpleAccountSyncStore.Status.Ready);
        }
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
            expect(store.readPendingConfig(), PurpleAccountSyncStore.Status.NoPending);
        }
        final File state = new File(root, "state.json");
        final File stage = new File(root, "pending/config.json");
        Files.write(stage.toPath(), record);
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
            if (stage.exists()) throw new AssertionError("confirmed stage not cleaned");
        }
        Files.write(stage.toPath(), record);
        Files.write(state.toPath(), initial);
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
            expect(store.readPendingConfig(), PurpleAccountSyncStore.Status.NoPending);
        }
        if (stage.exists()) throw new AssertionError("unreserved next record was kept");
        Files.write(stage.toPath(), "record:5".getBytes(StandardCharsets.UTF_8));
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.OrphanStage);
        }
        if (!stage.exists()) throw new AssertionError("orphan was deleted");
        Files.write(stage.toPath(), record);
        Files.write(state.toPath(), reserved);
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
            expect(store.readPendingConfig(), PurpleAccountSyncStore.Status.Ready);
        }
        Files.write(stage.toPath(), "record:9".getBytes(StandardCharsets.UTF_8));
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.PendingMismatch);
        }
        stage.delete();
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.PendingMissing);
        }
        Files.write(stage.toPath(), record);
        core.bound = false;
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.AccountUnbound);
        }
        core.bound = true;
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "other"), PurpleAccountSyncStore.Status.DeviceMismatch);
        }
        Files.write(state.toPath(), "garbage".getBytes(StandardCharsets.UTF_8));
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.InvalidState);
        }
        Files.write(state.toPath(), "v2:1:1:device".getBytes(StandardCharsets.UTF_8));
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.NewerState);
        }
        Files.write(state.toPath(), reserved);
        Files.write(new File(root, "state.json.new").toPath(), initial);
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
            expect(store.readPendingConfig(), PurpleAccountSyncStore.Status.Ready);
        }
        if (new File(root, "state.json.new").exists()) {
            throw new AssertionError("uncommitted write kept");
        }
        for (String name : new String[] { "state.json.tmp", "pending/config.json.tmp", "other" }) {
            final File stray = new File(root, name);
            Files.write(stray.toPath(), initial);
            try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
                expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.AmbiguousFiles);
            }
            if (!stray.exists()) throw new AssertionError("unknown name " + name + " removed");
            stray.delete();
        }
        final File oddArtifact = new File(root, "state.json.new");
        oddArtifact.mkdir();
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.AmbiguousFiles);
        }
        if (!oddArtifact.isDirectory()) throw new AssertionError("directory artifact removed");
        oddArtifact.delete();
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
        }
        final File raceRoot = new File(base, "race/purple/sync");
        new File(base, "race").mkdir();
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(raceRoot, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Uninitialized);
            expect(store.initialize(initial), PurpleAccountSyncStore.Status.Ready);
            core.invalidStageStatus = true;
            expect(store.stageConfig(record, reserved),
                    PurpleAccountSyncStore.Status.InvalidRecord);
            if (new File(raceRoot, "pending/config.json").exists()) {
                throw new AssertionError("invalid stage was persisted");
            }
            core.invalidStageStatus = false;
            core.unbindAfterReserve = true;
            expect(store.stageConfig(record, reserved),
                    PurpleAccountSyncStore.Status.AccountUnbound);
            if (!new File(raceRoot, "pending/config.json").exists()) {
                throw new AssertionError("binding race lost staged record");
            }
        }
        core.unbindAfterReserve = false;
        core.bound = true;
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(raceRoot, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
            expect(store.readPendingConfig(), PurpleAccountSyncStore.Status.Ready);
        }

        final File corruptRoot = new File(base, "corrupt/purple/sync");
        new File(base, "corrupt").mkdir();
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(corruptRoot, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Uninitialized);
            AtomicFile.corruptAfterFinish = true;
            expect(store.initialize(initial), PurpleAccountSyncStore.Status.IoError);
            if (AtomicFile.failWriteCalls != 0) {
                throw new AssertionError("failWrite called after commit");
            }
            if (!new File(corruptRoot, "state.json").exists()) {
                throw new AssertionError("committed file disappeared");
            }
        }
        testCommitAndHasState(base, core);
        testKilledWrites(base, core);
        testUnsentStageOnWriteFailure(base, core);
        System.out.println("PurpleAccountSyncStore host test passed");
    }

    private enum Site { Initialize, Stage, Reserve, Confirm, Commit }

    private static final byte[] INITIAL = "v1:0:0:device".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SEEN = "v1:0:0:device:3".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECORD = "record:1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] RESERVED = "v1:1:1:device".getBytes(StandardCharsets.UTF_8);

    private static File freshRoot(File base, String name) {
        final File parent = new File(base, name);
        parent.mkdirs();
        return new File(parent, "purple/sync");
    }

    private static void noArtifacts(File root, String what) {
        for (String name : new String[] { "state.json.new", "state.json.bak",
                "pending/config.json.new", "pending/config.json.bak" }) {
            if (new File(root, name).exists()) {
                throw new AssertionError(what + ": " + name + " left behind");
            }
        }
    }

    private static void same(File file, byte[] expected, String what) throws Exception {
        if (!Arrays.equals(Files.readAllBytes(file.toPath()), expected)) {
            throw new AssertionError(what);
        }
    }

    private static void testKilledWrites(File base, FakeCore core) throws Exception {
        for (boolean legacy : new boolean[] { false, true }) {
            for (Site site : Site.values()) {
                for (AtomicFile.Point point : AtomicFile.Point.values()) {
                    killedWrite(base, core, legacy, site, point);
                }
            }
        }
        AtomicFile.legacy = false;
    }

    private static void killedWrite(File base, FakeCore core, boolean legacy,
            Site site, AtomicFile.Point point) throws Exception {
        final String what = (legacy ? "legacy " : "") + site + " killed at " + point;
        final File root = freshRoot(base, "kill-" + (legacy ? "legacy-" : "") + site + "-" + point);
        final File state = new File(root, "state.json");
        final File stage = new File(root, "pending/config.json");
        AtomicFile.legacy = legacy;
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Uninitialized);
            if (site != Site.Initialize) {
                expect(store.initialize(INITIAL), PurpleAccountSyncStore.Status.Ready);
            }
            if (site == Site.Confirm) {
                expect(store.stageConfig(RECORD, RESERVED), PurpleAccountSyncStore.Status.Ready);
            }
            AtomicFile.killName = (site == Site.Stage) ? "config.json" : "state.json";
            AtomicFile.killPoint = point;
            try {
                switch (site) {
                case Initialize:
                    store.initialize(INITIAL);
                    break;
                case Stage:
                case Reserve:
                    store.stageConfig(RECORD, RESERVED);
                    break;
                case Confirm:
                    store.confirmConfigReadBack(RECORD, 5);
                    break;
                case Commit:
                    store.commitConfigState(SEEN);
                    break;
                }
                throw new AssertionError(what + ": the write was not killed");
            } catch (AtomicFile.Killed expected) {
            }
        }
        if (AtomicFile.killName != null) {
            throw new AssertionError(what + ": kill not reached");
        }
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            final PurpleAccountSyncStore.Result opened = store.open(true, 0, "device");
            noArtifacts(root, what);
            switch (site) {
            case Initialize:
                if (legacy && point == AtomicFile.Point.Written) {
                    expect(opened, PurpleAccountSyncStore.Status.Ready);
                    same(state, INITIAL, what + ": first write landed");
                    break;
                }
                expect(opened, PurpleAccountSyncStore.Status.Uninitialized);
                if (state.exists()) throw new AssertionError(what + ": state left");
                expect(store.initialize(INITIAL), PurpleAccountSyncStore.Status.Ready);
                break;
            case Stage:
            case Reserve:
                expect(opened, PurpleAccountSyncStore.Status.Ready);
                expect(store.readPendingConfig(), PurpleAccountSyncStore.Status.NoPending);
                if (stage.exists()) throw new AssertionError(what + ": unsent stage kept");
                same(state, INITIAL, what + ": state moved");
                expect(store.stageConfig(RECORD, RESERVED), PurpleAccountSyncStore.Status.Ready);
                break;
            case Confirm:
                expect(opened, PurpleAccountSyncStore.Status.Ready);
                final PurpleAccountSyncStore.Result pending = store.readPendingConfig();
                expect(pending, PurpleAccountSyncStore.Status.Ready);
                if (!Arrays.equals(pending.staged, RECORD)) {
                    throw new AssertionError(what + ": pending record lost");
                }
                same(state, RESERVED, what + ": reserved state lost");
                expect(store.confirmConfigReadBack(RECORD, 5), PurpleAccountSyncStore.Status.Ready);
                break;
            case Commit:
                expect(opened, PurpleAccountSyncStore.Status.Ready);
                same(state, INITIAL, what + ": uncommitted state kept");
                expect(store.commitConfigState(SEEN), PurpleAccountSyncStore.Status.Ready);
                break;
            }
        }
    }

    private static void testUnsentStageOnWriteFailure(File base, FakeCore core)
            throws Exception {
        for (boolean legacy : new boolean[] { false, true }) {
            AtomicFile.legacy = legacy;
            for (String full : new String[] { "state.json", "config.json" }) {
                final String what = (legacy ? "legacy " : "") + "no space for " + full;
                final File root = freshRoot(base, "full-" + (legacy ? "legacy-" : "") + full);
                final File state = new File(root, "state.json");
                final File stage = new File(root, "pending/config.json");
                try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
                    expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Uninitialized);
                    expect(store.initialize(INITIAL), PurpleAccountSyncStore.Status.Ready);
                    final int failed = AtomicFile.failWriteCalls;
                    AtomicFile.fullName = full;
                    expect(store.stageConfig(RECORD, RESERVED), PurpleAccountSyncStore.Status.IoError);
                    if (AtomicFile.fullName != null || AtomicFile.failWriteCalls != failed + 1) {
                        throw new AssertionError(what + ": write did not fail before commit");
                    }
                    if (stage.exists()) throw new AssertionError(what + ": unsent stage kept");
                    same(state, INITIAL, what + ": state moved");
                    noArtifacts(root, what);
                }
                try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
                    expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Ready);
                    expect(store.readPendingConfig(), PurpleAccountSyncStore.Status.NoPending);
                }
            }
            final String what = (legacy ? "legacy " : "") + "state changed by a failed write";
            final File root = freshRoot(base, "corrupt-reserve" + (legacy ? "-legacy" : ""));
            final File stage = new File(root, "pending/config.json");
            try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
                expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Uninitialized);
                expect(store.initialize(INITIAL), PurpleAccountSyncStore.Status.Ready);
                AtomicFile.corruptName = "state.json";
                expect(store.stageConfig(RECORD, RESERVED), PurpleAccountSyncStore.Status.IoError);
                if (AtomicFile.corruptName != null) throw new AssertionError(what + ": not reached");
                if (!stage.exists()) throw new AssertionError(what + ": stage removed");
            }
        }
        AtomicFile.legacy = false;
    }

    private static void testCommitAndHasState(File base, FakeCore core)
            throws Exception {
        final File parent = new File(base, "commit");
        final File root = new File(parent, "purple/sync");
        final File state = new File(root, "state.json");
        if (PurpleAccountSyncStore.hasState(root) || parent.exists()) {
            throw new AssertionError("hasState created or found files");
        }
        parent.mkdir();
        if (PurpleAccountSyncStore.hasState(root) || root.getParentFile().exists()) {
            throw new AssertionError("hasState created or found files");
        }
        final byte[] initial = "v1:0:0:device".getBytes(StandardCharsets.UTF_8);
        final byte[] seen = "v1:0:0:device:3".getBytes(StandardCharsets.UTF_8);
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Uninitialized);
            if (PurpleAccountSyncStore.hasState(root)) {
                throw new AssertionError("a lock and an empty pending directory are no state");
            }
            if (store.stateBytes() != null) {
                throw new AssertionError("state bytes before initialization");
            }
            expect(store.commitConfigState(seen), PurpleAccountSyncStore.Status.InvalidTransition);
            expect(store.initialize(initial), PurpleAccountSyncStore.Status.Ready);
            if (!PurpleAccountSyncStore.hasState(root)
                    || !Arrays.equals(store.stateBytes(), initial)) {
                throw new AssertionError("initialized state not reported");
            }
            store.stateBytes()[0] = 'x';
            if (!Arrays.equals(store.stateBytes(), initial)) {
                throw new AssertionError("state bytes are not a copy");
            }
            expect(store.commitConfigState(seen), PurpleAccountSyncStore.Status.Ready);
            if (!Arrays.equals(Files.readAllBytes(state.toPath()), seen)
                    || !Arrays.equals(store.stateBytes(), seen)) {
                throw new AssertionError("committed state not written");
            }
            final int writes = AtomicFile.finishWriteCalls;
            final int checks = core.commitChecks;
            expect(store.commitConfigState(seen), PurpleAccountSyncStore.Status.Unchanged);
            if (AtomicFile.finishWriteCalls != writes || core.commitChecks != checks + 1) {
                throw new AssertionError("unchanged state was written");
            }
            expect(store.commitConfigState("v1:0:0:device:2".getBytes(StandardCharsets.UTF_8)),
                    PurpleAccountSyncStore.Status.InvalidTransition);
            expect(store.commitConfigState("v1:0:0:other:4".getBytes(StandardCharsets.UTF_8)),
                    PurpleAccountSyncStore.Status.DeviceMismatch);
            expect(store.commitConfigState("garbage".getBytes(StandardCharsets.UTF_8)),
                    PurpleAccountSyncStore.Status.InvalidState);
            expect(store.commitConfigState(null), PurpleAccountSyncStore.Status.InvalidTransition);
            if (!Arrays.equals(Files.readAllBytes(state.toPath()), seen)
                    || AtomicFile.finishWriteCalls != writes) {
                throw new AssertionError("refused commit wrote state");
            }
            expect(store.stageConfig("record:1".getBytes(StandardCharsets.UTF_8),
                    "v1:1:1:device".getBytes(StandardCharsets.UTF_8)),
                    PurpleAccountSyncStore.Status.Ready);
            final byte[] staged = Files.readAllBytes(state.toPath());
            expect(store.commitConfigState("v1:1:1:device:5".getBytes(StandardCharsets.UTF_8)),
                    PurpleAccountSyncStore.Status.InvalidTransition);
            expect(store.commitConfigState("v1:1:0:device:5".getBytes(StandardCharsets.UTF_8)),
                    PurpleAccountSyncStore.Status.InvalidTransition);
            if (!Arrays.equals(Files.readAllBytes(state.toPath()), staged)) {
                throw new AssertionError("commit over a pending record wrote state");
            }
            if (!PurpleAccountSyncStore.hasState(root)) {
                throw new AssertionError("staged state not reported");
            }
        }

        final File lostRoot = new File(base, "lost/purple/sync");
        new File(base, "lost").mkdir();
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(lostRoot, core)) {
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.Uninitialized);
            expect(store.initialize(initial), PurpleAccountSyncStore.Status.Ready);
            core.bound = false;
            expect(store.commitConfigState(seen), PurpleAccountSyncStore.Status.AccountUnbound);
            core.bound = true;
            expect(store.commitConfigState(seen), PurpleAccountSyncStore.Status.InvalidTransition);
            if (store.stateBytes() != null || !Arrays.equals(
                    Files.readAllBytes(new File(lostRoot, "state.json").toPath()), initial)) {
                throw new AssertionError("unbound commit wrote state");
            }
        }

        final File orphanRoot = new File(base, "orphan/purple/sync");
        new File(orphanRoot, "pending").mkdirs();
        if (PurpleAccountSyncStore.hasState(orphanRoot)) {
            throw new AssertionError("an empty pending directory is no state");
        }
        Files.write(new File(orphanRoot, "pending/config.json").toPath(),
                "record:1".getBytes(StandardCharsets.UTF_8));
        if (!PurpleAccountSyncStore.hasState(orphanRoot)) {
            throw new AssertionError("an orphan stage is state");
        }
        final File strayRoot = new File(base, "stray/purple/sync");
        strayRoot.mkdirs();
        Files.write(new File(strayRoot, "state.json.new").toPath(), initial);
        if (!PurpleAccountSyncStore.hasState(strayRoot)) {
            throw new AssertionError("an atomic artifact is state");
        }
    }

    private static void expect(PurpleAccountSyncStore.Result result,
            PurpleAccountSyncStore.Status expected) {
        if (result.status != expected) {
            throw new AssertionError(result.status + " expected " + expected);
        }
    }
}
