package org.telegram.messenger.purple;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class PurpleSyncChatPostEffect {
    interface Port {
        long now();
        default long wall(){return now();}
        default PurpleRpcRetry.Transient cooldown(){return null;}
        void renew(Consumer<Boolean> done);
        int send(Consumer<PurpleSyncAutoUpload.Result> done);
        void cancel(int token);
        void delay(Runnable task,long millis);
        void cancelDelay(Runnable task);
        default void waiting(PurpleRpcRetry.Transient value){}
    }
    private final Port port;
    private final BooleanSupplier current;
    private final Consumer<PurpleSyncAutoUpload.Result> done;
    private PurpleRpcRetry.Budget budget;
    private Runnable retry,timeout;
    private int token;
    private long ticket;
    private boolean finished;
    private PurpleRpcRetry.Transient lastCooldown;
    private long cooldownUntil;
    PurpleSyncChatPostEffect(Port port,BooleanSupplier current,Consumer<PurpleSyncAutoUpload.Result> done){
        this.port=port;this.current=current;this.done=done;
    }
    boolean live(){return !finished&&current.getAsBoolean();}
    void start(){start(false);}
    void start(boolean renewed){start(renewed,null);}
    void start(boolean renewed,PurpleRpcRetry.Transient prior){
        remember(prior);
        if(cooldownUntil>port.now())pause(prior,cooldownUntil-port.now());
        else if(renewed)send();else renew();
    }
    private void remember(PurpleRpcRetry.Transient value){
        if(value!=null&&value!=lastCooldown){
            cooldownUntil=Math.max(cooldownUntil,PurpleRpcRetry.add(port.now(),Math.max(0,value.retryAt-port.wall())));
            lastCooldown=value;
        }
    }
    private void pause(PurpleRpcRetry.Transient value,long remaining){
        if(!live()){cancel();return;}
        long run=++ticket;
        retry=()->{
            if(run!=ticket)return;
            retry=null;
            if(!live()){cancel();return;}
            if(budget!=null)budget.resume(port.now(),new PurpleRpcRetry.Transient(value.kind,port.now(),false));
            renew();
        };
        port.delay(retry,Math.max(1,remaining));
        port.waiting(new PurpleRpcRetry.Transient(value.kind,PurpleRpcRetry.add(port.wall(),remaining),false));
    }
    private void renew(){
        if(!live()){cancel();return;}
        long run=++ticket;
        port.renew(ready->{if(run!=ticket||!live())return;if(Boolean.TRUE.equals(ready))send();else finish(new PurpleSyncAutoUpload.Result(0,"AuthorizationChanged"));});
    }
    private void send(){
        if(!live()){cancel();return;}
        remember(port.cooldown());
        if(cooldownUntil>port.now()){pause(lastCooldown,cooldownUntil-port.now());return;}
        if(budget==null)budget=new PurpleRpcRetry.Budget(port.now(),120000);
        if(port.now()>budget.deadline()){fail(new PurpleSyncAutoUpload.Result(0,"Timeout",-1),true);return;}
        long run=++ticket;
        timeout=()->{if(run!=ticket)return;timeout=null;if(live())fail(new PurpleSyncAutoUpload.Result(0,"Timeout",-1),true);else cancel();};
        port.delay(timeout,Math.max(1,budget.deadline()-port.now()));
        int sent=port.send(result->{if(run!=ticket||!live())return;clearRequest();if(result!=null&&result.id>0)finish(result);else fail(result!=null?result:new PurpleSyncAutoUpload.Result(0,"Transport",-1),false);});
        if(run==ticket&&!finished)token=sent;else if(sent!=0)port.cancel(sent);
    }
    private void fail(PurpleSyncAutoUpload.Result result,boolean ownTimeout){
        clearRequest();
        remember(port.cooldown());
        PurpleRpcRetry.Transient failure=budget.failed(port.now(),result.code,result.error,ownTimeout);
        final PurpleRpcRetry.Transient wait=failure!=null&&cooldownUntil>failure.retryAt
                ?new PurpleRpcRetry.Transient(failure.kind,cooldownUntil,false):failure;
        PurpleRpcRetry.Transient shown=wait!=null?new PurpleRpcRetry.Transient(wait.kind,
                PurpleRpcRetry.add(port.wall(),Math.max(0,wait.retryAt-port.now())),wait.shortRetry):null;
        if(wait!=null&&wait.kind==PurpleRpcRetry.Kind.Flood){
            long run=++ticket;
            retry=()->{
                if(run!=ticket)return;
                retry=null;
                if(live()){budget.resume(port.now(),wait);renew();}else cancel();
            };
            port.delay(retry,Math.max(1,wait.retryAt-port.now()));port.waiting(shown);return;
        }
        finish(new PurpleSyncAutoUpload.Result(0,result.error,result.code,shown));
    }
    private void clearRequest(){if(timeout!=null){port.cancelDelay(timeout);timeout=null;}int old=token;token=0;if(old!=0)port.cancel(old);}
    private void finish(PurpleSyncAutoUpload.Result result){if(finished)return;finished=true;++ticket;clearRequest();done.accept(result);}
    void cancel(){if(finished)return;finished=true;++ticket;clearRequest();if(retry!=null){port.cancelDelay(retry);retry=null;}}
}
