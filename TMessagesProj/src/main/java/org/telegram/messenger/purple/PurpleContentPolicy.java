package org.telegram.messenger.purple;

import android.view.View;
import android.view.WindowManager;

import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.Stories.StoriesUtilities;

public final class PurpleContentPolicy {
    private PurpleContentPolicy() {
    }

    public static boolean secureContent(boolean restricted) {
        return false;
    }

    public static int contentWindowFlags(int flags) {
        return flags & ~WindowManager.LayoutParams.FLAG_SECURE;
    }

    public static int galleryVisibility(boolean embedded) {
        return embedded ? View.GONE : View.VISIBLE;
    }

    public static boolean allowScreenshots(boolean allowed) {
        return true;
    }

    public static boolean canSaveStory(int account, TL_stories.StoryItem story) {
        return story != null && !(story instanceof TL_stories.TL_storyItemDeleted)
                && !(story instanceof TL_stories.TL_storyItemSkipped)
                && (story.pinned || !StoriesUtilities.isExpired(account, story));
    }

    public static boolean allowLocalStorySave() {
        return true;
    }

    public static boolean restrictSaving(boolean restricted) {
        return false;
    }
}
