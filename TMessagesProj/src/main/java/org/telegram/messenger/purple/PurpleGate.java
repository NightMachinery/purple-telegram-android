/*
 * This is the source code of Purple Telegram for Android.
 *
 * The seam between the Work Mode core and Telegram's data model. The core knows
 * about bare peer ids and chat kinds; this class is the only place that turns a
 * TLRPC.Dialog into those, and the only place that decides whether a row belongs
 * in the chat list.
 *
 * Hiding is a view, not an edit: nothing here ever touches the model. A hidden
 * chat is still in MessagesController's dialogs, still pinned, still reachable
 * from the forward picker and from search - it is simply left out of the list
 * DialogsActivity draws. See docs/purple/work_mode.md, "Hiding is a view, not an
 * edit".
 */

package org.telegram.messenger.purple;

import android.os.SystemClock;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class PurpleGate {

    /**
     * The two errors {@code PurpleCore.Loaded.failed} produces, meaning the
     * bridge answered nothing at all rather than answering "your settings.toml
     * does not parse". Only these mean the previous resolution has to be kept:
     * a parse failure still comes back with a usable resolution in it.
     */
    private static final String ERROR_NO_RESULT = "no result from the core";
    private static final String ERROR_BAD_RESULT = "bad result from the core";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /**
     * Tested first by every hot path. While false the app must behave exactly
     * like stock, so it stays false until a non-normal preset has actually been
     * loaded - a missing settings.toml, a broken one, or a build without the
     * native library all leave it alone.
     */
    private static volatile boolean filtering;

    /** The last resolution we could compute, or null before the first load. */
    private static volatile PurpleCore.Loaded loaded;

    private static volatile boolean everLoaded;

    /**
     * Whether the running resolution came from settings.toml.good rather than
     * from settings.toml. Shown in the picker: a preset running off a copy the
     * user cannot see is the kind of thing that has to be said out loud.
     */
    private static volatile boolean usedLastGood;

    /**
     * The show mode of each dialog, as {@link PurpleCore#visible} packed it. The
     * mode half of the answer only moves when the resolution does, so it is
     * cached; the unread half is recomputed on every pass because it moves all
     * the time.
     */
    private static final LongSparseArray<Integer> modeCache = new LongSparseArray<>();

    private static final Object cacheLock = new Object();

    /** Throttles the per-pass counting line, which a scrolling list would flood. */
    private static volatile long lastCountLog;

    /** The same, for the folder strip, which is rebuilt on every accessor call. */
    private static volatile long lastFolderLog;

    /**
     * Bumped by every reload. The chat list watches it to notice that the strip
     * it is holding was built for a different preset: the tab the user is on is
     * an index into a list whose membership just changed, so an index that is
     * still in range now means a different folder.
     */
    private static volatile int generation;

    /**
     * Fired when a peek's deadline passes. A reload is the whole of it: the
     * bridge re-asks PeekLive(), finds it over, and clears the flag.
     */
    private static final Runnable PEEK_OVER = () -> reload("peek over");

    /**
     * Fired when the earliest "until" decision runs out. A reload is the whole
     * of it: the bridge prunes what has expired and hands the file back.
     */
    private static final Runnable OVERRIDE_OVER = () -> reload("an until expired");

    /**
     * Where an extra view's filter id starts.
     *
     * The same reserved base the desktop fork uses, and far above any id the
     * server hands out. These filters live only in the display accessor, so an
     * id never reaches a request - but one that could collide with a real
     * folder's would be a trap for anything that ever looked one up.
     */
    private static final int VIEW_ID_BASE = 0x50555250;

    /**
     * The synthetic filters standing for the preset's extra tabs.
     *
     * Rebuilt once per reload and handed out by reference, never rebuilt per
     * call: {@code sortDialogs()} fills {@code filter.dialogs} for whichever
     * filter is selected, and a fresh object every time would mean the tab on
     * screen was never the one anything filled.
     */
    private static volatile ArrayList<MessagesController.DialogFilter> viewFilters =
            new ArrayList<>();

    /**
     * Whether some extra view's pinned order is still missing peers.
     *
     * Set while a pin names a chat this client has not loaded yet, which on a
     * cold start is <i>every</i> pin: the gate loads the first time anything
     * asks it a question, long before the dialog list is in memory. Cleared by
     * {@link #refillViewPinsIfNeeded()} once they all resolve.
     */
    private static volatile boolean viewPinsIncomplete;

    /** Telegram's Archive, which is a folder id rather than a chat. */
    private static final int ARCHIVE_FOLDER_ID = 1;

    private PurpleGate() {
    }

    /**
     * Whether a preset is hiding anything at all.
     *
     * @return false when the app should behave exactly like stock
     */
    public static boolean filtering() {
        return filtering;
    }

    /** The resolution now in force, or null if nothing has been loaded yet. */
    public static PurpleCore.Loaded state() {
        return loaded;
    }

    /** Whether the running resolution came from the last good copy. */
    public static boolean usedLastGood() {
        return usedLastGood;
    }

    /** Changes whenever the resolution is reloaded. See {@link #generation}. */
    public static int generation() {
        return generation;
    }

    /**
     * Whether the folder strip is anything other than the account's own
     * folders, in the account's own order.
     *
     * This, and not {@link #filtering()}, is what guards folder reordering:
     * a preset whose whole selection is {@code "*ALL"} leaves the strip
     * identical to the account's, so dragging a tab still means what it says.
     * Mirrors the desktop fork's {@code Purple::FoldersRestricted()}.
     */
    public static boolean foldersRestricted() {
        if (!filtering) {
            return false;
        }
        final PurpleCore.Loaded current = loaded;
        if (current == null) {
            return false;
        }
        // Extra views sit on the strip too, so a strip index is no longer the
        // real list's index shifted by one. Checked before the peek, because a
        // peek reveals folders and leaves the extra views exactly where they
        // were - the desktop's order, and the reason this is no longer one line.
        if (!current.views.isEmpty()) {
            return true;
        }
        // A peek puts the whole strip back in the account's own order, so a
        // strip index means a server-side position again and dragging is safe.
        return !current.clock.peeking && current.foldersRestricted;
    }

    /** Whether a peek is currently revealing what the preset hides. */
    public static boolean peeking() {
        final PurpleCore.Loaded current = loaded;
        return current != null && current.clock.peeking;
    }

    /**
     * Whether the Premium features this client alone withholds are unlocked -
     * `[premium] enabled_p` in settings.toml, on unless it says otherwise.
     *
     * Answers **true** before anything has been loaded, and after a load that
     * failed, because that is what the file's own default says and what a
     * missing file means. Every other gate here defaults to "stock behaviour"
     * on the way in; this one cannot, or a fresh install would withhold
     * something the settings it has not read yet would grant.
     *
     * ensureLoaded() rather than a bare read, for the same reason
     * getNotifyOverride does it: these call sites are reached on paths that a
     * chat list has never necessarily touched. After the first call it is one
     * volatile read.
     */
    public static boolean localPremium() {
        ensureLoaded();
        final PurpleCore.Loaded current = loaded;
        return current == null || current.premium;
    }

    /**
     * settings.toml as it is on disk right now, or null if it is not there.
     *
     * Read fresh rather than remembered: everything that edits the file splices
     * the bytes it was handed, so working from a copy taken earlier would write
     * back a file that had moved on.
     */
    public static byte[] settingsBytes() {
        return readSettings();
    }

    /**
     * The settings.toml the running resolution was built from - its length and
     * SHA-256 - or null when there was no file to read.
     *
     * Kept for one caller: {@link PurpleWatcher} has to tell an edit somebody
     * else made from the echo of the app's own write, which has already
     * reloaded synchronously by the time the watcher hears about it. The
     * desktop fork does the same comparison in {@code purple_config.cpp}.
     */
    private static volatile String settingsFingerprint;

    /** Length and SHA-256 of these bytes, or null for "there is no file". */
    private static String fingerprintOf(byte[] settings) {
        if (settings == null) {
            return null;
        }
        try {
            final byte[] sum = MessageDigest.getInstance("SHA-256").digest(settings);
            final StringBuilder out = new StringBuilder(sum.length * 2 + 12);
            out.append(settings.length).append(':');
            for (int a = 0; a < sum.length; ++a) {
                out.append(Character.forDigit((sum[a] >> 4) & 0xf, 16))
                        .append(Character.forDigit(sum[a] & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            // Then nothing can be compared, and every event is a real one.
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Whether these bytes are the settings.toml the resolution already runs on.
     *
     * False whenever either side is unknown, which is the safe direction: a
     * file that has just been deleted, or one that could not be digested, is
     * reported as a change and gets its reload.
     */
    public static boolean runningSettings(byte[] settings) {
        final String known = settingsFingerprint;
        final String candidate = fingerprintOf(settings);
        return known != null && known.equals(candidate);
    }

    /** Loads settings.toml and state.toml once, the first time anyone asks. */
    public static void ensureLoaded() {
        if (everLoaded) {
            return;
        }
        ensureLoadedLocked();
    }

    private static synchronized void ensureLoadedLocked() {
        if (everLoaded) {
            return;
        }
        // Set before reloading, not after: reload() posts a chat list refresh,
        // and a refresh that came back round to ensureLoaded() would recurse.
        everLoaded = true;
        reload("first use");
        // Only now, so the watch is never the thing that triggers the first
        // load: it exists to notice the file changing afterwards.
        PurpleWatcher.start();
    }

    /**
     * Rereads both files, resolves the active preset and refreshes every chat
     * list.
     *
     * A load that fails outright - no native library, or a bridge that answered
     * nothing - leaves the previous resolution running rather than falling back
     * to normal. Unhiding every chat the user hid, because a file was half
     * written or a library was missing, is the one failure that must not happen.
     *
     * @param reason what asked for the reload, for the log line
     */
    public static void reload(String reason) {
        final byte[] settings = readSettings();
        final byte[] state = PurpleState.read();

        PurpleCore.Loaded next = load(settings, state);
        if (next == null) {
            return;
        }
        // A settings.toml that does not parse still comes back resolved - the
        // core falls back to its cached resolution and reports the error
        // alongside it. Only the bridge saying nothing is unrecoverable.
        if (!next.ok && (ERROR_NO_RESULT.equals(next.error) || ERROR_BAD_RESULT.equals(next.error))) {
            FileLog.e("Purple: " + next.error + ", keeping the previous resolution.");
            return;
        }

        // The resolution cache in state.toml remembers the order a preset
        // resolved to, but not what its lists contained - membership lives in
        // settings.toml and nowhere else. So a settings.toml that has gone
        // missing leaves every chat unclaimed, and a preset that names what
        // gets through hides an account it can no longer describe.
        //
        // Falling back to the last copy that worked is the fix, and the file
        // itself is the right thing to keep: it needs no second schema, it
        // cannot disagree with the real one about what a list means, and it
        // covers a file broken halfway through an edit the same way it covers
        // one that vanished.
        boolean shadowed = false;
        if (settings == null || !next.ok) {
            final byte[] lastGood = readFile(PurpleSettings.lastGoodFile());
            if (lastGood != null) {
                final PurpleCore.Loaded alt = load(lastGood, state);
                if (alt != null && alt.ok) {
                    FileLog.d("Purple: settings.toml is "
                            + (settings == null ? "missing" : "unusable")
                            + ", running from the last good copy.");
                    next = alt;
                    shadowed = true;
                }
            }
        }
        usedLastGood = shadowed;
        if (!shadowed && next.ok && settings != null) {
            PurpleSettings.writeAtomic(PurpleSettings.lastGoodFile(), settings);
        }

        loaded = next;
        // What the watcher compares the file against. Set from the bytes this
        // reload actually read, so the app's own write comes straight back as
        // "already running" rather than as a change to react to.
        settingsFingerprint = fingerprintOf(settings);
        synchronized (cacheLock) {
            modeCache.clear();
        }
        if (next.stateText != null) {
            PurpleState.write(next.stateText.getBytes(UTF_8));
        }
        filtering = !next.normal;
        ++generation;
        refreshPeekTimer(next);
        refreshOverrideTimer(next);
        rebuildViewFilters(next);

        if (next.normal) {
            FileLog.d("Purple: normal. (" + reason + ")");
        } else {
            FileLog.d("Purple: preset '" + next.preset + "', " + next.lists + " lists"
                    + (next.clock.peeking ? " (peeking)" : "") + ". (" + reason + ")");
        }
        if (!next.ok && !TextUtils.isEmpty(next.error)) {
            FileLog.d("Purple: settings.toml: " + next.error);
        }
        if (next.usedCache) {
            FileLog.d("Purple: using the cached resolution (" + next.cacheReason + ").");
        }

        postRefresh();

        // Either file can change the answer sooner than the next thirty seconds
        // would: a rule added, a pause lifted, or a preset chosen by hand that
        // the schedule now has an opinion about.
        PurpleSchedule.refresh();
    }

    /**
     * Ends a running peek when its deadline arrives.
     *
     * The timer belongs to the gate for the same reason it does on the desktop:
     * the gate is what has to re-run when it fires. Nothing else would look at
     * the deadline again, so without this a peek would sit there until the next
     * unrelated change to the files.
     *
     * There is no clearing half here, unlike the desktop's: a peek that outlived
     * the app is already gone from state.toml by the time this runs, because the
     * bridge clears an expired flag while it resolves and hands the rewritten
     * file back with everything else.
     */
    private static void refreshPeekTimer(PurpleCore.Loaded current) {
        AndroidUtilities.cancelRunOnUIThread(PEEK_OVER);
        if (!current.clock.peeking || current.clock.peekDeadline <= 0) {
            // No deadline means auto_off is turned off, and that peek really
            // does run until it is turned off by hand.
            return;
        }
        final long left = current.clock.peekDeadline - System.currentTimeMillis() / 1000L;
        AndroidUtilities.runOnUIThread(PEEK_OVER, Math.max(left, 0L) * 1000L);
    }

    /**
     * Starts or ends a peek: every chat back in the list, no group waiting for a
     * mention, every folder on the strip.
     *
     * It reveals; it does not un-silence. The two halves of a preset answer
     * different questions - hiding is about what you can find, silencing is
     * about what may interrupt you - and a burst of notifications for chats
     * already on the screen, taken back two minutes later, is not what looking
     * at the chat list asked for. The engine enforces that, not this method.
     *
     * @return what happened, so the caller can say which way it went
     */
    public static PurpleCore.PeekChange togglePeek() {
        ensureLoaded();
        final PurpleCore.PeekChange change = PurpleCore.togglePeek(PurpleState.read());
        if (change.text != null && PurpleState.write(change.text.getBytes(UTF_8))) {
            reload(change.peeking ? "peek" : "peek over");
        }
        return change;
    }

    /**
     * Holds the schedule off, or lets it catch up.
     *
     * Unpausing catches up with wherever the schedule has got to, by the same
     * boundary rule as everything else: the target moved while it was not
     * looking, so the reload below re-ticks and it applies once.
     *
     * @return whether the choice reached state.toml
     */
    public static boolean setSchedulePaused(boolean paused) {
        ensureLoaded();
        final String text = PurpleCore.setSchedulePaused(PurpleState.read(), paused);
        if (text == null || !PurpleState.write(text.getBytes(UTF_8))) {
            return false;
        }
        reload(paused ? "schedule paused" : "schedule resumed");
        return true;
    }

    /**
     * Arms the expiry of the earliest "until" decision outstanding.
     *
     * Same shape and same reason as the peek timer: nothing else would look at
     * the deadline again, so without this a decision would sit there in force
     * until the next unrelated change to the files. One timer for all of them,
     * re-armed on every reload, because a reload is what follows every change
     * to the set.
     */
    private static void refreshOverrideTimer(PurpleCore.Loaded current) {
        AndroidUtilities.cancelRunOnUIThread(OVERRIDE_OVER);
        final long deadline = current.clock.nextOverrideDeadline;
        if (deadline <= 0) {
            return;
        }
        final long left = deadline - System.currentTimeMillis() / 1000L;
        AndroidUtilities.runOnUIThread(OVERRIDE_OVER, Math.max(left, 0L) * 1000L);
    }

    /**
     * Makes, replaces or cancels one "until" decision about a chat.
     *
     * A statement about a preset, so it does nothing under Normal - there would
     * be nothing for it to outrank. One per chat per preset: a second decision
     * replaces the first rather than queueing behind it, which the core does.
     *
     * @param kind one of the {@code PurpleCore.OVERRIDE_} values
     * @param seconds how long it lasts; zero cancels whatever is running
     * @return whether the decision reached state.toml
     */
    public static boolean setOverride(
            int currentAccount, long dialogId, int kind, int seconds) {
        ensureLoaded();
        final long id = bareIdOf(currentAccount, dialogId);
        if (id == 0) {
            return false;
        }
        final String text = PurpleCore.setOverride(PurpleState.read(), id, kind, seconds);
        if (text == null || !PurpleState.write(text.getBytes(UTF_8))) {
            return false;
        }
        reload(seconds > 0 ? "until set" : "until cancelled");
        return true;
    }

    /** The "until" decision in force for this chat, for the menu that offers one. */
    public static int overrideKind(int currentAccount, long dialogId) {
        return filtering ? overrideFor(currentAccount, dialogId) : PurpleCore.OVERRIDE_NONE;
    }

    /**
     * Makes {@code preset} the active one and reloads.
     *
     * @param presetOrNull the preset name; null, empty or "normal" is the bypass
     * @return whether the choice reached state.toml
     */
    public static boolean setPreset(String presetOrNull) {
        final String text;
        try {
            text = PurpleCore.setPreset(PurpleState.read(), presetOrNull);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return false;
        }
        if (text == null) {
            return false;
        }
        if (!PurpleState.write(text.getBytes(UTF_8))) {
            return false;
        }
        reload("preset switch");
        return true;
    }

    /** What the Work Mode entry in the main menu says. */
    public static String menuLabel() {
        final PurpleCore.Loaded current = loaded;
        if (current == null || current.normal) {
            return LocaleController.getString(R.string.PurpleWorkMode);
        }
        return LocaleController.formatString(R.string.PurpleWorkModeActive, current.title);
    }

    /**
     * What a dialog is, as the core's ChatKind numbers it.
     *
     * Mirrors the desktop fork's {@code Purple::KindOf}: basic groups and
     * supergroups are one kind, because being upgraded must not move a chat
     * between lists. A dialog we know nothing about yet gets the harmless
     * answer for its sign rather than being skipped, so an unloaded peer is
     * still decided by the preset rather than sliding through it.
     *
     * @param dialogId a TLRPC.Dialog id; must not be the Archive row
     * @return one of the {@code PurpleCore.KIND_} constants
     */
    public static int kindOf(int currentAccount, long dialogId) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        if (DialogObject.isEncryptedDialog(dialogId)) {
            final TLRPC.EncryptedChat encryptedChat =
                    controller.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (encryptedChat == null) {
                return PurpleCore.KIND_PRIVATE;
            }
            final TLRPC.User user = controller.getUser(encryptedChat.user_id);
            return UserObject.isBot(user) ? PurpleCore.KIND_BOT : PurpleCore.KIND_PRIVATE;
        }
        if (dialogId > 0) {
            final TLRPC.User user = controller.getUser(dialogId);
            return UserObject.isBot(user) ? PurpleCore.KIND_BOT : PurpleCore.KIND_PRIVATE;
        }
        final TLRPC.Chat chat = controller.getChat(-dialogId);
        return ChatObject.isChannelAndNotMegaGroup(chat) ? PurpleCore.KIND_CHANNEL : PurpleCore.KIND_GROUP;
    }

    /**
     * The chat's id as settings.toml writes it.
     *
     * The bare id and nothing else: there is no {@code -100} channel prefix in
     * this codebase, that is the Bot API's convention. An encrypted chat is
     * addressed as the user behind it, the same unwrap
     * {@code MessagesController.DialogFilter.alwaysShow} does.
     */
    public static long bareIdOf(int currentAccount, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            final TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance(currentAccount)
                    .getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            return encryptedChat != null ? encryptedChat.user_id : 0;
        }
        return Math.abs(dialogId);
    }

    /**
     * Whether the running preset shows this dialog right now.
     *
     * Saved Messages gets no exemption - it is an ordinary chat, deliberately.
     * See docs/purple/work_mode.md, "Saved Messages has no exemption".
     */
    public static boolean shown(int currentAccount, TLRPC.Dialog dialog) {
        if (!filtering || dialog == null) {
            return true;
        }
        // The Archive row is not a chat and has no kind, so it is answered
        // before the core is ever asked about it.
        if (DialogObject.isFolderDialogId(dialog.id)) {
            return true;
        }
        // A decision made about this one chat, which outranks the preset for as
        // long as it lasts.
        switch (byHand(currentAccount, dialog.id, true)) {
        case PurpleCore.OVERRIDE_SHOW: return true;
        case PurpleCore.OVERRIDE_HIDE: return false;
        default: break;
        }
        return shownForMode(currentAccount, dialog, packedFor(currentAccount, dialog.id));
    }

    /**
     * Whether this dialog is out of the app rather than out of the preset's view.
     *
     * Mirrors the desktop fork's {@code History::purpleHiddenFromChatList()}.
     * Only a preset that asked for it - {@code hide_everywhere_p} - takes a chat
     * out of the app rather than out of its own view; everything else keeps the
     * chat where it is and leaves it out of the view alone, which is what lets a
     * hidden chat stay pinned, searchable and reachable from the forward picker.
     *
     * And the one-chat version of the same request: a "hide until" made while
     * {@code hide_scope = "hide_everywhere"}. A preset-wide switch and a
     * decision about a single chat, doing the same thing for the same reason -
     * the second one simply expires by itself. {@link #byHand} is what asks it,
     * so the peek veto and the close buffer already apply: a hide that is not
     * hiding anything at this moment takes nothing out of anything.
     *
     * Asked once per dialog per rebuild of every list derived from
     * {@code dialogs_dict}, so the two tests at the top - nothing is hiding, and
     * nothing anywhere asked to hide globally - are what a file that never
     * mentions either of these costs.
     */
    public static boolean hiddenEverywhere(int currentAccount, TLRPC.Dialog dialog) {
        if (!filtering || dialog == null) {
            return false;
        }
        final PurpleCore.Loaded current = loaded;
        if (current == null) {
            return false;
        }
        final boolean scope = (current.clock.hideScope == PurpleCore.SCOPE_EVERYWHERE);
        if (!current.hideEverywhere && !scope) {
            return false;
        }
        // The Archive row is not a chat and has no kind, the same answer
        // shown() gives it before the core is ever asked.
        if (DialogObject.isFolderDialogId(dialog.id)) {
            return false;
        }
        if (scope && byHand(currentAccount, dialog.id, true) == PurpleCore.OVERRIDE_HIDE) {
            return true;
        }
        return current.hideEverywhere && !shown(currentAccount, dialog);
    }

    /**
     * The same question asked with an id, for the callers that have no dialog.
     *
     * The lookup runs only once the cheap tests above have already said the
     * question is live, so a file that asks for neither switch never reaches
     * {@code dialogs_dict} at all. A chat with no dialog object is not in any
     * list this could take it out of, so it is not hidden either.
     */
    public static boolean hiddenEverywhere(int currentAccount, long dialogId) {
        if (!hidingEverywhere()) {
            return false;
        }
        return hiddenEverywhere(
                currentAccount,
                MessagesController.getInstance(currentAccount).dialogs_dict.get(dialogId));
    }

    /**
     * Whether anything at all can be missing from the lists sortDialogs builds.
     *
     * Asked without a chat, by the code that has to know whether the model it
     * is about to read is the whole account or a subset of it - the pinned
     * order is the one that matters, because it is uploaded with
     * {@code force = true} and a truncated one unpins the rest of the account.
     */
    public static boolean hidingEverywhere() {
        if (!filtering) {
            return false;
        }
        final PurpleCore.Loaded current = loaded;
        return current != null
                && (current.hideEverywhere
                        || (current.clock.hideScope == PurpleCore.SCOPE_EVERYWHERE
                                && !current.clock.overrides.isEmpty()));
    }

    /**
     * Whether the archive is out of the way while a preset runs.
     *
     * Mirrors {@code hide_archive_p}: no pull gesture, no row in the list, the
     * same state as an account that has never archived anything - which is a
     * state every path in the app already handles, and the reason this is one
     * flag rather than a hook at each pull site.
     *
     * On unless the preset says otherwise, which is the opposite default from
     * {@code hide_everywhere_p}: a preset that has already named what gets
     * through has no reason to leave a door to the rest of it open.
     *
     * A peek does not bring it back. Peek suspends the hiding of chats, and a
     * chat the archive holds is not hidden by the preset - it is filed. Making
     * the row appear and vanish on the peek timer would shift the whole list by
     * one on both edges, for no chat anybody was looking for.
     */
    public static boolean hidingArchive() {
        if (!filtering) {
            return false;
        }
        final PurpleCore.Loaded current = loaded;
        return current != null && current.hideArchive;
    }

    /**
     * The span a row is in the view only for the moment, on the monotonic clock.
     *
     * Covers both reasons a row can be here on a timer - the close buffer and a
     * "show until" - because the chat list draws one mark for both and should
     * not have to know which it is looking at.
     */
    public static final class Temporary {
        /** Both on {@link SystemClock#elapsedRealtime()}, in millis. */
        public final long fromMs;
        public final long untilMs;

        /**
         * Whether a "show until" is what is holding the row here, rather than
         * the close buffer. The chat list draws the same mark either way but in
         * a different colour, because the two mean different things: one is a
         * decision you made and will remember making, the other is a chat you
         * happened to look at a minute ago.
         */
        public final boolean held;

        Temporary(long fromMs, long untilMs, boolean held) {
            this.fromMs = fromMs;
            this.untilMs = untilMs;
            this.held = held;
        }
    }

    /**
     * The span this row is temporary for, or null when it is not.
     *
     * Mirrors the desktop fork's {@code History::purpleTemporary()}. Asked once
     * per row per update, so the two cheap tests at the top - nothing is
     * hiding, and nothing is on a clock anywhere - are what every ordinary row
     * under every ordinary preset actually costs.
     */
    public static Temporary temporary(int currentAccount, TLRPC.Dialog dialog) {
        if (!filtering || dialog == null) {
            return null;
        }
        final PurpleCore.Loaded current = loaded;
        if (current == null || current.clock.peeking) {
            // A peek shows everything, so nothing on screen during one is on
            // screen because of a clock.
            return null;
        }
        // Zero while the chat is the one you have open: it is not counting down
        // yet, the clock starts when you stop looking at it.
        final long graceUntil = PurpleRecent.graceUntil(currentAccount, dialog.id);
        if (current.clock.overrides.isEmpty() && graceUntil == 0) {
            return null;
        }

        final long nowUnix = System.currentTimeMillis() / 1000L;
        final long nowMs = SystemClock.elapsedRealtime();

        // A "show until" and nothing else. The mark means "this row is here on
        // a clock", and that is only true of a row the override put there: a
        // chat a "hide until" has put away is still a permanent member of
        // whatever folder tab is still showing it, and marking it as leaving
        // would say the opposite of what is happening. A "notify until" does
        // not touch visibility at all. What is on a clock in those two cases is
        // the decision, not the row.
        //
        // And in both branches, wouldLeaveTheView() - the whole claim the mark
        // makes is that the row goes when the clock stops, so a chat the preset
        // lets through anyway must not carry one. Asked last because it is the
        // expensive test.
        final long id = bareIdOf(currentAccount, dialog.id);
        if (id != 0) {
            final java.util.List<PurpleCore.Override> overrides = current.clock.overrides;
            for (int i = 0, n = overrides.size(); i < n; ++i) {
                final PurpleCore.Override entry = overrides.get(i);
                if (entry.peer != id || entry.until <= nowUnix) {
                    continue;
                }
                if (entry.kind == PurpleCore.OVERRIDE_SHOW
                        && wouldLeaveTheView(currentAccount, dialog)) {
                    // Unix seconds, so it is converted here rather than stored
                    // twice. Only ever against a deadline still in the future,
                    // which keeps the arithmetic away from a wall clock that
                    // has been dragged backwards.
                    return new Temporary(
                            nowMs - Math.max(nowUnix - entry.from, 0L) * 1000L,
                            nowMs + (entry.until - nowUnix) * 1000L,
                            true); // Held open by the override, not lingering.
                }
                break;
            }
        }

        // The close buffer. Re-reads the setting, so turning [recent] off drops
        // the mark at once rather than at the end of whatever was running.
        final int stay = PurpleRecent.staySeconds();
        if (stay > 0
                && graceUntil > nowMs
                && wouldLeaveTheView(currentAccount, dialog)) {
            return new Temporary(graceUntil - stay * 1000L, graceUntil, false);
        }
        return null;
    }

    /**
     * How the chat list should mark a row that is only there on a clock.
     *
     * One of the {@code PurpleCore.STYLE_} values, and {@code STYLE_NONE} - the
     * default, meaning nothing is drawn - before anything has been loaded.
     */
    public static int recentStyle() {
        final PurpleCore.Loaded current = loaded;
        return current == null ? PurpleCore.STYLE_NONE : current.clock.recentStyle;
    }

    /**
     * Whether this row would be gone if the clock holding it here ran out.
     *
     * Mirrors the desktop fork's {@code History::purpleWouldLeaveTheView()},
     * and is the test that keeps the mark honest: a chat the preset lets
     * through anyway is not leaving, whatever else is also true of it.
     */
    private static boolean wouldLeaveTheView(int currentAccount, TLRPC.Dialog dialog) {
        if (!filtering || peeking()) {
            return false;
        }
        // A hide stays in the reckoning - it is a reason the row would be gone,
        // not a reason it is here. A show is taken out, because it is exactly
        // the temporary thing being asked about.
        if (overrideFor(currentAccount, dialog.id) == PurpleCore.OVERRIDE_HIDE) {
            return true;
        }
        return !shownForMode(currentAccount, dialog, packedFor(currentAccount, dialog.id));
    }

    /**
     * What an "until" decision says about this chat, with a peek's veto applied.
     *
     * A peek reveals, so it outranks a hide - and that is what makes a
     * hide-until cancellable rather than a trap: the row has to come back for
     * you to be able to reach the menu that cancels it. It does not outrank the
     * notify half, because a peek is a look at the chat list and not a request
     * to be interrupted.
     *
     * The peek's effect on the preset's own answer is not here at all: the
     * engine already forces Always while one is running, so every path that
     * asks {@code packedFor} gets it for free. This is only about the order
     * between a peek and an "until".
     *
     * @param forView true for the hiding question, false for the silencing one
     */
    private static int byHand(int currentAccount, long dialogId, boolean forView) {
        // The close buffer comes first, ahead of everything including a hide -
        // it is not a statement about what the preset lets through but about
        // what you were doing ten seconds ago. View only: having just read
        // something is no reason to be interrupted by it.
        if (forView && PurpleRecent.shown(currentAccount, dialogId)) {
            return PurpleCore.OVERRIDE_SHOW;
        }
        final int kind = overrideFor(currentAccount, dialogId);
        if (!forView || kind == PurpleCore.OVERRIDE_NONE) {
            return kind;
        }
        final PurpleCore.Loaded current = loaded;
        return (current != null && current.clock.peeking) ? PurpleCore.OVERRIDE_NONE : kind;
    }

    /**
     * The "until" decision in force for this chat, or {@code OVERRIDE_NONE}.
     *
     * Answered from the list the load result handed over rather than from the
     * file: this runs once per row per rebuild and once per incoming message,
     * and a JNI call with a state parse behind it is the one thing those paths
     * cannot carry. The bridge has already dropped what expired and what
     * belongs to another preset, so the only test left is the clock, which can
     * move under Java between two reloads.
     *
     * The empty case is the common one and costs a size check, the same shape
     * the folder walks use.
     */
    private static int overrideFor(int currentAccount, long dialogId) {
        final PurpleCore.Loaded current = loaded;
        if (current == null || current.clock.overrides.isEmpty()) {
            return PurpleCore.OVERRIDE_NONE;
        }
        final long id = bareIdOf(currentAccount, dialogId);
        if (id == 0) {
            return PurpleCore.OVERRIDE_NONE;
        }
        final long now = System.currentTimeMillis() / 1000L;
        final java.util.List<PurpleCore.Override> overrides = current.clock.overrides;
        for (int i = 0, n = overrides.size(); i < n; ++i) {
            final PurpleCore.Override entry = overrides.get(i);
            if (entry.peer == id && entry.until > now) {
                return entry.kind;
            }
        }
        return PurpleCore.OVERRIDE_NONE;
    }

    /**
     * Whether the running preset silences this chat.
     *
     * A preset can only ever <i>add</i> a mute. A chat the user muted by hand
     * stays muted whichever entry claims it, and switching presets never
     * un-silences anything - so this answer is combined with the user's own
     * mute by the callers, never substituted for it. See
     * docs/purple/work_mode.md and the desktop fork's
     * {@code NotifySettings::purpleSilenced}.
     *
     * Two things this deliberately does not do. It never asks about unread
     * state: a chat the preset gates on unread is still allowed to notify,
     * because being out of the list until it has something to say is a
     * different claim from being silenced - and {@code shownForMode} already
     * reads the unread count, so asking from here would recurse through the
     * counters that call this. And it ignores topics: a preset silencing a
     * chat silences its topics, because the core has never heard of one.
     *
     * A folder can silence too, with {@code notify_p = false}, and that half is
     * asked last - a chat its own list already silenced never reaches the
     * folder walk. See {@link #silencedByFolder}.
     *
     * Called per row draw, per unread-counter pass and per incoming message,
     * so the not-filtering answer costs one volatile read and no file I/O -
     * this must never call {@link #ensureLoaded()}.
     *
     * @param dialogId a TLRPC.Dialog id, or any peer id in dialog form
     */
    public static boolean silenced(int currentAccount, long dialogId) {
        if (!filtering) {
            return false;
        }
        // The Archive row is not a chat and has no kind, so it is answered
        // before the core is ever asked about it.
        if (DialogObject.isFolderDialogId(dialogId)) {
            return false;
        }
        // An "until" decision outranks the preset here too, and no peek test
        // above it: a peek reveals, it does not un-silence. Notify lifts the
        // preset's mute and only the preset's - the caller still combines this
        // with the user's own mute, so a chat muted by hand stays muted, which
        // is the rule this whole path is built on.
        switch (byHand(currentAccount, dialogId, false)) {
        case PurpleCore.OVERRIDE_NOTIFY: return false;
        case PurpleCore.OVERRIDE_HIDE: return true;
        default: break;
        }
        if ((packedFor(currentAccount, dialogId) & PurpleCore.NOTIFY_BIT) == 0) {
            return true;
        }
        return silencedByFolder(currentAccount, dialogId);
    }

    /**
     * Whether a folder the preset silenced holds this chat.
     *
     * A list can already silence a hand-picked set of chats, and for those the
     * list is the simpler tool. What a list cannot do is track a folder defined
     * by a <i>rule</i> - "all groups", "non-contacts", "everything except these
     * three" - whose membership moves on its own as chats arrive. Only the
     * folder form follows that.
     *
     * Answered live rather than from a snapshot, exactly as the desktop fork's
     * {@code NotifySettings::purpleSilencedByFolder()} answers it: the walk is
     * over the folders the preset named, and the emptiness check in front of it
     * is what keeps every other preset paying nothing. There is no cached
     * answer to go stale, which is the whole reason not to keep one - folder
     * membership moves with every message that arrives.
     *
     * Membership is decided as though this preset silenced nothing.
     * {@code includesDialog} reads {@code mutedWithoutPreset} for the "Exclude
     * muted" flag, which is what stops the obvious version eating itself: a
     * folder carrying that flag would otherwise stop containing a chat the
     * moment the preset silenced it, which would un-silence it, which would put
     * it back. See docs/purple/work_mode.md, "Breaking the loop".
     *
     * Called from the storage and notifications threads as well as the UI one,
     * and {@code includesDialog} walks lists the UI thread owns. A walk that
     * throws answers "not a member", which is the safe direction: the preset
     * silences nothing it was not already silencing, and the next query gets it
     * right.
     */
    private static boolean silencedByFolder(int currentAccount, long dialogId) {
        final PurpleCore.Loaded current = loaded;
        // The common case, and free: a preset that silenced no folder never
        // reaches the walk.
        return current != null
                && !current.silencedFolders.isEmpty()
                && heldByAny(currentAccount, dialogId, current.silencedFolders);
    }

    /**
     * Whether this chat's unread belongs in any running total.
     *
     * The "every running total" half of the default {@code hide_scope}: a chat
     * you put away half an hour ago is out of All chats, out of the archive's
     * number and out of every folder tab's, while its own row and its own badge
     * stay exactly where they were. {@code keep_in_folder} is a promise about
     * the row, not about the sum, so the tab keeps showing the chat and stops
     * adding it up. See docs/purple/work_mode.md, "How far a 'hide until'
     * reaches".
     *
     * This keys on the "hide until" and on nothing else. A chat the preset
     * itself hides still reaches the totals, as a muted one, through the hooked
     * {@code isDialogMuted} the counter passes already ask - and that is
     * deliberate: a preset is a standing arrangement about what you look at,
     * while an "until" is a decision you just made about one chat, and only the
     * second is worth rewriting a number over.
     *
     * Asked once per unread chat per recount, on the storage thread, so it must
     * never call {@link #ensureLoaded()} - the same rule the badge half follows,
     * and the {@code isDialogMuted} calls beside it in those loops already
     * establish that reaching into the gate from there is fine.
     */
    public static boolean countedInTotals(int currentAccount, long dialogId) {
        if (!filtering) {
            return true;
        }
        final PurpleCore.Loaded current = loaded;
        if (current == null) {
            return true;
        }
        // A chat the preset has taken out of the app is out of every number it
        // would otherwise be part of - there is no row left for a total to be
        // counting. Asked first because it is the wider claim, and it costs one
        // field read unless a preset actually asked for it.
        if (hiddenEverywhere(currentAccount, dialogId)) {
            return false;
        }
        return current.clock.hideScope == PurpleCore.SCOPE_COUNTED
                || byHand(currentAccount, dialogId, true) != PurpleCore.OVERRIDE_HIDE;
    }

    /**
     * Whether this chat may put a number on the app icon.
     *
     * A third axis, independent of hiding and silencing: a folder can be
     * silenced without being uncounted and uncounted without being silenced.
     * For a folder that is background on purpose, a count is a number you have
     * already decided not to act on, and the launcher badge claiming attention
     * on its behalf is exactly the interruption a work mode exists to stop.
     *
     * Only the app badge and the folder's own tab. The preset's own view still
     * counts a chat an uncounted folder holds, because that tab is counting
     * what is on screen in front of you, which is a different question from
     * whether the icon should light up.
     *
     * The hide-until half is not repeated here but taken from
     * {@link #countedInTotals}: a chat out of every running total is out of the
     * badge too, and the badge adds the quiet folders on top of it.
     */
    public static boolean countedForBadge(int currentAccount, long dialogId) {
        if (!filtering) {
            return true;
        }
        if (!countedInTotals(currentAccount, dialogId)) {
            return false;
        }
        final PurpleCore.Loaded current = loaded;
        if (current == null) {
            return true;
        }
        return current.quietFolders.isEmpty()
                || !heldByAny(currentAccount, dialogId, current.quietFolders);
    }

    /**
     * Whether the launcher badge has to be rebuilt rather than read off the
     * running totals.
     *
     * Two reasons, and either is enough: a folder asked to be left out of the
     * counts, or a "hide until" is running under a scope that takes its chat out
     * of them. Both are rare, and the rebuild only ever runs when one is true.
     */
    public static boolean badgeRebuilt() {
        if (!filtering) {
            return false;
        }
        final PurpleCore.Loaded current = loaded;
        if (current == null) {
            return false;
        }
        return !current.quietFolders.isEmpty()
                || (current.clock.hideScope != PurpleCore.SCOPE_COUNTED
                        && !current.clock.overrides.isEmpty());
    }

    /**
     * Whether this folder's own tab may show a number.
     *
     * The tab half of {@code badge_p}, asked of the folder rather than of a
     * chat: a folder left out of the counts shows no count of its own either.
     */
    public static boolean folderCounted(MessagesController.DialogFilter filter) {
        if (!filtering || filter == null) {
            return true;
        }
        final PurpleCore.Loaded current = loaded;
        return current == null
                || current.quietFolders.isEmpty()
                || !namedIn(current.quietFolders, filter.name);
    }

    /**
     * The mode a folder that pulls this chat into the view gives it, or
     * {@link PurpleCore#MODE_UNSET} when no folder does.
     *
     * The escape hatch, and the positive form of "this folder is not subject to
     * filtering": the chats in a folder named with {@code include_in_main_view}
     * join the preset's view whatever the lists decided. A folder that asked
     * for its pinned chats only lets through exactly those - the rest are not
     * dropped so much as left where they already were, with whatever their own
     * list decided.
     *
     * A chat can sit in two exempt folders and the most permissive wins: a
     * narrow one saying no must not speak for a wide one that would say yes.
     * Mirrors {@code History::purpleExemptFolderMode()}.
     */
    private static int exemptFolderMode(int currentAccount, long dialogId) {
        final PurpleCore.Loaded current = loaded;
        if (current == null || current.exemptFolders.isEmpty()) {
            // Free for every preset that does not use the escape hatch.
            return PurpleCore.MODE_UNSET;
        }
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        int result = PurpleCore.MODE_UNSET;
        try {
            final ArrayList<MessagesController.DialogFilter> filters =
                    controller.getDialogFiltersUnrestricted();
            final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (filters == null || filters.isEmpty() || dialog == null) {
                return PurpleCore.MODE_UNSET;
            }
            final long asked = unwrapped(controller, dialogId);
            final AccountInstance account = AccountInstance.getInstance(currentAccount);
            for (int a = 0, n = filters.size(); a < n; ++a) {
                final MessagesController.DialogFilter filter = filters.get(a);
                if (filter == null || filter.isDefault()) {
                    continue;
                }
                for (int i = 0, count = current.exemptFolders.size(); i < count; ++i) {
                    final PurpleCore.ExemptFolder folder = current.exemptFolders.get(i);
                    if (!folder.name.equalsIgnoreCase(filter.name)
                            || !filter.includesDialog(account, asked, dialog)) {
                        continue;
                    }
                    // "pinned" is the folder's own pinned order, which is what
                    // Telegram itself puts at the top of that tab - not the main
                    // chat list's pins.
                    if (folder.pinnedOnly
                            && filter.pinnedDialogs.indexOfKey(dialogId) < 0) {
                        continue;
                    }
                    final int mode = folder.showMode == PurpleCore.MODE_UNSET
                            ? defaultModeFor(current, currentAccount, dialogId)
                            : folder.showMode;
                    if (result == PurpleCore.MODE_UNSET || rank(mode) > rank(result)) {
                        result = mode;
                    }
                }
            }
        } catch (Exception e) {
            // Same tolerance as heldByAny(): "no folder pulled it in" is the
            // answer that changes nothing.
            return PurpleCore.MODE_UNSET;
        }
        return result;
    }

    /** {@code DefaultShowMode()} for whatever this chat turns out to be. */
    private static int defaultModeFor(
            PurpleCore.Loaded current, int currentAccount, long dialogId) {
        final int kind = kindOf(currentAccount, dialogId);
        return (kind >= 0 && kind < current.defaultModes.length)
                ? current.defaultModes[kind]
                : PurpleCore.SHOW_MESSAGE;
    }

    /**
     * How permissive a show mode is, for picking between two folders.
     *
     * The enum's own order will not do - it runs Always, Message,
     * MessageOrReaction, Mention, Never - so this is the desktop's
     * {@code ShowModeRank()}, spelled out.
     */
    private static int rank(int mode) {
        switch (mode) {
        case PurpleCore.SHOW_NEVER: return 0;
        case PurpleCore.SHOW_MENTION: return 1;
        case PurpleCore.SHOW_MESSAGE: return 2;
        case PurpleCore.SHOW_MESSAGE_OR_REACTION: return 3;
        case PurpleCore.SHOW_ALWAYS: return 4;
        default: return 0;
        }
    }

    /**
     * The same unwrap sortDialogs() does before asking a filter: a folder holds
     * an encrypted chat under the user behind it.
     */
    private static long unwrapped(MessagesController controller, long dialogId) {
        if (!DialogObject.isEncryptedDialog(dialogId)) {
            return dialogId;
        }
        final TLRPC.EncryptedChat encrypted = controller.getEncryptedChat(
                DialogObject.getEncryptedChatId(dialogId));
        return encrypted != null ? encrypted.user_id : dialogId;
    }

    /**
     * Whether any of these folders, by name, holds this chat.
     *
     * The one question all three folder flags need, and the reason they were
     * one piece of work rather than three. See {@link #silencedByFolder} for
     * why it is answered live and what a thrown answer means.
     */
    private static boolean heldByAny(
            int currentAccount, long dialogId, java.util.List<String> names) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        try {
            final ArrayList<MessagesController.DialogFilter> filters =
                    controller.getDialogFiltersUnrestricted();
            if (filters == null || filters.isEmpty()) {
                return false;
            }
            final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog == null) {
                // Nothing to ask a folder about. Reached on the notification
                // path before the dialog list has loaded, among other places;
                // guessing here would be worse than saying no.
                return false;
            }
            final long asked = unwrapped(controller, dialogId);
            final AccountInstance account = AccountInstance.getInstance(currentAccount);
            for (int a = 0, n = filters.size(); a < n; ++a) {
                final MessagesController.DialogFilter filter = filters.get(a);
                // The default folder's name is empty and its label is supplied
                // at render time, so it is never matched by name - the same
                // reason findByName() skips it.
                if (filter == null || filter.isDefault()) {
                    continue;
                }
                if (!namedIn(names, filter.name)) {
                    continue;
                }
                if (filter.includesDialog(account, asked, dialog)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // A list the UI thread was rewriting underneath us. Not an error
            // worth a stack trace on a path this hot; see the note above about
            // which direction is safe.
            return false;
        }
        return false;
    }

    /**
     * Whether this folder holds this chat right now.
     *
     * The membership question on its own, for the one caller that has a folder
     * in hand already. Answered as though the preset silenced nothing, the same
     * loop breaker every other membership test here uses.
     */
    public static boolean folderHolds(
            int currentAccount, MessagesController.DialogFilter filter, long dialogId) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
        if (dialog == null) {
            return false;
        }
        return filter.includesDialog(
                AccountInstance.getInstance(currentAccount),
                unwrapped(controller, dialogId),
                dialog);
    }

    /**
     * Rebuilds every chat list, without rereading anything.
     *
     * For the things that change what is on screen without changing what the
     * files say - a grace period starting or running out.
     */
    public static void refreshLists() {
        postRefresh();
    }

    /** Case-insensitive membership, as folder titles are matched everywhere. */
    private static boolean namedIn(java.util.List<String> names, String title) {
        for (int i = 0, count = names.size(); i < count; ++i) {
            if (names.get(i).equalsIgnoreCase(title)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the dialogs the running preset hides.
     *
     * @return {@code source} itself when nothing is hidden, so the common path
     *         allocates nothing and the caller keeps the live array
     */
    public static ArrayList<TLRPC.Dialog> filter(int currentAccount, ArrayList<TLRPC.Dialog> source) {
        ensureLoaded();
        if (!filtering || source == null || source.isEmpty()) {
            return source;
        }

        final int count = source.size();
        ArrayList<TLRPC.Dialog> result = null;
        int hidden = 0;
        int gated = 0;
        int gatedShowing = 0;
        int silenced = 0;
        for (int a = 0; a < count; ++a) {
            final TLRPC.Dialog dialog = source.get(a);
            final boolean show;
            if (dialog == null) {
                show = true;
            } else if (DialogObject.isFolderDialogId(dialog.id)) {
                // The Archive row is not a chat and no list has a say in it -
                // except the one preset-wide switch that takes it out
                // altogether. Dropping it here as well as answering
                // hasHiddenArchive() is what covers the pinned case: an account
                // whose archive row is pinned rather than pulled has it in
                // dialogs_dict like any other row, and hasHiddenArchive() is
                // never asked about that one.
                show = !hidingArchive();
            } else {
                final int packed = packedFor(currentAccount, dialog.id);

                // This loop decides for itself rather than calling shown() and
                // silenced(), because it also counts what it saw - so the two
                // "until" tests have to be repeated here, through the same
                // helper those two use. Missing them here was a real bug: the
                // hooks were in place and the chat list, which is the only
                // caller that matters, went straight past them.
                final int hand = byHand(currentAccount, dialog.id, false);
                if (hand == PurpleCore.OVERRIDE_NOTIFY) {
                    // Notified on purpose, for a while.
                } else if (hand == PurpleCore.OVERRIDE_HIDE
                        || (packed & PurpleCore.NOTIFY_BIT) == 0
                        || silencedByFolder(currentAccount, dialog.id)) {
                    ++silenced;
                }

                final int view = byHand(currentAccount, dialog.id, true);
                if (view == PurpleCore.OVERRIDE_SHOW) {
                    show = true;
                } else if (view == PurpleCore.OVERRIDE_HIDE) {
                    show = false;
                } else {
                    final int mode = effectiveMode(currentAccount, dialog.id, packed);
                    show = shownForShowMode(currentAccount, dialog, mode);
                    // Counted only when the mode is what decided, because a
                    // chat held by an "until" is not waiting on unread.
                    if (watchesUnread(mode)) {
                        ++gated;
                        if (show) {
                            ++gatedShowing;
                        }
                    }
                }
            }
            if (show) {
                if (result != null) {
                    result.add(dialog);
                }
            } else {
                if (result == null) {
                    // First hidden row: copy what we have kept so far and carry
                    // on into the new list.
                    result = new ArrayList<>(count);
                    for (int b = 0; b < a; ++b) {
                        result.add(source.get(b));
                    }
                }
                ++hidden;
            }
        }
        // Whatever a folder let in comes in even when the chat is archived.
        // Archiving is how visibility gets controlled in stock Telegram; under
        // a preset the preset controls it, by name, so a folder that asked for
        // its chats should get them wherever they happen to be filed -
        // otherwise you would have to unarchive things to make a preset work,
        // which is editing the account to change a view. The chats stay
        // archived; they are simply also here.
        int pulled = 0;
        final ArrayList<TLRPC.Dialog> fromArchive = archivePulledIn(currentAccount);
        if (fromArchive != null) {
            if (result == null) {
                result = new ArrayList<>(source);
            }
            result.addAll(fromArchive);
            pulled = fromArchive.size();
            // The main list arrives sorted, and a pulled-in chat is not pinned
            // *here* - its pin belongs to the archive - so the merge is a
            // re-sort with that one difference. Nearly-sorted input, which is
            // the case TimSort is fastest on.
            Collections.sort(result, mergedOrder(currentAccount));
        }

        // A preset that hides nothing looks exactly like one that is working,
        // and the usual cause is a list name spelled slightly wrong - so say
        // what the pass did. Gated chats are counted apart from hidden ones
        // because they are not the same claim: a gated chat is only out of the
        // list while it has nothing to say.
        final long now = SystemClock.elapsedRealtime();
        if (now - lastCountLog >= 1000L) {
            lastCountLog = now;
            FileLog.d("Purple: " + hidden + " of " + count + " dialogs hidden, "
                    + gated + " unread-gated (" + gatedShowing + " showing), "
                    + silenced + " silenced"
                    + (pulled > 0 ? ", " + pulled + " pulled in from the archive." : "."));
        }
        return result == null ? source : result;
    }

    /**
     * The archived chats an exempt folder pulls into the main view.
     *
     * Only the main list ever reaches this - every call site of {@link #filter}
     * guards on {@code folderId == 0} - and only a preset that named a folder
     * with {@code include_in_main_view} reaches the walk at all.
     *
     * The rows are the account's own {@code TLRPC.Dialog} objects, borrowed
     * from the archive's bucket rather than made up, so this is still a view
     * and not an edit: the chats stay archived, stay in the Archive row's own
     * list, and stay wherever else they were.
     *
     * @return the chats to add, or null when there are none
     */
    private static ArrayList<TLRPC.Dialog> archivePulledIn(int currentAccount) {
        final PurpleCore.Loaded current = loaded;
        if (current == null || current.exemptFolders.isEmpty()) {
            return null;
        }
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        final ArrayList<TLRPC.Dialog> archived = controller.getDialogs(ARCHIVE_FOLDER_ID);
        if (archived == null || archived.isEmpty()) {
            return null;
        }
        ArrayList<TLRPC.Dialog> result = null;
        for (int a = 0, n = archived.size(); a < n; ++a) {
            final TLRPC.Dialog dialog = archived.get(a);
            if (dialog == null || DialogObject.isFolderDialogId(dialog.id)) {
                continue;
            }
            final int mode = exemptFolderMode(currentAccount, dialog.id);
            if (mode == PurpleCore.MODE_UNSET
                    || !shownForShowMode(currentAccount, dialog, mode)) {
                continue;
            }
            if (result == null) {
                result = new ArrayList<>();
            }
            result.add(dialog);
        }
        return result;
    }

    /**
     * The main list's own order, with one difference: a chat pulled in from the
     * archive is never pinned here.
     *
     * Its {@code pinned} flag is real, but it is a pin inside the Archive, and
     * letting it sort into the main list's pinned run would put it above chats
     * the user actually pinned and lengthen the pinned divider. Otherwise this
     * is {@code MessagesController.dialogComparator}, which is what the list
     * being merged into is already sorted by.
     */
    private static Comparator<TLRPC.Dialog> mergedOrder(int currentAccount) {
        final MediaDataController media = MediaDataController.getInstance(currentAccount);
        return (one, two) -> {
            final boolean folderOne = one instanceof TLRPC.TL_dialogFolder;
            final boolean folderTwo = two instanceof TLRPC.TL_dialogFolder;
            if (folderOne != folderTwo) {
                return folderOne ? -1 : 1;
            }
            final boolean pinnedOne = one.pinned && one.folder_id == 0;
            final boolean pinnedTwo = two.pinned && two.folder_id == 0;
            if (pinnedOne != pinnedTwo) {
                return pinnedOne ? -1 : 1;
            }
            if (pinnedOne) {
                if (one.pinnedNum != two.pinnedNum) {
                    return one.pinnedNum > two.pinnedNum ? -1 : 1;
                }
                return 0;
            }
            final long dateOne = DialogObject.getLastMessageOrDraftDate(
                    one, media.getDraft(one.id, 0));
            final long dateTwo = DialogObject.getLastMessageOrDraftDate(
                    two, media.getDraft(two.id, 0));
            return dateOne == dateTwo ? 0 : (dateOne > dateTwo ? -1 : 1);
        };
    }

    /**
     * The folders the running preset puts on the strip, in the order it named
     * them.
     *
     * A display-only view, which is the whole rule: everything that edits,
     * counts, resolves membership or uploads an order reads
     * {@code MessagesController.getDialogFiltersUnrestricted()} instead, or the
     * folder settings page, the add-to-folder menu and the premium limit all
     * start describing an account the user does not have. Mirrors the desktop
     * fork's {@code ChatFilters::purpleShownList()}.
     *
     * "All chats" always leads. On the desktop it is dropped, because the
     * preset's own main view stands in its place; here the default tab already
     * <i>is</i> that view - {@link #filter} runs on the list it draws - so
     * taking it away would leave nowhere to see the preset at all.
     *
     * A fresh list per call rather than a cached one. The accessor is called
     * from about two dozen event-driven places and none of them draws, so the
     * cost is a small allocation at UI-event rates, and the alternative is an
     * invalidation contract with every folder rename, add, remove and reorder
     * in the app.
     *
     * @param raw the account's real folders; returned as-is when nothing is
     *            restricted, so stock behaviour keeps the live list
     */
    /**
     * Rebuilds the synthetic filters for the preset's extra tabs.
     *
     * A view is a selection you asked for by name, so almost none of a real
     * folder's machinery applies: no flags, no {@code alwaysShow}, no server
     * order. Membership is answered by {@link #viewHolds} out of the packed
     * per-chat value, which is the one hook
     * {@code DialogFilter.includesDialog()} needs.
     */
    private static void rebuildViewFilters(PurpleCore.Loaded next) {
        if (next.views.isEmpty()) {
            if (!viewFilters.isEmpty()) {
                viewFilters = new ArrayList<>();
            }
            return;
        }
        // The list being replaced, read once. Its objects are published - other
        // threads are holding them through the volatile - so nothing here
        // touches one; only the localId is copied out of it.
        final ArrayList<MessagesController.DialogFilter> previous = viewFilters;
        final ArrayList<MessagesController.DialogFilter> result =
                new ArrayList<>(next.views.size());
        boolean incomplete = false;
        for (int i = 0, n = Math.min(next.views.size(), PurpleCore.VIEW_LIMIT); i < n; ++i) {
            final MessagesController.DialogFilter filter = new MessagesController.DialogFilter();
            filter.id = VIEW_ID_BASE + i;
            filter.name = next.views.get(i).name;
            filter.order = i;
            // The tab strip tracks a tab by localId, and a fresh object per
            // reload hands it a number it has never seen - so the strip reads
            // every invented tab as new, its stable-id rescue can never match,
            // and the user is thrown back to the first tab. Reloads happen for
            // a peek, a schedule tick and a list edit as well as a preset
            // switch, so this is most of them. The same view keeps the same
            // number by position, which is what identity means here: view i is
            // the i-th tab the preset asked for.
            if (i < previous.size()) {
                filter.localId = previous.get(i).localId;
            }
            incomplete |= !fillViewPins(filter, next.views.get(i).pinned);
            result.add(filter);
        }
        viewFilters = result;
        viewPinsIncomplete = incomplete;
        FileLog.d("Purple: " + result.size() + " extra view"
                + (result.size() == 1 ? "" : "s") + " on the strip.");
    }

    /**
     * Puts the view's own pinned order into the filter.
     *
     * An extra view <b>owns</b> its order: it is not standing in for All chats,
     * so there is no main-list order to copy, and nothing on the server has
     * heard of the tab. A lower number sorts earlier, which is the order the
     * file lists them in.
     *
     * The file writes bare ids, which have had their type stripped, so the sign
     * has to be recovered before the comparator can match a dialog id. A pin
     * naming a peer this client has not loaded yet cannot be resolved here;
     * saying so is what {@link #refillViewPinsIfNeeded()} needs to know it has
     * work left, because on a cold start that is every pin in the file.
     *
     * @return whether every pin resolved, so the order is the one asked for
     */
    private static boolean fillViewPins(MessagesController.DialogFilter filter, long[] pinned) {
        if (pinned == null || pinned.length == 0) {
            return true;
        }
        boolean complete = true;
        try {
            final MessagesController controller =
                    MessagesController.getInstance(UserConfig.selectedAccount);
            for (int a = 0; a < pinned.length; ++a) {
                final long bare = pinned[a];
                if (bare == 0) {
                    continue;
                }
                final long dialogId;
                if (controller.dialogs_dict.get(bare) != null) {
                    dialogId = bare;
                } else if (controller.dialogs_dict.get(-bare) != null) {
                    dialogId = -bare;
                } else if (controller.getUser(bare) != null) {
                    dialogId = bare;
                } else if (controller.getChat(bare) != null) {
                    dialogId = -bare;
                } else {
                    complete = false;
                    continue;
                }
                filter.pinnedDialogs.put(dialogId, a);
            }
        } catch (Exception e) {
            // The dialog list, being rewritten by the UI thread. An unordered
            // tab is the safe direction, and the next sort fixes it.
            FileLog.e(e, false);
            complete = false;
        }
        return complete;
    }

    /**
     * Fills any extra-view pins that could not be resolved when the settings
     * were read.
     *
     * The gate loads the first time anything asks it a question, which on a
     * cold start is well before the dialog list is in memory, so every pin
     * naming a peer the client had not loaded yet was dropped - and nothing
     * put it back, so the tab stood in date order until the next settings
     * reload. Called from {@code MessagesController.sortDialogs}, which runs
     * whenever the list changes and therefore exactly when those peers arrive,
     * and runs it <i>before</i> the sort reads {@code pinnedDialogs}, so the
     * first list the tab ever draws is already in the right order.
     *
     * Re-putting a pin that already resolved writes the same index back, so
     * there is nothing to clear first and a pin the user added by hand in the
     * meantime survives.
     *
     * Costs one volatile read in the settled case, which is the normal one.
     * A pin naming a peer that never loads - a chat left long ago, an id typed
     * wrong - keeps the flag up and so keeps this walking a handful of ids per
     * sort. That is the right direction: the peer may still arrive, and the
     * walk is a map lookup per pin on a path that already sorts the list.
     */
    public static void refillViewPinsIfNeeded() {
        if (!viewPinsIncomplete) {
            return;
        }
        final PurpleCore.Loaded current = loaded;
        final ArrayList<MessagesController.DialogFilter> filters = viewFilters;
        if (current == null || filters.isEmpty()) {
            viewPinsIncomplete = false;
            return;
        }
        boolean incomplete = false;
        final int n = Math.min(filters.size(), current.views.size());
        for (int i = 0; i < n; ++i) {
            incomplete |= !fillViewPins(filters.get(i), current.views.get(i).pinned);
        }
        viewPinsIncomplete = incomplete;
        if (!incomplete) {
            FileLog.d("Purple: extra view pins resolved.");
        }
    }

    /** Whether this filter is one of the preset's invented tabs. */
    public static boolean isExtraView(MessagesController.DialogFilter filter) {
        return filter != null && filter.id >= VIEW_ID_BASE;
    }

    /**
     * Whether one of the preset's extra tabs holds this chat.
     *
     * The whole answer is a bit in the value {@link #packedFor} already caches,
     * so this costs a map lookup and a mask - which it has to, because it is
     * asked once per chat per sort for every tab that is showing.
     *
     * A view selects membership and nothing else: the unread-watching modes are
     * deliberately not honoured here, and neither are the per-kind defaults. A
     * "P0" tab that emptied itself whenever its chats went quiet would be the
     * opposite of the point. The core enforces that in {@code ViewHolds}; this
     * side only reads the bit it set.
     */
    public static boolean viewHolds(
            int currentAccount, MessagesController.DialogFilter filter, long dialogId) {
        if (!filtering || !isExtraView(filter)) {
            return false;
        }
        if (DialogObject.isFolderDialogId(dialogId)) {
            // A view holds no folders. The Archive row belongs to the main view,
            // as it does to All chats, and there is nothing a list of peers
            // could say that would put it on a tab you invented.
            return false;
        }
        final int index = filter.id - VIEW_ID_BASE;
        if (index < 0 || index >= PurpleCore.VIEW_LIMIT) {
            return false;
        }
        final int packed = packedFor(currentAccount, dialogId);
        return ((packed >>> PurpleCore.VIEW_SHIFT) & (1 << index)) != 0;
    }

    /**
     * How many unread chats an extra tab is showing, for its own badge.
     *
     * Walked rather than accumulated, for the reason every count in this fork is
     * walked: the totals Telegram maintains are summed from buckets a synthetic
     * filter was never counted into, so there is nothing to take from - and the
     * last subtraction in this fork's history drove a badge to -334. Only ever
     * runs when a preset actually declared a view.
     *
     * A view's tab is a running total like any other, so a chat under a "hide
     * until" is left out of it too: the row stays on the tab and keeps its own
     * badge, and the tab stops adding it up. Which is why this asks
     * {@link #countedInTotals} and not {@link #countedForBadge} - an uncounted
     * folder speaks for the launcher icon, not for the tab in front of you.
     */
    public static int viewUnread(int currentAccount, MessagesController.DialogFilter filter) {
        if (!isExtraView(filter)) {
            return filter == null ? 0 : filter.unreadCount;
        }
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        int count = 0;
        try {
            final ArrayList<TLRPC.Dialog> all = controller.getAllDialogs();
            for (int a = 0, n = all.size(); a < n; ++a) {
                final TLRPC.Dialog dialog = all.get(a);
                if (dialog == null || DialogObject.isFolderDialogId(dialog.id)) {
                    continue;
                }
                if (viewHolds(currentAccount, filter, dialog.id)
                        && countedInTotals(currentAccount, dialog.id)
                        && controller.getDialogUnreadCount(dialog) != 0) {
                    ++count;
                }
            }
        } catch (Exception e) {
            // A list the UI thread was rewriting underneath us; the next refresh
            // gets it right, and a wrong number for one frame is not worth a
            // stack trace on a path that draws.
            return count;
        }
        return count;
    }

    /**
     * The number a folder's pill should show, once the preset has had its say.
     *
     * One rule kept in one place, because it is asked in two: the folder strip
     * along the top of the chat list, and the folders popup in the tabs
     * activity. Two copies of it had already drifted apart - the popup was
     * putting a number on a folder the preset had taken out of the counts, and
     * a zero on an invented tab full of unread chats.
     *
     * The default folder passes straight through. All chats is the preset's own
     * view, and the total behind it has already been rewritten by the hooks in
     * the counters themselves.
     *
     * @param stockCount what Telegram would have drawn: the main unread count
     *                   for the default folder, {@code filter.unreadCount} for
     *                   any other
     * @return the number to draw, or zero for no pill at all
     */
    public static int tabCount(
            int currentAccount, MessagesController.DialogFilter filter, int stockCount) {
        if (filter == null || filter.isDefault()) {
            return stockCount;
        }
        // A folder the preset left out of the counts shows no number on its own
        // tab either. For a folder that is background on purpose a count is a
        // number you have already decided not to act on.
        if (!folderCounted(filter)) {
            return 0;
        }
        // An invented tab keeps its own count, because the totals Telegram
        // maintains are summed from buckets it was never counted into - so
        // unreadCount on one is zero rather than wrong, and a badge of zero on
        // a tab full of unread chats reads as broken.
        if (isExtraView(filter)) {
            return viewUnread(currentAccount, filter);
        }
        return stockCount;
    }

    public static ArrayList<MessagesController.DialogFilter> shownFilters(
            ArrayList<MessagesController.DialogFilter> raw) {
        final PurpleCore.Loaded current = loaded;
        if (!filtering || current == null || raw == null || raw.isEmpty()) {
            return raw;
        }
        final ArrayList<MessagesController.DialogFilter> result = new ArrayList<>(raw.size());
        for (int a = 0, n = raw.size(); a < n; ++a) {
            final MessagesController.DialogFilter filter = raw.get(a);
            if (filter != null && filter.isDefault()) {
                result.add(filter);
                break;
            }
        }
        // The preset's own tabs belong next to each other, after its main view
        // and before any folder, rather than scattered through the account's.
        // Not lifted by a peek: a peek suspends hiding, and a view hides
        // nothing - it is a selection asked for by name, and filling it with
        // every chat for two minutes would only take it away.
        final ArrayList<MessagesController.DialogFilter> views = viewFilters;
        result.addAll(views);

        if (current.clock.peeking) {
            // A hidden folder is hidden, so a peek brings it back with
            // everything else - which the desktop spells "*ALL", and which is
            // the account's own order, so the folders go on in the order they
            // arrived.
            for (int a = 0, n = raw.size(); a < n; ++a) {
                final MessagesController.DialogFilter filter = raw.get(a);
                if (filter != null && !filter.isDefault()) {
                    result.add(filter);
                }
            }
            // Logged on the same line as every other answer, and not skipped
            // along with the work: the count is the only readable evidence that
            // the strip followed the peek rather than staying where it was.
            logStrip(result.size(), raw.size() + views.size(), null, true);
            return result;
        }
        StringBuilder missing = null;
        for (int i = 0, count = current.folders.size(); i < count; ++i) {
            final PurpleCore.FolderEntry wanted = current.folders.get(i);
            if (wanted.all) {
                // Every folder the selection does not name elsewhere, in the
                // account's own order, at this position. The parser cannot
                // expand it - it has never heard of a Telegram folder - so it
                // arrives as a marker and is expanded here, which is what keeps
                // its place in the strip meaningful.
                for (int a = 0, n = raw.size(); a < n; ++a) {
                    final MessagesController.DialogFilter filter = raw.get(a);
                    if (filter == null || filter.isDefault() || result.contains(filter)) {
                        continue;
                    }
                    if (!namedElsewhere(current, filter.name)) {
                        result.add(filter);
                    }
                }
                continue;
            }
            if (!wanted.enabled) {
                // Switched off. Still in the selection, so the "*ALL" above
                // skipped it rather than handing it back - which is the whole
                // reason a disabled entry stays in the resolution.
                continue;
            }
            if (!wanted.show) {
                // Named, but deliberately off the strip. Worth being able to
                // say separately from leaving it out.
                continue;
            }
            final MessagesController.DialogFilter found = findByName(raw, wanted.name);
            if (found == null) {
                if (missing == null) {
                    missing = new StringBuilder();
                } else {
                    missing.append(", ");
                }
                missing.append(wanted.name);
            } else if (!result.contains(found)) {
                result.add(found);
            }
        }
        logStrip(result.size(), raw.size() + views.size(), missing, false);
        return result;
    }

    /**
     * Says what the strip came out as, at most once a second.
     *
     * Same reasoning as the hidden-chat count: a folder named slightly wrong
     * looks exactly like one the preset meant to leave out, and the count is
     * often the only readable evidence there is - the tabs themselves are custom
     * views that no UI dump can see.
     */
    private static void logStrip(int shown, int total, StringBuilder missing, boolean peeking) {
        final long now = SystemClock.elapsedRealtime();
        if (now - lastFolderLog < 1000L) {
            return;
        }
        lastFolderLog = now;
        FileLog.d("Purple: folder strip showing " + shown + " of " + total
                + (peeking ? " (peeking)" : "")
                + (missing == null ? "." : ", naming folders that do not exist: " + missing + "."));
    }

    /** Whether some entry other than "*ALL" already claims this folder. */
    private static boolean namedElsewhere(PurpleCore.Loaded current, String name) {
        for (int i = 0, count = current.folders.size(); i < count; ++i) {
            final PurpleCore.FolderEntry entry = current.folders.get(i);
            if (!entry.all && entry.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static MessagesController.DialogFilter findByName(
            ArrayList<MessagesController.DialogFilter> raw, String name) {
        for (int a = 0, n = raw.size(); a < n; ++a) {
            final MessagesController.DialogFilter filter = raw.get(a);
            // The default folder's name is empty and its label is supplied at
            // render time, so it is never matched by name - it is always there
            // already.
            if (filter != null && !filter.isDefault() && name.equalsIgnoreCase(filter.name)) {
                return filter;
            }
        }
        return null;
    }

    /** The cached half of the answer: what the preset says about this chat. */
    private static int packedFor(int currentAccount, long id) {
        synchronized (cacheLock) {
            final Integer cached = modeCache.get(id);
            if (cached != null) {
                return cached;
            }
        }
        final int packed;
        try {
            packed = PurpleCore.visible(bareIdOf(currentAccount, id), kindOf(currentAccount, id));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            // Nothing to decide with, so decide nothing: shown, and allowed to
            // notify. Both halves have to be spelled out - the notify bit being
            // clear is what silences a chat, so a bare SHOW_ALWAYS here would
            // mute the whole account the moment the bridge failed.
            return PurpleCore.SHOW_ALWAYS | PurpleCore.NOTIFY_BIT;
        }
        synchronized (cacheLock) {
            modeCache.put(id, packed);
        }
        return packed;
    }

    /**
     * Finishes a cached show mode against the dialog's unread state. This half
     * is never cached: it moves every time a message arrives or is read.
     */
    private static boolean shownForMode(int currentAccount, TLRPC.Dialog dialog, int packed) {
        return shownForShowMode(
                currentAccount,
                dialog,
                effectiveMode(currentAccount, dialog.id, packed));
    }

    /**
     * The show mode actually in force for this chat.
     *
     * A folder the preset pulls in decides first, because it is the more
     * specific statement: it named this folder, where a list entry named a kind
     * or a set of ids. What it does not decide is <i>when</i> - a folder saying
     * nothing about a mode leaves its chats to the default for what they are.
     * Same order as the desktop fork's {@code History::purpleHiddenByPreset()}.
     */
    private static int effectiveMode(int currentAccount, long dialogId, int packed) {
        final int exempt = exemptFolderMode(currentAccount, dialogId);
        return exempt == PurpleCore.MODE_UNSET
                ? (packed & PurpleCore.SHOW_MASK)
                : exempt;
    }

    private static boolean shownForShowMode(
            int currentAccount, TLRPC.Dialog dialog, int mode) {
        if (mode == PurpleCore.SHOW_ALWAYS) {
            return true;
        }
        if (mode == PurpleCore.SHOW_NEVER) {
            return false;
        }
        if (mode == PurpleCore.SHOW_MENTION) {
            return dialog.unread_mentions_count > 0;
        }
        final boolean unread =
                MessagesController.getInstance(currentAccount).getDialogUnreadCount(dialog) > 0
                        || dialog.unread_mark;
        if (mode == PurpleCore.SHOW_MESSAGE_OR_REACTION) {
            return unread || dialog.unread_reactions_count > 0;
        }
        return unread;
    }

    private static boolean watchesUnread(int mode) {
        return mode == PurpleCore.SHOW_MESSAGE
                || mode == PurpleCore.SHOW_MESSAGE_OR_REACTION
                || mode == PurpleCore.SHOW_MENTION;
    }

    /** One load attempt; null when the bridge could not be called at all. */
    private static PurpleCore.Loaded load(byte[] settings, byte[] state) {
        try {
            return PurpleCore.load(settings, state);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] readSettings() {
        return readFile(PurpleSettings.settingsFile());
    }

    private static byte[] readFile(File file) {
        if (!file.exists() || file.length() > PurpleSettings.MAX_SIZE) {
            return null;
        }
        try {
            return PurpleSettings.readAll(file);
        } catch (IOException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Tells every chat list to rebuild, the way
     * {@code MessagesController.onFilterUpdate} does.
     *
     * sortDialogs, because {@code hide_everywhere_p} is answered there and
     * nowhere else: the lists it builds - the forward picker, the folder tabs,
     * the kind-limited pickers - are the ones a globally hidden chat has to be
     * missing from, and dialogsNeedReload does not rebuild them, it only redraws
     * what they already hold. Everything else this class does is a view over the
     * model and needs no re-sort; this one is a rebuild of derived lists.
     */
    private static void postRefresh() {
        AndroidUtilities.runOnUIThread(() -> {
            dropStaleViewSelections();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; ++a) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                // A no-op while the interface is paused, which is fine: resuming
                // sorts the dialogs itself, before anything can be drawn.
                MessagesController.getInstance(a).sortDialogs(null);
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload, true);
                // Which folders are on the strip is part of the preset too, and
                // the strip is rebuilt from this one signal - the same one a
                // folder edit sends.
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogFiltersUpdated);
                // A preset also decides what is silenced, and the unread
                // counters are built from muted/unmuted buckets, so switching
                // one moves numbers that no chat-list rebuild would touch.
                // Every tab and not only the "exclude muted" ones, because a
                // "hide until" takes its chat out of every running total and
                // a reload is what starts and ends one.
                MessagesStorage.getInstance(a).updateAllFiltersCountersForPurple();
            }
        });
    }

    /**
     * Forgets a selected filter that was one of the preset's invented tabs and
     * is not on the strip any more.
     *
     * A selection is a live object, and the sort below refills whichever one it
     * finds there. A view that the reload took away still answers membership -
     * by id, out of bits nothing sets any more - so it answers "no" for every
     * chat, and the page under it would draw an empty list until the user
     * touched another tab. Cleared before the sort rather than after, so the
     * sort is not spent filling a list nobody can reach.
     *
     * Only the invented tabs. A real folder that goes away is Telegram's own
     * business and it already handles it; a view is ours alone.
     *
     * On the UI thread, because {@code reload()} is not: the first load runs
     * from wherever asked for it, and this reaches into MessagesController.
     */
    private static void dropStaleViewSelections() {
        final ArrayList<MessagesController.DialogFilter> current = viewFilters;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; ++a) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            final MessagesController controller = MessagesController.getInstance(a);
            for (int k = 0; k < controller.selectedDialogFilter.length; ++k) {
                final MessagesController.DialogFilter selected = controller.selectedDialogFilter[k];
                if (selected == null || !isExtraView(selected) || stillOnStrip(current, selected)) {
                    continue;
                }
                controller.selectDialogFilter(null, k);
            }
        }
    }

    /**
     * Whether a view is still one of the strip's, matched by id.
     *
     * By id and not by object, because every reload builds new filters: an
     * object comparison would call every surviving view stale and drop a
     * perfectly good selection on each peek.
     */
    private static boolean stillOnStrip(
            ArrayList<MessagesController.DialogFilter> current,
            MessagesController.DialogFilter selected) {
        for (int a = 0, n = current.size(); a < n; ++a) {
            if (current.get(a).id == selected.id) {
                return true;
            }
        }
        return false;
    }
}
