package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatFlowPort implements PurpleSyncChatMoveRecovery.Port, PurpleSyncChatMoveConsent.Port {
    private final PurpleSyncChat.Holder holder;
    private final BooleanSupplier foreground;
    private final Consumer<PurpleSyncChatChange.State> changed;
    private final Consumer<PurpleSyncChatMoveConsent.State> consent;
    private final Set<PurpleSyncChatTelegram> readers=new HashSet<>();
    private volatile boolean closed;
    private PurpleSyncChatCarry carry;
    private PurpleSyncChatSetupEnvironment provision;
    PurpleSyncChatFlowPort(PurpleSyncChat.Holder holder,BooleanSupplier foreground,Consumer<PurpleSyncChatChange.State> changed,
            Consumer<PurpleSyncChatMoveConsent.State> consent){this.holder=holder;this.foreground=foreground;this.changed=changed;this.consent=consent;}
    public PurpleSyncChatOrigin origin(){return holder.env.origin;}
    public String authority(){return holder.env.authority;}
    public boolean current(){return !closed&&holder.env.current();}
    public boolean foreground(){return foreground.getAsBoolean();}
    public void worker(Runnable task){holder.env.worker(task);}
    private boolean live(){return current()&&foreground();}
    private PurpleSyncChatCarry dispatcher(){
        if(carry==null)carry=PurpleSyncChatCarry.production(holder,this::current,this::foreground,null);
        return carry;
    }
    public void resolve(Consumer<PurpleSyncChatScreen.Frame> done){resolve("config",done);}
    public void resolve(String stream,Consumer<PurpleSyncChatScreen.Frame> done){if(live())dispatcher().freshFrame(stream,done);}
    public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> done){
        holder.env.ui(()->{
            if(!live())return;
            PurpleSyncChatTelegram reader=new PurpleSyncChatTelegram(origin(),PurpleSyncChatTelegram.platform(origin(),this::live));readers.add(reader);
            reader.info(target,info->{readers.remove(reader);reader.cancel();worker(()->{if(live())done.accept(info);});});
        });
    }
    public PurpleSyncChatSetup.Env provision(){
        if(provision==null)provision=new PurpleSyncChatSetupEnvironment(holder,foreground,this::current,true);
        return provision;
    }
    private JSONObject begin(PurpleSyncChatService.Binding opening)throws Exception {
        PurpleAccountFileSnapshot.worker();
        PurpleAccountBinding.Binding binding=PurpleAccountBinding.readForCheck(origin().slot);
        PurpleSyncChatService.Binding actual=holder.env.binding();
        if(!live()||!binding.current()||!opening.same(actual)||!opening.stateStamp.equals(actual.stateStamp))return null;
        return beginning(binding.token);
    }
    static JSONObject beginning(String token)throws Exception {
        return new JSONObject().put("preferenceToken",PurpleSyncRetainedBases.encode(token.getBytes(StandardCharsets.UTF_8)))
                .put("markText",PurpleSyncChatSetupRequests.MARK).put("otherStagesPresent",false)
                .put("markRequestId",Long.toUnsignedString(PurpleSyncAutoUpload.randomId()))
                .put("ownRequestId",Long.toUnsignedString(PurpleSyncAutoUpload.randomId()));
    }
    public JSONObject begin(PurpleSyncChatScreen.ChangeSelection selection,PurpleSyncChatClient.Info target)throws Exception {return begin(selection.offer.opening.binding);}
    public JSONObject begin(PurpleSyncChatMoveConsent.Selection selection)throws Exception {
        PurpleSyncChatService.Binding binding=holder.env.binding();
        if(!selection.offer.bindingIdentity.equals(binding.identity)||!selection.offer.stateStamp.equals(binding.stateStamp))return null;
        return begin(binding);
    }
    public JSONObject repair(PurpleSyncChatScreen.Frame opening,PurpleSyncChatClient.Info info)throws Exception {return begin(opening.binding);}
    public PurpleSyncChatMove.Port carry(){return dispatcher();}
    public void decorate(String effect,PurpleSyncChatMove.Committed proof,Consumer<Boolean> done){
        holder.env.ui(()->{
            if(!live()||proof==null||!origin().equals(proof.origin)||!authority().equals(proof.authority)){worker(()->done.accept(false));return;}
            PurpleSyncChatTelegram writer=new PurpleSyncChatTelegram(origin(),PurpleSyncChatTelegram.platform(origin(),this::live));readers.add(writer);
            writer.decorate(effect,proof.mark,(request,grant)->{
                PurpleSyncChatTelegram reader=new PurpleSyncChatTelegram(origin(),PurpleSyncChatTelegram.platform(origin(),this::live));readers.add(reader);
                reader.readBack(proof.mark,back->{
                    readers.remove(reader);reader.cancel();
                    grant.accept(live()&&back!=null&&back.error==null&&back.info!=null&&back.info.readable()&&verified(proof.mark,back));
                });
            },(response,error)->{
                readers.remove(writer);writer.cancel();
                if(!live())return;
                if(error!=null){worker(()->done.accept(false));return;}
                if(effect.equals("Mute")){
                    if(!(response instanceof TLRPC.TL_boolTrue)){worker(()->done.accept(false));return;}
                    mute(proof.mark.chat,done);
                }else if(response instanceof TLRPC.Updates){
                    PurpleSyncChatSetupNative.updates(origin(),(TLRPC.Updates)response,this::live,value->worker(()->{if(live())done.accept(value.error==PurpleSyncChatSetup.Error.None&&Boolean.TRUE.equals(value.value));}));
                }else worker(()->done.accept(false));
            });
        });
    }
    static boolean verified(Cache mark,PurpleSyncChatSetup.ReadBack back){
        PurpleSyncChatClient.Exact exact=PurpleSyncChatTelegram.classify(back.info.origin,mark,back.response,back.error,back.info);
        TLRPC.Message message=exact!=null?exact.message:null;
        return exact!=null&&exact.state==DirectRead.Valid&&exact.info!=null&&exact.info.eligibility().allowed()&&message!=null
                &&message.id==mark.markId&&message.date==mark.markDate&&PurpleSyncChatSetupRequests.MARK.equals(message.message)
                &&(message.media==null||message.media.getClass()==TLRPC.TL_messageMediaEmpty.class)
                &&ownership(PurpleSyncChatFacts.message(back.info.origin,exact.info.peer,message),Options.restricted())==Ownership.Own;
    }
    private void mute(Peer chat,Consumer<Boolean> done){
        worker(()->{
            PurpleSyncChatSetupNative.Notifications notifications=PurpleSyncChatSetupNative.notifications(origin(),this::live);
            boolean saved=live()&&notifications.preferences(origin().slot,PurpleSyncChatIO.dialogId(chat,origin().userId));
            holder.env.ui(()->{
                boolean ok=saved&&PurpleSyncChatSetupNative.muteLocal(origin(),chat,new PurpleSyncChatSetupNative.Notifications(){
                    public boolean current(PurpleSyncChatOrigin expected){return notifications.current(expected);}
                    public boolean preferences(int slot,long dialog){return saved&&live();}
                    public void remove(int slot,long dialog){notifications.remove(slot,dialog);}
                    public void flags(int slot,long dialog){notifications.flags(slot,dialog);}
                    public void dialog(int slot,long dialog){notifications.dialog(slot,dialog);}
                    public void changed(int slot){notifications.changed(slot);}
                });
                worker(()->{if(live())done.accept(ok);});
            });
        });
    }
    public void status(PurpleSyncChatChange.State state){holder.env.ui(()->{if(live()&&changed!=null)try{changed.accept(state);}catch(RuntimeException|LinkageError error){holder.flows.cancel(this);}});}
    public void status(PurpleSyncChatMoveConsent.State state){holder.env.ui(()->{if(live()&&consent!=null)try{consent.accept(state);}catch(RuntimeException|LinkageError error){holder.flows.cancel(this);}});}
    public void cancel(){
        closed=true;
        try{if(carry!=null)carry.close();}finally{try{if(provision!=null)provision.cancel();}finally{holder.env.ui(()->{
            for(PurpleSyncChatTelegram reader:readers)reader.cancel();readers.clear();
        });}}
    }
}
