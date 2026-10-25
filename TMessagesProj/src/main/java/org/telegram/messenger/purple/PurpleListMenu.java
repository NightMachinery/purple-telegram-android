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

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.List;

public final class PurpleListMenu {

    private PurpleListMenu() {
    }

    /**
     * Whether the menu should appear at all.
     *
     * Live lists offer membership edits; a non-Normal running resolution
     * offers its verdict and temporary overrides even with no live lists.
     * Normal with no live lists keeps the unconfigured fork's stock menus.
     */
    public static boolean available() {
        final PurpleCore.Loaded state = PurpleGate.state();
        return state != null && (state.listCount > 0 || !state.normal);
    }

    /**
     * The lists for one chat as {@code session} reads them, or null when
     * there is no session for this account or the lists cannot be read, so a
     * caller can tell no lists from a refusal. It used to open a session of
     * its own and answer a refusal with an empty list, which a caller could
     * not tell from a file with no lists.
     */
    public static List<PurpleCore.ListEntry> listsFor(PurpleWriter.Session session, int currentAccount, long dialogId) {
        if (session == null || !session.forAccount(currentAccount)) return null;
        return session.listsOrNull(PurpleGate.bareIdOf(currentAccount, dialogId));
    }

    public static String toggle(
            PurpleWriter.Session session, int currentAccount, long dialogId,
            PurpleCore.ListEntry list, boolean add) {
        if (session == null || !session.forAccount(currentAccount) || list == null) return "list context changed";
        long peer = PurpleGate.bareIdOf(currentAccount, dialogId);
        return session.toggle(list.name, peer, add, titleOf(currentAccount, peer));
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
        if (PurpleWorkMode.stock(currentAccount)) return LocaleController.formatString(R.string.PurpleListsStock, PurpleSettings.accountLabel(currentAccount));
        if (!PurpleGate.filtering(currentAccount)) {
            return null;
        }
        final PurpleCore.Loaded current = PurpleGate.state();
        if (current == null) {
            return null;
        }
        final long bareId = PurpleGate.bareIdOf(currentAccount, dialogId);
        final int kind = PurpleGate.kindOf(currentAccount, dialogId);
        final int packed = PurpleWorkMode.visible(currentAccount, bareId, kind);
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

        final String list = PurpleWorkMode.decider(currentAccount, bareId, kind);
        final String where = (list == null || list.length() == 0)
                ? LocaleController.formatString(R.string.PurpleVerdictNoList, current.title)
                : LocaleController.formatString(R.string.PurpleVerdictInList, list);
        return where + ": " + state + folder;
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

}
