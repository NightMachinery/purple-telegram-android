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
import java.util.ArrayList;

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
        return current != null && current.foldersRestricted;
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
        synchronized (cacheLock) {
            modeCache.clear();
        }
        if (next.stateText != null) {
            PurpleState.write(next.stateText.getBytes(UTF_8));
        }
        filtering = !next.normal;
        ++generation;

        if (next.normal) {
            FileLog.d("Purple: normal. (" + reason + ")");
        } else {
            FileLog.d("Purple: preset '" + next.preset + "', " + next.lists + " lists. (" + reason + ")");
        }
        if (!next.ok && !TextUtils.isEmpty(next.error)) {
            FileLog.d("Purple: settings.toml: " + next.error);
        }
        if (next.usedCache) {
            FileLog.d("Purple: using the cached resolution (" + next.cacheReason + ").");
        }

        postRefresh();
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
        return shownForMode(currentAccount, dialog, packedFor(currentAccount, dialog.id));
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
        if (current == null || current.silencedFolders.isEmpty()) {
            // The common case, and free: a preset that silenced no folder never
            // reaches the walk below.
            return false;
        }
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
            // The same unwrap sortDialogs() does before asking a filter: a
            // folder holds an encrypted chat under the user behind it.
            long asked = dialogId;
            if (DialogObject.isEncryptedDialog(asked)) {
                final TLRPC.EncryptedChat encrypted = controller.getEncryptedChat(
                        DialogObject.getEncryptedChatId(asked));
                if (encrypted != null) {
                    asked = encrypted.user_id;
                }
            }
            final AccountInstance account = AccountInstance.getInstance(currentAccount);
            for (int a = 0, n = filters.size(); a < n; ++a) {
                final MessagesController.DialogFilter filter = filters.get(a);
                // The default folder's name is empty and its label is supplied
                // at render time, so it is never matched by name - the same
                // reason findByName() skips it.
                if (filter == null || filter.isDefault()) {
                    continue;
                }
                if (!namedIn(current.silencedFolders, filter.name)) {
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
            if (dialog == null || DialogObject.isFolderDialogId(dialog.id)) {
                show = true;
            } else {
                final int packed = packedFor(currentAccount, dialog.id);
                show = shownForMode(currentAccount, dialog, packed);
                if ((packed & PurpleCore.NOTIFY_BIT) == 0
                        || silencedByFolder(currentAccount, dialog.id)) {
                    ++silenced;
                }
                if (watchesUnread(packed & PurpleCore.SHOW_MASK)) {
                    ++gated;
                    if (show) {
                        ++gatedShowing;
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
                    + silenced + " silenced.");
        }
        return result == null ? source : result;
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
        final long now = SystemClock.elapsedRealtime();
        if (now - lastFolderLog >= 1000L) {
            lastFolderLog = now;
            // Same reasoning as the hidden-chat count: a folder named slightly
            // wrong looks exactly like one the preset meant to leave out.
            FileLog.d("Purple: folder strip showing " + result.size() + " of " + raw.size()
                    + (missing == null ? "." : ", naming folders that do not exist: " + missing + "."));
        }
        return result;
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
        final int mode = packed & PurpleCore.SHOW_MASK;
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
     * Deliberately not sortDialogs: this class never changes the model, only
     * the view of it, so there is nothing to re-sort.
     */
    private static void postRefresh() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; ++a) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload, true);
                // Which folders are on the strip is part of the preset too, and
                // the strip is rebuilt from this one signal - the same one a
                // folder edit sends.
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogFiltersUpdated);
                // A preset also decides what is silenced, and the unread
                // counters are built from muted/unmuted buckets, so switching
                // one moves numbers that no chat-list rebuild would touch.
                // This is the same call the app makes when a global mute
                // setting changes, for the same reason.
                MessagesStorage.getInstance(a).updateMutedDialogsFiltersCounters();
            }
        });
    }
}
