package org.telegram.ui.ActionBar;import android.view.View;import android.view.MotionEvent;import android.os.Build;import org.telegram.messenger.AndroidUtilities;import android.graphics.drawable.Drawable;import java.util.*;public class ActionBarMenuItem extends View{
 public class Icon{public Drawable getDrawable(){return icon;}}public ActionBarMenu parent;public void setVisibility(int v){if(!org.telegram.ui.PurpleSelectionActions.visibility(parent,this,v))super.setVisibility(v);}public Drawable icon;public final Map<Integer,ActionBarMenuSubItem> popup=new LinkedHashMap<>();public Icon getIconView(){return new Icon();}
 public ActionBarMenuSubItem addSubItem(int id,int res,Drawable d,CharSequence s,boolean dismiss,boolean check,Object provider){ActionBarMenuSubItem p=new ActionBarMenuSubItem();p.setTag(id);p.setContentDescription(s);popup.put(id,p);return p;}public boolean showing;public boolean isSubMenuShowing(){return showing;}public void closeSubMenu(){showing=false;}

 public ActionBarMenu parentMenu;
 public interface Delegate { void onItemClick(int id); }
 public Delegate delegate;
 private boolean longClickEnabled, showSubmenuByMove=true, allowCloseAnimation=true;
 private Runnable showMenuRunnable;
 private View selectedMenuView;
 private final int[] location=new int[2];
 private final android.graphics.Rect rect=new android.graphics.Rect();
 private final PopupWindow popupWindow=new PopupWindow();
 private final PopupLayout popupLayout=new PopupLayout();
 public class PopupWindow {
  public boolean isShowing(){return showing;}
  public void dismiss(){showing=false;}
  public void dismiss(boolean animated){dismiss();}
 }
 public class PopupLayout {
  public int getItemsCount(){return popup.size();}
  public View getItemAt(int index){return new ArrayList<>(popup.values()).get(index);}
  public void getLocationOnScreen(int[] location){location[0]=location[1]=0;}
 }
 public boolean hasSubMenu(){return !popup.isEmpty();}
 public void toggleSubMenu(){showing=!showing;}
 // NATIVE_GESTURE_METHODS
}
