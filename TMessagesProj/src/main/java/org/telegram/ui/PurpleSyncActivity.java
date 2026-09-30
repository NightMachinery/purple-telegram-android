/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */

package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.purple.PurpleSyncApply;
import org.telegram.messenger.purple.PurpleSyncCore;
import org.telegram.messenger.purple.PurpleSyncHistory;
import org.telegram.messenger.purple.PurpleSyncPublisher;
import org.telegram.messenger.purple.PurpleSyncRunner;
import org.telegram.messenger.purple.PurpleSyncSettingsFile;
import org.telegram.messenger.purple.PurpleSyncTelegramTransport;
import org.telegram.messenger.purple.PurpleSyncTransport;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class PurpleSyncActivity extends UniversalFragment {
    private static final int ROW_STATUS = 1;
    private static final int ROW_ACTION = 2;
    private static final int ROW_UNDO = 3;
    private static final int ROW_CHECK = 4;
    private static final int ROW_CANCEL = 5;
    private static final int ROW_HISTORY = 6;

    private enum Followup { None, Publish, PublishChanges, FinishSending, Share }

    private interface Starter {
        PurpleSyncRunner.Start start();
    }

    private PurpleSyncRunner runner;
    private TextView statusView;
    private CharSequence status = "";
    private PurpleSyncRunner.Check shown;
    private PurpleSyncCore.Action action = PurpleSyncCore.Action.None;
    private PurpleSyncRunner.PostTicket share;
    private String sharePrefix;
    private long checkedAt;
    private boolean lost;

    public PurpleSyncActivity(int account) {
        setCurrentAccount(account);
    }

    PurpleSyncRunner runner() {
        return (runner != null && !runner.closed()) ? runner : null;
    }

    @Override
    public boolean onFragmentCreate() {
        final UserConfig config = UserConfig.getInstance(currentAccount);
        final long userId = config.isClientActivated() ? config.getClientUserId() : 0;
        if (userId > 0) {
            runner = new PurpleSyncRunner(currentAccount,
                    new PurpleSyncTelegramTransport(currentAccount, userId));
            status = getString(R.string.PurpleSyncReady);
        } else {
            status = getString(R.string.PurpleSyncSignIn);
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (runner != null) {
            runner.close();
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (runner != null && !lost && !runner.accountAvailable()) {
            stopForLostAccount();
        } else {
            refresh();
        }
    }

    @Override
    public View createView(Context context) {
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        statusView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        statusView.setGravity(PurpleSyncReviewDialog.gravity());
        statusView.setPadding(dp(21), dp(4), dp(21), dp(14));
        statusView.setText(status);
        return super.createView(context);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PurpleSyncTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(accountLabel()));
        if (statusView != null) {
            final UItem statusItem = UItem.asCustom(statusView, LayoutHelper.WRAP_CONTENT);
            statusItem.id = ROW_STATUS;
            items.add(statusItem);
        }
        if (actionVisible()) {
            final UItem item = UItem.asButton(ROW_ACTION, PurpleSyncText.actionText(action));
            item.accent = true;
            items.add(item);
        }
        if (runner != null && runner.undoOffer() != null) {
            items.add(UItem.asButton(ROW_UNDO, getString(R.string.PurpleSyncUndoLast)));
        }
        final UItem check = UItem.asButton(ROW_CHECK, getString(R.string.PurpleSyncCheck));
        check.enabled = canCheck();
        items.add(check);
        if (runner != null && runner.checking()) {
            items.add(UItem.asButton(ROW_CANCEL, getString(R.string.PurpleSyncCancelCheck)));
        }
        items.add(UItem.asShadow(getString(R.string.PurpleSyncIntro)));
        if (runner != null) {
            items.add(UItem.asButton(ROW_HISTORY, getString(R.string.PurpleSyncHistory)));
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ROW_ACTION:
                runCurrentAction();
                break;
            case ROW_UNDO:
                confirmUndo();
                break;
            case ROW_CHECK:
                if (canCheck()) {
                    startCheck(Followup.None, "");
                }
                break;
            case ROW_CANCEL:
                cancelCheck();
                break;
            case ROW_HISTORY:
                if (runner() != null) {
                    presentFragment(new PurpleSyncHistoryActivity(this));
                }
                break;
            default:
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private String accountLabel() {
        final TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (user == null) {
            return getString(R.string.PurpleSyncTitle);
        }
        final String name = UserObject.getUserName(user);
        final String username = UserObject.getPublicUsername(user);
        return TextUtils.isEmpty(username)
                ? name
                : formatString(R.string.PurpleSyncAccountWithUsername, name, username);
    }

    private boolean available() {
        return runner != null && !runner.closed() && runner.accountAvailable();
    }

    private boolean canCheck() {
        return available() && !runner.busy();
    }

    private boolean actionVisible() {
        return available() && !runner.busy() && shown != null
                && shown == runner.current() && action != PurpleSyncCore.Action.None;
    }

    private boolean stillCurrent(PurpleSyncRunner.Check check) {
        return available() && !runner.busy() && check != null && check == shown
                && check == runner.current();
    }

    private void refresh() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void setStatus(CharSequence text) {
        status = (text == null) ? "" : text;
        if (statusView != null) {
            statusView.setText(status);
        }
        refresh();
    }

    private void clearReview() {
        shown = null;
        action = PurpleSyncCore.Action.None;
    }

    private void showReview(PurpleSyncRunner.Check check, String prefix) {
        shown = check;
        action = check.review.action;
        setStatus(PurpleSyncText.joined(prefix,
                PurpleSyncText.describe(check.review, checkedAt)));
    }

    private void reviewAgain(String prefix, PurpleSyncRunner.Check next) {
        if (next == null || !available() || next != runner.current()) {
            clearReview();
            setStatus(PurpleSyncText.joined(prefix, getString(R.string.PurpleSyncCheckNext)));
            return;
        }
        showReview(next, PurpleSyncText.joined(prefix, adoptFailureText(next)));
    }

    private static String adoptFailureText(PurpleSyncRunner.Check check) {
        return (check.adoptFailure != null)
                ? PurpleSyncText.applyFailureText(check.adoptFailure, null) : "";
    }

    private void stopForLostAccount() {
        lost = true;
        if (runner != null && runner.checking()) {
            runner.cancel();
        }
        clearReview();
        share = null;
        sharePrefix = null;
        setStatus(getString(R.string.PurpleSyncLostAccount));
    }

    private void refused(PurpleSyncRunner.Start start) {
        switch (start) {
            case Busy:
                bulletin(getString(R.string.PurpleSyncWait));
                break;
            case Stale:
                clearReview();
                setStatus(getString(R.string.PurpleSyncResultChanged));
                break;
            case AccountUnavailable:
                stopForLostAccount();
                break;
            default:
                refresh();
                break;
        }
    }

    private void bulletin(CharSequence text) {
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show();
        }
    }

    private static PurpleSyncCore.Action followupAction(Followup followup) {
        switch (followup) {
            case Publish:
                return PurpleSyncCore.Action.Publish;
            case PublishChanges:
                return PurpleSyncCore.Action.PublishChanges;
            case FinishSending:
                return PurpleSyncCore.Action.FinishSending;
            default:
                return PurpleSyncCore.Action.None;
        }
    }

    private void startCheck(Followup followup, String prefix) {
        if (runner == null) {
            return;
        } else if (!available()) {
            stopForLostAccount();
            return;
        }
        clearReview();
        final PurpleSyncRunner.Start started = runner.check(
                (phase, done, total) -> {
                    if (phase == PurpleSyncTransport.Phase.Scanning && statusView != null) {
                        status = progressText(followup, prefix, done);
                        statusView.setText(status);
                    }
                },
                outcome -> onChecked(outcome, followup));
        if (started != PurpleSyncRunner.Start.Started) {
            if (followup == Followup.Share) {
                share = null;
                sharePrefix = null;
            }
            refused(started);
            return;
        }
        lost = false;
        setStatus(progressText(followup, prefix, 0));
    }

    private static String progressText(Followup followup, String prefix, int count) {
        return PurpleSyncText.joined(prefix, formatString(followup != Followup.None
                ? R.string.PurpleSyncCheckingAgain : R.string.PurpleSyncChecking, count));
    }

    private void cancelCheck() {
        if (runner == null || !runner.cancel()) {
            return;
        }
        final boolean sharing = share != null;
        share = null;
        sharePrefix = null;
        clearReview();
        setStatus(getString(sharing
                ? R.string.PurpleSyncShareCancelled : R.string.PurpleSyncCheckCancelled));
    }

    private void onChecked(PurpleSyncRunner.CheckOutcome outcome, Followup followup) {
        if (outcome.status == PurpleSyncRunner.CheckStatus.AccountUnavailable) {
            stopForLostAccount();
            return;
        } else if (outcome.status != PurpleSyncRunner.CheckStatus.Finished
                || outcome.check == null) {
            final boolean sharing = share != null;
            share = null;
            sharePrefix = null;
            clearReview();
            setStatus(getString(sharing
                    ? R.string.PurpleSyncShareCancelled : R.string.PurpleSyncCheckCancelled));
            return;
        }
        checkedAt = System.currentTimeMillis();
        final PurpleSyncRunner.Check check = outcome.check;
        if (followup == Followup.Share) {
            final PurpleSyncRunner.PostTicket ticket = share;
            final String prefix = sharePrefix;
            share = null;
            sharePrefix = null;
            clearReview();
            if (ticket == null) {
                setStatus(getString(R.string.PurpleSyncNothingToShare));
                return;
            }
            startPublish(prefix, getString(R.string.PurpleSyncSharing),
                    () -> runner.publish(check, ticket.request, result -> onPublished(result, prefix)));
            return;
        }
        final String failure = adoptFailureText(check);
        final PurpleSyncCore.Action expected = followupAction(followup);
        final boolean matches = expected != PurpleSyncCore.Action.None
                && check.review.action == expected;
        final String prefix = (expected == PurpleSyncCore.Action.None || matches)
                ? failure
                : PurpleSyncText.joined(getString(
                        check.review.status == PurpleSyncCore.ReviewStatus.Ready
                                ? R.string.PurpleSyncChangedNothingSent
                                : R.string.PurpleSyncNothingSent), failure);
        showReview(check, prefix);
        if (matches) {
            confirmAction(expected);
        }
    }

    private void startPublish(String prefix, String progress, Starter starter) {
        if (!available()) {
            stopForLostAccount();
            return;
        }
        final PurpleSyncRunner.Start started = starter.start();
        if (started != PurpleSyncRunner.Start.Started) {
            refused(started);
            return;
        }
        clearReview();
        setStatus(PurpleSyncText.joined(prefix, progress));
    }

    private void onPublished(PurpleSyncPublisher.Result result, String prefix) {
        clearReview();
        setStatus(PurpleSyncText.joined(prefix, PurpleSyncText.publishText(result)));
    }

    private void runCurrentAction() {
        if (runner == null || shown == null || runner.busy()) {
            return;
        } else if (!available()) {
            stopForLostAccount();
            return;
        }
        switch (action) {
            case Publish:
                startCheck(Followup.Publish, "");
                break;
            case PublishChanges:
                startCheck(Followup.PublishChanges, "");
                break;
            case FinishSending:
                startCheck(Followup.FinishSending, "");
                break;
            case Join:
                confirmAction(PurpleSyncCore.Action.Join);
                break;
            case ReviewUpdate:
            case Choose:
                openReview();
                break;
            default:
                break;
        }
    }

    private void confirmAction(PurpleSyncCore.Action confirmed) {
        final Activity activity = getParentActivity();
        final PurpleSyncRunner.Check at = shown;
        if (activity == null || at == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(PurpleSyncText.actionText(confirmed));
        builder.setMessage(PurpleSyncText.confirmText(confirmed, at.review));
        builder.setPositiveButton(PurpleSyncText.actionText(confirmed), (dialog, which) -> {
            if (!available()) {
                stopForLostAccount();
            } else if (!stillCurrent(at)) {
                clearReview();
                setStatus(getString(R.string.PurpleSyncResultChanged));
            } else {
                runAction(confirmed, at);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void runAction(PurpleSyncCore.Action confirmed, PurpleSyncRunner.Check at) {
        if (confirmed == PurpleSyncCore.Action.FinishSending) {
            startPublish("", getString(R.string.PurpleSyncFinishing),
                    () -> runner.publish(at, PurpleSyncCore.PostRequest.finishSending(),
                            result -> onPublished(result, "")));
            return;
        }
        final PurpleSyncRunner.Start started = runner.apply(at, null, outcome -> {
            final PurpleSyncApply.ApplyResult result = outcome.result;
            if (result.status != PurpleSyncCore.ApplyStatus.Applied) {
                clearReview();
                setStatus(PurpleSyncText.applyFailureText(result, outcome.failure));
            } else if (confirmed == PurpleSyncCore.Action.Join) {
                reviewAgain(getString(R.string.PurpleSyncJoinedNothingSent), outcome.next);
            } else if (outcome.post == null) {
                reviewAgain(getString(R.string.PurpleSyncNothingNeedsSending), outcome.next);
            } else {
                final PurpleSyncRunner.PostTicket ticket = outcome.post;
                startPublish("", getString(R.string.PurpleSyncPublishing),
                        () -> runner.publish(ticket, published -> onPublished(published, "")));
            }
        });
        if (started != PurpleSyncRunner.Start.Started) {
            refused(started);
            return;
        }
        clearReview();
        refresh();
    }

    private void openReview() {
        final PurpleSyncRunner.Check at = shown;
        if (!stillCurrent(at)) {
            return;
        }
        PurpleSyncReviewDialog.show(this, runner, at, key -> applyChoice(at, key));
    }

    private void applyChoice(PurpleSyncRunner.Check at, String key) {
        if (!available()) {
            stopForLostAccount();
            return;
        } else if (!stillCurrent(at)) {
            clearReview();
            setStatus(getString(R.string.PurpleSyncResultChangedOrRunning));
            return;
        }
        final PurpleSyncCore.Review review = at.review;
        final boolean choosing = review.verdict == PurpleSyncCore.Verdict.Choose
                || review.verdict == PurpleSyncCore.Verdict.Conflict;
        final String device = chosenDevice(review, key);
        final boolean hadNoFile = review.localStatus == PurpleSyncSettingsFile.Status.Absent;
        final PurpleSyncRunner.Start started = runner.apply(at, key, outcome -> {
            final PurpleSyncApply.ApplyResult result = outcome.result;
            if (result.status != PurpleSyncCore.ApplyStatus.Applied) {
                clearReview();
                setStatus(PurpleSyncText.applyFailureText(result, outcome.failure));
                return;
            }
            String prefix = result.joined ? getString(R.string.PurpleSyncJoined) : "";
            if (result.wroteFile) {
                showUpdated(device, result.undoAvailable);
                prefix = PurpleSyncText.joined(prefix, formatString(result.undoAvailable
                        ? R.string.PurpleSyncUpdatedUndo
                        : hadNoFile
                        ? R.string.PurpleSyncUpdatedNoFile
                        : R.string.PurpleSyncUpdatedNotText, device));
            }
            if (outcome.post != null && choosing) {
                share = outcome.post;
                sharePrefix = prefix;
                startCheck(Followup.Share, prefix);
            } else if (outcome.post != null) {
                final PurpleSyncRunner.PostTicket ticket = outcome.post;
                final String posted = prefix;
                startPublish(prefix, getString(R.string.PurpleSyncPublishing),
                        () -> runner.publish(ticket, published -> onPublished(published, posted)));
            } else {
                reviewAgain(prefix, outcome.next);
            }
        });
        if (started != PurpleSyncRunner.Start.Started) {
            refused(started);
            return;
        }
        clearReview();
        refresh();
    }

    private static String chosenDevice(PurpleSyncCore.Review review, String key) {
        if (key != null) {
            for (PurpleSyncCore.Choice choice : review.choices) {
                if (key.equals(choice.key)) {
                    return PurpleSyncText.deviceName(review.heads.get(choice.head).name);
                }
            }
        }
        return getString(R.string.PurpleSyncAnotherDevice);
    }

    private void showUpdated(String device, boolean undoAvailable) {
        if (!BulletinFactory.canShowBulletin(this)) {
            return;
        }
        final String text = formatString(R.string.PurpleSyncUpdatedFrom, device);
        if (undoAvailable) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text,
                    getString(R.string.PurpleSyncUndo), Bulletin.DURATION_PROLONG,
                    this::confirmUndo).show();
        } else {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show();
        }
    }

    private void confirmUndo() {
        final Activity activity = getParentActivity();
        final PurpleSyncRunner.UndoOffer offer = (runner != null) ? runner.undoOffer() : null;
        if (activity == null || offer == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PurpleSyncUndoLast));
        builder.setMessage(formatString(R.string.PurpleSyncUndoQuestion,
                PurpleSyncText.deviceName(offer.device)));
        builder.setPositiveButton(getString(R.string.PurpleSyncUndo), (dialog, which) -> {
            final PurpleSyncRunner.UndoOffer current = runner.undoOffer();
            if (runner.busy() || current == null || !current.historyId.equals(offer.historyId)) {
                return;
            }
            final long entryMillis = PurpleSyncText.historyMillis(offer.historyId);
            final PurpleSyncRunner.Start started = runner.undo(current, outcome -> {
                if (outcome.result.status == PurpleSyncCore.RestoreStatus.Restored) {
                    bulletin(getString(R.string.PurpleSyncUndone));
                    reviewAgain(getString(R.string.PurpleSyncUndoneStatus), outcome.next);
                } else {
                    keepReview(outcome.next);
                    setStatus(PurpleSyncText.restoreText(outcome.result, entryMillis));
                }
            });
            if (started != PurpleSyncRunner.Start.Started) {
                refused(started);
                return;
            }
            clearReview();
            refresh();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    boolean restoreFromHistory(PurpleSyncHistory.Entry entry) {
        if (runner() == null || runner.busy()) {
            return false;
        }
        final PurpleSyncRunner.Start started = runner.restore(entry.id, outcome -> {
            final String text = PurpleSyncText.restoreText(outcome.result, entry.createdMs);
            if (outcome.result.status == PurpleSyncCore.RestoreStatus.Restored) {
                bulletin(getString(R.string.PurpleSyncRestoredBulletin));
                reviewAgain(text, outcome.next);
            } else {
                keepReview(outcome.next);
                setStatus(text);
            }
        });
        if (started != PurpleSyncRunner.Start.Started) {
            return false;
        }
        clearReview();
        refresh();
        return true;
    }

    private void keepReview(PurpleSyncRunner.Check next) {
        if (next != null && available() && next == runner.current()) {
            shown = next;
            action = next.review.action;
        } else {
            clearReview();
        }
    }
}
