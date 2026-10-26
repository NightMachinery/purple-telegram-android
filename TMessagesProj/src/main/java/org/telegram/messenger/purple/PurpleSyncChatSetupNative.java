package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatSetupNative {
    interface Notifications {
        boolean current(PurpleSyncChatOrigin origin);
        boolean preferences(int account,long dialogId);
        void remove(int account,long dialogId);
        void flags(int account,long dialogId);
        void dialog(int account,long dialogId);
        void changed(int account);
    }
    static boolean muteLocal(PurpleSyncChatOrigin origin,Peer chat,Notifications port) {
        if(origin==null||chat==null||chat.kind!=PeerKind.Channel||!port.current(origin))return false;
        int account=origin.slot;long did=PurpleSyncChatIO.dialogId(chat,origin.userId);
        if(!port.preferences(account,did)||!port.current(origin))return false;
        port.remove(account,did);if(!port.current(origin))return false;
        port.flags(account,did);if(!port.current(origin))return false;
        port.dialog(account,did);if(!port.current(origin))return false;
        port.changed(account);return port.current(origin);
    }
    static Notifications notifications(PurpleSyncChatOrigin origin,BooleanSupplier current) {
        return new Notifications() {
            public boolean current(PurpleSyncChatOrigin expected) { return origin.equals(expected)&&current.getAsBoolean(); }
            public boolean preferences(int account,long did) {
                return account==origin.slot&&current.getAsBoolean()&&MessagesController.getNotificationsSettings(account).edit()
                        .putInt("notify2_"+NotificationsController.getSharedPrefKey(did,0),2).commit();
            }
            public void remove(int account,long did) { if(account==origin.slot&&current.getAsBoolean())NotificationsController.getInstance(account).removeNotificationsForDialog(did); }
            public void flags(int account,long did) { if(account==origin.slot&&current.getAsBoolean())MessagesStorage.getInstance(account).setDialogFlags(did,1); }
            public void dialog(int account,long did) {
                if(account!=origin.slot||!current.getAsBoolean())return;
                TLRPC.Dialog dialog=MessagesController.getInstance(account).dialogs_dict.get(did);
                if(dialog!=null){if(dialog.notify_settings==null)dialog.notify_settings=new TLRPC.TL_peerNotifySettings();dialog.notify_settings.mute_until=Integer.MAX_VALUE;}
            }
            public void changed(int account) { if(account==origin.slot&&current.getAsBoolean())NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.notificationsSettingsUpdated); }
        };
    }
    static void updates(PurpleSyncChatOrigin origin,TLRPC.Updates updates,BooleanSupplier current,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done) {
        Utilities.stageQueue.postRunnable(()->{
            boolean applied=false;
            if(current.getAsBoolean()&&updates!=null) {
                try {
                    MessagesController controller=MessagesController.getInstance(origin.slot);
                    controller.processUpdates(updates,false);applied=current.getAsBoolean();
                } catch(RuntimeException|LinkageError error) { applied=false; }
            }
            boolean result=applied;
            AndroidUtilities.runOnUIThread(()->{if(current.getAsBoolean()) {
                MessagesController controller=MessagesController.getInstance(origin.slot);
                if(result){controller.putUsers(updates.users,false);controller.putChats(updates.chats,false);}
                if(current.getAsBoolean())done.accept(new PurpleSyncChatSetup.Reply<>(origin,result?PurpleSyncChatSetup.Error.None:PurpleSyncChatSetup.Error.Unknown,result));
            }});
        });
    }
    private PurpleSyncChatSetupNative() { }
}
