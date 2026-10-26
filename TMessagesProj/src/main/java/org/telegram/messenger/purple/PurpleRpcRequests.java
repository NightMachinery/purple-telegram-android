package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLMethod;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class PurpleRpcRequests {
    private static final ConcurrentHashMap<Integer,PurpleRpcRequest> calls=new ConcurrentHashMap<>();
    private static final HashMap<String,PurpleRpcRequest.Stream> streams=new HashMap<>();
    private static final ConcurrentHashMap<Object,Waiting> waits=new ConcurrentHashMap<>();
    private static final ArrayList<Watch> observers=new ArrayList<>();
    private static final class Watch {
        final int account;final PurpleAccountFiles.Identity origin;final java.lang.ref.WeakReference<Runnable> callback;
        Watch(int account,PurpleAccountFiles.Identity origin,Runnable callback){this.account=account;this.origin=origin;this.callback=new java.lang.ref.WeakReference<>(callback);}
    }
    public static Runnable observe(int account,Runnable callback){
        Watch watch=new Watch(account,PurpleAccountFiles.capture(account),callback);observers.add(watch);
        return ()->{observers.remove(watch);java.util.Objects.requireNonNull(callback);};
    }
    private static void changed(int account){AndroidUtilities.runOnUIThread(()->{
        for(Watch watch:new ArrayList<>(observers)){Runnable callback=watch.callback.get();
            if(callback==null||watch.origin==null||!watch.origin.current()){observers.remove(watch);continue;}
            if(watch.account==account){try{callback.run();}catch(RuntimeException ignored){}}
        }
    });}
    private static final HashMap<String,PurpleRpcRetry.Budget> writeBudgets=new HashMap<>();
    private static final class Waiting {
        final int account;
        final String purpose;
        final PurpleAccountFiles.Identity origin;
        final PurpleRpcRetry.Transient value;
        final java.util.concurrent.atomic.AtomicBoolean consumed=new java.util.concurrent.atomic.AtomicBoolean();
        Waiting(int account,String purpose,PurpleAccountFiles.Identity origin,PurpleRpcRetry.Transient value){this.account=account;this.purpose=purpose;this.origin=origin;this.value=value;}
    }
    public static int send(int account,String purpose,BooleanSupplier current,TLObject request,RequestDelegate done){
        return send(account,purpose,current,request,done,null,0);
    }
    public static int send(int account,String purpose,BooleanSupplier current,TLObject request,RequestDelegate done,Consumer<PurpleRpcRetry.Transient> waiting,long timeout){
        return send(account,purpose,current,request,done,waiting,timeout,null);
    }
    public static int send(int account,String purpose,BooleanSupplier current,TLObject request,RequestDelegate done,Consumer<PurpleRpcRetry.Transient> waiting,long timeout,Runnable cancelled){
        return send(account,purpose,current,request,done,waiting,timeout,cancelled,null);
    }
    public static int send(int account,String purpose,BooleanSupplier current,TLObject request,RequestDelegate done,Consumer<PurpleRpcRetry.Transient> waiting,long timeout,Runnable cancelled,Runnable retired){
        PurpleAccountFiles.Identity origin=PurpleAccountFiles.capture(account);
        if(origin==null||request==null){if(retired!=null)retired.run();return 0;}
        PurpleRpcRequest[] holder=new PurpleRpcRequest[1];
        String key=origin.key+"/"+origin.lifetime+"/"+purpose;
        PurpleRpcRequest.Stream stream;
        synchronized(streams){stream=streams.computeIfAbsent(key,ignored->new PurpleRpcRequest.Stream());}
        PurpleRpcRequest.Port port=new PurpleRpcRequest.Port(){
            public boolean current(){return origin.current()&&current.getAsBoolean();}
            public long now(){return System.currentTimeMillis();}
            public void dispatch(Runnable task){AndroidUtilities.runOnUIThread(task);}
            public void delay(Runnable task,long millis){AndroidUtilities.runOnUIThread(task,millis);}
            public void cancelDelay(Runnable task){AndroidUtilities.cancelRunOnUIThread(task);}
            public int send(TLObject value,PurpleRpcRequest.Reply reply){return ConnectionsManager.getInstance(account).sendRequest(value,reply::accept,ConnectionsManager.RequestFlagFailOnServerErrors);}
            public void cancel(int token){ConnectionsManager.getInstance(account).cancelRequest(token,true);}
            public void waiting(PurpleRpcRetry.Transient value){
                if(!current())return;
                waits.put(holder[0].id,new Waiting(account,purpose,origin,value));
                changed(account);if(waiting!=null)waiting.accept(value);
            }
        };
        PurpleRpcRequest call=new PurpleRpcRequest(stream,port,request,timeout>0?timeout:30000,done::run,ended->{
            calls.remove(ended.id,ended);waits.remove(ended.id);if(retired!=null)retired.run();changed(account);
            synchronized(streams){if(stream.empty())streams.remove(key,stream);}
            if(ended.abandoned()&&cancelled!=null)cancelled.run();
        });
        holder[0]=call;calls.put(call.id,call);call.start();return call.id;
    }
    public static <T extends TLObject> int sendTyped(int account,String purpose,BooleanSupplier current,TLMethod<T> request,org.telegram.messenger.Utilities.Callback2<T,org.telegram.tgnet.TLRPC.TL_error> done,Consumer<PurpleRpcRetry.Transient> waiting){
        return send(account,purpose,current,request,(response,error)->{
            @SuppressWarnings("unchecked") T value=(T)response;done.run(value,error);
        },waiting,30000);
    }
    public static PurpleRpcRetry.Transient writeFailure(int account,String purpose,org.telegram.tgnet.TLRPC.TL_error error){
        PurpleAccountFiles.Identity origin=PurpleAccountFiles.capture(account);
        if(origin==null||!origin.current())return null;
        String key=origin.key+"/"+origin.lifetime+"/write/"+purpose;
        if(error==null){waits.remove(key);synchronized(writeBudgets){writeBudgets.remove(key);}changed(account);return null;}
        PurpleRpcRetry.Transient value;
        synchronized(writeBudgets){value=writeBudgets.computeIfAbsent(key,ignored->new PurpleRpcRetry.Budget(System.currentTimeMillis(),1)).failed(System.currentTimeMillis(),error.code,error.text,false);}
        if(value!=null)waits.put(key,new Waiting(account,purpose,origin,value));else waits.remove(key);
        changed(account);
        return value;
    }
    public static void cancel(int token){PurpleRpcRequest call=calls.get(token);if(call!=null)call.cancel();}
    static void waiting(int account,Object owner,PurpleRpcRetry.Transient value){
        if(value==null){waits.remove(owner);changed(account);return;}
        PurpleAccountFiles.Identity origin=PurpleAccountFiles.capture(account);
        if(origin!=null&&origin.current()){waits.put(owner,new Waiting(account,"sync-chat",origin,value));changed(account);}
    }
    public static long takeWriteRetryAt(int account,String purpose){
        PurpleAccountFiles.Identity origin=PurpleAccountFiles.capture(account);
        Waiting value=origin!=null?waits.get(origin.key+"/"+origin.lifetime+"/write/"+purpose):null;
        return value!=null&&value.origin.current()&&value.consumed.compareAndSet(false,true)?value.value.retryAt:0;
    }
    public static PurpleRpcRetry.Transient writeWaiting(int account,String purpose){
        PurpleAccountFiles.Identity origin=PurpleAccountFiles.capture(account);
        Waiting value=origin!=null?waits.get(origin.key+"/"+origin.lifetime+"/write/"+purpose):null;
        return value!=null&&value.origin.current()&&value.value.retryAt>System.currentTimeMillis()?value.value:null;
    }
    public static PurpleRpcRetry.Transient waiting(int account){return waiting(account,null);}
    public static PurpleRpcRetry.Transient waiting(int account,String purpose){
        PurpleRpcRetry.Transient result=null;
        for(Waiting entry:waits.values())if(entry.account==account&&(purpose==null||purpose.equals(entry.purpose))&&entry.origin.current()&&entry.value.retryAt>System.currentTimeMillis()&&(result==null||entry.value.retryAt>result.retryAt))result=entry.value;
        return result;
    }
    public static String text(PurpleRpcRetry.Transient value){
        String time=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(value.retryAt));
        return String.format(java.util.Locale.getDefault(),LocaleController.getString(value.kind==PurpleRpcRetry.Kind.Flood?"PurpleTelegramWaitUntil":"PurpleTelegramRetryAt"),time);
    }
    public static void identityChanged(int account){AndroidUtilities.runOnUIThread(()->{
        for(java.util.Map.Entry<Object,Waiting> item:new ArrayList<>(waits.entrySet()))if(item.getValue().account==account&&!item.getValue().origin.current()){waits.remove(item.getKey(),item.getValue());synchronized(writeBudgets){writeBudgets.remove(item.getKey());}}
        changed(account);
        for(PurpleRpcRequest call:new ArrayList<>(calls.values()))call.checkCurrent();
    });}
    private PurpleRpcRequests(){}
}
