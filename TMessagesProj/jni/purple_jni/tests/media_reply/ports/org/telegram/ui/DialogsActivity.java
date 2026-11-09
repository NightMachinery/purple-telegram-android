package org.telegram.ui;
public class DialogsActivity extends org.telegram.ui.ActionBar.BaseFragment {
 public static final int DIALOGS_TYPE_FORWARD=3;
 public interface Delegate {boolean select(DialogsActivity fragment,java.util.ArrayList<org.telegram.messenger.MessagesStorage.TopicKey> ids,CharSequence message,boolean param,boolean notify,int schedule,int repeat,java.util.ArrayList<Integer> topics);}
 public Delegate delegate;
 public DialogsActivity(android.os.Bundle args){}
 public void setDelegate(Delegate delegate){this.delegate=delegate;}
 public boolean choose(long id){java.util.ArrayList<org.telegram.messenger.MessagesStorage.TopicKey> ids=new java.util.ArrayList<>();ids.add(new org.telegram.messenger.MessagesStorage.TopicKey(id));return delegate.select(this,ids,null,false,false,0,0,null);}
}
