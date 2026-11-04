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
        carryLeftovers(parent);
        twoInstalls(parent);
        disagreeingHeads(parent);
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

    /**
     * R1 by install D, then R2 by install A with R1 as parent: core lists a head per install, and R2 is the chat's
     * current head (core's headKey). A new login with the defaults joins R2, one with other settings asks once and
     * "Use the synced settings" takes R2, and one whose file means what R2 means joins silently, publishing its file
     * as R2's successor when its bytes differ.
     */
    static void twoInstalls(File parent) throws Exception {
        Device d = new Device(parent, "first-use-r1-install-d", "fixture-device-d");
        Device a = new Device(parent, "first-use-r2-install-a", "fixture-device-a");
        d.local(MINE); d.bind();
        a.local(THEIRS); a.bind();
        Group chat = new Group();
        PurpleSyncFirstUse.Outcome r1 = firstUse(d, chat, PurpleSyncCore.FirstUseChoice.None, "R1");
        check(r1.result == PurpleSyncFirstUse.Result.Published, "R1: install D publishes the first record: " + r1.result);
        PurpleSyncFirstUse.Outcome asked = firstUse(a, chat, PurpleSyncCore.FirstUseChoice.None, "R2 ask");
        check(asked.result == PurpleSyncFirstUse.Result.Ask, "R2: install A has other settings and is asked: " + asked.result);
        PurpleSyncFirstUse.Outcome r2 = firstUse(a, chat, PurpleSyncCore.FirstUseChoice.ThisDevice, "R2");
        check(r2.result == PurpleSyncFirstUse.Result.Published && chat.messages.size() == 2, "R2: install A publishes its settings as the successor: " + r2.result);
        PurpleAccountSyncCore.Result second = PurpleAccountSyncCore.inspectConfigRecord(chat.current().get(1));
        check(chat.heads().size() == 1 && Arrays.equals(THEIRS, chat.heads().get(0)) && chat.spaces().size() == 1,
                "R1/R2: one space, R2 the current settings (" + second.status + ")");
        // Join: the defaults take R2.
        Device join = new Device(parent, "first-use-r1r2-join", "fixture-device-r1r2-join");
        join.local(null); join.bind();
        PurpleSyncFirstUse.Outcome joined = firstUse(join, chat, PurpleSyncCore.FirstUseChoice.None, "R1/R2 join");
        check(joined.result == PurpleSyncFirstUse.Result.Joined && joined.action == PurpleSyncCore.FirstUseAction.Join && Arrays.equals(THEIRS, join.local()),
                "R1/R2 join: a new login with the defaults joins R2 automatically: " + joined.result + " " + joined.reason);
        settled(join, chat, "R1/R2 join");
        // Use the synced settings: other settings ask once, and the answer takes R2.
        Device synced = new Device(parent, "first-use-r1r2-synced", "fixture-device-r1r2-synced");
        synced.local(MINE_COMMENTED); synced.bind();
        PurpleSyncFirstUse.Outcome question = firstUse(synced, chat, PurpleSyncCore.FirstUseChoice.None, "R1/R2 ask");
        check(question.result == PurpleSyncFirstUse.Result.Ask, "R1/R2: other settings ask once: " + question.result);
        PurpleSyncFirstUse.Outcome took = firstUse(synced, chat, PurpleSyncCore.FirstUseChoice.Synced, "R1/R2 use the synced settings");
        check(took.result == PurpleSyncFirstUse.Result.Joined && Arrays.equals(THEIRS, synced.local()) && chat.messages.size() == 2,
                "R1/R2 use the synced settings: R2 replaces the file, nothing posted: " + took.result + " " + took.reason);
        settled(synced, chat, "R1/R2 use the synced settings");
        // Silent join: a file meaning what R2 means joins with no question (core 493d0db counts only the current
        // heads, so R1, which R2 succeeds, does not make the chat look split). The same bytes join and keep the file.
        Device silent = new Device(parent, "first-use-r1r2-silent", "fixture-device-r1r2-silent");
        silent.local(THEIRS); silent.bind();
        PurpleSyncFirstUse.Outcome quiet = firstUse(silent, chat, PurpleSyncCore.FirstUseChoice.None, "R1/R2 silent join");
        check(quiet.action == PurpleSyncCore.FirstUseAction.JoinSilently && quiet.result == PurpleSyncFirstUse.Result.Joined
                && Arrays.equals(THEIRS, silent.local()) && chat.messages.size() == 2 && chat.posts == 2,
                "R1/R2 silent join: the same settings as R2 join silently, nothing posted: " + quiet.action + " " + quiet.result + " " + quiet.reason + (quiet.applied != null ? " " + quiet.applied.status + " " + quiet.applied.setupStatus + " " + quiet.applied.storeStatus : ""));
        settled(silent, chat, "R1/R2 silent join");
        // Other bytes of R2's meaning: the file stays and is published as R2's successor, R2 its one parent.
        byte[] commented = bytes(new String(THEIRS, java.nio.charset.StandardCharsets.UTF_8) + "# this device's note\n");
        Device noted = new Device(parent, "first-use-r1r2-silent-noted", "fixture-device-r1r2-noted");
        noted.local(commented); noted.bind();
        PurpleSyncFirstUse.Outcome successor = firstUse(noted, chat, PurpleSyncCore.FirstUseChoice.None, "R1/R2 silent join, other bytes");
        check(successor.action == PurpleSyncCore.FirstUseAction.JoinSilently && successor.result == PurpleSyncFirstUse.Result.Published
                && Arrays.equals(commented, noted.local()) && chat.messages.size() == 3,
                "R1/R2 silent join, other bytes: the file stays and is published as the successor: " + successor.action + " " + successor.result + " " + successor.reason);
        check(chat.heads().size() == 1 && Arrays.equals(commented, chat.heads().get(0)) && chat.spaces().size() == 1,
                "R1/R2 silent join, other bytes: that file is the chat's one current head, in the one space");
        settled(noted, chat, "R1/R2 silent join, other bytes");
        System.out.println("FIRST_USE_R1R2=PASS");
    }

    /**
     * P1-1: a state of another space beside leftovers of a finished pre-magic carry. The store opened Ready because
     * the carry is terminal (PurpleSyncChatMoveStore.completed checks it against state.json's binding). Building a real
     * finished carry needs the carry suites, so the case plants the leftovers and marks them terminal as that open
     * does. Setting the state aside must take the leftovers too: left beside a store without a state they read as an
     * unfinished carry, and every later open, Check and automatic pass refused with CarryPending.
     */
    static void carryLeftovers(File parent) throws Exception {
        for (PurpleSyncCore.FirstUseChoice answer : new PurpleSyncCore.FirstUseChoice[] { PurpleSyncCore.FirstUseChoice.Synced, PurpleSyncCore.FirstUseChoice.ThisDevice }) {
            Device old = new Device(parent, "first-use-carry-" + answer, "fixture-device-carry");
            Device other = new Device(parent, "first-use-carry-other-" + answer, "fixture-device-carry-other");
            Cloud saved = new Cloud(202);
            old.local(THEIRS);
            publishSaved(old, saved, "carry: the old space before magic");
            old.bind();
            other.local(MINE); other.bind();
            Group chat = new Group();
            check(firstUse(other, chat, PurpleSyncCore.FirstUseChoice.None, "carry: another space first").result == PurpleSyncFirstUse.Result.Published, "carry: the chat holds another space");
            old.use();
            String[] leftovers = { "carry.json", "carry-cache.json", "carry-objects", "carry-history" };
            try (PurpleAccountSyncStore store = old.env(MAGIC).store()) {
                check(store.open(true, 1, old.id).status == PurpleAccountSyncStore.Status.Ready, "carry: the old state opens");
                for (String name : leftovers) {
                    File file = new File(old.sync(), name);
                    if (name.endsWith(".json")) write(file, "{}"); else directory(file);
                }
                java.lang.reflect.Field terminal = PurpleAccountSyncStore.class.getDeclaredField("terminalCarry");
                terminal.setAccessible(true); terminal.set(store, true);
                check(store.setAside().status == PurpleAccountSyncStore.Status.Uninitialized, "carry: the state is set aside with its carry leftovers");
            }
            for (String name : leftovers) check(!new File(old.sync(), name).exists(), "carry: " + name + " left the sync folder");
            check(old.archived() && new File(old.sync(), "chat.json").isFile(), "carry: the old state is archived, the binding stays");
            check(!PurpleSyncChatMoveStore.artifacts(old.sync()) && !PurpleAccountSyncStore.hasState(old.sync()), "carry: the folder holds no carry and no state");
            try (PurpleAccountSyncStore store = old.env(MAGIC).store()) {
                check(store.open(true, 1, old.id).status == PurpleAccountSyncStore.Status.Uninitialized, "carry: the store opens without a state, not CarryPending");
            }
            PurpleSyncFirstUse.Outcome acted = firstUse(old, chat, answer, "carry: " + answer);
            check(acted.result == (answer == PurpleSyncCore.FirstUseChoice.Synced ? PurpleSyncFirstUse.Result.Joined : PurpleSyncFirstUse.Result.Published),
                    "carry " + answer + ": first use acts after the set aside: " + acted.result + " " + acted.reason);
            settled(old, chat, "carry " + answer);
        }
        System.out.println("FIRST_USE_CARRY=PASS");
    }

    /**
     * Two disagreeing heads: R1 (install D), then R2 (install A) and R3 (install B) both with parent R1. A new device
     * with other settings is asked; "Use this device's settings" publishes with core's expectedParents, both heads,
     * so its record supersedes both. An answer kept for the split is dropped once the heads changed: the next device
     * that kept it plans again and is asked, instead of publishing over settings its answer never saw.
     */
    static void disagreeingHeads(File parent) throws Exception {
        Device d = new Device(parent, "first-use-split-d", "fixture-device-split-d");
        Device a = new Device(parent, "first-use-split-a", "fixture-device-split-a");
        Device b = new Device(parent, "first-use-split-b", "fixture-device-split-b");
        Device n = new Device(parent, "first-use-split-n", "fixture-device-split-n");
        Device m = new Device(parent, "first-use-split-m", "fixture-device-split-m");
        byte[] third = bytes("# B exact\nversion=2\n[last_seen]\nreasons_p=false\ntrade_p=false\n");
        byte[] fourth = bytes("# N exact\nversion=2\n[screen_time]\nretention_days=30\n");
        d.local(MINE); d.bind(); a.local(null); a.bind(); b.local(null); b.bind(); n.local(fourth); n.bind(); m.local(fourth); m.bind();
        Group chat = new Group();
        check(firstUse(d, chat, PurpleSyncCore.FirstUseChoice.None, "split R1").result == PurpleSyncFirstUse.Result.Published, "split: R1");
        for (Device joiner : new Device[] { a, b })
            check(firstUse(joiner, chat, PurpleSyncCore.FirstUseChoice.None, "split join").result == PurpleSyncFirstUse.Result.Joined, "split: " + joiner.id + " joins R1");
        // A and B each publish a change against R1: B's Check is taken before A's record lands.
        b.local(third);
        PurpleSyncRunner rb = b.runner(chat, MAGIC);
        PurpleSyncRunner.Check staleB = checked(rb, "split B");
        a.local(THEIRS);
        PurpleSyncRunner ra = a.runner(chat, MAGIC);
        PurpleSyncRunner.ApplyOutcome pa = apply(ra, checked(ra, "split A"), null);
        PurpleSyncPublisher.Result[] sa = { null };
        check(pa.post != null && ra.publish(pa.post, v -> sa[0] = v) == PurpleSyncRunner.Start.Started && sa[0].status == PurpleSyncCore.PublishStatus.Confirmed, "split: A publishes R2");
        ra.close();
        b.use();
        PurpleSyncRunner.ApplyOutcome pb = apply(rb, staleB, null);
        PurpleSyncPublisher.Result[] sb = { null };
        check(pb.post != null && rb.publish(pb.post, v -> sb[0] = v) == PurpleSyncRunner.Start.Started && sb[0] != null, "split: B publishes R3 against R1");
        rb.close();
        check(chat.heads().size() == 2 && chat.spaces().size() == 1, "split: two disagreeing current heads: " + chat.heads().size() + " (B " + sb[0].status + ")");
        // M is asked under the split and its answer is kept for those heads (as after a failed step).
        PurpleSyncFirstUse.Outcome mAsked = firstUse(m, chat, PurpleSyncCore.FirstUseChoice.None, "split M ask");
        check(mAsked.result == PurpleSyncFirstUse.Result.Ask, "split: M is asked: " + mAsked.result);
        m.use();
        PurpleSyncRunner rm = m.runner(chat, MAGIC);
        PurpleSyncCore.FirstUse seen = PurpleSyncFirstUse.plan(checked(rm, "split M seen"), PurpleSyncCore.FirstUseChoice.None);
        rm.close();
        check(seen.headKey.isEmpty() && seen.expectedParents.size() == 2, "split: core names both heads as expected parents, no head key: " + seen.expectedParents);
        // M's answer as kept for the split (the devices of these cases share one preferences store, so it is planted
        // only after N acted).
        String kept = PurpleSyncFirstUse.answer(PurpleSyncCore.FirstUseChoice.ThisDevice, seen);
        // N answers "Use this device's settings": its record supersedes both heads.
        check(firstUse(n, chat, PurpleSyncCore.FirstUseChoice.None, "split N ask").result == PurpleSyncFirstUse.Result.Ask, "split: N is asked");
        PurpleSyncFirstUse.Outcome nPublished = firstUse(n, chat, PurpleSyncCore.FirstUseChoice.ThisDevice, "split N this device");
        check(nPublished.result == PurpleSyncFirstUse.Result.Published && chat.heads().size() == 1 && Arrays.equals(fourth, chat.heads().get(0)),
                "split: \"Use this device's settings\" supersedes both heads: " + nPublished.result + " " + nPublished.reason + " heads=" + chat.heads().size());
        settled(n, chat, "split N");
        // M's kept answer was for the split; the heads changed, so it is dropped and M plans again.
        PurpleSyncFirstUse.answers(1).put(kept);
        int posts = chat.posts;
        PurpleSyncFirstUse.Outcome mAgain = firstUse(m, chat, PurpleSyncCore.FirstUseChoice.None, "split M after the heads changed");
        check(mAgain.result != PurpleSyncFirstUse.Result.Published && chat.posts == posts && PurpleSyncFirstUse.answers(1).get() == null,
                "split: a stale kept answer is dropped, nothing published over settings it never saw: " + mAgain.result + " " + mAgain.action);
        System.out.println("FIRST_USE_SPLIT=PASS");
    }

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
        // The answer outlives a failed step: the Check after setting the old state aside does not finish, and the
        // next first use, with no answer given, acts on the kept one instead of asking again.
        chat.failCheckAt = chat.checks + 2;
        PurpleSyncFirstUse.Outcome broken = firstUse(desktop, chat, answer, "D1: the other device answers " + answer + ", then the network drops");
        check(broken.result == PurpleSyncFirstUse.Result.Refused && "check_failed".equals(broken.reason) && broken.archived && desktop.state() == null,
                "D1 " + answer + ": the step after setting the old state aside failed with a reason code: " + broken.result + " " + broken.reason);
        PurpleSyncFirstUse.Outcome answered = firstUse(desktop, chat, PurpleSyncCore.FirstUseChoice.None, "D1: the other device after the failure");
        check(answered.action != PurpleSyncCore.FirstUseAction.Ask && answered.result != PurpleSyncFirstUse.Result.Ask, "D1 " + answer + ": the kept answer is used, nothing is asked again: " + answered.result);
        check(desktop.archived(), "D1 " + answer + ": the old state was set aside, not deleted");
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
