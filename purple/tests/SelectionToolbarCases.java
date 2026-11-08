package org.telegram.ui;
import android.view.View;import android.widget.LinearLayout;import android.graphics.drawable.Drawable;import org.telegram.ui.ActionBar.*;import org.telegram.ui.Components.AnimatedTextView;import org.telegram.messenger.LocaleController;
public class SelectionToolbarCases{
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}
 static void gestures(){
  for(boolean open:new boolean[]{false,true})for(int id:new int[]{11,0x70750008})for(int state=0;state<4;state++){
   ActionBarMenu menu=new ActionBarMenu();AnimatedTextView count=new AnimatedTextView();count.setLayoutParams(new LinearLayout.LayoutParams(0,-1));menu.addView(count);
   menu.addItemWithWidth(40,1,48,"Other action");ActionBarMenuItem item=menu.addItemWithWidth(id,1,48,"Native action");int[] calls={0};item.setOnClickListener(v->calls[0]++);
   PurpleSelectionActions.install(menu,count);ActionBarMenuItem more=(ActionBarMenuItem)menu.getChildAt(3);more.layout(0,0,48,48);
   PurpleSelectionActions.measure(menu,48);ActionBarMenuSubItem row=more.popup.get(id);row.layout(0,0,48,48);
   if(state==1)item.setEnabled(false);
   if(state==2)item.setVisibility(View.GONE);
   if(state==3)menu.setVisibility(View.GONE);
   more.showing=open;
   int[] actions={android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP,android.view.MotionEvent.ACTION_CANCEL};
   for(int step=0;step<actions.length;step++){
    more.onTouchEvent(new android.view.MotionEvent(actions[step],24,step==1?64:24));
    org.telegram.messenger.AndroidUtilities.flush();
   }
   check(menu.directClicks.isEmpty()&&calls[0]==0,"tap-only native gesture cannot bypass row guards");
   check(more.showing==open,"native gesture neither opens nor dismisses tap-only popup");
   more.showing=true;row.performClick();
   check(calls[0]==(state==0?1:0),"native and reserved Markdown row taps retain current guards and exact handler");
   check(!more.showing,"row tap dismisses normally including revoked or disabled entry");
  }
  ActionBarMenu control=new ActionBarMenu();ActionBarMenuItem nativeMore=control.addItemWithWidth(99,1,48,"More");nativeMore.layout(0,0,48,48);
  ActionBarMenuSubItem disabled=nativeMore.addSubItem(11,0,null,"Forward",true,false,null);disabled.setEnabled(false);disabled.layout(0,0,48,48);
  nativeMore.setShowSubmenuByMove(true);nativeMore.setLongClickEnabled(false);
  nativeMore.onTouchEvent(new android.view.MotionEvent(0,24,24));
  nativeMore.onTouchEvent(new android.view.MotionEvent(2,24,64));
  check(nativeMore.showing,"control native move opens popup");
  nativeMore.onTouchEvent(new android.view.MotionEvent(2,24,24));nativeMore.onTouchEvent(new android.view.MotionEvent(1,24,24));
  check(control.directClicks.size()==1&&control.directClicks.get(0)==11&&!nativeMore.showing,"control reproduces disabled native drag dispatch and dismissal");
 }
 static void tags(){
  for(boolean premium:new boolean[]{false,true})for(int mask=0;mask<128;mask++){
   ActionBarMenu menu=new ActionBarMenu();AnimatedTextView count=new AnimatedTextView();count.setLayoutParams(new LinearLayout.LayoutParams(0,-1));menu.addView(count);
   ActionBarMenuItem[] items=new ActionBarMenuItem[7];for(int i=0;i<7;i++)items[i]=menu.addItemWithWidth(i,1,48,"Native action "+i);
   ActionBarMenuItem tag=menu.addItemWithWidth(20,1,48,"Tag");PurpleSelectionActions.install(menu,count);
   SelectionTagPolicy policy=new SelectionTagPolicy();policy.actionBar.menu=menu;policy.premium=premium;policy.tagItem=tag;
   policy.editItem=items[0];policy.forwardItem=items[1];policy.saveItem=items[2];policy.copyItem=items[3];policy.deleteItem=items[4];policy.starItem=items[5];policy.shareItem=items[6];
   for(int selection:new int[]{mask,mask^127,mask}){
    for(int i=0;i<7;i++)items[i].setVisibility((selection&(1<<i))!=0?View.VISIBLE:View.GONE);
    for(int width:new int[]{1080,160,160,1080}){
     PurpleSelectionActions.measure(menu,width);policy.update();
     check(PurpleSelectionActions.visible(menu,tag)==(premium&&Integer.bitCount(selection)<4),"actual native tag predicate counts logical seven-action eligibility after selection and width changes");
     check(PurpleSelectionActions.visibleCount(menu,items)==Integer.bitCount(selection),"logical action count remains independent of prior overflow");
    }
   }
   policy.tagItem=null;policy.update();
  }
  ActionBarMenu plain=new ActionBarMenu();ActionBarMenuItem item=new ActionBarMenuItem();check(PurpleSelectionActions.visibleCount(plain,item,null)==1,"unregistered and absent native actions retain visibility policy");
 }
 public static void main(String[] args){
 gestures();tags();
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
