package org.telegram.ui;

import java.lang.reflect.*;
import android.os.SystemClock;
import org.telegram.messenger.purple.*;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.messenger.ApplicationLoader;

public final class PeekEscalationCases {
    private static int checks;
    private static void check(boolean yes, String text) { ++checks; if (!yes) throw new AssertionError(text); }
    private static Object field(Class<?> type, Object object, String name) throws Exception {
        Field f=type.getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static Object pending() throws Exception { return field(PurplePeekControls.class,null,"pending"); }
    private static void complete() throws Exception {
        Object p=pending(); check(p!=null,"a gated operation exists");
        Field gateField=p.getClass().getDeclaredField("gate");gateField.setAccessible(true);SystemClock.now+=((PurpleWaitGate)gateField.get(p)).remaining(SystemClock.now);Method tick=p.getClass().getDeclaredMethod("tick");tick.setAccessible(true);tick.invoke(p);SystemClock.now+=500;Method m=p.getClass().getDeclaredMethod("complete");m.setAccessible(true);m.invoke(p);
    }
    private static long left() throws Exception {
        Object p=pending(); return ((PurpleWaitGate)field(p.getClass(),p,"gate")).remaining(SystemClock.now);
    }
    private static BaseFragment setup() throws Exception {
        Method m=PeekFocusCases.class.getDeclaredMethod("setup",boolean.class,boolean.class,boolean.class);
        m.setAccessible(true);m.invoke(null,false,false,false);
        return (BaseFragment)field(PeekFocusCases.class,null,"fragment");
    }
    private static void finish(long milliseconds) throws Exception { SystemClock.now+=milliseconds; complete(); }
    private static void model() {
        PurplePeekStreakModel m=new PurplePeekStreakModel();
        for(int n=0;n<14;n++) {
            check(10+m.extra(true,10,90)==10+Math.min(n*10,90),"default sequence and cap at n="+n);
            check(m.extra(true,10,90)==Math.min(n*10,90),"base zero retains extra");
            m.started(0,100,100000,"a");
        }
        check(!m.reset(99999999,99999999,"a",60),"running Peek blocks reset");
        m.observe(false,0,1000,101000,"a");
        check(!m.reset(3600999,999999999,"a",60),"59:59.999 since END does not reset even after wall jump");
        check(m.reset(3601000,1,"a",60)&&m.count==0,"60:00 since END resets despite wall going back");
        m.started(0,10,100000,"a");m.observe(false,0,20,100020,"a");
        check(!m.reset(5,100019,"b",60),"reboot plus backwards wall never resets");
        check(!m.reset(5,3700019,"b",60),"reboot 59:59.999 does not reset");
        check(m.reset(5,3700020,"b",60),"reboot exact one hour resets");
        m.started(110000,1000,100000,"a");m.observe(false,0,12000,1,"a");
        check(m.endElapsed==12000,"ending while the wall clock is backwards anchors inactivity at the actual END");
        m.started(110000,1000,100000,"a");m.observe(false,0,13000,112000,"a");
        check(m.endElapsed==11000,"missed natural expiry uses its monotonic deadline");
        PurplePeekStreakModel copy=PurplePeekStreakModel.decode(m.encode());
        check(copy.count==m.count&&copy.endElapsed==m.endElapsed&&!copy.active,"restart retains the streak and END anchor");
        check(!copy.reset(999999999,999999999,"",60),"unknown boot identity cannot shorten the wait");
        copy.started(0,0,0,"b"); check(copy.count>=0,"increment cannot overflow");
        check(copy.extra(false,10,90)==0,"disabled policy keeps but does not apply the streak");
        copy.count=Long.MAX_VALUE;check(copy.extra(true,300,3600)==3600,"overflow-safe cap");
        copy.started(0,0,0,"b");check(copy.count==Integer.MAX_VALUE,"saturating success count");
        for(String bad:new String[]{"garbage","1;-1;0;0;0;0;0;a","1;9223372036854775807;0;0;0;0;0;a","1;1;2;0;0;0;0;a"}) {
            boolean refused=false;try{PurplePeekStreakModel.decode(bad);}catch(IllegalArgumentException e){refused=true;}
            check(refused,"malformed persisted streak refuses: "+bad);
        }
    }
    private static void controller() throws Exception {
        BaseFragment f=setup();
        PurplePeekLocalSettings.Snapshot policy=PurplePeekLocalSettings.snapshot();
        check(policy.escalation&&policy.step==10&&policy.maxExtra==90&&policy.resetMinutes==60,"all local defaults");
        PurplePeekControls.start(f,60);check(left()==2000,"first uses base only");
        PurplePeekControls.cancel();
        check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==0,"aborted countdown never counts");
        PurplePeekControls.start(f,60);finish(2000);
        check(PurpleGate.peeking()&&PurplePeekStreak.delay(2,PurpleGate.state()).extra==10,"successful start counts once");
        PurplePeekControls.extend(f,60);check(left()==12000,"extension uses escalated delay");finish(12000);
        check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==20,"successful extension counts once");
        PurplePeekControls.changePreset(f,"normal");check(left()==22000,"Work Mode loosening uses the same escalated wait");
        PurplePeekControls.cancel();
        check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==20,"Work Mode abort does not count");
        PurplePeekControls.end(f);
        check(!PurpleGate.peeking(),"end is instant");
        String persisted=(String)((org.telegram.ui.PeekPreferences)ApplicationLoader.applicationContext.getSharedPreferences("purple_peek_local",0)).values.get("streak");
        check(PurplePeekStreakModel.decode(persisted).count==2&&!PurplePeekStreakModel.decode(persisted).active,"durable streak records successful activations and END");
        Field saved=PurplePeekStreak.class.getDeclaredField("state");saved.setAccessible(true);saved.set(null,null);
        check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==20,"same-login restart restores successful streak");
        PurpleState.fail=true;
        PurplePeekControls.start(f,60);finish(22000);
        check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==20,"refused state write never counts");
        PurpleState.fail=false;PurpleGate.refuseStart=true;
        PurplePeekControls.start(f,60);finish(22000);
        check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==20,"explicit refused Peek result never counts");
        PurpleGate.refuseStart=false;PurpleGate.startPeek(0,PurpleGate.peekSettings());
        PurplePeekControls.extend(f,60);finish(22000);
        check(PurplePeekStreak.delay(2,PurpleGate.state()).extra==20,"unlimited/capped no-op extension never counts");
    }
    private static void options(String name) throws Exception {
        BaseFragment f=setup();
        if(name.equals("delay")) {
            PurplePeekControls.changeDelay(f,3,new PurpleWriter.Session(),PurpleGate.peekSettings());
            check(!PurplePeekControls.isPending()&&PurpleWriter.delayWrites==1,"base delay increase is instant");
            PurplePeekControls.changeDelay(f,1,new PurpleWriter.Session(),PurpleGate.peekSettings());
            check(left()==2000&&PurpleWriter.delayWrites==1,"base delay decrease waits and retains old value");
            PurplePeekControls.cancel();check(PurpleWriter.delayWrites==1,"aborted decrease never writes");return;
        }
        if(name.equals("exit")) {
            PurplePeekControls.changeExitHidden(f,false);check(left()==2000&&PurpleWriter.exitWrites==0,"exit-hidden off waits");
            PurplePeekControls.cancel();check(PurpleWriter.exitWrites==0,"aborted off never writes");
            PurplePeekControls.changeExitHidden(f,false);finish(2000);check(PurpleWriter.exitWrites==1,"off writes only after countdown");return;
        }
        PurplePeekLocalSettings.Snapshot initial=PurplePeekLocalSettings.snapshot();
        int before=initial.value(name),lower=before==0?0:before-1;
        PurplePeekControls.changeWaitOption(f,name,lower,initial);check(left()==2000,"loosening "+name+" waits");
        check(PurplePeekLocalSettings.snapshot().value(name)==before,"wait keeps old "+name);
        PurplePeekControls.cancel();check(PurplePeekLocalSettings.snapshot()==initial,"abort keeps old policy");
        PurplePeekControls.changeWaitOption(f,name,lower,initial);finish(2000);
        check(PurplePeekLocalSettings.snapshot().value(name)==lower,"completed loosening writes "+name);
        PurplePeekLocalSettings.Snapshot lowerPolicy=PurplePeekLocalSettings.snapshot();
        PurplePeekControls.changeWaitOption(f,name,before,lowerPolicy);
        check(!PurplePeekControls.isPending()&&PurplePeekLocalSettings.snapshot().value(name)==before,"tightening "+name+" is instant");
        PurplePeekControls.changeWaitOption(f,name,lower,PurplePeekLocalSettings.snapshot());finish(2000);
        PurplePeekControls.start(f,60);check(PurplePeekControls.isPending(),"another wait is pending");
        PurplePeekControls.changeWaitOption(f,name,before,PurplePeekLocalSettings.snapshot());
        check(!PurplePeekControls.isPending()&&PurplePeekLocalSettings.snapshot().value(name)==before,"tightening "+name+" cancels another wait and applies immediately");
        PurplePeekControls.changeWaitOption(f,name,lower,initial);
        check(!PurplePeekControls.isPending()&&PurplePeekLocalSettings.snapshot().value(name)==before,"stale opening snapshot refuses");
    }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("model"))model();else if(args[0].equals("controller"))controller();else options(args[0]);
        System.out.println("PEEK_ESCALATION_"+args[0]+"_CHECKS="+checks);
    }
}
