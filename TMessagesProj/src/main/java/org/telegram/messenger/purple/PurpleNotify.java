package org.telegram.messenger.purple;

import org.telegram.messenger.MessageObject;

public final class PurpleNotify {

    private PurpleNotify() {
    }

    public static boolean previewAlways(int account, MessageObject message) {
        return !message.isStoryPush
                && !message.isStoryMentionPush
                && !message.isStoryReactionPush
                && PurpleGate.previewAlways(account, message.getDialogId());
    }

    public static boolean previewAlways(int account, MessageObject message, long dialogId) {
        return !message.isStoryPush
                && !message.isStoryMentionPush
                && !message.isStoryReactionPush
                && PurpleGate.previewAlways(account, dialogId);
    }

    public static boolean previewAlwaysGroup(int account, boolean story, MessageObject last, long dialogId) {
        return !story && last != null && !last.isStoryReactionPush
                && PurpleGate.previewAlways(account, dialogId);
    }

    public static boolean silenced(int account, long dialogId) {
        PurpleGate.ensureLoaded();
        return PurpleGate.silenced(account, dialogId);
    }
}
