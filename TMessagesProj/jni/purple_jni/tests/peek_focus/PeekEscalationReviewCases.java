package org.telegram.ui;

import java.lang.reflect.*;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.purple.*;
import org.telegram.ui.ActionBar.BaseFragment;
import android.os.SystemClock;

public final class PeekEscalationReviewCases {
    static int checks;
    static void check(boolean value,String message){++checks;if(!value)throw new AssertionError(message);}
    static void restart(PersistenceProbe.App app)throws Exception {
        app.durable.values.clear();app.durable.values.putAll(app.durable.disk);app.durable.loseCounter=false;
        PersistenceProbe.set(PurplePeekStreak.class,"state",null);PersistenceProbe.set(PurplePeekStreak.class,"failed",false);
        PersistenceProbe.set(PurplePeekStreak.class,"boot",null);PersistenceProbe.set(PurplePeekLocalSettings.class,"current",null);
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("reboot")) {
            PurplePeekStreakModel m=new PurplePeekStreakModel();m.started(110000,1000,100000,"oldBoot");
            m.observe(false,0,1000,3710000,"newBoot");check(m.reset(1000,3710000,"newBoot",60)&&m.count==0,"one hour since an expiry while off resets immediately");
            m=new PurplePeekStreakModel();m.started(110000,1000,100000,"oldBoot");
            m.observe(false,0,1000,105000,"newBoot");
            check(m.endWall==105000&&m.endElapsed==1000&&m.boot.equals("newBoot"),"early reboot END keeps the observed end, before the scheduled upper bound");
            check(!m.reset(3600999,3704999,"newBoot",60)&&m.reset(3601000,3705000,"newBoot",60),"early reboot END resets at exactly one hour");
            m=new PurplePeekStreakModel();m.started(110000,1000,100000,"oldBoot");m.observe(false,0,1000,109000,"newBoot");
            check(!m.reset(1000,109000,"newBoot",60)&&m.count==1,"backwards wall stays protective");
            m=new PurplePeekStreakModel();m.started(110000,1000,100000,"oldBoot");m.observe(false,0,2000,111000,"newBoot");
            check(m.boot.equals("newBoot")&&m.endElapsed==1000,"representable cross-boot age converts to monotonic END");
            check(!m.reset(3600999,1,"newBoot",60)&&m.reset(3601000,1,"newBoot",60),"converted anchor uses monotonic exact boundary despite wall changes");
        } else {
            BaseFragment f=PersistenceProbe.setup();PersistenceProbe.App app=new PersistenceProbe.App();ApplicationLoader.applicationContext=app;
            PersistenceProbe.set(PurplePeekLocalSettings.class,"current",null);PersistenceProbe.set(PurplePeekStreak.class,"state",null);
            if(args[0].equals("expired-no-op")) {
                PurpleGate.startPeek(60,PurpleGate.peekSettings());
                PurpleState.bytes="active_preset=\"work\"\npeek_active=true\npeek_deadline_unix=1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                PurplePeekControls.extend(f,60);SystemClock.now=2000;PersistenceProbe.complete();
                check(!PurpleGate.peeking(),"expired no-op extension retains the existing End Peek behavior");
                check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==0,"expired no-op never reserves an increment");
            } else if(args[0].equals("extension-failure")) {
                PurplePeekControls.start(f,60);SystemClock.now=2000;PersistenceProbe.complete();
                byte[] before=PurpleState.bytes.clone();check(PurpleGate.peeking(),"first Peek succeeds");
                PurplePeekControls.extend(f,60);app.durable.loseCounter=true;SystemClock.now=14000;PersistenceProbe.complete();
                check(java.util.Arrays.equals(before,PurpleState.bytes),"failed increment commit prevents extension state write");
                restart(app);check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==10,"restart retains the successful first count");
            } else {
                PurplePeekControls.start(f,60);SystemClock.now=2000;
                PurpleGate.crashBeforeWrite=args[0].equals("crash-before");PurpleGate.crashAfterWrite=!PurpleGate.crashBeforeWrite;
                boolean crashed=false;try{PersistenceProbe.complete();}catch(InvocationTargetException e){crashed=e.getCause() instanceof AssertionError;}
                check(crashed,"exact state boundary process-death cut reached");
                check(PurplePeekStreakModel.decode((String)app.durable.disk.get("streak")).count==1,"increment durable before either crash cut");
                PurpleGate.crashBeforeWrite=PurpleGate.crashAfterWrite=false;restart(app);PurpleGate.reload("process restart");
                check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==10,"restart never under-counts a reserved activation");
            }
        }
        System.out.println("PEEK_REVIEW_"+args[0]+"_CHECKS="+checks);
    }
}
