#!/usr/bin/env python3
import argparse,json,subprocess
from pathlib import Path
def main(argv=None):
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--javac',required=True);p.add_argument('--wrapper',default='');a=p.parse_args(argv)
    a.output.mkdir();src=a.output/'src';src.mkdir();out=a.output/'classes';out.mkdir()
    def body(signature):
     text=a.source.read_text();start=text.index(signature);opening=text.index('{',start);depth=1;i=opening+1
     while depth:
      if text[i]=='{':depth+=1
      elif text[i]=='}':depth-=1
      i+=1
     return text[start:i]
    methods='\n'.join(body(sig) for sig in ['public void onResume()', 'public void onPause()', 'private void stopForLostAccount()'])
    files={
    'org/telegram/messenger/R.java':'package org.telegram.messenger;public class R{public static class string{public static final int PurpleSyncReady=1,PurpleSyncLostAccount=2;}}',
    'org/telegram/messenger/purple/PurpleRpcRequests.java':'package org.telegram.messenger.purple;public class PurpleRpcRequests{public static String text(Object x){return "waiting";}}',
    'org/telegram/messenger/purple/PurpleSyncRunner.java':'package org.telegram.messenger.purple;public class PurpleSyncRunner{public boolean available=true,closed;public boolean accountAvailable(){return available&&!closed;}public boolean checking(){return false;}public void cancel(){}public void close(){closed=true;}public void suspend(){}public void transientWait(java.util.function.Consumer<Object> c){}}',
    'org/telegram/messenger/purple/PurpleSyncChat.java':'package org.telegram.messenger.purple;public class PurpleSyncChat{public static int requests;public static java.util.function.Consumer<PurpleSyncRunner> pending;public static void manualRunner(int slot,java.util.function.BooleanSupplier live,java.util.function.Consumer<PurpleSyncRunner> done){requests++;pending=done;}public static void deliver(PurpleSyncRunner value){java.util.function.Consumer<PurpleSyncRunner> done=pending;pending=null;done.accept(value);}}',
    'org/telegram/ui/PurpleSyncActivity.java':"""package org.telegram.ui;
    import org.telegram.messenger.R;import org.telegram.messenger.purple.*;
    class Fragment {public void onResume(){}public void onPause(){}}
    public class PurpleSyncActivity extends Fragment {
     PurpleSyncRunner runner;boolean manualForeground,firstAskOpened,manualOpening,destroyed,lost;long manualTicket;int currentAccount;Object share,sharePrefix;String status="ready";
     void failureOnce(){}void checkOnOpenAsk(){}void refresh(){}void clearReview(){}boolean inFront(){return manualForeground&&!destroyed;}void setStatus(String value){status=value;}String getString(int value){return value==1?"ready":"unavailable";}
     """+methods+"""
     static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
     public static void main(String[] args){
      java.util.List<String> failures=new java.util.ArrayList<>();
      for(String mode:new String[]{"null","recover","stale","retired"})try{test(mode);}catch(AssertionError e){failures.add(e.getMessage());}
      if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));System.out.println("SYNC_ACTIVITY_METHODS=PASS");
     }
     static void test(String mode){
      PurpleSyncChat.requests=0;PurpleSyncChat.pending=null;PurpleSyncActivity view=new PurpleSyncActivity();
      if(mode.equals("null")){view.onResume();PurpleSyncChat.deliver(null);check(view.lost&&view.status.equals("unavailable"),"null manual runner must not report ready");view.onResume();PurpleSyncRunner recovered=new PurpleSyncRunner();PurpleSyncChat.deliver(recovered);check(!view.lost&&view.runner==recovered&&view.status.equals("ready"),"new resolved runner restores truthful startup status");}
      else if(mode.equals("stale")){view.onResume();view.onPause();PurpleSyncRunner stale=new PurpleSyncRunner();PurpleSyncChat.deliver(stale);check(stale.closed&&view.runner==null,"paused UI rejects and closes stale resolution");}
      else {PurpleSyncRunner original=new PurpleSyncRunner();original.available=false;view.runner=original;view.onResume();check(view.lost&&view.status.equals("unavailable"),"unavailable captured runner is held");if(mode.equals("recover")){original.available=true;view.onResume();check(!view.lost&&view.status.equals("ready")&&view.runner==original,"same captured runner recovery must refresh status");}else{view.onResume();check(view.runner==original&&PurpleSyncChat.requests==0&&view.lost,"retired context must not be silently rebound");}}
     }
    }
    """}
    for name,text in files.items():f=src/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
    (a.output/'method-inputs.json').write_text(json.dumps({'source':str(a.source),'methods':methods},indent=2))
    subprocess.run(([a.wrapper] if a.wrapper else [])+[a.javac,'-source','8','-target','8','-d',str(out)]+[str(x) for x in src.rglob('*.java')],check=True)
    result=subprocess.run([str(Path(a.javac).with_name('java')),'-cp',str(out),'org.telegram.ui.PurpleSyncActivity'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    print(result.stdout,end='')
    return result.returncode

if __name__=='__main__':raise SystemExit(main())
