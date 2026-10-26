package org.telegram.ui;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
public class ChatActivity extends org.telegram.ui.ActionBar.BaseFragment {
 public int account;public long dialog=42,migrated=43;public boolean paused;public android.app.Activity activity=new android.app.Activity();
 public final java.util.Map<String,MessageObject> messages=new java.util.HashMap<>();
 public final java.util.Map<Long,MessageObject.GroupedMessages> groups=new java.util.HashMap<>();
 public int getCurrentAccount(){return account;}
 public long getDialogId(){return dialog;}
 public long getMergeDialogId(){return migrated;}
 public boolean isPaused(){return paused;}
 public android.app.Activity getParentActivity(){return activity;}
 public UserConfig getUserConfig(){return UserConfig.getInstance(account);}
 public MessagesController getMessagesController(){return new MessagesController();}
 public ConnectionsManager getConnectionsManager(){return ConnectionsManager.getInstance(account);}
 public int getClassGuid(){return 11;}
 public MessageObject.GroupedMessages getGroup(long id){return groups.get(id);}
 public static CharSequence getMessageCaption(MessageObject message,MessageObject.GroupedMessages group,int[] id){return message.caption;}
 public static CharSequence getMessageContent(MessageObject message,long previous,boolean name){return message.display;}
}
