package org.telegram.messenger.purple;

import java.io.File;
import java.util.*;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

final class PurpleSyncBackstopCases {
    static final class Clock implements PurpleSyncAutoBackstop.Port {
        final PurpleSyncAutoCases.Port engine;
        final PurpleAccountFiles.Identity identity;
        final Map<Runnable,Long> scheduled = new LinkedHashMap<>();
        long time;
        boolean foreground = true, enabled = true, owner = true;
        int checks;
        Clock(PurpleSyncAutoCases.Port engine) {
            this.engine = engine; identity = PurpleAccountFiles.capture(1); time = engine.clock * 1000;
        }
        public boolean eligible() { return enabled && foreground && owner && identity.current(); }
        public long now() { return time; }
        public void post(Runnable task,long delay) { PurpleModernSyncCases.check(delay > 0 && delay <= 900000, "bounded registered delay"); scheduled.put(task,time+delay); }
        public void cancel(Runnable task) { scheduled.remove(task); }
        public void check() { checks++;engine.clock=time/1000;engine.engine.event(PurpleSyncAutoCore.Event.Foreground); }
        Runnable callback() { PurpleModernSyncCases.check(scheduled.size()==1,"one real registered callback"); return scheduled.keySet().iterator().next(); }
        void fire() { Runnable task=callback();scheduled.remove(task);task.run(); }
    }
    static void run(File parent) throws Exception {
        for(String scenario:new String[]{"idle","disabled","secondary","owner","logout","replaced","background","inflight","backoff","nostate"}) {
            boolean fresh=scenario.equals("nostate");
            PurpleSyncAutoCases.Port p=fresh ? new PurpleSyncAutoCases.Port(new Frame(parent,"backstop-nostate",true),1) : PurpleSyncAutoCases.bound(parent,"backstop-"+scenario);
            if(fresh)p.enabled=true;else PurpleSyncAutoCases.initialPublish(p);
            Clock c=new Clock(p);PurpleSyncAutoBackstop timer=new PurpleSyncAutoBackstop(c);
            timer.resume();Runnable old=c.callback();timer.resume();check(c.scheduled.size()==1,"resume cannot multiply timers");
            int scans=p.checks;c.time+=900000;
            if(scenario.equals("disabled")) { c.enabled=false;p.enabled=false;timer.suspend();old.run();check(c.checks==0&&c.scheduled.isEmpty(),"disabled stale callback cannot run or rearm"); }
            else if(scenario.equals("owner")) { c.owner=false;c.scheduled.remove(old);old.run();check(c.checks==0,"owner invalidation refuses effects"); }
            else if(scenario.equals("logout")) { org.telegram.messenger.UserConfig.getInstance(1).active=false;c.scheduled.remove(old);old.run();check(c.checks==0,"logout invalidates registered callback"); }
            else if(scenario.equals("replaced")) { retire(1);c.scheduled.remove(old);old.run();check(c.checks==0,"same-slot new lifetime invalidates registered callback"); }
            else if(scenario.equals("background")) {
                c.foreground=false;timer.suspend();old.run();check(c.checks==0,"background cannot check");
                c.time+=6*900000;c.foreground=true;p.clock=c.time/1000;p.engine.event(PurpleSyncAutoCore.Event.Foreground);int before=p.checks;
                timer.resume();check(c.scheduled.size()==1,"overdue foreground re-arms once");old.run();check(p.checks==before,"old callback cannot replay overdue intervals");
            } else {
                if(scenario.equals("inflight")){p.holdCheck=true;p.clock=c.time/1000;p.engine.event(PurpleSyncAutoCore.Event.ChatUpdated);}
                if(scenario.equals("backoff")){p.testingBackoff=true;p.exceptionBackoff=new long[]{2,c.time/1000+120};}
                c.fire();check(c.checks==1&&c.scheduled.size()==1,"due registered callback executes and rearms once");
                if(scenario.equals("backoff"))check(p.checks==scans&&p.delay==120000,"periodic trigger respects exact backoff");
                else if(scenario.equals("inflight"))check(p.checks==scans+1,"periodic trigger coalesces while actual check in flight");
                else if(scenario.equals("nostate"))check(p.reason==PurpleSyncAutoCore.Reason.AccountUnbound&&p.posts==0&&!new File(p.frame.sync(1),"state.json").exists(),"no-state backstop does not create or publish state");
                else check(p.checks==scans+1,"idle and stored secondary account check without lifecycle event");
                old.run();check(c.checks==1,"consumed callback cannot replay");
            }
            timer.close();old.run();check(c.scheduled.isEmpty(),"dispose retires callback");p.engine.close();
        }
        PurpleSyncAutoCases.Port p=PurpleSyncAutoCases.bound(parent,"backstop-anchor");PurpleSyncAutoCases.initialPublish(p);
        Clock c=new Clock(p);PurpleSyncAutoBackstop timer=new PurpleSyncAutoBackstop(c);timer.resume();Runnable old=c.callback();
        c.time+=300000;timer.checked(c.time);check(c.scheduled.get(c.callback())==c.time+900000,"actual later check shifts deadline to fifteen minutes after it");
        old.run();check(c.checks==0,"replaced scheduled callback is inert");timer.close();p.engine.close();
    }
}
