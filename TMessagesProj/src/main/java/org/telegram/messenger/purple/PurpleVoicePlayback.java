package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;

/** Owns one manual voice/round queue and its captured after-voice settings. Main thread only. */
public final class PurpleVoicePlayback {
    public static final int RESUME = 0, UNHEARD = 1, ALL = 2;
    private static final int SKIP = 0, PLAY = 1, STOP = 2;
    private static Queue current;

    private static final class Queue {
        final int account, mode;
        final long dialog;
        final boolean resume;
        final Set<Integer> ids = new HashSet<>();
        boolean closed;

        Queue(MessageObject start, int settings) {
            account = start.currentAccount;
            dialog = start.getDialogId();
            mode = settings & 3;
            resume = (settings & 4) != 0;
            ids.add(start.getId());
            closed = mode == RESUME;
        }

        boolean owns(MessageObject message) {
            return message != null && message.currentAccount == account && message.getDialogId() == dialog
                    && (message.isVoice() || message.isRoundVideo());
        }
    }

    private PurpleVoicePlayback() {}

    public static int mode() {
        return PurpleCore.voiceContinuationSettings() & 3;
    }

    public static boolean resumeMusic() {
        return (PurpleCore.voiceContinuationSettings() & 4) != 0;
    }

    public static String value(int mode) {
        return mode == RESUME ? "resume_music" : mode == ALL ? "all_following" : "unheard";
    }

    /** Uses the same id order, merge and view-once guards as the upstream builder. */
    public static ArrayList<MessageObject> playlist(MessageObject start, List<MessageObject> messages,
            boolean encrypted, boolean unreadOnly, long mergeDialog) {
        if (start == null) return null;
        PurpleVoiceQueue.skipsInPlaylist(start, start);
        current = new Queue(start, PurpleCore.voiceContinuationSettings());
        final ArrayList<MessageObject> result = new ArrayList<>();
        result.add(start);
        if (start.getId() == 0 || current.closed) return result;
        for (int i = messages.size() - 1; i >= 0; i--) {
            final MessageObject candidate = messages.get(i);
            if (candidate.getDialogId() == mergeDialog && start.getDialogId() != mergeDialog) continue;
            if (!current.owns(candidate) || (encrypted ? candidate.getId() >= start.getId() : candidate.getId() <= start.getId())) continue;
            if (candidate.isVoiceOnce() || candidate.isRoundOnce()) continue;
            final int decision = PurpleCore.voiceContinuationDecision(current.mode, candidate.isOut(),
                    candidate.isContentUnread(), !PurpleVoiceQueue.skipsInPlaylist(start, candidate));
            if (decision == STOP) { current.closed = true; break; }
            if (decision == SKIP || unreadOnly && (!candidate.isContentUnread() || candidate.isOut())) continue;
            result.add(candidate);
            current.ids.add(candidate.getId());
        }
        return result;
    }

    public static boolean skipsAppended(MessageObject candidate) {
        if (current == null || !current.owns(candidate)) return PurpleVoiceQueue.skipsAppended(candidate);
        if (current.closed || candidate.isVoiceOnce() || candidate.isRoundOnce()) return true;
        final int decision = PurpleCore.voiceContinuationDecision(current.mode, candidate.isOut(),
                candidate.isContentUnread(), !PurpleVoiceQueue.skipsAppended(candidate));
        if (decision == STOP) current.closed = true;
        if (decision != PLAY) return true;
        current.ids.add(candidate.getId());
        return false;
    }

    /** Runs before upstream restores interrupted music, leaving cleanupPlayer to advance its real mixed playlist. */
    public static boolean advance(MediaController controller) {
        final MessageObject playing = controller.getPlayingMessageObject();
        if (current == null || !current.owns(playing) || !current.ids.contains(playing.getId())
                || controller.hasNoNextVoiceOrRoundVideoMessage()) return false;
        controller.cleanupPlayer(true, false, true, false);
        return true;
    }

    /** cleanupPlayer clears the playing object before starting the next queue item. */
    public static boolean keepsMusic(boolean saved, MessageObject next) {
        return saved && next != null && (next.isVoice() || next.isRoundVideo());
    }

    public static boolean restorePaused() {
        return current != null ? !current.resume : !resumeMusic();
    }
}
