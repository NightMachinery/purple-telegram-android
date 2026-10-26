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
    private static final int ROW_RECOVERY = 7;
    private static final int ROW_AUTO=8;
    private static final int ROW_CHAT=0x7075010f;
    private org.telegram.messenger.purple.PurpleSyncAuto.Control automatic;
    private CharSequence automaticStatus="";

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
        if (userId > 0 && org.telegram.messenger.purple.PurpleAccountOwner.modernOperation(currentAccount)) {
            runner = new PurpleSyncRunner(currentAccount,
                    new PurpleSyncTelegramTransport(currentAccount, userId));
            org.telegram.messenger.purple.PurpleSyncChat.ensureResolved(currentAccount,false,ignored->{});
            status = getString(R.string.PurpleSyncReady);
        } else {
            status = getString(R.string.PurpleAccountWriteHeld);
        }
        automatic=org.telegram.messenger.purple.PurpleSyncAuto.control(currentAccount,value->{automaticStatus=value.text;refresh();});
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if(automatic!=null)automatic.close();
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
        items.add(UItem.asButton(ROW_CHAT,PurpleSyncChatActivity.text("Title")));
        UItem auto=UItem.asCheck(ROW_AUTO,getString(R.string.PurpleSyncAutoTitle));
        auto.checked=automatic!=null&&automatic.enabled();auto.enabled=automatic!=null&&automatic.current();items.add(auto);
        items.add(UItem.asShadow(automaticStatus));
        if (statusView != null) {
            final UItem statusItem = UItem.asCustom(statusView, LayoutHelper.WRAP_CONTENT);
            statusItem.id = ROW_STATUS;
            items.add(statusItem);
        }
        if(shown!=null&&runner()!=null&&!runner.busy()) {
            PurpleSyncRunner.RecoveryAction recovery=shown.recoveryAction;
            if(recovery==PurpleSyncRunner.RecoveryAction.Relink||recovery==PurpleSyncRunner.RecoveryAction.StartAgain||recovery==PurpleSyncRunner.RecoveryAction.ResumeArchive) {
                items.add(UItem.asButton(ROW_RECOVERY,getString(recovery==PurpleSyncRunner.RecoveryAction.Relink?R.string.PurpleSyncRelink
                        :recovery==PurpleSyncRunner.RecoveryAction.ResumeArchive?R.string.PurpleSyncResumeArchive:R.string.PurpleSyncStartAgain)));
                items.add(UItem.asShadow(formatString(recovery==PurpleSyncRunner.RecoveryAction.Relink?R.string.PurpleSyncRelinkAbout
                        :R.string.PurpleSyncStartAgainAbout,accountLabel())));
            }
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
            case ROW_CHAT:
                presentFragment(new PurpleSyncChatActivity(currentAccount));
                break;
            case ROW_AUTO:
                if(automatic!=null&&automatic.current())automatic.setEnabled(!automatic.enabled());
                break;
            case ROW_RECOVERY:
                confirmRecovery();
                break;
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
        action = check.action();
        setStatus(PurpleSyncText.joined(prefix,
                check.setupOnlyJoin?getString(R.string.PurpleSyncEmptyJoinAbout):PurpleSyncText.describe(check.review, checkedAt)));
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
            setStatus(getString(outcome.status==PurpleSyncRunner.CheckStatus.SyncChatHeld?R.string.PurpleSyncIncomplete:sharing
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
                    () -> runner.publish(check, ticket, result -> onPublished(result, prefix)));
            return;
        }
        final String failure = adoptFailureText(check);
        final PurpleSyncCore.Action expected = followupAction(followup);
        final boolean matches = expected != PurpleSyncCore.Action.None
                && check.action() == expected;
        final String prefix = (expected == PurpleSyncCore.Action.None || matches)
                ? failure
                : PurpleSyncText.joined(getString(
                        check.review.status == PurpleSyncCore.ReviewStatus.Ready
                                ? R.string.PurpleSyncChangedNothingSent
                                : R.string.PurpleSyncNothingSent), failure);
        showReview(check, prefix);
        if (matches && inFront()) {
            confirmAction(expected);
        }
    }

    private boolean inFront() {
        return !isPaused() && parentLayout != null
                && parentLayout.getLastFragment() == this;
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
        builder.setTitle(formatString(R.string.PurpleImpactTitle, accountLabel()));
        String impact = "";
        boolean ready = !at.requiresChoice(confirmed);
        for (PurpleSyncCore.Choice choice : at.review.choices) {
            if (at.requiresChoice(confirmed) && choice.key == null) {
                ready = choice.impactReady;
                impact = runner.impactNotes(at, choice);
                break;
            }
        }
        if (!ready) {
            builder.setMessage(getString(R.string.PurpleImpactHeld));
            builder.setNegativeButton(getString(R.string.Close), null);
            showDialog(builder.create());
            return;
        }
        builder.setMessage(at.setupOnlyJoin&&confirmed==PurpleSyncCore.Action.Join
                ?getString(R.string.PurpleSyncEmptyJoinAbout)
                :PurpleSyncText.joined(PurpleSyncText.confirmText(confirmed, at.review), impact));
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
        AlertDialog confirmation=builder.create();showDialog(confirmation);PurpleSyncReviewDialog.focusCancel(confirmation);
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
                showUpdated(device, result.undoAvailable && outcome.post == null);
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

    private void confirmRecovery() {
        Activity activity=getParentActivity();
        PurpleSyncRunner.Check at=shown;
        if(activity==null||at==null||runner()==null||runner.busy()||!stillCurrent(at))return;
        boolean archive=at.recoveryAction!=PurpleSyncRunner.RecoveryAction.Relink;
        int label=archive?(at.recoveryAction==PurpleSyncRunner.RecoveryAction.ResumeArchive?R.string.PurpleSyncResumeArchive:R.string.PurpleSyncStartAgain):R.string.PurpleSyncRelink;
        AlertDialog.Builder builder=new AlertDialog.Builder(activity);
        builder.setTitle(formatString(R.string.PurpleImpactTitle,accountLabel()));
        builder.setMessage(formatString(archive?R.string.PurpleSyncStartAgainAbout:R.string.PurpleSyncRelinkAbout,accountLabel()));
        builder.setPositiveButton(getString(label),(dialog,which)->{
            if(runner()==null)return;
            PurpleSyncRunner.Start started=runner.recover(at,archive,outcome->{
                clearReview();
                if(outcome.ok)reviewAgain(getString(archive?R.string.PurpleSyncArchiveDone:R.string.PurpleSyncRelinkDone),outcome.next);
                else setStatus(getString(R.string.PurpleSyncRecoveryHeld));
            });
            if(started!=PurpleSyncRunner.Start.Started) { refused(started);return; }
            clearReview();setStatus(getString(R.string.PurpleSyncRecoveryChecking));
        });
        builder.setNegativeButton(getString(R.string.Cancel),null);
        AlertDialog dialog=builder.create();showDialog(dialog);PurpleSyncReviewDialog.focusCancel(dialog);
    }

    private void confirmUndo() {
        final Activity activity = getParentActivity();
        if (activity == null || runner() == null) {
            return;
        } else if (runner.busy()) {
            bulletin(getString(R.string.PurpleSyncWait));
            return;
        }
        final PurpleSyncRunner.UndoOffer offer = runner.undoOffer();
        if (offer == null) {
            return;
        }
        runner.preview(offer.historyId, preview -> {
            if (preview != null && preview.impactReady && !isPaused() && runner() != null) confirmUndo(offer, preview);
            else bulletin(getString(R.string.PurpleImpactHeld));
        });
    }

    private void confirmUndo(PurpleSyncRunner.UndoOffer offer, PurpleSyncRunner.Preview preview) {
        Activity activity = getParentActivity();
        if (activity == null || runner() == null) return;
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(formatString(R.string.PurpleImpactTitle,accountLabel()));
        builder.setMessage(formatString(R.string.PurpleSyncUndoQuestion,
                PurpleSyncText.deviceName(offer.device)) + "\n\n" + preview.impactNotes);
        builder.setPositiveButton(getString(R.string.PurpleSyncUndo), (dialog, which) -> {
            final PurpleSyncRunner.UndoOffer current = runner.undoOffer();
            if (runner.busy() || current == null || !current.historyId.equals(offer.historyId)) {
                return;
            }
            final long entryMillis = PurpleSyncText.historyMillis(offer.historyId);
            final PurpleSyncRunner.Start started = runner.undo(current, preview, outcome -> {
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
        AlertDialog confirmation=builder.create();showDialog(confirmation);PurpleSyncReviewDialog.focusCancel(confirmation);
    }

    boolean restoreFromHistory(PurpleSyncHistory.Entry entry, PurpleSyncRunner.Preview preview) {
        if (runner() == null || runner.busy()) {
            return false;
        }
        final PurpleSyncRunner.Start started = runner.restore(preview, outcome -> {
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
