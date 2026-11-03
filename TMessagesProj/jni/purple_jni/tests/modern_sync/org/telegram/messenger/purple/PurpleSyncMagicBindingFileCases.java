package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

/**
 * The magic chat binding ({@code chat.json}) lives in the account's sync folder beside the sync state. The native check
 * of R2 found every store open failing with AmbiguousFiles once it existed. These cases hold the folder's scans to it:
 * the store and its recovery open beside it, and a set aside or Start again leaves it byte for byte.
 */
final class PurpleSyncMagicBindingFileCases {
    static final PurpleSyncChatCore.Peer MAGIC = PurpleSyncMagicMoveCases.MAGIC;

    /**
     * The bytes an R2 device holds: the driver's own steps for a chat it created, archived and muted, then the verify
     * that bound it (and cleared the finished create), all through the real binding serializer, written as
     * {@code PurpleSyncMagicTelegram.writeBinding} does.
     */
    static byte[] r2Binding() {
        long started = 1800000000L;
        String binding = PurpleSyncMagicCore.createStep("", PurpleSyncMagicCore.CreateStep.Requested, started, null);
        for (PurpleSyncMagicCore.CreateStep step : new PurpleSyncMagicCore.CreateStep[] {
                PurpleSyncMagicCore.CreateStep.Created, PurpleSyncMagicCore.CreateStep.Archived, PurpleSyncMagicCore.CreateStep.Muted })
            binding = PurpleSyncMagicCore.createStep(binding, step, started, MAGIC);
        check(binding != null && binding.contains("\"step\":\"muted\""), "the create steps ran up to muted");
        PurpleSyncMagicCore.Candidate chat = new PurpleSyncMagicCore.Candidate();
        chat.channelId = MAGIC.id; chat.title = PurpleSyncMagicCore.TITLE; chat.megagroup = true;
        chat.facts.kind = PurpleSyncChatCore.Kind.Supergroup;
        chat.facts.creator = chat.facts.admin = chat.facts.soleAdmin = chat.facts.canSendFiles = true;
        chat.facts.otherMembers = 0L; chat.facts.otherAdmins = 0L;
        PurpleSyncMagicCore.Verified verified = PurpleSyncMagicCore.verify(binding, chat, started + 1);
        check(verified != null && verified.problem == PurpleSyncMagicCore.Problem.None, "the created chat verifies");
        PurpleSyncMagicCore.Binding parsed = PurpleSyncMagicCore.parse(verified.binding);
        check(parsed != null && parsed.mode == PurpleSyncMagicCore.Mode.Magic && MAGIC.equals(parsed.chat) && parsed.verifiedAt == started + 1
                && verified.binding.contains("\"step\":\"none\""), "an R2 binding: magic mode, the bound channel, verified, its create done and cleared");
        return verified.binding.getBytes(StandardCharsets.UTF_8);
    }

    static void run(File parent) throws Exception {
        storeOpens(parent);
        emptyFolder(parent);
        startAgain(parent);
    }

    /** The bound chat's store opens beside the binding, recovers beside it and sets its state aside without it. */
    static void storeOpens(File parent) throws Exception {
        Frame frame = new Frame(parent, "magic-binding-file", false);
        String key = PurpleAccountFiles.capture(0).key;
        try (PurpleAccountSyncStore store = PurpleSyncMagicMoveCases.self(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready, "Saved Messages store opens");
            check(store.moveTo(MAGIC, 101, key, PurpleSyncMagicMoveCases.fingerprint(frame)).status == PurpleAccountSyncStore.Status.Ready,
                    "the state moves to the bound chat");
        }
        File file = new File(frame.sync(0), "chat.json");
        byte[] binding = r2Binding();
        Files.write(file.toPath(), binding);
        try (PurpleAccountSyncStore store = PurpleSyncMagicMoveCases.magic(frame)) {
            PurpleAccountSyncStore.Result opened = store.open(true, 0, device(0));
            check(opened.status == PurpleAccountSyncStore.Status.Ready, "the store opens beside an R2 chat.json: " + opened.status);
        }
        File temporary = new File(frame.sync(0), "owner-1234567890.tmp"), negative = new File(frame.sync(0), "owner--42.tmp");
        Files.write(temporary.toPath(), binding); Files.write(negative.toPath(), binding);
        try (PurpleAccountSyncStore store = PurpleSyncMagicMoveCases.magic(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready, "and beside temporaries its writer left");
        }
        Files.delete(temporary.toPath()); Files.delete(negative.toPath());
        for (String name : new String[] { "chat.json.bak", "chat.json.tmp", "owner-x.tmp", "chat.toml" }) {
            File other = new File(frame.sync(0), name);
            Files.write(other.toPath(), binding);
            try (PurpleAccountSyncStore store = PurpleSyncMagicMoveCases.magic(frame)) {
                check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.AmbiguousFiles, "only exact names pass, not " + name);
            }
            Files.delete(other.toPath());
        }
        Files.delete(file.toPath());
        File outside = new File(frame.app, "outside-binding");
        Files.write(outside.toPath(), binding);
        Files.createSymbolicLink(file.toPath(), outside.toPath());
        try (PurpleAccountSyncStore store = PurpleSyncMagicMoveCases.magic(frame)) {
            PurpleAccountSyncStore.Result opened = store.open(true, 0, device(0));
            check(opened.status == PurpleAccountSyncStore.Status.AmbiguousFiles, "a linked chat.json still refuses: " + opened.status);
        }
        Files.delete(file.toPath());
        Files.write(file.toPath(), binding);

        // A crash inside a state write leaves a backup: recovery repairs it beside the binding and leaves the binding.
        File state = new File(frame.sync(0), "state.json"), backup = new File(state.getPath() + ".bak");
        byte[] before = frame.state(0);
        Files.write(backup.toPath(), before);
        check(PurpleAccountSyncStore.recoverInterrupted(frame.root, frame.sync(0), A, PHYSICAL, () -> true), "interrupted recovery runs beside chat.json");
        check(!backup.exists() && Arrays.equals(before, frame.state(0)) && Arrays.equals(binding, Files.readAllBytes(file.toPath())),
                "it repaired the state and kept chat.json byte for byte");

        try (PurpleAccountSyncStore store = PurpleSyncMagicMoveCases.magic(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Ready, "the store opens again");
            check(store.setAside().status == PurpleAccountSyncStore.Status.Uninitialized, "the state is set aside");
        }
        File[] archives = frame.sync(0).listFiles((dir, name) -> PurpleSyncRecoveryFiles.archiveName(name));
        check(archives != null && archives.length == 1 && !new File(archives[0], "chat.json").exists()
                && Arrays.equals(binding, Files.readAllBytes(file.toPath())), "a set aside leaves chat.json in place, byte for byte");
        try (PurpleAccountSyncStore store = PurpleSyncMagicMoveCases.magic(frame)) {
            check(store.open(true, 0, device(0)).status == PurpleAccountSyncStore.Status.Uninitialized,
                    "the store opens empty beside the binding and the orphaned folder");
        }
    }

    /** The binding alone is no sync state: setup is not mistaken for a recovery. */
    static void emptyFolder(File parent) throws Exception {
        File sync = new File(parent, "magic-binding-only");
        directory(sync);
        Files.write(new File(sync, "chat.json").toPath(), r2Binding());
        check(!PurpleAccountSyncStore.hasState(sync), "a folder with only chat.json holds no sync state");
        write(new File(sync, "state.json"), "x");
        check(PurpleAccountSyncStore.hasState(sync), "and one with a state does");
    }

    /** Start again archives the state and pending stages and keeps the binding: the magic chat outlives a reset. */
    static void startAgain(File parent) throws Exception {
        Frame frame = PurpleSyncRecoveryCases.invalid(parent, "magic-binding-start-again");
        File file = new File(frame.sync(1), "chat.json");
        byte[] binding = r2Binding();
        Files.write(file.toPath(), binding);
        PurpleSyncRecoveryModel.Offer shown = PurpleSyncRecoveryCases.offer(frame, 1, frame.clouds[1].inventory(), PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(shown != null && shown.plan.offerStartAgain, "an invalid state beside chat.json still offers Start again");
        check(shown.model.startAgain(shown, false), "Start again completes beside chat.json");
        check(!new File(frame.sync(1), "state.json").exists() && !new File(frame.sync(1), "pending").exists()
                && !new File(frame.sync(1), "start-again.json").exists(), "the state and stages left the folder");
        check(Arrays.equals(binding, Files.readAllBytes(file.toPath())), "Start again keeps chat.json byte for byte");
        File[] archives = frame.sync(1).listFiles((dir, name) -> PurpleSyncRecoveryFiles.archiveName(name));
        check(archives != null && archives.length == 1 && !new File(archives[0], "chat.json").exists(), "and does not archive it");
        PurpleSyncRecoveryModel.Offer setup = PurpleSyncRecoveryCases.offer(frame, 1, frame.clouds[1].inventory(), PurpleSyncRecoveryFiles.NATIVE_MOVES);
        check(setup != null && setup.plan.status == PurpleSyncRecoveryCore.Status.Setup, "the next Check sets up beside the kept binding");
        frame.unchangedA();
    }

    private PurpleSyncMagicBindingFileCases() { }
}
