package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.telegram.messenger.ApplicationLoader;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;
import org.telegram.messenger.purple.PurpleSyncMagicDevices.Device;
import org.telegram.messenger.purple.PurpleSyncMagicDevices.Group;

/**
 * First use of the magic sync chat (R4b) on the real store, retention and known-files code, each device's files
 * directory behind a link, with only Telegram faked (PurpleSyncMagicDevices). The migration and the automatic passes
 * are in PurpleSyncMagicScenarioCases.
 *
 * <ul>
 * <li>Each first-use action from a device with no state: Wait, Publish, Join, JoinSilently with the same and with
 * different bytes, and Ask with both answers; an answered device is never asked again.</li>
 * <li>The race of two customised fresh devices into an empty chat: one head; the other device ends in Ask.</li>
 * <li>D1: Android's fresh-space post whose local commit failed, and a device whose state is in the old space. The
 * pending post is not disturbed; the other device asks once, and either answer settles it.</li>
 * </ul>
 * A first-use case runs {@link PurpleSyncFirstUse} the way both callers do: a Check on the runner, then first use with
 * no answer (automatic sync, PurpleSyncAuto.Runtime.runFirstUse) or with the user's answer (the Sync screen).
 */
final class PurpleSyncFirstUseCases {
    static final PurpleSyncChatCore.Peer MAGIC = PurpleSyncMagicDevices.MAGIC;
    /** The shipped defaults, spelled as a file: only a comment. */
    static final byte[] DEFAULTS = bytes("# nothing changed from the defaults\n");
    static final byte[] MINE = PurpleSyncMagicDevices.MINE, THEIRS = PurpleSyncMagicDevices.THEIRS, MINE_COMMENTED = PurpleSyncMagicDevices.MINE_COMMENTED;

    static void run(File parent) throws Exception {
        actions(parent);
        race(parent);
        d1(parent, PurpleSyncCore.FirstUseChoice.Synced);
        d1(parent, PurpleSyncCore.FirstUseChoice.ThisDevice);
        System.out.println("FIRST_USE_CASES=PASS");
    }

    // ---- helpers ----

    static PurpleSyncRunner.Check checked(PurpleSyncRunner runner, String step) {
        PurpleSyncRunner.CheckOutcome[] outcome = { null };
        check(runner.check(null, value -> outcome[0] = value) == PurpleSyncRunner.Start.Started, step + ": Check starts");
        check(outcome[0] != null && outcome[0].status == PurpleSyncRunner.CheckStatus.Finished && outcome[0].check != null, step + ": Check finishes");
        return outcome[0].check;
    }

    /** A Check, then first use with {@code choice}: what automatic sync (no answer) and the Sync screen do. */
    static PurpleSyncFirstUse.Outcome firstUse(Device device, Group chat, PurpleSyncCore.FirstUseChoice choice, String step) {
        PurpleSyncRunner runner = device.runner(chat, MAGIC);
        try {
            PurpleSyncRunner.Check shown = checked(runner, step);
            PurpleSyncFirstUse.Outcome[] outcome = { null };
            PurpleSyncFirstUse.run(runner, shown, choice, value -> outcome[0] = value);
            check(outcome[0] != null, step + ": first use finishes");
            return outcome[0];
        } finally { runner.close(); }
    }

    /** A later Check of a device: bound, Ready, nothing first use would act on. */
    static PurpleSyncRunner.Check settled(Device device, Group chat, String step) {
        PurpleSyncRunner runner = device.runner(chat, MAGIC);
        try {
            PurpleSyncRunner.Check shown = checked(runner, step);
            check(shown.review.bound && shown.review.status == PurpleSyncCore.ReviewStatus.Ready && !PurpleSyncFirstUse.applies(shown),
                    step + ": a later Check is Ready with no first use: " + shown.review.status + " " + shown.review.verdict + " bound=" + shown.review.bound);
            return shown;
        } finally { runner.close(); }
    }

    /** Join the Saved Messages space of {@code saved}: the pre-magic setup of a second device. */
    static void joinSaved(Device device, Cloud saved, String step) {
        PurpleSyncRunner runner = device.runner(saved, PurpleSyncChatCore.Peer.self());
        PurpleSyncRunner.Check shown = checked(runner, step);
        String key = shown.review.verdict == PurpleSyncCore.Verdict.Adopt ? null : remote(shown).key;
        PurpleSyncRunner.ApplyOutcome applied = apply(runner, shown, key);
        check(applied.result.status == PurpleSyncCore.ApplyStatus.Applied && applied.result.joined, step + ": joined the shared Saved Messages space: " + applied.result.status);
        runner.close();
    }

    /** Publish into Saved Messages from a device with no state: the pre-magic setup of the first device. */
    static void publishSaved(Device device, Cloud saved, String step) {
        PurpleSyncRunner runner = device.runner(saved, PurpleSyncChatCore.Peer.self());
        PurpleSyncRunner.Check shown = checked(runner, step);
        PurpleSyncRunner.ApplyOutcome applied = apply(runner, shown, null);
        check(applied.result.status == PurpleSyncCore.ApplyStatus.Applied && applied.post != null, step + ": the first Saved Messages post is prepared");
        PurpleSyncPublisher.Result[] sent = { null };
        check(runner.publish(applied.post, value -> sent[0] = value) == PurpleSyncRunner.Start.Started && sent[0] != null
                && sent[0].status == PurpleSyncCore.PublishStatus.Confirmed, step + ": published into Saved Messages");
        runner.close();
    }

    // ---- cases ----

    /** Each first-use action from a device with no state, and the one-time answers. */
    static void actions(File parent) throws Exception {
        // Wait: an empty chat and the shipped defaults (no file, and a file of comments only).
        for (byte[] defaults : new byte[][] { null, DEFAULTS, PurpleSyncMagicDevices.SPELLED_DEFAULTS }) {
            Device device = new Device(parent, "first-use-wait-" + (defaults == null ? "absent" : defaults == DEFAULTS ? "comments" : "spelled"), "fixture-device-w");
            device.use(); device.local(defaults); device.bind();
            Group chat = new Group();
            PurpleSyncFirstUse.Outcome outcome = firstUse(device, chat, PurpleSyncCore.FirstUseChoice.None, "wait");
            check(outcome.result == PurpleSyncFirstUse.Result.Wait && outcome.action == PurpleSyncCore.FirstUseAction.Wait, "wait: defaults in an empty chat wait: " + outcome.result + " " + outcome.reason);
            check(chat.posts == 0 && device.state() == null && Arrays.equals(defaults, device.local()), "wait: nothing posted, no state, the file untouched");
        }
        // Publish: an empty chat and customised settings publish the first record themselves.
        Device publisher = new Device(parent, "first-use-publish", "fixture-device-p");
        publisher.use(); publisher.local(MINE); publisher.bind();
        Group chat = new Group();
        PurpleSyncFirstUse.Outcome published = firstUse(publisher, chat, PurpleSyncCore.FirstUseChoice.None, "publish");
        check(published.result == PurpleSyncFirstUse.Result.Published && published.action == PurpleSyncCore.FirstUseAction.Publish,
                "publish: customised settings publish into an empty chat with no confirmation: " + published.result + " " + published.reason);
        check(chat.posts == 1 && chat.spaces().equals(Collections.singleton(publisher.space())), "publish: one record, in the new state's space");
        settled(publisher, chat, "publish");

        // Join: defaults join the chat and take its head (no file, and a file of comments only).
        for (byte[] defaults : new byte[][] { null, DEFAULTS, PurpleSyncMagicDevices.SPELLED_DEFAULTS }) {
            Device joiner = new Device(parent, "first-use-join-" + (defaults == null ? "absent" : defaults == DEFAULTS ? "comments" : "spelled"), "fixture-device-j");
            joiner.use(); joiner.local(defaults); joiner.bind();
            int posts = chat.posts;
            PurpleSyncFirstUse.Outcome joined = firstUse(joiner, chat, PurpleSyncCore.FirstUseChoice.None, "join");
            check(joined.result == PurpleSyncFirstUse.Result.Joined && joined.action == PurpleSyncCore.FirstUseAction.Join, "join: defaults join with no question: " + joined.result + " " + joined.reason);
            check(Arrays.equals(MINE, joiner.local()) && chat.posts == posts && publisher.space().equals(joiner.space()), "join: the chat's head is applied, nothing posted, the chat's space");
            check(defaults == null || joiner.frame.env(1).history().list().size() >= 1, "join: History keeps the replaced file");
            settled(joiner, chat, "join");
        }
        // JoinSilently, same bytes: joins and keeps the file; nothing is posted.
        Device same = new Device(parent, "first-use-silent-same", "fixture-device-s");
        same.use(); same.local(MINE); same.bind();
        int posts = chat.posts;
        PurpleSyncFirstUse.Outcome silent = firstUse(same, chat, PurpleSyncCore.FirstUseChoice.None, "silent same bytes");
        check(silent.result == PurpleSyncFirstUse.Result.Joined && silent.action == PurpleSyncCore.FirstUseAction.JoinSilently,
                "silent same bytes: joins silently: " + silent.result + " " + silent.reason);
        check(Arrays.equals(MINE, same.local()) && chat.posts == posts && publisher.space().equals(same.space()), "silent same bytes: file untouched, nothing posted");
        settled(same, chat, "silent same bytes");
        // JoinSilently, other bytes of the same meaning: keeps the file and publishes it as the successor.
        Device commented = new Device(parent, "first-use-silent-other", "fixture-device-c");
        commented.use(); commented.local(MINE_COMMENTED); commented.bind();
        PurpleSyncFirstUse.Outcome successor = firstUse(commented, chat, PurpleSyncCore.FirstUseChoice.None, "silent other bytes");
        check(successor.action == PurpleSyncCore.FirstUseAction.JoinSilently && successor.result == PurpleSyncFirstUse.Result.Published,
                "silent other bytes: joins silently and publishes the successor: " + successor.result + " " + successor.reason);
        check(Arrays.equals(MINE_COMMENTED, commented.local()) && chat.posts == posts + 1 && chat.spaces().size() == 1, "silent other bytes: file untouched, one successor in the one space");
        List<byte[]> heads = chat.heads();
        check(heads.size() == 1 && Arrays.equals(MINE_COMMENTED, heads.get(0)), "silent other bytes: the chat's one head is this file");
        settled(commented, chat, "silent other bytes");

        // Ask: other settings; nothing changes until answered, then each answer settles it for good.
        for (PurpleSyncCore.FirstUseChoice answer : new PurpleSyncCore.FirstUseChoice[] { PurpleSyncCore.FirstUseChoice.Synced, PurpleSyncCore.FirstUseChoice.ThisDevice }) {
            Device asked = new Device(parent, "first-use-ask-" + answer, "fixture-device-a");
            asked.use(); asked.local(THEIRS); asked.bind();
            int before = chat.posts;
            PurpleSyncFirstUse.Outcome question = firstUse(asked, chat, PurpleSyncCore.FirstUseChoice.None, "ask");
            check(question.result == PurpleSyncFirstUse.Result.Ask && question.action == PurpleSyncCore.FirstUseAction.Ask, "ask: other settings ask once: " + question.result + " " + question.reason);
            check(asked.state() == null && chat.posts == before && Arrays.equals(THEIRS, asked.local()), "ask: nothing changes until answered");
            PurpleSyncFirstUse.Outcome answered = firstUse(asked, chat, answer, "ask " + answer);
            if (answer == PurpleSyncCore.FirstUseChoice.Synced) {
                check(answered.result == PurpleSyncFirstUse.Result.Joined && answered.action == PurpleSyncCore.FirstUseAction.Join, "ask synced: joins: " + answered.result + " " + answered.reason);
                check(Arrays.equals(heads.get(0), asked.local()) && chat.posts == before, "ask synced: the chat's head replaced the file, nothing posted");
                check(asked.frame.env(1).history().list().size() >= 1, "ask synced: History keeps this device's old file");
            } else {
                check(answered.result == PurpleSyncFirstUse.Result.Published && answered.action == PurpleSyncCore.FirstUseAction.Publish, "ask this device: publishes: " + answered.result + " " + answered.reason);
                List<byte[]> now = chat.heads();
                check(Arrays.equals(THEIRS, asked.local()) && chat.posts == before + 1 && chat.spaces().size() == 1 && now.size() == 1 && Arrays.equals(THEIRS, now.get(0)),
                        "ask this device: this device's file is the chat's one head, as the successor");
                heads = now;
            }
            settled(asked, chat, "ask " + answer);
            PurpleSyncFirstUse.Outcome again = firstUse(asked, chat, PurpleSyncCore.FirstUseChoice.None, "ask " + answer + " again");
            check(again.result == PurpleSyncFirstUse.Result.NotFirstUse, "ask " + answer + ": never asked again: " + again.result);
        }
    }

    /**
     * Two customised fresh devices race into an empty chat: both Check while it is empty, the first publishes, the
     * second then acts on its stale Check. The chat keeps one head, and the second device ends in the one-time choice.
     */
    static void race(File parent) throws Exception {
        Device one = new Device(parent, "first-use-race-1", "fixture-device-r1");
        Device two = new Device(parent, "first-use-race-2", "fixture-device-r2");
        one.use(); one.local(MINE); one.bind();
        two.use(); two.local(THEIRS); two.bind();
        Group chat = new Group();
        PurpleSyncRunner second = two.runner(chat, MAGIC);
        PurpleSyncRunner.Check stale = checked(second, "race: device 2 Checks the empty chat");
        check(PurpleSyncFirstUse.plan(stale, PurpleSyncCore.FirstUseChoice.None).action == PurpleSyncCore.FirstUseAction.Publish, "race: device 2 plans to publish too");
        PurpleSyncFirstUse.Outcome won = firstUse(one, chat, PurpleSyncCore.FirstUseChoice.None, "race: device 1");
        check(won.result == PurpleSyncFirstUse.Result.Published, "race: device 1 publishes first: " + won.result);
        two.use();
        PurpleSyncFirstUse.Outcome[] lost = { null };
        PurpleSyncFirstUse.run(second, stale, PurpleSyncCore.FirstUseChoice.None, value -> lost[0] = value);
        second.close();
        check(lost[0] != null && lost[0].result == PurpleSyncFirstUse.Result.Ask && two.state() == null,
                "race: device 2's stale publish does not go out; it reads the chat again and asks, with no state of its own: " + (lost[0] != null ? lost[0].result + " " + lost[0].reason : "none"));
        List<byte[]> heads = chat.heads();
        check(chat.spaces().size() == 1 && heads.size() == 1 && Arrays.equals(MINE, heads.get(0)), "race: the chat keeps one space and one head: " + chat.spaces().size() + " " + heads.size());
        PurpleSyncFirstUse.Outcome asked = firstUse(two, chat, PurpleSyncCore.FirstUseChoice.None, "race: device 2 again");
        check(asked.result == PurpleSyncFirstUse.Result.Ask, "race: device 2 ends in the one-time choice: " + asked.result + " " + asked.reason);
        check(Arrays.equals(THEIRS, two.local()), "race: device 2's file is untouched");
    }

    /**
     * D1: the chat holds Android's record in a fresh space, whose local commit failed (the post is staged, as on the
     * device), and the other device holds a state of the old, pre-magic space with other settings. Android's pending
     * post is not disturbed: first use does not apply to it and Finish sending confirms it. The other device moves its
     * old state into the magic chat, finds only another space there, and asks once; {@code answer} sets the old state
     * aside and settles it, and a later Check of both devices is Ready with no question.
     */
    static void d1(File parent, PurpleSyncCore.FirstUseChoice answer) throws Exception {
        Device android = new Device(parent, "first-use-d1-android-" + answer, "fixture-device-android");
        Device desktop = new Device(parent, "first-use-d1-desktop-" + answer, "fixture-device-desktop");
        Cloud saved = new Cloud(202);
        desktop.use(); desktop.local(THEIRS);
        publishSaved(desktop, saved, "D1: the other device before magic");
        String old = desktop.space();
        desktop.bind();

        android.use(); android.local(MINE); android.bind();
        File poison = new File(android.sync(), "retained-bases/d1-commit-refused");
        Group chat = new Group() {
            boolean refused;
            @Override public void post(byte[] staged, PostDone done) {
                if (!refused) try { directory(poison.getParentFile()); Files.write(poison.toPath(), new byte[0]); refused = true; } catch (Exception e) { throw new AssertionError(e); }
                super.post(staged, done);
            }
        };
        PurpleSyncFirstUse.Outcome first = firstUse(android, chat, PurpleSyncCore.FirstUseChoice.None, "D1: Android's first publish");
        check(chat.posts == 1 && first.sent != null && first.sent.status == PurpleSyncCore.PublishStatus.StoreError,
                "D1: Android's record reached the chat and its local commit failed: " + first.result + " " + (first.sent != null ? first.sent.status + " " + first.sent.storeStatus : "none"));
        Files.deleteIfExists(poison.toPath());
        String fresh = android.space();
        PurpleAccountSyncCore.Result staged = android.state();
        check(fresh != null && !fresh.equals(old) && staged.pendingSeq != 0 && new File(android.sync(), "pending/config.json").exists(),
                "D1: Android holds a fresh space with its post staged");

        // Android: first use leaves the pending post alone, and Finish sending confirms it without a second send.
        PurpleSyncFirstUse.Outcome untouched = firstUse(android, chat, PurpleSyncCore.FirstUseChoice.None, "D1: Android Check");
        check(untouched.result == PurpleSyncFirstUse.Result.NotFirstUse && new File(android.sync(), "pending/config.json").exists() && chat.posts == 1,
                "D1: first use does not touch Android's pending post: " + untouched.result);
        PurpleSyncRunner runner = android.runner(chat, MAGIC);
        PurpleSyncRunner.Check shown = checked(runner, "D1: Android Check");
        check(shown.review.status == PurpleSyncCore.ReviewStatus.Ready && shown.review.verdict == PurpleSyncCore.Verdict.Pending, "D1: Android's Check shows the unfinished post: " + shown.review.verdict);
        PurpleSyncPublisher.Result[] finished = { null };
        check(runner.publish(shown, PurpleSyncCore.PostRequest.finishSending(), value -> finished[0] = value) == PurpleSyncRunner.Start.Started
                && finished[0] != null && finished[0].status == PurpleSyncCore.PublishStatus.Confirmed && chat.posts == 1, "D1: Finish sending confirms the found record");
        runner.close();

        // The other device: its old state moves in, meets only Android's space and asks once; nothing changes yet.
        PurpleSyncFirstUse.Outcome question = firstUse(desktop, chat, PurpleSyncCore.FirstUseChoice.None, "D1: the other device");
        check(question.result == PurpleSyncFirstUse.Result.Ask && !question.archived, "D1: the other device asks once: " + question.result + " " + question.reason);
        check(old.equals(desktop.space()) && Arrays.equals(THEIRS, desktop.local()) && !desktop.archived() && chat.posts == 1, "D1: nothing changed before the answer");
        PurpleSyncFirstUse.Outcome answered = firstUse(desktop, chat, answer, "D1: the other device answers " + answer);
        check(answered.archived && desktop.archived(), "D1 " + answer + ": the old state was set aside, not deleted");
        check(fresh.equals(desktop.space()), "D1 " + answer + ": the other device joined Android's space");
        if (answer == PurpleSyncCore.FirstUseChoice.Synced) {
            check(answered.result == PurpleSyncFirstUse.Result.Joined && Arrays.equals(MINE, desktop.local()) && chat.posts == 1,
                    "D1 synced: Android's settings applied, nothing posted: " + answered.result + " " + answered.reason);
            check(desktop.frame.env(1).history().list().size() >= 1, "D1 synced: History keeps the old file");
        } else {
            check(answered.result == PurpleSyncFirstUse.Result.Published && Arrays.equals(THEIRS, desktop.local()) && chat.posts == 2,
                    "D1 this device: its settings published as the successor: " + answered.result + " " + answered.reason);
            List<byte[]> heads = chat.heads();
            check(heads.size() == 1 && Arrays.equals(THEIRS, heads.get(0)) && chat.spaces().size() == 1, "D1 this device: one space, its file the one head");
        }
        check(new File(desktop.sync(), "chat.json").isFile(), "D1 " + answer + ": the chat binding stays");
        settled(desktop, chat, "D1 " + answer + " the other device");
        PurpleSyncRunner.Check later = settled(android, chat, "D1 " + answer + " Android");
        check(answer == PurpleSyncCore.FirstUseChoice.Synced ? later.review.verdict == PurpleSyncCore.Verdict.UpToDate : later.review.verdict == PurpleSyncCore.Verdict.UpdateReady,
                "D1 " + answer + ": Android sees " + (answer == PurpleSyncCore.FirstUseChoice.Synced ? "nothing new" : "the other device's update") + ": " + later.review.verdict);
    }

    private PurpleSyncFirstUseCases() { }
}
