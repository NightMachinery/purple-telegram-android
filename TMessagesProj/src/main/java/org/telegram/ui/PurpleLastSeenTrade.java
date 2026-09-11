/*
 * This is the source code of Purple Telegram for Android.
 *
 * "Show mine to see theirs": the last-seen trade. See docs/purple/work_mode.md,
 * "Last seen: reasons and the trade".
 *
 * Telegram's rule is symmetric - hide your last seen and you stop being shown
 * other people's. The server says so, in the `by_me' flag on a coarse status,
 * and the fork says so too (PurpleLastSeen.decorate). This is the one thing
 * that can be DONE about it: show them yours for a few seconds, ask for theirs,
 * put the rules back.
 *
 * Three things this deliberately is not. It is not a toggle - every trade is a
 * tap, and the cooldown in the core is what stops a tap becoming a
 * subscription. It is not silent - the sheet says what the seconds cost before
 * anything is sent. And the restore is not conditional: it runs after a
 * success, after a timeout and after an error, because a fork that could leave
 * somebody's privacy rules open on a failed request would be worse than one
 * with no trade in it.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.purple.PurpleCore;
import org.telegram.messenger.purple.PurpleLastSeen;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Locale;

public final class PurpleLastSeenTrade {

    private static final String PREFS = "purple_last_seen";

    /** The "don't ask again" answer. A device decision, so not in settings.toml. */
    private static final String KEY_NO_ASK = "purple_trade_no_ask";

    /** How often theirs is asked for again while the hold runs, in millis. */
    private static final long POLL_MS = 1500L;

    /** How many times the restore is retried before it is reported as failed. */
    private static final int RESTORE_TRIES = 3;

    /**
     * The trade in flight, or zero.
     *
     * One at a time and no queue: two trades running at once would each restore
     * the rules the other had just changed, and the second restore would put
     * back a state that was never the user's.
     */
    private static volatile long running;

    private PurpleLastSeenTrade() {
    }

    /**
     * Offers, or refuses, a trade with this user.
     *
     * The refusals that are said out loud are the ones that will not turn into
     * a yes while the box sits there: the offer switched off, a last seen that
     * is not coarse because of our own rules, a trade already running. A
     * cooldown is not one of those - it is a wait - so where there is a
     * remembered read to show it beside, the sheet opens and counts it down
     * rather than firing a toast that vanishes without saying how much is left.
     */
    public static void show(BaseFragment fragment, int currentAccount, long userId) {
        if (fragment == null || userId == 0) {
            return;
        }
        final TLRPC.User user =
                MessagesController.getInstance(currentAccount).getUser(userId);
        if (user == null || !PurpleLastSeen.tradeOffered()) {
            return;
        }
        final String name = UserObject.getFirstName(user);
        if (PurpleLastSeen.reasonFor(user) != PurpleCore.REASON_BY_ME) {
            // Their own setting, an exact time already, or "a long time ago",
            // which is not a coarsening of anything: in none of the three is
            // there anything of ours in the way to move.
            error(fragment, formatString(R.string.PurpleTradeNotNeeded, name));
            return;
        }
        if (running != 0) {
            return;
        }
        final PurpleCore.Trade read = PurpleLastSeen.remembered(userId);
        // A trade whose hold ran out with nothing exact arriving left no line
        // on screen, so there is nothing for a countdown to stand beside and
        // the cooldown is a refusal again.
        final boolean refresh = read != null && read.wasOnlineUnix > 0;
        final int cooldownLeft = PurpleLastSeen.cooldownLeft(userId);
        if (cooldownLeft > 0 && !refresh) {
            error(fragment, formatString(R.string.PurpleTradeCooldown, name));
            return;
        }
        if (cooldownLeft <= 0 && noAsk()) {
            // The sheet was the consent, and it was given once for good. The tap
            // is still per trade, which is what keeps this from being a toggle.
            start(fragment, currentAccount, user);
            return;
        }
        confirm(fragment, currentAccount, user, name, refresh ? read : null, cooldownLeft);
    }

    /**
     * The sheet: what the seconds cost, what the last trade read, how long the
     * wait has left, and the two ways out of it.
     *
     * The countdown is recomputed from the clock on every tick rather than
     * decremented, so a box left open across a suspend does not go on counting
     * a wait that wall-clock time has already spent. It runs while the box is
     * up and is cancelled when it goes, because a ticker outliving its dialog
     * would hold the fragment and update a view nobody is looking at.
     */
    private static void confirm(BaseFragment fragment, int currentAccount,
            TLRPC.User user, String name, PurpleCore.Trade read, int cooldownLeft) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(getString(R.string.PurpleTradeTitle));
        final CharSequence explanation = formatString(
                R.string.PurpleTradeText, name, PurpleLastSeen.holdSeconds());
        builder.setMessage((read == null)
                ? explanation
                : (explanation + "\n\n" + formatString(R.string.PurpleTradeRemembered,
                        name,
                        LocaleController.formatDateOnline(read.wasOnlineUnix, null),
                        PurpleLastSeen.ago(read.readAtUnix))));

        final LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        // Only where there is a wait to report. A first trade's sheet is
        // exactly the sheet it always was.
        final TextView waiting;
        if (read != null) {
            waiting = new TextView(activity);
            waiting.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            waiting.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
            waiting.setText(waitText(cooldownLeft));
            wrapper.addView(waiting, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 24, 0, 24, 4));
        } else {
            waiting = null;
        }

        final boolean[] noAsk = new boolean[] { false };
        // Left out once it has been answered for good: the sheet is open here
        // to say how long the wait is, not to ask a question that is settled.
        if (!noAsk()) {
            final CheckBoxCell cell = new CheckBoxCell(activity, 1);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setText(getString(R.string.PurpleTradeDontAsk), "", false, false);
            cell.setOnClickListener(v -> {
                noAsk[0] = !noAsk[0];
                ((CheckBoxCell) v).setChecked(noAsk[0], true);
            });
            wrapper.addView(cell, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 48, Gravity.TOP | Gravity.LEFT, 8, 0, 8, 0));
        }
        builder.setView(wrapper);

        // "Refresh now" rather than "Share once" for somebody already traded
        // with: this is a second look, and the button should say which of the
        // two it is before it is pressed.
        builder.setPositiveButton(getString((read == null)
                ? R.string.PurpleTradeShare
                : R.string.PurpleTradeRefresh), (box, which) -> {
            // Checked again rather than trusted to the disabled button: the
            // button is the only thing holding the press back, and a wait this
            // fork got wrong would be a trade the cooldown was supposed to stop.
            if (PurpleLastSeen.cooldownLeft(user.id) > 0) {
                return;
            }
            // Written only on the way through the button. A "don't ask again"
            // ticked and then cancelled is not an answer to the question that
            // was asked.
            if (noAsk[0]) {
                setNoAsk(true);
            }
            start(fragment, currentAccount, user);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);

        final AlertDialog dialog = builder.create();
        if (fragment.showDialog(dialog, d -> stopTicking()) == null) {
            return;
        }
        final View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        setPressable(button, cooldownLeft <= 0);
        if (waiting == null || cooldownLeft <= 0) {
            return;
        }
        tick(user.id, waiting, button);
    }

    /** The ticker, so a dialog that goes takes it with it. */
    private static Runnable ticking;

    /**
     * Counts the wait down a second at a time, from the clock.
     *
     * Stops at zero rather than going on: the button is pressable from then on,
     * and a runnable re-posting itself every second for as long as somebody
     * leaves the box open is a thing to switch off once it has nothing left to
     * say.
     */
    private static void tick(long userId, TextView waiting, View button) {
        stopTicking();
        final Runnable step = new Runnable() {
            @Override
            public void run() {
                if (ticking != this) {
                    return;
                }
                final int left = PurpleLastSeen.cooldownLeft(userId);
                waiting.setText(waitText(left));
                if (left <= 0) {
                    setPressable(button, true);
                    ticking = null;
                    return;
                }
                AndroidUtilities.runOnUIThread(this, 1000L);
            }
        };
        ticking = step;
        AndroidUtilities.runOnUIThread(step, 1000L);
    }

    private static void stopTicking() {
        if (ticking != null) {
            AndroidUtilities.cancelRunOnUIThread(ticking);
            ticking = null;
        }
    }

    /** "You can refresh in 3:12", or that the wait is over. */
    private static CharSequence waitText(int secondsLeft) {
        if (secondsLeft <= 0) {
            return getString(R.string.PurpleTradeCooldownNow);
        }
        return formatString(R.string.PurpleTradeCooldownLeft, String.format(
                Locale.US, "%d:%02d", secondsLeft / 60, secondsLeft % 60));
    }

    /**
     * Greys the button out, or hands it back.
     *
     * Disabled rather than hidden: the wait is what the box is open to say, and
     * a button that appears out of nowhere when it ends would leave nothing for
     * the countdown to have been counting towards.
     */
    private static void setPressable(View button, boolean pressable) {
        if (button == null) {
            return;
        }
        button.setEnabled(pressable);
        button.setAlpha(pressable ? 1f : 0.5f);
    }

    /**
     * Reads the rules that are in force, so the restore can put back exactly
     * those.
     *
     * From the server rather than from ContactsController's cache, and that is
     * the point: the cache is whatever was last loaded, and restoring to a
     * stale copy of somebody's privacy settings is the one mistake here that
     * cannot be undone by trying again.
     */
    private static void start(BaseFragment fragment, int currentAccount, TLRPC.User user) {
        final long userId = user.id;
        running = userId;
        FileLog.d("Purple: trade starting for " + userId);
        info(fragment, getString(R.string.PurpleTradeWorking));

        final TL_account.getPrivacy req = new TL_account.getPrivacy();
        req.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        ConnectionsManager.getInstance(currentAccount).sendRequest(req,
                (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (!(response instanceof TL_account.privacyRules)) {
                        FileLog.d("Purple: trade could not read the rules");
                        failed(fragment, error);
                        return;
                    }
                    final TL_account.privacyRules rules = (TL_account.privacyRules) response;
                    MessagesController.getInstance(currentAccount).putUsers(rules.users, false);
                    MessagesController.getInstance(currentAccount).putChats(rules.chats, false);
                    open(fragment, currentAccount, user, rules.rules);
                }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /** Puts the user through, keeping the rules as they were for the restore. */
    private static void open(BaseFragment fragment, int currentAccount, TLRPC.User user,
            ArrayList<TLRPC.PrivacyRule> current) {
        if (inputOf(currentAccount, user.id) == null
                || !addressable(currentAccount, current)) {
            // Refused before anything is opened rather than after. setPrivacy
            // replaces the whole list, so a rule naming somebody this client
            // cannot address would come back as inputUserEmpty and the restore
            // would quietly drop them from the user's own exceptions - a
            // privacy setting changed by a feature that promised not to.
            FileLog.e("Purple: trade refused, a rule names an unaddressable user");
            running = 0;
            error(fragment, getString(R.string.PurpleTradeUnresolved));
            return;
        }
        final ArrayList<TLRPC.InputPrivacyRule> restore = asInput(currentAccount, current, 0);
        final ArrayList<TLRPC.InputPrivacyRule> opened =
                asInput(currentAccount, current, user.id);
        FileLog.d("Purple: trade opening the rules for " + user.id);

        final TL_account.setPrivacy req = new TL_account.setPrivacy();
        req.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        req.rules = opened;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req,
                (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (!(response instanceof TL_account.privacyRules)) {
                        // Nothing was changed, so there is nothing to put back.
                        FileLog.d("Purple: trade refused at the open");
                        failed(fragment, error);
                        return;
                    }
                    FileLog.d("Purple: trade open, waiting "
                            + PurpleLastSeen.holdSeconds() + "s");
                    poll(fragment, currentAccount, user, restore,
                            System.currentTimeMillis()
                                    + PurpleLastSeen.holdSeconds() * 1000L);
                }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /**
     * Asks for their status until an exact one arrives or the hold runs out.
     *
     * users.getUsers rather than an updateInterfaces observer, because the
     * status this needs is one the server has to be asked for: opening the rules
     * does not make it push a new one, and a listener would sit through the
     * whole hold hearing nothing. putUsers on the way past is what refreshes
     * every header already showing this user.
     */
    private static void poll(BaseFragment fragment, int currentAccount, TLRPC.User user,
            ArrayList<TLRPC.InputPrivacyRule> restore, long deadlineMs) {
        final TLRPC.InputUser inputUser =
                MessagesController.getInstance(currentAccount).getInputUser(user);
        if (inputUser == null) {
            finish(fragment, currentAccount, user, restore, 0);
            return;
        }
        final TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        req.id.add(inputUser);
        ConnectionsManager.getInstance(currentAccount).sendRequest(req,
                (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    long wasOnline = 0;
                    if (response instanceof Vector) {
                        final ArrayList<TLRPC.User> users = new ArrayList<>();
                        for (Object object : ((Vector) response).objects) {
                            if (object instanceof TLRPC.User) {
                                users.add((TLRPC.User) object);
                                if (((TLRPC.User) object).id == user.id) {
                                    wasOnline = exactOf(((TLRPC.User) object).status);
                                }
                            }
                        }
                        MessagesController.getInstance(currentAccount).putUsers(users, false);
                    }
                    if (wasOnline > 0) {
                        FileLog.d("Purple: trade read " + wasOnline + " for " + user.id);
                        finish(fragment, currentAccount, user, restore, wasOnline);
                    } else if (System.currentTimeMillis() + POLL_MS < deadlineMs) {
                        AndroidUtilities.runOnUIThread(() -> poll(
                                fragment, currentAccount, user, restore, deadlineMs), POLL_MS);
                    } else {
                        FileLog.d("Purple: trade hold ran out for " + user.id);
                        finish(fragment, currentAccount, user, restore, 0);
                    }
                }));
    }

    /**
     * Writes the trade down and puts the rules back.
     *
     * A read of zero is written too: the hold ran out with nothing exact, but
     * the seconds of exposure happened, and the cooldown counts them.
     */
    private static void finish(BaseFragment fragment, int currentAccount, TLRPC.User user,
            ArrayList<TLRPC.InputPrivacyRule> restore, long wasOnline) {
        PurpleLastSeen.noteTrade(user.id, wasOnline);
        NotificationCenter.getInstance(currentAccount).postNotificationName(
                NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS);
        // Said by the restore rather than here, so the sentence "your privacy
        // settings are back as they were" is only ever printed by the thing
        // that put them back.
        restore(fragment, currentAccount, restore, RESTORE_TRIES, (wasOnline > 0)
                ? formatString(R.string.PurpleTradeDone,
                        LocaleController.formatDateOnline(wasOnline, null))
                : getString(R.string.PurpleTradeTimedOut));
    }

    /**
     * Puts the rules back, and keeps trying.
     *
     * Retried where nothing else here is, because this is the step whose failure
     * leaves somebody more exposed than they asked to be. The cached copy is
     * re-fetched afterwards so the Privacy screen agrees with the server rather
     * than with what it last saw before the trade.
     */
    private static void restore(BaseFragment fragment, int currentAccount,
            ArrayList<TLRPC.InputPrivacyRule> rules, int triesLeft, CharSequence done) {
        final TL_account.setPrivacy req = new TL_account.setPrivacy();
        req.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        req.rules = rules;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req,
                (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (response instanceof TL_account.privacyRules) {
                        final TL_account.privacyRules back = (TL_account.privacyRules) response;
                        ContactsController.getInstance(currentAccount).setPrivacyRules(
                                back.rules, ContactsController.PRIVACY_RULES_TYPE_LASTSEEN);
                        ContactsController.getInstance(currentAccount).loadPrivacySettings(true);
                        FileLog.d("Purple: trade rules restored");
                        running = 0;
                        info(fragment, done);
                        return;
                    }
                    if (triesLeft > 1) {
                        FileLog.d("Purple: trade restore failed, retrying");
                        AndroidUtilities.runOnUIThread(() -> restore(
                                fragment, currentAccount, rules, triesLeft - 1, done), 2000L);
                        return;
                    }
                    FileLog.e("Purple: trade could not restore the rules");
                    running = 0;
                    error(fragment, formatString(R.string.PurpleTradeRestoreFailed,
                            (error == null || error.text == null) ? "?" : error.text));
                }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /**
     * The server's rules as the ones to send back, optionally with a user let
     * through.
     *
     * Every rule is carried over, including the ones this has no interest in -
     * bots, close friends, chat participants - because setPrivacy replaces the
     * whole list and a rule left out of the send is a rule deleted. `letThrough'
     * of zero is the plain conversion, which is what the restore uses.
     *
     * Adding the user is two edits and both are needed: allow-users gains them,
     * and disallow-users loses them, because a disallow outranks an allow and a
     * user only added to the first would still be shut out.
     */
    private static ArrayList<TLRPC.InputPrivacyRule> asInput(int currentAccount,
            ArrayList<TLRPC.PrivacyRule> rules, long letThrough) {
        final ArrayList<TLRPC.InputPrivacyRule> result = new ArrayList<>();
        boolean added = false;
        if (rules != null) {
            for (TLRPC.PrivacyRule rule : rules) {
                if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                    final TLRPC.TL_inputPrivacyValueAllowUsers allow =
                            new TLRPC.TL_inputPrivacyValueAllowUsers();
                    for (Long id : ((TLRPC.TL_privacyValueAllowUsers) rule).users) {
                        final TLRPC.InputUser input = inputOf(currentAccount, id);
                        if (input != null) {
                            allow.users.add(input);
                        }
                    }
                    if (letThrough != 0 && !has(((TLRPC.TL_privacyValueAllowUsers) rule).users,
                            letThrough)) {
                        final TLRPC.InputUser input = inputOf(currentAccount, letThrough);
                        if (input != null) {
                            allow.users.add(input);
                        }
                    }
                    added = true;
                    result.add(allow);
                } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                    final TLRPC.TL_inputPrivacyValueDisallowUsers disallow =
                            new TLRPC.TL_inputPrivacyValueDisallowUsers();
                    for (Long id : ((TLRPC.TL_privacyValueDisallowUsers) rule).users) {
                        if (letThrough != 0 && id == letThrough) {
                            continue;
                        }
                        final TLRPC.InputUser input = inputOf(currentAccount, id);
                        if (input != null) {
                            disallow.users.add(input);
                        }
                    }
                    result.add(disallow);
                } else if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) {
                    final TLRPC.TL_inputPrivacyValueAllowChatParticipants chats =
                            new TLRPC.TL_inputPrivacyValueAllowChatParticipants();
                    chats.chats.addAll(
                            ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats);
                    result.add(chats);
                } else if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) {
                    final TLRPC.TL_inputPrivacyValueDisallowChatParticipants chats =
                            new TLRPC.TL_inputPrivacyValueDisallowChatParticipants();
                    chats.chats.addAll(
                            ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats);
                    result.add(chats);
                } else if (rule instanceof TLRPC.TL_privacyValueAllowAll) {
                    result.add(new TLRPC.TL_inputPrivacyValueAllowAll());
                } else if (rule instanceof TLRPC.TL_privacyValueDisallowAll) {
                    result.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
                } else if (rule instanceof TLRPC.TL_privacyValueAllowContacts) {
                    result.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
                } else if (rule instanceof TLRPC.TL_privacyValueDisallowContacts) {
                    result.add(new TLRPC.TL_inputPrivacyValueDisallowContacts());
                } else if (rule instanceof TLRPC.TL_privacyValueAllowCloseFriends) {
                    result.add(new TLRPC.TL_inputPrivacyValueAllowCloseFriends());
                } else if (rule instanceof TLRPC.TL_privacyValueAllowPremium) {
                    result.add(new TLRPC.TL_inputPrivacyValueAllowPremium());
                } else if (rule instanceof TLRPC.TL_privacyValueAllowBots) {
                    result.add(new TLRPC.TL_inputPrivacyValueAllowBots());
                } else if (rule instanceof TLRPC.TL_privacyValueDisallowBots) {
                    result.add(new TLRPC.TL_inputPrivacyValueDisallowBots());
                }
            }
        }
        if (letThrough != 0 && !added) {
            // No allow-users rule to extend, so the trade brings one with it.
            final TLRPC.InputUser input = inputOf(currentAccount, letThrough);
            if (input != null) {
                final TLRPC.TL_inputPrivacyValueAllowUsers allow =
                        new TLRPC.TL_inputPrivacyValueAllowUsers();
                allow.users.add(input);
                result.add(allow);
            }
        }
        return result;
    }

    /**
     * The user as the server wants them named, or null when this client cannot
     * name them.
     *
     * getInputUser answers with inputUserEmpty for somebody it has never heard
     * of rather than with null, which is exactly the value that would sail
     * through a null check and come out of the server as a deleted exception.
     */
    private static TLRPC.InputUser inputOf(int currentAccount, long userId) {
        final TLRPC.InputUser input =
                MessagesController.getInstance(currentAccount).getInputUser(userId);
        return (input instanceof TLRPC.TL_inputUserEmpty) ? null : input;
    }

    /**
     * Whether every user named in these rules can be named back to the server.
     *
     * The getPrivacy response carries the users its rules mention and they are
     * put into the controller before this runs, so a false here means something
     * really is missing rather than merely not loaded yet.
     */
    private static boolean addressable(int currentAccount,
            ArrayList<TLRPC.PrivacyRule> rules) {
        if (rules == null) {
            return true;
        }
        for (TLRPC.PrivacyRule rule : rules) {
            final ArrayList<Long> ids;
            if (rule instanceof TLRPC.TL_privacyValueAllowUsers) {
                ids = ((TLRPC.TL_privacyValueAllowUsers) rule).users;
            } else if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) {
                ids = ((TLRPC.TL_privacyValueDisallowUsers) rule).users;
            } else {
                continue;
            }
            for (Long id : ids) {
                if (id != null && inputOf(currentAccount, id) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean has(ArrayList<Long> ids, long id) {
        for (Long entry : ids) {
            if (entry != null && entry == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * The real moment out of a status, or zero.
     *
     * Online counts as a moment and it is now: they are there, which is a more
     * exact answer than any timestamp. Offline carries the moment in `expires',
     * which is the field the app's own formatter reads.
     */
    private static long exactOf(TLRPC.UserStatus status) {
        if (status instanceof TLRPC.TL_userStatusOnline) {
            return System.currentTimeMillis() / 1000L;
        }
        if (status instanceof TLRPC.TL_userStatusOffline && status.expires > 0) {
            return status.expires;
        }
        return 0;
    }

    /** Ends a trade that never opened anything, so nothing has to be put back. */
    private static void failed(BaseFragment fragment, TLRPC.TL_error error) {
        running = 0;
        error(fragment, formatString(R.string.PurpleTradeFailed,
                (error == null || error.text == null) ? "?" : error.text));
    }

    private static void error(BaseFragment fragment, CharSequence text) {
        if (BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment).createErrorBulletin(text).show();
        }
    }

    private static void info(BaseFragment fragment, CharSequence text) {
        if (BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, text).show();
        }
    }

    /** Whether the sheet has been answered once for good. */
    public static boolean noAsk() {
        try {
            return prefs().getBoolean(KEY_NO_ASK, false);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void setNoAsk(boolean value) {
        try {
            prefs().edit().putBoolean(KEY_NO_ASK, value).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
