package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

/**
 * Magic mode end to end on the real Android store, retention and known-files code and the real file layout, with only
 * the transport faked (native check A4). The app's files directory is reached through a symbolic link, as Android's
 * /data/user/0 links to /data/data: the retained-bases check compared the whole canonical path with the absolute one,
 * so the first magic publish posted its record, failed its local commit with InvalidRetention and left a store that no
 * longer opened. Every step is followed by the three checks of the sync folder: the retention check, the store's open
 * (its known-files check) and the move store's known paths.
 *
 * The automatic path (R4b) runs the real engine and network over the magic group (PurpleSyncMagicDevices): one own
 * record and one Save make exactly one automatic write with no exception, for a fresh device through first use, and
 * for two devices that migrate from a shared Saved Messages space.
 */
final class PurpleSyncMagicScenarioCases {
    static final PurpleSyncChatCore.Peer MAGIC = PurpleSyncMagicPublishCases.MAGIC;

    static void run(File parent) throws Exception {
        freshAccount(parent);
        fromD1(parent);
        automatic(parent, false);
        automatic(parent, true);
        automaticFresh(parent);
        automaticWaits(parent);
        automaticUnsent(parent);
        migration(parent);
        automaticFailure(parent);
        System.out.println("MAGIC_AUTOMATIC_CASES=PASS");
    }

    // ---- the automatic path (R4b): the real engine and network over the magic group ----

    /** A device with no state publishes the first record (first use), and the store then holds it confirmed. */
    static void ownRecord(PurpleSyncMagicDevices.Device device, PurpleSyncMagicDevices.Group group, String step) throws Exception {
        PurpleSyncFirstUse.Outcome first = PurpleSyncFirstUseCases.firstUse(device, group, PurpleSyncCore.FirstUseChoice.None, step);
        check(first.result == PurpleSyncFirstUse.Result.Published && group.messages.size() == 1, step + ": one own record, confirmed: " + first.result);
        PurpleAccountSyncCore.Result state = device.state();
        check(state.isValid() && state.pendingSeq == 0 && MAGIC.token().equals(PurpleSyncMagicPublishCases.stateChat(device.sync())), step + ": the store holds it, nothing pending");
    }

    /** One automatic Save: exactly one automatic write of the saved bytes, no exception, nothing held. */
    static void oneSave(PurpleSyncMagicDevices.Automatic port, byte[] bytes, String step) throws Exception {
        PurpleSyncMagicDevices.Group group = port.group;
        int writes = group.writes(), posts = group.posts;
        port.saved(bytes);
        check(port.failures.isEmpty(), step + ": the automatic pass throws nothing: " + port.failures);
        check(group.writes() == writes + 1, step + ": exactly one automatic write: " + (group.writes() - writes) + " (posts " + (group.posts - posts) + ", edits " + group.edits + ") reason=" + port.reason);
        java.util.List<byte[]> heads = group.heads();
        check(heads.size() == 1 && Arrays.equals(bytes, heads.get(0)) && group.spaces().size() == 1, step + ": the group's one head is the saved file");
        check(port.reason == PurpleSyncAutoCore.Reason.None, step + ": automatic sync holds nothing: " + port.reason);
        PurpleAccountSyncCore.Result state = port.device.state();
        check(state.isValid() && state.pendingSeq == 0, step + ": the store confirmed the automatic record");
        int after = group.writes();
        port.later();
        check(port.failures.isEmpty() && group.writes() == after, step + ": a later pass writes nothing again");
    }

    /**
     * The device check of R4a2: the chat is bound, the group holds one own record confirmed by this device, and
     * automatic sync is on. One Save makes exactly one automatic write: an edit of the own record where Telegram allows
     * it (core's EditInPlace, the default), or with {@code editRefused} exactly one new record posted. Every pass threw
     * AutomaticNonSelfHeld before: PurpleSyncAutoCore.Inventory accepted only a Saved Messages inventory.
     */
    static void automatic(File parent, boolean editRefused) throws Exception {
        PurpleSyncMagicDevices.Device device = new PurpleSyncMagicDevices.Device(parent, "magic-automatic-" + (editRefused ? "post" : "edit"), "fixture-device-auto");
        device.local(PurpleSyncMagicDevices.MINE); device.bind();
        PurpleSyncMagicDevices.Group group = new PurpleSyncMagicDevices.Group();
        ownRecord(device, group, "automatic");
        PurpleSyncMagicDevices.Automatic port = new PurpleSyncMagicDevices.Automatic(device, group);
        port.client.editRefused = editRefused;
        port.event(PurpleSyncAutoCore.Event.Enable);
        check(port.enabled && port.failures.isEmpty(), "automatic: automatic sync turns on with no exception: " + port.failures + " " + port.reason);
        int writes = group.writes();
        port.later();
        check(port.failures.isEmpty() && group.writes() == writes && port.reason == PurpleSyncAutoCore.Reason.None,
                "automatic: a pass over the own head throws nothing and writes nothing: " + port.failures + " " + port.reason);
        oneSave(port, PurpleSyncMagicDevices.THEIRS, "automatic " + (editRefused ? "post" : "edit"));
        check(editRefused ? group.posts == 2 && group.edits == 0 : group.posts == 1 && group.edits == 1,
                "automatic: " + (editRefused ? "exactly one new record posted" : "the own record edited in place") + ": posts " + group.posts + " edits " + group.edits);
        check(editRefused ? group.messages.size() == 1 && group.deletes == 1 : group.messages.size() == 1,
                "automatic: only this device's obsolete record retires after the post: " + group.messages.size() + " deletes " + group.deletes);
        port.engine.close();
    }

    /** A fresh device with automatic sync on: first use publishes the first record, then a Save writes once. */
    static void automaticFresh(File parent) throws Exception {
        PurpleSyncMagicDevices.Device device = new PurpleSyncMagicDevices.Device(parent, "magic-automatic-fresh", "fixture-device-fresh");
        device.local(PurpleSyncMagicDevices.MINE); device.bind();
        PurpleSyncMagicDevices.Group group = new PurpleSyncMagicDevices.Group();
        PurpleSyncMagicDevices.Automatic port = new PurpleSyncMagicDevices.Automatic(device, group);
        port.runFirstUse = true;
        port.event(PurpleSyncAutoCore.Event.Enable);
        check(port.firstUses == 1 && port.firstUse != null && port.firstUse.result == PurpleSyncFirstUse.Result.Published && group.posts == 1,
                "automatic fresh: turning automatic sync on runs first use, which publishes the first record: " + (port.firstUse != null ? port.firstUse.result : null));
        check(port.enabled && port.failures.isEmpty() && new File(device.sync(), "auto.json").exists(), "automatic fresh: automatic sync is on for the new state");
        oneSave(port, PurpleSyncMagicDevices.THEIRS, "automatic fresh");
        port.engine.close();
    }

    /**
     * A fresh device with the defaults and automatic sync on, in an empty chat: first use waits, and the lifecycle
     * triggers read the chat for it at most once per 15 minutes. A device's first record arriving is then joined.
     */
    static void automaticWaits(File parent) throws Exception {
        PurpleSyncMagicDevices.Device waiting = new PurpleSyncMagicDevices.Device(parent, "magic-automatic-wait", "fixture-device-wait");
        PurpleSyncMagicDevices.Device other = new PurpleSyncMagicDevices.Device(parent, "magic-automatic-wait-other", "fixture-device-wait-other");
        waiting.local(null); waiting.bind();
        PurpleSyncMagicDevices.Group group = new PurpleSyncMagicDevices.Group();
        PurpleSyncMagicDevices.Automatic port = new PurpleSyncMagicDevices.Automatic(waiting, group);
        port.runFirstUse = true;
        port.event(PurpleSyncAutoCore.Event.Enable);
        check(port.firstUse != null && port.firstUse.result == PurpleSyncFirstUse.Result.Wait && port.enabled && group.posts == 0 && waiting.state() == null,
                "automatic wait: the defaults in an empty chat wait, automatic sync stays on: " + (port.firstUse != null ? port.firstUse.result : null));
        int scans = port.firstScans, checks = group.checks;
        for (PurpleSyncAutoCore.Event event : new PurpleSyncAutoCore.Event[] { PurpleSyncAutoCore.Event.SessionReady, PurpleSyncAutoCore.Event.Foreground, PurpleSyncAutoCore.Event.Unlock }) {
            port.clock += 60;
            port.event(event);
        }
        check(port.firstScans == scans && group.checks == checks, "automatic wait: activations within 15 minutes read nothing: " + (group.checks - checks));
        other.local(PurpleSyncMagicDevices.MINE); other.bind();
        PurpleSyncFirstUse.Outcome first = PurpleSyncFirstUseCases.firstUse(other, group, PurpleSyncCore.FirstUseChoice.None, "automatic wait: another device");
        check(first.result == PurpleSyncFirstUse.Result.Published, "automatic wait: a device with settings publishes: " + first.result);
        port.clock += PurpleSyncFirstUse.CHECK_SECONDS;
        port.event(PurpleSyncAutoCore.Event.Foreground);
        check(port.firstScans == scans + 1 && port.firstUse.result == PurpleSyncFirstUse.Result.Joined && Arrays.equals(PurpleSyncMagicDevices.MINE, waiting.local()),
                "automatic wait: 15 minutes later it reads again and joins: " + port.firstUse.result);
        check(port.failures.isEmpty(), "automatic wait: no exception: " + port.failures);
        port.engine.close();
    }

    /**
     * P2-4: a fresh customised device with automatic sync on, in an empty chat: first use joins a new space to
     * publish the first record, and Telegram refuses that post. First use reports it joined with the post unsent, and
     * automatic sync takes the file as a Save, so exactly one record goes out after all.
     */
    static void automaticUnsent(File parent) throws Exception {
        PurpleSyncMagicDevices.Device device = new PurpleSyncMagicDevices.Device(parent, "magic-automatic-unsent", "fixture-device-unsent");
        device.local(PurpleSyncMagicDevices.MINE); device.bind();
        PurpleSyncMagicDevices.Group group = new PurpleSyncMagicDevices.Group();
        group.refuseNextPost = true;
        PurpleSyncMagicDevices.Automatic port = new PurpleSyncMagicDevices.Automatic(device, group);
        port.runFirstUse = true;
        port.event(PurpleSyncAutoCore.Event.Enable);
        check(port.firstUse != null && port.firstUse.result == PurpleSyncFirstUse.Result.Joined && port.firstUse.unsent && "successor_unsent".equals(port.firstUse.reason),
                "automatic unsent: first use joined and reports its post unsent: " + (port.firstUse != null ? port.firstUse.result + " " + port.firstUse.reason : null));
        check(port.failures.isEmpty() && group.messages.size() == 1 && java.util.Arrays.equals(PurpleSyncMagicDevices.MINE, group.heads().get(0)),
                "automatic unsent: automatic sync published the file after all, once: " + group.messages.size() + " " + port.reason + " " + port.failures);
        port.engine.close();
    }

    /**
     * Two devices that shared a Saved Messages space bind the magic chat and turn automatic sync on. Each store moves
     * its state into the magic group on open (MoveSyncChat keeps the space); the first device's moved state publishes
     * into the empty group, the second finds that record in its own space: no first use, no question. Then a Save on
     * the second device writes once and the first device applies it automatically. Both folders hold a leftover of
     * an older build (sync-chat-setup.json), which the store accepts and the account recovery scan refuses: with the
     * scan applied in magic mode, automatic sync took the moved state for no state (AccountUnbound) and never
     * published it, the R3/R4 failure.
     */
    static void migration(File parent) throws Exception {
        PurpleSyncMagicDevices.Device one = new PurpleSyncMagicDevices.Device(parent, "magic-migrate-1", "fixture-device-m1");
        PurpleSyncMagicDevices.Device two = new PurpleSyncMagicDevices.Device(parent, "magic-migrate-2", "fixture-device-m2");
        Cloud saved = new Cloud(202);
        one.local(PurpleSyncMagicDevices.MINE);
        PurpleSyncFirstUseCases.publishSaved(one, saved, "migration: device 1 before magic");
        two.local(PurpleSyncMagicDevices.MINE);
        PurpleSyncFirstUseCases.joinSaved(two, saved, "migration: device 2 before magic");
        // Device 2 then has no settings file (the inherited defaults): its state must move all the same.
        two.local(null);
        String space = one.space();
        check(space != null && space.equals(two.space()), "migration: both devices share one Saved Messages space before magic");
        // Automatic sync was on before magic: each device has its automatic record, which the move carries.
        for (PurpleSyncMagicDevices.Device device : new PurpleSyncMagicDevices.Device[] { one, two }) {
            PurpleSyncMagicDevices.Automatic before = new PurpleSyncMagicDevices.Automatic(device, new PurpleSyncMagicDevices.Group(), PurpleSyncChatCore.Peer.self());
            before.event(PurpleSyncAutoCore.Event.Enable);
            check(before.enabled && new File(device.sync(), "auto.json").exists(), "migration: automatic sync was on before magic on " + device.id + ": " + before.reason);
            before.engine.close();
        }
        PurpleSyncMagicDevices.Group group = new PurpleSyncMagicDevices.Group();
        for (PurpleSyncMagicDevices.Device device : new PurpleSyncMagicDevices.Device[] { one, two }) {
            device.bind();
            write(new File(device.sync(), "sync-chat-setup.json"), "{}");
        }
        PurpleSyncMagicDevices.Automatic first = new PurpleSyncMagicDevices.Automatic(one, group);
        first.enabled = true;
        first.later();
        check(MAGIC.token().equals(PurpleSyncMagicPublishCases.stateChat(one.sync())) && space.equals(one.space()),
                "migration: device 1's state moved into the magic group and kept its space");
        check(group.messages.size() == 1 && group.spaces().equals(Collections.singleton(space)),
                "migration: device 1's moved state published into the magic group in the shared space: " + group.messages.size() + " " + group.spaces() + " reason=" + first.reason);
        check(first.firstUses == 0 && first.mismatches == 0 && first.failures.isEmpty() && first.reason != PurpleSyncAutoCore.Reason.AccountUnbound,
                "migration: device 1 ran no first use and is not AccountUnbound: " + first.reason + " " + first.failures);

        PurpleSyncMagicDevices.Automatic second = new PurpleSyncMagicDevices.Automatic(two, group);
        second.enabled = true;
        second.later();
        check(MAGIC.token().equals(PurpleSyncMagicPublishCases.stateChat(two.sync())) && space.equals(two.space()),
                "migration: device 2's state moved into the magic group and kept its space");
        check(group.spaces().equals(Collections.singleton(space)) && group.heads().size() == 1, "migration: the magic group holds one space and one head: " + group.spaces());
        check(second.firstUses == 0 && second.mismatches == 0 && second.failures.isEmpty() && second.reason != PurpleSyncAutoCore.Reason.AccountUnbound,
                "migration: device 2 ran no first use, met no other space and is not AccountUnbound: " + second.reason + " " + second.failures);
        for (PurpleSyncMagicDevices.Device device : new PurpleSyncMagicDevices.Device[] { one, two }) {
            check(!device.archived(), "migration: nothing was set aside");
            PurpleSyncFirstUseCases.settled(device, group, "migration: " + device.id);
        }
        check(Arrays.equals(PurpleSyncMagicDevices.MINE, one.local()) && two.local() == null, "migration: no settings file changed or appeared");
        oneSave(first, PurpleSyncMagicDevices.THEIRS, "migration: a Save on device 1");
        second.later();
        check(second.failures.isEmpty() && second.reason == PurpleSyncAutoCore.Reason.FileAbsent && two.local() == null,
                "migration: device 2, which has no settings file, holds for that alone: " + second.reason + " " + second.failures);
        first.engine.close();
        second.engine.close();
    }

    /**
     * An automatic pass that throws is not lost: the port gets the exception with its diag reason code, and automatic
     * sync then holds (core's SyncChatProblem) without a network call until the user resumes it.
     */
    static void automaticFailure(File parent) throws Exception {
        PurpleSyncMagicDevices.Device device = new PurpleSyncMagicDevices.Device(parent, "magic-automatic-failure", "fixture-device-fail");
        device.local(PurpleSyncMagicDevices.MINE); device.bind();
        PurpleSyncMagicDevices.Group group = new PurpleSyncMagicDevices.Group();
        ownRecord(device, group, "automatic failure");
        PurpleSyncMagicDevices.Automatic port = new PurpleSyncMagicDevices.Automatic(device, group);
        port.event(PurpleSyncAutoCore.Event.Enable);
        port.failCheck = new IllegalStateException("fixture failure inside the pass");
        port.later();
        check(port.failures.equals(java.util.Collections.singletonList("illegal_state")), "automatic failure: the exception reaches the port as a reason code: " + port.failures);
        int checks = port.checks;
        port.later();
        check(port.failures.size() == 1 && port.checks == checks && port.reason == PurpleSyncAutoCore.Reason.SyncChatProblem,
                "automatic failure: automatic sync holds with the problem and reads nothing: " + port.failures + " " + port.reason);
        port.failures.clear();
        port.failCheck = null;
        port.later();
        check(port.failures.isEmpty() && port.checks == checks + 1 && port.reason == PurpleSyncAutoCore.Reason.None, "automatic failure: once resumed it runs again: " + port.reason);
        port.engine.close();
    }

    /** A Frame whose files directory is reached through a link: parent/NAME-user0 -> parent/NAME-data. */
    static Frame linked(File parent, String name) throws Exception {
        File data = new File(parent, name + "-data"), user = new File(parent, name + "-user0");
        check(data.mkdir(), "exclusive linked fixture");
        Files.createSymbolicLink(user.toPath(), data.toPath());
        Frame frame = new Frame(user, "files", true);
        check(!frame.app.getCanonicalFile().equals(frame.app.getAbsoluteFile()),
                "the files directory is reached through a link, as /data/user/0 is on Android");
        PurpleSyncMagicPublishCases.field("started", true); PurpleSyncMagicPublishCases.field("ready", true);
        PurpleSyncMagicPublishCases.field("owner", A); PurpleSyncMagicPublishCases.field("global", A);
        return frame;
    }

    /** The account's sync folder passes all three checks, and the bound chat's store opens on it. */
    static PurpleAccountSyncCore.Result accepted(PurpleSyncFileContext context, String step) throws Exception {
        File sync = context.files.syncDirectory;
        PurpleAccountSyncStore.Result opened;
        try (PurpleAccountSyncStore store = PurpleAccountSyncStore.following(context, MAGIC)) {
            opened = store.open(true, 1, device(1));
            boolean none = !new File(sync, "state.json").exists();
            check(opened.status == (none ? PurpleAccountSyncStore.Status.Uninitialized : PurpleAccountSyncStore.Status.Ready),
                    step + ": the store opens" + (none ? " (no state yet)" : "") + ": " + opened.status);
            check(MAGIC.equals(store.chat()), step + ": on the magic chat");
        }
        check(PurpleSyncRetainedFiles.valid(sync), step + ": the retention check accepts the folder");
        PurpleSyncChatMoveStore move = new PurpleSyncChatMoveStore(sync, new PurpleAccountSyncStore(sync, PurpleAccountSyncStore.NATIVE), 1, context.origin.key,
                PurpleAccountBinding.read(1).token, hex64(), device(1), new File(PurpleAccountFiles.root(), "sync-chat/" + context.origin.key + ".json"), () -> true);
        try { move.open(); } catch (Exception e) { throw new AssertionError(step + ": the move store's known paths refuse the folder: " + e.getMessage(), e); }
        finally { move.close(); }
        check(true, step + ": the move store's known paths accept the folder");
        File state = new File(sync, "state.json");
        return state.exists() ? PurpleAccountSyncCore.inspectState(Files.readAllBytes(state.toPath())) : null;
    }
    static String hex64() { char[] value = new char[64]; Arrays.fill(value, 'a'); return new String(value); }

    static PurpleSyncRunner runner(Frame frame, PurpleSyncMagicPublishCases.Chat chat) {
        return new PurpleSyncRunner(1, 202, chat, new PurpleSyncMagicPublishCases.Magic(frame, new PurpleSyncFileContext(1)), Runnable::run, Runnable::run);
    }

    /** Check, keep the local file, publish: the "Publish changes" path. */
    static PurpleSyncPublisher.Result publish(PurpleSyncRunner runner, String step) {
        PurpleSyncRunner.Check shown = fresh(runner);
        check(shown.review.status == PurpleSyncCore.ReviewStatus.Ready && (shown.review.verdict == PurpleSyncCore.Verdict.LocalChanges || shown.review.verdict == PurpleSyncCore.Verdict.Empty),
                step + ": Check offers Publish changes: " + shown.review.status + " " + shown.review.verdict);
        PurpleSyncRunner.ApplyOutcome applied = apply(runner, shown, null);
        check(applied.result.status == PurpleSyncCore.ApplyStatus.Applied && applied.post != null, step + ": the post is prepared: " + applied.result.status
                + " " + applied.result.storeStatus);
        PurpleSyncPublisher.Result[] sent = { null };
        check(runner.publish(applied.post, value -> sent[0] = value) == PurpleSyncRunner.Start.Started, step + ": the publish starts");
        check(sent[0] != null, step + ": the publish finishes");
        return sent[0];
    }

    static void upToDate(PurpleSyncRunner runner, String step) {
        PurpleSyncRunner.Check shown = fresh(runner);
        check(shown.review.status == PurpleSyncCore.ReviewStatus.Ready && shown.review.verdict == PurpleSyncCore.Verdict.UpToDate,
                step + ": Check sees this device's own record as the head: " + shown.review.status + " " + shown.review.verdict);
    }

    static void confirmed(PurpleSyncPublisher.Result sent, String step, PurpleSyncFileContext context) {
        if (sent.status == PurpleSyncCore.PublishStatus.Confirmed) { check(true, step + ": the publish is confirmed"); return; }
        PurpleAccountSyncStore.Status reopened;
        try (PurpleAccountSyncStore store = PurpleAccountSyncStore.following(context, MAGIC)) { reopened = store.open(true, 1, device(1)).status; }
        check(false, step + ": the publish is confirmed: " + sent.status + " " + sent.storeStatus + "; a following store open: " + reopened);
    }

    /** fresh account -> bind -> first publish -> close -> reopen -> Check -> second publish -> restart -> reopen. */
    static void freshAccount(File parent) throws Exception {
        Frame frame = linked(parent, "magic-scenario");
        PurpleSyncFileContext context = new PurpleSyncFileContext(1);
        File sync = context.files.syncDirectory;
        directory(sync);
        write(new File(sync, "lock"), new byte[0]);
        byte[] binding = PurpleSyncMagicBindingFileCases.r2Binding();
        write(new File(sync, "chat.json"), binding);
        check(accepted(context, "bound") == null, "bound: no sync state yet");

        PurpleSyncMagicPublishCases.Chat chat = new PurpleSyncMagicPublishCases.Chat();
        PurpleSyncRunner runner = runner(frame, chat);
        confirmed(publish(runner, "first publish"), "first publish", context);
        runner.close();
        check(chat.posts == 1, "first publish: one record posted");
        PurpleAccountSyncCore.Result state = accepted(context, "first publish closed");
        check(state.isValid() && state.seq == 1 && state.pendingSeq == 0, "first publish: the store holds seq 1, nothing pending");
        rest(frame, context, chat, binding, "fresh", 1);
    }

    /** reopen -> Check -> second publish -> restart -> reopen, from a state whose head is this device's post. */
    static void rest(Frame frame, PurpleSyncFileContext context, PurpleSyncMagicPublishCases.Chat chat, byte[] binding, String run, long seq) throws Exception {
        File sync = context.files.syncDirectory;
        int posts = chat.posts;
        PurpleSyncRunner runner = runner(frame, chat);
        upToDate(runner, run + " reopened Check");
        runner.close();
        check(chat.posts == posts, run + " reopened Check: nothing posted");
        PurpleAccountSyncCore.Result state = accepted(context, run + " reopened Check");
        check(state.isValid() && state.seq == seq && state.pendingSeq == 0, run + " reopened Check: the store holds seq " + seq + ", nothing pending: "
                + state.seq + "/" + state.pendingSeq);

        write(frame.settings(1), REMOTE);
        runner = runner(frame, chat);
        confirmed(publish(runner, run + " second publish"), run + " second publish", context);
        runner.close();
        check(chat.posts == posts + 1, run + " second publish: one more record");
        state = accepted(context, run + " second publish closed");
        check(state.isValid() && state.seq == seq + 1 && state.pendingSeq == 0, run + " second publish: the store holds seq " + (seq + 1));

        // Restart: new file context, store, environment and runner objects over the same folder.
        PurpleSyncFileContext restarted = new PurpleSyncFileContext(1);
        accepted(restarted, run + " restart");
        runner = new PurpleSyncRunner(1, 202, chat, new PurpleSyncMagicPublishCases.Magic(frame, restarted), Runnable::run, Runnable::run);
        upToDate(runner, run + " restart Check");
        runner.close();
        accepted(restarted, run + " restart Check");
        check(chat.posts == posts + 1, run + " restart: nothing posted again");
        check(Arrays.equals(binding, Files.readAllBytes(new File(sync, "chat.json").toPath())), run + ": chat.json is untouched throughout");
    }

    /**
     * D1: a Saved Messages state moved into the bound magic chat beside an R2 chat.json, then the A4 publish: the
     * record is posted and the local commit fails. The fake transport makes the commit's retention read fail the same
     * way the link did (an unknown entry in retained-bases during the post), then removes the entry: the folder then
     * holds what the device's failed commit left.
     */
    static void fromD1(File parent) throws Exception {
        Frame frame = linked(parent, "magic-scenario-d1");
        frame.initialize(1);
        PurpleSyncRecoveryCases.publish(frame, 1);
        PurpleSyncFileContext context = new PurpleSyncFileContext(1);
        File sync = context.files.syncDirectory;
        byte[] binding = PurpleSyncMagicBindingFileCases.r2Binding();
        write(new File(sync, "chat.json"), binding);
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(sync, PurpleAccountSyncStore.NATIVE, PHYSICAL, PurpleEditorDirectories.NATIVE)) {
            check(store.open(true, 1, device(1)).status == PurpleAccountSyncStore.Status.Ready, "D1: the Saved Messages state opens");
            String fingerprint = PurpleSyncCore.settingsFingerprint(Files.readAllBytes(frame.settings(1).toPath()));
            check(store.moveTo(MAGIC, 202, PurpleAccountFiles.capture(1).key, fingerprint).status == PurpleAccountSyncStore.Status.Ready, "D1: moved to the bound chat");
        }
        PurpleAccountSyncCore.Result moved = accepted(context, "D1 moved");

        File poison = new File(sync, "retained-bases/a4-commit-refused");
        PurpleSyncMagicPublishCases.Chat chat = new PurpleSyncMagicPublishCases.Chat() {
            boolean refused;
            @Override public void post(byte[] staged, PostDone done) {
                if (!refused) try { Files.write(poison.toPath(), new byte[0]); refused = true; } catch (Exception e) { throw new AssertionError(e); }
                super.post(staged, done);
            }
        };
        PurpleSyncRunner runner = runner(frame, chat);
        PurpleSyncPublisher.Result sent = publish(runner, "D1 A4 publish");
        runner.close();
        check(chat.posts == 1 && sent.status == PurpleSyncCore.PublishStatus.StoreError && sent.storeStatus == PurpleAccountSyncStore.Status.InvalidRetention,
                "D1 A4 publish: posted, then the local commit fails as on the device: " + sent.status + " " + sent.storeStatus);
        Files.deleteIfExists(poison.toPath());
        // What the failed commit left: the moved state with the post staged, the staged record, the pinned base.
        check(Arrays.equals(new String[] { "chat.json", "lock", "pending", "retained-bases", "state.json" }, PurpleSyncRecoveryFiles.names(sync))
                && Arrays.equals(new String[] { "config.json" }, new File(sync, "pending").list())
                && new File(sync, "retained-bases").list().length == 1, "D1: the failed commit left a staged post and a pinned base");
        PurpleAccountSyncCore.Result left = accepted(context, "D1 after the failed commit");
        check(left.isValid() && left.seq == moved.seq + 1 && left.pendingSeq == left.seq, "D1: the post is staged, not confirmed: " + left.seq + "/" + left.pendingSeq);
        // A new process: the store opens as it is. The Check shows the post as unfinished (not "your other devices have
        // not seen this device's settings"), and Finish sending finds the staged record in the chat and confirms it
        // without sending it again (the core's ConfirmFound plan). From then on the record is this device's head.
        PurpleSyncFileContext restarted = new PurpleSyncFileContext(1);
        PurpleSyncRunner runner2 = new PurpleSyncRunner(1, 202, chat, new PurpleSyncMagicPublishCases.Magic(frame, restarted), Runnable::run, Runnable::run);
        PurpleSyncRunner.Check shown = fresh(runner2);
        check(shown.review.status == PurpleSyncCore.ReviewStatus.Ready && shown.review.verdict == PurpleSyncCore.Verdict.Pending,
                "D1 Check: the posted record is an unfinished post of this device: " + shown.review.status + " " + shown.review.verdict);
        PurpleSyncPublisher.Result[] finished = { null };
        check(runner2.publish(shown, PurpleSyncCore.PostRequest.finishSending(), value -> finished[0] = value) == PurpleSyncRunner.Start.Started,
                "D1 Finish sending starts");
        confirmed(finished[0], "D1 Finish sending", restarted);
        runner2.close();
        check(chat.posts == 1 && !new File(sync, "pending/config.json").exists(), "D1 Finish sending: nothing sent again, nothing pending");
        rest(frame, restarted, chat, binding, "D1", left.seq);
    }

    private PurpleSyncMagicScenarioCases() { }
}
