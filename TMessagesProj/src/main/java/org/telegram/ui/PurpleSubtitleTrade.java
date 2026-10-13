package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.purple.PurpleLastSeen;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ChatAvatarContainer;

public final class PurpleSubtitleTrade {
    private final ChatAvatarContainer container;
    private long userId;
    private boolean pressed;

    public PurpleSubtitleTrade(ChatAvatarContainer container) {
        this.container = container;
    }

    public void clear() {
        userId = 0;
    }

    public CharSequence decorate(TLRPC.User user, CharSequence status) {
        final int width = container.getMeasuredWidth();
        final int availableWidth = width > 0
                ? width - dp((container.getAvatarImageView().getVisibility() == View.VISIBLE ? 54 : 0) + 16)
                : 0;
        final CharSequence decorated = PurpleLastSeen.decorate(user, status, availableWidth);
        userId = PurpleLastSeen.tappable(user) ? user.id : 0;
        return decorated;
    }

    private boolean onSubtitle(float x, float y) {
        final View view = container.getSubtitleTextView();
        return view != null
                && view.getVisibility() == View.VISIBLE
                && y >= view.getTop()
                && y <= view.getBottom()
                && x >= view.getLeft()
                && x <= view.getLeft() + Math.max(view.getMeasuredWidth(), dp(48));
    }

    public boolean onTouch(MotionEvent event, BaseFragment fragment, int currentAccount,
            Runnable resetPressed) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            pressed = userId != 0
                    && fragment != null
                    && onSubtitle(event.getX(), event.getY());
        } else if (pressed
                && (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL)) {
            final boolean tapped = event.getAction() == MotionEvent.ACTION_UP
                    && onSubtitle(event.getX(), event.getY());
            pressed = false;
            resetPressed.run();
            if (tapped) {
                PurpleLastSeenTrade.show(fragment, currentAccount, userId);
            }
            return true;
        }
        return false;
    }
}
