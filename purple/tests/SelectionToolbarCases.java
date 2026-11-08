package org.telegram.ui;
import android.view.View;import android.widget.LinearLayout;import android.graphics.drawable.Drawable;import org.telegram.ui.ActionBar.*;import org.telegram.ui.Components.AnimatedTextView;import org.telegram.messenger.LocaleController;
public class SelectionToolbarCases{
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}
 public static void main(String[] args){
 for(boolean rtl:new boolean[]{false,true})for(boolean dark:new boolean[]{false,true})for(int width:new int[]{240,320,360,406,600,1080})for(int text:new int[]{0,60,128,300})for(int mask=1;mask<128;mask++){
  LocaleController.isRTL=rtl;ActionBarMenu menu=new ActionBarMenu();AnimatedTextView count=new AnimatedTextView();count.width=text;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1);lp.leftMargin=85;lp.rightMargin=5;count.setLayoutParams(lp);menu.addView(count);
  int[] clicks=new int[7];ActionBarMenuItem[] items=new ActionBarMenuItem[7];
  for(int i=0;i<7;i++){final int id=i;items[i]=menu.addItemWithWidth(i,1,48,"Action "+i);items[i].icon=new Drawable(dark?0xffffffff:0xff222222);items[i].setOnClickListener(v->clicks[id]++);}
  PurpleSelectionActions.install(menu,count);ActionBarMenuItem more=(ActionBarMenuItem)menu.getChildAt(8);
  for(int i=0;i<7;i++)items[i].setVisibility((mask&(1<<i))!=0?View.VISIBLE:View.GONE);
  for(int pass=0;pass<2;pass++){
   PurpleSelectionActions.measure(menu,pass==0?width:1080);
   int used=90;
   for(int i=0;i<7;i++){
    ActionBarMenuSubItem popup=more.popup.get(i);boolean direct=items[i].getVisibility()==View.VISIBLE,overflow=popup.getVisibility()==View.VISIBLE,eligible=(mask&(1<<i))!=0;
    check(PurpleSelectionActions.visible(menu,items[i])==eligible,"Markdown eligibility reads logical native Copy visibility");
    check((direct||overflow)==eligible&&!(direct&&overflow),"eligible action has exactly one reachable native presentation");
    check(items[i].icon.color==(dark?0xffffffff:0xff222222),"native theme tint preserved");
    check(popup.getContentDescription().equals(items[i].getContentDescription()),"native accessible labels preserved");
    LinearLayout.LayoutParams bounds=(LinearLayout.LayoutParams)items[i].getLayoutParams();check(bounds.width==48&&bounds.leftMargin==0&&bounds.rightMargin==0,"48dp action target without overlapping margins");
    if(direct)used+=48;
    if(eligible){if(direct)items[i].performClick();else {more.showing=true;popup.performClick();}check(clicks[i]==pass+1,"overflow retains exact original click handler");}
   }
   if(more.getVisibility()==View.VISIBLE)used+=48;
   check(used+48<= (pass==0?width:1080),"count remains usable beside full size actions");
  }
  PurpleSelectionActions.measure(menu,160);int moved=-1;for(int i=0;i<7;i++)if(more.popup.get(i).getVisibility()==View.VISIBLE){moved=i;break;}
  if(moved>=0){int before=clicks[moved];items[moved].setVisibility(View.GONE);more.showing=true;more.popup.get(moved).performClick();check(clicks[moved]==before,"revoked popup cannot invoke native handler");}
 }
 ActionBarMenu plain=new ActionBarMenu();check(!PurpleSelectionActions.visibility(plain,new ActionBarMenuItem(),View.GONE),"unrelated menus retain native visibility path");PurpleSelectionActions.measure(plain,1);
 System.out.println("SELECTION_TOOLBAR_CHECKS="+checks);
 }
}
