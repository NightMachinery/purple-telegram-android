package org.telegram.messenger.purple;

import java.util.*;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;

final class PurpleSyncChatPostEffectCases {
    static final class Port implements PurpleSyncChatPostEffect.Port {
        long clock=1000,wall=Long.MIN_VALUE;PurpleRpcRetry.Transient cooldown;int renewals,sends,cancels;boolean current=true,allow=true,defer;
        final Map<Runnable,Long> timers=new LinkedHashMap<>();
        final List<Consumer<PurpleSyncAutoUpload.Result>> replies=new ArrayList<>();
        Consumer<Boolean> approval;PurpleRpcRetry.Transient waiting;
        public void waiting(PurpleRpcRetry.Transient value){waiting=value;}
        public long now(){return clock;}
        public long wall(){return wall==Long.MIN_VALUE?clock:wall;}
        public PurpleRpcRetry.Transient cooldown(){return cooldown;}
        public void renew(Consumer<Boolean> done){renewals++;if(defer)approval=done;else done.accept(allow);}
        public int send(Consumer<PurpleSyncAutoUpload.Result> done){sends++;replies.add(done);return sends;}
        public void cancel(int token){cancels++;}
        public void delay(Runnable task,long millis){timers.put(task,clock+millis);}
        public void cancelDelay(Runnable task){timers.remove(task);}
        void through(long now){clock=now;for(Map.Entry<Runnable,Long> e:new ArrayList<>(timers.entrySet()))if(e.getValue()<=now&&timers.remove(e.getKey())!=null)e.getKey().run();}
        void reply(int code,String type){replies.get(replies.size()-1).accept(new PurpleSyncAutoUpload.Result(0,type,code));}
    }
    static void run(){
        int before=checks;
        for(String flood:new String[]{"FLOOD_WAIT_8","FLOOD_PREMIUM_WAIT_8"}){
            Port p=new Port();List<PurpleSyncAutoUpload.Result> done=new ArrayList<>();PurpleSyncChatPostEffect effect=new PurpleSyncChatPostEffect(p,()->p.current,done::add);
            effect.start();check(p.sends==1&&p.renewals==1,"manual effect initially renews");
            Runnable old=p.timers.keySet().iterator().next();p.reply(420,flood);
            check(done.isEmpty()&&p.timers.size()==1&&p.sends==1,"one short flood timer retains original completion");
            p.through(9999);check(p.sends==1,"short flood waits X plus one");p.through(10000);
            check(p.sends==2&&p.renewals==2,"definite flood dispatch renews again");
            old.run();check(done.isEmpty()&&p.timers.size()==1,"stale effect deadline cannot clear rearmed timer");
            p.replies.get(0).accept(new PurpleSyncAutoUpload.Result(99,null));check(done.isEmpty(),"superseded callback cannot complete newer effect");
            p.reply(420,flood);check(done.isEmpty()&&p.waiting!=null&&!p.waiting.shortRetry,"second flood is a typed scheduled deferral");
            check(p.timers.size()==1&&p.sends==2,"repeated flood retains exactly one logical-operation timer");
            p.through(18000);check(p.sends==3&&p.renewals==3,"long deferral renews the same effect before dispatch");
            effect.cancel();check(p.timers.isEmpty(),"closing a long wait cancels its retained effect");
        }
        for(String reason:new String[]{"INTERNAL","Transport","CHANNEL_PRIVATE"}){
            Port p=new Port();List<PurpleSyncAutoUpload.Result> done=new ArrayList<>();new PurpleSyncChatPostEffect(p,()->p.current,done::add).start();
            p.reply(reason.startsWith("FLOOD")?420:reason.equals("INTERNAL")?500:reason.equals("Transport")?-1:400,reason);
            check(done.size()==1&&p.sends==1&&p.timers.isEmpty(),"no blind replay "+reason);
            check((done.get(0).waiting!=null)==!reason.equals("CHANNEL_PRIVATE"),"transient and permanent distinction "+reason);
            if(reason.startsWith("FLOOD"))check(done.get(0).waiting.retryAt==91000,"long flood carries exact retryAt");
        }
        Port longWait=new Port();List<PurpleSyncAutoUpload.Result> longResult=new ArrayList<>();PurpleSyncChatPostEffect longEffect=new PurpleSyncChatPostEffect(longWait,()->longWait.current,longResult::add);longEffect.start();longWait.reply(420,"FLOOD_WAIT_90");
        check(longResult.isEmpty()&&longWait.waiting.retryAt==91000&&longWait.timers.size()==1,"long flood keeps original callback and retryAt");
        longWait.through(91000);check(longWait.sends==2&&longWait.renewals==2,"long flood resume renews before dispatch");longEffect.cancel();
        for(String stop:new String[]{"rights","cancel","account"}){
            Port p=new Port();List<PurpleSyncAutoUpload.Result> done=new ArrayList<>();PurpleSyncChatPostEffect effect=new PurpleSyncChatPostEffect(p,()->p.current,done::add);effect.start();p.reply(420,"FLOOD_WAIT_8");
            if(stop.equals("rights"))p.allow=false;else if(stop.equals("cancel"))effect.cancel();else p.current=false;
            p.through(10000);check(p.sends==1,"deferred effect refuses changed authority "+stop);
            check(p.timers.isEmpty(),"changed authority leaves no timer "+stop);
        }
        Port p=new Port();List<PurpleSyncAutoUpload.Result> done=new ArrayList<>();new PurpleSyncChatPostEffect(p,()->p.current,done::add).start();p.through(121000);
        check(done.size()==1&&done.get(0).waiting.kind==PurpleRpcRetry.Kind.Timeout&&p.sends==1,"own timeout is typed ambiguous with pending reconciliation");
        Port success=new Port();List<PurpleSyncAutoUpload.Result> result=new ArrayList<>();new PurpleSyncChatPostEffect(success,()->success.current,result::add).start(true);success.replies.get(0).accept(new PurpleSyncAutoUpload.Result(7,null));
        check(result.size()==1&&result.get(0).id==7&&success.renewals==0&&success.timers.isEmpty(),"initial already-renewed dispatch has one success");
        Port cooldown=new Port();List<PurpleSyncAutoUpload.Result> cooled=new ArrayList<>();
        PurpleSyncChatPostEffect cooledEffect=new PurpleSyncChatPostEffect(cooldown,()->cooldown.current,cooled::add);
        cooledEffect.start(true,new PurpleRpcRetry.Transient(PurpleRpcRetry.Kind.Server,6000,false));
        check(cooldown.sends==0&&cooldown.renewals==0&&cooldown.timers.size()==1,"prior cooldown delays even already-approved dispatch");
        cooldown.through(5999);check(cooldown.sends==0,"prior cooldown cannot dispatch early");
        cooldown.through(6000);check(cooldown.sends==1&&cooldown.renewals==1,"prior cooldown renews fresh proof before first dispatch");cooledEffect.cancel();
        Port gate=new Port();gate.defer=true;List<PurpleSyncAutoUpload.Result> gated=new ArrayList<>();
        PurpleSyncChatPostEffect gatedEffect=new PurpleSyncChatPostEffect(gate,()->gate.current,gated::add);gatedEffect.start();gatedEffect.cancel();gate.approval.accept(true);
        check(gate.sends==0&&gated.isEmpty()&&gate.timers.isEmpty(),"cancelled deferred proof cannot revive dispatch");
        for(long skew:new long[]{-86400000,86400000}){
            Port split=new Port();split.wall=1700000000000L;List<PurpleSyncAutoUpload.Result> splitResults=new ArrayList<>();
            new PurpleSyncChatPostEffect(split,()->split.current,splitResults::add).start();split.reply(420,"FLOOD_WAIT_8");
            check(split.waiting.retryAt==1700000009000L,"retryAt is wall time while budget uses monotonic time");
            split.wall+=skew;split.through(9999);check(split.sends==1,"wall adjustment cannot accelerate retry");
            split.through(10000);check(split.sends==2&&split.renewals==2,"wall adjustment cannot strand retry");
            split.through(130000);check(splitResults.size()==1&&splitResults.get(0).waiting.kind==PurpleRpcRetry.Kind.Timeout,"stalled post-retry RPC expires on monotonic extended deadline");
        }
        Port overlap=new Port();overlap.wall=1700000000000L;List<PurpleSyncAutoUpload.Result> overlapResults=new ArrayList<>();
        PurpleSyncChatPostEffect overlapEffect=new PurpleSyncChatPostEffect(overlap,()->overlap.current,overlapResults::add);overlapEffect.start();
        overlap.cooldown=new PurpleRpcRetry.Transient(PurpleRpcRetry.Kind.Flood,overlap.wall+90000,false);overlap.reply(420,"FLOOD_WAIT_8");
        check(overlap.timers.size()==1&&overlap.waiting.retryAt==overlap.wall+90000,"combined wait selects latest active deadline");
        overlap.wall+=86400000;overlap.through(10000);check(overlap.sends==1,"short retry cannot escape longer account cooldown");
        overlap.through(91000);check(overlap.sends==2&&overlap.renewals==2,"captured account cooldown uses monotonic remaining time despite wall jump");overlapEffect.cancel();
        byte[] bytes={1,2,3};long upload=PurpleSyncAutoUpload.identity("upload",bytes),send=PurpleSyncAutoUpload.identity("send",bytes);
        check(upload!=0&&send!=0&&upload!=send,"file and send identities are separate nonzero domains");
        check(send==PurpleSyncAutoUpload.identity("send",bytes.clone())&&send!=PurpleSyncAutoUpload.identity("send",new byte[]{1,2,4}),"same durable staged bytes retain identity while another record differs");
        System.out.println("MANUAL_POST_EFFECT_CHECKS="+(checks-before));
    }
}
