package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import java.util.function.BooleanSupplier;

public final class PurpleSyncChatSetupUI {
    public interface Actions {
        void tryAgain();
        void useSavedMessages();
        void cancel();
    }
    public static void limit(BaseFragment fragment,int account,BooleanSupplier current,Actions actions) {
        if(!current.getAsBoolean()||fragment.getCurrentAccount()!=account)return;
        fragment.dismissCurrentDialog();
        AndroidUtilities.runOnUIThread(()->{
            if(!current.getAsBoolean()||fragment.getCurrentAccount()!=account||fragment.getParentActivity()==null)return;
            LimitReachedBottomSheet sheet=new LimitReachedBottomSheet(fragment,fragment.getParentActivity(),LimitReachedBottomSheet.TYPE_TO0_MANY_COMMUNITIES,account,null);
            fragment.showDialog(sheet,ignored->offer(fragment,account,current,actions));
        });
    }
    private static void offer(BaseFragment fragment,int account,BooleanSupplier current,Actions actions) {
        if(!current.getAsBoolean()||fragment.getCurrentAccount()!=account||fragment.getParentActivity()==null)return;
        AlertDialog.Builder dialog=new AlertDialog.Builder(fragment.getParentActivity());
        dialog.setTitle(PurpleSyncChatActivity.text("LimitTitle"));dialog.setMessage(PurpleSyncChatActivity.text("LimitAbout"));
        dialog.setPositiveButton(PurpleSyncChatActivity.text("TryAgain"),(ignored,which)->{if(current.getAsBoolean())actions.tryAgain();});
        dialog.setNeutralButton(PurpleSyncChatActivity.text("Saved"),(ignored,which)->{if(current.getAsBoolean())actions.useSavedMessages();});
        dialog.setNegativeButton(LocaleController.getString(R.string.Cancel),(ignored,which)->{if(current.getAsBoolean())actions.cancel();});
        fragment.showDialog(dialog.create());
    }
    private PurpleSyncChatSetupUI() { }
}
