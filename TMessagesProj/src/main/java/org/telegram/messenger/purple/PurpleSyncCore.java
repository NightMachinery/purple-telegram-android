/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PurpleSyncCore {
    public static final String WRITER_PLATFORM = "Android";
    public static final String WRITER_APP = "Purple Telegram Android";

    static final int LOCAL_PRESENT = 0;
    static final int LOCAL_ABSENT = 1;
    static final int LOCAL_INVALID = 2;

    private PurpleSyncCore() {
    }

    public enum PageStatus { More, Complete, Stalled }

    public enum InventoryStatus { Complete, NeedsReview, Incomplete }

    public enum ReviewStatus {
        Ready, NeedsReview, Incomplete, CloneDetected, AccountUnavailable,
        AccountUnbound, StoreError, InvalidSettings
    }

    public enum Verdict {
        Invalid, Pending, Conflict, Choose, UpdateReady, Adopt, Empty,
        LocalChanges, UpToDate
    }

    public enum Message {
        NeedsReviewWithPending, NeedsReview, Incomplete, CloneDetected,
        AccountUnavailable, AccountUnbound, StoreError, InvalidSettings,
        InvalidRecords, Pending, ChooseBound, ChooseUnbound,
        ConflictConcurrent, ConflictSplitBound, ConflictSplitUnbound,
        UpdateReady, UpdateMissing, AdoptBound, AdoptUnbound,
        NotPublishableAbsent, NotPublishableInvalid, EmptyBound, EmptyUnbound,
        LocalChangesEdited, LocalChangesOwnStale, UpToDateAlone, UpToDateWith
    }

    public enum Action {
        None, Publish, Join, ReviewUpdate, Choose, PublishChanges, FinishSending
    }

    public enum ApplyPlanStatus { Ready, NeedsRecheck, NeedsReview, InvalidChoice }

    public enum CompletionStatus { Ready, InvalidPlan, ReadBackMismatch, AdoptRefused }

    public enum CommitStatus { Ready, Unchanged, InvalidTransition, InvalidState }

    public enum PostEntry { Refuse, FinishStaged, NewContent }

    public enum PostStep { Finish, ConfirmFound, Stage, Post }

    public enum PublishStatus {
        Confirmed, AlreadySynced, NeedsReview, CloneDetected, Incomplete,
        AccountUnavailable, AccountUnbound, StoreError, InvalidSettings,
        OutcomeUnknown, Cancelled, StillSending
    }

    public enum OwnStatus {
        Incomplete, NeedsReview, Absent, Present, PendingFound, CloneDetected
    }

    public enum ApplyStatus {
        Applied, NeedsRecheck, NeedsReview, InvalidChoice, AccountUnavailable,
        AccountUnbound, SetupFailed, StoreError, InvalidSettings, HistoryError,
        WriteError
    }

    public enum RestoreStatus {
        Restored, Unchanged, NotFound, FileDidNotExist, NotText, InvalidReason,
        InvalidSettings, HistoryError, WriteError
    }

    public enum ApplyFailureKind {
        None, JoinedNotWritten, WrittenStateNotSaved, WrittenNotReadBack,
        NothingDone
    }

    public enum DiffLineKind { Context, Removed, Added }

    public enum ChangeKind { Added, Removed, Changed }

    static final class RawReply {
        final byte[] record;
        final byte[] state;
        final byte[] text;
        final byte[][] texts;
        final String metadataJson;

        RawReply(byte[] record, byte[] state, byte[] text, byte[][] texts,
                String metadataJson) {
            this.record = record;
            this.state = state;
            this.text = text;
            this.texts = texts;
            this.metadataJson = metadataJson;
        }
    }

    public abstract static class Answer {
        public final String error;

        Answer(String error) {
            this.error = error;
        }

        public boolean isValid() {
            return error == null;
        }
    }

    public static final class DeviceName {
        public final String platform;
        public final String shortId;

        DeviceName(String platform, String shortId) {
            this.platform = platform;
            this.shortId = shortId;
        }
    }

    public static final class Head {
        public final int messageId;
        public final String space;
        public final String install;
        public final long seq;
        public final String key;
        public final List<String> lineage;
        public final String device;
        public final String platform;
        public final String app;
        public final long at;
        public final boolean newerSchema;
        public final DeviceName name;
        public final byte[] text;

        Head(JSONObject json, byte[] text) throws JSONException {
            messageId = integer(json, "messageId");
            space = string(json, "space");
            install = string(json, "install");
            seq = decimal(json, "seq");
            key = string(json, "key");
            lineage = strings(json, "lineage");
            device = string(json, "device");
            platform = string(json, "platform");
            app = string(json, "app");
            at = decimal(json, "at");
            newerSchema = bool(json, "newerSchema");
            name = deviceName(object(json, "name"));
            this.text = text;
        }
    }

    public static final class Choice {
        public final String key;
        public final int head;
        public final boolean publishes;

        Choice(JSONObject json) throws JSONException {
            key = optionalString(json, "key");
            head = integer(json, "head");
            publishes = bool(json, "publishes");
        }
    }

    public static final class Page extends Answer {
        public final PageStatus status;
        public final int offset;
        public final long count;
        public final int[] candidates;

        Page(String error) {
            super(error);
            status = PageStatus.Stalled;
            offset = 0;
            count = 0;
            candidates = new int[0];
        }

        Page(JSONObject json) throws JSONException {
            super(null);
            status = enumOf(PageStatus.class, string(json, "page"));
            offset = integer(json, "offset");
            count = decimal(json, "count");
            final JSONArray list = array(json, "candidates");
            candidates = new int[list.length()];
            for (int i = 0; i != candidates.length; ++i) {
                candidates[i] = exactInt(list.opt(i), "candidates");
            }
        }
    }

    public static final class Review extends Answer {
        public final InventoryStatus inventory;
        public final ReviewStatus status;
        public final long accountUserId;
        public final boolean bound;
        public final String space;
        public final String install;
        public final boolean pending;
        public final PurpleSyncSettingsFile.Status localStatus;
        public final String localFingerprint;
        public final boolean localPublishable;
        public final Verdict verdict;
        public final boolean ownStale;
        public final List<Head> heads;
        public final List<String> offered;
        public final List<String> same;
        public final Head ownHead;
        public final Message message;
        public final Action action;
        public final List<DeviceName> devices;
        public final long at;
        public final int others;
        public final List<Choice> choices;
        public final String stamp;

        private Review(String error, ReviewStatus status, Message message) {
            super(error);
            inventory = InventoryStatus.Incomplete;
            this.status = status;
            accountUserId = 0;
            bound = false;
            space = "";
            install = "";
            pending = false;
            localStatus = PurpleSyncSettingsFile.Status.Invalid;
            localFingerprint = "";
            localPublishable = false;
            verdict = Verdict.Invalid;
            ownStale = false;
            heads = Collections.emptyList();
            offered = Collections.emptyList();
            same = Collections.emptyList();
            ownHead = null;
            this.message = message;
            action = Action.None;
            devices = Collections.emptyList();
            at = 0;
            others = 0;
            choices = Collections.emptyList();
            stamp = "";
        }

        Review(JSONObject json, byte[][] texts) throws JSONException {
            super(null);
            inventory = enumOf(InventoryStatus.class, string(json, "inventory"));
            status = enumOf(ReviewStatus.class, string(json, "review"));
            accountUserId = decimal(json, "accountUserId");
            bound = bool(json, "bound");
            space = string(json, "space");
            install = string(json, "install");
            pending = bool(json, "pending");
            localStatus = enumOf(PurpleSyncSettingsFile.Status.class,
                    string(json, "localStatus"));
            localFingerprint = string(json, "localFingerprint");
            localPublishable = bool(json, "localPublishable");
            verdict = enumOf(Verdict.class, string(json, "verdict"));
            ownStale = bool(json, "ownStale");
            final JSONArray headList = array(json, "heads");
            if (texts == null || texts.length != headList.length()) {
                throw new JSONException("heads and texts differ");
            }
            final List<Head> parsedHeads = new ArrayList<>();
            for (int i = 0; i != headList.length(); ++i) {
                parsedHeads.add(new Head(element(headList, i), texts[i]));
            }
            heads = Collections.unmodifiableList(parsedHeads);
            offered = strings(json, "offered");
            same = strings(json, "same");
            final JSONObject own = optionalObject(json, "ownHead");
            ownHead = (own != null) ? new Head(own, null) : null;
            message = enumOf(Message.class, string(json, "message"));
            action = enumOf(Action.class, string(json, "action"));
            final JSONArray deviceList = array(json, "devices");
            final List<DeviceName> parsedDevices = new ArrayList<>();
            for (int i = 0; i != deviceList.length(); ++i) {
                parsedDevices.add(deviceName(element(deviceList, i)));
            }
            devices = Collections.unmodifiableList(parsedDevices);
            at = decimal(json, "at");
            others = integer(json, "others");
            final JSONArray choiceList = array(json, "choices");
            final List<Choice> parsedChoices = new ArrayList<>();
            for (int i = 0; i != choiceList.length(); ++i) {
                final Choice choice = new Choice(element(choiceList, i));
                if (choice.head < -1 || choice.head >= heads.size()
                        || (choice.head < 0) != (choice.key == null)) {
                    throw new JSONException("choice head out of range");
                }
                parsedChoices.add(choice);
            }
            choices = Collections.unmodifiableList(parsedChoices);
            stamp = string(json, "stamp");
        }

        public static Review clientFailure(ReviewStatus status) {
            switch (status) {
                case AccountUnavailable:
                    return new Review(null, status, Message.AccountUnavailable);
                case AccountUnbound:
                    return new Review(null, status, Message.AccountUnbound);
                case StoreError:
                    return new Review(null, status, Message.StoreError);
                default:
                    throw new IllegalArgumentException(
                            "the core reports " + status + " itself");
            }
        }

        static Review failure(String error) {
            return new Review(error, ReviewStatus.StoreError, Message.StoreError);
        }
    }

    public static final class DiffLine {
        public final DiffLineKind kind;
        public final int oldLine;
        public final int newLine;
        public final String text;

        DiffLine(JSONObject json) throws JSONException {
            kind = enumOf(DiffLineKind.class, string(json, "kind"));
            oldLine = integer(json, "oldLine");
            newLine = integer(json, "newLine");
            text = string(json, "text");
        }
    }

    public static final class DiffHunk {
        public final int oldStart;
        public final int oldCount;
        public final int newStart;
        public final int newCount;
        public final List<DiffLine> lines;

        DiffHunk(JSONObject json) throws JSONException {
            oldStart = integer(json, "oldStart");
            oldCount = integer(json, "oldCount");
            newStart = integer(json, "newStart");
            newCount = integer(json, "newCount");
            final JSONArray list = array(json, "lines");
            final List<DiffLine> parsed = new ArrayList<>();
            for (int i = 0; i != list.length(); ++i) {
                parsed.add(new DiffLine(element(list, i)));
            }
            lines = Collections.unmodifiableList(parsed);
        }
    }

    public static final class Change {
        public final ChangeKind kind;
        public final String table;
        public final String label;

        Change(JSONObject json) throws JSONException {
            kind = enumOf(ChangeKind.class, string(json, "kind"));
            table = string(json, "table");
            label = string(json, "label");
        }
    }

    public static final class Diff extends Answer {
        public final boolean identical;
        public final boolean truncated;
        public final int added;
        public final int removed;
        public final List<DiffHunk> hunks;
        public final boolean summaryParsed;
        public final List<Change> summary;

        Diff(String error) {
            super(error);
            identical = false;
            truncated = false;
            added = 0;
            removed = 0;
            hunks = Collections.emptyList();
            summaryParsed = false;
            summary = Collections.emptyList();
        }

        Diff(JSONObject json) throws JSONException {
            super(null);
            identical = bool(json, "identical");
            truncated = bool(json, "truncated");
            added = integer(json, "added");
            removed = integer(json, "removed");
            final JSONArray hunkList = array(json, "hunks");
            final List<DiffHunk> parsedHunks = new ArrayList<>();
            for (int i = 0; i != hunkList.length(); ++i) {
                parsedHunks.add(new DiffHunk(element(hunkList, i)));
            }
            hunks = Collections.unmodifiableList(parsedHunks);
            summaryParsed = bool(json, "summaryParsed");
            final JSONArray changeList = array(json, "summary");
            final List<Change> parsedChanges = new ArrayList<>();
            for (int i = 0; i != changeList.length(); ++i) {
                parsedChanges.add(new Change(element(changeList, i)));
            }
            summary = Collections.unmodifiableList(parsedChanges);
        }
    }

    public static final class ApplyRequest {
        final PurpleSyncInventory inventory;
        final byte[] state;
        final byte[] staged;
        final PurpleSyncSettingsFile.Contents local;
        final String stamp;
        final String chosenKey;
        final boolean afterJoin;
        final PurpleSyncSettingsFile.Contents preJoin;

        ApplyRequest(PurpleSyncInventory inventory, byte[] state, byte[] staged,
                PurpleSyncSettingsFile.Contents local, String stamp,
                String chosenKey, boolean afterJoin,
                PurpleSyncSettingsFile.Contents preJoin) {
            if (inventory == null || local == null || stamp == null
                    || (afterJoin && (state == null || preJoin == null))
                    || (state == null && staged != null)) {
                throw new IllegalArgumentException("incomplete apply request");
            }
            this.inventory = inventory;
            this.state = state;
            this.staged = staged;
            this.local = local;
            this.stamp = stamp;
            this.chosenKey = chosenKey;
            this.afterJoin = afterJoin;
            this.preJoin = preJoin;
        }

        public static ApplyRequest unbound(PurpleSyncInventory inventory,
                PurpleSyncSettingsFile.Contents local, String shownStamp,
                String chosenKey) {
            return new ApplyRequest(inventory, null, null, local, shownStamp,
                    chosenKey, false, null);
        }

        public static ApplyRequest bound(PurpleSyncInventory inventory,
                byte[] state, byte[] staged,
                PurpleSyncSettingsFile.Contents local, String shownStamp,
                String chosenKey) {
            if (state == null) {
                throw new IllegalArgumentException("a bound request needs state");
            }
            return new ApplyRequest(inventory, state, staged, local, shownStamp,
                    chosenKey, false, null);
        }

        public static ApplyRequest afterJoin(PurpleSyncInventory inventory,
                byte[] joinedState, PurpleSyncSettingsFile.Contents local,
                String shownStamp, String chosenKey,
                PurpleSyncSettingsFile.Contents preJoin) {
            return new ApplyRequest(inventory, joinedState, null, local,
                    shownStamp, chosenKey, true, preJoin);
        }
    }

    public static final class ApplyPlan extends Answer {
        public final ApplyPlanStatus status;
        public final boolean join;
        public final String space;
        public final Verdict verdict;
        public final boolean writeRemote;
        public final boolean publish;
        public final List<String> adopt;
        public final List<String> parents;
        public final String localFingerprint;
        public final String writeFingerprint;
        public final String versionKey;
        public final boolean update;
        public final boolean otherVersionsRemain;
        public final Head source;

        ApplyPlan(String error) {
            super(error);
            status = ApplyPlanStatus.NeedsReview;
            join = false;
            space = "";
            verdict = Verdict.Invalid;
            writeRemote = false;
            publish = false;
            adopt = Collections.emptyList();
            parents = Collections.emptyList();
            localFingerprint = "";
            writeFingerprint = "";
            versionKey = "";
            update = false;
            otherVersionsRemain = false;
            source = null;
        }

        ApplyPlan(JSONObject json, byte[] text) throws JSONException {
            super(null);
            status = enumOf(ApplyPlanStatus.class, string(json, "plan"));
            join = bool(json, "join");
            space = string(json, "space");
            verdict = enumOf(Verdict.class, string(json, "verdict"));
            writeRemote = bool(json, "writeRemote");
            publish = bool(json, "publish");
            adopt = strings(json, "adopt");
            parents = strings(json, "parents");
            localFingerprint = string(json, "localFingerprint");
            writeFingerprint = string(json, "writeFingerprint");
            versionKey = string(json, "versionKey");
            update = bool(json, "update");
            otherVersionsRemain = bool(json, "otherVersionsRemain");
            final JSONObject head = optionalObject(json, "source");
            if ((head != null) != (text != null)) {
                throw new JSONException("source and text differ");
            }
            source = (head != null) ? new Head(head, text) : null;
        }
    }

    public static final class ApplyCompletion extends Answer {
        public final ApplyPlanStatus plan;
        public final CompletionStatus status;
        public final String fingerprint;
        public final boolean adopted;
        public final CommitStatus commit;
        public final byte[] nextState;
        public final Verdict nextVerdict;
        public final boolean publishNeeded;
        public final List<String> expectedParents;
        public final boolean promiseKept;

        ApplyCompletion(String error) {
            super(error);
            plan = ApplyPlanStatus.NeedsReview;
            status = CompletionStatus.InvalidPlan;
            fingerprint = "";
            adopted = false;
            commit = null;
            nextState = null;
            nextVerdict = Verdict.Invalid;
            publishNeeded = false;
            expectedParents = Collections.emptyList();
            promiseKept = true;
        }

        ApplyCompletion(JSONObject json, byte[] state) throws JSONException {
            super(null);
            plan = enumOf(ApplyPlanStatus.class, string(json, "plan"));
            status = enumOf(CompletionStatus.class, string(json, "completion"));
            fingerprint = string(json, "fingerprint");
            adopted = bool(json, "adopted");
            final String commitName = optionalString(json, "commit");
            commit = (commitName != null)
                    ? enumOf(CommitStatus.class, commitName) : null;
            if ((commit == CommitStatus.Ready) != (state != null)) {
                throw new JSONException("commit and state differ");
            }
            nextState = state;
            nextVerdict = enumOf(Verdict.class, string(json, "nextVerdict"));
            publishNeeded = bool(json, "publishNeeded");
            expectedParents = strings(json, "expectedParents");
            promiseKept = bool(json, "promiseKept");
        }
    }

    public static final class CommitCheck extends Answer {
        public final CommitStatus status;
        public final byte[] state;

        CommitCheck(String error) {
            super(error);
            status = CommitStatus.InvalidState;
            state = null;
        }

        CommitCheck(JSONObject json, byte[] state) throws JSONException {
            super(null);
            status = enumOf(CommitStatus.class, string(json, "commit"));
            if ((status == CommitStatus.Ready) != (state != null)) {
                throw new JSONException("commit and state differ");
            }
            this.state = state;
        }
    }

    public static final class PostRequest {
        public final boolean pendingOnly;
        public final String expectedFingerprint;
        public final List<String> expectedParents;

        public PostRequest(boolean pendingOnly, String expectedFingerprint,
                List<String> expectedParents) {
            this.pendingOnly = pendingOnly;
            this.expectedFingerprint = expectedFingerprint;
            this.expectedParents = (expectedParents != null)
                    ? Collections.unmodifiableList(new ArrayList<>(expectedParents))
                    : null;
        }

        public static PostRequest finishSending() {
            return new PostRequest(true, null, null);
        }

        public static PostRequest newContent(String expectedFingerprint,
                List<String> expectedParents) {
            if (expectedFingerprint == null || expectedParents == null) {
                throw new IllegalArgumentException(
                        "new content needs a fingerprint and parents");
            }
            return new PostRequest(false, expectedFingerprint, expectedParents);
        }

        String[] parents() {
            return (expectedParents != null)
                    ? expectedParents.toArray(new String[0]) : null;
        }
    }

    public static final class EntryPlan extends Answer {
        public final PostEntry entry;

        EntryPlan(String error) {
            super(error);
            entry = PostEntry.Refuse;
        }

        EntryPlan(JSONObject json) throws JSONException {
            super(null);
            entry = enumOf(PostEntry.class, string(json, "entry"));
        }
    }

    public static final class PostPlan extends Answer {
        public final PostStep step;
        public final PublishStatus status;
        public final int messageId;
        public final String key;
        public final OwnStatus own;
        public final byte[] record;

        PostPlan(String error) {
            super(error);
            step = PostStep.Finish;
            status = PublishStatus.NeedsReview;
            messageId = 0;
            key = "";
            own = OwnStatus.Incomplete;
            record = null;
        }

        PostPlan(JSONObject json, byte[] record) throws JSONException {
            super(null);
            step = enumOf(PostStep.class, string(json, "step"));
            status = enumOf(PublishStatus.class, string(json, "publish"));
            messageId = integer(json, "messageId");
            key = string(json, "key");
            own = enumOf(OwnStatus.class, string(json, "own"));
            if ((step != PostStep.Finish) != (record != null)
                    || (step == PostStep.ConfirmFound && messageId <= 0)) {
                throw new JSONException("post step and record differ");
            }
            this.record = record;
        }
    }

    public static final class ApplyFailure extends Answer {
        public final ApplyFailureKind kind;
        public final boolean joined;
        public final boolean historyKept;
        public final boolean undoAvailable;
        public final boolean otherVersionsRemain;

        ApplyFailure(String error) {
            super(error);
            kind = ApplyFailureKind.NothingDone;
            joined = false;
            historyKept = false;
            undoAvailable = false;
            otherVersionsRemain = false;
        }

        ApplyFailure(JSONObject json) throws JSONException {
            super(null);
            kind = enumOf(ApplyFailureKind.class, string(json, "kind"));
            joined = bool(json, "joined");
            historyKept = bool(json, "historyKept");
            undoAvailable = bool(json, "undoAvailable");
            otherVersionsRemain = bool(json, "otherVersionsRemain");
        }
    }

    public static final class UndoCheck extends Answer {
        public final boolean finished;

        UndoCheck(String error) {
            super(error);
            finished = false;
        }

        UndoCheck(JSONObject json) throws JSONException {
            super(null);
            finished = bool(json, "finished");
        }
    }

    private static native RawReply classifyHistoryPageNative(int offset,
            int[] ids, boolean[] isMessage, boolean[] forwarded,
            boolean[] isDocument, String[] captions, String[] fileNames);
    private static native String settingsFingerprintNative(byte[] bytes);
    private static native boolean isConfigVersionKeyNative(String key);
    private static native boolean settingsTextWritableNative(byte[] bytes);
    private static native RawReply reviewConfigNative(long accountUserId,
            boolean scanComplete, int[] ids, int[] transport,
            long[] documentIds, long[] editDates, byte[][] records,
            byte[] state, byte[] staged, int localStatus, byte[] localText,
            String device, String platform, String app);
    private static native RawReply diffConfigNative(byte[] before, byte[] after);
    private static native RawReply planConfigApplyNative(long accountUserId,
            boolean scanComplete, int[] ids, int[] transport,
            long[] documentIds, long[] editDates, byte[][] records,
            byte[] state, byte[] staged, int localStatus, byte[] localText,
            String stamp, String chosenKey, boolean afterJoin,
            int preJoinStatus, byte[] preJoinText);
    private static native RawReply completeConfigApplyNative(long accountUserId,
            boolean scanComplete, int[] ids, int[] transport,
            long[] documentIds, long[] editDates, byte[][] records,
            byte[] state, byte[] staged, int localStatus, byte[] localText,
            String stamp, String chosenKey, boolean afterJoin,
            int preJoinStatus, byte[] preJoinText, int readBackStatus,
            byte[] readBackText);
    private static native RawReply checkConfigCommitNative(byte[] current,
            byte[] next);
    private static native RawReply planPostEntryNative(boolean pendingOnly,
            String expectedFingerprint, String[] expectedParents,
            boolean staged);
    private static native RawReply planConfigPostNative(long accountUserId,
            boolean scanComplete, int[] ids, int[] transport,
            long[] documentIds, long[] editDates, byte[][] records,
            byte[] state, byte[] staged, int localStatus, byte[] localText,
            boolean pendingOnly, String expectedFingerprint,
            String[] expectedParents, byte[] accountToken, long now,
            String platform, String app, boolean sendQueued);
    private static native RawReply checkStagedPostNative(long accountUserId,
            boolean scanComplete, int[] ids, int[] transport,
            long[] documentIds, long[] editDates, byte[][] records,
            byte[] state, byte[] staged, byte[] accountToken);
    private static native RawReply describeApplyFailureNative(String status,
            boolean joined, boolean wroteFile, boolean historyKept,
            boolean undoAvailable, boolean otherVersionsRemain);
    private static native RawReply undoFinishedNative(String status);

    private interface Parser<T> {
        T parse(RawReply raw, JSONObject json) throws JSONException;
    }

    private interface Failure<T> {
        T failure(String error);
    }

    private interface Call {
        RawReply call();
    }

    private static <T> T answer(Call call, Parser<T> parser, Failure<T> failure) {
        final RawReply raw;
        try {
            PurpleCore.ensureLoaded();
            raw = call.call();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return failure.failure("NativeUnavailable");
        }
        if (raw == null || raw.metadataJson == null) {
            return failure.failure("NativeUnavailable");
        }
        try {
            final JSONObject json = new JSONObject(raw.metadataJson);
            final String status = string(json, "status");
            if (!"Valid".equals(status)) {
                final String error = optionalString(json, "error");
                return failure.failure(error != null ? error : "Invalid");
            }
            return parser.parse(raw, json);
        } catch (JSONException e) {
            return failure.failure("MalformedNativeReply");
        } catch (RuntimeException e) {
            return failure.failure("MalformedNativeReply");
        }
    }

    public static Page classifyHistoryPage(int offset, int[] ids,
            boolean[] isMessage, boolean[] forwarded, boolean[] isDocument,
            String[] captions, String[] fileNames) {
        return answer(() -> classifyHistoryPageNative(offset, ids, isMessage,
                        forwarded, isDocument, captions, fileNames),
                (raw, json) -> new Page(json), Page::new);
    }

    public static String settingsFingerprint(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            PurpleCore.ensureLoaded();
            final String fingerprint = settingsFingerprintNative(bytes);
            return (fingerprint != null && !fingerprint.isEmpty())
                    ? fingerprint : null;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return null;
        }
    }

    public static boolean isConfigVersionKey(String key) {
        if (key == null) {
            return false;
        }
        try {
            PurpleCore.ensureLoaded();
            return isConfigVersionKeyNative(key);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return false;
        }
    }

    public static boolean isSettingsTextWritable(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        try {
            PurpleCore.ensureLoaded();
            return settingsTextWritableNative(bytes);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return false;
        }
    }

    public static Review review(PurpleSyncInventory inventory, byte[] state,
            byte[] staged, PurpleSyncSettingsFile.Contents local,
            String device) {
        return review(inventory, state, staged, local, device,
                WRITER_PLATFORM, WRITER_APP);
    }

    static Review review(PurpleSyncInventory inventory, byte[] state,
            byte[] staged, PurpleSyncSettingsFile.Contents local,
            String device, String platform, String app) {
        if (inventory == null || local == null || device == null) {
            return Review.failure("NullInput");
        }
        return answer(() -> reviewConfigNative(inventory.accountUserId,
                        inventory.scanComplete, inventory.ids,
                        inventory.transport, inventory.documentIds,
                        inventory.editDates, inventory.bytes, state, staged,
                        localStatus(local), localText(local), device,
                        platform, app),
                (raw, json) -> new Review(json, raw.texts), Review::failure);
    }

    public static Diff diff(byte[] before, byte[] after) {
        return answer(() -> diffConfigNative(before, after),
                (raw, json) -> new Diff(json), Diff::new);
    }

    public static ApplyPlan planApply(ApplyRequest request) {
        final PurpleSyncInventory inventory = request.inventory;
        return answer(() -> planConfigApplyNative(inventory.accountUserId,
                        inventory.scanComplete, inventory.ids,
                        inventory.transport, inventory.documentIds,
                        inventory.editDates, inventory.bytes, request.state,
                        request.staged, localStatus(request.local),
                        localText(request.local), request.stamp,
                        request.chosenKey, request.afterJoin,
                        localStatus(request.preJoin),
                        localText(request.preJoin)),
                (raw, json) -> new ApplyPlan(json, raw.text), ApplyPlan::new);
    }

    public static ApplyCompletion completeApply(ApplyRequest request,
            PurpleSyncSettingsFile.Contents readBack) {
        if (readBack == null) {
            return new ApplyCompletion("NullInput");
        }
        final PurpleSyncInventory inventory = request.inventory;
        return answer(() -> completeConfigApplyNative(inventory.accountUserId,
                        inventory.scanComplete, inventory.ids,
                        inventory.transport, inventory.documentIds,
                        inventory.editDates, inventory.bytes, request.state,
                        request.staged, localStatus(request.local),
                        localText(request.local), request.stamp,
                        request.chosenKey, request.afterJoin,
                        localStatus(request.preJoin),
                        localText(request.preJoin), localStatus(readBack),
                        localText(readBack)),
                (raw, json) -> new ApplyCompletion(json, raw.state),
                ApplyCompletion::new);
    }

    public static CommitCheck checkCommit(byte[] current, byte[] next) {
        return answer(() -> checkConfigCommitNative(current, next),
                (raw, json) -> new CommitCheck(json, raw.state),
                CommitCheck::new);
    }

    public static EntryPlan planPostEntry(PostRequest request, boolean staged) {
        return answer(() -> planPostEntryNative(request.pendingOnly,
                        request.expectedFingerprint, request.parents(), staged),
                (raw, json) -> new EntryPlan(json), EntryPlan::new);
    }

    public static PostPlan planPost(int currentAccount,
            PurpleSyncInventory inventory, byte[] state, byte[] staged,
            PurpleSyncSettingsFile.Contents local, PostRequest request,
            long nowSeconds, boolean sendQueued) {
        return planPost(currentAccount, inventory, state, staged, local,
                request, nowSeconds, sendQueued, WRITER_PLATFORM, WRITER_APP);
    }

    static PostPlan planPost(int currentAccount,
            PurpleSyncInventory inventory, byte[] state, byte[] staged,
            PurpleSyncSettingsFile.Contents local, PostRequest request,
            long nowSeconds, boolean sendQueued, String platform, String app) {
        if (inventory == null || local == null || request == null) {
            return new PostPlan("NullInput");
        }
        final PurpleAccountBinding.Binding binding =
                PurpleAccountBinding.read(currentAccount);
        if (!binding.isValid()) {
            return new PostPlan(binding.error);
        }
        if (binding.userId != inventory.accountUserId) {
            return new PostPlan("AccountChanged");
        }
        final byte[] token = tokenBytes(binding.token);
        final PostPlan plan = answer(() -> planConfigPostNative(
                        inventory.accountUserId, inventory.scanComplete,
                        inventory.ids, inventory.transport,
                        inventory.documentIds, inventory.editDates,
                        inventory.bytes, state, staged, localStatus(local),
                        localText(local), request.pendingOnly,
                        request.expectedFingerprint, request.parents(), token,
                        nowSeconds, platform, app, sendQueued),
                (raw, json) -> new PostPlan(json, raw.record), PostPlan::new);
        return PurpleAccountBinding.isSameActiveUser(currentAccount,
                binding.userId) ? plan : new PostPlan("AccountChanged");
    }

    public static PostPlan checkStagedPost(int currentAccount,
            PurpleSyncInventory inventory, byte[] state, byte[] staged) {
        if (inventory == null) {
            return new PostPlan("NullInput");
        }
        final PurpleAccountBinding.Binding binding =
                PurpleAccountBinding.read(currentAccount);
        if (!binding.isValid()) {
            return new PostPlan(binding.error);
        }
        if (binding.userId != inventory.accountUserId) {
            return new PostPlan("AccountChanged");
        }
        final byte[] token = tokenBytes(binding.token);
        final PostPlan plan = answer(() -> checkStagedPostNative(
                        inventory.accountUserId, inventory.scanComplete,
                        inventory.ids, inventory.transport,
                        inventory.documentIds, inventory.editDates,
                        inventory.bytes, state, staged, token),
                (raw, json) -> new PostPlan(json, raw.record), PostPlan::new);
        return PurpleAccountBinding.isSameActiveUser(currentAccount,
                binding.userId) ? plan : new PostPlan("AccountChanged");
    }

    public static ApplyFailure describeApplyFailure(ApplyStatus status,
            boolean joined, boolean wroteFile, boolean historyKept,
            boolean undoAvailable, boolean otherVersionsRemain) {
        if (status == null) {
            return new ApplyFailure("NullInput");
        }
        return answer(() -> describeApplyFailureNative(status.name(), joined,
                        wroteFile, historyKept, undoAvailable,
                        otherVersionsRemain),
                (raw, json) -> new ApplyFailure(json), ApplyFailure::new);
    }

    public static UndoCheck undoFinished(RestoreStatus status) {
        if (status == null) {
            return new UndoCheck("NullInput");
        }
        return answer(() -> undoFinishedNative(status.name()),
                (raw, json) -> new UndoCheck(json), UndoCheck::new);
    }

    private static int localStatus(PurpleSyncSettingsFile.Contents local) {
        if (local == null) {
            return LOCAL_INVALID;
        }
        switch (local.status) {
            case Present:
                return LOCAL_PRESENT;
            case Absent:
                return LOCAL_ABSENT;
            default:
                return LOCAL_INVALID;
        }
    }

    private static byte[] localText(PurpleSyncSettingsFile.Contents local) {
        return (local != null
                && local.status == PurpleSyncSettingsFile.Status.Present)
                ? local.bytes : null;
    }

    private static byte[] tokenBytes(String token) {
        return token.getBytes(StandardCharsets.UTF_8);
    }

    private static DeviceName deviceName(JSONObject json) throws JSONException {
        return new DeviceName(string(json, "platform"), string(json, "shortId"));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name)
            throws JSONException {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            throw new JSONException("unknown " + type.getSimpleName() + " " + name);
        }
    }

    private static String string(JSONObject json, String name)
            throws JSONException {
        final Object value = json.opt(name);
        if (!(value instanceof String)) {
            throw new JSONException(name + " must be a string");
        }
        return (String) value;
    }

    private static String optionalString(JSONObject json, String name)
            throws JSONException {
        final Object value = json.opt(name);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new JSONException(name + " must be a string");
        }
        return (String) value;
    }

    private static boolean bool(JSONObject json, String name)
            throws JSONException {
        final Object value = json.opt(name);
        if (!(value instanceof Boolean)) {
            throw new JSONException(name + " must be a boolean");
        }
        return (Boolean) value;
    }

    private static int integer(JSONObject json, String name)
            throws JSONException {
        return exactInt(json.opt(name), name);
    }

    private static int exactInt(Object value, String name)
            throws JSONException {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            final long wide = (Long) value;
            if (wide == (int) wide) {
                return (int) wide;
            }
        }
        throw new JSONException(name + " must be an integer");
    }

    private static long decimal(JSONObject json, String name)
            throws JSONException {
        final String value = string(json, name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new JSONException(name + " is not a safe integer");
        }
    }

    private static JSONArray array(JSONObject json, String name)
            throws JSONException {
        final Object value = json.opt(name);
        if (!(value instanceof JSONArray)) {
            throw new JSONException(name + " must be an array");
        }
        return (JSONArray) value;
    }

    private static JSONObject object(JSONObject json, String name)
            throws JSONException {
        final Object value = json.opt(name);
        if (!(value instanceof JSONObject)) {
            throw new JSONException(name + " must be an object");
        }
        return (JSONObject) value;
    }

    private static JSONObject optionalObject(JSONObject json, String name)
            throws JSONException {
        final Object value = json.opt(name);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (!(value instanceof JSONObject)) {
            throw new JSONException(name + " must be an object");
        }
        return (JSONObject) value;
    }

    private static JSONObject element(JSONArray list, int index)
            throws JSONException {
        final Object value = list.opt(index);
        if (!(value instanceof JSONObject)) {
            throw new JSONException("array element must be an object");
        }
        return (JSONObject) value;
    }

    private static List<String> strings(JSONObject json, String name)
            throws JSONException {
        final JSONArray list = array(json, name);
        final List<String> result = new ArrayList<>();
        for (int i = 0; i != list.length(); ++i) {
            final Object value = list.opt(i);
            if (!(value instanceof String)) {
                throw new JSONException(name + " must hold strings");
            }
            result.add((String) value);
        }
        return Collections.unmodifiableList(result);
    }
}
