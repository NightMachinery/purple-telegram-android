package org.telegram.messenger.purple;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class PurpleRpcRequest {
    public interface Reply {void accept(TLObject response,TLRPC.TL_error error);}
    public interface Port {
        boolean current();
        long now();
        void dispatch(Runnable task);
        void delay(Runnable task,long millis);
        void cancelDelay(Runnable task);
        int send(TLObject request,Reply done);
        void cancel(int token);
        default void waiting(PurpleRpcRetry.Transient value){}
    }
    public static final class Stream {
        private final ArrayDeque<PurpleRpcRequest> requests=new ArrayDeque<>();
        private boolean driving;
        private Runnable pending;
        private PurpleRpcRequest pendingOwner;
        private void drive(){
            if(driving||pending!=null)return;
            driving=true;
            try{
                while(!requests.isEmpty()){
                    PurpleRpcRequest next=requests.peekFirst();
                    if(next.finished){requests.removeFirst();continue;}
                    if(!next.port.current()){next.finish(null,null,false);continue;}
                    if(!next.sending)next.attempt();
                    if(!next.finished)break;
                }
            }finally{driving=false;}
        }
        private void waitFor(PurpleRpcRequest owner,PurpleRpcRetry.Transient value){
            if(pending!=null)throw new IllegalStateException("one pending retry per stream");
            pendingOwner=owner;
            pending=()->{
                if(pendingOwner!=owner||owner.finished)return;
                long remaining=value.retryAt-owner.port.now();
                if(remaining>0){owner.port.delay(pending,remaining);return;}
                pending=null;pendingOwner=null;
                owner.budget.resume(owner.port.now(),value);owner.sending=false;drive();
            };
            owner.port.delay(pending,Math.max(0,value.retryAt-owner.port.now()));
        }
        private void remove(PurpleRpcRequest owner){
            if(pendingOwner==owner){owner.port.cancelDelay(pending);pending=null;pendingOwner=null;}
            requests.remove(owner);
        }
        public int pendingRetries(){return pending==null?0:1;}
        public boolean empty(){return requests.isEmpty();}
        public void cancel(){for(PurpleRpcRequest request:new ArrayList<>(requests))request.cancel();}
    }
    private static final AtomicInteger ids=new AtomicInteger(1);
    public final int id=ids.getAndUpdate(value->value==Integer.MAX_VALUE?1:value+1)*-1;
    private final Stream stream;
    private final Port port;
    private final TLObject request;
    private final Reply done;
    private final Consumer<PurpleRpcRequest> ended;
    private final PurpleRpcRetry.Budget budget;
    private final boolean originalDisableFree;
    private boolean finished,sending,abandoned;
    public boolean abandoned(){return abandoned;}
    private int token;
    private long generation;
    private Runnable timeout;
    public PurpleRpcRequest(Stream stream,Port port,TLObject request,long timeout,Reply done,Consumer<PurpleRpcRequest> ended){
        this.stream=stream;this.port=port;this.request=request;this.done=done;this.ended=ended;
        budget=new PurpleRpcRetry.Budget(port.now(),timeout);originalDisableFree=request.disableFree;
    }
    public void start(){port.dispatch(()->{if(finished)return;request.disableFree=true;stream.requests.addLast(this);stream.drive();});}
    public void checkCurrent(){port.dispatch(()->{if(!finished&&!port.current()){finish(null,null,false);stream.drive();}});}
    public void cancel(){port.dispatch(()->{if(!finished){finish(null,null,false);stream.drive();}});}
    private void cancelTimeout(){if(timeout!=null){port.cancelDelay(timeout);timeout=null;}}
    private void attempt(){
        if(finished||!port.current()){finish(null,null,false);return;}
        if(port.now()>=budget.deadline()){sending=true;failed(-504,"REQUEST_TIMEOUT",true);return;}
        sending=true;long run=++generation;
        timeout=()->{
            if(finished||generation!=run)return;
            int old=token;token=0;++generation;if(old>0)port.cancel(old);
            cancelTimeout();failed(-504,"REQUEST_TIMEOUT",true);
        };
        port.delay(timeout,Math.max(0,budget.deadline()-port.now()));
        try{
            int sent=port.send(request,(response,error)->port.dispatch(()->{
                if(finished||generation!=run)return;
                token=0;cancelTimeout();
                if(!port.current()){finish(null,null,false);return;}
                if(port.now()>budget.deadline()){failed(-504,"REQUEST_TIMEOUT",true);return;}
                if(error!=null){failed(error.code,error.text,false);return;}
                finish(response,null,true);stream.drive();
            }));
            if(!finished&&generation==run){token=sent;if(sent<=0){cancelTimeout();failed(-1,"TRANSPORT_UNAVAILABLE",false);}}
            else if(sent>0)port.cancel(sent);
        }catch(RuntimeException error){cancelTimeout();failed(-1,"TRANSPORT_UNAVAILABLE",false);}
    }
    private void failed(int code,String type,boolean ownTimeout){
        if(finished)return;
        ++generation;
        PurpleRpcRetry.Transient outcome=budget.failed(port.now(),code,type,ownTimeout);
        if(outcome==null){TLRPC.TL_error error=new TLRPC.TL_error();error.code=code;error.text=type;finish(null,error,true);stream.drive();return;}
        stream.waitFor(this,outcome);
        try{port.waiting(outcome);}catch(RuntimeException error){if(!port.current()){finish(null,null,false);stream.drive();}}
    }
    private void finish(TLObject response,TLRPC.TL_error error,boolean deliver){
        if(finished)return;finished=true;abandoned=!deliver;++generation;cancelTimeout();stream.remove(this);
        int old=token;token=0;if(old>0)port.cancel(old);
        request.disableFree=originalDisableFree;if(!originalDisableFree)request.freeResources();
        ended.accept(this);
        if(deliver&&port.current())done.accept(response,error);
    }
}
