package org.telegram.messenger.purple;

import java.util.function.Consumer;

public final class PurpleSyncChatFlows implements AutoCloseable {
    interface Factory {
        PurpleSyncChatMoveRecovery.Port change(Consumer<PurpleSyncChatChange.State> changed);
        PurpleSyncChatMoveConsent.Port consent(Consumer<PurpleSyncChatMoveConsent.State> changed);
    }
    public static final class Handle implements AutoCloseable {
        private final Runnable retry;
        private final AutoCloseable flow;
        private final Object context;
        private final PurpleSyncChatFlows owner;
        private volatile boolean closed;
        Handle(AutoCloseable flow,Runnable retry,Object context,PurpleSyncChatFlows owner){this.flow=flow;this.retry=retry;this.context=context;this.owner=owner;}
        public boolean current(){
            synchronized(owner){if(closed||owner.closed||owner.active!=this)return false;}
            boolean live=context instanceof PurpleSyncChatMove.Port?((PurpleSyncChatMove.Port)context).current()
                    :context instanceof PurpleSyncChatChange.Port?((PurpleSyncChatChange.Port)context).current()
                    :context instanceof PurpleSyncChatMoveConsent.Port&&((PurpleSyncChatMoveConsent.Port)context).current();
            synchronized(owner){return live&&!closed&&!owner.closed&&owner.active==this;}
        }
        public void retry(){if(!closed)retry.run();}
        @Override public void close(){synchronized(this){if(closed)return;closed=true;}try{flow.close();}catch(Exception|LinkageError ignored){}finally{owner.release(this);}}
    }
    private Handle active;
    private volatile boolean closed;
    private Handle own(AutoCloseable flow,Runnable retry,Object context){
        Handle next=new Handle(flow,retry,context,this),old;
        synchronized(this){if(closed)old=next;else{old=active;active=next;}}
        if(old!=null)old.close();return old==next?null:next;
    }
    Handle change(boolean enabled,boolean cleanup,Factory factory,PurpleSyncChatScreen.ChangeSelection selection,Consumer<PurpleSyncChatChange.State> changed){
        if(!enabled){changed.accept(new PurpleSyncChatChange.State("Held","QualificationHeld",false));return null;}
        if(closed||selection==null)return null;
        PurpleSyncChatChange.Port port=factory.change(changed);
        PurpleSyncChatChange flow=new PurpleSyncChatChange(port,selection);flow.cleanupEnabled(cleanup);
        Handle handle=own(flow,flow::retry,port);if(handle!=null)flow.start();return handle;
    }
    Handle consent(boolean enabled,boolean cleanup,Factory factory,PurpleSyncChatMoveConsent.Selection selection,Consumer<PurpleSyncChatMoveConsent.State> changed){
        if(!enabled){changed.accept(new PurpleSyncChatMoveConsent.State("Held","QualificationHeld",false));return null;}
        if(closed||selection==null)return null;
        PurpleSyncChatMoveConsent.Port port=factory.consent(changed);
        PurpleSyncChatMoveConsent flow=new PurpleSyncChatMoveConsent(port,selection);flow.cleanupEnabled(cleanup);
        Handle handle=own(flow,flow::retry,port);if(handle!=null)flow.start();return handle;
    }
    Handle recover(boolean enabled,boolean cleanup,Factory factory,PurpleSyncChatScreen.Frame opening,PurpleSyncChatMoveRecovery.ActionChoice choice,
            PurpleSyncChatScreen.ChangeSelection selection,Consumer<PurpleSyncChatChange.State> changed){
        if(!enabled){changed.accept(PurpleSyncChatMoveRecovery.production());return null;}
        if(closed)return null;
        PurpleSyncChatMoveRecovery.Port port=factory.change(changed);
        PurpleSyncChatMoveRecovery flow=new PurpleSyncChatMoveRecovery(port,opening,choice,selection);flow.cleanupEnabled(cleanup);
        Handle handle=own(flow,flow::retry,port);if(handle!=null)flow.start();return handle;
    }
    Handle resume(PurpleSyncChatMove.Port port,PurpleSyncChatClient.Info info,boolean cleanup){
        if(closed)return null;
        PurpleSyncChatMove move=new PurpleSyncChatMove(port,cleanup);Handle handle=own(move,move::retry,port);if(handle!=null)move.resume(info);return handle;
    }
    private synchronized void release(Handle handle){if(active==handle)active=null;}
    void cancel(Object context){
        Handle old;
        synchronized(this){old=active;if(old==null||old.context!=context)return;active=null;}
        old.close();
    }
    void cancelActive(){Handle old;synchronized(this){old=active;active=null;}if(old!=null)old.close();}
    @Override public void close(){closed=true;cancelActive();}
}
