"""Execute actual long-output review and confirmation over inert native dialog ports."""
import pathlib, runpy, subprocess, os, re, json, xml.etree.ElementTree as ET
method=runpy.run_path(str(pathlib.Path(__file__).with_name('bubble_routing.py')))['method']

def run(root, output):
    output.mkdir(parents=True,exist_ok=False)
    java=root/'TMessagesProj/src/main/java/org/telegram'
    source=(java/'ui/PurpleLongRoundSend.java').read_text()
    def put(name,text):
        p=output/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    resources={n: i+1 for i,n in enumerate(sorted(set(re.findall(r'R.string.(\w+)',source))))}
    xml={n.attrib['name']:''.join(n.itertext()) for n in ET.parse(root/'TMessagesProj/src/main/res/values/purple_strings.xml').getroot() if n.tag=='string'}
    xml.update(Cancel='Cancel',Send='Send',OK='OK')
    put('org/telegram/messenger/R.java','package org.telegram.messenger;public class R{public static class string{'+''.join('public static final int '+n+'='+str(i)+';' for n,i in resources.items())+'}}')
    put('org/telegram/messenger/LocaleController.java','package org.telegram.messenger;public class LocaleController {public static String getString(int id){switch(id){'+''.join('case '+str(i)+':return '+json.dumps(xml[n])+';' for n,i in resources.items())+'default:throw new AssertionError();}}public static String formatString(int id,Object... args){return String.format(java.util.Locale.ROOT,getString(id),args);}}')
    put('org/telegram/messenger/AndroidUtilities.java','package org.telegram.messenger;public class AndroidUtilities{public static void runOnUIThread(Runnable r,long delay){}}')
    put('org/telegram/messenger/SendMessageChatArguments.java','package org.telegram.messenger;public class SendMessageChatArguments{}')
    put('org/telegram/messenger/purple/PurpleRecordingEntry.java','package org.telegram.messenger.purple;public class PurpleRecordingEntry{public String id="source";}')
    put('org/telegram/messenger/purple/PurpleRecordingSendAs.java','package org.telegram.messenger.purple;import org.telegram.messenger.*;public class PurpleRecordingSendAs{public static boolean valid=true;public static boolean sameUser(int a,long u){return valid;}public static SendMessageChatArguments copyFrozen(int a,long u,long d,SendMessageChatArguments original){return valid?new SendMessageChatArguments():null;}}')
    put('org/telegram/ui/ActionBar/BaseFragment.java','package org.telegram.ui.ActionBar;public class BaseFragment{public boolean paused;public Object getParentActivity(){return new Object();}public Object getResourceProvider(){return null;}public boolean isPaused(){return paused;}public void showDialog(Object dialog){}}')
    put('org/telegram/ui/ActionBar/AlertDialog.java','''package org.telegram.ui.ActionBar;
      public class AlertDialog {public interface Click{void run(Object d,int which);}public interface Dismiss{void run(Object d);}
       public static Builder latest;public static class Builder{public Click positive,neutral;public Dismiss dismiss;public String message,primary;
        public Builder(Object a,Object b){latest=this;}public Builder setTitle(String s){return this;}public Builder setMessage(String s){message=s;return this;}
        public Builder setNegativeButton(String s,Click c){return this;}public Builder setNeutralButton(String s,Click c){neutral=c;return this;}public Builder setPositiveButton(String s,Click c){primary=s;positive=c;return this;}public Builder setOnDismissListener(Dismiss d){dismiss=d;return this;}public Object create(){return this;}}
      }''')
    put('org/telegram/ui/Components/AlertsCreator.java','''package org.telegram.ui.Components;public class AlertsCreator{public interface Paid{void run(long p);}public static Paid pending;public static int count;public static void ensurePaidMessageConfirmation(int a,long d,int n,Paid paid,long stars){count=n;pending=paid;}}''')
    put('org/telegram/messenger/purple/PurpleLongRoundQueue.java',(java/'messenger/purple/PurpleLongRoundQueue.java').read_text())
    review=method(source,'    public static void review(')
    constructor=method(source,'        Request(BaseFragment host,')
    confirm=method(source,'        void confirm()')
    host=method(source,'        BaseFragment host()')
    put('org/telegram/ui/LongChoices.java','''package org.telegram.ui;
      import java.util.*;import java.lang.ref.WeakReference;import java.util.concurrent.atomic.AtomicBoolean;
      import org.telegram.messenger.*;import org.telegram.messenger.purple.*;import org.telegram.ui.ActionBar.*;import org.telegram.ui.Components.*;
      public class LongChoices {
       static final Map<String,Request> active=new HashMap<>();public interface Sender{}
       static class Output {boolean circle;Output(boolean b){circle=b;}}
       static String text(int r){return LocaleController.getString(r);}
      '''+review+'''
       static class Request {
        final WeakReference<BaseFragment> owner;final PurpleRecordingEntry item;final int account;final long user,dialog,startUs,endUs;
        final Sender sender;final Runnable done;final SendMessageChatArguments[] arguments=new SendMessageChatArguments[6];
        List<Output> outputs;PurpleLongRoundQueue queue;boolean closed,lastCircles;int preparations,enqueues,finishes;
      '''+constructor+host+confirm+'''
        void prepare(boolean circles){lastCircles=circles;preparations++;}
        void enqueue(long paid){enqueues++;}
        void finish(boolean error){closed=true;finishes++;}
        void ready(boolean circles,int n){outputs=new ArrayList<>();for(int i=0;i<n;i++)outputs.add(new Output(circles));queue=new PurpleLongRoundQueue(n);confirm();}
       }
       static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
       public static void main(String[] args){
        BaseFragment host=new BaseFragment();PurpleRecordingEntry item=new PurpleRecordingEntry();
        review(host,item,1,9,100,new SendMessageChatArguments(),null,()->{},-1,-1);
        Request request=active.get(item.id);check(request.preparations==1&&request.lastCircles,"normal Send defaults to preparing circles without picker");
        check(request.enqueues==0,"preparation cannot send before review");
        request.ready(true,3);AlertDialog.Builder review=AlertDialog.latest;
        check(review.primary.contains("3")&&review.message.contains("3")&&review.message.contains("full original"),"primary circles review shows count and original retention");
        review.neutral.run(null,0);review.dismiss.run(null);
        check(!request.closed&&!request.lastCircles&&request.preparations==2&&request.enqueues==0,"ordinary video is secondary deliberate preparation, no send");
        request.ready(false,1);review=AlertDialog.latest;review.dismiss.run(null);
        check(request.closed&&request.enqueues==0,"review cancel preserves unsent state");
        active.clear();review(host,item,1,9,100,new SendMessageChatArguments(),null,()->{},-1,-1);request=active.get(item.id);request.ready(true,3);
        review=AlertDialog.latest;review.positive.run(null,0);review.dismiss.run(null);
        check(AlertsCreator.count==3&&request.enqueues==0&&!request.closed,"paid approval covers actual count before enqueue");
        AlertsCreator.pending.run(0);check(request.enqueues==1,"approval starts one sequence");
        AlertsCreator.pending.run(0);check(request.enqueues==1&&!request.closed,"duplicate approval cannot send twice or abort accepted sequence");
        active.clear();PurpleRecordingSendAs.valid=false;review(host,item,1,9,100,new SendMessageChatArguments(),null,()->{},-1,-1);
        check(active.isEmpty(),"replacement account cannot open review");
        System.out.println("LONG_ROUND_CHOICE_CHECKS="+checks);
       }}''')
    compiler=pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac'
    subprocess.run([str(compiler),'-d',str(output/'classes'),*map(str,output.rglob('*.java'))],check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',str(output/'classes'),'org.telegram.ui.LongChoices'],check=True)
