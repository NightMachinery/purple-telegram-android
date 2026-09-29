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
            if (parts.length != 4 || !parts[0].equals("v1")) {
                return reply(parts[0].equals("v2") ? "NewerVersion" : "Invalid",
                        "", 0, 0, false, null);
            }
            try {
                return reply("Valid", "", Long.parseLong(parts[1]),
                        Long.parseLong(parts[2]), false, state);
            } catch (NumberFormatException e) {
                return reply("Invalid", "", 0, 0, false, null);
            }
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
            expect(store.open(true, 0, "device"), PurpleAccountSyncStore.Status.AmbiguousFiles);
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
        System.out.println("PurpleAccountSyncStore host test passed");
    }

    private static void expect(PurpleAccountSyncStore.Result result,
            PurpleAccountSyncStore.Status expected) {
        if (result.status != expected) {
            throw new AssertionError(result.status + " expected " + expected);
        }
    }
}
