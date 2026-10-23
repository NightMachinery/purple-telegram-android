package org.telegram.messenger.purple;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.Stories.StoriesController;

import java.util.ArrayList;

public final class PurpleStories {
    private PurpleStories() {
    }

    public static ArrayList<TL_stories.PeerStories> shownStories(int currentAccount, StoriesController c) {
        if (!PurpleGate.filteringStories(currentAccount)) {
            return c.getDialogListStories();
        }
        final ArrayList<TL_stories.PeerStories> dialogListStories = c.getDialogListStories();
        ArrayList<TL_stories.PeerStories> result = null;
        for (int i = 0, n = dialogListStories.size(); i < n; ++i) {
            final TL_stories.PeerStories peerStories = dialogListStories.get(i);
            if (storyShownOnStrip(currentAccount, c, DialogObject.getPeerDialogId(peerStories.peer))) {
                if (result != null) {
                    result.add(peerStories);
                }
                continue;
            }
            if (result == null) {
                result = new ArrayList<>(dialogListStories.subList(0, i));
            }
        }
        return result == null ? dialogListStories : result;
    }

    public static boolean storyShownOnStrip(int currentAccount, StoriesController c, long dialogId) {
        return PurpleGate.storyShown(
                currentAccount, dialogId, c.hasUnreadStories(dialogId));
    }

    public static boolean storyStripHasStories(int currentAccount, StoriesController c) {
        return (c.getDialogListStories() != null && shownStories(currentAccount, c).size() > 0)
                || (c.hasSelfStories() && selfStoryShown(currentAccount));
    }

    public static boolean storyStripOnlySelf(int currentAccount, StoriesController c) {
        final ArrayList<TL_stories.PeerStories> shown = shownStories(currentAccount, c);
        return c.hasSelfStories() && selfStoryShown(currentAccount) && (shown.isEmpty() || (shown.size() == 1 && DialogObject.getPeerDialogId(shown.get(0).peer) == UserConfig.getInstance(currentAccount).clientUserId));
    }

    public static boolean filteringStoryStrip(int currentAccount) {
        return PurpleGate.filteringStories(currentAccount);
    }

    public static boolean selfStoryShown(int currentAccount) {
        return PurpleGate.addStoryShown(currentAccount);
    }

    public static int titleCount(int currentAccount, StoriesController c, boolean hidden, int size) {
        int totalCount = Math.max(1, Math.max(c.getTotalStoriesCount(hidden), size));
        if (!hidden && PurpleGate.filteringStories(currentAccount)) {
            totalCount = Math.max(1, size);
        }
        return totalCount;
    }
}
