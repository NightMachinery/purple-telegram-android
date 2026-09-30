package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PurpleSyncExecutorsTest {
    private static final int ACCOUNT = 0;
    private static final long USER = 777;
    private static final long WAIT_MS = 60000;
    private static final long START_MILLIS = 1800000000000L;

    private static final byte[] T0 = utf8("version = 1\n# first\n");
    private static final byte[] T1 = utf8("version = 1\n# local edit\n");
    private static final byte[] TB = utf8("version = 1\n# from b\n");
    private static final byte[] TB2 = utf8("version = 1\n# from b, second\n");
    private static final byte[] TB3 = utf8("version = 1\n# from b, third\n");
    private static final byte[] TL = utf8("version = 1\n# local conflict\n");
    private static final byte[] TC = utf8("version = 1\n# from c\n");
    private static final byte[] NOT_UTF8 = { 'a', ' ', '=', ' ', '1', '\n',
        '#', ' ', (byte) 0xff, (byte) 0xfe, '\n' };

    private static int checks;
    private static int failures;
    private static String section = "";
    private static File scratch;

    private PurpleSyncExecutorsTest() {
    }

    private static void check(boolean ok) {
        ++checks;
        if (!ok) {
            ++failures;
            final StackTraceElement where = new Throwable().getStackTrace()[1];
            System.out.println("  FAIL  " + section + ":" + where.getLineNumber());
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fill(int size, char value) {
        final byte[] result = new byte[size];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String fingerprint(byte[] text) {
        try {
            return text.length + ":" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean sameSet(List<String> a, List<String> b) {
        return a != null && b != null && a.size() == b.size()
                && new HashSet<>(a).equals(new HashSet<>(b));
    }

    private static <T> T onUi(Callable<T> task) {
        try {
            return AndroidUtilities.UI.submit(task).get(WAIT_MS,
                    TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void settle() {
        for (int round = 0; round != 3; ++round) {
            onUi(() -> null);
            for (DispatchQueue queue : DispatchQueue.ALL) {
                final CompletableFuture<Void> drained = new CompletableFuture<>();
                queue.postRunnable(() -> drained.complete(null));
                try {
                    drained.get(WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        }
    }

    private static void reset() {
        UserConfig.activated = true;
        UserConfig.userId = USER;
        ConnectionsManager.currentTimeMillis = START_MILLIS;
        PurpleSettings.refuse = false;
        PurpleSettings.writeInstead = null;
        PurpleSettings.afterWrite = null;
        PurpleSettings.onSettingsFile = null;
    }

    private static final class Waiter<T> implements PurpleSyncRunner.Listener<T> {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void onResult(T result) {
            calls.incrementAndGet();
            future.complete(result);
        }

        T get() {
            try {
                return future.get(WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                throw new AssertionError("no result", e);
            }
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class Version {
        final String key;
        final byte[] record;

        Version(String key, byte[] record) {
            this.key = key;
            this.record = record;
        }
    }

    private static Version versionOf(byte[] record) {
        final PurpleAccountSyncCore.Result inspected =
                PurpleAccountSyncCore.inspectConfigRecord(record);
        check(inspected.isValid());
        return new Version(inspected.key, record);
    }

    private static final class Remote {
        final String install;
        final String device;
        final String platform;
        long seq;

        Remote(String install, String device, String platform) {
            this.install = install;
            this.device = device;
            this.platform = platform;
        }
    }

    private static Remote remote(char seed, String platform) {
        final PurpleAccountSyncCore.Result install =
                PurpleAccountSyncCore.formatInstallId(fill(16, seed));
        check(install.isValid());
        return new Remote(install.id,
                platform.toLowerCase(Locale.ROOT) + ":" + install.id, platform);
    }

    private static final class Cloud {
        private final List<Integer> ids = new ArrayList<>();
        private final List<byte[]> records = new ArrayList<>();
        private int nextId = 100;

        synchronized int add(byte[] bytes) {
            ids.add(nextId);
            records.add(bytes.clone());
            return nextId++;
        }

        synchronized int size() {
            return ids.size();
        }

        synchronized byte[] last() {
            return records.get(records.size() - 1);
        }

        synchronized int lastId() {
            return ids.get(ids.size() - 1);
        }

        synchronized PurpleSyncInventory inventory() {
            final PurpleSyncInventory.Builder builder =
                    new PurpleSyncInventory.Builder(USER);
            for (int i = 0; i != ids.size(); ++i) {
                builder.fetched(ids.get(i), ids.get(i) + 5000L, 1700000000L,
                        records.get(i));
            }
            return builder.build(true);
        }
    }

    private static Version post(Cloud cloud, Remote remote, String space,
            byte[] text, Version... parents) {
        final byte[][] records = new byte[parents.length][];
        for (int i = 0; i != parents.length; ++i) {
            records[i] = parents[i].record;
        }
        ++remote.seq;
        final PurpleAccountSyncCore.Result built =
                PurpleAccountSyncCore.buildConfigRecord(text, records, space,
                        remote.install, remote.device, remote.platform,
                        "Harness", remote.seq, 1700000000L + remote.seq);
        check(built.isValid());
        cloud.add(built.record);
        return versionOf(built.record);
    }

    private enum PostMode { Confirm, LoseReceipt, Fail, Refused }

    private static final class FakeTransport implements PurpleSyncTransport {
        final Cloud cloud;
        volatile boolean sendQueued;
        volatile boolean accountChanged;
        volatile boolean hold;
        volatile boolean deliverOnCancel = true;
        volatile PostMode mode = PostMode.Confirm;
        volatile Runnable onCheck;
        volatile Runnable onPost;
        final AtomicInteger checks = new AtomicInteger();
        final AtomicInteger posts = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final List<byte[]> posted = Collections.synchronizedList(new ArrayList<>());
        volatile boolean holdPost;
        private CheckDone heldDone;
        private CheckResult heldResult;
        private PostDone heldPost;

        FakeTransport(Cloud cloud) {
            this.cloud = cloud;
        }

        @Override
        public void check(Progress progress, CheckDone done) {
            checks.incrementAndGet();
            AndroidUtilities.UI.execute(() -> {
                final Runnable hook = onCheck;
                if (hook != null) {
                    hook.run();
                }
                progress.onProgress(Phase.Scanning, 1, 0);
                final CheckResult result = accountChanged
                        ? new CheckResult(CheckStatus.AccountChanged, null, false)
                        : new CheckResult(CheckStatus.Finished, cloud.inventory(),
                                sendQueued);
                synchronized (this) {
                    if (hold) {
                        heldDone = done;
                        heldResult = result;
                        return;
                    }
                }
                done.onCheckDone(result);
            });
        }

        void release() {
            AndroidUtilities.UI.execute(() -> {
                final CheckDone done;
                final CheckResult result;
                synchronized (this) {
                    done = heldDone;
                    result = heldResult;
                    heldDone = null;
                    heldResult = null;
                    hold = false;
                }
                if (done != null) {
                    done.onCheckDone(result);
                }
            });
        }

        @Override
        public void post(byte[] staged, PostDone done) {
            posts.incrementAndGet();
            posted.add(staged.clone());
            final PostMode current = mode;
            final int id = (current == PostMode.Confirm
                    || current == PostMode.LoseReceipt) ? cloud.add(staged) : 0;
            synchronized (this) {
                if (holdPost) {
                    heldPost = done;
                    return;
                }
            }
            AndroidUtilities.UI.execute(() -> {
                final Runnable hook = onPost;
                if (hook != null) {
                    hook.run();
                }
                switch (current) {
                    case Confirm:
                        done.onPostDone(new PostResult(PostStatus.Confirmed, id,
                                staged.clone()));
                        break;
                    case Refused:
                        done.onPostDone(new PostResult(PostStatus.NeedsReview, 0,
                                null));
                        break;
                    default:
                        done.onPostDone(new PostResult(PostStatus.OutcomeUnknown,
                                0, null));
                        break;
                }
            });
        }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
            if (!deliverOnCancel) {
                return;
            }
            AndroidUtilities.UI.execute(() -> {
                final CheckDone done;
                final PostDone posted;
                synchronized (this) {
                    done = heldDone;
                    heldDone = null;
                    heldResult = null;
                    posted = heldPost;
                    heldPost = null;
                }
                if (done != null) {
                    done.onCheckDone(new CheckResult(CheckStatus.Cancelled, null,
                            false));
                }
                if (posted != null) {
                    posted.onPostDone(new PostResult(PostStatus.Cancelled, 0,
                            null));
                }
            });
        }
    }

    private static final class Phone {
        final File files;
        final String device;
        final Map<String, String> prefs = new ConcurrentHashMap<>();

        Phone(String name, int seed) {
            files = new File(scratch, name);
            check(files.mkdirs());
            device = String.format(Locale.ROOT, "android-%08x", seed * 0x01010101);
        }

        void use() {
            ApplicationLoader.filesDir = files;
            PurpleDevice.current = device;
            MessagesController.VALUES = prefs;
        }

        File purple() {
            return new File(files, "purple");
        }

        File settings() {
            return new File(purple(), "settings.toml");
        }

        File syncRoot() {
            return new File(purple(), "sync");
        }

        File historyDir() {
            return new File(purple(), "sync-history");
        }

        void write(byte[] text) {
            try {
                Files.createDirectories(purple().toPath());
                Files.write(settings().toPath(), text);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        byte[] text() {
            try {
                return settings().isFile()
                        ? Files.readAllBytes(settings().toPath()) : null;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        byte[] state() {
            final File file = new File(syncRoot(), "state.json");
            try {
                return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        byte[] stage() {
            final File file = new File(new File(syncRoot(), "pending"), "config.json");
            try {
                return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        PurpleAccountSyncCore.Result inspect() {
            final PurpleAccountSyncCore.Result result =
                    PurpleAccountSyncCore.inspectState(state());
            check(result.isValid());
            return result;
        }

        PurpleSyncHistory store() {
            return new PurpleSyncHistory(historyDir(), PurpleSyncHistory.NATIVE,
                    System::currentTimeMillis);
        }

        List<PurpleSyncHistory.Entry> history() {
            return store().list();
        }

        PurpleSyncRunner runner(FakeTransport transport) {
            use();
            return onUi(() -> new PurpleSyncRunner(ACCOUNT, transport));
        }

        PurpleSyncRunner.Session session() {
            return new PurpleSyncRunner.Session(ACCOUNT, USER,
                    new PurpleSyncRunner.Platform());
        }
    }

    private static PurpleSyncRunner.CheckOutcome checkOutcome(
            PurpleSyncRunner runner) {
        final Waiter<PurpleSyncRunner.CheckOutcome> waiter = new Waiter<>();
        final PurpleSyncRunner.Start start = onUi(() -> runner.check(null, waiter));
        check(start == PurpleSyncRunner.Start.Started);
        return waiter.get();
    }

    private static PurpleSyncRunner.Check fresh(PurpleSyncRunner runner) {
        final PurpleSyncRunner.CheckOutcome outcome = checkOutcome(runner);
        check(outcome.status == PurpleSyncRunner.CheckStatus.Finished);
        check(outcome.check != null && outcome.check.review.isValid());
        return outcome.check;
    }

    private static PurpleSyncRunner.ApplyOutcome apply(PurpleSyncRunner runner,
            PurpleSyncRunner.Check shown, String key) {
        final Waiter<PurpleSyncRunner.ApplyOutcome> waiter = new Waiter<>();
        final PurpleSyncRunner.Start start =
                onUi(() -> runner.apply(shown, key, waiter));
        check(start == PurpleSyncRunner.Start.Started);
        final PurpleSyncRunner.ApplyOutcome outcome = waiter.get();
        check(outcome.failure.isValid());
        check((outcome.failure.kind == PurpleSyncCore.ApplyFailureKind.None)
                == (outcome.result.status == PurpleSyncCore.ApplyStatus.Applied));
        return outcome;
    }

    private static PurpleSyncPublisher.Result publish(PurpleSyncRunner runner,
            PurpleSyncRunner.Check fresh, PurpleSyncCore.PostRequest request) {
        final Waiter<PurpleSyncPublisher.Result> waiter = new Waiter<>();
        final PurpleSyncRunner.Start start =
                onUi(() -> runner.publish(fresh, request, waiter));
        check(start == PurpleSyncRunner.Start.Started);
        return waiter.get();
    }

    private static PurpleSyncPublisher.Result publish(PurpleSyncRunner runner,
            PurpleSyncRunner.PostTicket ticket) {
        final Waiter<PurpleSyncPublisher.Result> waiter = new Waiter<>();
        final PurpleSyncRunner.Start start =
                onUi(() -> runner.publish(ticket, waiter));
        check(start == PurpleSyncRunner.Start.Started);
        return waiter.get();
    }

    private static PurpleSyncRunner.RestoreOutcome undo(PurpleSyncRunner runner,
            PurpleSyncRunner.UndoOffer offer) {
        final Waiter<PurpleSyncRunner.RestoreOutcome> waiter = new Waiter<>();
        final PurpleSyncRunner.Start start = onUi(() -> runner.undo(offer, waiter));
        check(start == PurpleSyncRunner.Start.Started);
        return waiter.get();
    }

    private static PurpleSyncRunner.RestoreOutcome restore(
            PurpleSyncRunner runner, String id) {
        final Waiter<PurpleSyncRunner.RestoreOutcome> waiter = new Waiter<>();
        final PurpleSyncRunner.Start start = onUi(() -> runner.restore(id, waiter));
        check(start == PurpleSyncRunner.Start.Started);
        return waiter.get();
    }

    private static PurpleSyncRunner.UndoOffer undoOffer(PurpleSyncRunner runner) {
        return onUi(runner::undoOffer);
    }

    private static PurpleSyncPublisher.Result publishAction(
            PurpleSyncRunner runner) {
        final PurpleSyncRunner.Check clicked = fresh(runner);
        check(clicked.review.action == PurpleSyncCore.Action.Publish
                || clicked.review.action == PurpleSyncCore.Action.PublishChanges);
        final PurpleSyncRunner.ApplyOutcome kept = apply(runner, clicked, null);
        check(kept.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(kept.post != null && !kept.post.freshCheckFirst && kept.next == null);
        if (kept.post == null) {
            return null;
        }
        return publish(runner, kept.post);
    }

    private static PurpleSyncPublisher.Result share(PurpleSyncRunner runner,
            PurpleSyncRunner.ApplyOutcome chosen) {
        check(chosen.post != null && chosen.post.freshCheckFirst);
        check(onUi(() -> runner.publish(chosen.post,
                new Waiter<PurpleSyncPublisher.Result>()))
                == PurpleSyncRunner.Start.Stale);
        final PurpleSyncRunner.Check clicked = fresh(runner);
        return publish(runner, clicked, chosen.post.request);
    }

    private static PurpleSyncPublisher.Result finishSending(
            PurpleSyncRunner runner) {
        final PurpleSyncRunner.Check clicked = fresh(runner);
        check(clicked.review.verdict == PurpleSyncCore.Verdict.Pending);
        check(clicked.review.action == PurpleSyncCore.Action.FinishSending);
        return publish(runner, clicked, PurpleSyncCore.PostRequest.finishSending());
    }

    private static PurpleSyncHistory.Entry entry(Phone phone, String id) {
        for (PurpleSyncHistory.Entry entry : phone.history()) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private static void close(PurpleSyncRunner runner) {
        onUi(() -> {
            runner.close();
            return null;
        });
        settle();
    }

    private static String space(Phone phone) {
        return phone.inspect().space;
    }

    private static String base(Phone phone) {
        return phone.inspect().key;
    }

    private static void testEmptyPublishAndLocalChanges() {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("empty", 1);
        a.use();
        a.write(T0);
        final PurpleSyncRunner runner = a.runner(transport);
        check(onUi(runner::accountAvailable));
        final int writes = PurpleSettings.calls;

        final PurpleSyncRunner.Check empty = fresh(runner);
        check(empty.review.status == PurpleSyncCore.ReviewStatus.Ready);
        check(!empty.review.bound);
        check(empty.review.verdict == PurpleSyncCore.Verdict.Empty);
        check(empty.review.message == PurpleSyncCore.Message.EmptyUnbound);
        check(empty.review.action == PurpleSyncCore.Action.Publish);
        check(empty.adoptFailure == null && !empty.sendQueued);
        check(empty.records() == 0 && empty.bytes() == 0);
        check(Arrays.equals(empty.local.bytes, T0));
        check(!a.syncRoot().exists());

        final PurpleSyncRunner.ApplyOutcome invalid = apply(runner, empty, "1.x");
        check(invalid.result.status == PurpleSyncCore.ApplyStatus.InvalidChoice);
        check(invalid.failure.kind == PurpleSyncCore.ApplyFailureKind.NothingDone);
        check(invalid.next == null && invalid.post == null);
        check(a.state() == null);
        check(onUi(() -> runner.apply(empty, null,
                new Waiter<PurpleSyncRunner.ApplyOutcome>()))
                == PurpleSyncRunner.Start.Stale);

        final PurpleSyncRunner.Check again = fresh(runner);
        final PurpleSyncRunner.ApplyOutcome joined = apply(runner, again, null);
        check(joined.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(joined.result.joined && !joined.result.wroteFile);
        check(joined.result.setupStatus == PurpleSyncApply.SetupStatus.Ready);
        check(!joined.result.adopted && joined.result.publishNeeded);
        check(joined.result.fingerprint.equals(fingerprint(T0)));
        check(joined.next == null && joined.post != null);
        check(joined.post != null && !joined.post.freshCheckFirst);
        check(joined.post != null && joined.post.request.expectedParents.isEmpty());
        check(joined.post != null
                && fingerprint(T0).equals(joined.post.request.expectedFingerprint));
        check(a.state() != null && a.inspect().device.equals(a.device));
        check(a.state() != null && a.inspect().key.isEmpty());
        final PurpleSyncPublisher.Result first = publish(runner, joined.post);
        check(first.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(first.messageId == cloud.lastId() && first.posts == 1);
        check(transport.posts.get() == 1 && cloud.size() == 1);
        final Version v1 = versionOf(cloud.last());
        check(base(a).equals(v1.key));
        check(a.stage() == null);

        final PurpleSyncRunner.Check upToDate = fresh(runner);
        check(upToDate.review.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(upToDate.review.message == PurpleSyncCore.Message.UpToDateAlone);
        check(upToDate.review.action == PurpleSyncCore.Action.None);
        check(upToDate.records() == 1 && upToDate.bytes() == cloud.last().length);
        check(a.history().isEmpty());
        check(PurpleSettings.calls == writes);

        a.write(T1);
        final PurpleSyncRunner.Check changed = fresh(runner);
        check(changed.review.verdict == PurpleSyncCore.Verdict.LocalChanges);
        check(changed.review.action == PurpleSyncCore.Action.PublishChanges);
        final PurpleSyncRunner.ApplyOutcome keep = apply(runner, changed, null);
        check(keep.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(keep.post != null && !keep.post.freshCheckFirst);
        check(keep.post != null
                && keep.post.request.expectedParents.equals(List.of(v1.key)));
        final PurpleSyncPublisher.Result second = publish(runner, keep.post);
        check(second.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(transport.posts.get() == 2);
        check(!base(a).equals(v1.key));
        check(fresh(runner).review.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(a.history().isEmpty());
        check(PurpleSettings.calls == writes);
        close(runner);
    }

    private static void testJoinAdoptAndChoose() {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("join-a", 2);
        a.use();
        a.write(T0);
        final PurpleSyncRunner first = a.runner(transport);
        check(publishAction(first).status == PurpleSyncCore.PublishStatus.Confirmed);
        close(first);
        final Version v1 = versionOf(cloud.last());
        final String space = space(a);

        final Phone same = new Phone("join-same", 3);
        same.use();
        same.write(T0);
        final PurpleSyncRunner joiner = same.runner(transport);
        final PurpleSyncRunner.Check adopt = fresh(joiner);
        check(!adopt.review.bound);
        check(adopt.review.verdict == PurpleSyncCore.Verdict.Adopt);
        check(adopt.review.message == PurpleSyncCore.Message.AdoptUnbound);
        check(adopt.review.action == PurpleSyncCore.Action.Join);
        check(adopt.review.same.equals(List.of(v1.key)));
        check(!same.syncRoot().exists());
        final int posts = transport.posts.get();
        final PurpleSyncRunner.ApplyOutcome joined = apply(joiner, adopt, null);
        check(joined.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(joined.result.joined && joined.result.adopted);
        check(!joined.result.wroteFile && !joined.result.publishNeeded);
        check(joined.post == null && joined.next != null);
        check(joined.next != null
                && joined.next.review.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(space(same).equals(space) && base(same).equals(v1.key));
        check(transport.posts.get() == posts);
        check(Arrays.equals(same.text(), T0) && same.history().isEmpty());
        check(undoOffer(joiner) == null);
        close(joiner);

        final Phone picker = new Phone("join-pick", 4);
        picker.use();
        picker.write(TL);
        final PurpleSyncRunner pickRunner = picker.runner(transport);
        final PurpleSyncRunner.Check choose = fresh(pickRunner);
        check(choose.review.verdict == PurpleSyncCore.Verdict.Choose);
        check(choose.review.message == PurpleSyncCore.Message.ChooseUnbound);
        check(choose.review.action == PurpleSyncCore.Action.Choose);
        check(choose.review.offered.equals(List.of(v1.key)));
        check(choose.review.choices.size() == 2);
        final PurpleSyncRunner.ApplyOutcome picked = apply(pickRunner, choose, v1.key);
        check(picked.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(picked.result.joined && picked.result.wroteFile);
        check(picked.result.adopted && !picked.result.publishNeeded);
        check(picked.result.undoAvailable && picked.result.historyKept());
        check(picked.result.source != null
                && picked.result.source.key.equals(v1.key));
        check(Arrays.equals(picker.text(), T0));
        check(PurpleSettings.lastFromImport);
        check(AndroidUtilities.UI_THREAD.equals(PurpleSettings.lastThread));
        check(PurpleSyncApply.REASON_APPLY.equals(PurpleSettings.lastReason));
        final PurpleSyncHistory.Entry kept = entry(picker, picked.result.historyId);
        check(kept != null && kept.reason == PurpleSyncHistory.Reason.BeforeChoice);
        check(kept != null && kept.existed && kept.fingerprint.equals(fingerprint(TL)));
        check(kept != null && kept.label.equals(
                PurpleSyncApply.deviceLabel(picked.result.source.name)));
        check(kept != null && !kept.label.isEmpty());
        check(Arrays.equals(picker.store().read(picked.result.historyId), TL));
        check(picked.next != null
                && picked.next.review.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(base(picker).equals(v1.key) && space(picker).equals(space));
        final PurpleSyncRunner.UndoOffer offer = undoOffer(pickRunner);
        check(offer != null && offer.historyId.equals(picked.result.historyId));
        check(offer != null && offer.device != null);
        close(pickRunner);

        final Phone keeper = new Phone("join-keep", 5);
        keeper.use();
        keeper.write(TL);
        final PurpleSyncRunner keepRunner = keeper.runner(transport);
        final PurpleSyncRunner.Check keepChoice = fresh(keepRunner);
        check(keepChoice.review.verdict == PurpleSyncCore.Verdict.Choose);
        final PurpleSyncRunner.ApplyOutcome keptLocal =
                apply(keepRunner, keepChoice, null);
        check(keptLocal.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(keptLocal.result.joined && !keptLocal.result.wroteFile);
        check(keptLocal.result.publishNeeded);
        check(keptLocal.result.expectedParents.equals(List.of(v1.key)));
        check(keptLocal.next == null);
        check(keeper.history().isEmpty() && Arrays.equals(keeper.text(), TL));
        final int beforeShare = transport.posts.get();
        final PurpleSyncPublisher.Result shared = share(keepRunner, keptLocal);
        check(shared.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(transport.posts.get() == beforeShare + 1);
        final PurpleSyncRunner.Check afterShare = fresh(keepRunner);
        check(afterShare.review.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(undoOffer(keepRunner) == null);
        close(keepRunner);

        final Phone absent = new Phone("join-absent", 6);
        absent.use();
        final PurpleSyncRunner absentRunner = absent.runner(transport);
        final PurpleSyncRunner.Check absentChoice = fresh(absentRunner);
        check(absentChoice.review.verdict == PurpleSyncCore.Verdict.Choose);
        check(absentChoice.local.status == PurpleSyncSettingsFile.Status.Absent);
        check(absentChoice.review.offered.size() == 1);
        final String head = absentChoice.review.offered.get(0);
        final PurpleSyncRunner.ApplyOutcome filled =
                apply(absentRunner, absentChoice, head);
        check(filled.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(filled.result.wroteFile && !filled.result.undoAvailable);
        check(filled.result.historyKept());
        final PurpleSyncHistory.Entry none = entry(absent, filled.result.historyId);
        check(none != null && !none.existed && none.size == 0);
        check(undoOffer(absentRunner) == null);
        check(absent.text() != null);
        close(absentRunner);
    }

    private static void testUpdateUndoAndSilentAdopt() {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("update", 7);
        a.use();
        a.write(T0);
        final PurpleSyncRunner runner = a.runner(transport);
        check(publishAction(runner).status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version v1 = versionOf(cloud.last());
        final String space = space(a);
        final Remote b = remote('b', "Android");
        final Version b1 = post(cloud, b, space, TB, v1);

        final PurpleSyncRunner.Check update = fresh(runner);
        check(update.review.verdict == PurpleSyncCore.Verdict.UpdateReady);
        check(update.review.action == PurpleSyncCore.Action.ReviewUpdate);
        check(update.review.offered.equals(List.of(b1.key)));
        final int writes = PurpleSettings.calls;
        final PurpleSyncRunner.ApplyOutcome applied = apply(runner, update, b1.key);
        check(applied.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(applied.result.wroteFile && applied.result.adopted);
        check(!applied.result.publishNeeded && !applied.result.joined);
        check(applied.result.undoAvailable);
        check(applied.result.nextVerdict == PurpleSyncCore.Verdict.UpToDate);
        check(applied.post == null && applied.next != null);
        check(applied.next != null
                && applied.next.review.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(Arrays.equals(a.text(), TB));
        check(base(a).equals(b1.key));
        check(PurpleSettings.calls == writes + 1);
        check(PurpleSettings.lastFromImport);
        check(AndroidUtilities.UI_THREAD.equals(PurpleSettings.lastThread));
        final PurpleSyncHistory.Entry before = entry(a, applied.result.historyId);
        check(before != null && before.reason == PurpleSyncHistory.Reason.BeforeUpdate);
        check(before != null && before.versionKey.equals(v1.key));
        check(before != null && before.fingerprint.equals(fingerprint(T0)));
        check(Arrays.equals(a.store().read(applied.result.historyId), T0));
        check(before != null && before.label.equals(
                PurpleSyncApply.deviceLabel(applied.result.source.name)));

        final PurpleSyncRunner.UndoOffer offer = undoOffer(runner);
        check(offer != null && offer.historyId.equals(applied.result.historyId));
        check(offer != null && offer.device != null
                && offer.device.shortId.equals(applied.result.source.name.shortId));
        check(onUi(() -> runner.undo(new PurpleSyncRunner.UndoOffer("x", null),
                new Waiter<PurpleSyncRunner.RestoreOutcome>()))
                == PurpleSyncRunner.Start.Stale);
        final PurpleSyncRunner.RestoreOutcome undone = undo(runner, offer);
        check(undone.result.status == PurpleSyncCore.RestoreStatus.Restored);
        check(undone.undoFinished);
        check(undone.result.fingerprint.equals(fingerprint(T0)));
        check(Arrays.equals(a.text(), T0));
        check(!PurpleSettings.lastFromImport);
        check(PurpleSyncApply.REASON_UNDO.equals(PurpleSettings.lastReason));
        check(AndroidUtilities.UI_THREAD.equals(PurpleSettings.lastThread));
        check(undoOffer(runner) == null);
        final PurpleSyncHistory.Entry undoEntry = entry(a, undone.result.historyId);
        check(undoEntry != null && undoEntry.reason == PurpleSyncHistory.Reason.BeforeUndo);
        check(undoEntry != null && undoEntry.label.equals(applied.result.historyId));
        check(undoEntry != null && undoEntry.versionKey.isEmpty());
        check(Arrays.equals(a.store().read(undone.result.historyId), TB));
        check(undone.next != null
                && undone.next.review.verdict == PurpleSyncCore.Verdict.LocalChanges);

        check(publishAction(runner).status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version v2 = versionOf(cloud.last());
        check(fresh(runner).review.verdict == PurpleSyncCore.Verdict.UpToDate);

        final Version b2 = post(cloud, b, space, TB2, v2);
        a.write(TB2);
        final PurpleSyncApply.Reviewed raw =
                PurpleSyncApply.review(a.session(), cloud.inventory());
        check(raw.review.verdict == PurpleSyncCore.Verdict.Adopt);
        check(raw.review.message == PurpleSyncCore.Message.AdoptBound);
        final int historyCount = a.history().size();
        final int adoptWrites = PurpleSettings.calls;
        final int adoptPosts = transport.posts.get();
        final PurpleSyncRunner.Check silent = fresh(runner);
        check(silent.review.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(silent.adoptFailure == null);
        check(base(a).equals(b2.key));
        check(a.history().size() == historyCount);
        check(PurpleSettings.calls == adoptWrites);
        check(transport.posts.get() == adoptPosts);
        close(runner);
    }

    private static void testChooseAndConflictBound() {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("bound-choose", 8);
        a.use();
        a.write(T0);
        final PurpleSyncRunner runner = a.runner(transport);
        check(publishAction(runner).status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version v1 = versionOf(cloud.last());
        final String space = space(a);

        final Remote b = remote('b', "macOS");
        final Version b1 = post(cloud, b, space, TB);
        final PurpleSyncRunner.Check choose = fresh(runner);
        check(choose.review.verdict == PurpleSyncCore.Verdict.Choose);
        check(choose.review.message == PurpleSyncCore.Message.ChooseBound);
        final PurpleSyncRunner.ApplyOutcome picked = apply(runner, choose, b1.key);
        check(picked.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(picked.result.wroteFile && picked.result.adopted);
        check(picked.result.publishNeeded && !picked.result.otherVersionsRemain);
        check(sameSet(picked.result.expectedParents, List.of(b1.key, v1.key)));
        check(picked.next == null && picked.post != null);
        check(Arrays.equals(a.text(), TB));
        final PurpleSyncPublisher.Result repick = share(runner, picked);
        check(repick.status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version resolution = versionOf(cloud.last());
        check(fresh(runner).review.verdict == PurpleSyncCore.Verdict.UpToDate);

        a.write(TL);
        final Version b2 = post(cloud, b, space, TB2, resolution);
        final PurpleSyncRunner.Check conflict = fresh(runner);
        check(conflict.review.verdict == PurpleSyncCore.Verdict.Conflict);
        check(conflict.review.message == PurpleSyncCore.Message.ConflictConcurrent);
        check(conflict.review.offered.equals(List.of(b2.key)));
        final int historyCount = a.history().size();
        final PurpleSyncRunner.ApplyOutcome kept = apply(runner, conflict, null);
        check(kept.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(!kept.result.wroteFile && kept.result.publishNeeded);
        check(kept.result.expectedParents.equals(List.of(b2.key)));
        check(a.history().size() == historyCount && Arrays.equals(a.text(), TL));
        final PurpleSyncPublisher.Result keptPost = share(runner, kept);
        check(keptPost.status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version mine = versionOf(cloud.last());
        check(fresh(runner).review.verdict == PurpleSyncCore.Verdict.UpToDate);

        final Remote c = remote('c', "Windows");
        final Version b3 = post(cloud, b, space, TB3, mine);
        final Version c1 = post(cloud, c, space, TC, mine);
        final PurpleSyncRunner.Check split = fresh(runner);
        check(split.review.verdict == PurpleSyncCore.Verdict.Conflict);
        check(split.review.message == PurpleSyncCore.Message.ConflictSplitBound);
        check(split.review.offered.size() == 2);
        final PurpleSyncRunner.ApplyOutcome pickC = apply(runner, split, c1.key);
        check(pickC.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(pickC.result.wroteFile && pickC.result.otherVersionsRemain);
        check(pickC.result.publishNeeded);
        check(sameSet(pickC.result.expectedParents, List.of(c1.key, b3.key)));
        check(Arrays.equals(a.text(), TC));
        final PurpleSyncHistory.Entry saved = entry(a, pickC.result.historyId);
        check(saved != null && saved.reason == PurpleSyncHistory.Reason.BeforeChoice);
        check(Arrays.equals(a.store().read(pickC.result.historyId), TL));
        final PurpleSyncPublisher.Result resolved = share(runner, pickC);
        check(resolved.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(fresh(runner).review.verdict == PurpleSyncCore.Verdict.UpToDate);
        close(runner);
    }

    private static void testWriteOrderAndFailures() throws Exception {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("failures", 9);
        a.use();
        a.write(T0);
        final PurpleSyncRunner runner = a.runner(transport);
        check(publishAction(runner).status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version v1 = versionOf(cloud.last());
        final String space = space(a);
        final Remote b = remote('b', "Linux");
        final Version b1 = post(cloud, b, space, TB, v1);
        final byte[] stateBefore = a.state();

        Files.createDirectories(a.historyDir().toPath());
        Files.setPosixFilePermissions(a.historyDir().toPath(),
                PosixFilePermissions.fromString("rwxr-xr-x"));
        final int writes = PurpleSettings.calls;
        final PurpleSyncRunner.ApplyOutcome historyFailed =
                apply(runner, fresh(runner), b1.key);
        check(historyFailed.result.status == PurpleSyncCore.ApplyStatus.HistoryError);
        check(!historyFailed.result.wroteFile && !historyFailed.result.historyKept());
        check(historyFailed.failure.kind == PurpleSyncCore.ApplyFailureKind.NothingDone);
        check(historyFailed.next == null && historyFailed.post == null);
        check(Arrays.equals(a.text(), T0));
        check(Arrays.equals(a.state(), stateBefore));
        check(PurpleSettings.calls == writes);
        check(undoOffer(runner) == null);
        Files.setPosixFilePermissions(a.historyDir().toPath(),
                PosixFilePermissions.fromString("rwx------"));
        check(a.history().isEmpty());

        final PurpleSyncRunner.Check stale = fresh(runner);
        a.write(T1);
        final PurpleSyncRunner.ApplyOutcome recheck = apply(runner, stale, b1.key);
        check(recheck.result.status == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(!recheck.result.wroteFile && !recheck.result.historyKept());
        check(Arrays.equals(a.text(), T1));
        check(Arrays.equals(a.state(), stateBefore));
        check(a.history().isEmpty());
        a.write(T0);

        final PurpleSyncRunner.Check raced = fresh(runner);
        final AtomicInteger calls = new AtomicInteger();
        PurpleSettings.onSettingsFile = () -> {
            if (calls.incrementAndGet() == 2) {
                a.write(T1);
            }
        };
        final PurpleSyncRunner.ApplyOutcome changed = apply(runner, raced, b1.key);
        PurpleSettings.onSettingsFile = null;
        check(changed.result.status == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(!changed.result.wroteFile && changed.result.historyKept());
        check(Arrays.equals(a.text(), T1));
        check(Arrays.equals(a.state(), stateBefore));
        check(PurpleSettings.calls == writes);
        check(Arrays.equals(a.store().read(changed.result.historyId), T0));
        a.write(T0);

        PurpleSettings.refuse = true;
        final PurpleSyncRunner.ApplyOutcome refused = apply(runner, fresh(runner), b1.key);
        PurpleSettings.refuse = false;
        check(refused.result.status == PurpleSyncCore.ApplyStatus.WriteError);
        check(!refused.result.wroteFile && refused.result.historyKept());
        check(refused.failure.kind == PurpleSyncCore.ApplyFailureKind.NothingDone);
        check(Arrays.equals(a.text(), T0));
        check(Arrays.equals(a.state(), stateBefore));

        PurpleSettings.writeInstead = T1;
        final PurpleSyncRunner.ApplyOutcome mismatch = apply(runner, fresh(runner), b1.key);
        PurpleSettings.writeInstead = null;
        check(mismatch.result.status == PurpleSyncCore.ApplyStatus.WriteError);
        check(mismatch.result.wroteFile && mismatch.result.undoAvailable);
        check(mismatch.failure.kind
                == PurpleSyncCore.ApplyFailureKind.WrittenNotReadBack);
        check(Arrays.equals(a.state(), stateBefore));
        check(undoOffer(runner) != null);
        a.write(T0);

        final File blocker = new File(a.syncRoot(), "state.json.new");
        PurpleSettings.afterWrite = () -> check(blocker.mkdir());
        final PurpleSyncRunner.ApplyOutcome crashed = apply(runner, fresh(runner), b1.key);
        PurpleSettings.afterWrite = null;
        check(blocker.delete());
        check(crashed.result.status == PurpleSyncCore.ApplyStatus.StoreError);
        check(crashed.result.storeStatus == PurpleAccountSyncStore.Status.IoError);
        check(crashed.result.wroteFile && crashed.result.undoAvailable);
        check(!crashed.result.otherVersionsRemain);
        check(crashed.failure.kind
                == PurpleSyncCore.ApplyFailureKind.WrittenStateNotSaved);
        check(Arrays.equals(a.text(), TB));
        check(Arrays.equals(a.state(), stateBefore));
        final PurpleSyncRunner.UndoOffer crashOffer = undoOffer(runner);
        check(crashOffer != null
                && crashOffer.historyId.equals(crashed.result.historyId));
        final PurpleSyncApply.Reviewed afterCrash =
                PurpleSyncApply.review(a.session(), cloud.inventory());
        check(afterCrash.review.verdict == PurpleSyncCore.Verdict.Adopt);
        check(afterCrash.review.same.equals(List.of(b1.key)));
        final PurpleSyncRunner.Check recovered = fresh(runner);
        check(recovered.review.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(recovered.adoptFailure == null);
        check(base(a).equals(b1.key));
        check(undoOffer(runner) != null);
        close(runner);
    }

    private static void testJoinFailures() throws Exception {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("join-fail-a", 10);
        a.use();
        a.write(T0);
        final PurpleSyncRunner first = a.runner(transport);
        check(publishAction(first).status == PurpleSyncCore.PublishStatus.Confirmed);
        close(first);
        final Version v1 = versionOf(cloud.last());

        final Phone e = new Phone("join-fail-e", 11);
        e.use();
        e.write(TL);
        Files.createDirectories(e.historyDir().toPath());
        Files.setPosixFilePermissions(e.historyDir().toPath(),
                PosixFilePermissions.fromString("rwxr-xr-x"));
        final PurpleSyncRunner runner = e.runner(transport);
        final PurpleSyncRunner.Check choose = fresh(runner);
        check(choose.review.verdict == PurpleSyncCore.Verdict.Choose && !choose.review.bound);
        final PurpleSyncRunner.ApplyOutcome half = apply(runner, choose, v1.key);
        check(half.result.status == PurpleSyncCore.ApplyStatus.HistoryError);
        check(half.result.joined && !half.result.wroteFile);
        check(half.failure.kind == PurpleSyncCore.ApplyFailureKind.JoinedNotWritten);
        check(half.failure.joined);
        check(e.state() != null && space(e).equals(space(a)));
        check(Arrays.equals(e.text(), TL));
        Files.setPosixFilePermissions(e.historyDir().toPath(),
                PosixFilePermissions.fromString("rwx------"));
        final PurpleSyncRunner.Check bound = fresh(runner);
        check(bound.review.bound);
        check(bound.review.verdict == PurpleSyncCore.Verdict.Choose);
        check(bound.review.message == PurpleSyncCore.Message.ChooseBound);
        final PurpleSyncRunner.ApplyOutcome finished = apply(runner, bound, v1.key);
        check(finished.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(!finished.result.joined && finished.result.wroteFile);
        check(Arrays.equals(e.text(), T0));
        close(runner);

        final Cloud empty = new Cloud();
        final FakeTransport emptyTransport = new FakeTransport(empty);
        final Phone f = new Phone("join-fail-f", 12);
        f.use();
        f.write(T0);
        final PurpleSyncRunner fresher = f.runner(emptyTransport);
        ConnectionsManager.currentTimeMillis = 0;
        final PurpleSyncRunner.Check emptyCheck = fresh(fresher);
        check(emptyCheck.review.verdict == PurpleSyncCore.Verdict.Empty);
        final PurpleSyncRunner.ApplyOutcome noTime = apply(fresher, emptyCheck, null);
        check(noTime.result.status == PurpleSyncCore.ApplyStatus.SetupFailed);
        check(noTime.result.setupStatus
                == PurpleSyncApply.SetupStatus.InvalidGeneratedState);
        check(!noTime.result.joined);
        check(f.state() == null && !PurpleAccountSyncStore.hasState(f.syncRoot()));
        ConnectionsManager.currentTimeMillis = START_MILLIS;
        final PurpleSyncRunner.Check retry = fresh(fresher);
        check(!retry.review.bound);
        final PurpleSyncRunner.ApplyOutcome joined = apply(fresher, retry, null);
        check(joined.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(joined.result.joined && f.state() != null);
        close(fresher);
    }

    private static void testFinishSending() {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("finish", 13);
        a.use();
        a.write(T0);
        final PurpleSyncRunner runner = a.runner(transport);
        check(publishAction(runner).status == PurpleSyncCore.PublishStatus.Confirmed);

        a.write(T1);
        transport.mode = PostMode.Fail;
        final int posts = transport.posts.get();
        final PurpleSyncPublisher.Result failed = publishAction(runner);
        transport.mode = PostMode.Confirm;
        check(failed.status == PurpleSyncCore.PublishStatus.OutcomeUnknown);
        check(transport.posts.get() == posts + 1 && cloud.size() == 1);
        final byte[] staged = a.stage();
        check(staged != null && Arrays.equals(staged,
                transport.posted.get(transport.posted.size() - 1)));
        check(a.inspect().pendingSeq != 0);

        final PurpleSyncRunner.Check pending = fresh(runner);
        check(pending.review.verdict == PurpleSyncCore.Verdict.Pending);
        check(pending.review.action == PurpleSyncCore.Action.FinishSending);
        check(pending.review.pending);
        final PurpleSyncPublisher.Result refused = publish(runner, pending,
                PurpleSyncCore.PostRequest.newContent(fingerprint(T1), List.of()));
        check(refused.status == PurpleSyncCore.PublishStatus.NeedsReview);
        check(refused.posts == 0 && transport.posts.get() == posts + 1);
        check(Arrays.equals(a.stage(), staged));

        final byte[] stateHeld = a.state();
        transport.sendQueued = true;
        final PurpleSyncRunner.Check queued = fresh(runner);
        check(queued.sendQueued);
        check(queued.review.verdict == PurpleSyncCore.Verdict.Pending);
        final PurpleSyncPublisher.Result still = publish(runner, queued,
                PurpleSyncCore.PostRequest.finishSending());
        transport.sendQueued = false;
        check(still.status == PurpleSyncCore.PublishStatus.StillSending);
        check(still.posts == 0 && transport.posts.get() == posts + 1);
        check(Arrays.equals(a.state(), stateHeld));
        check(Arrays.equals(a.stage(), staged));

        final PurpleSyncPublisher.Result finished = finishSending(runner);
        check(finished.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(finished.posts == 1 && transport.posts.get() == posts + 2);
        check(Arrays.equals(transport.posted.get(transport.posted.size() - 1),
                staged));
        check(a.stage() == null && a.inspect().pendingSeq == 0);
        check(fresh(runner).review.verdict == PurpleSyncCore.Verdict.UpToDate);
        close(runner);
    }

    private static void testLostReceiptAndTwoBoxes() {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("receipt", 14);
        a.use();
        a.write(T0);
        final PurpleSyncRunner boxA = a.runner(transport);
        check(publishAction(boxA).status == PurpleSyncCore.PublishStatus.Confirmed);

        a.write(T1);
        transport.mode = PostMode.LoseReceipt;
        final PurpleSyncPublisher.Result lost = publishAction(boxA);
        transport.mode = PostMode.Confirm;
        check(lost.status == PurpleSyncCore.PublishStatus.OutcomeUnknown);
        final int landed = cloud.lastId();
        final int posts = transport.posts.get();
        check(a.stage() != null);

        final PurpleSyncRunner.Check found = fresh(boxA);
        check(found.review.verdict == PurpleSyncCore.Verdict.Pending);
        check(found.review.ownHead != null && found.review.ownHead.messageId == landed);
        final PurpleSyncPublisher.Result confirmed = publish(boxA, found,
                PurpleSyncCore.PostRequest.finishSending());
        check(confirmed.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(confirmed.messageId == landed && confirmed.posts == 0);
        check(transport.posts.get() == posts);
        check(a.stage() == null);
        check(fresh(boxA).review.verdict == PurpleSyncCore.Verdict.UpToDate);

        a.write(TL);
        transport.mode = PostMode.LoseReceipt;
        check(publishAction(boxA).status == PurpleSyncCore.PublishStatus.OutcomeUnknown);
        transport.mode = PostMode.Confirm;
        final int second = cloud.lastId();
        final int postsBefore = transport.posts.get();
        final PurpleSyncRunner boxB = a.runner(transport);
        final PurpleSyncRunner.Check checkA = fresh(boxA);
        final PurpleSyncRunner.Check checkB = fresh(boxB);
        check(checkA.review.verdict == PurpleSyncCore.Verdict.Pending);
        check(checkB.review.verdict == PurpleSyncCore.Verdict.Pending);
        final PurpleSyncPublisher.Result fromB = publish(boxB, checkB,
                PurpleSyncCore.PostRequest.finishSending());
        check(fromB.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(fromB.messageId == second && fromB.posts == 0);
        final byte[] afterB = a.state();
        final PurpleSyncPublisher.Result fromA = publish(boxA, checkA,
                PurpleSyncCore.PostRequest.finishSending());
        check(fromA.status == PurpleSyncCore.PublishStatus.NeedsReview);
        check(fromA.posts == 0 && transport.posts.get() == postsBefore);
        check(Arrays.equals(a.state(), afterB));
        check(fresh(boxA).review.verdict == PurpleSyncCore.Verdict.UpToDate);
        close(boxA);
        close(boxB);
    }

    private static void testUndoRules() throws Exception {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("undo", 15);
        a.use();
        a.write(T0);
        final PurpleSyncRunner runner = a.runner(transport);
        check(publishAction(runner).status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version v1 = versionOf(cloud.last());
        final String space = space(a);
        final Remote b = remote('b', "Android");

        final Version b1 = post(cloud, b, space, TB, v1);
        final PurpleSyncRunner.ApplyOutcome first = apply(runner, fresh(runner), b1.key);
        check(first.result.status == PurpleSyncCore.ApplyStatus.Applied);
        final PurpleSyncRunner.UndoOffer offer = undoOffer(runner);
        check(offer != null && offer.historyId.equals(first.result.historyId));

        transport.hold = true;
        final Waiter<PurpleSyncRunner.CheckOutcome> held = new Waiter<>();
        check(onUi(() -> runner.check(null, held)) == PurpleSyncRunner.Start.Started);
        check(undoOffer(runner) == null);
        check(onUi(() -> runner.undo(offer, new Waiter<PurpleSyncRunner.RestoreOutcome>()))
                == PurpleSyncRunner.Start.Busy);
        check(onUi(() -> runner.restore(offer.historyId,
                new Waiter<PurpleSyncRunner.RestoreOutcome>()))
                == PurpleSyncRunner.Start.Busy);
        transport.release();
        final PurpleSyncRunner.CheckOutcome released = held.get();
        check(released.status == PurpleSyncRunner.CheckStatus.Finished);
        check(undoOffer(runner) != null);

        a.write(T1);
        final PurpleSyncRunner.Check local = fresh(runner);
        check(local.review.verdict == PurpleSyncCore.Verdict.LocalChanges);
        final PurpleSyncRunner.ApplyOutcome keep = apply(runner, local, null);
        check(keep.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(!keep.result.wroteFile);
        check(undoOffer(runner) != null
                && undoOffer(runner).historyId.equals(offer.historyId));
        check(publish(runner, keep.post).status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version mine = versionOf(cloud.last());
        check(undoOffer(runner) != null);

        final Version b2 = post(cloud, b, space, TB2, mine);
        final PurpleSyncRunner.ApplyOutcome second = apply(runner, fresh(runner), b2.key);
        check(second.result.status == PurpleSyncCore.ApplyStatus.Applied);
        final PurpleSyncRunner.UndoOffer replaced = undoOffer(runner);
        check(replaced != null && replaced.historyId.equals(second.result.historyId));
        check(!replaced.historyId.equals(offer.historyId));
        check(onUi(() -> runner.undo(offer, new Waiter<PurpleSyncRunner.RestoreOutcome>()))
                == PurpleSyncRunner.Start.Stale);

        Files.delete(a.settings().toPath());
        Files.createDirectory(a.settings().toPath());
        final PurpleSyncRunner.RestoreOutcome invalid = undo(runner, replaced);
        check(invalid.result.status == PurpleSyncCore.RestoreStatus.InvalidSettings);
        check(!invalid.undoFinished);
        check(undoOffer(runner) != null
                && undoOffer(runner).historyId.equals(replaced.historyId));
        Files.delete(a.settings().toPath());
        a.write(TB2);

        final PurpleSyncHistory.Entry target = entry(a, replaced.historyId);
        check(target != null);
        Files.delete(new File(a.historyDir(), replaced.historyId + ".toml").toPath());
        final PurpleSyncRunner.RestoreOutcome gone = undo(runner, replaced);
        check(gone.result.status == PurpleSyncCore.RestoreStatus.NotFound);
        check(gone.undoFinished);
        check(undoOffer(runner) == null);
        check(Arrays.equals(a.text(), TB2));

        final Version b3 = post(cloud, b, space, TB3, b2);
        final PurpleSyncRunner.ApplyOutcome third = apply(runner, fresh(runner), b3.key);
        check(third.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(undoOffer(runner) != null);
        final PurpleSyncHistory.Entry older = entry(a, first.result.historyId);
        check(older != null);
        final PurpleSyncRunner.RestoreOutcome restored = restore(runner, older.id);
        check(restored.result.status == PurpleSyncCore.RestoreStatus.Restored);
        check(!restored.undoFinished);
        check(undoOffer(runner) == null);
        check(Arrays.equals(a.text(), T0));

        a.write(NOT_UTF8);
        final Version b4 = post(cloud, b, space, TB, b3);
        final PurpleSyncRunner.Check binary = fresh(runner);
        check(binary.review.verdict == PurpleSyncCore.Verdict.Conflict
                || binary.review.verdict == PurpleSyncCore.Verdict.UpdateReady
                || binary.review.verdict == PurpleSyncCore.Verdict.Choose);
        final PurpleSyncRunner.ApplyOutcome overBinary = apply(runner, binary, b4.key);
        check(overBinary.result.status == PurpleSyncCore.ApplyStatus.Applied);
        check(overBinary.result.wroteFile && !overBinary.result.undoAvailable);
        check(overBinary.result.historyKept());
        check(Arrays.equals(a.store().read(overBinary.result.historyId), NOT_UTF8));
        check(undoOffer(runner) == null);
        final PurpleSyncRunner.RestoreOutcome notText =
                restore(runner, overBinary.result.historyId);
        check(notText.result.status == PurpleSyncCore.RestoreStatus.NotText);
        close(runner);
    }

    private static void testRestore() throws Exception {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("restore", 16);
        a.use();
        final PurpleSyncRunner runner = a.runner(transport);
        final PurpleSyncHistory store = a.store();
        final PurpleSyncHistory.Entry gone = store.save(null,
                PurpleSyncHistory.Reason.BeforeChoice, "Android 0000", "", null);
        final PurpleSyncHistory.Entry t0 = store.save(T0,
                PurpleSyncHistory.Reason.BeforeUpdate, "Android 0000", "", null);
        check(gone != null && t0 != null);
        check(restore(runner, gone.id).result.status
                == PurpleSyncCore.RestoreStatus.FileDidNotExist);
        check(restore(runner, "0000000000000001-0000000000000000").result.status
                == PurpleSyncCore.RestoreStatus.NotFound);
        final PurpleSyncRunner.RestoreOutcome fromAbsent = restore(runner, t0.id);
        check(fromAbsent.result.status == PurpleSyncCore.RestoreStatus.Restored);
        check(fromAbsent.next == null);
        check(Arrays.equals(a.text(), T0));
        check(PurpleSyncApply.REASON_RESTORE.equals(PurpleSettings.lastReason));
        check(!PurpleSettings.lastFromImport);
        final PurpleSyncHistory.Entry before = entry(a, fromAbsent.result.historyId);
        check(before != null && !before.existed);
        check(before != null && before.reason == PurpleSyncHistory.Reason.BeforeRestore);
        check(before != null && before.label.equals(t0.id));
        final PurpleSyncRunner.RestoreOutcome same = restore(runner, t0.id);
        check(same.result.status == PurpleSyncCore.RestoreStatus.Unchanged);
        check(same.result.fingerprint.equals(fingerprint(T0)));

        final Waiter<PurpleSyncRunner.Preview> preview = new Waiter<>();
        a.write(T1);
        check(onUi(() -> runner.preview(t0.id, preview)));
        final PurpleSyncRunner.Preview shown = preview.get();
        check(shown != null && Arrays.equals(shown.text, T0));
        check(shown != null && shown.entry.id.equals(t0.id));
        check(shown != null && shown.diff != null && shown.diff.isValid()
                && !shown.diff.identical);
        final Waiter<List<PurpleSyncHistory.Entry>> listed = new Waiter<>();
        check(onUi(() -> runner.history(listed)));
        check(listed.get().size() == a.history().size());

        for (int i = 0; i != PurpleSyncHistory.LIMIT + 2; ++i) {
            check(store.save(utf8("filler = " + i + "\n"),
                    PurpleSyncHistory.Reason.BeforeUpdate, "x", "", null) != null);
        }
        check(entry(a, t0.id) == null);
        final PurpleSyncHistory.Entry oldest = a.history().get(a.history().size() - 1);
        final PurpleSyncRunner.RestoreOutcome keepTarget = restore(runner, oldest.id);
        check(keepTarget.result.status == PurpleSyncCore.RestoreStatus.Restored);
        check(entry(a, oldest.id) != null);
        check(entry(a, keepTarget.result.historyId) != null);
        check(a.history().size() == PurpleSyncHistory.LIMIT);
        close(runner);
    }

    private static void testAccountChanges() {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("account", 17);
        a.use();
        a.write(T0);
        final PurpleSyncRunner runner = a.runner(transport);

        transport.onCheck = () -> UserConfig.userId = USER + 1;
        final PurpleSyncRunner.CheckOutcome during = checkOutcome(runner);
        transport.onCheck = null;
        UserConfig.userId = USER;
        check(during.status == PurpleSyncRunner.CheckStatus.Finished);
        check(during.check.review.status
                == PurpleSyncCore.ReviewStatus.AccountUnavailable);
        check(during.check.review.action == PurpleSyncCore.Action.None);
        check(!a.syncRoot().exists());

        transport.accountChanged = true;
        final PurpleSyncRunner.CheckOutcome switched = checkOutcome(runner);
        transport.accountChanged = false;
        check(switched.status == PurpleSyncRunner.CheckStatus.AccountUnavailable);
        check(switched.check == null && !onUi(runner::busy));

        check(publishAction(runner).status == PurpleSyncCore.PublishStatus.Confirmed);
        final Version v1 = versionOf(cloud.last());
        final Remote b = remote('b', "Android");
        final Version b1 = post(cloud, b, space(a), TB, v1);
        final byte[] stateBefore = a.state();

        final PurpleSyncRunner.Check update = fresh(runner);
        UserConfig.userId = USER + 1;
        check(!onUi(runner::accountAvailable));
        check(onUi(() -> runner.apply(update, b1.key,
                new Waiter<PurpleSyncRunner.ApplyOutcome>()))
                == PurpleSyncRunner.Start.AccountUnavailable);
        check(onUi(() -> runner.check(null,
                new Waiter<PurpleSyncRunner.CheckOutcome>()))
                == PurpleSyncRunner.Start.AccountUnavailable);
        UserConfig.userId = USER;

        final AtomicInteger reads = new AtomicInteger();
        PurpleSettings.onSettingsFile = () -> {
            if (reads.incrementAndGet() == 1) {
                UserConfig.userId = USER + 1;
            }
        };
        final PurpleSyncRunner.ApplyOutcome stopped = apply(runner, update, b1.key);
        PurpleSettings.onSettingsFile = null;
        UserConfig.userId = USER;
        check(stopped.result.status == PurpleSyncCore.ApplyStatus.AccountUnavailable);
        check(!stopped.result.wroteFile && !stopped.result.historyKept());
        check(Arrays.equals(a.text(), T0));
        check(Arrays.equals(a.state(), stateBefore));
        check(a.history().isEmpty());

        a.write(T1);
        final PurpleSyncRunner.Check conflict = fresh(runner);
        check(conflict.review.verdict == PurpleSyncCore.Verdict.Conflict);
        final PurpleSyncRunner.ApplyOutcome keep = apply(runner, conflict, null);
        check(keep.post != null);
        final PurpleSyncRunner.Check clicked = fresh(runner);
        transport.onPost = () -> UserConfig.userId = USER + 1;
        final int posts = transport.posts.get();
        final PurpleSyncPublisher.Result unknown =
                publish(runner, clicked, keep.post.request);
        transport.onPost = null;
        UserConfig.userId = USER;
        check(unknown.status == PurpleSyncCore.PublishStatus.OutcomeUnknown);
        check(unknown.posts == 1 && transport.posts.get() == posts + 1);
        check(a.stage() != null && a.inspect().pendingSeq != 0);
        final PurpleSyncPublisher.Result recovered = finishSending(runner);
        check(recovered.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(recovered.posts == 0 && transport.posts.get() == posts + 1);
        check(fresh(runner).review.verdict == PurpleSyncCore.Verdict.UpToDate);

        final PurpleSyncRunner.Check before = fresh(runner);
        UserConfig.activated = false;
        check(onUi(() -> runner.publish(before,
                PurpleSyncCore.PostRequest.finishSending(),
                new Waiter<PurpleSyncPublisher.Result>()))
                == PurpleSyncRunner.Start.AccountUnavailable);
        UserConfig.activated = true;
        close(runner);
    }

    private static void testStaleCallbacks() throws Exception {
        final Cloud cloud = new Cloud();
        final FakeTransport transport = new FakeTransport(cloud);
        final Phone a = new Phone("stale", 18);
        a.use();
        a.write(T0);
        final PurpleSyncRunner runner = a.runner(transport);

        transport.hold = true;
        transport.deliverOnCancel = false;
        final Waiter<PurpleSyncRunner.CheckOutcome> cancelled = new Waiter<>();
        check(onUi(() -> runner.check(null, cancelled)) == PurpleSyncRunner.Start.Started);
        settle();
        check(onUi(runner::busy) && onUi(runner::checking));
        check(onUi(() -> runner.check(null, new Waiter<PurpleSyncRunner.CheckOutcome>()))
                == PurpleSyncRunner.Start.Busy);
        check(onUi(runner::cancel));
        check(!onUi(runner::busy) && !onUi(runner::cancel));
        check(transport.cancels.get() == 1);
        transport.release();
        settle();
        check(cancelled.calls() == 0);
        check(onUi(runner::current) == null);

        transport.hold = true;
        final Waiter<PurpleSyncRunner.CheckOutcome> overtaken = new Waiter<>();
        check(onUi(() -> runner.check(null, overtaken)) == PurpleSyncRunner.Start.Started);
        settle();
        final PurpleSyncTransport.CheckDone staleDone;
        final PurpleSyncTransport.CheckResult staleResult;
        synchronized (transport) {
            staleDone = transport.heldDone;
            staleResult = transport.heldResult;
            transport.heldDone = null;
            transport.heldResult = null;
        }
        check(staleDone != null && staleResult != null);
        check(onUi(runner::cancel));
        final Waiter<PurpleSyncRunner.CheckOutcome> running = new Waiter<>();
        check(onUi(() -> runner.check(null, running)) == PurpleSyncRunner.Start.Started);
        settle();
        onUi(() -> {
            staleDone.onCheckDone(staleResult);
            return null;
        });
        settle();
        check(overtaken.calls() == 0 && running.calls() == 0);
        check(onUi(runner::checking) && onUi(runner::busy));
        check(onUi(() -> runner.check(null, new Waiter<PurpleSyncRunner.CheckOutcome>()))
                == PurpleSyncRunner.Start.Busy);
        transport.release();
        check(running.get().status == PurpleSyncRunner.CheckStatus.Finished);
        settle();
        check(overtaken.calls() == 0 && running.calls() == 1);
        transport.deliverOnCancel = true;

        transport.hold = true;
        final Waiter<PurpleSyncRunner.CheckOutcome> dropCancelled = new Waiter<>();
        check(onUi(() -> runner.check(null, dropCancelled)) == PurpleSyncRunner.Start.Started);
        settle();
        check(onUi(runner::cancel));
        settle();
        transport.hold = false;
        check(dropCancelled.calls() == 0);
        check(!onUi(runner::busy) && onUi(runner::current) == null);

        final PurpleSyncRunner.Check older = fresh(runner);
        final PurpleSyncRunner.Check newer = fresh(runner);
        check(onUi(() -> runner.apply(older, null,
                new Waiter<PurpleSyncRunner.ApplyOutcome>()))
                == PurpleSyncRunner.Start.Stale);
        check(onUi(() -> runner.publish(older,
                PurpleSyncCore.PostRequest.finishSending(),
                new Waiter<PurpleSyncPublisher.Result>()))
                == PurpleSyncRunner.Start.Stale);
        check(onUi(runner::current) == newer);

        final Waiter<PurpleSyncRunner.ApplyOutcome> dropped = new Waiter<>();
        check(onUi(() -> {
            final PurpleSyncRunner.Start start = runner.apply(newer, null, dropped);
            runner.close();
            return start;
        }) == PurpleSyncRunner.Start.Started);
        settle();
        check(dropped.calls() == 0);
        check(onUi(runner::closed) && onUi(runner::current) == null);
        check(onUi(() -> runner.check(null, new Waiter<PurpleSyncRunner.CheckOutcome>()))
                == PurpleSyncRunner.Start.Closed);
        check(!onUi(() -> runner.history(new Waiter<List<PurpleSyncHistory.Entry>>())));

        final PurpleSyncRunner second = a.runner(transport);
        transport.hold = true;
        final Waiter<PurpleSyncRunner.CheckOutcome> closing = new Waiter<>();
        check(onUi(() -> second.check(null, closing)) == PurpleSyncRunner.Start.Started);
        settle();
        close(second);
        transport.release();
        settle();
        check(closing.calls() == 0);

        final PurpleSyncRunner third = a.runner(transport);
        final PurpleSyncRunner.Check clicked = fresh(third);
        final Waiter<PurpleSyncPublisher.Result> abandoned = new Waiter<>();
        final PurpleSyncRunner.ApplyOutcome joined = apply(third, clicked, null);
        check(joined.post != null);
        final int posts = transport.posts.get();
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        for (DispatchQueue queue : DispatchQueue.ALL) {
            queue.postRunnable(gate::join);
        }
        check(onUi(() -> {
            final PurpleSyncRunner.Start start = third.publish(joined.post, abandoned);
            third.close();
            return start;
        }) == PurpleSyncRunner.Start.Started);
        gate.complete(null);
        settle();
        check(abandoned.calls() == 0);
        check(transport.posts.get() == posts && a.stage() == null);
        check(a.inspect().pendingSeq == 0);

        final PurpleSyncRunner fourth = a.runner(transport);
        final PurpleSyncRunner.Check reopened = fresh(fourth);
        check(reopened.review.status == PurpleSyncCore.ReviewStatus.Ready);
        check(reopened.review.verdict == PurpleSyncCore.Verdict.Empty);
        final PurpleSyncRunner.ApplyOutcome again = apply(fourth, reopened, null);
        check(again.post != null);
        transport.holdPost = true;
        final Waiter<PurpleSyncPublisher.Result> inFlight = new Waiter<>();
        check(onUi(() -> fourth.publish(again.post, inFlight))
                == PurpleSyncRunner.Start.Started);
        for (int i = 0; i != 600 && transport.posts.get() == posts; ++i) {
            Thread.sleep(10);
        }
        check(transport.posts.get() == posts + 1);
        final int cancels = transport.cancels.get();
        close(fourth);
        transport.holdPost = false;
        check(transport.cancels.get() == cancels + 1);
        check(inFlight.calls() == 0);
        check(a.stage() != null && a.inspect().pendingSeq != 0);

        final PurpleSyncRunner fifth = a.runner(transport);
        final PurpleSyncRunner.Check pending = fresh(fifth);
        check(pending.review.verdict == PurpleSyncCore.Verdict.Pending);
        final PurpleSyncPublisher.Result finished = publish(fifth, pending,
                PurpleSyncCore.PostRequest.finishSending());
        check(finished.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(finished.posts == 0 && finished.messageId == cloud.lastId());
        check(transport.posts.get() == posts + 1 && a.stage() == null);
        close(fifth);
    }

    private static void testHandoff() throws Exception {
        final PurpleSyncRunner.Poster drop = task -> {
        };
        final long started = System.nanoTime();
        check("abandoned".equals(PurpleSyncRunner.onThread(drop, () -> "ran",
                "abandoned", 50)));
        check(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(50));

        final AtomicBoolean ranLate = new AtomicBoolean();
        final List<Runnable> parked = Collections.synchronizedList(new ArrayList<>());
        check("abandoned".equals(PurpleSyncRunner.onThread(parked::add, () -> {
            ranLate.set(true);
            return "ran";
        }, "abandoned", 50)));
        for (Runnable task : parked) {
            task.run();
        }
        check(!ranLate.get());

        check("ran".equals(PurpleSyncRunner.onThread(AndroidUtilities::runOnUIThread,
                () -> "ran", "abandoned", 5000)));
        check("slow".equals(PurpleSyncRunner.onThread(AndroidUtilities::runOnUIThread,
                () -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    return "slow";
                }, "abandoned", 50)));
        check(PurpleSyncRunner.onThread(AndroidUtilities::runOnUIThread,
                () -> {
                    throw new IllegalStateException("thrown on the UI thread");
                }, "abandoned", 5000) == null);

        final Phone a = new Phone("handoff", 19);
        a.use();
        a.write(T0);
        final PurpleSyncSettingsFile.Contents t0 = PurpleSyncSettingsFile.read();
        final PurpleSyncSettingsFile.WriteResult unknown = PurpleSyncRunner.replaceOn(
                AndroidUtilities::runOnUIThread, () -> {
                    throw new IllegalStateException("lost");
                }, PurpleSyncSettingsFile::read);
        check(unknown.status == PurpleSyncSettingsFile.WriteStatus.ReadBackMismatch);
        check(unknown.wrote() && unknown.readBack != null
                && Arrays.equals(unknown.readBack.bytes, T0));
        final PurpleSyncSettingsFile.WriteResult changed =
                new PurpleSyncRunner.Platform().replaceSettings(
                        new PurpleSyncSettingsFile.Contents(
                                PurpleSyncSettingsFile.Status.Present, T1),
                        TB, "test", true);
        check(changed.status == PurpleSyncSettingsFile.WriteStatus.Changed);
        check(!changed.wrote() && Arrays.equals(a.text(), T0));
        final PurpleSyncSettingsFile.WriteResult written =
                new PurpleSyncRunner.Platform().replaceSettings(t0, TB, "test", true);
        check(written.status == PurpleSyncSettingsFile.WriteStatus.Written);
        check(Arrays.equals(a.text(), TB));
        check(AndroidUtilities.UI_THREAD.equals(PurpleSettings.lastThread));
        final PurpleSyncSettingsFile.WriteResult absent =
                new PurpleSyncRunner.Platform().replaceSettings(
                        new PurpleSyncSettingsFile.Contents(
                                PurpleSyncSettingsFile.Status.Absent, new byte[0]),
                        T0, "test", true);
        check(absent.status == PurpleSyncSettingsFile.WriteStatus.Changed);
        check(Arrays.equals(a.text(), TB));
    }

    private interface Section {
        void run() throws Exception;
    }

    private static void run(String name, Section body) {
        section = name;
        reset();
        final int before = failures;
        try {
            body.run();
        } catch (Throwable e) {
            ++failures;
            System.out.println("  FAIL  " + name + ": " + e);
            e.printStackTrace(System.out);
        }
        settle();
        System.out.println((failures == before ? "  ok    " : "  bad   ") + name);
    }

    public static void main(String[] args) throws Exception {
        scratch = Files.createTempDirectory(new File(
                System.getProperty("java.io.tmpdir")).toPath(), "executors")
                .toFile().getCanonicalFile();
        run("empty publish and local changes", PurpleSyncExecutorsTest::testEmptyPublishAndLocalChanges);
        run("join, adopt and choose", PurpleSyncExecutorsTest::testJoinAdoptAndChoose);
        run("update, undo and silent adopt", PurpleSyncExecutorsTest::testUpdateUndoAndSilentAdopt);
        run("bound choose and conflict", PurpleSyncExecutorsTest::testChooseAndConflictBound);
        run("write order and failures", PurpleSyncExecutorsTest::testWriteOrderAndFailures);
        run("join failures", PurpleSyncExecutorsTest::testJoinFailures);
        run("finish sending", PurpleSyncExecutorsTest::testFinishSending);
        run("lost receipt and two boxes", PurpleSyncExecutorsTest::testLostReceiptAndTwoBoxes);
        run("undo rules", PurpleSyncExecutorsTest::testUndoRules);
        run("restore", PurpleSyncExecutorsTest::testRestore);
        run("account changes", PurpleSyncExecutorsTest::testAccountChanges);
        run("stale callbacks", PurpleSyncExecutorsTest::testStaleCallbacks);
        run("ui handoff", PurpleSyncExecutorsTest::testHandoff);
        System.out.println("PurpleSyncExecutorsTest: " + checks + " checks, "
                + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
