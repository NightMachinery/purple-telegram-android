package org.telegram.messenger.purple;
import android.content.Intent;
import androidx.core.content.pm.*;
import org.telegram.messenger.*;
public final class PurpleDirectShareNavigationCases {
 static int checks;
 static void check(boolean value,String why){++checks;if(!value)throw new AssertionError(why);}
 static void ordinary(Intent i){check(PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,i),"ordinary intent admitted: "+i.getAction());check(PurpleDirectShare.shareRecipient(i)==0,"ordinary intent keeps ordinary dispatch");}
 public static void main(String[] args){
  UserConfig c=UserConfig.getInstance(0);c.active=true;c.user=11;UserConfig.selectedAccount=0;
  for(String action:new String[]{null,"android.intent.action.MAIN","android.intent.action.VIEW","new_dialog","org.telegram.messenger.OPEN_ACCOUNT","voip","voip_answer","com.tmessages.openplayer"})ordinary(new Intent().setAction(action).putExtra("currentAccount",0));
  for(String field:new String[]{"userId","chatId"})ordinary(new Intent().setAction("com.tmessages.openchat_notification").putExtra(field,7L).putExtra("currentAccount",0));
  ordinary(new Intent().setAction("com.tmessages.openchat_notification").putExtra("encId",7).putExtra("currentAccount",0));
  ordinary(new Intent().setAction(Intent.ACTION_SEND));ordinary(new Intent().setAction(Intent.ACTION_SEND_MULTIPLE));
  c.missingLogin=true;ordinary(new Intent().setAction("com.tmessages.openchat_notification").putExtra("userId",7L).putExtra("currentAccount",0));c.missingLogin=false;
  for(long peer:new long[]{7,-7,DialogObject.makeEncryptedDialogId(7)}){
   Intent raw=new Intent().setAction("com.tmessages.openchat_pin");if(peer==7)raw.putExtra("userId",7L);else if(peer==-7)raw.putExtra("chatId",7L);else raw.putExtra("encId",7);
   Intent pin=PurpleDirectShare.bindShortcut(0,peer,raw);check(pin!=null&&PurpleDirectShare.admitShortcut(ApplicationLoader.applicationContext,pin)&&PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,pin),"single-account pin accepted");
   c.lifetime+=10;check(PurpleDirectShare.currentIntent(pin),"process lifetime changes preserve same login");
   PurpleGate.hidden.add("0:"+peer);check(!PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,pin),"newly hidden pin refused");PurpleGate.hidden.clear();check(PurpleDirectShare.currentIntent(pin),"unhidden pin accepted");
   ++c.loginSession;check(!PurpleDirectShare.currentIntent(pin),"same user new login refused");--c.loginSession;
   c.active=false;check(!PurpleDirectShare.currentIntent(pin),"logout refused");c.active=true;
  }
  Intent compose=PurpleDirectShare.bindShortcut(0,0,new Intent().setAction("new_dialog"));check(compose!=null&&PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,compose),"single-account compose accepted");
  Intent bot=PurpleDirectShare.bindShortcut(0,7,new Intent().setAction("com.tmessages.openshortcutbot7").putExtra("botId",7L));check(bot!=null&&PurpleDirectShare.admitShortcut(ApplicationLoader.applicationContext,bot),"bound attached bot accepted");
  for(String action:new String[]{Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE}){
   Intent share=PurpleDirectShare.bindShortcut(0,7,new Intent().setAction(action).putExtra("dialogId",7L).putExtra("hash",SharedConfig.directShareHash));check(PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext,share)&&PurpleDirectShare.shareRecipient(share)==7,"single-account common share recipient");
   SharedConfig.directShare=false;check(PurpleDirectShare.shareRecipient(share)==0,"disabled share retains picker");SharedConfig.directShare=true;
   check(PurpleDirectShare.shareRecipient(new Intent(share).putExtra("hash","obsolete"))==0,"obsolete token cannot select recipient");
   ordinary(PurpleDirectShare.routeShare(new Intent().setAction(action).putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID,"unknown").putExtra("dialogId",7L).putExtra("currentAccount",0)));
  }
  System.out.println("NAVIGATION: "+checks+" assertions PASS");
 }
}
