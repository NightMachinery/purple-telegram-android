package org.telegram.ui.ActionBar;import android.widget.LinearLayout;public class ActionBarMenu extends LinearLayout{
 public ActionBarMenuItem addItemWithWidth(int id,int icon,int width,CharSequence title){ActionBarMenuItem v=new ActionBarMenuItem();v.parent=this;v.setTag(id);v.setContentDescription(title);v.setLayoutParams(new LayoutParams(width,-1));addView(v);return v;}
}
