package org.telegram.messenger.purple;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import org.telegram.messenger.*;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;
final class PurpleSyncRuntimeCases {
    static Object field(Class<?> type,Object target,String name)throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target); }
    static void set(Class<?> type,Object target,String name,Object value)throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value); }
    static void ui() { int left=100;while(!AndroidUtilities.pending.isEmpty()){check(--left>0,"UI settles");AndroidUtilities.pending.remove(0).run();} }
    static void run(File parent)throws Exception {
        Map<String,Object> saved=new HashMap<>();for(String key:new String[]{"started","ready","owner","global"})saved.put(key,field(PurpleAccountOwner.class,null,key));
        Map<String,Object> runtimes=(Map<String,Object>)field(PurpleSyncAuto.class,null,"accounts");
        Thread prior=android.os.Looper.mainThread;
        Object observing=field(PurpleSyncAuto.class,null,"observing");set(PurpleSyncAuto.class,null,"observing",true);
        try {
            Frame frame=new Frame(parent,"registered-runtime",true);frame.initialize(1);
            set(PurpleAccountOwner.class,null,"started",true);set(PurpleAccountOwner.class,null,"ready",true);set(PurpleAccountOwner.class,null,"owner",A);set(PurpleAccountOwner.class,null,"global",A);
            android.os.Looper.mainThread=Thread.currentThread();ApplicationLoader.mainInterfacePaused=false;
            SharedConfig.appLocked=false;SharedConfig.isWaitingForPasscodeEnter=false;
            AndroidUtilities.pending.clear();AndroidUtilities.delayed.clear();DispatchQueue.pending.clear();
            PurpleSyncAuto.Control control=PurpleSyncAuto.control(1,status->{});DispatchQueue.flush();ui();
            Object runtime=runtimes.get(B);check(runtime!=null,"actual facade secondary runtime");
            Method enabled=runtime.getClass().getDeclaredMethod("enabledHint",boolean.class);enabled.setAccessible(true);enabled.invoke(runtime,true);
            PurpleSyncAuto.foreground();ui();
            ArrayList<Runnable> timers=new ArrayList<>();for(Runnable task:AndroidUtilities.delayed.keySet())if(task.getClass().getName().contains("PurpleSyncAutoBackstop"))timers.add(task);
            check(timers.size()==1,"opted-in stored secondary has actual registered backstop callback");
            Runnable task=timers.get(0);Object timer=field(runtime.getClass(),runtime,"backstop");set(timer.getClass(),timer,"deadline",System.currentTimeMillis()-1);
            int queued=DispatchQueue.pending.size();AndroidUtilities.delayed.remove(task);task.run();
            check(DispatchQueue.pending.size()>queued,"actual registered due callback queues production engine transition");
            queued=DispatchQueue.pending.size();task.run();check(DispatchQueue.pending.size()==queued,"consumed actual callback cannot multiply work");
            control.setEnabled(false);ui();queued=DispatchQueue.pending.size();task.run();check(DispatchQueue.pending.size()==queued,"disabled callback cannot queue more work");
            control.close();
        } finally {
            for(Object runtime:new ArrayList<>(runtimes.values())) {((PurpleSyncAutoEngine)field(runtime.getClass(),runtime,"engine")).close();try {Object timer=field(runtime.getClass(),runtime,"backstop");Method close=timer.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(timer);}catch(NoSuchFieldException ignored){} }
            set(PurpleSyncAuto.class,null,"observing",observing);runtimes.clear();DispatchQueue.pending.clear();AndroidUtilities.pending.clear();AndroidUtilities.delayed.clear();android.os.Looper.mainThread=prior;
            for(Map.Entry<String,Object> entry:saved.entrySet())set(PurpleAccountOwner.class,null,entry.getKey(),entry.getValue());
        }
    }
}
