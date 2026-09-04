/*
 * This is the source code of Purple Telegram for Android.
 *
 * Putting a chat in a Work Mode list, from the chat list rather than by editing
 * settings.toml. Mirrors the desktop fork's purple_list_menu.cpp - see
 * docs/purple/work_mode.md, "Putting a chat in a list".
 *
 * The whole reason this exists: the ids in `members' are not shown anywhere in
 * Telegram's UI, so building a list otherwise means turning on an experimental
 * option, reading a number off a profile, and typing it into the file by hand.
 */

package org.telegram.messenger.purple;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;

public final class PurpleListMenu {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private PurpleListMenu() {
    }

    /**
     * Whether the menu should appear at all.
     *
     * It does not, unless a list has been written, so an unconfigured fork's
     * menus are exactly upstream's.
     */
    public static boolean available() {
        final PurpleCore.Loaded state = PurpleGate.state();
        return state != null && state.listCount > 0;
    }

    /** Every list, and whether this chat is in it. Empty if the file is gone. */
    public static List<PurpleCore.ListEntry> listsFor(int currentAccount, long dialogId) {
        return PurpleCore.listsFor(
                PurpleGate.settingsBytes(),
                PurpleGate.bareIdOf(currentAccount, dialogId));
    }

    /**
     * Adds or removes this chat, and reloads.
     *
     * Reads the file again rather than trusting what the menu was built from:
     * the menu may have been open while an import or an edit landed, and a
     * splice against stale text would write back a file that had moved on.
     *
     * Nothing is written when the splice refuses - an unwritable file, or a
     * list written as an inline table it will not edit a line at a time - so a
     * failure leaves both the file and the running resolution as they were.
     *
     * @return null on success, or a reason to show the user
     */
    public static String toggle(
            int currentAccount,
            long dialogId,
            PurpleCore.ListEntry list,
            boolean add) {
        if (list == null) {
            return "no list";
        }
        final byte[] settings = PurpleGate.settingsBytes();
        if (settings == null) {
            return "settings.toml is missing";
        }
        final long bareId = PurpleGate.bareIdOf(currentAccount, dialogId);
        final PurpleCore.SpliceResult result = PurpleCore.spliceMember(
                settings,
                list.name,
                bareId,
                add,
                titlesJson(currentAccount, list, bareId));
        if (!result.ok()) {
            FileLog.e("Purple: list edit refused: " + result.error);
            return result.error;
        }
        if (!result.changed || result.text == null) {
            // Already how it was asked to be. Not an error, and not worth a
            // write that would only churn the file's timestamp.
            return null;
        }
        if (!PurpleSettings.writeAtomic(
                PurpleSettings.settingsFile(), result.text.getBytes(UTF_8))) {
            return "could not write settings.toml";
        }
        // The file the gate reads has changed under it. The watcher would get
        // there on its own, but only after its quiet period, and a chat the
        // user has just filed should move now.
        PurpleGate.reload("list edit");
        return null;
    }

    /**
     * One line saying what is deciding this chat, or null under Normal.
     *
     * Two halves. <b>Where</b> is the entry that claimed it - the first list in
     * the preset's order whose members or kinds match - or the honest "no list
     * this view names", which is the fall-through: a preset names what gets
     * through, so saying nothing about a chat is saying no.
     *
     * <b>What</b> is what actually happened, which is not the same as what the
     * entry wanted. A chat a folder pulled back in is shown whatever its list
     * said, and a line reading "hidden" over a row sitting in the list is worse
     * than no line at all - so the state comes from the same {@code shown()}
     * the chat list asked, not from the mode alone. A chat held back by a mode
     * says what would bring it back, which is the only useful thing to tell
     * somebody asking why they cannot see it.
     */
    public static String verdictLine(int currentAccount, long dialogId) {
        if (!PurpleGate.filtering()) {
            return null;
        }
        final PurpleCore.Loaded current = PurpleGate.state();
        if (current == null) {
            return null;
        }
        final long bareId = PurpleGate.bareIdOf(currentAccount, dialogId);
        final int kind = PurpleGate.kindOf(currentAccount, dialogId);
        final int packed = PurpleCore.visible(bareId, kind);
        final int mode = packed & PurpleCore.SHOW_MASK;

        final TLRPC.Dialog dialog =
                MessagesController.getInstance(currentAccount).dialogs_dict.get(dialogId);
        final boolean hidden = dialog != null && !PurpleGate.shown(currentAccount, dialog);

        final String state;
        if (hidden) {
            switch (mode) {
            case PurpleCore.SHOW_MESSAGE:
                state = LocaleController.getString(R.string.PurpleVerdictUntilMessage);
                break;
            case PurpleCore.SHOW_MESSAGE_OR_REACTION:
                state = LocaleController.getString(R.string.PurpleVerdictUntilReaction);
                break;
            case PurpleCore.SHOW_MENTION:
                state = LocaleController.getString(R.string.PurpleVerdictUntilMention);
                break;
            default:
                state = LocaleController.getString(R.string.PurpleVerdictHidden);
                break;
            }
        } else if ((packed & PurpleCore.NOTIFY_BIT) != 0) {
            state = LocaleController.getString(R.string.PurpleVerdictShown);
        } else {
            state = LocaleController.getString(R.string.PurpleVerdictSilenced);
        }

        // A chat on screen whose own entry said Never is there because a folder
        // pulled it in, and saying so is the difference between a line that
        // explains the row and one that contradicts it.
        final String folder = (!hidden && mode == PurpleCore.SHOW_NEVER)
                ? LocaleController.getString(R.string.PurpleVerdictByFolder)
                : "";

        final String list = PurpleCore.decider(bareId, kind);
        final String where = (list == null || list.length() == 0)
                ? LocaleController.formatString(R.string.PurpleVerdictNoList, current.title)
                : LocaleController.formatString(R.string.PurpleVerdictInList, list);
        return where + ": " + state + folder;
    }

    /**
     * {@code {"12345":"Some Chat"}} for every id whose line the splice might
     * rewrite: the chat being filed, and everyone already in the list.
     *
     * Names go stale, so they are regenerated from the model on every write
     * rather than read back out of the file and trusted.
     */
    private static String titlesJson(
            int currentAccount, PurpleCore.ListEntry list, long bareId) {
        final StringBuilder json = new StringBuilder("{");
        boolean first = true;
        first = appendTitle(json, currentAccount, bareId, first);
        for (int a = 0; a < list.members.length; ++a) {
            if (list.members[a] != bareId) {
                first = appendTitle(json, currentAccount, list.members[a], first);
            }
        }
        return json.append('}').toString();
    }

    private static boolean appendTitle(
            StringBuilder json, int currentAccount, long bareId, boolean first) {
        final String title = titleOf(currentAccount, bareId);
        if (title == null || title.length() == 0) {
            return first;
        }
        if (!first) {
            json.append(',');
        }
        json.append('"').append(bareId).append("\":");
        appendJsonString(json, title);
        return false;
    }

    /**
     * The chat's display name, from whichever side of the id it turns out to be.
     *
     * A bare id has had its type stripped, so it may name a user or a chat and
     * there is nothing in the number to say which. Users are asked first because
     * a user id and a chat id can collide.
     */
    private static String titleOf(int currentAccount, long bareId) {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        final TLRPC.User user = controller.getUser(bareId);
        if (user != null) {
            return UserObject.getUserName(user);
        }
        final TLRPC.Chat chat = controller.getChat(bareId);
        return chat != null ? chat.title : null;
    }

    /** Enough JSON escaping for a display name, which is arbitrary user text. */
    private static void appendJsonString(StringBuilder out, String value) {
        out.append('"');
        for (int a = 0, n = value.length(); a < n; ++a) {
            final char c = value.charAt(a);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        out.append('"');
    }
}
