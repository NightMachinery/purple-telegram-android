/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PurpleSyncApply {
    static final String REASON_APPLY = "sync apply";
    static final String REASON_RESTORE = "sync restore";
    static final String REASON_UNDO = "sync undo";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final PurpleSyncSettingsFile.Contents NO_FILE =
            new PurpleSyncSettingsFile.Contents(
                    PurpleSyncSettingsFile.Status.Invalid, new byte[0]);

    public enum SetupStatus {
        Ready, AlreadyBound, AccountUnavailable, NeedsReview, AccountUnbound,
        StoreError, InvalidGeneratedState
    }

    public static final class ApplyResult {
        public final PurpleSyncCore.ApplyStatus status;
        public final PurpleAccountSyncStore.Status storeStatus;
        public final SetupStatus setupStatus;
        public final String historyId;
        public final String fingerprint;
        public final PurpleSyncCore.Verdict nextVerdict;
        public final List<String> expectedParents;
        public final PurpleSyncCore.Head source;
        public final boolean joined;
        public final boolean wroteFile;
        public final boolean undoAvailable;
        public final boolean otherVersionsRemain;
        public final boolean adopted;
        public final boolean publishNeeded;

        private ApplyResult(Draft draft, PurpleSyncCore.ApplyStatus status,
                PurpleAccountSyncStore.Status storeStatus) {
            this.status = status;
            this.storeStatus = storeStatus;
            setupStatus = draft.setupStatus;
            historyId = draft.historyId;
            fingerprint = draft.fingerprint;
            nextVerdict = draft.nextVerdict;
            expectedParents = draft.expectedParents;
            source = draft.source;
            joined = draft.joined;
            wroteFile = draft.wroteFile;
            undoAvailable = draft.undoAvailable;
            otherVersionsRemain = draft.otherVersionsRemain;
            adopted = draft.adopted;
            publishNeeded = draft.publishNeeded;
        }

        public boolean historyKept() {
            return !historyId.isEmpty();
        }
    }

    public static final class RestoreResult {
        public final PurpleSyncCore.RestoreStatus status;
        public final String historyId;
        public final String fingerprint;

        RestoreResult(PurpleSyncCore.RestoreStatus status, String historyId,
                String fingerprint) {
            this.status = status;
            this.historyId = historyId;
            this.fingerprint = fingerprint;
        }
    }

    static final class Reviewed {
        final PurpleSyncCore.Review review;
        final PurpleSyncSettingsFile.Contents local;
        final ApplyResult adoptFailure;

        Reviewed(PurpleSyncCore.Review review,
                PurpleSyncSettingsFile.Contents local, ApplyResult adoptFailure) {
            this.review = review;
            this.local = local;
            this.adoptFailure = adoptFailure;
        }
    }

    static final class Pending {
        final PurpleAccountSyncStore.Status status;
        final byte[] staged;

        private Pending(PurpleAccountSyncStore.Status status, byte[] staged) {
            this.status = status;
            this.staged = staged;
        }

        boolean ok() {
            return status == PurpleAccountSyncStore.Status.Ready;
        }
    }

    private static final class Draft {
        SetupStatus setupStatus;
        String historyId = "";
        String fingerprint = "";
        PurpleSyncCore.Verdict nextVerdict = PurpleSyncCore.Verdict.Invalid;
        List<String> expectedParents = Collections.emptyList();
        PurpleSyncCore.Head source;
        boolean joined;
        boolean wroteFile;
        boolean undoAvailable;
        boolean otherVersionsRemain;
        boolean adopted;
        boolean publishNeeded;

        ApplyResult stop(PurpleSyncCore.ApplyStatus status) {
            return new ApplyResult(this, status, null);
        }

        ApplyResult stop(PurpleSyncCore.ApplyStatus status,
                PurpleAccountSyncStore.Status storeStatus) {
            return new ApplyResult(this, status, storeStatus);
        }
    }

    private static final class Joined {
        final SetupStatus status;
        final PurpleAccountSyncStore.Status storeStatus;

        Joined(SetupStatus status, PurpleAccountSyncStore.Status storeStatus) {
            this.status = status;
            this.storeStatus = storeStatus;
        }
    }

    private static final class Written {
        PurpleSyncCore.ApplyStatus status = PurpleSyncCore.ApplyStatus.WriteError;
        String historyId = "";
        PurpleSyncSettingsFile.Contents readBack;
        boolean wrote;
        boolean restorable;
    }

    private PurpleSyncApply() {
    }

    static Reviewed review(PurpleSyncRunner.Session session,
            PurpleSyncInventory inventory) {
        if (!session.available() || inventory == null
                || inventory.accountUserId != session.userId) {
            return failed(PurpleSyncCore.ReviewStatus.AccountUnavailable);
        }
        final PurpleSyncRunner.Env env = session.env;
        final PurpleSyncSettingsFile.Contents local = env.readSettings();
        final String device = env.device();
        if (!env.hasState()) {
            return reviewed(session, PurpleSyncCore.review(inventory, null, null,
                    local, device), local);
        }
        final PurpleAccountSyncStore store = env.store();
        try {
            final PurpleAccountSyncStore.Result opened =
                    store.open(true, session.account, device);
            if (opened.status == PurpleAccountSyncStore.Status.Uninitialized) {
                store.close();
                return reviewed(session, PurpleSyncCore.review(inventory, null,
                        null, local, device), local);
            } else if (opened.status == PurpleAccountSyncStore.Status.AccountUnbound) {
                return failed(PurpleSyncCore.ReviewStatus.AccountUnbound);
            } else if (opened.status != PurpleAccountSyncStore.Status.Ready) {
                FileLog.e("Purple: settings sync store did not open: "
                        + opened.status);
                return failed(PurpleSyncCore.ReviewStatus.StoreError);
            }
            final Pending pending = readPending(store);
            final byte[] state = store.stateBytes();
            if (!pending.ok() || state == null) {
                FileLog.e("Purple: settings sync stage unreadable: "
                        + pending.status);
                return failed(PurpleSyncCore.ReviewStatus.StoreError);
            }
            return reviewed(session, PurpleSyncCore.review(inventory, state,
                    pending.staged, local, device), local);
        } finally {
            store.close();
        }
    }

    static Reviewed reviewWithAdopt(PurpleSyncRunner.Session session,
            PurpleSyncInventory inventory) {
        final Reviewed first = review(session, inventory);
        final PurpleSyncCore.Review review = first.review;
        if (review.status != PurpleSyncCore.ReviewStatus.Ready || !review.bound
                || review.verdict != PurpleSyncCore.Verdict.Adopt) {
            return first;
        }
        final ApplyResult adopted = apply(session, inventory, review, null);
        if (adopted.status != PurpleSyncCore.ApplyStatus.Applied) {
            FileLog.e("Purple: settings sync could not record matching "
                    + "settings: " + adopted.status);
            return new Reviewed(review, first.local, adopted);
        }
        return review(session, inventory);
    }

    static ApplyResult apply(PurpleSyncRunner.Session session,
            PurpleSyncInventory inventory, PurpleSyncCore.Review shown,
            String chosenKey) {
        final Draft draft = new Draft();
        if (!session.available() || inventory == null || shown == null
                || inventory.accountUserId != session.userId
                || shown.accountUserId != inventory.accountUserId) {
            return draft.stop(PurpleSyncCore.ApplyStatus.AccountUnavailable);
        } else if (!shown.isValid()) {
            return draft.stop(PurpleSyncCore.ApplyStatus.NeedsReview);
        }
        final PurpleSyncRunner.Env env = session.env;
        final PurpleAccountSyncStore store = env.store();
        try {
            PurpleSyncSettingsFile.Contents preJoin = null;
            if (!shown.bound) {
                preJoin = env.readSettings();
                final PurpleSyncCore.ApplyPlan before = PurpleSyncCore.planApply(
                        PurpleSyncCore.ApplyRequest.unbound(inventory, preJoin,
                                shown.stamp, chosenKey));
                if (!before.isValid()) {
                    return draft.stop(PurpleSyncCore.ApplyStatus.NeedsReview);
                } else if (before.status != PurpleSyncCore.ApplyPlanStatus.Ready) {
                    return draft.stop(statusOf(before.status));
                } else if (!before.join) {
                    return draft.stop(PurpleSyncCore.ApplyStatus.NeedsRecheck);
                }
                final Joined joined = join(session, store, before.space);
                draft.setupStatus = joined.status;
                if (joined.status == SetupStatus.AlreadyBound) {
                    return draft.stop(PurpleSyncCore.ApplyStatus.NeedsRecheck);
                } else if (joined.status != SetupStatus.Ready) {
                    return draft.stop(PurpleSyncCore.ApplyStatus.SetupFailed,
                            joined.storeStatus);
                }
                draft.joined = true;
            } else {
                final PurpleAccountSyncStore.Result opened =
                        store.open(true, session.account, env.device());
                if (opened.status == PurpleAccountSyncStore.Status.AccountUnbound) {
                    return draft.stop(PurpleSyncCore.ApplyStatus.AccountUnbound);
                } else if (opened.status != PurpleAccountSyncStore.Status.Ready) {
                    return draft.stop(PurpleSyncCore.ApplyStatus.StoreError,
                            opened.status);
                }
            }
            final byte[] state = store.stateBytes();
            if (state == null) {
                return draft.stop(PurpleSyncCore.ApplyStatus.StoreError,
                        PurpleAccountSyncStore.Status.InvalidState);
            }
            final Pending pending = readPending(store);
            if (!pending.ok()) {
                return draft.stop(PurpleSyncCore.ApplyStatus.StoreError,
                        pending.status);
            }
            final PurpleSyncSettingsFile.Contents local = env.readSettings();
            final PurpleSyncCore.ApplyRequest request = shown.bound
                    ? PurpleSyncCore.ApplyRequest.bound(inventory, state,
                            pending.staged, local, shown.stamp, chosenKey)
                    : PurpleSyncCore.ApplyRequest.afterJoin(inventory, state,
                            local, shown.stamp, chosenKey, preJoin);
            final PurpleSyncCore.ApplyPlan plan = PurpleSyncCore.planApply(request);
            if (!plan.isValid()) {
                return draft.stop(PurpleSyncCore.ApplyStatus.NeedsReview);
            } else if (plan.status != PurpleSyncCore.ApplyPlanStatus.Ready
                    || plan.join) {
                return draft.stop(plan.join
                        ? PurpleSyncCore.ApplyStatus.NeedsRecheck
                        : statusOf(plan.status));
            }
            draft.fingerprint = plan.localFingerprint;
            PurpleSyncSettingsFile.Contents current = local;
            if (plan.writeRemote) {
                if (plan.source == null) {
                    return draft.stop(PurpleSyncCore.ApplyStatus.NeedsReview);
                }
                draft.otherVersionsRemain = plan.otherVersionsRemain;
                draft.source = plan.source;
                final Written write = writeWithHistory(env, session, local,
                        plan.source.text, plan.writeFingerprint,
                        plan.update ? PurpleSyncHistory.Reason.BeforeUpdate
                                : PurpleSyncHistory.Reason.BeforeChoice,
                        deviceLabel(plan.source.name), plan.versionKey, null,
                        true, REASON_APPLY);
                draft.historyId = write.historyId;
                draft.wroteFile = write.wrote;
                draft.undoAvailable = write.wrote && write.restorable;
                if (write.status != PurpleSyncCore.ApplyStatus.Applied) {
                    return draft.stop(write.status);
                }
                current = write.readBack;
            }
            final PurpleSyncCore.ApplyCompletion completion =
                    PurpleSyncCore.completeApply(request, current);
            if (!completion.isValid()) {
                return draft.stop(PurpleSyncCore.ApplyStatus.NeedsReview);
            }
            switch (completion.status) {
                case Ready:
                    break;
                case ReadBackMismatch:
                    return draft.stop(PurpleSyncCore.ApplyStatus.WriteError);
                default:
                    return draft.stop(PurpleSyncCore.ApplyStatus.NeedsReview);
            }
            if (completion.adopted) {
                if (completion.commit == PurpleSyncCore.CommitStatus.Ready) {
                    if (!session.available()) {
                        return draft.stop(PurpleSyncCore.ApplyStatus.StoreError,
                                PurpleAccountSyncStore.Status.AccountUnbound);
                    }
                    final PurpleAccountSyncStore.Result committed =
                            store.commitConfigState(completion.nextState);
                    if (committed.status != PurpleAccountSyncStore.Status.Ready
                            && committed.status
                                    != PurpleAccountSyncStore.Status.Unchanged) {
                        return draft.stop(PurpleSyncCore.ApplyStatus.StoreError,
                                committed.status);
                    }
                } else if (completion.commit != PurpleSyncCore.CommitStatus.Unchanged) {
                    return draft.stop(PurpleSyncCore.ApplyStatus.StoreError,
                            PurpleAccountSyncStore.Status.InvalidTransition);
                }
                draft.adopted = true;
            }
            draft.fingerprint = completion.fingerprint;
            draft.nextVerdict = completion.nextVerdict;
            draft.publishNeeded = completion.publishNeeded;
            draft.expectedParents = completion.expectedParents;
            if (!completion.promiseKept) {
                FileLog.e("Purple: the settings choice promised a different "
                        + "publish than a fresh check proposes.");
            }
            return draft.stop(PurpleSyncCore.ApplyStatus.Applied);
        } finally {
            store.close();
        }
    }

    static RestoreResult restore(PurpleSyncRunner.Env env, String id,
            PurpleSyncHistory.Reason reason) {
        if (reason != PurpleSyncHistory.Reason.BeforeRestore
                && reason != PurpleSyncHistory.Reason.BeforeUndo) {
            return restored(PurpleSyncCore.RestoreStatus.InvalidReason);
        }
        final PurpleSyncHistory history = env.history();
        PurpleSyncHistory.Entry entry = null;
        for (PurpleSyncHistory.Entry listed : history.list()) {
            if (listed.id.equals(id)) {
                entry = listed;
                break;
            }
        }
        if (entry == null) {
            return restored(PurpleSyncCore.RestoreStatus.NotFound);
        } else if (!entry.existed) {
            return restored(PurpleSyncCore.RestoreStatus.FileDidNotExist);
        }
        final byte[] text = history.read(id);
        if (text == null
                || !entry.fingerprint.equals(PurpleSyncCore.settingsFingerprint(text))) {
            return restored(PurpleSyncCore.RestoreStatus.NotFound);
        } else if (!PurpleSyncCore.isSettingsTextWritable(text)) {
            return restored(PurpleSyncCore.RestoreStatus.NotText);
        }
        final PurpleSyncSettingsFile.Contents local = env.readSettings();
        if (local.status == PurpleSyncSettingsFile.Status.Invalid) {
            return restored(PurpleSyncCore.RestoreStatus.InvalidSettings);
        } else if (local.status == PurpleSyncSettingsFile.Status.Present
                && Arrays.equals(local.bytes, text)) {
            return new RestoreResult(PurpleSyncCore.RestoreStatus.Unchanged, "",
                    entry.fingerprint);
        }
        final boolean undo = reason == PurpleSyncHistory.Reason.BeforeUndo;
        final Written write = writeWithHistory(env, null, local, text,
                entry.fingerprint, reason, entry.id, "", id, false,
                undo ? REASON_UNDO : REASON_RESTORE);
        switch (write.status) {
            case Applied:
                return new RestoreResult(PurpleSyncCore.RestoreStatus.Restored,
                        write.historyId, entry.fingerprint);
            case HistoryError:
                return new RestoreResult(PurpleSyncCore.RestoreStatus.HistoryError,
                        write.historyId, "");
            default:
                return new RestoreResult(PurpleSyncCore.RestoreStatus.WriteError,
                        write.historyId, "");
        }
    }

    static Pending readPending(PurpleAccountSyncStore store) {
        final PurpleAccountSyncStore.Result pending = store.readPendingConfig();
        switch (pending.status) {
            case Ready:
                return new Pending(PurpleAccountSyncStore.Status.Ready,
                        pending.staged);
            case NoPending:
                return new Pending(PurpleAccountSyncStore.Status.Ready, null);
            default:
                return new Pending(pending.status, null);
        }
    }

    static PurpleSyncSettingsFile.Contents noFile() {
        return NO_FILE;
    }

    public static String deviceLabel(PurpleSyncCore.DeviceName name) {
        if (name == null) {
            return "";
        }
        final String label = name.platform.isEmpty()
                ? name.shortId : name.platform + " " + name.shortId;
        return label.length() > PurpleSyncHistory.MAX_LABEL_LENGTH
                ? label.substring(0, PurpleSyncHistory.MAX_LABEL_LENGTH) : label;
    }

    private static Joined join(PurpleSyncRunner.Session session,
            PurpleAccountSyncStore store, String selectedSpace) {
        if (!session.available()) {
            return new Joined(SetupStatus.AccountUnavailable, null);
        }
        final PurpleSyncRunner.Env env = session.env;
        final String device = env.device();
        final PurpleAccountSyncStore.Result opened =
                store.open(true, session.account, device);
        if (opened.status == PurpleAccountSyncStore.Status.Ready) {
            final PurpleAccountSyncCore.Result inspected =
                    PurpleAccountSyncCore.inspectState(store.stateBytes());
            return new Joined(inspected.isValid() && !selectedSpace.isEmpty()
                    && selectedSpace.equals(inspected.space)
                    ? SetupStatus.AlreadyBound : SetupStatus.NeedsReview, null);
        } else if (opened.status == PurpleAccountSyncStore.Status.AccountUnbound) {
            return new Joined(SetupStatus.AccountUnbound, null);
        } else if (opened.status != PurpleAccountSyncStore.Status.Uninitialized) {
            return new Joined(SetupStatus.StoreError, opened.status);
        }
        final byte[] entropy = new byte[16];
        RANDOM.nextBytes(entropy);
        final PurpleAccountSyncCore.Result install =
                PurpleAccountSyncCore.formatInstallId(entropy);
        if (!install.isValid() || install.id.isEmpty()) {
            return new Joined(SetupStatus.InvalidGeneratedState, null);
        }
        String space = selectedSpace;
        if (space.isEmpty()) {
            final long serverMillis = env.serverTimeMillis(session.account);
            if (serverMillis <= 0) {
                return new Joined(SetupStatus.InvalidGeneratedState, null);
            }
            final PurpleAccountSyncCore.Result created =
                    PurpleAccountSyncCore.formatTimeOrderedSpaceId(serverMillis);
            if (!created.isValid() || created.id.isEmpty()) {
                return new Joined(SetupStatus.InvalidGeneratedState, null);
            }
            space = created.id;
        }
        if (!session.available()) {
            return new Joined(SetupStatus.AccountUnavailable, null);
        }
        final PurpleAccountSyncCore.Result initialized =
                PurpleAccountSyncCore.initializeBoundLocalState(session.account,
                        install.id, device, space);
        if (!initialized.isValid() || initialized.state == null) {
            return new Joined(setupFailure(initialized.error), null);
        } else if (!session.available()) {
            return new Joined(SetupStatus.AccountUnavailable, null);
        }
        final PurpleAccountSyncStore.Result stored =
                store.initialize(initialized.state);
        if (stored.status == PurpleAccountSyncStore.Status.AccountUnbound) {
            return new Joined(SetupStatus.AccountUnbound, null);
        } else if (stored.status != PurpleAccountSyncStore.Status.Ready) {
            return new Joined(SetupStatus.StoreError, stored.status);
        }
        FileLog.d("Purple: joined settings sync in "
                + (selectedSpace.isEmpty() ? "a new" : "the existing")
                + " space.");
        return new Joined(SetupStatus.Ready, null);
    }

    private static SetupStatus setupFailure(String error) {
        if ("AccountUnavailable".equals(error) || "AccountChanged".equals(error)) {
            return SetupStatus.AccountUnavailable;
        } else if ("InvalidBindingToken".equals(error)
                || "BindingConflict".equals(error)
                || "BindingWriteFailed".equals(error)
                || "BindingUnavailable".equals(error)) {
            return SetupStatus.AccountUnbound;
        }
        FileLog.e("Purple: settings sync state could not be created: " + error);
        return SetupStatus.InvalidGeneratedState;
    }

    private static Written writeWithHistory(PurpleSyncRunner.Env env,
            PurpleSyncRunner.Session account,
            PurpleSyncSettingsFile.Contents current, byte[] text,
            String fingerprint, PurpleSyncHistory.Reason reason, String label,
            String versionKey, String keepId, boolean fromImport,
            String writeReason) {
        final Written result = new Written();
        if (text == null || fingerprint == null
                || !fingerprint.equals(PurpleSyncCore.settingsFingerprint(text))
                || !PurpleSyncCore.isSettingsTextWritable(text)) {
            return result;
        } else if (account != null && !account.available()) {
            result.status = PurpleSyncCore.ApplyStatus.AccountUnavailable;
            return result;
        }
        final boolean existed =
                current.status == PurpleSyncSettingsFile.Status.Present;
        final PurpleSyncHistory.Entry saved = env.history().save(
                existed ? current.bytes : null, reason, label, versionKey, keepId);
        if (saved == null) {
            result.status = PurpleSyncCore.ApplyStatus.HistoryError;
            return result;
        }
        result.historyId = saved.id;
        result.restorable = saved.existed
                && PurpleSyncCore.isSettingsTextWritable(current.bytes);
        if (account != null && !account.available()) {
            result.status = PurpleSyncCore.ApplyStatus.AccountUnavailable;
            return result;
        }
        final PurpleSyncSettingsFile.WriteResult written =
                env.replaceSettings(current, text, writeReason, fromImport);
        result.wrote = written.wrote();
        if (written.status == PurpleSyncSettingsFile.WriteStatus.Changed) {
            result.status = PurpleSyncCore.ApplyStatus.NeedsRecheck;
            return result;
        } else if (written.status != PurpleSyncSettingsFile.WriteStatus.Written
                || written.readBack == null
                || written.readBack.status != PurpleSyncSettingsFile.Status.Present
                || !Arrays.equals(written.readBack.bytes, text)
                || !fingerprint.equals(PurpleSyncCore.settingsFingerprint(
                        written.readBack.bytes))) {
            FileLog.e("Purple: settings.toml did not read back as written.");
            return result;
        }
        result.readBack = written.readBack;
        result.status = PurpleSyncCore.ApplyStatus.Applied;
        return result;
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

    private static Reviewed failed(PurpleSyncCore.ReviewStatus status) {
        return new Reviewed(PurpleSyncCore.Review.clientFailure(status), null, null);
    }

    private static Reviewed reviewed(PurpleSyncRunner.Session session,
            PurpleSyncCore.Review review, PurpleSyncSettingsFile.Contents local) {
        if (!review.isValid()) {
            FileLog.e("Purple: settings sync review failed: " + review.error);
            return failed(PurpleSyncCore.ReviewStatus.StoreError);
        } else if (review.accountUserId != session.userId || !session.available()) {
            return failed(PurpleSyncCore.ReviewStatus.AccountUnavailable);
        }
        return new Reviewed(review, local, null);
    }

    private static RestoreResult restored(PurpleSyncCore.RestoreStatus status) {
        return new RestoreResult(status, "", "");
    }
}
