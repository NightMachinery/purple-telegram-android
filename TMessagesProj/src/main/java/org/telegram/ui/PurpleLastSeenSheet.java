package org.telegram.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.purple.PurpleLastSeen;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Components.MessagePrivateSeenView;

public final class PurpleLastSeenSheet {
    private PurpleLastSeenSheet() {
    }

    public static void bindReadRow(View row, int currentAccount, long dialogId,
            Runnable dismiss, Runnable updated, Theme.ResourcesProvider resourcesProvider) {
        final TLRPC.User user = dialogId > 0
                ? MessagesController.getInstance(currentAccount).getUser(dialogId)
                : null;
        if (PurpleLastSeen.peekEligible(user)) {
            row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 6, 0));
            row.setOnClickListener(v -> MessagePrivateSeenView.showSheet(row.getContext(), currentAccount,
                    dialogId, true, dismiss, updated, resourcesProvider));
        }
    }

    public static void adapt(BottomSheet sheet, TextView headerView, TextView descriptionView,
            ButtonWithCounterView button1, int currentAccount, long dialogId, Runnable dismiss) {
        final TLRPC.User lastSeenUser = dialogId > 0
                ? MessagesController.getInstance(currentAccount).getUser(dialogId)
                : null;
        final boolean purplePeekEnabled = PurpleLastSeen.peekEnabled();
        final boolean purplePeek = PurpleLastSeen.peekEligible(lastSeenUser);
        final String username = lastSeenUser != null ? UserObject.getFirstName(lastSeenUser) : "";
        headerView.setText(LocaleController.getString(R.string.PurpleTradeTitle));
        descriptionView.setText(AndroidUtilities.replaceTags(purplePeek
                ? LocaleController.formatString(R.string.PurpleTradeSheetText, username, PurpleLastSeen.holdSeconds())
                : LocaleController.formatString(purplePeekEnabled
                        ? R.string.PurpleTradeSheetUnavailable
                        : R.string.PurpleTradeSheetOff, username)));
        button1.setText(LocaleController.getString(R.string.PurpleTradeShare), false);
        if (!purplePeek) {
            ((ViewGroup) button1.getParent()).removeView(button1);
        }
    }

    public static boolean onLastSeenButton(BottomSheet sheet, Runnable dismiss,
            int currentAccount, long dialogId) {
        final TLRPC.User user = dialogId > 0
                ? MessagesController.getInstance(currentAccount).getUser(dialogId)
                : null;
        if (!PurpleLastSeen.peekEligible(user)) {
            return true;
        }
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) {
            return true;
        }
        sheet.dismiss();
        if (dismiss != null) {
            dismiss.run();
        }
        PurpleLastSeenTrade.show(fragment, currentAccount, dialogId);
        return true;
    }
}
