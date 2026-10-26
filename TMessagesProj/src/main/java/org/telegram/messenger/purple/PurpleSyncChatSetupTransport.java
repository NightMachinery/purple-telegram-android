package org.telegram.messenger.purple;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.util.function.Consumer;

final class PurpleSyncChatSetupTransport {
    interface Port {
        boolean current(PurpleSyncChatSetup.Command command);
        void dispatch(Runnable task);
        int send(TLObject request,PurpleSyncChatTelegram.Reply reply);
        void cancel(int token);
        void delay(Runnable task,long millis);
        void cancelDelay(Runnable task);
        long nowSeconds();
        void resumeEffect(PurpleSyncChatSetup.Command command,Consumer<Boolean> ready);
        boolean waiting(PurpleSyncChatSetup.Command command,long retryAt);
        boolean retain(PurpleSyncChatSetup.Command command,TLObject request,TLRPC.Updates updates);
    }
    private final Port port;
    private long generation;
    private Call active;
    private final class Call {
        final PurpleSyncChatSetup.Command command;
        final TLObject request;
        final Consumer<PurpleSyncChatSetup.Reply<TLObject>> done;
        final long generation;
        long attempt;
        int token;
        Runnable retry;
        Call(PurpleSyncChatSetup.Command command,TLObject request,Consumer<PurpleSyncChatSetup.Reply<TLObject>> done){
            this.command=command;this.request=request;this.done=done;generation=++PurpleSyncChatSetupTransport.this.generation;
        }
        boolean current(){return active==this&&generation==PurpleSyncChatSetupTransport.this.generation&&port.current(command);}
        void attempt(){
            if(!current())return;
            port.resumeEffect(command,allowed->port.dispatch(()->{
                if(!current())return;
                if(!Boolean.TRUE.equals(allowed)){finish(PurpleSyncChatSetup.Error.Unknown,null,0);return;}
                long sentAttempt=++attempt;boolean[] returned={false};
                try {
                    int sent=port.send(request,(response,error)->port.dispatch(()->{
                        returned[0]=true;
                        boolean retained=!(response instanceof TLRPC.Updates)||port.retain(command,request,(TLRPC.Updates)response);
                        if(!current()||sentAttempt!=attempt)return;
                        token=0;
                        int seconds=PurpleSyncChatSetupRequests.floodSeconds(error);
                        if(seconds>0) {
                            long deadline=PurpleRpcRetry.add(port.nowSeconds(),seconds);
                            if(!port.waiting(command,deadline))return;
                            retry=()->{retry=null;if(current())attempt();};port.delay(retry,seconds*1000L);return;
                        }
                        finish(retained?PurpleSyncChatSetupRequests.error(error):PurpleSyncChatSetup.Error.Unknown,
                                retained&&error==null?response:null,0);
                    }));
                    if(current()&&sentAttempt==attempt&&!returned[0]) {
                        token=sent;if(sent<=0)finish(PurpleSyncChatSetup.Error.Unknown,null,0);
                    } else if(sent>0)port.cancel(sent);
                } catch(RuntimeException|LinkageError error){if(current())finish(PurpleSyncChatSetup.Error.Unknown,null,0);}
            }));
        }
        void finish(PurpleSyncChatSetup.Error error,TLObject result,int seconds){
            if(!current())return;
            active=null;if(retry!=null)port.cancelDelay(retry);retry=null;token=0;
            done.accept(new PurpleSyncChatSetup.Reply<>(command.origin,error,result,seconds));
        }
        void cancel(){if(retry!=null)port.cancelDelay(retry);retry=null;int stopped=token;token=0;if(stopped!=0)port.cancel(stopped);}
    }
    PurpleSyncChatSetupTransport(Port port){this.port=port;}
    void write(PurpleSyncChatSetup.Command command,TLObject request,Consumer<PurpleSyncChatSetup.Reply<TLObject>> done){
        cancel();if(command==null||request==null){if(command!=null)done.accept(new PurpleSyncChatSetup.Reply<>(command.origin,PurpleSyncChatSetup.Error.Unknown,null));return;}
        active=new Call(command,request,done);active.attempt();
    }
    void cancel(){++generation;Call stopped=active;active=null;if(stopped!=null)stopped.cancel();}
}
