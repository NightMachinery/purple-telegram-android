"""Execute owned long review/prepare/finish with extracted native dialog dispatch."""
import pathlib, runpy, subprocess, os, re, json, xml.etree.ElementTree as ET
method=runpy.run_path(str(pathlib.Path(__file__).with_name('bubble_routing.py')))['method']

def run(root, output, mode='all', production_root=None):
    output.mkdir(parents=True,exist_ok=False)
    java=root/'TMessagesProj/src/main/java/org/telegram'
    production_java=(production_root or root)/'TMessagesProj/src/main/java/org/telegram'
    source=(production_java/'ui/PurpleLongRoundSend.java').read_text()
    native=(production_java/'ui/ActionBar/BaseFragment.java').read_text()
    def put(name,text):
        p=output/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    resources={n:i+1 for i,n in enumerate(sorted(set(re.findall(r'R.string.(\w+)',source))))}
    xml={n.attrib['name']:''.join(n.itertext()) for n in ET.parse(root/'TMessagesProj/src/main/res/values/purple_strings.xml').getroot() if n.tag=='string'}
    xml.update(Cancel='Cancel',Send='Send',OK='OK')
    put('org/telegram/messenger/R.java','package org.telegram.messenger;public class R{public static class string{'+''.join('public static final int '+n+'='+str(i)+';' for n,i in resources.items())+'}}')
    put('org/telegram/messenger/LocaleController.java','package org.telegram.messenger;public class LocaleController {public static String getString(int id){switch(id){'+''.join('case '+str(i)+':return '+json.dumps(xml[n])+';' for n,i in resources.items())+'default:throw new AssertionError();}}public static String formatString(int id,Object... args){return String.format(java.util.Locale.ROOT,getString(id),args);}}')
    put('org/telegram/messenger/AndroidUtilities.java','package org.telegram.messenger;public class AndroidUtilities{public static void runOnUIThread(Runnable r){r.run();}public static void runOnUIThread(Runnable r,long delay){}}')
    put('org/telegram/messenger/SendMessageChatArguments.java','package org.telegram.messenger;public class SendMessageChatArguments{}')
    put('org/telegram/messenger/FileLog.java','package org.telegram.messenger;public class FileLog{public static void e(Exception e){}}')
    put('org/telegram/messenger/FileLoader.java','package org.telegram.messenger;public class FileLoader{public static final int MEDIA_DIR_DOCUMENT=1;public static java.io.File getDirectory(int k){return new java.io.File("synthetic-output");}}')
    put('org/telegram/messenger/purple/PurpleRecordingEntry.java','package org.telegram.messenger.purple;public class PurpleRecordingEntry{public String id="source",media="retained-original.mp4";}')
    put('org/telegram/messenger/purple/PurpleRecordingSendAs.java','package org.telegram.messenger.purple;import org.telegram.messenger.*;public class PurpleRecordingSendAs{public static boolean valid=true;public static boolean sameUser(int a,long u){return valid;}public static SendMessageChatArguments copyFrozen(int a,long u,long d,SendMessageChatArguments original){return valid?new SendMessageChatArguments():null;}}')
    put('org/telegram/messenger/purple/PurpleRecordingDelivery.java','package org.telegram.messenger.purple;public class PurpleRecordingDelivery{public static int revokes;public static void revoke(String id){revokes++;}}')
    put('org/telegram/messenger/purple/PurpleDiagLog.java','package org.telegram.messenger.purple;public class PurpleDiagLog{public static String reason(String s){return s;}public static void event(String a,String b,String c){}}')
    put('org/telegram/messenger/purple/PurpleLongRoundSelection.java','package org.telegram.messenger.purple;public class PurpleLongRoundSelection{public static class Invalid extends RuntimeException{}}')
    put('org/telegram/messenger/purple/PurpleLongRoundMedia.java','''package org.telegram.messenger.purple;
      import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import java.io.File;
      public class PurpleLongRoundMedia {
       public static int count=3,calls;public static boolean fail,invalid,lastCircles;
       public static class Output{public boolean circle;Output(boolean c){circle=c;}}
       public static class Work{public final AtomicBoolean cancelled=new AtomicBoolean();public int discards;public void discard(){discards++;}}
       public static List<Output> prepare(File a,File b,boolean circles,long start,long end,Work work){
        calls++;lastCircles=circles;if(work.cancelled.get()||fail)throw new IllegalStateException();if(invalid)throw new PurpleLongRoundSelection.Invalid();
        ArrayList<Output> result=new ArrayList<>();for(int i=0;i<(circles?count:1);i++)result.add(new Output(circles));return result;}
      }''')
    put('android/content/DialogInterface.java','''package android.content;public interface DialogInterface{
      interface OnDismissListener{void onDismiss(DialogInterface d);}interface OnCancelListener{void onCancel(DialogInterface d);}
      interface OnClickListener{void onClick(DialogInterface d,int which);}}''')
    put('android/app/Dialog.java','''package android.app;import android.content.DialogInterface;
      public class Dialog implements DialogInterface{
       public static boolean failShow;public OnDismissListener dismiss;public OnCancelListener cancel;public boolean shown,outside;
       public void setOnDismissListener(OnDismissListener l){dismiss=l;}public void setOnCancelListener(OnCancelListener l){cancel=l;}
       public void setCanceledOnTouchOutside(boolean b){outside=b;}public void show(){if(failShow)throw new IllegalStateException();shown=true;}
       public void dismiss(){if(!shown)return;shown=false;if(dismiss!=null)dismiss.onDismiss(this);}
       public void cancel(){if(cancel!=null)cancel.onCancel(this);dismiss();}
      }''')
    overloads='\n'.join(method(native,signature) for signature in [
        '    public Dialog showDialog(Dialog dialog)',
        '    public Dialog showDialog(Dialog dialog, Dialog.OnDismissListener onDismissListener)',
        '    public Dialog showDialog(Dialog dialog, boolean allowInTransition, final Dialog.OnDismissListener onDismissListener)'])
    assert 'visibleDialog.setOnDismissListener' in overloads and 'onDismissListener.onDismiss' in overloads
    put('org/telegram/ui/ActionBar/BaseFragment.java','''package org.telegram.ui.ActionBar;
      import android.app.Dialog;import java.util.*;import org.telegram.messenger.FileLog;
      public class BaseFragment{
       public boolean paused;public Dialog visibleDialog;public int nativeDismissals;public Parent parentLayout=new Parent();public ArrayList<Sheet> sheetsStack;
       public static class Parent{public boolean refuse;public boolean isTransitionAnimationInProgress(){return refuse;}public boolean isSwipeInProgress(){return false;}public boolean checkTransitionAnimation(){return false;}}
       public static class Sheet{public static Dialog shown;public boolean isShown(){return true;}public boolean showDialog(Dialog d){shown=d;d.show();return true;}}
       public Object getParentActivity(){return new Object();}public Object getResourceProvider(){return null;}public boolean isPaused(){return paused;}
       protected void onDialogDismiss(Dialog d){nativeDismissals++;}
      '''+overloads+'}')
    put('org/telegram/ui/ActionBar/AlertDialog.java','''package org.telegram.ui.ActionBar;import android.app.Dialog;import android.content.DialogInterface;
      public class AlertDialog extends Dialog{
       public static final int ALERT_TYPE_SPINNER=1;public static Builder latest;public Builder builder;public String message;
       public AlertDialog(Object a,int type,Object resources){}public void setMessage(String m){message=m;}
       public void positive(){if(builder.positive!=null)builder.positive.onClick(this,1);dismiss();}
       public void neutral(){if(builder.neutral!=null)builder.neutral.onClick(this,0);dismiss();}
       public static class Builder{public DialogInterface.OnClickListener positive,neutral;public DialogInterface.OnDismissListener dismiss;public String message,primary;
        public Builder(Object a,Object b){latest=this;}public Builder setTitle(String s){return this;}public Builder setMessage(String s){message=s;return this;}
        public Builder setNegativeButton(String s,DialogInterface.OnClickListener c){return this;}public Builder setNeutralButton(String s,DialogInterface.OnClickListener c){neutral=c;return this;}
        public Builder setPositiveButton(String s,DialogInterface.OnClickListener c){primary=s;positive=c;return this;}public Builder setOnDismissListener(DialogInterface.OnDismissListener d){dismiss=d;return this;}
        public AlertDialog create(){AlertDialog d=new AlertDialog(null,0,null);d.builder=this;d.message=message;d.setOnDismissListener(dismiss);return d;}
        public AlertDialog show(){AlertDialog d=create();d.show();return d;}}
      }''')
    put('org/telegram/ui/Components/AlertsCreator.java','''package org.telegram.ui.Components;public class AlertsCreator{public interface Paid{void run(long p);}public static Paid pending;public static int count;public static void dismissNative(){}public static void ensurePaidMessageConfirmation(int a,long d,int n,Paid paid,long stars){count=n;pending=paid;}}''')
    put('org/telegram/messenger/purple/PurpleLongRoundQueue.java',(java/'messenger/purple/PurpleLongRoundQueue.java').read_text())
    methods='\n'.join(method(source,sig) for sig in ['    public static void review(', '        Request(BaseFragment host,','        BaseFragment host()','        void prepare(boolean circles)','        void preparationFailed(boolean circles, boolean invalidSelection)','        void confirm()','        boolean replaceablePayment(','        void finish(boolean error)'])
    review=method(source,'    public static void review(')
    methods=methods.replace(review,'',1)
    if '        boolean showOwnedDialog(' in source:methods+=method(source,'        boolean showOwnedDialog(')
    put('org/telegram/ui/LongChoices.java','''package org.telegram.ui;
      import java.io.File;import java.util.*;import java.lang.ref.WeakReference;import java.util.concurrent.atomic.AtomicBoolean;
      import android.content.DialogInterface;import org.telegram.messenger.*;import org.telegram.messenger.purple.*;import org.telegram.ui.ActionBar.*;import org.telegram.ui.Components.*;
      public class LongChoices{
       static final Map<String,Request> active=new HashMap<>();public interface Sender{}
       static class Tasks{ArrayDeque<Runnable> tasks=new ArrayDeque<>();void execute(Runnable r){tasks.add(r);}void drain(){while(!tasks.isEmpty())tasks.remove().run();}}
       static Tasks worker=new Tasks();static String text(int r){return LocaleController.getString(r);}
      '''+review+'''
       static class Request{
        final WeakReference<BaseFragment> owner;final PurpleRecordingEntry item;final int account;final long user,dialog,startUs,endUs;
        final Sender sender;final Runnable done;final SendMessageChatArguments[] arguments=new SendMessageChatArguments[6];
        final PurpleLongRoundMedia.Work work=new PurpleLongRoundMedia.Work();
        List<PurpleLongRoundMedia.Output> outputs;PurpleLongRoundQueue queue;AlertDialog progress;
        boolean closed,preparing,awaitingPayment;int paymentGeneration,enqueues;
      '''+methods+'''
        void enqueue(long paid){enqueues++;}
       }
       static int checks,payments,lifecycle;
       static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
       static void life(boolean ok,String why){lifecycle++;check(ok,why);}
       static void payment(boolean ok,String why){payments++;check(ok,why);}
       static void reset(){for(Request r:new ArrayList<>(active.values()))r.finish(false);worker.drain();active.clear();PurpleRecordingSendAs.valid=true;PurpleLongRoundMedia.fail=false;PurpleLongRoundMedia.invalid=false;android.app.Dialog.failShow=false;}
       static Request begin(BaseFragment host,PurpleRecordingEntry item){review(host,item,1,9,100,new SendMessageChatArguments(),null,()->{},-1,-1);return active.get(item.id);}
       static AlertDialog visible(BaseFragment host){return (AlertDialog)host.visibleDialog;}
       static Request ready(BaseFragment host,PurpleRecordingEntry item){Request r=begin(host,item);worker.drain();return r;}
       static void cancelControl(boolean failure){reset();BaseFragment host=new BaseFragment();PurpleRecordingEntry item=new PurpleRecordingEntry();PurpleLongRoundMedia.fail=failure;
        Request r=ready(host,item);AlertDialog shown=visible(host);check(shown!=null&&shown.shown,"actual native review/failure dialog shown");shown.dismiss();
        check(r.closed&&active.isEmpty()&&r.enqueues==0,failure?"preparation failure dismissal releases active request and permits retry":"native review cancel releases active request and permits retry");
        PurpleLongRoundMedia.fail=false;Request retry=begin(host,item);check(retry!=null&&retry!=r,"same entry can immediately retry after native dismissal");reset();}
       public static void main(String[] args){
        if(args.length>0&&!args[0].equals("all")){cancelControl(args[0].equals("failure"));return;}
        cancelControl(false);cancelControl(true);
        reset();BaseFragment host=new BaseFragment();PurpleRecordingEntry item=new PurpleRecordingEntry();Request request=begin(host,item);
        check(request.preparing&&request.enqueues==0&&worker.tasks.size()==1,"normal Send prepares default circles without picker or send");
        worker.drain();check(PurpleLongRoundMedia.lastCircles,"default preparation selects circles");AlertDialog review=visible(host);
        check(review.builder.primary.contains("3")&&review.message.contains("3")&&review.message.contains("full original"),"primary review shows count and retained original");
        review.neutral();check(!request.closed&&request.preparing&&request.enqueues==0,"neutral choice starts preparation without send or cancelling replacement");worker.drain();
        check(!PurpleLongRoundMedia.lastCircles&&request.outputs.size()==1,"ordinary video is deliberate secondary preparation");visible(host).dismiss();check(request.closed&&request.enqueues==0,"review cancel preserves unsent state");
        reset();host=new BaseFragment();request=ready(host,item);visible(host).positive();
        check(AlertsCreator.count==3&&request.enqueues==0&&!request.closed,"paid approval covers actual count before enqueue");
        AlertsCreator.pending.run(0);check(request.enqueues==1,"approval starts one sequence");AlertsCreator.pending.run(0);check(request.enqueues==1&&!request.closed,"duplicate approval cannot send twice or abort accepted sequence");
        reset();host=new BaseFragment();Request cancelled=ready(host,item);visible(host).positive();AlertsCreator.Paid late=AlertsCreator.pending;AlertsCreator.dismissNative();int revokesBefore=PurpleRecordingDelivery.revokes;
        Request retry=begin(host,item);payment(retry!=cancelled&&cancelled.closed&&PurpleRecordingDelivery.revokes==revokesBefore+1,"native paid cancel retires old generation exactly once for explicit zero-attempt retry");
        worker.drain();payment(PurpleLongRoundMedia.lastCircles&&retry.enqueues==0,"payment retry returns to circles and deliberate review");
        late.run(12);payment(cancelled.enqueues==0&&!retry.closed,"old paid callback cannot send or close replacement");
        visible(host).positive();AlertsCreator.pending.run(12);payment(retry.enqueues==1&&!retry.closed,"new paid generation can approve");
        begin(host,item);payment(active.get(item.id)==retry&&!retry.closed,"approved generation cannot be replaced");
        late.run(12);AlertsCreator.pending.run(12);payment(retry.enqueues==1&&cancelled.enqueues==0,"late and duplicate payment callbacks remain idempotent");
        reset();PurpleRecordingSendAs.valid=false;begin(new BaseFragment(),item);check(active.isEmpty(),"replacement account cannot open review");
        for(int kind=0;kind<3;kind++){reset();host=new BaseFragment();request=ready(host,item);int nativeBefore=host.nativeDismissals;
         if(kind==0)visible(host).dismiss();else if(kind==1)visible(host).cancel();else if(visible(host).outside)visible(host).dismiss();
         life(request.closed&&active.isEmpty()&&request.enqueues==0,"cancel/back/outside release reservation without send");
         life(host.nativeDismissals==nativeBefore+1&&host.visibleDialog==null,"BaseFragment native dismissal cleanup preserved");
         life(begin(host,item)!=null,"same entry/account retry after every cancellation route");}
        reset();host=new BaseFragment();PurpleLongRoundMedia.fail=true;request=ready(host,item);PurpleLongRoundMedia.fail=false;visible(host).positive();
        life(!request.closed&&request.preparing&&!request.work.cancelled.get(),"preparation error ordinary fallback survives reentrant native replacement");worker.drain();
        life(!request.closed&&!PurpleLongRoundMedia.lastCircles&&request.enqueues==0,"ordinary fallback returns to final review without send");
        reset();host=new BaseFragment();PurpleLongRoundMedia.invalid=true;request=ready(host,item);life(visible(host).builder.positive==null,"invalid selection has no widening fallback");visible(host).dismiss();life(request.closed&&active.isEmpty(),"invalid selection dismissal permits retry");
        reset();host=new BaseFragment();host.parentLayout.refuse=true;int callsBefore=PurpleLongRoundMedia.calls;begin(host,item);life(active.isEmpty(),"native transition refusal closes spinner request");worker.drain();life(PurpleLongRoundMedia.calls==callsBefore,"refused spinner starts no preparation task");
        reset();host=new BaseFragment();request=begin(host,item);host.parentLayout.refuse=true;worker.drain();life(request.closed&&active.isEmpty()&&request.enqueues==0,"native review refusal closes prepared request");life(request.work.discards>=2,"refused review disposes derived work");
        reset();host=new BaseFragment();PurpleLongRoundMedia.fail=true;request=begin(host,item);host.parentLayout.refuse=true;worker.drain();life(request.closed&&active.isEmpty(),"native preparation-error show refusal releases reservation");
        reset();host=new BaseFragment();android.app.Dialog.failShow=true;begin(host,item);life(active.isEmpty(),"native spinner show exception closes request");worker.drain();
        reset();host=new BaseFragment();request=begin(host,item);android.app.Dialog.failShow=true;worker.drain();life(request.closed&&active.isEmpty(),"native review show exception closes request");
        reset();host=new BaseFragment();request=begin(host,item);life(!visible(host).outside,"spinner keeps outside cancellation disabled after native host setup");visible(host).dismiss();life(request.closed&&request.work.cancelled.get()&&active.isEmpty(),"external spinner dismissal cancels pending preparation");worker.drain();life(request.enqueues==0&&host.visibleDialog==null&&request.work.discards>=1,"late preparation cannot reopen cancelled review");
        reset();host=new BaseFragment();request=begin(host,item);revokesBefore=PurpleRecordingDelivery.revokes;visible(host).cancel();worker.drain();life(request.closed&&request.work.cancelled.get()&&PurpleRecordingDelivery.revokes==revokesBefore+1&&request.enqueues==0,"spinner cancel/dismiss callbacks retire exactly once without enqueue");life(begin(host,item)!=null,"spinner cancellation permits same-entry retry");
        reset();host=new BaseFragment();request=begin(host,item);android.app.Dialog other=new android.app.Dialog();host.showDialog(other);life(request.closed&&host.visibleDialog==other,"external dialog replacement releases old spinner without closing replacement");worker.drain();
        reset();host=new BaseFragment();request=begin(host,item);worker.drain();life(!request.closed&&!request.work.cancelled.get()&&visible(host).builder!=null,"intentional spinner dismissal keeps completed review live");
        reset();host=new BaseFragment();request=ready(host,item);host.paused=true;visible(host).positive();life(request.closed&&active.isEmpty()&&request.enqueues==0,"host invalidation on final click closes reservation without send");
        reset();host=new BaseFragment();request=ready(host,item);PurpleRecordingSendAs.valid=false;visible(host).neutral();life(request.closed&&active.isEmpty()&&request.enqueues==0,"account invalidation on format switch closes request");
        reset();host=new BaseFragment();host.sheetsStack=new ArrayList<>();host.sheetsStack.add(new BaseFragment.Sheet());request=begin(host,item);worker.drain();
        life(!request.closed,"sheet-owned dialog branch retains live review");BaseFragment.Sheet.shown.dismiss();
        life(request.closed&&active.isEmpty(),"sheet-owned branch preserves dismissal callback even before native wrapping");life(begin(host,item)!=null,"sheet dismissal permits same-entry retry");
        reset();System.out.println("LONG_ROUND_PAYMENT_CHECKS="+payments);System.out.println("LONG_ROUND_CHOICE_CHECKS="+checks);System.out.println("LONG_ROUND_DIALOG_LIFECYCLE_CHECKS="+lifecycle);
       }}''')
    compiler=pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac'
    subprocess.run([str(compiler),'-d',str(output/'classes'),*map(str,output.rglob('*.java'))],check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',str(output/'classes'),'org.telegram.ui.LongChoices',mode],check=True)
