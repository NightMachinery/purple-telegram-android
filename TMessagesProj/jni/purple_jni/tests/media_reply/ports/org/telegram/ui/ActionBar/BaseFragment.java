package org.telegram.ui.ActionBar;
public class BaseFragment {
 protected int currentAccount;
 public boolean isFinished,paused;
 public android.app.Activity activity=new android.app.Activity();
 public android.view.View view=new android.view.View();
 public int getCurrentAccount(){return currentAccount;}
 public void setCurrentAccount(int account){currentAccount=account;}
 public boolean isPaused(){return paused;}
 public android.app.Activity getParentActivity(){return activity;}
 public android.view.View getFragmentView(){return view;}
 public void finishFragment(){isFinished=true;paused=true;}
}
