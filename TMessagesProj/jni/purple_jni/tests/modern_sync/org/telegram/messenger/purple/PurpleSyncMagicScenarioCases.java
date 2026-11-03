package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

/**
 * Magic mode end to end on the real Android store, retention and known-files code and the real file layout, with only
 * the transport faked (native check A4). The app's files directory is reached through a symbolic link, as Android's
 * /data/user/0 links to /data/data: the retained-bases check compared the whole canonical path with the absolute one,
 * so the first magic publish posted its record, failed its local commit with InvalidRetention and left a store that no
 * longer opened. Every step is followed by the three checks of the sync folder: the retention check, the store's open
 * (its known-files check) and the move store's known paths.
 */
final class PurpleSyncMagicScenarioCases {
    static final PurpleSyncChatCore.Peer MAGIC = PurpleSyncMagicPublishCases.MAGIC;

    static void run(File parent) throws Exception {
        freshAccount(parent);
        fromD1(parent);
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
