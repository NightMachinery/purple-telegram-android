"""Run the complete recording strip with framework RTL deliberately unsupported."""


def prepare(root, put):
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    put('org/telegram/ui/PurpleRecordingStripView.java', (java/'ui/PurpleRecordingStripView.java').read_text())
    put('android/content/SharedPreferences.java', 'package android.content;public class SharedPreferences{public boolean getBoolean(String k,boolean d){return true;}public SharedPreferences edit(){return this;}public SharedPreferences putBoolean(String k,boolean b){return this;}public void apply(){}}')
    put('android/graphics/Paint.java', 'package android.graphics;public class Paint{public static final int ANTI_ALIAS_FLAG=1;public Paint(int f){}public void setColor(int c){}public void setStrokeWidth(int n){}}')
    put('android/os/SystemClock.java', 'package android.os;public class SystemClock{public static long elapsedRealtime(){return 1000;}}')
    put('android/text/format/DateUtils.java', 'package android.text.format;public class DateUtils{public static String formatElapsedTime(long n){return "0:01";}}')
    put('android/widget/Toast.java', 'package android.widget;import android.content.Context;public class Toast{public static final int LENGTH_LONG=1;public static Toast makeText(Context c,String t,int n){return new Toast();}public void show(){}}')
    put('androidx/core/app/NotificationManagerCompat.java', 'package androidx.core.app;import android.content.Context;public class NotificationManagerCompat{public static NotificationManagerCompat from(Context c){return new NotificationManagerCompat();}public boolean areNotificationsEnabled(){return true;}}')
    put('org/telegram/ui/Components/BackupImageView.java', 'package org.telegram.ui.Components;import android.content.Context;import android.view.View;public class BackupImageView extends View{public BackupImageView(Context c){super(c);}public void setRoundRadius(int r){}public void setForUserOrChat(Object p,Object d){}public void setImageDrawable(Object d){}}')
    put('org/telegram/ui/Components/AvatarDrawable.java', 'package org.telegram.ui.Components;public class AvatarDrawable{public static final int AVATAR_TYPE_ANONYMOUS=1;public void setAvatarType(int t){}public void setInfo(int a,Object p){}}')
    put('org/telegram/tgnet/TLObject.java', 'package org.telegram.tgnet;public class TLObject{}')
    put('org/telegram/tgnet/TLRPC.java', 'package org.telegram.tgnet;public class TLRPC{public static class EncryptedChat extends TLObject{public long user_id;}}')
    put('org/telegram/messenger/DialogObject.java', 'package org.telegram.messenger;public class DialogObject{public static boolean isEncryptedDialog(long d){return false;}public static int getEncryptedChatId(long d){return 0;}}')
    put('org/telegram/messenger/MessagesController.java', 'package org.telegram.messenger;import org.telegram.tgnet.*;public class MessagesController{public static MessagesController getInstance(int a){return new MessagesController();}public TLRPC.EncryptedChat getEncryptedChat(int i){return null;}public TLObject getUser(long d){return null;}public TLObject getChat(long d){return null;}}')
    put('org/telegram/messenger/purple/PurpleGate.java', 'package org.telegram.messenger.purple;public class PurpleGate{public static boolean hiddenEverywhere(int a,long d){return false;}}')
    put('org/telegram/messenger/purple/PurpleRecordingDockState.java', 'package org.telegram.messenger.purple;public class PurpleRecordingDockState{public long generation=1,dialog=1;public int account,levelIndex;public float[] levels=new float[64];public boolean finishing,paused,round,active=true;public boolean active(){return active;}public long duration(long n){return n;}}')
    put('org/telegram/messenger/purple/PurpleRecordingDock.java', 'package org.telegram.messenger.purple;public class PurpleRecordingDock{public static boolean refuse;public static boolean refuseStart(){return refuse;}public static final PurpleRecordingDockState state=new PurpleRecordingDockState();public static String title="Long Latin title";public static String title(){return title;}public static void open(){}public static void preview(long g){}public static void pause(long g){}public static void send(long g){}public static void discard(long g){}public static boolean unavailable(){return false;}}')
    put('org/telegram/ui/ActionBar/AlertDialog.java', 'package org.telegram.ui.ActionBar;import android.content.Context;public class AlertDialog{public interface Click{void click(Object d,int w);}public static class Builder{public Builder(Context c,Theme.ResourcesProvider r){}public Builder setTitle(String s){return this;}public Builder setMessage(String s){return this;}public Builder setPositiveButton(String s,Click c){return this;}public Builder setNegativeButton(String s,Click c){return this;}public void show(){}}}')
    put('RecordingStripRtlCases.java', '''import android.content.Context;import android.view.*;import android.widget.*;import org.telegram.ui.*;import org.telegram.messenger.*;import org.telegram.messenger.purple.*;
public class RecordingStripRtlCases{
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}
 static View field(PurpleRecordingStripView row,String name)throws Exception{java.lang.reflect.Field f=PurpleRecordingStripView.class.getDeclaredField(name);f.setAccessible(true);return (View)f.get(row);}
 public static void main(String[] args)throws Exception{
 for(boolean initial:new boolean[]{false,true})for(String title:new String[]{"Long Latin title","عنوان طويل","Mixed عنوان"}){
  LocaleController.isRTL=initial;PurpleRecordingDock.title=title;
  PurpleRecordingStripView row=new PurpleRecordingStripView(new Context(),null);
  String[] names={"avatar","target","timer","waveform","pause","send","discard"};View[] logical=new View[7];
  for(int i=0;i<7;i++)logical[i]=field(row,names[i]);
  for(boolean rtl:new boolean[]{initial,!initial,initial})for(boolean active:new boolean[]{false,true}){
   LocaleController.isRTL=rtl;PurpleRecordingDock.state.active=active;
   row.refresh();row.measure(360,48);
   check(row.getChildCount()==7,"locale reuse retains every control");
   for(int i=0;i<7;i++)check(row.getChildAt(i)==logical[rtl?6-i:i],"explicit physical order independent of framework RTL: "+names[i]);
   check(row.padLeft==(rtl?4:7)&&row.padRight==(rtl?7:4),"stock inset mirrored physically");
   LinearLayout.LayoutParams avatar=(LinearLayout.LayoutParams)logical[0].getLayoutParams();
   check(avatar.leftMargin==(rtl?7:0)&&avatar.rightMargin==(rtl?0:7),"avatar gap remains between avatar and title");
   TextView target=(TextView)logical[1],timer=(TextView)logical[2];
   check((target.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==(rtl?Gravity.RIGHT:Gravity.LEFT),"title anchored toward avatar in both locales");
   check(target.getTextAlignment()==View.TEXT_ALIGNMENT_GRAVITY&&target.getTextDirection()==0,"native bidi with explicit physical title anchor");
   check(timer.getTextDirection()==View.TEXT_DIRECTION_LTR&&(timer.getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.LEFT,"timer text and digits stay LTR");
   check(((LinearLayout.LayoutParams)target.getLayoutParams()).weight==1&&target.getLayoutParams().width==0,"long title retains weighted space");
   check(logical[0].getLayoutParams().width==28&&logical[2].getLayoutParams().width==56&&logical[3].getLayoutParams().width==36,"avatar timer waveform sizes retained");
   for(int i=4;i<7;i++)check(logical[i].getLayoutParams().width==36&&logical[i].getLayoutParams().height==36,"native action targets retained");
   if(active)check(target.text.equals(title)&&timer.text.equals("0:01"),"dynamic title and elapsed text preserved");
  }
 }
 System.out.println("RECORDING_STRIP_RTL_CHECKS="+checks);}
}''')
