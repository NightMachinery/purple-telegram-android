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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
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
     * The show mode of each dialog, as {@link PurpleCore#visible} packed it. The
     * mode half of the answer only moves when the resolution does, so it is
     * cached; the unread half is recomputed on every pass because it moves all
     * the time.
     */
    private static final LongSparseArray<Integer> modeCache = new LongSparseArray<>();

    private static final Object cacheLock = new Object();

    /** Throttles the per-pass counting line, which a scrolling list would flood. */
    private static volatile long lastCountLog;

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

        final PurpleCore.Loaded next;
        try {
            next = PurpleCore.load(settings, state);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            FileLog.e(e);
            return;
        }
        // A settings.toml that does not parse still comes back resolved - the
        // core falls back to its cached resolution and reports the error
        // alongside it. Only the bridge saying nothing is unrecoverable.
        if (!next.ok && (ERROR_NO_RESULT.equals(next.error) || ERROR_BAD_RESULT.equals(next.error))) {
            FileLog.e("Purple: " + next.error + ", keeping the previous resolution.");
            return;
        }

        loaded = next;
        synchronized (cacheLock) {
            modeCache.clear();
        }
        if (next.stateText != null) {
            PurpleState.write(next.stateText.getBytes(UTF_8));
        }
        filtering = !next.normal;

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
        return shownForMode(currentAccount, dialog, modeOf(currentAccount, dialog));
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
        for (int a = 0; a < count; ++a) {
            final TLRPC.Dialog dialog = source.get(a);
            final boolean show;
            if (dialog == null || DialogObject.isFolderDialogId(dialog.id)) {
                show = true;
            } else {
                final int packed = modeOf(currentAccount, dialog);
                show = shownForMode(currentAccount, dialog, packed);
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
        if (result == null) {
            return source;
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
                    + gated + " unread-gated (" + gatedShowing + " showing).");
        }
        return result;
    }

    /** The cached half of the answer: what the preset says about this chat. */
    private static int modeOf(int currentAccount, TLRPC.Dialog dialog) {
        final long id = dialog.id;
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
            // Nothing to hide with, so hide nothing rather than guess.
            return PurpleCore.SHOW_ALWAYS;
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

    private static byte[] readSettings() {
        final File file = PurpleSettings.settingsFile();
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
            }
        });
    }
}
