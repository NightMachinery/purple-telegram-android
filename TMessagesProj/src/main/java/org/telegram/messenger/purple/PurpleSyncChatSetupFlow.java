package org.telegram.messenger.purple;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class PurpleSyncChatSetupFlow implements AutoCloseable {
    public static final class State {
        public final String phase,reason;
        public final boolean busy,resumable,newReaders,canReview;
        public final PurpleSyncChatCore.Peer target;
        State(String phase,String reason,boolean busy,boolean resumable,boolean newReaders){this(phase,reason,busy,resumable,newReaders,null,false);}
        State(String phase,String reason,boolean busy,boolean resumable,boolean newReaders,PurpleSyncChatCore.Peer target,boolean canReview){
            this.phase=phase;this.reason=reason;this.busy=busy;this.resumable=resumable;this.newReaders=newReaders;this.target=target;this.canReview=canReview;
        }
    }
    private final PurpleSyncChat.Holder holder;
    private final BooleanSupplier foreground;
    private final Consumer<State> observer;
    private volatile boolean closed;
    private volatile State state=new State("Opening","",false,false,false);
    private PurpleSyncChatSetupEnvironment env;
    private PurpleSyncChatSetup setup;
    PurpleSyncChatSetupFlow(PurpleSyncChat.Holder holder,BooleanSupplier foreground,Consumer<State> observer){
        this.holder=holder;this.foreground=foreground;this.observer=observer;
        worker(this::open);
    }
    static boolean postingHeld(java.io.File journal){
        return PurpleAccountFileSnapshot.read(journal,16384).status!=PurpleAccountFileSnapshot.Status.Missing;
    }
    public State state(){return state;}
    public boolean current(){return !closed&&foreground.getAsBoolean()&&holder.env.context.current();}
    private void worker(Runnable task){PurpleSyncChat.queue().postRunnable(()->{
        try{task.run();}catch(RuntimeException|LinkageError error){if(setup!=null)setup.close();publish(new State("Held","SetupUnavailable",false,state.resumable,false));}
    });}
    private void publish(State next){
        state=next;
        holder.env.ui(()->{if(current())try{observer.accept(next);}catch(RuntimeException|LinkageError error){close();}});
    }
    private void open(){
        if(!current()||!holder.env.current())return;
        env=new PurpleSyncChatSetupEnvironment(holder,foreground,()->!closed);
        try {
            byte[] raw=env.journal().read();
            PurpleSyncChatSetup.Journal journal=raw!=null?PurpleSyncChatSetup.Journal.parse(raw,env.origin(),PurpleSyncChatSetup.authority(env.authority())):null;
            publish(new State(journal!=null&&journal.step==PurpleSyncChatSetup.Step.Complete?"Complete":"Ready","",false,
                    journal!=null&&journal.step!=PurpleSyncChatSetup.Step.Complete,journal!=null&&journal.newReaders,journal!=null?journal.chat:null,false));
        }catch(Exception|LinkageError error){publish(new State("Held","JournalUnavailable",false,true,false));}
    }
    private void changed(PurpleSyncChatSetup.Phase phase){
        PurpleSyncChatSetup.Journal journal=setup.journal();
        boolean unissued=journal!=null&&journal.mark==null&&(journal.step==PurpleSyncChatSetup.Step.Ready||journal.step==PurpleSyncChatSetup.Step.PeerInfo
                ||journal.step==PurpleSyncChatSetup.Step.MarkStarted&&journal.deferred);
        publish(new State(phase.name(),setup.reason(),setup.busy(),journal!=null&&journal.step!=PurpleSyncChatSetup.Step.Complete,setup.newReaders(),
                journal!=null?journal.chat:null,phase==PurpleSyncChatSetup.Phase.Held&&unissued&&journal.chat!=null));
    }
    public void begin(PurpleSyncChatScreen.Selection selection){
        worker(()->{
            if(!current()||env==null||setup!=null&&setup.busy())return;
            if(selection!=null&&state.resumable&&(!state.canReview||selection.createChannel||!java.util.Objects.equals(selection.chat,state.target))){
                publish(new State("Held","PendingSetup",false,true,state.newReaders,state.target,state.canReview));return;
            }
            setup=new PurpleSyncChatSetup(env,selection,this::changed);env.setup=setup;setup.start();
            if(setup.phase()==PurpleSyncChatSetup.Phase.Ready)publish(new State("Held","OperationBusy",false,state.resumable,false));
        });
    }
    public void resume(){begin(null);}
    public void tryAgain(){worker(()->{if(current()&&setup!=null){setup.tryAgain();if(!setup.busy()&&setup.phase()==PurpleSyncChatSetup.Phase.Ready)publish(new State("Held","OperationBusy",false,state.resumable,false));}});}
    public void useSavedMessages(){worker(()->{if(current()&&setup!=null)setup.useSavedMessages();});}
    @Override public void close(){
        if(closed)return;closed=true;
        worker(()->{if(setup!=null)setup.close();else if(env!=null)env.cancel();});
    }
}
