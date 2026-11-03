/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleSyncChatCore.Peer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.PeerKind;

/**
 * What the Sync chat screen shows for one account under Magic Purple Sync, and which chats the user may pick
 * instead of the magic chat. Pure, so the host tests drive it with real bindings.
 */
public final class PurpleSyncMagicScreen {
    public enum Line { Looking, Using, Waiting, Problem }

    public static final class Frame {
        public final Line line;
        /** Using: the chat sync reads and posts in. Problem: the chat the problem concerns, or null. */
        public final Peer chat;
        public final PurpleSyncMagicCore.Problem problem;
        /** Waiting: unix seconds when sync looks again, 0 when unknown. */
        public final long retryAt;
        /** Waiting: Telegram asked for the wait (a flood wait); any other wait is Purple's own. */
        public final boolean telegram;
        /** The user picked the chat rather than the magic chat. */
        public final boolean manual;
        /** The user chose to see the magic chat in Purple's chat lists. */
        public final boolean listed;
        /** The problem has not been shown yet: the screen shows it once and reports it shown. */
        public final boolean showProblem;

        Frame(Line line, Peer chat, PurpleSyncMagicCore.Problem problem, long retryAt, boolean telegram, boolean manual, boolean listed,
              boolean showProblem) {
            this.line = line; this.chat = chat; this.problem = problem; this.retryAt = retryAt; this.telegram = telegram;
            this.manual = manual; this.listed = listed; this.showProblem = showProblem;
        }
        public boolean savedMessages() { return Peer.self().equals(chat); }
        /** Open the chat: any chat but Saved Messages, which Telegram's own menu reaches. */
        public boolean canOpen() { return chat != null && !savedMessages(); }
        /** The list toggle: only the magic chat is hidden, so only it can be shown again. */
        public boolean canList() { return !manual && chat != null && chat.kind == PeerKind.Channel; }
        public boolean canUseDefault() { return manual; }
        /** Join: the chat holds another sync space's records, and this device may set its own state aside. */
        public boolean canJoin() { return problem == PurpleSyncMagicCore.Problem.SpaceMismatch && chat != null; }
    }

    static Frame frame(PurpleSyncMagicChat.Result result, PurpleSyncMagicCore.Binding binding) {
        boolean manual = binding != null && binding.mode == PurpleSyncMagicCore.Mode.Manual;
        boolean listed = binding != null && binding.listed;
        if (result == null) return new Frame(Line.Looking, null, PurpleSyncMagicCore.Problem.None, 0, false, manual, listed, false);
        switch (result.kind) {
            case Use: return new Frame(Line.Using, result.chat, PurpleSyncMagicCore.Problem.None, 0, false, manual, listed, false);
            case Wait:
                // WHY: while the lists load the lookup is still looking; only a flood wait is worded as Telegram's.
                if (result.loading && !result.telegram) return new Frame(Line.Looking, null, PurpleSyncMagicCore.Problem.None, 0, false, manual, listed, false);
                return new Frame(Line.Waiting, null, PurpleSyncMagicCore.Problem.None, result.retryAt, result.telegram, manual, listed, false);
            case Problem: return new Frame(Line.Problem, result.chat, result.problem, 0, false, manual, listed, result.show);
            default: return new Frame(Line.Looking, null, PurpleSyncMagicCore.Problem.None, 0, false, manual, listed, false);
        }
    }

    public enum Pick { Allowed, NotAlone, Unknown, Unsupported }

    /**
     * Whether the user may sync through {@code chat} instead of the magic chat (owner decision Q2): Saved
     * Messages, or a supergroup the user is in alone. {@code local} is the chat from the local cache with
     * its full info when cached; without a member count the answer is Unknown, so the user opens the chat
     * once and picks again. Lookup checks the rest (creator, admins, rights) as for any manual binding.
     */
    static Pick pick(Peer chat, PurpleSyncMagicCore.Candidate local) {
        if (Peer.self().equals(chat)) return Pick.Allowed;
        if (chat == null || chat.kind != PeerKind.Channel || local == null || local.channelId != chat.id
                || !local.member || !local.megagroup || local.gigagroup) return Pick.Unsupported;
        Long others = local.facts.otherMembers;
        if (others == null) return Pick.Unknown;
        return others == 0 ? Pick.Allowed : Pick.NotAlone;
    }

    private PurpleSyncMagicScreen() { }
}
