/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleSyncApply;
import org.telegram.messenger.purple.PurpleSyncCore;
import org.telegram.messenger.purple.PurpleSyncHistory;
import org.telegram.messenger.purple.PurpleSyncPublisher;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class PurpleSyncText {
    static final int SUMMARY_LIMIT = 6;
    static final int DIFF_LINE_LIMIT = 400;
    static final int DIFF_EDIT_LIMIT = 1000;

    private static final int HISTORY_TIME_DIGITS = 16;

    private PurpleSyncText() {
    }

    static String joined(String first, String second) {
        if (TextUtils.isEmpty(first)) {
            return (second == null) ? "" : second;
        } else if (TextUtils.isEmpty(second)) {
            return first;
        }
        return first + "\n\n" + second;
    }

    static String disclosure() {
        return getString(R.string.PurpleSyncDisclosure);
    }

    static String deviceName(PurpleSyncCore.DeviceName name) {
        if (name == null) {
            return getString(R.string.PurpleSyncAnotherDevice);
        }
        final String platform = name.platform.isEmpty()
                ? getString(R.string.PurpleSyncUnnamedDevice) : name.platform;
        return name.shortId.isEmpty() ? platform : platform + " " + name.shortId;
    }

    static String deviceList(List<PurpleSyncCore.DeviceName> devices) {
        final List<String> names = new ArrayList<>();
        for (PurpleSyncCore.DeviceName device : devices) {
            final String name = deviceName(device);
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return getString(R.string.PurpleSyncAnotherDevice);
        } else if (names.size() == 1) {
            return names.get(0);
        }
        final String last = names.remove(names.size() - 1);
        return formatString(R.string.PurpleSyncDevicesAnd, TextUtils.join(", ", names), last);
    }

    static PurpleSyncCore.DeviceName firstDevice(PurpleSyncCore.Review review) {
        return review.devices.isEmpty() ? null : review.devices.get(0);
    }

    static String moment(long millis) {
        final Date date = new Date(millis);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date) + " "
                + LocaleController.getInstance().getFormatterDay().format(date);
    }

    static String recordTime(long seconds) {
        return moment(seconds * 1000L);
    }

    static String clock(long millis) {
        return LocaleController.getInstance().getFormatterDay().format(new Date(millis));
    }

    static long historyMillis(String id) {
        if (id == null || id.length() <= HISTORY_TIME_DIGITS
                || id.charAt(HISTORY_TIME_DIGITS) != '-') {
            return -1;
        }
        for (int i = 0; i != HISTORY_TIME_DIGITS; ++i) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') {
                return -1;
            }
        }
        return Long.parseLong(id.substring(0, HISTORY_TIME_DIGITS));
    }

    static String historyLabel(PurpleSyncHistory.Entry entry) {
        final String label = (entry.label == null) ? "" : entry.label;
        switch (entry.reason) {
            case BeforeUpdate:
                return label.isEmpty()
                        ? getString(R.string.PurpleSyncBeforeAnUpdate)
                        : formatString(R.string.PurpleSyncBeforeUpdateFrom, label);
            case BeforeChoice:
                return label.isEmpty()
                        ? getString(R.string.PurpleSyncBeforeAChoice)
                        : formatString(R.string.PurpleSyncBeforeUsing, label);
            case BeforeRestore: {
                final long restored = historyMillis(label);
                return (restored < 0)
                        ? getString(R.string.PurpleSyncBeforeARestore)
                        : formatString(R.string.PurpleSyncBeforeRestoring, moment(restored));
            }
            default: {
                final long restored = historyMillis(label);
                return (restored < 0)
                        ? getString(R.string.PurpleSyncBeforeAnUndo)
                        : formatString(R.string.PurpleSyncBeforeUndoTo, moment(restored));
            }
        }
    }

    static String historyRow(PurpleSyncHistory.Entry entry) {
        return moment(entry.createdMs) + " · " + historyDetail(entry);
    }

    static String historyDetail(PurpleSyncHistory.Entry entry) {
        final String label = historyLabel(entry);
        return entry.existed
                ? label
                : label + " · " + getString(R.string.PurpleSyncHistoryNoFile);
    }

    static String actionText(PurpleSyncCore.Action action) {
        switch (action) {
            case Publish:
                return getString(R.string.PurpleSyncActionPublish);
            case Join:
                return getString(R.string.PurpleSyncActionJoin);
            case ReviewUpdate:
                return getString(R.string.PurpleSyncActionReviewUpdate);
            case Choose:
                return getString(R.string.PurpleSyncActionChoose);
            case PublishChanges:
                return getString(R.string.PurpleSyncActionPublishChanges);
            case FinishSending:
                return getString(R.string.PurpleSyncActionFinishSending);
            default:
                return "";
        }
    }

    static String chooseText(PurpleSyncCore.Review review) {
        final String devices = deviceList(review.devices);
        switch (review.message) {
            case ChooseBound:
                return formatString(R.string.PurpleSyncChooseBound, devices);
            case ChooseUnbound:
                return formatString(R.string.PurpleSyncChooseUnbound, devices);
            case ConflictConcurrent:
                return formatString(R.string.PurpleSyncConflictConcurrent, devices);
            case ConflictSplitBound:
                return formatString(R.string.PurpleSyncConflictSplitBound, devices);
            default:
                return formatString(R.string.PurpleSyncConflictSplitUnbound, devices);
        }
    }

    static String describe(PurpleSyncCore.Review review, long checkedAt) {
        switch (review.message) {
            case NeedsReviewWithPending:
                return getString(R.string.PurpleSyncNeedsReviewWithPending);
            case NeedsReview:
                return getString(R.string.PurpleSyncNeedsReview);
            case Incomplete:
                return getString(R.string.PurpleSyncIncomplete);
            case CloneDetected:
                return getString(R.string.PurpleSyncCloneDetected);
            case AccountUnavailable:
                return getString(R.string.PurpleSyncAccountUnavailable);
            case AccountUnbound:
                return getString(R.string.PurpleSyncAccountUnbound);
            case StoreError:
                return getString(R.string.PurpleSyncStoreError);
            case InvalidSettings:
                return getString(R.string.PurpleSyncInvalidSettings);
            case InvalidRecords:
                return getString(R.string.PurpleSyncInvalidRecords);
            case Pending:
                return getString(R.string.PurpleSyncPending);
            case ChooseBound:
            case ChooseUnbound:
            case ConflictConcurrent:
            case ConflictSplitBound:
            case ConflictSplitUnbound:
                return chooseText(review) + " " + getString(R.string.PurpleSyncChooseWhich);
            case UpdateReady:
                return formatString(R.string.PurpleSyncUpdateReady,
                        deviceName(firstDevice(review)), recordTime(review.at));
            case UpdateMissing:
                return getString(R.string.PurpleSyncUpdateMissing);
            case AdoptUnbound:
                return formatString(R.string.PurpleSyncAdoptUnbound, deviceList(review.devices));
            case AdoptBound:
                return formatString(R.string.PurpleSyncAdoptBound, deviceList(review.devices));
            case NotPublishableAbsent:
                return getString(R.string.PurpleSyncNotPublishableAbsent);
            case NotPublishableInvalid:
                return getString(R.string.PurpleSyncNotPublishableInvalid);
            case EmptyBound:
                return getString(R.string.PurpleSyncEmptyBound);
            case EmptyUnbound:
                return getString(R.string.PurpleSyncEmptyUnbound);
            case LocalChangesEdited:
                return getString(R.string.PurpleSyncLocalChangesEdited);
            case LocalChangesOwnStale:
                return getString(R.string.PurpleSyncLocalChangesOwnStale);
            case UpToDateAlone:
                return formatString(R.string.PurpleSyncUpToDateAlone, clock(checkedAt));
            case UpToDateWith:
                return formatString(R.string.PurpleSyncUpToDateWith,
                        formatPluralString("PurpleSyncOtherDevices", review.others),
                        clock(checkedAt));
            default:
                return "";
        }
    }

    static String confirmText(PurpleSyncCore.Action action, PurpleSyncCore.Review review) {
        switch (action) {
            case Publish:
                return getString(review.bound
                        ? R.string.PurpleSyncConfirmPublishBound
                        : R.string.PurpleSyncConfirmPublishUnbound) + " " + disclosure();
            case PublishChanges:
                return getString(R.string.PurpleSyncConfirmPublishChanges) + " " + disclosure();
            case FinishSending:
                return getString(R.string.PurpleSyncConfirmFinishSending) + " " + disclosure();
            case Join:
                return formatString(R.string.PurpleSyncConfirmJoin, deviceList(review.devices))
                        + " " + disclosure();
            default:
                return "";
        }
    }

    static String publishText(PurpleSyncPublisher.Result result) {
        switch (result.status) {
            case Confirmed:
                return formatString(R.string.PurpleSyncPublishConfirmed, result.messageId);
            case AlreadySynced:
                return getString(R.string.PurpleSyncPublishAlreadySynced);
            case OutcomeUnknown:
                return getString(R.string.PurpleSyncPublishOutcomeUnknown);
            case NeedsReview:
                return getString(R.string.PurpleSyncPublishNeedsReview);
            case AccountUnbound:
                return getString(R.string.PurpleSyncPublishAccountUnbound);
            case StoreError:
                return getString(R.string.PurpleSyncPublishStoreError);
            case AccountUnavailable:
                return getString(R.string.PurpleSyncPublishAccountUnavailable);
            case CloneDetected:
                return getString(R.string.PurpleSyncPublishCloneDetected);
            case InvalidSettings:
                return getString(R.string.PurpleSyncPublishInvalidSettings);
            case Incomplete:
                return getString(R.string.PurpleSyncPublishIncomplete);
            case Cancelled:
                return getString(R.string.PurpleSyncPublishCancelled);
            case StillSending:
                return getString(R.string.PurpleSyncPublishStillSending);
            default:
                return getString(R.string.PurpleSyncPublishStopped);
        }
    }

    static String applyFailureText(PurpleSyncApply.ApplyResult result,
            PurpleSyncCore.ApplyFailure failure) {
        if (result == null || result.status == PurpleSyncCore.ApplyStatus.Applied) {
            return "";
        }
        PurpleSyncCore.ApplyFailure described = failure;
        if (described == null || !described.isValid()) {
            described = PurpleSyncCore.describeApplyFailure(result.status,
                    result.joined, result.wroteFile, result.historyKept(),
                    result.undoAvailable, result.otherVersionsRemain);
        }
        final PurpleSyncCore.ApplyFailureKind kind;
        if (described.isValid()) {
            kind = described.kind;
        } else if (result.wroteFile) {
            kind = PurpleSyncCore.ApplyFailureKind.WrittenNotReadBack;
        } else if (result.joined) {
            kind = PurpleSyncCore.ApplyFailureKind.JoinedNotWritten;
        } else {
            kind = PurpleSyncCore.ApplyFailureKind.NothingDone;
        }
        final boolean historyKept = result.historyKept();
        switch (kind) {
            case None:
                return "";
            case JoinedNotWritten:
                return formatString(R.string.PurpleSyncJoinedNotWritten,
                        joinedFailureReason(result.status, historyKept));
            case WrittenStateNotSaved:
            case WrittenNotReadBack:
                return (result.joined ? getString(R.string.PurpleSyncJoined) + " " : "")
                        + writtenFailureText(kind, result.otherVersionsRemain,
                                result.undoAvailable);
            default:
                return nothingDoneText(result.status, historyKept);
        }
    }

    private static String joinedFailureReason(PurpleSyncCore.ApplyStatus status,
            boolean historyKept) {
        switch (status) {
            case NeedsRecheck:
                return getString(R.string.PurpleSyncReasonRecheck);
            case NeedsReview:
            case InvalidChoice:
                return getString(R.string.PurpleSyncReasonChoiceGone);
            case AccountUnavailable:
                return getString(R.string.PurpleSyncReasonAccountUnavailable);
            case AccountUnbound:
                return getString(R.string.PurpleSyncReasonAccountUnbound);
            case StoreError:
                return getString(R.string.PurpleSyncReasonStore);
            case InvalidSettings:
                return getString(R.string.PurpleSyncReasonSettings);
            case HistoryError:
                return getString(R.string.PurpleSyncReasonHistory);
            case WriteError:
                return getString(historyKept
                        ? R.string.PurpleSyncReasonWriteFailed
                        : R.string.PurpleSyncReasonNotExact);
            default:
                return getString(R.string.PurpleSyncReasonUnfinished);
        }
    }

    private static String writtenFailureText(PurpleSyncCore.ApplyFailureKind kind,
            boolean otherVersionsRemain, boolean undoAvailable) {
        final String undo = undoAvailable ? " " + getString(R.string.PurpleSyncWrittenUndo) : "";
        if (kind == PurpleSyncCore.ApplyFailureKind.WrittenStateNotSaved) {
            return getString(R.string.PurpleSyncWrittenStateNotSaved) + " "
                    + getString(otherVersionsRemain
                            ? R.string.PurpleSyncWrittenChooseAgain
                            : R.string.PurpleSyncWrittenRecorded)
                    + undo;
        }
        return getString(R.string.PurpleSyncWrittenNotReadBack) + undo;
    }

    private static String nothingDoneText(PurpleSyncCore.ApplyStatus status,
            boolean historyKept) {
        switch (status) {
            case NeedsRecheck:
                return getString(R.string.PurpleSyncApplyRecheck);
            case NeedsReview:
            case InvalidChoice:
                return getString(R.string.PurpleSyncApplyChoiceGone);
            case AccountUnavailable:
                return getString(R.string.PurpleSyncApplyAccountUnavailable);
            case AccountUnbound:
                return getString(R.string.PurpleSyncApplyAccountUnbound);
            case SetupFailed:
                return getString(R.string.PurpleSyncApplySetupFailed);
            case StoreError:
                return getString(R.string.PurpleSyncApplyStoreError);
            case InvalidSettings:
                return getString(R.string.PurpleSyncApplyInvalidSettings);
            case HistoryError:
                return getString(R.string.PurpleSyncHistoryError);
            case WriteError:
                return getString(historyKept
                        ? R.string.PurpleSyncApplyWriteErrorKept
                        : R.string.PurpleSyncApplyWriteErrorExact);
            default:
                return "";
        }
    }

    static String restoreText(PurpleSyncApply.RestoreResult result, long entryMillis) {
        final String when = moment(entryMillis);
        switch (result.status) {
            case Restored:
                return formatString(R.string.PurpleSyncRestoreRestored, when);
            case Unchanged:
                return formatString(R.string.PurpleSyncRestoreUnchanged, when);
            case NotFound:
                return getString(R.string.PurpleSyncRestoreNotFound);
            case FileDidNotExist:
                return getString(R.string.PurpleSyncRestoreNoFile);
            case NotText:
                return getString(R.string.PurpleSyncRestoreNotText);
            case InvalidReason:
                return getString(R.string.PurpleSyncRestoreInvalidReason);
            case InvalidSettings:
                return getString(R.string.PurpleSyncRestoreInvalidSettings);
            case HistoryError:
                return getString(R.string.PurpleSyncHistoryError);
            case WriteError:
                return getString(R.string.PurpleSyncRestoreWriteError);
            default:
                return "";
        }
    }

    static String choiceIntro(PurpleSyncCore.Review review) {
        if (review.verdict == PurpleSyncCore.Verdict.UpdateReady) {
            return formatString(R.string.PurpleSyncUpdateIntro, deviceName(firstDevice(review)));
        }
        return chooseText(review) + " " + getString(R.string.PurpleSyncChooseIntro);
    }

    static String choiceButton(PurpleSyncCore.Review review, boolean publishes) {
        if (review.verdict == PurpleSyncCore.Verdict.UpdateReady) {
            return getString(R.string.PurpleSyncApply);
        } else if (review.bound) {
            return getString(publishes ? R.string.PurpleSyncUseAndShare : R.string.PurpleSyncUseThis);
        }
        return getString(publishes ? R.string.PurpleSyncJoinAndShare : R.string.PurpleSyncJoinWithThis);
    }

    static List<String> changeLines(PurpleSyncCore.Diff diff) {
        final List<String> result = new ArrayList<>();
        if (diff.identical) {
            result.add(getString(R.string.PurpleSyncNoLineChanges));
            return result;
        } else if (!diff.summaryParsed) {
            result.add(formatString(R.string.PurpleSyncLineCounts,
                    formatPluralString("PurpleSyncLinesAdded", diff.added),
                    formatPluralString("PurpleSyncLinesRemoved", diff.removed)));
            return result;
        } else if (diff.summary.isEmpty()) {
            result.add(getString(R.string.PurpleSyncFormattingOnly));
            return result;
        }
        final int shown = Math.min(diff.summary.size(), SUMMARY_LIMIT);
        final PurpleSyncCore.ChangeKind[] kinds = {
                PurpleSyncCore.ChangeKind.Changed,
                PurpleSyncCore.ChangeKind.Added,
                PurpleSyncCore.ChangeKind.Removed,
        };
        for (PurpleSyncCore.ChangeKind kind : kinds) {
            final List<String> labels = new ArrayList<>();
            for (int i = 0; i != shown; ++i) {
                if (diff.summary.get(i).kind == kind) {
                    labels.add(diff.summary.get(i).label);
                }
            }
            if (labels.isEmpty()) {
                continue;
            }
            final int prefix = (kind == PurpleSyncCore.ChangeKind.Changed)
                    ? R.string.PurpleSyncSummaryChanged
                    : (kind == PurpleSyncCore.ChangeKind.Added)
                    ? R.string.PurpleSyncSummaryAdded
                    : R.string.PurpleSyncSummaryRemoved;
            result.add(formatString(prefix, TextUtils.join(", ", labels)));
        }
        final int more = diff.summary.size() - shown;
        if (more > 0) {
            result.add(formatString(R.string.PurpleSyncSummaryMore, more));
        }
        return result;
    }

    static String diffText(PurpleSyncCore.Diff diff) {
        if (diff.identical) {
            return getString(R.string.PurpleSyncNoLineChanges);
        }
        final List<String> lines = new ArrayList<>();
        if (diff.truncated) {
            lines.add(formatString(R.string.PurpleSyncDiffTruncated, DIFF_EDIT_LIMIT));
        }
        int total = 0;
        for (PurpleSyncCore.DiffHunk hunk : diff.hunks) {
            total += 1 + hunk.lines.size();
        }
        int shown = 0;
        for (PurpleSyncCore.DiffHunk hunk : diff.hunks) {
            if (shown >= DIFF_LINE_LIMIT) {
                break;
            }
            lines.add(String.format(Locale.US, "@@ -%d,%d +%d,%d @@",
                    hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount));
            ++shown;
            for (PurpleSyncCore.DiffLine line : hunk.lines) {
                if (shown >= DIFF_LINE_LIMIT) {
                    break;
                }
                final char prefix = (line.kind == PurpleSyncCore.DiffLineKind.Added)
                        ? '+'
                        : (line.kind == PurpleSyncCore.DiffLineKind.Removed) ? '-' : ' ';
                lines.add(prefix + line.text);
                ++shown;
            }
        }
        if (total > shown) {
            lines.add(formatPluralString("PurpleSyncDiffMore", total - shown));
        }
        return TextUtils.join("\n", lines);
    }
}
