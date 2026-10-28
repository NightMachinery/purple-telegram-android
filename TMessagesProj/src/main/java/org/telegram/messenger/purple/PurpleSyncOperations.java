package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

final class PurpleSyncOperations {
    private static final HashMap<String, CarryLease> carries=new HashMap<>();
    private static final HashMap<String, State> accounts = new HashMap<>();
    /*
     * Editor publication holds AccountOwner, AccountFiles, then AccountSettings.
     * Its operation check reads a RAM capability, never the registry monitor.
     * Account-liveness suppliers and idle callbacks run outside that monitor.
     * Revalidate the captured owner and token after evaluating account liveness.
     * Revoke before cancellation callbacks; release must match owner and acquisition token.
     */
    static final class Capability {
        final String key;
        final Object owner;
        private volatile boolean revoked;
        private Capability(String key,Object owner){this.key=key;this.owner=owner;}
        boolean current() { return !revoked; }
        void revoke() { revoked=true; }
    }
    private static final class State {
        Object owner;
        Capability capability;
        final java.util.LinkedHashMap<Object,Runnable> idle=new java.util.LinkedHashMap<>();
        BooleanSupplier current;
        Runnable quiesce;
        PurpleSyncRunner.UndoOffer undo;
    }
    private static State state(String key) {
        State value=accounts.get(key);
        if(value==null){value=new State();accounts.put(key,value);}
        return value;
    }
    private static List<Runnable> retire(State value) {
        if(value.capability!=null)value.capability.revoke();
        value.owner=null;value.current=null;value.capability=null;value.quiesce=null;
        List<Runnable> waiting=new ArrayList<>(value.idle.values());value.idle.clear();
        return waiting;
    }
    private static Capability live(String key) {
        while(true){
            final Object owner;
            final Capability capability;
            final BooleanSupplier current;
            synchronized(PurpleSyncOperations.class){
                State value=state(key);owner=value.owner;capability=value.capability;current=value.current;
            }
            if(capability==null)return null;
            boolean valid=capability.current()&&(current==null||current.getAsBoolean());
            List<Runnable> waiting=null;
            synchronized(PurpleSyncOperations.class){
                State value=state(key);
                if(value.owner!=owner||value.capability!=capability)continue;
                if(valid&&capability.current())return capability;
                waiting=retire(value);
            }
            for(Runnable task:waiting)task.run();
            return null;
        }
    }
    static boolean owned(String key,Object owner) {
        while(true){
            Capability capability=live(key);
            synchronized(PurpleSyncOperations.class){
                State value=state(key);
                if(value.capability==capability&&(capability==null||capability.current()))return value.owner==owner;
            }
        }
    }
    static boolean busy(String key) {
        while(true){
            Capability capability=live(key);
            synchronized(PurpleSyncOperations.class){
                State value=state(key);
                if(value.capability==capability&&(capability==null||capability.current()))return value.owner!=null;
            }
        }
    }
    static Capability acquire(String key,Object owner,BooleanSupplier current) { return acquire(key,owner,current,null); }
    static Capability acquire(String key,Object owner,BooleanSupplier current,Runnable quiesce) {
        while(true){
            Capability capability=live(key);
            synchronized(PurpleSyncOperations.class){
                State value=state(key);
                if(value.capability!=capability||capability!=null&&!capability.current())continue;
                if(value.owner!=null||carries.containsKey(key)&&carries.get(key).owner!=owner)return null;
                value.owner=owner;value.current=current;value.quiesce=quiesce;value.capability=new Capability(key,owner);
                return value.capability;
            }
        }
    }
    static void end(String key,Object owner,Capability acquisition) {
        List<Runnable> waiting;
        synchronized(PurpleSyncOperations.class){
            State value=state(key);
            if(acquisition==null||value.owner!=owner||value.capability!=acquisition)return;
            waiting=retire(value);
        }
        for(Runnable task:waiting)task.run();
    }
    static synchronized void forgetIdle(String key,Object owner) { state(key).idle.remove(owner); }
    static void whenIdle(String key,Object owner,Runnable callback) {
        while(true){
            Capability capability=live(key);
            synchronized(PurpleSyncOperations.class){
                State value=state(key);
                if(value.capability!=capability||capability!=null&&!capability.current())continue;
                if(value.owner!=null||carries.containsKey(key)){value.idle.put(owner,callback);return;}
            }
            callback.run();return;
        }
    }
    static final class CarryLease implements AutoCloseable {
        final String key;final Object owner;private volatile boolean closed;
        private CarryLease(String key,Object owner){this.key=key;this.owner=owner;}
        boolean current(){return !closed;}
        boolean quiescent(){return current()&&!busy(key);}
        void quiesce(){
            Runnable callback;
            synchronized(PurpleSyncOperations.class){State value=state(key);callback=value.quiesce;}
            if(callback!=null)callback.run();
        }
        @Override public void close(){
            List<Runnable> waiting=new ArrayList<>();
            synchronized(PurpleSyncOperations.class){
                if(closed)return;closed=true;
                if(carries.get(key)==this){carries.remove(key);State value=state(key);if(value.owner==null){waiting.addAll(value.idle.values());value.idle.clear();}}
            }
            for(Runnable task:waiting)task.run();
        }
    }
    static CarryLease carry(String key,Object owner){
        synchronized(PurpleSyncOperations.class){if(carries.containsKey(key))return null;CarryLease lease=new CarryLease(key,owner);carries.put(key,lease);return lease;}
    }
    static synchronized boolean carryHeld(String key){return carries.containsKey(key);}
    static synchronized PurpleSyncRunner.UndoOffer undo(String key) { return state(key).undo; }
    static synchronized void undo(String key,PurpleSyncRunner.UndoOffer undo) { state(key).undo=undo; }
    private PurpleSyncOperations() { }
}
