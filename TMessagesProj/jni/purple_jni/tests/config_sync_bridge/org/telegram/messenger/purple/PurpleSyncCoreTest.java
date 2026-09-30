package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class PurpleSyncCoreTest {
    private static final int ACCOUNT = 0;
    private static final long USER = 777;

    private static final byte[] T0 = utf8("version = 1\n# first\n");
    private static final byte[] T1 = utf8("version = 1\n# local edit\n");
    private static final byte[] TB = utf8("version = 1\n# from b\n");
    private static final byte[] TB2 = utf8("version = 1\n# from b, second\n");
    private static final byte[] TB3 = utf8("version = 1\n# from b, third\n");
    private static final byte[] TL = utf8("version = 1\n# local conflict\n");
    private static final byte[] TB4 = utf8("version = 1\n# from b, fourth\n");
    private static final byte[] TB5 = utf8("version = 1\n# from b, fifth\n");
    private static final byte[] TC = utf8("version = 1\n# from c\n");
    private static final byte[] TB6 = utf8("version = 1\n# from b, sixth\n");
    private static final byte[] TC2 = utf8("version = 1\n# from c, second\n");

    private static int checks;
    private static int failures;
    private static String section = "";

    private PurpleSyncCoreTest() {
    }

    private static void check(boolean ok) {
        ++checks;
        if (!ok) {
            ++failures;
            final StackTraceElement where = new Throwable().getStackTrace()[1];
            System.out.println("  FAIL  " + section + ":" + where.getLineNumber());
        }
    }

    private static void begin(String name) {
        section = name;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fill(int size, char value) {
        final byte[] result = new byte[size];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static String fingerprint(byte[] text) throws Exception {
        return text.length + ":" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text));
    }

    private static PurpleSyncSettingsFile.Contents present(byte[] text) {
        return new PurpleSyncSettingsFile.Contents(
                PurpleSyncSettingsFile.Status.Present, text);
    }

    private static PurpleSyncSettingsFile.Contents absent() {
        return new PurpleSyncSettingsFile.Contents(
                PurpleSyncSettingsFile.Status.Absent, new byte[0]);
    }

    private static PurpleSyncSettingsFile.Contents invalid() {
        return new PurpleSyncSettingsFile.Contents(
                PurpleSyncSettingsFile.Status.Invalid, new byte[0]);
    }

    private static String id(PurpleAccountSyncCore.Result result) {
        check(result.isValid());
        return result.id;
    }

    private static PurpleAccountSyncCore.Result inspect(byte[] state) {
        final PurpleAccountSyncCore.Result result =
                PurpleAccountSyncCore.inspectState(state);
        check(result.isValid());
        return result;
    }

    private static JSONObject configStream(byte[] state) {
        return new JSONObject(new String(state, StandardCharsets.UTF_8))
                .getJSONObject("streams").getJSONObject("config");
    }

    private static long seenSeq(byte[] state, String install) {
        return configStream(state).getJSONObject("seen_seq").optLong(install, -1);
    }

    private static byte[] canonical(JSONObject state) {
        final PurpleAccountSyncCore.Result result = PurpleAccountSyncCore
                .inspectState(utf8(state.toString()));
        return result.isValid() ? result.state : utf8(state.toString());
    }

    private static boolean sameSet(List<String> a, List<String> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
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

        Remote(String install, String device, String platform, long seq) {
            this.install = install;
            this.device = device;
            this.platform = platform;
            this.seq = seq;
        }

        Remote copy() {
            return new Remote(install, device, platform, seq);
        }
    }

    private static Remote remote(char seed, String platform) {
        final String install = id(PurpleAccountSyncCore.formatInstallId(
                fill(16, seed)));
        return new Remote(install,
                platform.toLowerCase(Locale.ROOT) + ":" + install, platform, 0);
    }

    private static byte[] buildRemote(Remote remote, String space, byte[] text,
            Version... parents) {
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
        return built.record;
    }

    private static final class Cloud {
        final List<Integer> ids = new ArrayList<>();
        final List<byte[]> records = new ArrayList<>();
        int nextId = 100;
        boolean scanComplete = true;

        int add(byte[] bytes) {
            ids.add(nextId);
            records.add(bytes);
            return nextId++;
        }

        int size() {
            return ids.size();
        }

        Cloud copy() {
            final Cloud result = new Cloud();
            result.ids.addAll(ids);
            result.records.addAll(records);
            result.nextId = nextId;
            result.scanComplete = scanComplete;
            return result;
        }

        PurpleSyncInventory inventory() {
            final PurpleSyncInventory.Builder builder =
                    new PurpleSyncInventory.Builder(USER);
            for (int i = 0; i != ids.size(); ++i) {
                builder.fetched(ids.get(i), ids.get(i) + 5000L, 1700000000L,
                        records.get(i));
            }
            return builder.build(scanComplete);
        }
    }

    private static Version post(Cloud cloud, Remote remote, String space,
            byte[] text, Version... parents) {
        final byte[] bytes = buildRemote(remote, space, text, parents);
        cloud.add(bytes);
        return versionOf(bytes);
    }

    private static final class Device {
        final char seed;
        final String device;
        byte[] state;
        byte[] staged;
        PurpleSyncSettingsFile.Contents local = absent();
        String platform = PurpleSyncCore.WRITER_PLATFORM;
        String app = PurpleSyncCore.WRITER_APP;
        long now = 1800000000L;
        boolean queued;

        Device(char seed) {
            this.seed = seed;
            this.device = "android-" + String.format(Locale.ROOT, "%08x",
                    (int) seed * 0x01010101);
        }

        Device copy() {
            final Device result = new Device(seed);
            result.state = state;
            result.staged = staged;
            result.local = local;
            result.platform = platform;
            result.app = app;
            result.now = now;
            result.queued = queued;
            return result;
        }

        void setLocal(byte[] text) {
            local = present(text);
        }

        void removeLocal() {
            local = absent();
        }

        String space() {
            return inspect(state).space;
        }

        String install() {
            return inspect(state).install;
        }

        String base() {
            return inspect(state).key;
        }

        long pendingSeq() {
            return inspect(state).pendingSeq;
        }
    }

    private static PurpleSyncCore.Review review(Device device,
            PurpleSyncInventory inventory) {
        final PurpleSyncCore.Review result = PurpleSyncCore.review(inventory,
                device.state, device.staged, device.local, device.device,
                device.platform, device.app);
        check(result.isValid());
        return result;
    }

    private static PurpleSyncCore.Review review(Device device, Cloud cloud) {
        return review(device, cloud.inventory());
    }

    private static boolean join(Device device, String space) {
        if (device.state != null) {
            return false;
        }
        final String install = id(PurpleAccountSyncCore.formatInstallId(
                fill(16, device.seed)));
        final String chosen = !space.isEmpty() ? space
                : id(PurpleAccountSyncCore.formatTimeOrderedSpaceId(
                        1800000000000L + device.seed));
        final PurpleAccountSyncCore.Result initialized =
                PurpleAccountSyncCore.initializeBoundLocalState(ACCOUNT,
                        install, device.device, chosen);
        if (!initialized.isValid()) {
            return false;
        }
        device.state = initialized.state;
        return true;
    }

    private static boolean stage(Device device, byte[] record, String key) {
        final PurpleAccountSyncCore.Result reserved =
                PurpleAccountSyncCore.reserveConfigRecord(ACCOUNT,
                        device.state, record);
        if (!reserved.isValid() || !key.equals(reserved.key)) {
            return false;
        }
        final PurpleAccountSyncCore.Result checked =
                PurpleAccountSyncCore.checkLocalStage(reserved.state, record);
        if (!checked.isValid() || !"Pending".equals(checked.verdict)) {
            return false;
        }
        device.state = reserved.state;
        device.staged = record;
        return true;
    }

    private static boolean confirm(Device device, byte[] bytes, int messageId) {
        if (device.state == null || device.staged == null
                || !Arrays.equals(bytes, device.staged)) {
            return false;
        }
        final PurpleAccountSyncCore.Result confirmed =
                PurpleAccountSyncCore.confirmConfigReadBack(device.state,
                        device.staged, device.device,
                        PurpleAccountSyncCore.OBSERVATION_PRESENT, bytes,
                        messageId);
        if (!confirmed.isValid() || !"NoClone".equals(confirmed.verdict)
                || !confirmed.changed || confirmed.state == null) {
            return false;
        }
        device.state = confirmed.state;
        device.staged = null;
        return true;
    }

    private enum PostMode { Confirm, LoseReceipt, Fail }

    private static final class PublishRun {
        PurpleSyncCore.PublishStatus status =
                PurpleSyncCore.PublishStatus.Incomplete;
        int messageId;
        int posts;
        byte[] posted;
        Version version;
    }

    private static PublishRun publish(Device device, Cloud cloud,
            PurpleSyncInventory inventory, PurpleSyncCore.PostRequest request,
            PostMode mode) {
        final PublishRun run = new PublishRun();
        if (device.state == null) {
            run.status = PurpleSyncCore.PublishStatus.AccountUnbound;
            return run;
        }
        final PurpleSyncCore.EntryPlan entry = PurpleSyncCore.planPostEntry(
                request, device.pendingSeq() != 0);
        check(entry.isValid());
        final PurpleSyncSettingsFile.Contents local =
                (entry.entry == PurpleSyncCore.PostEntry.NewContent)
                        ? device.local : invalid();
        final PurpleSyncCore.PostPlan plan = PurpleSyncCore.planPost(ACCOUNT,
                inventory, device.state, device.staged, local, request,
                device.now, device.queued, device.platform, device.app);
        check(plan.isValid());
        if (entry.entry == PurpleSyncCore.PostEntry.Refuse) {
            check(plan.step == PurpleSyncCore.PostStep.Finish
                    && plan.status == PurpleSyncCore.PublishStatus.NeedsReview);
        }
        switch (plan.step) {
            case Finish:
                run.status = plan.status;
                check(plan.record == null);
                break;
            case ConfirmFound:
                run.messageId = plan.messageId;
                run.version = versionOf(plan.record);
                run.status = confirm(device, plan.record, plan.messageId)
                        ? PurpleSyncCore.PublishStatus.Confirmed
                        : PurpleSyncCore.PublishStatus.StoreError;
                break;
            case Stage: {
                if (!stage(device, plan.record, plan.key)) {
                    run.status = PurpleSyncCore.PublishStatus.StoreError;
                    break;
                }
                final PurpleSyncCore.PostPlan staged =
                        PurpleSyncCore.checkStagedPost(ACCOUNT, inventory,
                                device.state, device.staged);
                check(staged.isValid());
                if (staged.step != PurpleSyncCore.PostStep.Post) {
                    run.status = staged.status;
                    break;
                }
                check(Arrays.equals(staged.record, device.staged));
                send(run, device, cloud, staged.record, mode);
            } break;
            case Post:
                check(Arrays.equals(plan.record, device.staged));
                send(run, device, cloud, plan.record, mode);
                break;
        }
        return run;
    }

    private static void send(PublishRun run, Device device, Cloud cloud,
            byte[] bytes, PostMode mode) {
        ++run.posts;
        run.posted = bytes;
        run.version = versionOf(bytes);
        if (mode == PostMode.Fail) {
            run.status = PurpleSyncCore.PublishStatus.OutcomeUnknown;
            return;
        }
        final int id = cloud.add(bytes);
        if (mode == PostMode.LoseReceipt) {
            run.status = PurpleSyncCore.PublishStatus.OutcomeUnknown;
            return;
        }
        run.messageId = id;
        run.status = confirm(device, bytes, id)
                ? PurpleSyncCore.PublishStatus.Confirmed
                : PurpleSyncCore.PublishStatus.StoreError;
    }

    private static PublishRun publish(Device device, Cloud cloud,
            PurpleSyncCore.PostRequest request) {
        return publish(device, cloud, cloud.inventory(), request,
                PostMode.Confirm);
    }

    private static PublishRun publish(Device device, Cloud cloud,
            PurpleSyncCore.PostRequest request, PostMode mode) {
        return publish(device, cloud, cloud.inventory(), request, mode);
    }

    private static PublishRun publish(Device device, Cloud cloud,
            PurpleSyncInventory inventory, PurpleSyncCore.PostRequest request) {
        return publish(device, cloud, inventory, request, PostMode.Confirm);
    }

    private static PurpleSyncCore.PostRequest request(String fingerprint,
            List<String> parents) {
        return new PurpleSyncCore.PostRequest(false, fingerprint, parents);
    }

    private static PurpleSyncCore.PostRequest pendingOnly() {
        return PurpleSyncCore.PostRequest.finishSending();
    }

    private static PurpleSyncCore.PostRequest localRequest(Device device,
            Cloud cloud) {
        final PurpleSyncCore.Review review = review(device, cloud);
        final String fingerprint = review.localFingerprint;
        if (review.status != PurpleSyncCore.ReviewStatus.Ready) {
            return request(fingerprint, List.of());
        }
        switch (review.verdict) {
            case UpToDate:
                return request(fingerprint, List.of());
            case Empty:
            case LocalChanges: {
                final PurpleSyncCore.ApplyPlan keep = PurpleSyncCore.planApply(
                        PurpleSyncCore.ApplyRequest.bound(cloud.inventory(),
                                device.state, device.staged, device.local,
                                review.stamp, null));
                check(keep.isValid());
                return request(fingerprint,
                        keep.status == PurpleSyncCore.ApplyPlanStatus.Ready
                                ? keep.parents : null);
            }
            default:
                return request(fingerprint, null);
        }
    }

    private static PublishRun publishLocal(Device device, Cloud cloud) {
        return publish(device, cloud, localRequest(device, cloud));
    }

    private static PurpleSyncCore.EntryPlan entry(
            PurpleSyncCore.PostRequest request, boolean staged) {
        final PurpleSyncCore.EntryPlan result =
                PurpleSyncCore.planPostEntry(request, staged);
        check(result.isValid());
        return result;
    }

    private static final class ApplyRun {
        PurpleSyncCore.ApplyStatus status = PurpleSyncCore.ApplyStatus.NeedsReview;
        boolean joined;
        boolean wroteFile;
        boolean adopted;
        boolean publishNeeded;
        boolean otherVersionsRemain;
        boolean promiseKept = true;
        boolean update;
        PurpleSyncCore.Verdict nextVerdict = PurpleSyncCore.Verdict.Invalid;
        String fingerprint = "";
        String versionKey = "";
        List<String> expectedParents = List.of();
        PurpleSyncCore.Head source;
    }

    private static PurpleSyncCore.ApplyStatus statusOf(
            PurpleSyncCore.ApplyPlanStatus status) {
        switch (status) {
            case Ready:
                return PurpleSyncCore.ApplyStatus.Applied;
            case NeedsRecheck:
                return PurpleSyncCore.ApplyStatus.NeedsRecheck;
            case InvalidChoice:
                return PurpleSyncCore.ApplyStatus.InvalidChoice;
            default:
                return PurpleSyncCore.ApplyStatus.NeedsReview;
        }
    }

    private static ApplyRun apply(Device device, PurpleSyncInventory inventory,
            PurpleSyncCore.Review shown, String key) {
        final ApplyRun run = new ApplyRun();
        PurpleSyncSettingsFile.Contents preJoin = null;
        if (!shown.bound) {
            preJoin = device.local;
            final PurpleSyncCore.ApplyPlan before = PurpleSyncCore.planApply(
                    PurpleSyncCore.ApplyRequest.unbound(inventory, preJoin,
                            shown.stamp, key));
            check(before.isValid());
            if (before.status != PurpleSyncCore.ApplyPlanStatus.Ready) {
                run.status = statusOf(before.status);
                return run;
            } else if (!before.join || !join(device, before.space)) {
                run.status = PurpleSyncCore.ApplyStatus.NeedsRecheck;
                return run;
            }
            run.joined = true;
        }
        final PurpleSyncCore.ApplyRequest request = shown.bound
                ? PurpleSyncCore.ApplyRequest.bound(inventory, device.state,
                        device.staged, device.local, shown.stamp, key)
                : PurpleSyncCore.ApplyRequest.afterJoin(inventory, device.state,
                        device.local, shown.stamp, key, preJoin);
        final PurpleSyncCore.ApplyPlan plan = PurpleSyncCore.planApply(request);
        check(plan.isValid());
        if (plan.status != PurpleSyncCore.ApplyPlanStatus.Ready || plan.join) {
            run.status = plan.join ? PurpleSyncCore.ApplyStatus.NeedsRecheck
                    : statusOf(plan.status);
            return run;
        }
        run.update = plan.update;
        run.versionKey = plan.versionKey;
        run.source = plan.source;
        run.otherVersionsRemain = plan.otherVersionsRemain;
        if (plan.writeRemote) {
            if (!PurpleSyncCore.isSettingsTextWritable(plan.source.text)) {
                run.status = PurpleSyncCore.ApplyStatus.WriteError;
                return run;
            }
            device.setLocal(plan.source.text);
            run.wroteFile = true;
        }
        final PurpleSyncCore.ApplyCompletion completion =
                PurpleSyncCore.completeApply(request, device.local);
        check(completion.isValid());
        check(completion.plan == PurpleSyncCore.ApplyPlanStatus.Ready);
        switch (completion.status) {
            case Ready:
                break;
            case ReadBackMismatch:
                run.status = PurpleSyncCore.ApplyStatus.WriteError;
                return run;
            default:
                run.status = PurpleSyncCore.ApplyStatus.NeedsReview;
                return run;
        }
        if (completion.adopted) {
            if (completion.commit == PurpleSyncCore.CommitStatus.Ready) {
                final PurpleSyncCore.CommitCheck commit =
                        PurpleSyncCore.checkCommit(device.state,
                                completion.nextState);
                check(commit.isValid()
                        && commit.status == PurpleSyncCore.CommitStatus.Ready
                        && Arrays.equals(commit.state, completion.nextState));
                device.state = commit.state;
            } else if (completion.commit != PurpleSyncCore.CommitStatus.Unchanged) {
                run.status = PurpleSyncCore.ApplyStatus.StoreError;
                return run;
            }
            run.adopted = true;
        } else {
            check(completion.commit == null && completion.nextState == null);
        }
        run.fingerprint = completion.fingerprint;
        run.nextVerdict = completion.nextVerdict;
        run.publishNeeded = completion.publishNeeded;
        run.expectedParents = completion.expectedParents;
        run.promiseKept = completion.promiseKept;
        run.status = PurpleSyncCore.ApplyStatus.Applied;
        return run;
    }

    private static ApplyRun apply(Device device, Cloud cloud,
            PurpleSyncCore.Review shown, String key) {
        return apply(device, cloud.inventory(), shown, key);
    }

    private static PurpleSyncCore.Page page(int offset, int[] ids,
            boolean[] candidates) {
        final int count = ids.length;
        final boolean[] message = new boolean[count];
        final boolean[] forwarded = new boolean[count];
        final boolean[] document = new boolean[count];
        final String[] captions = new String[count];
        final String[] names = new String[count];
        for (int i = 0; i != count; ++i) {
            message[i] = true;
            document[i] = true;
            captions[i] = candidates[i] ? "#purplesync" : "";
            names[i] = candidates[i] ? null : "notes.json";
        }
        final PurpleSyncCore.Page result = PurpleSyncCore.classifyHistoryPage(
                offset, ids, message, forwarded, document, captions, names);
        check(result.isValid());
        return result;
    }

    private static PurpleSyncInventory.Builder withRecord(byte[] record) {
        return new PurpleSyncInventory.Builder(USER).fetched(100, 5100,
                1700000000L, record);
    }

    private static boolean candidate(boolean message, boolean forwarded,
            boolean document, String caption, String name) {
        final PurpleSyncCore.Page result = PurpleSyncCore.classifyHistoryPage(0,
                new int[] { 5 }, new boolean[] { message },
                new boolean[] { forwarded }, new boolean[] { document },
                new String[] { caption }, new String[] { name });
        check(result.isValid() && result.status == PurpleSyncCore.PageStatus.More);
        return result.candidates.length == 1 && result.candidates[0] == 5;
    }

    private static void testBasics() throws Exception {
        begin("basics");
        check(fingerprint(T0).equals(PurpleSyncCore.settingsFingerprint(T0)));
        check(fingerprint(new byte[0]).equals(
                PurpleSyncCore.settingsFingerprint(new byte[0])));
        final byte[] odd = { 0, (byte) 0xff, '"', '\\', 'x' };
        check(fingerprint(odd).equals(PurpleSyncCore.settingsFingerprint(odd)));
        check(PurpleSyncCore.settingsFingerprint(null) == null);
        check(PurpleSyncCore.isConfigVersionKey("1." + fingerprint(T0)));
        check(PurpleSyncCore.isConfigVersionKey("12." + fingerprint(T1)));
        check(!PurpleSyncCore.isConfigVersionKey("0." + fingerprint(T0)));
        check(!PurpleSyncCore.isConfigVersionKey("garbage"));
        check(!PurpleSyncCore.isConfigVersionKey(""));
        check(!PurpleSyncCore.isConfigVersionKey(null));
        check(PurpleSyncCore.isSettingsTextWritable(T0));
        check(PurpleSyncCore.isSettingsTextWritable(new byte[0]));
        check(!PurpleSyncCore.isSettingsTextWritable(new byte[] {
                'v', '\n', '#', ' ', (byte) 0xff, (byte) 0xfe, '\n' }));
        check(!PurpleSyncCore.isSettingsTextWritable(null));

        final PurpleSyncCore.Page stalled = page(98, new int[] { 98 },
                new boolean[] { true });
        check(stalled.status == PurpleSyncCore.PageStatus.Stalled);
        check(stalled.candidates.length == 0);
        final PurpleSyncCore.Page more = page(98, new int[] { 97, 90 },
                new boolean[] { true, false });
        check(more.status == PurpleSyncCore.PageStatus.More);
        check(more.offset == 90);
        check(more.count == 2);
        check(Arrays.equals(more.candidates, new int[] { 97 }));
        check(page(0, new int[] { 5 }, new boolean[] { true }).status
                == PurpleSyncCore.PageStatus.More);
        final PurpleSyncCore.Page complete = page(90, new int[0], new boolean[0]);
        check(complete.status == PurpleSyncCore.PageStatus.Complete);
        check(complete.offset == 90 && complete.candidates.length == 0);
        check(page(0, new int[] { 7, 0 }, new boolean[] { true, true }).status
                == PurpleSyncCore.PageStatus.Stalled);
        check(page(0, new int[] { 7, -3 }, new boolean[] { true, true }).status
                == PurpleSyncCore.PageStatus.Stalled);
        check(page(0, new int[] { 7, 7 }, new boolean[] { true, true }).status
                == PurpleSyncCore.PageStatus.Stalled);
        check(page(0, new int[] { 7, 9 }, new boolean[] { true, true }).status
                == PurpleSyncCore.PageStatus.Stalled);
        final PurpleSyncCore.Page edge = page(0, new int[] { Integer.MAX_VALUE },
                new boolean[] { true });
        check(edge.status == PurpleSyncCore.PageStatus.More);
        check(edge.offset == Integer.MAX_VALUE);
        check(!PurpleSyncCore.classifyHistoryPage(0, new int[] { 1 },
                new boolean[0], new boolean[0], new boolean[0], new String[0],
                new String[0]).isValid());

        check(candidate(true, false, true, "#purplesync", null));
        check(candidate(true, false, true, "Settings #purplesync from the laptop",
                null));
        check(!candidate(true, false, true, "#PurpleSync", null));
        check(!candidate(true, false, true, null, null));
        check(candidate(true, false, true, "", "Purple settings sync.json"));
        check(candidate(true, false, true, "", "Purple playlists sync.json"));
        check(!candidate(true, false, true, "", "settings.toml"));
        check(!candidate(true, false, true, "", "purple settings sync.json"));
        check(!candidate(true, true, true, "#purplesync",
                "Purple settings sync.json"));
        check(!candidate(false, false, true, "#purplesync", null));
        check(!candidate(true, false, false, "#purplesync", null));

        final String space = id(PurpleAccountSyncCore.formatSpaceId(
                fill(16, 's')));
        final Remote b = remote('b', "Android");
        final byte[] record = buildRemote(b, space, TB);
        final Device device = new Device('a');
        device.setLocal(T0);
        final PurpleSyncInventory.Builder builder = withRecord(record);
                new PurpleSyncInventory.Builder(USER).fetched(100, 5100,
                        1700000000L, record);
        final PurpleSyncCore.Review fetched = review(device, builder.build(true));
        check(fetched.status == PurpleSyncCore.ReviewStatus.Ready);
        check(fetched.inventory == PurpleSyncCore.InventoryStatus.Complete);
        check(review(device, new PurpleSyncInventory.Builder(USER)
                .fetched(100, 0, 0, record).build(true)).status
                == PurpleSyncCore.ReviewStatus.NeedsReview);
        final int[] needsReview = {
            PurpleSyncInventory.VANISHED, PurpleSyncInventory.CHANGED,
            PurpleSyncInventory.OVERSIZED, PurpleSyncInventory.INVALID,
        };
        for (int outcome : needsReview) {
            final PurpleSyncCore.Review failed = review(device, withRecord(record)
                    .failed(101, outcome, 5101, 0).build(true));
            check(failed.status == PurpleSyncCore.ReviewStatus.NeedsReview);
            check(failed.inventory == PurpleSyncCore.InventoryStatus.NeedsReview);
        }
        final int[] incomplete = {
            PurpleSyncInventory.INACCESSIBLE, PurpleSyncInventory.REQUEST_FAILED,
            PurpleSyncInventory.CANCELLED,
        };
        for (int outcome : incomplete) {
            final PurpleSyncCore.Review failed = review(device, withRecord(record)
                    .failed(101, outcome, 5101, 0).build(true));
            check(failed.status == PurpleSyncCore.ReviewStatus.Incomplete);
            check(failed.inventory == PurpleSyncCore.InventoryStatus.Incomplete);
        }
        final byte[] huge = new byte[4 * 1024 * 1024 + 1];
        check(review(device, withRecord(record).fetched(101, 5101, 0, huge)
                .build(true)).status == PurpleSyncCore.ReviewStatus.NeedsReview);
        check(review(device, withRecord(record).fetched(101, 5101, 0, new byte[0])
                .build(true)).status == PurpleSyncCore.ReviewStatus.NeedsReview);
        check(review(device, builder.build(false)).status
                == PurpleSyncCore.ReviewStatus.Incomplete);
        final PurpleSyncInventory counted = withRecord(record)
                .failed(101, PurpleSyncInventory.VANISHED, 0, 0).build(true);
        check(counted.count() == 2);
        check(counted.totalBytes() == record.length);
        boolean threw = false;
        try {
            new PurpleSyncInventory.Builder(USER).failed(1,
                    PurpleSyncInventory.FETCHED, 0, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw);

        final byte[] largest = new byte[256 * 1024];
        Arrays.fill(largest, (byte) '#');
        final Device big = new Device('z');
        big.setLocal(largest);
        check(review(big, new Cloud()).status == PurpleSyncCore.ReviewStatus.Ready);
        big.setLocal(Arrays.copyOf(largest, largest.length + 1));
        check(review(big, new Cloud()).status
                == PurpleSyncCore.ReviewStatus.InvalidSettings);
    }

    private static void testHeads() throws Exception {
        begin("heads");
        final String space = id(PurpleAccountSyncCore.formatSpaceId(
                fill(16, 's')));
        final Cloud cloud = new Cloud();
        final Remote b = remote('b', "Android");
        final Remote c = remote('c', "Windows");
        final Version b1 = post(cloud, b, space, TB);
        final Version b2 = post(cloud, b, space, TB2, b1);
        final Version c1 = post(cloud, c, space, TC);
        final Device device = new Device('a');
        device.setLocal(T0);
        final PurpleSyncCore.Review review = review(device, cloud);
        check(review.status == PurpleSyncCore.ReviewStatus.Ready);
        check(!review.bound);
        check(review.accountUserId == USER);
        check(review.space.equals(space));
        check(review.install.isEmpty());
        check(review.verdict == PurpleSyncCore.Verdict.Conflict);
        check(review.offered.size() == 2);
        check(review.localStatus == PurpleSyncSettingsFile.Status.Present);
        check(review.localFingerprint.equals(fingerprint(T0)));
        check(review.heads.size() == 2);
        check(review.ownHead == null);
        boolean foundB = false;
        boolean foundC = false;
        for (PurpleSyncCore.Head head : review.heads) {
            if (head.install.equals(b.install)) {
                foundB = true;
                check(head.seq == 2);
                check(head.key.equals(b2.key));
                check(Arrays.equals(head.text, TB2));
                check(head.device.equals(b.device));
                check(head.platform.equals("Android"));
                check(head.app.equals("Harness"));
                check(head.at == 1700000002L);
                check(!head.newerSchema);
                check(head.messageId == 101);
                check(head.space.equals(space));
                check(head.lineage.equals(List.of(b1.key)));
                check(head.name.platform.equals("Android"));
                check(head.name.shortId.equals(
                        b.install.substring(b.install.indexOf('-') + 1,
                                b.install.indexOf('-') + 5)));
            } else if (head.install.equals(c.install)) {
                foundC = true;
                check(head.key.equals(c1.key));
                check(Arrays.equals(head.text, TC));
            }
        }
        check(foundB && foundC);
        check(new HashSet<>(review.offered).equals(
                new HashSet<>(List.of(b2.key, c1.key))));

        final Cloud ambiguous = cloud.copy();
        final Remote forked = b.copy();
        forked.seq = 1;
        ambiguous.add(buildRemote(forked, space, TB3, b1));
        check(review(device, ambiguous).status
                == PurpleSyncCore.ReviewStatus.NeedsReview);
        device.local = invalid();
        final PurpleSyncCore.Review refused = review(device, cloud);
        check(refused.status == PurpleSyncCore.ReviewStatus.InvalidSettings);
        check(refused.localStatus == PurpleSyncSettingsFile.Status.Invalid);
        check(refused.message == PurpleSyncCore.Message.InvalidSettings);
        device.removeLocal();
        final PurpleSyncCore.Review none = review(device, cloud);
        check(none.status == PurpleSyncCore.ReviewStatus.Ready);
        check(none.localStatus == PurpleSyncSettingsFile.Status.Absent);
        check(none.localFingerprint.equals(fingerprint(new byte[0])));
    }

    private static void testEmptyJoinAndLocalChanges() throws Exception {
        begin("empty, join and local changes");
        final Cloud cloud = new Cloud();
        final Device device = new Device('a');
        device.setLocal(T0);
        final PurpleSyncCore.Review review = review(device, cloud);
        check(review.status == PurpleSyncCore.ReviewStatus.Ready);
        check(!review.bound);
        check(review.space.isEmpty());
        check(review.verdict == PurpleSyncCore.Verdict.Empty);
        check(review.message == PurpleSyncCore.Message.EmptyUnbound);
        check(review.action == PurpleSyncCore.Action.Publish);
        check(review.localPublishable);
        check(apply(device, cloud, review, "1.x").status
                == PurpleSyncCore.ApplyStatus.InvalidChoice);
        check(device.state == null);

        final ApplyRun applied = apply(device, cloud, review, null);
        check(applied.status == PurpleSyncCore.ApplyStatus.Applied);
        check(applied.joined);
        check(!applied.wroteFile);
        check(!applied.adopted);
        check(applied.publishNeeded);
        check(applied.expectedParents.isEmpty());
        check(applied.nextVerdict == PurpleSyncCore.Verdict.Empty);
        check(applied.fingerprint.equals(fingerprint(T0)));
        check(applied.promiseKept);
        check(device.state != null && !device.install().isEmpty());
        check(device.state != null && device.base().isEmpty());
        final PurpleSyncCore.Review joinedEmpty = review(device, cloud);
        check(joinedEmpty.bound);
        check(joinedEmpty.message == PurpleSyncCore.Message.EmptyBound);

        check(publish(device, cloud, request(null, List.of("1.x"))).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(publish(device, cloud, request(applied.fingerprint,
                List.of("1.x"))).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(publish(device, cloud, request(applied.fingerprint, null)).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(cloud.size() == 0);
        final PublishRun first = publish(device, cloud,
                request(applied.fingerprint, applied.expectedParents));
        check(first.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(first.posts == 1);
        check(first.version.key.equals("1." + fingerprint(T0)));
        check(device.base().equals(first.version.key));
        check(device.pendingSeq() == 0);
        check(device.staged == null);

        final PurpleSyncCore.Review upToDate = review(device, cloud);
        check(upToDate.status == PurpleSyncCore.ReviewStatus.Ready);
        check(upToDate.bound);
        check(upToDate.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(upToDate.ownHead != null);
        check(upToDate.ownHead != null
                && upToDate.ownHead.key.equals(first.version.key));
        check(upToDate.ownHead != null
                && upToDate.ownHead.messageId == first.messageId);
        check(upToDate.message == PurpleSyncCore.Message.UpToDateAlone);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.AlreadySynced);
        check(apply(device, cloud, upToDate, null).status
                == PurpleSyncCore.ApplyStatus.InvalidChoice);

        device.setLocal(T1);
        final PurpleSyncCore.Review changed = review(device, cloud);
        check(changed.verdict == PurpleSyncCore.Verdict.LocalChanges);
        check(!changed.ownStale);
        check(changed.message == PurpleSyncCore.Message.LocalChangesEdited);
        check(changed.action == PurpleSyncCore.Action.PublishChanges);
        check(publish(device, cloud, request(fingerprint(T0),
                List.of(first.version.key))).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(publish(device, cloud, request(null, List.of())).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(publish(device, cloud, request(fingerprint(T1), List.of())).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(publish(device, cloud, new PurpleSyncCore.PostRequest(false, null,
                null)).status == PurpleSyncCore.PublishStatus.NeedsReview);
        final ApplyRun noop = apply(device, cloud, changed, null);
        check(noop.status == PurpleSyncCore.ApplyStatus.Applied);
        check(!noop.joined && !noop.wroteFile && !noop.adopted);
        check(noop.publishNeeded);
        check(noop.expectedParents.equals(List.of(first.version.key)));
        final PublishRun second = publish(device, cloud,
                request(noop.fingerprint, noop.expectedParents));
        check(second.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        final Cloud oldCloud = cloud.copy();
        final Remote early = remote('e', "Linux");
        final String olderSpace = id(PurpleAccountSyncCore
                .formatTimeOrderedSpaceId(1));
        post(oldCloud, early, olderSpace, TB);
        check(review(device, oldCloud).status
                == PurpleSyncCore.ReviewStatus.NeedsReview);

        final Cloud cloneCloud = cloud.copy();
        final Remote clone = new Remote(device.install(),
                "desktop:someone-else", "macOS", 2);
        post(cloneCloud, clone, device.space(), TB, second.version);
        check(review(device, cloneCloud).status
                == PurpleSyncCore.ReviewStatus.CloneDetected);
        check(review(device, cloneCloud).message
                == PurpleSyncCore.Message.CloneDetected);
        check(publishLocal(device, cloneCloud).status
                == PurpleSyncCore.PublishStatus.CloneDetected);

        final Cloud incomplete = cloud.copy();
        incomplete.scanComplete = false;
        check(review(device, incomplete).status
                == PurpleSyncCore.ReviewStatus.Incomplete);
        check(review(device, incomplete).message
                == PurpleSyncCore.Message.Incomplete);
        check(publishLocal(device, incomplete).status
                == PurpleSyncCore.PublishStatus.Incomplete);
    }

    private static void testChooseUpdateAdoptConflict() {
        begin("choose, update, adopt and conflict");
        final Cloud cloud = new Cloud();
        final Device device = new Device('a');
        device.setLocal(T0);
        check(apply(device, cloud, review(device, cloud), null).status
                == PurpleSyncCore.ApplyStatus.Applied);
        final PublishRun v1 = publishLocal(device, cloud);
        check(v1.status == PurpleSyncCore.PublishStatus.Confirmed);
        device.setLocal(T1);
        final PublishRun v2 = publishLocal(device, cloud);
        check(v2.status == PurpleSyncCore.PublishStatus.Confirmed);
        final String space = device.space();

        final Remote b = remote('b', "Android");
        final Version b1 = post(cloud, b, space, TB);
        final PurpleSyncCore.Review choose = review(device, cloud);
        check(choose.verdict == PurpleSyncCore.Verdict.Choose);
        check(choose.offered.equals(List.of(b1.key)));
        check(choose.message == PurpleSyncCore.Message.ChooseBound);
        check(choose.action == PurpleSyncCore.Action.Choose);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(apply(device, cloud, choose, "1.0:x").status
                == PurpleSyncCore.ApplyStatus.InvalidChoice);
        final PurpleSyncCore.ApplyPlan choice = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.bound(cloud.inventory(),
                        device.state, device.staged, device.local,
                        choose.stamp, b1.key));
        check(choice.status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(choice.writeRemote && choice.publish);
        final ApplyRun picked = apply(device, cloud, choose, b1.key);
        check(picked.status == PurpleSyncCore.ApplyStatus.Applied);
        check(picked.wroteFile && picked.adopted && !picked.joined);
        check(Arrays.equals(device.local.bytes, TB));
        check(!picked.update);
        check(picked.versionKey.equals(v2.version.key));
        check(picked.source != null && picked.source.platform.equals("Android"));
        check(picked.source != null && picked.source.install.equals(b.install));
        check(!picked.otherVersionsRemain);
        check(device.base().equals(b1.key));
        check(seenSeq(device.state, b.install) == 1);
        check(picked.nextVerdict == PurpleSyncCore.Verdict.LocalChanges);
        check(picked.publishNeeded);
        check(picked.promiseKept);
        check(sameSet(picked.expectedParents, choice.parents));
        check(sameSet(picked.expectedParents, List.of(b1.key, v2.version.key)));
        final PublishRun repick = publish(device, cloud,
                request(picked.fingerprint, picked.expectedParents));
        check(repick.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);
        check(review(device, cloud).message == PurpleSyncCore.Message.UpToDateWith);

        final Version b2 = post(cloud, b, space, TB2, repick.version);
        final PurpleSyncCore.Review update = review(device, cloud);
        check(update.verdict == PurpleSyncCore.Verdict.UpdateReady);
        check(update.offered.equals(List.of(b2.key)));
        check(update.message == PurpleSyncCore.Message.UpdateReady);
        check(update.action == PurpleSyncCore.Action.ReviewUpdate);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(apply(device, cloud, update, null).status
                == PurpleSyncCore.ApplyStatus.InvalidChoice);
        final ApplyRun applied = apply(device, cloud, update, b2.key);
        check(applied.status == PurpleSyncCore.ApplyStatus.Applied);
        check(applied.wroteFile && applied.adopted);
        check(Arrays.equals(device.local.bytes, TB2));
        check(applied.update);
        check(applied.versionKey.equals(repick.version.key));
        check(applied.nextVerdict == PurpleSyncCore.Verdict.UpToDate);
        check(!applied.publishNeeded);
        check(applied.expectedParents.isEmpty());
        check(applied.promiseKept);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        device.setLocal(TB);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.LocalChanges);
        device.setLocal(TB2);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        final Version b3 = post(cloud, b, space, TB3, b2);
        final byte[] beforeCrash = device.state;
        device.setLocal(TB3);
        final PurpleSyncCore.Review crashed = review(device, cloud);
        check(crashed.verdict == PurpleSyncCore.Verdict.Adopt);
        check(crashed.message == PurpleSyncCore.Message.AdoptBound);
        check(crashed.action == PurpleSyncCore.Action.None);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(crashed.same.equals(List.of(b3.key)));
        final ApplyRun adopted = apply(device, cloud, crashed, null);
        check(adopted.status == PurpleSyncCore.ApplyStatus.Applied);
        check(adopted.adopted && !adopted.wroteFile);
        check(adopted.nextVerdict == PurpleSyncCore.Verdict.UpToDate);
        check(!adopted.publishNeeded);
        check(device.base().equals(b3.key));
        check(!Arrays.equals(device.state, beforeCrash));
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        device.setLocal(TL);
        final Version b4 = post(cloud, b, space, TB4, b3);
        final PurpleSyncCore.Review conflict = review(device, cloud);
        check(conflict.verdict == PurpleSyncCore.Verdict.Conflict);
        check(conflict.offered.equals(List.of(b4.key)));
        check(conflict.message == PurpleSyncCore.Message.ConflictConcurrent);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        final ApplyRun kept = apply(device, cloud, conflict, null);
        check(kept.status == PurpleSyncCore.ApplyStatus.Applied);
        check(!kept.wroteFile && !kept.adopted);
        check(kept.publishNeeded);
        check(kept.expectedParents.equals(List.of(b4.key)));
        check(Arrays.equals(device.local.bytes, TL));
        check(publish(device, cloud, request(kept.fingerprint,
                List.of(b4.key, b3.key))).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        final PublishRun keptPost = publish(device, cloud,
                request(kept.fingerprint, kept.expectedParents));
        check(keptPost.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        final Remote c = remote('c', "Windows");
        final Version b5 = post(cloud, b, space, TB5, keptPost.version);
        final Version c1 = post(cloud, c, space, TC);
        final PurpleSyncCore.Review split = review(device, cloud);
        check(split.verdict == PurpleSyncCore.Verdict.Conflict);
        check(split.offered.size() == 2);
        final PurpleSyncCore.ApplyPlan promise = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.bound(cloud.inventory(),
                        device.state, device.staged, device.local, split.stamp,
                        c1.key));
        check(promise.status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(promise.publish);
        check(sameSet(promise.parents, List.of(c1.key, b5.key)));
        device.setLocal(TC);
        final PurpleSyncCore.Review afterCrash = review(device, cloud);
        check(afterCrash.verdict == PurpleSyncCore.Verdict.Conflict);
        check(afterCrash.offered.equals(List.of(b5.key)));
        check(afterCrash.same.equals(List.of(c1.key)));
        final ApplyRun recovered = apply(device, cloud, afterCrash, null);
        check(recovered.status == PurpleSyncCore.ApplyStatus.Applied);
        check(recovered.adopted && !recovered.wroteFile);
        check(recovered.publishNeeded);
        check(sameSet(recovered.expectedParents, promise.parents));
        final PublishRun recoveredPost = publish(device, cloud,
                request(recovered.fingerprint, recovered.expectedParents));
        check(recoveredPost.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        final Version b6 = post(cloud, b, space, TB6, recoveredPost.version);
        final Version c2 = post(cloud, c, space, TC2, recoveredPost.version);
        final PurpleSyncCore.Review twoAhead = review(device, cloud);
        check(twoAhead.verdict == PurpleSyncCore.Verdict.Conflict);
        check(twoAhead.offered.size() == 2);
        check(twoAhead.message == PurpleSyncCore.Message.ConflictSplitBound);
        final PurpleSyncCore.ApplyPlan pickPromise = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.bound(cloud.inventory(),
                        device.state, device.staged, device.local,
                        twoAhead.stamp, b6.key));
        final ApplyRun pickB = apply(device, cloud, twoAhead, b6.key);
        check(pickB.status == PurpleSyncCore.ApplyStatus.Applied);
        check(Arrays.equals(device.local.bytes, TB6));
        check(pickB.publishNeeded);
        check(pickB.otherVersionsRemain);
        check(!pickB.update);
        check(pickB.promiseKept);
        check(pickPromise.publish);
        check(sameSet(pickB.expectedParents, List.of(b6.key, c2.key)));
        check(sameSet(pickB.expectedParents, pickPromise.parents));
        final PurpleSyncCore.Review staleClick = review(device, cloud);
        check(staleClick.verdict == PurpleSyncCore.Verdict.Choose);
        final Cloud newer = cloud.copy();
        post(newer, c, space, TC, c2);
        check(publish(device, newer, request(pickB.fingerprint,
                pickB.expectedParents)).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(device.pendingSeq() == 0);
        final PublishRun resolved = publish(device, cloud,
                request(pickB.fingerprint, pickB.expectedParents));
        check(resolved.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);
    }

    private static void testRechecks() {
        begin("rechecks");
        final Cloud cloud = new Cloud();
        final Device device = new Device('a');
        device.setLocal(T0);
        check(apply(device, cloud, review(device, cloud), null).status
                == PurpleSyncCore.ApplyStatus.Applied);
        final PublishRun v1 = publishLocal(device, cloud);
        check(v1.status == PurpleSyncCore.PublishStatus.Confirmed);
        final String space = device.space();
        final Remote b = remote('b', "Android");
        final Version b1 = post(cloud, b, space, TB, v1.version);

        final PurpleSyncCore.Review review = review(device, cloud);
        check(review.verdict == PurpleSyncCore.Verdict.UpdateReady);
        final byte[] stateBefore = device.state;
        device.setLocal(T1);
        check(apply(device, cloud, review, b1.key).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(Arrays.equals(device.local.bytes, T1));
        check(Arrays.equals(device.state, stateBefore));
        device.removeLocal();
        check(apply(device, cloud, review, b1.key).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        device.local = invalid();
        check(apply(device, cloud, review, b1.key).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        device.setLocal(T0);

        final Cloud moved = cloud.copy();
        post(moved, b.copy(), space, TB2, b1);
        check(apply(device, moved.inventory(), review, b1.key).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(Arrays.equals(device.local.bytes, T0));

        final PurpleSyncCore.ApplyPlan stale = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.bound(cloud.inventory(),
                        device.state, device.staged, device.local,
                        "0".repeat(64), b1.key));
        check(stale.isValid());
        check(stale.status == PurpleSyncCore.ApplyPlanStatus.NeedsRecheck);
        check(stale.source == null && !stale.writeRemote);
        final PurpleSyncCore.ApplyCompletion staleCompletion =
                PurpleSyncCore.completeApply(PurpleSyncCore.ApplyRequest.bound(
                        cloud.inventory(), device.state, device.staged,
                        device.local, "0".repeat(64), b1.key), present(TB));
        check(staleCompletion.plan == PurpleSyncCore.ApplyPlanStatus.NeedsRecheck);
        check(staleCompletion.status == PurpleSyncCore.CompletionStatus.InvalidPlan);
        check(staleCompletion.nextState == null);
        check(Arrays.equals(device.state, stateBefore));

        device.setLocal(TB);
        final PurpleSyncCore.Review next = review(device, cloud);
        check(next.status == PurpleSyncCore.ReviewStatus.Ready);
        check(next.verdict == PurpleSyncCore.Verdict.Adopt);
        final ApplyRun silent = apply(device, cloud, next, null);
        check(silent.status == PurpleSyncCore.ApplyStatus.Applied);
        check(silent.adopted && !silent.wroteFile);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        device.setLocal(T1);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.Confirmed);
        device.setLocal(T0);
        final PublishRun stagedRun = publish(device, cloud,
                localRequest(device, cloud), PostMode.Fail);
        check(stagedRun.status == PurpleSyncCore.PublishStatus.OutcomeUnknown);
        check(stagedRun.posts == 1);
        check(device.pendingSeq() != 0);
        final PurpleSyncCore.Review pendingReview = review(device, cloud);
        check(pendingReview.status == PurpleSyncCore.ReviewStatus.Ready);
        check(pendingReview.verdict == PurpleSyncCore.Verdict.Pending);
        check(pendingReview.pending);
        check(pendingReview.message == PurpleSyncCore.Message.Pending);
        check(pendingReview.action == PurpleSyncCore.Action.FinishSending);
        check(apply(device, cloud, pendingReview, null).status
                == PurpleSyncCore.ApplyStatus.InvalidChoice);
        cloud.add(device.staged);
        final PurpleSyncCore.Review foundPending = review(device, cloud);
        check(foundPending.status == PurpleSyncCore.ReviewStatus.Ready);
        check(foundPending.verdict == PurpleSyncCore.Verdict.Pending);
        check(foundPending.ownHead != null);
        final Cloud otherSpace = cloud.copy();
        post(otherSpace, remote('e', "Linux"), id(PurpleAccountSyncCore
                .formatTimeOrderedSpaceId(1)), TB);
        final PurpleSyncCore.Review paused = review(device, otherSpace);
        check(paused.status == PurpleSyncCore.ReviewStatus.NeedsReview);
        check(paused.message == PurpleSyncCore.Message.NeedsReviewWithPending);
    }

    private static void testJoinVariants() {
        begin("join variants");
        final Cloud cloud = new Cloud();
        final String space = id(PurpleAccountSyncCore.formatSpaceId(
                fill(16, 'j')));
        final Remote b = remote('b', "Android");
        final Version b1 = post(cloud, b, space, TB);

        final Device absentDevice = new Device('f');
        absentDevice.removeLocal();
        final PurpleSyncCore.Review review = review(absentDevice, cloud);
        check(review.status == PurpleSyncCore.ReviewStatus.Ready);
        check(review.verdict == PurpleSyncCore.Verdict.Choose);
        check(review.message == PurpleSyncCore.Message.ChooseUnbound);
        final ApplyRun joined = apply(absentDevice, cloud, review, b1.key);
        check(joined.status == PurpleSyncCore.ApplyStatus.Applied);
        check(joined.joined && joined.wroteFile && joined.adopted);
        check(!joined.publishNeeded);
        check(joined.versionKey.isEmpty());
        check(joined.nextVerdict == PurpleSyncCore.Verdict.UpToDate);
        check(Arrays.equals(absentDevice.local.bytes, TB));
        check(absentDevice.state != null && absentDevice.space().equals(space));
        check(absentDevice.state != null && absentDevice.base().equals(b1.key));
        check(review(absentDevice, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);
        check(publishLocal(absentDevice, cloud).status
                == PurpleSyncCore.PublishStatus.AlreadySynced);

        final Device same = new Device('g');
        same.setLocal(TB);
        final PurpleSyncCore.Review sameReview = review(same, cloud);
        check(sameReview.verdict == PurpleSyncCore.Verdict.Adopt);
        check(sameReview.message == PurpleSyncCore.Message.AdoptUnbound);
        check(sameReview.action == PurpleSyncCore.Action.Join);
        final ApplyRun adopted = apply(same, cloud, sameReview, null);
        check(adopted.status == PurpleSyncCore.ApplyStatus.Applied);
        check(adopted.joined && adopted.adopted && !adopted.wroteFile);
        check(!adopted.publishNeeded);
        check(review(same, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        final Remote c = remote('c', "Windows");
        final Cloud both = cloud.copy();
        final Version c1 = post(both, c, space, TC);
        final Device keep = new Device('h');
        keep.setLocal(T0);
        final PurpleSyncCore.Review keepReview = review(keep, both);
        check(keepReview.verdict == PurpleSyncCore.Verdict.Conflict);
        check(keepReview.offered.size() == 2);
        check(keepReview.message == PurpleSyncCore.Message.ConflictSplitUnbound);
        final PurpleSyncCore.ApplyPlan keepChoice = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.unbound(both.inventory(), keep.local,
                        keepReview.stamp, null));
        check(keepChoice.status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(keepChoice.join);
        check(keepChoice.space.equals(space));
        final ApplyRun kept = apply(keep, both, keepReview, null);
        check(kept.status == PurpleSyncCore.ApplyStatus.Applied);
        check(kept.joined && !kept.wroteFile && !kept.adopted);
        check(kept.publishNeeded);
        check(sameSet(kept.expectedParents, keepChoice.parents));
        check(sameSet(kept.expectedParents, List.of(b1.key, c1.key)));
        check(publishLocal(keep, both).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        final PublishRun keptPost = publish(keep, both,
                request(kept.fingerprint, kept.expectedParents));
        check(keptPost.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(keep, both).verdict == PurpleSyncCore.Verdict.UpToDate);

        final Remote d = remote('d', "Linux");
        final Cloud trio = cloud.copy();
        post(trio, c.copy(), space, TC);
        final Version d1 = post(trio, d, space, T0);
        final Device keepSame = new Device('i');
        keepSame.setLocal(T0);
        final PurpleSyncCore.Review keepSameReview = review(keepSame, trio);
        check(keepSameReview.verdict == PurpleSyncCore.Verdict.Choose);
        check(keepSameReview.offered.size() == 2);
        check(keepSameReview.same.equals(List.of(d1.key)));
        final ApplyRun keptSame = apply(keepSame, trio, keepSameReview, null);
        check(keptSame.status == PurpleSyncCore.ApplyStatus.Applied);
        check(keptSame.adopted && !keptSame.wroteFile);
        check(keptSame.publishNeeded);
        check(sameSet(keptSame.expectedParents, List.of(b1.key, c1.key)));
        final PublishRun keptSamePost = publish(keepSame, trio,
                request(keptSame.fingerprint, keptSame.expectedParents));
        check(keptSamePost.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(keepSame, trio).verdict == PurpleSyncCore.Verdict.UpToDate);

        final Device stale = new Device('k');
        stale.setLocal(T0);
        final PurpleSyncCore.Review first = review(stale, cloud);
        final Cloud grown = cloud.copy();
        post(grown, b.copy(), space, TB2, b1);
        check(apply(stale, grown, first, b1.key).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(stale.state == null);
        check(Arrays.equals(stale.local.bytes, T0));
        stale.setLocal(T1);
        check(apply(stale, cloud, first, b1.key).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(stale.state == null);

        final Device empty = new Device('l');
        empty.setLocal(T0);
        final PurpleSyncCore.Review emptyReview = review(empty, new Cloud());
        final Cloud taken = new Cloud();
        post(taken, remote('e', "Linux"), space, TB);
        check(apply(empty, taken, emptyReview, null).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(empty.state == null);
    }

    private static void testPublisher() {
        begin("publisher");
        final PurpleSyncCore.PostRequest full = request("f", List.of());
        final PurpleSyncCore.PostRequest pendingWith =
                new PurpleSyncCore.PostRequest(true, "f", List.of());
        final PurpleSyncCore.PostRequest nothing = request(null, null);
        check(entry(nothing, false).entry == PurpleSyncCore.PostEntry.Refuse);
        check(entry(nothing, true).entry == PurpleSyncCore.PostEntry.Refuse);
        check(entry(full, false).entry == PurpleSyncCore.PostEntry.NewContent);
        check(entry(full, true).entry == PurpleSyncCore.PostEntry.Refuse);
        check(entry(request("f", null), false).entry
                == PurpleSyncCore.PostEntry.Refuse);
        check(entry(request(null, List.of()), false).entry
                == PurpleSyncCore.PostEntry.Refuse);
        check(entry(pendingOnly(), true).entry
                == PurpleSyncCore.PostEntry.FinishStaged);
        check(entry(pendingOnly(), false).entry == PurpleSyncCore.PostEntry.Refuse);
        check(entry(pendingWith, true).entry == PurpleSyncCore.PostEntry.Refuse);
        check(entry(pendingWith, false).entry == PurpleSyncCore.PostEntry.Refuse);
        check(entry(new PurpleSyncCore.PostRequest(true, "f", null), true).entry
                == PurpleSyncCore.PostEntry.Refuse);

        final Cloud cloud = new Cloud();
        final Device device = new Device('a');
        device.setLocal(T0);
        final ApplyRun joined = apply(device, cloud, review(device, cloud), null);
        check(joined.status == PurpleSyncCore.ApplyStatus.Applied);
        check(joined.publishNeeded);
        PublishRun run = publish(device, cloud, nothing);
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);
        run = publish(device, cloud, pendingOnly());
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);
        check(device.pendingSeq() == 0);
        check(cloud.size() == 0);

        final Device stopped = device.copy();
        stopped.now = 0;
        check(publish(stopped, cloud, request(joined.fingerprint,
                joined.expectedParents)).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(stopped.pendingSeq() == 0);
        final JSONObject rebound = new JSONObject(new String(device.state,
                StandardCharsets.UTF_8));
        rebound.put("binding_token", "7a".repeat(16));
        final byte[] otherToken = canonical(rebound);
        final PurpleSyncCore.PostPlan wrongToken = PurpleSyncCore.planPost(
                ACCOUNT, cloud.inventory(), otherToken, null, device.local,
                request(joined.fingerprint, joined.expectedParents), device.now,
                true);
        check(wrongToken.isValid());
        check(wrongToken.step == PurpleSyncCore.PostStep.Finish);
        check(wrongToken.status == PurpleSyncCore.PublishStatus.NeedsReview);

        device.platform = "Windows";
        final PublishRun first = publish(device, cloud,
                request(joined.fingerprint, joined.expectedParents));
        device.platform = PurpleSyncCore.WRITER_PLATFORM;
        check(first.status == PurpleSyncCore.PublishStatus.Confirmed
                && first.posts == 1);
        final PurpleAccountSyncCore.Result firstHeader =
                PurpleAccountSyncCore.inspectConfigRecord(first.posted);
        check(firstHeader.platform.equals("Windows"));
        check(firstHeader.app.equals(PurpleSyncCore.WRITER_APP));
        check(firstHeader.at == device.now);
        check(firstHeader.seq == 1);
        check(firstHeader.device.equals(device.device));
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        device.setLocal(T1);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.LocalChanges);
        run = publish(device, cloud, nothing);
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);
        run = publish(device, cloud, pendingOnly());
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);

        final PurpleSyncCore.PostRequest changes = localRequest(device, cloud);
        check(changes.expectedParents != null);
        final PublishRun lost = publish(device, cloud, changes,
                PostMode.LoseReceipt);
        check(lost.status == PurpleSyncCore.PublishStatus.OutcomeUnknown
                && lost.posts == 1);
        check(device.pendingSeq() != 0);
        final PurpleSyncInventory snapshot = cloud.inventory();
        final PurpleSyncCore.Review pending = review(device, snapshot);
        check(pending.status == PurpleSyncCore.ReviewStatus.Ready);
        check(pending.verdict == PurpleSyncCore.Verdict.Pending);
        check(pending.action == PurpleSyncCore.Action.FinishSending);

        run = publish(device, cloud, snapshot, changes);
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);
        run = publish(device, cloud, snapshot, nothing);
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);
        run = publish(device, cloud, snapshot, new PurpleSyncCore.PostRequest(
                true, changes.expectedFingerprint, changes.expectedParents));
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);
        check(device.pendingSeq() != 0);

        final PurpleSyncCore.PostPlan found = PurpleSyncCore.planPost(ACCOUNT,
                snapshot, device.state, device.staged, invalid(), pendingOnly(),
                device.now, true);
        check(found.step == PurpleSyncCore.PostStep.ConfirmFound);
        check(found.messageId > 0);
        check(Arrays.equals(found.record, device.staged));
        check(found.own == PurpleSyncCore.OwnStatus.PendingFound);

        final PublishRun boxB = publish(device, cloud, snapshot, pendingOnly());
        check(boxB.status == PurpleSyncCore.PublishStatus.Confirmed && boxB.posts == 0);
        check(device.pendingSeq() == 0);
        device.setLocal(TL);
        final int recordsBefore = cloud.size();
        final byte[] stateBefore = device.state;
        final PublishRun boxA = publish(device, cloud, snapshot, pendingOnly());
        check(boxA.status == PurpleSyncCore.PublishStatus.NeedsReview && boxA.posts == 0);
        check(cloud.size() == recordsBefore);
        check(Arrays.equals(device.state, stateBefore));
        final PurpleSyncCore.Review afterEdit = review(device, cloud);
        check(afterEdit.verdict == PurpleSyncCore.Verdict.LocalChanges);
        check(afterEdit.action == PurpleSyncCore.Action.PublishChanges);

        final PurpleSyncCore.PostRequest edit = localRequest(device, cloud);
        final PublishRun failed = publish(device, cloud, edit, PostMode.Fail);
        check(failed.status == PurpleSyncCore.PublishStatus.OutcomeUnknown
                && failed.posts == 1);
        check(cloud.size() == recordsBefore);
        run = publish(device, cloud, edit);
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);
        final PurpleSyncCore.PostPlan staged = PurpleSyncCore.planPost(ACCOUNT,
                cloud.inventory(), device.state, device.staged, invalid(),
                pendingOnly(), device.now, false);
        check(staged.step == PurpleSyncCore.PostStep.Post);
        check(Arrays.equals(staged.record, device.staged));
        final PurpleSyncCore.PostPlan held = PurpleSyncCore.planPost(ACCOUNT,
                cloud.inventory(), device.state, device.staged, invalid(),
                pendingOnly(), device.now, true);
        check(held.step == PurpleSyncCore.PostStep.Finish);
        check(held.status == PurpleSyncCore.PublishStatus.StillSending);
        check(held.record == null);
        final PurpleSyncCore.PostPlan emptyStage = PurpleSyncCore.planPost(
                ACCOUNT, cloud.inventory(), device.state, null, device.local,
                pendingOnly(), device.now, true);
        check(emptyStage.step == PurpleSyncCore.PostStep.Finish);
        check(emptyStage.status == PurpleSyncCore.PublishStatus.NeedsReview);
        final PublishRun finished = publish(device, cloud, pendingOnly());
        check(finished.status == PurpleSyncCore.PublishStatus.Confirmed
                && finished.posts == 1);
        check(Arrays.equals(finished.posted, failed.posted));
        check(device.pendingSeq() == 0);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        final int late = cloud.add(failed.posted);
        check(late > finished.messageId);
        final PurpleSyncCore.Review duplicated = review(device, cloud);
        check(duplicated.status == PurpleSyncCore.ReviewStatus.Ready);
        check(duplicated.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(duplicated.ownHead != null
                && duplicated.ownHead.messageId == finished.messageId);
        device.setLocal(T1);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        device.setLocal(TB);
        final PublishRun queued = publish(device, cloud,
                localRequest(device, cloud), PostMode.Fail);
        check(queued.status == PurpleSyncCore.PublishStatus.OutcomeUnknown
                && queued.posts == 1);
        final PurpleSyncInventory beforeQueue = cloud.inventory();
        final int drained = cloud.add(queued.posted);
        final PublishRun again = publish(device, cloud, beforeQueue, pendingOnly());
        check(again.status == PurpleSyncCore.PublishStatus.Confirmed
                && again.posts == 1);
        check(Arrays.equals(again.posted, queued.posted));
        check(again.messageId > drained);
        final PurpleSyncCore.Review twice = review(device, cloud);
        check(twice.status == PurpleSyncCore.ReviewStatus.Ready);
        check(twice.verdict == PurpleSyncCore.Verdict.UpToDate);
        check(twice.ownHead != null && twice.ownHead.messageId == drained);
        device.setLocal(T0);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.Confirmed);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        device.removeLocal();
        check(publish(device, cloud, localRequest(device, cloud)).status
                == PurpleSyncCore.PublishStatus.InvalidSettings);
        device.setLocal(new byte[0]);
        check(publish(device, cloud, localRequest(device, cloud)).status
                == PurpleSyncCore.PublishStatus.InvalidSettings);
        device.setLocal(utf8("not [valid"));
        check(publish(device, cloud, localRequest(device, cloud)).status
                == PurpleSyncCore.PublishStatus.InvalidSettings);
        device.setLocal(bigText());
        check(publish(device, cloud, localRequest(device, cloud)).status
                == PurpleSyncCore.PublishStatus.InvalidSettings);
        check(device.pendingSeq() == 0);

        UserConfig.userId = USER + 1;
        check(PurpleSyncCore.planPost(ACCOUNT, cloud.inventory(), device.state,
                null, device.local, request("f", List.of()), device.now,
                false).error.equals("BindingUnavailable"));
        UserConfig.userId = USER;
        final PurpleSyncInventory foreign = new PurpleSyncInventory.Builder(
                USER + 1).build(true);
        check(PurpleSyncCore.planPost(ACCOUNT, foreign, device.state, null,
                device.local, request("f", List.of()), device.now, false)
                .error.equals("AccountChanged"));
        check(PurpleSyncCore.checkStagedPost(ACCOUNT, foreign, device.state,
                new byte[0]).error.equals("AccountChanged"));
        UserConfig.activated = false;
        check(PurpleSyncCore.checkStagedPost(ACCOUNT, cloud.inventory(),
                device.state, new byte[0]).error.equals("AccountUnavailable"));
        UserConfig.activated = true;
    }

    private static byte[] bigText() {
        final StringBuilder big = new StringBuilder("version = 1\n");
        while (big.length() < 250 * 1024) {
            big.append("# \"quoted\" padding line to grow the record\n");
        }
        return utf8(big.toString());
    }

    private static void testSendQueue() {
        begin("send queue");
        final Cloud cloud = new Cloud();
        final Device device = new Device('a');
        device.setLocal(T0);
        check(apply(device, cloud, review(device, cloud), null).status
                == PurpleSyncCore.ApplyStatus.Applied);

        device.queued = true;
        final PublishRun first = publishLocal(device, cloud);
        check(first.status == PurpleSyncCore.PublishStatus.Confirmed
                && first.posts == 1);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.AlreadySynced);
        PublishRun run = publish(device, cloud, request(null, null));
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);
        run = publish(device, cloud, pendingOnly());
        check(run.status == PurpleSyncCore.PublishStatus.NeedsReview && run.posts == 0);

        device.queued = false;
        device.setLocal(T1);
        final PublishRun queued = publish(device, cloud,
                localRequest(device, cloud), PostMode.Fail);
        check(queued.status == PurpleSyncCore.PublishStatus.OutcomeUnknown
                && queued.posts == 1);
        check(device.pendingSeq() != 0);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.Pending);

        device.queued = true;
        final int recordsBefore = cloud.size();
        final byte[] stateBefore = device.state;
        final byte[] stagedBefore = device.staged;
        final PublishRun held = publish(device, cloud, pendingOnly());
        check(held.status == PurpleSyncCore.PublishStatus.StillSending);
        check(held.posts == 0);
        check(cloud.size() == recordsBefore);
        check(Arrays.equals(device.state, stateBefore));
        check(Arrays.equals(device.staged, stagedBefore));
        check(publish(device, cloud, localRequest(device, cloud)).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        final Cloud incomplete = cloud.copy();
        incomplete.scanComplete = false;
        check(publish(device, incomplete, pendingOnly()).status
                == PurpleSyncCore.PublishStatus.Incomplete);
        final Cloud elsewhere = cloud.copy();
        post(elsewhere, remote('e', "Linux"), id(PurpleAccountSyncCore
                .formatTimeOrderedSpaceId(1)), TB);
        check(publish(device, elsewhere, pendingOnly()).status
                == PurpleSyncCore.PublishStatus.NeedsReview);
        check(Arrays.equals(device.state, stateBefore));

        final int arrived = cloud.add(queued.posted);
        final PublishRun confirmed = publish(device, cloud, pendingOnly());
        check(confirmed.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(confirmed.posts == 0);
        check(confirmed.messageId == arrived);
        check(device.pendingSeq() == 0);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);

        device.queued = false;
        device.setLocal(TB);
        final PublishRun failed = publish(device, cloud,
                localRequest(device, cloud), PostMode.Fail);
        check(failed.status == PurpleSyncCore.PublishStatus.OutcomeUnknown
                && failed.posts == 1);
        device.queued = true;
        check(publish(device, cloud, pendingOnly()).status
                == PurpleSyncCore.PublishStatus.StillSending);
        device.queued = false;
        final PublishRun resent = publish(device, cloud, pendingOnly());
        check(resent.status == PurpleSyncCore.PublishStatus.Confirmed
                && resent.posts == 1);
        check(Arrays.equals(resent.posted, failed.posted));
        check(device.pendingSeq() == 0);
        check(review(device, cloud).verdict == PurpleSyncCore.Verdict.UpToDate);
    }

    private static void testStamp() throws Exception {
        begin("stamp");
        final Cloud cloud = new Cloud();
        final Device device = new Device('a');
        device.setLocal(T0);
        check(apply(device, cloud, review(device, cloud), null).status
                == PurpleSyncCore.ApplyStatus.Applied);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.Confirmed);
        final Remote b = remote('b', "Android");
        final Remote c = remote('c', "Windows");
        post(cloud, b, device.space(), TB, versionOf(cloud.records.get(0)));
        post(cloud, c, device.space(), TC);
        device.setLocal(T1);
        final PurpleSyncCore.Review review = review(device, cloud);
        check(review.status == PurpleSyncCore.ReviewStatus.Ready);
        check(review.ownHead != null);
        check(review.heads.size() == 2);
        check(!review.offered.isEmpty());
        final String stamp = review.stamp;
        check(stamp.matches("[0-9a-f]{64}"));
        check(stamp.equals(review(device, cloud).stamp));
        final Device edited = device.copy();
        edited.setLocal(T0);
        check(!review(edited, cloud).stamp.equals(stamp));
        final Cloud grown = cloud.copy();
        post(grown, remote('d', "Linux"), device.space(), TC2);
        check(!review(device, grown).stamp.equals(stamp));

        final Device fresh = new Device('m');
        fresh.setLocal(T0);
        final PurpleSyncCore.Review unbound = review(fresh, cloud);
        check(!unbound.bound);
        check(unbound.status == PurpleSyncCore.ReviewStatus.Ready);
        final PurpleSyncSettingsFile.Contents preJoin = fresh.local;
        check(join(fresh, unbound.space));
        final PurpleSyncCore.Review bound = review(fresh, cloud);
        check(!bound.stamp.equals(unbound.stamp));
        final PurpleSyncCore.ApplyPlan joinedPlan = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.afterJoin(cloud.inventory(),
                        fresh.state, fresh.local, unbound.stamp, null, preJoin));
        check(joinedPlan.status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(!joinedPlan.join);
        check(PurpleSyncCore.planApply(PurpleSyncCore.ApplyRequest.bound(
                cloud.inventory(), fresh.state, null, fresh.local, bound.stamp,
                null)).status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(PurpleSyncCore.planApply(PurpleSyncCore.ApplyRequest.bound(
                cloud.inventory(), fresh.state, null, fresh.local,
                unbound.stamp, null)).status
                == PurpleSyncCore.ApplyPlanStatus.NeedsRecheck);
        check(PurpleSyncCore.planApply(PurpleSyncCore.ApplyRequest.afterJoin(
                cloud.inventory(), fresh.state, fresh.local, bound.stamp, null,
                preJoin)).status == PurpleSyncCore.ApplyPlanStatus.NeedsRecheck);
        check(PurpleSyncCore.planApply(PurpleSyncCore.ApplyRequest.afterJoin(
                cloud.inventory(), fresh.state, fresh.local, unbound.stamp,
                null, present(T1))).status
                == PurpleSyncCore.ApplyPlanStatus.NeedsRecheck);

        final Device lonely = new Device('n');
        lonely.setLocal(T0);
        final PurpleSyncCore.Review nothing = review(lonely, new Cloud());
        check(nothing.space.isEmpty());
        final PurpleSyncCore.ApplyPlan beforeJoin = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.unbound(new Cloud().inventory(),
                        lonely.local, nothing.stamp, null));
        check(beforeJoin.status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(beforeJoin.join && beforeJoin.space.isEmpty());
        check(join(lonely, beforeJoin.space));
        check(!lonely.space().isEmpty());
        final PurpleSyncCore.ApplyPlan created = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.afterJoin(new Cloud().inventory(),
                        lonely.state, lonely.local, nothing.stamp, null,
                        lonely.local));
        check(created.status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(created.space.equals(lonely.space()));

        final String key = review.offered.get(0);
        final PurpleSyncCore.ApplyRequest wrong = PurpleSyncCore.ApplyRequest
                .bound(cloud.inventory(), device.state, device.staged,
                        device.local, "stale", key);
        check(PurpleSyncCore.planApply(wrong).status
                == PurpleSyncCore.ApplyPlanStatus.NeedsRecheck);
        check(PurpleSyncCore.planApply(PurpleSyncCore.ApplyRequest.bound(
                cloud.inventory(), device.state, device.staged, device.local,
                stamp, "1.0:x")).status
                == PurpleSyncCore.ApplyPlanStatus.InvalidChoice);
        final PurpleSyncCore.ApplyRequest remoteRequest = PurpleSyncCore
                .ApplyRequest.bound(cloud.inventory(), device.state,
                        device.staged, device.local, stamp, key);
        final PurpleSyncCore.ApplyPlan plan = PurpleSyncCore.planApply(
                remoteRequest);
        check(plan.status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(!plan.join);
        check(plan.writeRemote);
        check(plan.source != null && plan.source.key.equals(key));
        check(plan.source != null && plan.source.text != null);
        check(plan.writeFingerprint.equals(key.substring(key.indexOf('.') + 1)));
        check(plan.localFingerprint.equals(fingerprint(T1)));
        check(plan.versionKey.isEmpty());
        check(plan.verdict == review.verdict);

        check(PurpleSyncCore.completeApply(wrong, device.local).status
                == PurpleSyncCore.CompletionStatus.InvalidPlan);
        check(PurpleSyncCore.completeApply(remoteRequest, device.local).status
                == PurpleSyncCore.CompletionStatus.ReadBackMismatch);
        check(PurpleSyncCore.completeApply(remoteRequest, absent()).status
                == PurpleSyncCore.CompletionStatus.ReadBackMismatch);
        final PurpleSyncCore.ApplyCompletion completed = PurpleSyncCore
                .completeApply(remoteRequest, present(plan.source.text));
        check(completed.status == PurpleSyncCore.CompletionStatus.Ready);
        check(completed.fingerprint.equals(fingerprint(plan.source.text)));
        check(completed.promiseKept);
        check(completed.adopted);
        check(completed.commit == PurpleSyncCore.CommitStatus.Ready);
        check(completed.nextState != null);
        final PurpleSyncCore.ApplyRequest keepLocal = PurpleSyncCore
                .ApplyRequest.bound(cloud.inventory(), device.state,
                        device.staged, device.local, stamp, null);
        final PurpleSyncCore.ApplyPlan keepPlan = PurpleSyncCore.planApply(
                keepLocal);
        check(keepPlan.status == PurpleSyncCore.ApplyPlanStatus.Ready);
        check(!keepPlan.writeRemote);
        check(keepPlan.source == null);
        check(PurpleSyncCore.completeApply(keepLocal, present(plan.source.text))
                .status == PurpleSyncCore.CompletionStatus.ReadBackMismatch);
        final PurpleSyncCore.ApplyCompletion kept = PurpleSyncCore.completeApply(
                keepLocal, device.local);
        check(kept.status == PurpleSyncCore.CompletionStatus.Ready);
        check(kept.publishNeeded);
        check(kept.promiseKept);
        check(sameSet(kept.expectedParents, keepPlan.parents));
        check(!kept.adopted && kept.commit == null && kept.nextState == null);
    }

    private static void testCommitCheck() {
        begin("commit check");
        final Cloud cloud = new Cloud();
        final Device device = new Device('a');
        device.setLocal(T0);
        check(apply(device, cloud, review(device, cloud), null).status
                == PurpleSyncCore.ApplyStatus.Applied);
        check(publishLocal(device, cloud).status
                == PurpleSyncCore.PublishStatus.Confirmed);
        final Cloud ownOnly = cloud.copy();
        final Remote b = remote('b', "Android");
        final Version b1 = post(cloud, b, device.space(), TB);
        final byte[] state = device.state;

        final PurpleSyncCore.CommitCheck same = PurpleSyncCore.checkCommit(
                state, state);
        check(same.isValid());
        check(same.status == PurpleSyncCore.CommitStatus.Unchanged);
        check(same.state == null);

        final JSONObject next = new JSONObject(new String(state,
                StandardCharsets.UTF_8));
        final JSONObject config = next.getJSONObject("streams")
                .getJSONObject("config");
        config.getJSONObject("seen_seq").put(b.install, 1);
        config.getJSONArray("equiv").put(b1.key);
        final byte[] nextBytes = canonical(next);
        final PurpleSyncCore.CommitCheck ready = PurpleSyncCore.checkCommit(
                state, nextBytes);
        check(ready.status == PurpleSyncCore.CommitStatus.Ready);
        check(Arrays.equals(ready.state, nextBytes));
        check(seenSeq(ready.state, b.install) == 1);
        check(inspect(ready.state).install.equals(device.install()));

        config.put("pending", b1.key);
        check(PurpleSyncCore.checkCommit(state, canonical(next)).status
                == PurpleSyncCore.CommitStatus.InvalidTransition);
        config.put("pending", "");
        final JSONObject pendingState = new JSONObject(new String(state,
                StandardCharsets.UTF_8));
        pendingState.getJSONObject("streams").getJSONObject("config")
                .put("pending", b1.key);
        check(PurpleSyncCore.checkCommit(canonical(pendingState), nextBytes)
                .status == PurpleSyncCore.CommitStatus.InvalidTransition);
        final Device staging = device.copy();
        staging.setLocal(T1);
        final PublishRun stagedRun = publish(staging, ownOnly,
                localRequest(staging, ownOnly), PostMode.Fail);
        check(stagedRun.status == PurpleSyncCore.PublishStatus.OutcomeUnknown);
        check(staging.pendingSeq() != 0);
        check(PurpleSyncCore.checkCommit(staging.state, staging.state).status
                == PurpleSyncCore.CommitStatus.InvalidTransition);

        final JSONObject lower = new JSONObject(new String(ready.state,
                StandardCharsets.UTF_8));
        lower.getJSONObject("streams").getJSONObject("config")
                .getJSONObject("seen_seq").put(b.install, 0);
        check(PurpleSyncCore.checkCommit(ready.state, canonical(lower)).status
                == PurpleSyncCore.CommitStatus.InvalidTransition);
        final JSONObject dropped = new JSONObject(new String(ready.state,
                StandardCharsets.UTF_8));
        dropped.getJSONObject("streams").getJSONObject("config")
                .getJSONObject("seen_seq").remove(b.install);
        check(PurpleSyncCore.checkCommit(ready.state, canonical(dropped)).status
                == PurpleSyncCore.CommitStatus.InvalidTransition);
        final JSONObject higher = new JSONObject(new String(ready.state,
                StandardCharsets.UTF_8));
        higher.getJSONObject("streams").getJSONObject("config")
                .getJSONObject("seen_seq").put(b.install, 5);
        check(PurpleSyncCore.checkCommit(ready.state, canonical(higher)).status
                == PurpleSyncCore.CommitStatus.Ready);

        final JSONObject invalidBase = new JSONObject(new String(nextBytes,
                StandardCharsets.UTF_8));
        invalidBase.getJSONObject("streams").getJSONObject("config")
                .put("base", "not a key");
        final PurpleSyncCore.CommitCheck refused = PurpleSyncCore.checkCommit(
                state, utf8(invalidBase.toString()));
        check(refused.status == PurpleSyncCore.CommitStatus.InvalidState);
        check(refused.state == null);

        final JSONObject otherField = new JSONObject(new String(nextBytes,
                StandardCharsets.UTF_8));
        otherField.put("space", id(PurpleAccountSyncCore.formatSpaceId(
                fill(16, 'w'))));
        check(PurpleSyncCore.checkCommit(state, canonical(otherField)).status
                == PurpleSyncCore.CommitStatus.InvalidTransition);
        final byte[] padded = Arrays.copyOf(nextBytes, nextBytes.length + 1);
        padded[padded.length - 1] = ' ';
        check(PurpleSyncCore.checkCommit(state, padded).status
                == PurpleSyncCore.CommitStatus.InvalidTransition);
        final PurpleSyncCore.CommitCheck broken = PurpleSyncCore.checkCommit(
                utf8("garbage"), nextBytes);
        check(!broken.isValid() && broken.error.equals("State"));
        check(!PurpleSyncCore.checkCommit(null, nextBytes).isValid());
    }

    private static void testDescribe() {
        begin("describe");
        final String space = id(PurpleAccountSyncCore.formatSpaceId(
                fill(16, 'q')));
        final Cloud cloud = new Cloud();
        final Remote b = remote('b', "Android");
        final Remote c = remote('c', "Windows");
        final Version b1 = post(cloud, b, space, T0);
        final Cloud split = cloud.copy();
        post(split, c, space, TC);
        final Device viewer = new Device('a');
        viewer.setLocal(T0);
        final PurpleSyncCore.Review unboundChoose = review(viewer, split);
        check(unboundChoose.verdict == PurpleSyncCore.Verdict.Choose);
        check(unboundChoose.message == PurpleSyncCore.Message.ChooseUnbound);
        check(unboundChoose.action == PurpleSyncCore.Action.Choose);
        check(unboundChoose.devices.size() == 1
                && unboundChoose.devices.get(0).platform.equals("Windows")
                && c.install.endsWith(unboundChoose.devices.get(0).shortId
                        + c.install.substring(c.install.indexOf('-') + 5)));
        check(unboundChoose.choices.size() == 2);
        final PurpleSyncCore.Choice remoteChoice = unboundChoose.choices.get(0);
        check(remoteChoice.key.equals(unboundChoose.offered.get(0)));
        check(remoteChoice.head >= 0
                && unboundChoose.heads.get(remoteChoice.head).install
                        .equals(c.install));
        check(remoteChoice.publishes);
        final PurpleSyncCore.Choice keepChoice = unboundChoose.choices.get(1);
        check(keepChoice.key == null && keepChoice.head == -1
                && keepChoice.publishes);

        final Device unpublishable = new Device('a');
        unpublishable.setLocal(utf8("not [valid"));
        final PurpleSyncCore.Review invalidChoose = review(unpublishable, split);
        check(!invalidChoose.localPublishable);
        check(invalidChoose.verdict == PurpleSyncCore.Verdict.Conflict);
        check(invalidChoose.choices.size() == 2);
        for (PurpleSyncCore.Choice choice : invalidChoose.choices) {
            check(choice.key != null && choice.head >= 0);
        }

        final PurpleSyncCore.Review adopt = review(viewer, cloud);
        check(adopt.verdict == PurpleSyncCore.Verdict.Adopt);
        check(adopt.message == PurpleSyncCore.Message.AdoptUnbound);
        check(adopt.action == PurpleSyncCore.Action.Join);
        check(adopt.devices.size() == 1
                && adopt.devices.get(0).platform.equals("Android"));
        check(adopt.choices.size() == 1);
        check(adopt.choices.size() == 1 && adopt.choices.get(0).key == null
                && !adopt.choices.get(0).publishes);

        viewer.setLocal(TB);
        final PurpleSyncCore.Review choose = review(viewer, cloud);
        check(choose.verdict == PurpleSyncCore.Verdict.Choose);
        check(choose.choices.size() == 2);
        check(choose.choices.size() == 2 && !choose.choices.get(0).publishes);
        check(choose.choices.size() == 2 && choose.choices.get(0).key.equals(b1.key));
        check(choose.choices.size() == 2 && choose.choices.get(1).publishes);

        viewer.setLocal(T0);
        check(apply(viewer, cloud, review(viewer, cloud), null).status
                == PurpleSyncCore.ApplyStatus.Applied);
        final PurpleSyncCore.Review upToDate = review(viewer, cloud);
        check(upToDate.message == PurpleSyncCore.Message.UpToDateWith);
        check(upToDate.others == 1);
        final Version b2 = post(cloud, b, space, TB, b1);
        final PurpleSyncCore.Review update = review(viewer, cloud);
        check(update.verdict == PurpleSyncCore.Verdict.UpdateReady);
        check(update.message == PurpleSyncCore.Message.UpdateReady);
        check(update.action == PurpleSyncCore.Action.ReviewUpdate);
        check(update.at == 1700000002L);
        check(update.devices.size() == 1
                && update.devices.get(0).platform.equals("Android"));
        check(update.choices.size() == 1);
        check(update.choices.size() == 1 && update.choices.get(0).key.equals(b2.key)
                && !update.choices.get(0).publishes);

        final Device namer = new Device('p');
        namer.setLocal(T0);
        final Cloud names = new Cloud();
        final Remote spaced = new Remote(id(PurpleAccountSyncCore
                .formatInstallId(fill(16, 'r'))), "linux:box", "  Linux  box ", 0);
        post(names, spaced, space, TB);
        final PurpleSyncCore.Review named = review(namer, names);
        check(named.heads.size() == 1
                && named.heads.get(0).name.platform.equals("Linux box"));
        check(named.heads.size() == 1 && named.heads.get(0).name.shortId.length() == 4);

        check(review(namer, new Cloud()).localPublishable);
        namer.setLocal(new byte[0]);
        check(!review(namer, new Cloud()).localPublishable);
        check(review(namer, new Cloud()).message
                == PurpleSyncCore.Message.NotPublishableInvalid);
        namer.removeLocal();
        check(!review(namer, new Cloud()).localPublishable);
        check(review(namer, new Cloud()).message
                == PurpleSyncCore.Message.NotPublishableAbsent);
        namer.setLocal(bigText());
        check(!review(namer, new Cloud()).localPublishable);

        for (PurpleSyncCore.ReviewStatus status : new PurpleSyncCore.ReviewStatus[] {
            PurpleSyncCore.ReviewStatus.AccountUnavailable,
            PurpleSyncCore.ReviewStatus.AccountUnbound,
            PurpleSyncCore.ReviewStatus.StoreError,
        }) {
            final PurpleSyncCore.Review failed =
                    PurpleSyncCore.Review.clientFailure(status);
            check(failed.status == status);
            check(failed.message.name().equals(status.name()));
            check(failed.action == PurpleSyncCore.Action.None);
        }
        boolean threw = false;
        try {
            PurpleSyncCore.Review.clientFailure(PurpleSyncCore.ReviewStatus.Ready);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw);

        check(kind(PurpleSyncCore.ApplyStatus.Applied, false, false)
                == PurpleSyncCore.ApplyFailureKind.None);
        check(kind(PurpleSyncCore.ApplyStatus.Applied, true, false)
                == PurpleSyncCore.ApplyFailureKind.None);
        check(kind(PurpleSyncCore.ApplyStatus.NeedsRecheck, false, false)
                == PurpleSyncCore.ApplyFailureKind.NothingDone);
        check(kind(PurpleSyncCore.ApplyStatus.NeedsRecheck, true, false)
                == PurpleSyncCore.ApplyFailureKind.JoinedNotWritten);
        check(kind(PurpleSyncCore.ApplyStatus.WriteError, true, false)
                == PurpleSyncCore.ApplyFailureKind.JoinedNotWritten);
        check(kind(PurpleSyncCore.ApplyStatus.StoreError, false, true)
                == PurpleSyncCore.ApplyFailureKind.WrittenStateNotSaved);
        check(kind(PurpleSyncCore.ApplyStatus.StoreError, true, true)
                == PurpleSyncCore.ApplyFailureKind.WrittenStateNotSaved);
        check(kind(PurpleSyncCore.ApplyStatus.WriteError, false, true)
                == PurpleSyncCore.ApplyFailureKind.WrittenNotReadBack);
        check(kind(PurpleSyncCore.ApplyStatus.NeedsReview, false, true)
                == PurpleSyncCore.ApplyFailureKind.WrittenNotReadBack);
        final PurpleSyncCore.ApplyFailure described =
                PurpleSyncCore.describeApplyFailure(
                        PurpleSyncCore.ApplyStatus.StoreError, true, true, true,
                        true, true);
        check(described.isValid());
        check(described.joined && described.historyKept
                && described.undoAvailable && described.otherVersionsRemain);
        for (PurpleSyncCore.ApplyStatus status : PurpleSyncCore.ApplyStatus.values()) {
            check(PurpleSyncCore.describeApplyFailure(status, false, false,
                    false, false, false).isValid());
        }
        for (PurpleSyncCore.RestoreStatus status : PurpleSyncCore.RestoreStatus.values()) {
            final PurpleSyncCore.UndoCheck undo = PurpleSyncCore.undoFinished(status);
            check(undo.isValid());
            final boolean kept = status == PurpleSyncCore.RestoreStatus.InvalidSettings
                    || status == PurpleSyncCore.RestoreStatus.HistoryError
                    || status == PurpleSyncCore.RestoreStatus.WriteError;
            check(undo.finished == !kept);
        }
    }

    private static PurpleSyncCore.ApplyFailureKind kind(
            PurpleSyncCore.ApplyStatus status, boolean joined,
            boolean wroteFile) {
        final PurpleSyncCore.ApplyFailure failure =
                PurpleSyncCore.describeApplyFailure(status, joined, wroteFile,
                        false, false, false);
        check(failure.isValid());
        return failure.kind;
    }

    private static byte[] newerRecord(String space, Remote remote)
            throws Exception {
        final byte[] oldText = utf8("version = 1\n");
        final byte[] newText = utf8("version = 2\n");
        final byte[] record = buildRemote(remote, space, oldText);
        String json = new String(record, StandardCharsets.UTF_8)
                .replace("\"schema\":1", "\"schema\":2")
                .replace("version = 1\\n", "version = 2\\n")
                .replace("1." + fingerprint(oldText), "1." + fingerprint(newText));
        final int payloadStart = json.indexOf("\"payload\":") + 10;
        final int payloadEnd = json.indexOf(",\"payload_sha256\"");
        check(payloadStart >= 10 && payloadEnd > payloadStart);
        final String hash = HexFormat.of().formatHex(MessageDigest
                .getInstance("SHA-256").digest(utf8(json.substring(payloadStart,
                        payloadEnd))));
        json = json.replaceFirst("\"payload_sha256\":\"[0-9a-f]{64}\"",
                "\"payload_sha256\":\"" + hash + "\"");
        return utf8(json);
    }

    private static void testReviewStatuses() throws Exception {
        begin("review statuses");
        final String space = id(PurpleAccountSyncCore.formatSpaceId(
                fill(16, 'r')));
        final Cloud cloud = new Cloud();
        final Remote b = remote('b', "Android");
        final Version b1 = post(cloud, b, space, TB);
        final Device device = new Device('a');
        device.setLocal(T0);

        final Cloud incomplete = cloud.copy();
        incomplete.scanComplete = false;
        check(review(device, incomplete).status
                == PurpleSyncCore.ReviewStatus.Incomplete);
        final Cloud broken = cloud.copy();
        broken.add(utf8("{\"not\":\"a record\"}"));
        check(review(device, broken).status
                == PurpleSyncCore.ReviewStatus.NeedsReview);
        check(review(device, broken).message == PurpleSyncCore.Message.NeedsReview);

        final byte[] newerRecord = newerRecord(space, remote('n', "Linux"));
        check("NewerSchema".equals(PurpleAccountSyncCore.inspectConfigRecord(
                newerRecord).status));
        final Cloud newer = cloud.copy();
        newer.add(newerRecord);
        check(review(device, newer).status
                == PurpleSyncCore.ReviewStatus.NeedsReview);

        check(apply(device, cloud, review(device, cloud), b1.key).status
                == PurpleSyncCore.ApplyStatus.Applied);
        check(Arrays.equals(device.local.bytes, TB));
        check(review(device, cloud).status == PurpleSyncCore.ReviewStatus.Ready);
        final Device elsewhere = device.copy();
        final JSONObject moved = new JSONObject(new String(device.state,
                StandardCharsets.UTF_8));
        moved.put("space", id(PurpleAccountSyncCore.formatSpaceId(
                fill(16, 'w'))));
        elsewhere.state = canonical(moved);
        check(review(elsewhere, cloud).status
                == PurpleSyncCore.ReviewStatus.NeedsReview);
        check(review(device, incomplete).status
                == PurpleSyncCore.ReviewStatus.Incomplete);
        final JSONObject invalid = new JSONObject(new String(device.state,
                StandardCharsets.UTF_8));
        invalid.getJSONObject("streams").getJSONObject("config")
                .put("base_lineage", new JSONArray().put("not a key"));
        final PurpleSyncCore.Review refused = PurpleSyncCore.review(
                cloud.inventory(), utf8(invalid.toString()), null, device.local,
                device.device);
        check(!refused.isValid());
        check("State".equals(refused.error));
        check(refused.status == PurpleSyncCore.ReviewStatus.StoreError);
    }

    private static PurpleSyncSettingsFile.Contents lastGood(
            PurpleSyncSettingsFile.Contents file) {
        return new PurpleSyncSettingsFile.Contents(file.status, file.bytes, true);
    }

    private static void testUsingLastGood() throws Exception {
        begin("using last good");
        final Cloud cloud = new Cloud();
        final Device device = new Device('g');
        device.setLocal(T0);
        final PurpleSyncCore.Review shown = review(device, cloud);
        check(shown.status == PurpleSyncCore.ReviewStatus.Ready);
        check(shown.action == PurpleSyncCore.Action.Publish);

        device.local = lastGood(present(T0));
        final PurpleSyncCore.Review unbound = review(device, cloud);
        check(unbound.status == PurpleSyncCore.ReviewStatus.UsingLastGood);
        check(unbound.message == PurpleSyncCore.Message.UsingLastGood);
        check(unbound.action == PurpleSyncCore.Action.None);
        check(!unbound.bound && !unbound.localPublishable);
        check(unbound.choices.isEmpty());
        check(!unbound.stamp.equals(shown.stamp));
        check(apply(device, cloud, shown, null).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(apply(device, cloud, unbound, null).status
                == PurpleSyncCore.ApplyStatus.NeedsReview);
        check(device.state == null);
        device.local = lastGood(absent());
        check(review(device, cloud).status
                == PurpleSyncCore.ReviewStatus.UsingLastGood);

        device.setLocal(T0);
        final Device joined = device.copy();
        check(join(joined, ""));
        final PurpleSyncCore.ApplyPlan fallenBack = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.afterJoin(cloud.inventory(),
                        joined.state, present(T0), shown.stamp, null,
                        lastGood(present(T0))));
        check(fallenBack.isValid());
        check(fallenBack.status == PurpleSyncCore.ApplyPlanStatus.NeedsRecheck);
        final PurpleSyncCore.ApplyPlan steady = PurpleSyncCore.planApply(
                PurpleSyncCore.ApplyRequest.afterJoin(cloud.inventory(),
                        joined.state, present(T0), shown.stamp, null,
                        present(T0)));
        check(steady.isValid());
        check(steady.status == PurpleSyncCore.ApplyPlanStatus.Ready);

        final ApplyRun joinedRun = apply(device, cloud, shown, null);
        check(joinedRun.status == PurpleSyncCore.ApplyStatus.Applied);
        final PublishRun first = publish(device, cloud,
                request(joinedRun.fingerprint, joinedRun.expectedParents));
        check(first.status == PurpleSyncCore.PublishStatus.Confirmed);
        device.setLocal(T1);
        final PurpleSyncCore.Review changed = review(device, cloud);
        check(changed.verdict == PurpleSyncCore.Verdict.LocalChanges);
        device.local = lastGood(present(T1));
        final PurpleSyncCore.Review bound = review(device, cloud);
        check(bound.status == PurpleSyncCore.ReviewStatus.UsingLastGood);
        check(bound.message == PurpleSyncCore.Message.UsingLastGood);
        check(bound.action == PurpleSyncCore.Action.None);
        check(bound.bound && bound.space.equals(device.space()));
        check(apply(device, cloud, changed, null).status
                == PurpleSyncCore.ApplyStatus.NeedsRecheck);
        check(publish(device, cloud, request(fingerprint(T1),
                List.of(first.version.key))).status
                == PurpleSyncCore.PublishStatus.InvalidSettings);
        check(cloud.size() == 1 && device.pendingSeq() == 0);

        device.setLocal(T1);
        final ApplyRun keep = apply(device, cloud, review(device, cloud), null);
        check(keep.status == PurpleSyncCore.ApplyStatus.Applied && keep.publishNeeded);
        final PublishRun lost = publish(device, cloud,
                request(keep.fingerprint, keep.expectedParents), PostMode.Fail);
        check(lost.status == PurpleSyncCore.PublishStatus.OutcomeUnknown);
        check(device.pendingSeq() != 0);
        device.local = lastGood(present(T1));
        final PurpleSyncCore.Review pending = review(device, cloud);
        check(pending.status == PurpleSyncCore.ReviewStatus.UsingLastGood);
        check(pending.pending && pending.action == PurpleSyncCore.Action.None);
        final PublishRun finished = publish(device, cloud, pendingOnly());
        check(finished.status == PurpleSyncCore.PublishStatus.Confirmed);
        check(finished.posts == 1 && cloud.size() == 2);
    }

    private static void testDiff() {
        begin("diff");
        final PurpleSyncCore.Diff same = PurpleSyncCore.diff(utf8("a\nb\n"),
                utf8("a\nb\n"));
        check(same.isValid());
        check(same.identical && same.hunks.isEmpty() && !same.truncated);
        check(same.added == 0 && same.removed == 0);
        final PurpleSyncCore.Diff inserted = PurpleSyncCore.diff(
                utf8("a\nb\nc\n"), utf8("a\nb\nX\nc\n"));
        check(!inserted.identical && !inserted.truncated);
        check(inserted.added == 1 && inserted.removed == 0);
        check(inserted.hunks.size() == 1);
        final PurpleSyncCore.DiffHunk hunk = inserted.hunks.get(0);
        check(hunk.oldStart == 1 && hunk.oldCount == 3);
        check(hunk.newStart == 1 && hunk.newCount == 4);
        check(hunk.lines.size() == 4);
        check(hunk.lines.get(2).kind == PurpleSyncCore.DiffLineKind.Added);
        check(hunk.lines.get(2).text.equals("X"));
        check(hunk.lines.get(2).oldLine == 0 && hunk.lines.get(2).newLine == 3);
        check(hunk.lines.get(3).oldLine == 3 && hunk.lines.get(3).newLine == 4);
        check(hunk.lines.get(0).kind == PurpleSyncCore.DiffLineKind.Context);
        final PurpleSyncCore.Diff replaced = PurpleSyncCore.diff(
                utf8("a\nb\nc\n"), utf8("a\nB\nc\n"));
        check(replaced.hunks.size() == 1
                && replaced.hunks.get(0).lines.get(1).kind
                        == PurpleSyncCore.DiffLineKind.Removed
                && replaced.hunks.get(0).lines.get(2).kind
                        == PurpleSyncCore.DiffLineKind.Added);
        final PurpleSyncCore.Diff lenient = PurpleSyncCore.diff(
                new byte[] { (byte) 0xff, (byte) 0xfe, ' ', 'o', 'k', '\n' },
                utf8("ok\n"));
        check(lenient.hunks.get(0).lines.get(0).text.equals("�� ok"));

        final StringBuilder old = new StringBuilder();
        final StringBuilder now = new StringBuilder();
        for (int i = 0; i != 501; ++i) {
            old.append("old ").append(i).append('\n');
        }
        for (int i = 0; i != 500; ++i) {
            now.append("new ").append(i).append('\n');
        }
        final PurpleSyncCore.Diff over = PurpleSyncCore.diff(utf8(old.toString()),
                utf8(now.toString()));
        check(over.truncated);
        check(!over.identical);
        check(over.hunks.size() == 1);
        check(over.removed == 501 && over.added == 500);
        check(over.hunks.get(0).oldStart == 1 && over.hunks.get(0).oldCount == 501);
        check(over.hunks.get(0).newStart == 1 && over.hunks.get(0).newCount == 500);
        check(over.hunks.get(0).lines.get(0).kind
                == PurpleSyncCore.DiffLineKind.Removed);
        final List<PurpleSyncCore.DiffLine> lines = over.hunks.get(0).lines;
        check(lines.get(lines.size() - 1).kind == PurpleSyncCore.DiffLineKind.Added);
        old.setLength(0);
        for (int i = 0; i != 500; ++i) {
            old.append("old ").append(i).append('\n');
        }
        final PurpleSyncCore.Diff atLimit = PurpleSyncCore.diff(
                utf8(old.toString()), utf8(now.toString()));
        check(!atLimit.truncated);
        check(atLimit.removed + atLimit.added == 1000);

        final String base = "version = 1\n\n[lists.work]\nmembers = [ 10, 11 ]\n"
                + "\n[lists.noise]\nkinds = [ \"channels\" ]\n";
        final String edited = "version = 1\n\n[lists.work]\nmembers = [ 10, 12 ]\n"
                + "\n[presets.gym]\nlist_order = []\n";
        final PurpleSyncCore.Diff summary = PurpleSyncCore.diff(utf8(base),
                utf8(edited));
        check(summary.summaryParsed);
        final List<String> changes = new ArrayList<>();
        for (PurpleSyncCore.Change change : summary.summary) {
            changes.add(change.kind + " " + change.table + " / " + change.label);
        }
        check(changes.equals(List.of(
                "Changed lists.work / List \"work\"",
                "Added presets.gym / Preset \"gym\"",
                "Removed lists.noise / List \"noise\"")));
        final PurpleSyncCore.Diff unparsed = PurpleSyncCore.diff(
                utf8("not [valid"), utf8(base));
        check(unparsed.isValid() && !unparsed.summaryParsed
                && unparsed.summary.isEmpty());
        check(!PurpleSyncCore.diff(null, utf8(base)).isValid());
    }

    private static void testNativeUnavailable() {
        begin("reply parsing");
        final PurpleSyncCore.Review bad = PurpleSyncCore.review(null, null,
                null, absent(), "d");
        check(!bad.isValid() && bad.error.equals("NullInput"));
        final PurpleSyncCore.Review mismatched = PurpleSyncCore.review(
                new PurpleSyncInventory(USER, true, new int[] { 1 },
                        new int[] { PurpleSyncInventory.VANISHED }, new long[1],
                        new long[1], new byte[1][]),
                utf8("{}"), null, absent(), "d");
        check(!mismatched.isValid() && mismatched.error.equals("State"));
    }

    private static byte[] withSeen(byte[] state, String install, long seq) {
        final JSONObject edited = new JSONObject(new String(state,
                StandardCharsets.UTF_8));
        edited.getJSONObject("streams").getJSONObject("config")
                .getJSONObject("seen_seq").put(install, seq);
        return canonical(edited);
    }

    private static void testStore() throws Exception {
        begin("store");
        final File parent = Files.createTempDirectory("purple-sync-store")
                .toFile();
        final File root = new File(parent, "purple/sync");
        final File stateFile = new File(root, "state.json");
        final Cloud cloud = new Cloud();
        final String space = id(PurpleAccountSyncCore.formatSpaceId(
                fill(16, 't')));
        final Remote b = remote('b', "Android");
        final Version b1 = post(cloud, b, space, TB);
        final Device device = new Device('s');
        device.setLocal(T0);
        check(!PurpleAccountSyncStore.hasState(root));
        check(!new File(parent, "purple").exists());
        try (PurpleAccountSyncStore store = new PurpleAccountSyncStore(root,
                PurpleAccountSyncStore.NATIVE)) {
            check(store.open(true, ACCOUNT, device.device).status
                    == PurpleAccountSyncStore.Status.Uninitialized);
            check(!PurpleAccountSyncStore.hasState(root));
            check(join(device, space));
            check(store.initialize(device.state).status
                    == PurpleAccountSyncStore.Status.Ready);
            check(PurpleAccountSyncStore.hasState(root));
            check(Arrays.equals(store.stateBytes(), device.state));

            final PurpleSyncCore.Review shown = review(device, cloud);
            check(shown.bound && shown.offered.equals(List.of(b1.key)));
            final PurpleSyncCore.ApplyRequest request = PurpleSyncCore
                    .ApplyRequest.bound(cloud.inventory(), store.stateBytes(),
                            null, device.local, shown.stamp, b1.key);
            final PurpleSyncCore.ApplyPlan plan = PurpleSyncCore.planApply(
                    request);
            check(plan.status == PurpleSyncCore.ApplyPlanStatus.Ready
                    && plan.writeRemote);
            device.setLocal(plan.source.text);
            final PurpleSyncCore.ApplyCompletion completion =
                    PurpleSyncCore.completeApply(request, device.local);
            check(completion.status == PurpleSyncCore.CompletionStatus.Ready);
            check(completion.adopted
                    && completion.commit == PurpleSyncCore.CommitStatus.Ready);
            final byte[] next = completion.nextState;
            check(store.commitConfigState(next).status
                    == PurpleAccountSyncStore.Status.Ready);
            check(Arrays.equals(store.stateBytes(), next));
            check(Arrays.equals(Files.readAllBytes(stateFile.toPath()), next));
            check(inspect(next).key.equals(b1.key));
            check(seenSeq(next, b.install) == 1);
            device.state = next;
            check(review(device, cloud).verdict
                    == PurpleSyncCore.Verdict.UpToDate);
            final long modified = stateFile.lastModified();
            check(store.commitConfigState(next).status
                    == PurpleAccountSyncStore.Status.Unchanged);
            check(stateFile.lastModified() == modified);

            check(store.commitConfigState(withSeen(next, b.install, 0)).status
                    == PurpleAccountSyncStore.Status.InvalidTransition);
            final JSONObject otherDevice = new JSONObject(new String(
                    withSeen(next, b.install, 4), StandardCharsets.UTF_8));
            otherDevice.put("created_device", "android-elsewhere");
            check(store.commitConfigState(canonical(otherDevice)).status
                    == PurpleAccountSyncStore.Status.DeviceMismatch);
            final JSONObject moved = new JSONObject(new String(
                    withSeen(next, b.install, 4), StandardCharsets.UTF_8));
            moved.put("space", id(PurpleAccountSyncCore.formatSpaceId(
                    fill(16, 'w'))));
            check(store.commitConfigState(canonical(moved)).status
                    == PurpleAccountSyncStore.Status.InvalidTransition);
            check(Arrays.equals(Files.readAllBytes(stateFile.toPath()), next));

            device.setLocal(T1);
            final PurpleSyncCore.PostRequest changes = localRequest(device,
                    cloud);
            final PurpleSyncCore.PostPlan stage = PurpleSyncCore.planPost(
                    ACCOUNT, cloud.inventory(), store.stateBytes(), null,
                    device.local, changes, device.now, false);
            check(stage.step == PurpleSyncCore.PostStep.Stage);
            final PurpleAccountSyncCore.Result reserved =
                    PurpleAccountSyncCore.reserveConfigRecord(ACCOUNT,
                            store.stateBytes(), stage.record);
            check(reserved.isValid() && reserved.key.equals(stage.key));
            final PurpleAccountSyncStore.Result staged = store.stageConfig(
                    stage.record, reserved.state);
            check(staged.status == PurpleAccountSyncStore.Status.Ready);
            final PurpleSyncCore.PostPlan post = PurpleSyncCore.checkStagedPost(
                    ACCOUNT, cloud.inventory(), store.stateBytes(),
                    staged.staged);
            check(post.step == PurpleSyncCore.PostStep.Post);
            check(Arrays.equals(post.record, stage.record));
            final byte[] pendingState = store.stateBytes();
            check(store.commitConfigState(withSeen(pendingState, b.install, 7))
                    .status == PurpleAccountSyncStore.Status.InvalidTransition);
            check(Arrays.equals(Files.readAllBytes(stateFile.toPath()),
                    pendingState));

            UserConfig.userId = USER + 1;
            check(store.commitConfigState(next).status
                    == PurpleAccountSyncStore.Status.AccountUnbound);
            UserConfig.userId = USER;
            check(store.stateBytes() == null);
            check(Arrays.equals(Files.readAllBytes(stateFile.toPath()),
                    pendingState));
        }
    }


    private static Throwable nativeFailure(Class<?> owner, String name,
            Class<?>[] types, Object... arguments) throws Exception {
        final Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            method.invoke(null, arguments);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    private static void unavailable(PurpleSyncCore.Answer answer) {
        check(answer != null && !answer.isValid()
                && "NativeUnavailable".equals(answer.error));
    }

    private static void unavailable(PurpleAccountSyncCore.Result result) {
        check(result != null && !result.isValid()
                && "BridgeError".equals(result.status)
                && "NativeUnavailable".equals(result.error));
    }

    private static void testStrippedReplies() throws Exception {
        begin("stripped replies");
        PurpleCore.ensureLoaded();
        check(Modifier.isAbstract(Class.forName(
                "org.telegram.messenger.purple.PurpleSyncCore$RawReply")
                .getModifiers()));
        check(Modifier.isAbstract(Class.forName(
                "org.telegram.messenger.purple.PurpleAccountSyncCore$RawReply")
                .getModifiers()));
        check(nativeFailure(PurpleSyncCore.class, "undoFinishedNative",
                new Class<?>[] { String.class }, "Restored")
                instanceof NoSuchMethodError);
        check(nativeFailure(PurpleAccountSyncCore.class,
                "compareSpaceIdsNative",
                new Class<?>[] { String.class, String.class }, "a", "b")
                instanceof NoSuchMethodError);

        check(fingerprint(T0).equals(PurpleSyncCore.settingsFingerprint(T0)));
        check(PurpleSyncCore.isSettingsTextWritable(T0));
        check(!PurpleSyncCore.isConfigVersionKey("not a key"));

        final PurpleSyncInventory inventory = new PurpleSyncInventory(USER,
                true, new int[0], new int[0], new long[0], new long[0],
                new byte[0][]);
        unavailable(PurpleSyncCore.classifyHistoryPage(0, new int[] { 5 },
                new boolean[] { true }, new boolean[] { false },
                new boolean[] { true }, new String[] { "#purplesync" },
                new String[] { "Purple settings sync.json" }));
        final PurpleSyncCore.Review review = PurpleSyncCore.review(inventory,
                null, null, present(T0), "android-stripped");
        unavailable(review);
        check(review.status == PurpleSyncCore.ReviewStatus.StoreError
                && review.message == PurpleSyncCore.Message.StoreError);
        unavailable(PurpleSyncCore.diff(T0, T1));
        final PurpleSyncCore.ApplyRequest request = PurpleSyncCore
                .ApplyRequest.unbound(inventory, present(T0), "stamp", null);
        unavailable(PurpleSyncCore.planApply(request));
        unavailable(PurpleSyncCore.completeApply(request, present(T0)));
        unavailable(PurpleSyncCore.checkCommit(utf8("{}"), utf8("{}")));
        unavailable(PurpleSyncCore.planPostEntry(
                PurpleSyncCore.PostRequest.finishSending(), true));
        check(PurpleAccountBinding.persist(ACCOUNT, USER,
                "0123456789abcdef0123456789abcdef").isValid());
        unavailable(PurpleSyncCore.planPost(ACCOUNT, inventory, utf8("{}"),
                null, present(T0), PurpleSyncCore.PostRequest.finishSending(),
                1, false));
        unavailable(PurpleSyncCore.checkStagedPost(ACCOUNT, inventory,
                utf8("{}"), utf8("{}")));
        unavailable(PurpleSyncCore.describeApplyFailure(
                PurpleSyncCore.ApplyStatus.WriteError, false, true, true,
                true, false));
        unavailable(PurpleSyncCore.undoFinished(
                PurpleSyncCore.RestoreStatus.Restored));

        final byte[] entropy = fill(16, 's');
        unavailable(PurpleAccountSyncCore.inspectConfigRecord(utf8("{}")));
        unavailable(PurpleAccountSyncCore.inspectState(utf8("{}")));
        unavailable(PurpleAccountSyncCore.checkLocalStage(utf8("{}"),
                utf8("{}")));
        unavailable(PurpleAccountSyncCore.buildConfigRecord(T0, new byte[0][],
                "space", "install", "android-stripped", "Android", "app", 1,
                1));
        unavailable(PurpleAccountSyncCore.buildConfigAcknowledgement(T0,
                utf8("{}"), "space", "install", "android-stripped", "Android",
                "app", 1, 1));
        unavailable(PurpleAccountSyncCore.reserveConfigSeq(ACCOUNT,
                utf8("{}"), "hash"));
        unavailable(PurpleAccountSyncCore.initializeLocalState("install",
                "android-stripped", "space"));
        unavailable(PurpleAccountSyncCore.initializeBoundLocalState(ACCOUNT,
                "install", "android-stripped", "space"));
        unavailable(PurpleAccountSyncCore.checkAccountBinding(ACCOUNT,
                utf8("{}")));
        unavailable(PurpleAccountSyncCore.reserveConfigRecord(ACCOUNT,
                utf8("{}"), utf8("{}")));
        unavailable(PurpleAccountSyncCore.appendIssuedConfigRecord(
                utf8("{}"), utf8("{}")));
        unavailable(PurpleAccountSyncCore.adoptIssuedOwnConfigMessage(
                utf8("{}"), 5, utf8("{}")));
        unavailable(PurpleAccountSyncCore.checkOwnRecord(utf8("{}"),
                "android-stripped", 0, utf8("{}")));
        unavailable(PurpleAccountSyncCore.confirmConfigReadBack(utf8("{}"),
                utf8("{}"), "android-stripped", 0, utf8("{}"), 5));
        unavailable(PurpleAccountSyncCore.recordConfirmedOwnConfigMessage(
                utf8("{}"), 5, utf8("{}")));
        unavailable(PurpleAccountSyncCore.checkOwnConfigMessageDeletion(
                utf8("{}"), 5, utf8("{}")));
        unavailable(PurpleAccountSyncCore.removeAbsentOwnConfigMessage(
                utf8("{}"), 5, 0));
        unavailable(PurpleAccountSyncCore.formatInstallId(entropy));
        unavailable(PurpleAccountSyncCore.formatSpaceId(entropy));
        unavailable(PurpleAccountSyncCore.formatTimeOrderedSpaceId(
                1790000000000L));
        unavailable(PurpleAccountSyncCore.compareSpaceIds("a", "b"));
    }

    public static void main(String[] args) throws Exception {
        UserConfig.activated = true;
        UserConfig.userId = USER;
        MessagesController.VALUES.clear();
        if (args.length == 1 && args[0].equals("stripped")) {
            testStrippedReplies();
            finish();
            return;
        }
        testBasics();
        testHeads();
        testEmptyJoinAndLocalChanges();
        testChooseUpdateAdoptConflict();
        testRechecks();
        testJoinVariants();
        testPublisher();
        testSendQueue();
        testStamp();
        testCommitCheck();
        testDescribe();
        testReviewStatuses();
        testUsingLastGood();
        testDiff();
        testNativeUnavailable();
        testStore();
        finish();
    }

    private static void finish() {
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
