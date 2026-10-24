package org.telegram.messenger.purple;

import java.util.HashMap;

final class PurpleSyncOperations {
    private static final HashMap<String, State> accounts = new HashMap<>();
    private static final class State {
        Object owner;
        final java.util.LinkedHashMap<Object,Runnable> idle=new java.util.LinkedHashMap<>();
        java.util.function.BooleanSupplier current;
        PurpleSyncRunner.UndoOffer undo;
    }
    private static State state(String key) {
        State value = accounts.get(key);
        if (value == null) { value = new State(); accounts.put(key, value); }
        return value;
    }
    private static State live(String key) {
        State value=state(key);
        if(value.owner!=null&&value.current!=null&&!value.current.getAsBoolean()){value.owner=null;value.current=null;}
        return value;
    }
    static synchronized boolean busy(String key) { return live(key).owner != null; }
    static synchronized boolean begin(String key, Object owner,java.util.function.BooleanSupplier current) {
        State value = live(key);
        if (value.owner != null) return false;
        value.owner = owner;value.current=current;
        return true;
    }
    static synchronized void end(String key, Object owner) {
        State value = state(key);
        if (value.owner == owner) { value.owner = null;value.current=null;java.util.ArrayList<Runnable> waiting=new java.util.ArrayList<>(value.idle.values());value.idle.clear();for(Runnable task:waiting)task.run(); }
    }
    static synchronized void whenIdle(String key,Object owner,Runnable callback) {State value=live(key);if(value.owner==null)callback.run();else value.idle.put(owner,callback);}
    static synchronized PurpleSyncRunner.UndoOffer undo(String key) { return state(key).undo; }
    static synchronized void undo(String key, PurpleSyncRunner.UndoOffer undo) { state(key).undo = undo; }
    private PurpleSyncOperations() { }
}
