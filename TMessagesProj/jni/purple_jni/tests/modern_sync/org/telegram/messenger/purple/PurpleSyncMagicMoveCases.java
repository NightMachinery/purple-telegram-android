package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

/** The store moves a real native state from Saved Messages into the bound chat (Magic Purple Sync A4). */
final class PurpleSyncMagicMoveCases {
    static final PurpleSyncChatCore.Peer MAGIC = PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, 777);

    static PurpleAccountSyncStore self(Frame frame) {
        return new PurpleAccountSyncStore(frame.sync(0), PurpleAccountSyncStore.NATIVE, PHYSICAL, PurpleEditorDirectories.NATIVE);
    }
    static PurpleAccountSyncStore magic(Frame frame) {
        return new PurpleAccountSyncStore(frame.sync(0), PurpleAccountSyncStore.NATIVE, MAGIC);
    }
    static String fingerprint(Frame frame) throws Exception {
        return PurpleSyncCore.settingsFingerprint(Files.readAllBytes(frame.settings(0).toPath()));
    }
    static JSONObject json(byte[] bytes) throws Exception { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }

    static void run(File parent) throws Exception {
        moveWithoutStage(parent);
        moveAbandonsStage(parent);
        setAside(parent);
    }

    static void moveWithoutStage(File parent) throws Exception {
        Frame frame = new Frame(parent, "magic-move", false);
        String key = PurpleAccountFiles.capture(0).key;
        File auto = new File(frame.sync(0), "auto.json");
        PurpleSyncAutoCore.Record fresh = PurpleSyncAutoCore.decodeRecord(null, key);
        check(fresh != null, "a fresh automatic record encodes");
        Files.write(auto.toPath(), fresh.bytes);
        byte[] before = frame.state(0);
        try (PurpleAccountSyncStore store = self(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready, "Saved Messages store opens");
            check(store.moveTo(PurpleSyncChatCore.Peer.self(), 101, key, fingerprint(frame)).status
                    == PurpleAccountSyncStore.Status.Unchanged, "moving to the same chat changes nothing");
            check(Arrays.equals(before, frame.state(0)), "an unchanged move keeps state.json");
            check(store.moveTo(MAGIC, 101, key, "") .status != PurpleAccountSyncStore.Status.Ready, "a move without a fingerprint is refused");
            check(Arrays.equals(before, frame.state(0)) && Arrays.equals(fresh.bytes, Files.readAllBytes(auto.toPath())),
                    "a refused move writes nothing");
            check(store.moveTo(MAGIC, 101, key, fingerprint(frame)).status == PurpleAccountSyncStore.Status.Ready, "the move succeeds");
            check(MAGIC.equals(store.chat()), "the open store now belongs to the bound chat");
        }
        JSONObject state = json(frame.state(0));
        check(state.getInt("version") == 2 && MAGIC.token().equals(state.getString("chat")), "state.json names the bound chat");
        PurpleAccountSyncCore.Result previous = PurpleAccountSyncCore.inspectState(before), moved = PurpleAccountSyncCore.inspectState(frame.state(0));
        check(moved.isValid() && previous.space.equals(moved.space) && previous.install.equals(moved.install)
                && previous.bindingToken.equals(moved.bindingToken), "the move keeps the space, install and binding");
        JSONObject record = json(Files.readAllBytes(auto.toPath()));
        check(fingerprint(frame).equals(record.getString("savedFingerprint")) && record.getBoolean("queuedCheck"),
                "the automatic record treats the file on disk as saved and checks next");
        try (PurpleAccountSyncStore store = self(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.AccountUnbound,
                    "a Saved Messages store no longer opens the moved state");
        }
        try (PurpleAccountSyncStore store = magic(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready, "the bound chat's store opens it");
        }
    }

    static void moveAbandonsStage(File parent) throws Exception {
        Frame frame = new Frame(parent, "magic-move-stage", false);
        String key = PurpleAccountFiles.capture(0).key;
        byte[] state = frame.state(0);
        PurpleAccountSyncCore.Result inspected = PurpleAccountSyncCore.inspectState(state);
        PurpleAccountSyncCore.Result built = PurpleAccountSyncCore.buildConfigRecord(Files.readAllBytes(frame.settings(0).toPath()),
                new byte[0][], inspected.space, inspected.install, device(0), "Android", "test", inspected.seq + 1, 1800000000L);
        check(built.isValid() && built.record != null, "a real config record builds: " + built.status + " " + built.error);
        PurpleAccountSyncCore.Result reserved = PurpleAccountSyncCore.reserveConfigRecord(0, state, built.record);
        check(reserved.isValid() && reserved.state != null, "the record reserves a sequence: " + reserved.status + " " + reserved.error);
        File stage = new File(frame.sync(0), "pending/config.json");
        try (PurpleAccountSyncStore store = self(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready, "Saved Messages store opens");
            check(store.stageConfig(built.record, reserved.state).status == PurpleAccountSyncStore.Status.Ready, "the record is staged");
            check(stage.exists(), "the stage file exists");
            check(store.moveTo(MAGIC, 101, key, fingerprint(frame)).status == PurpleAccountSyncStore.Status.Ready, "the staged state moves");
        }
        check(!stage.exists(), "the abandoned stage file is gone");
        JSONObject config = json(frame.state(0)).getJSONObject("streams").getJSONObject("config");
        check(config.optLong("pending_seq") == 0 && config.optLong("abandoned_seq") == inspected.seq + 1,
                "the moved state abandoned the stage's sequence");
        Files.createDirectories(stage.getParentFile().toPath());
        Files.write(stage.toPath(), built.record);
        try (PurpleAccountSyncStore store = magic(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready,
                    "an abandoned stage left by an interrupted move does not block opening");
        }
        check(!stage.exists(), "opening removes the abandoned stage");
        Files.write(stage.toPath(), "not a record".getBytes(StandardCharsets.UTF_8));
        try (PurpleAccountSyncStore store = magic(frame)) {
            check(store.open(true, 0, device(0)).status != PurpleAccountSyncStore.Status.Ready, "any other orphan stage still refuses");
        }
    }

    /** Join for space_mismatch: the device's own state moves into an orphaned folder and the store is empty again. */
    static void setAside(File parent) throws Exception {
        Frame frame = new Frame(parent, "magic-set-aside", false);
        String key = PurpleAccountFiles.capture(0).key;
        File auto = new File(frame.sync(0), "auto.json");
        Files.write(auto.toPath(), PurpleSyncAutoCore.decodeRecord(null, key).bytes);
        try (PurpleAccountSyncStore store = self(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready, "Saved Messages store opens");
            check(store.moveTo(MAGIC, 101, key, fingerprint(frame)).status == PurpleAccountSyncStore.Status.Ready, "the state moves to the bound chat");
        }
        byte[] state = frame.state(0), record = Files.readAllBytes(auto.toPath());
        try (PurpleAccountSyncStore store = magic(frame)) {
            check(store.setAside().status == PurpleAccountSyncStore.Status.InvalidTransition, "a store that is not open sets nothing aside");
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready, "the bound chat's store opens");
            check(store.setAside().status == PurpleAccountSyncStore.Status.Uninitialized, "the state is set aside");
            check(store.setAside().status == PurpleAccountSyncStore.Status.InvalidTransition, "only once");
        }
        File[] archives = frame.sync(0).listFiles((dir, name) -> PurpleSyncRecoveryFiles.archiveName(name));
        check(archives != null && archives.length == 1, "one orphaned folder holds it");
        check(!new File(frame.sync(0), "state.json").exists() && !auto.exists(), "the state and the automatic record left the sync folder");
        check(Arrays.equals(state, Files.readAllBytes(new File(archives[0], "state.json").toPath()))
                && Arrays.equals(record, Files.readAllBytes(new File(archives[0], "auto.json").toPath())), "nothing is deleted: both are kept byte for byte");
        try (PurpleAccountSyncStore store = magic(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Uninitialized,
                    "the store opens empty beside the orphaned folder, so the next check can join the chat's records");
        }
    }

    private PurpleSyncMagicMoveCases() { }
}
