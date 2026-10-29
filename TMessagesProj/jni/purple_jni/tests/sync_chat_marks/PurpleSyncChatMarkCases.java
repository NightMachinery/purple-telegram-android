package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

public final class PurpleSyncChatMarkCases {
    static int assertions,sequence;
    static final String AUTH=String.join("",Collections.nCopies(64,"a"));
    static void must(boolean value,String name){++assertions;if(!value)throw new AssertionError(name);}
    static PurpleSyncChatScreen.Frame frame(Cache winner,boolean complete,boolean held){
        JSONObject raw=json("action","Use","cache",winner.json(),"winner",winner.json(),"readChat",winner.chat.token(),"postingHeld",held);
        Resolution resolution=new Resolution(raw);
        return new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Use,resolution,"",complete,held,0)),
                new PurpleSyncChatService.Binding(true,true,winner.chat),null,info(winner.chat),Collections.emptyList());
    }
    static final class Pending {
        final String phase;final Runnable callback;
        Pending(String phase,Runnable callback){this.phase=phase;this.callback=callback;}
    }
    static final class Port implements PurpleSyncChatMarkActions.Port {
        final String key;
        final Peer chat;
        final TLRPC.TL_message message;
        final PurpleSyncChatMarkActions.Row row;
        final List<PurpleSyncChatMarkActions.State> states=new ArrayList<>();
        final ArrayDeque<Pending> callbacks=new ArrayDeque<>();
        PurpleSyncChatScreen.Frame frame;
        PurpleSyncChatClient.Exact first,after;
        PurpleSyncChatOrigin origin=ORIGIN;
        String authority=AUTH,throwAt="",pauseAt="";
        PurpleSyncOperations.Capability token;
        Object owner;
        PurpleSyncChatMarkActions action;
        boolean current=true,referenced,defer,duplicate,throwCancel,throwEnd,closeObserver,closeBegin;
        Runnable onEnd;
        int begins,ends,cancels,resolves,references,reads,deletes,applies;
        TLObject updates=new TLRPC.TL_updates();
        Port(Peer chat){this(chat,"mark-case-"+(++sequence));}
        Port(Peer chat,String key){
            this.key=key;this.chat=chat;message=mark(chat,7,50);row=PurpleSyncChatMarkActions.Row.capture(ORIGIN,AUTH,message,info(chat));
            must(row!=null,"supported own row "+chat.token());frame=frame(new Cache(chat,9,60),true,false);
            first=new PurpleSyncChatClient.Exact(DirectRead.Valid,mark(chat,7,50),info(chat));after=gone(row,chat);
            action=new PurpleSyncChatMarkActions(this,s->{states.add(s);if(closeObserver)action.close();});
        }
        public PurpleSyncChatOrigin origin(){return origin;}public String authority(){return authority;}public boolean current(){return current;}
        public boolean begin(Object owner){++begins;this.owner=owner;token=PurpleSyncOperations.acquire(key,owner,()->current);if(closeBegin)action.close();fail("Acquire");return token!=null;}
        public boolean owned(Object owner){return this.owner==owner&&token!=null&&token.current();}
        public void end(Object owner){
            if(this.owner!=owner||token==null)return;
            PurpleSyncOperations.Capability old=token;token=null;this.owner=null;old.revoke();PurpleSyncOperations.end(key,owner,old);++ends;
            if(onEnd!=null){Runnable callback=onEnd;onEnd=null;callback.run();}if(throwEnd)throw new IllegalStateException("end");
        }
        void fail(String phase){if(throwAt.equals(phase))throw new IllegalStateException(phase);}
        void reply(String phase,Runnable task){fail(phase);if(defer||pauseAt.equals(phase))callbacks.add(new Pending(phase,task));else{task.run();if(duplicate)task.run();}}
        public void resolve(Object owner,Consumer<PurpleSyncChatScreen.Frame> done){++resolves;reply("Resolve",()->done.accept(frame));}
        public void referenced(PurpleSyncChatMarkActions.Row row,Consumer<Boolean> done){++references;reply("References",()->done.accept(referenced));}
        public void exact(PurpleSyncChatMarkActions.Row row,Consumer<PurpleSyncChatClient.Exact> done){++reads;boolean initial=reads==1;reply(initial?"Read":"Verify",()->done.accept(initial?first:after));}
        public void delete(PurpleSyncChatMarkActions.Row row,Consumer<TLObject> done){must(row.location.markId==7&&row.location.chat.equals(chat),"delete only captured scoped id");++deletes;reply("Delete",()->done.accept(updates));}
        public void apply(PurpleSyncChatMarkActions.Row row,TLObject response){must(response==updates&&deletes==1,"apply returned Updates before absence read");++applies;fail("Apply");}
        public void cancel(Object owner){++cancels;if(throwCancel)throw new IllegalStateException("cancel");}
        void drain(){int bound=100;while(!callbacks.isEmpty()){must(--bound>0,"bounded callbacks");Runnable task=callbacks.removeFirst().callback;task.run();if(duplicate)task.run();}}
        void start(){action.remove(row);}
        void released(){must(!PurpleSyncOperations.busy(key)&&token==null,"exact account capability released");}
        String reason(){return states.isEmpty()?"":states.get(states.size()-1).reason;}
        boolean complete(){return !states.isEmpty()&&states.get(states.size()-1).absent;}
    }
    static PurpleSyncChatClient.Exact gone(PurpleSyncChatMarkActions.Row row,Peer chat){return new PurpleSyncChatClient.Exact(DirectRead.MarkGone,null,info(chat),new Cache(chat,row.location.markId,row.location.markDate));}
    static void success(){
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}){
            Port p=new Port(chat);p.duplicate=true;p.start();p.drain();must(p.complete()&&p.deletes==1&&p.applies==1&&p.reads==2,"single exact removal "+chat.token()+" "+p.reason()+" deletes="+p.deletes+" reads="+p.reads);p.released();p.action.close();p.released();
            Port absent=new Port(chat);absent.first=gone(absent.row,chat);absent.start();must(absent.complete()&&absent.deletes==0&&absent.applies==0,"already absent sends nothing");absent.released();
        }
    }
    static void changes(){
        for(String change:new String[]{"winner","older-winner","incomplete","held","wrongchat","text","date","author","foreign","newer","media","unknown","inexact","ordinary","scope","account","login","reference","uncertain-delete","uncertain-absence"}){
            Port p=new Port(CHANNEL);
            TLRPC.TL_message edited=mark(CHANNEL,7,50);
            switch(change){
                case "winner":p.frame=frame(p.row.location,true,false);break;
                case "older-winner":p.frame=frame(new Cache(CHANNEL,6,49),true,false);break;
                case "incomplete":p.frame=frame(new Cache(CHANNEL,9,60),false,false);break;
                case "held":p.frame=frame(new Cache(CHANNEL,9,60),true,true);break;
                case "wrongchat":edited.peer_id=peer(GROUP);break;
                case "text":edited.message="#purplechatsync\nchanged";break;
                case "date":edited.date=51;break;
                case "author":edited.post_author="different author";edited.flags|=1<<16;break;
                case "foreign":edited.from_id=peer(USER);edited.flags|=1<<8;edited.out=false;break;
                case "newer":edited.message="#purplechatsync v999";break;
                case "media":edited.media=new TLRPC.TL_messageMediaPhoto();break;
                case "unknown":p.first=new PurpleSyncChatClient.Exact(DirectRead.Unknown,null,info(CHANNEL));break;
                case "inexact":{TLRPC.TL_messages_messages response=new TLRPC.TL_messages_messages();response.inexact=true;response.messages.add(edited);p.first=PurpleSyncChatTelegram.classify(ORIGIN,p.row.location,response,null,info(CHANNEL));break;}
                case "ordinary":edited.message="ordinary text";p.first=PurpleSyncChatTelegram.classify(ORIGIN,p.row.location,messages(edited),null,info(CHANNEL));break;
                case "scope":p.first=gone(p.row,GROUP);break;
                case "account":p.origin=new PurpleSyncChatOrigin(0,0,22,"different");break;
                case "login":p.authority=String.join("",Collections.nCopies(64,"b"));break;
                case "reference":p.referenced=true;break;
                case "uncertain-delete":p.updates=null;break;
                case "uncertain-absence":p.after=new PurpleSyncChatClient.Exact(DirectRead.MarkGone,null,info(CHANNEL));break;
            }
            if(Arrays.asList("wrongchat","text","date","author","foreign","newer","media").contains(change))p.first=new PurpleSyncChatClient.Exact(DirectRead.Valid,edited,info(CHANNEL));
            p.start();must(!p.complete(),"changed/uncertain case held "+change);must(p.deletes==(change.startsWith("uncertain-")?1:0),"no broadened delete "+change);p.released();p.action.close();
        }
        Port p=new Port(CHANNEL);p.defer=true;p.start();p.frame=frame(p.row.location,true,false);p.drain();must(p.deletes==0&&p.reason().equals("CurrentMark"),"search row stale after fresh winner changes");p.released();
    }
    static void lifecycle(){
        for(String phase:new String[]{"Resolve","References","Read","Delete","Verify"}){
            Port p=new Port(CHANNEL);p.pauseAt=phase;p.start();must(!p.callbacks.isEmpty(),"deferred phase "+phase);p.action.close();p.action.close();p.drain();p.released();must(p.states.isEmpty(),"closed screen cannot receive stale reply "+phase);must(p.deletes==(phase.equals("Delete")||phase.equals("Verify")?1:0),"cancel never sends again "+phase);
            Port stale=new Port(CHANNEL);stale.pauseAt=phase;stale.start();stale.current=false;stale.drain();stale.released();must(stale.states.isEmpty(),"owner departure fences and releases "+phase);
        }
        for(String phase:new String[]{"Acquire","Resolve","References","Read","Delete","Apply","Verify"}){
            Port p=new Port(CHANNEL);p.throwAt=phase;p.start();p.released();must(!p.complete(),"throwing port cannot report success "+phase);
        }
        Port cleanup=new Port(CHANNEL);cleanup.throwCancel=true;cleanup.throwEnd=true;cleanup.start();cleanup.released();must(cleanup.complete(),"throwing cleanup still releases and reports absent");
        Port observer=new Port(CHANNEL);observer.closeObserver=true;observer.start();observer.released();
        Port reenter=new Port(CHANNEL);final Object next=new Object();final PurpleSyncOperations.Capability[] token={null};reenter.onEnd=()->token[0]=PurpleSyncOperations.acquire(reenter.key,next,()->true);reenter.start();must(token[0]!=null&&PurpleSyncOperations.owned(reenter.key,next),"reentrant retirement starts distinct operation");reenter.action.close();must(PurpleSyncOperations.owned(reenter.key,next),"late close cannot release successor");PurpleSyncOperations.end(reenter.key,next,token[0]);reenter.released();
        Port acquire=new Port(CHANNEL);acquire.closeBegin=true;acquire.start();acquire.released();must(acquire.resolves==0,"reentrant acquire close cannot resolve");
        Port busy=new Port(CHANNEL);Object other=new Object();PurpleSyncOperations.Capability held=PurpleSyncOperations.acquire(busy.key,other,()->true);busy.start();must(busy.resolves==0&&PurpleSyncOperations.owned(busy.key,other),"account serialization refuses busy owner without releasing it");PurpleSyncOperations.end(busy.key,other,held);
        Port first=new Port(CHANNEL);first.pauseAt="Read";first.start();Port second=new Port(CHANNEL,first.key);second.start();must(second.resolves==0&&first.owned(first.action),"shared account wrappers serialize distinct actions");first.action.close();Port third=new Port(CHANNEL,first.key);third.start();must(third.complete(),"next explicit action starts after prior cancel");third.released();first.drain();must(first.deletes==0,"old callback cannot delete during successor");
        Port revoked=new Port(CHANNEL);revoked.pauseAt="Read";revoked.start();revoked.token.revoke();revoked.drain();must(revoked.deletes==0&&revoked.states.isEmpty(),"revoked acquisition cannot admit a delete");revoked.released();
        for(boolean login:new boolean[]{false,true}){Port p=new Port(CHANNEL);p.pauseAt="Read";p.start();if(login)p.authority="changed";else p.origin=new PurpleSyncChatOrigin(0,0,22,"changed");p.drain();must(p.deletes==0&&p.states.isEmpty(),"identity change after resolve cannot delete");p.released();}
    }
    static void absence(){
        Port p=new Port(CHANNEL);Cache at=p.row.location;
        for(String kind:new String[]{"empty","zero","wrong-id","wrong-chat","inexact","flood","unreadable","ordinary","unknown","date","ambiguous"}){
            TLRPC.TL_messageEmpty missing=new TLRPC.TL_messageEmpty();missing.id=7;
            TLRPC.TL_messages_messages response=new TLRPC.TL_messages_messages();response.messages.add(missing);PurpleSyncChatClient.Info facts=info(CHANNEL);
            switch(kind){
                case "empty":break;
                case "zero":response.messages.clear();break;
                case "wrong-id":missing.id=8;break;
                case "wrong-chat":missing.flags=1;missing.peer_id=peer(GROUP);break;
                case "inexact":response.inexact=true;break;
                case "flood":response.flags=8;break;
                case "unreadable":facts=new PurpleSyncChatClient.Info(ORIGIN,CHANNEL,null,PurpleSyncChatClient.Error.Unknown,"",null,false);break;
                case "ordinary":response.messages.clear();TLRPC.TL_message ordinary=mark(CHANNEL,7,50);ordinary.message="ordinary";response.messages.add(ordinary);break;
                case "unknown":response=null;break;
                case "date":response.messages.clear();response.messages.add(mark(CHANNEL,7,51));break;
                case "ambiguous":response.messages.add(mark(CHANNEL,8,50));break;
            }
            PurpleSyncChatClient.Exact exact=PurpleSyncChatTelegram.classify(ORIGIN,at,response,null,facts);
            must(PurpleSyncChatMarkActions.absent(p.row,exact)==kind.equals("empty"),"only scoped explicit absence qualifies "+kind);
        }
        TLRPC.TL_messageEmpty explicit=new TLRPC.TL_messageEmpty();explicit.id=7;explicit.flags=1;explicit.peer_id=peer(CHANNEL);
        must(PurpleSyncChatMarkActions.absent(p.row,PurpleSyncChatTelegram.classify(ORIGIN,at,messages(explicit),null,info(CHANNEL))),"same-chat peer-bearing MessageEmpty qualifies");
        must(!PurpleSyncChatMarkActions.absent(p.row,new PurpleSyncChatClient.Exact(DirectRead.MarkGone,null,info(CHANNEL),new Cache(CHANNEL,8,50))),"same chat wrong scoped id refused");
        must(!PurpleSyncChatMarkActions.absent(p.row,new PurpleSyncChatClient.Exact(DirectRead.MarkGone,null,info(CHANNEL),new Cache(CHANNEL,7,51))),"wrong scoped date refused");
    }
    static void coreAuthority(){
        Port p=new Port(CHANNEL);must(PurpleSyncChatMarkActions.allowed(p.row,p.frame),"standalone shared helper grants exact own older mark");
        must(!PurpleSyncChatMarkActions.allowed(p.row,frame(p.row.location,true,false)),"core refuses current winner");
        must(!PurpleSyncChatMarkActions.allowed(p.row,frame(new Cache(CHANNEL,9,60),false,false)),"core refuses incomplete resolution");
        must(!PurpleSyncChatMarkActions.allowed(p.row,frame(new Cache(CHANNEL,9,60),true,true)),"core refuses posting hold");
        must(!PurpleSyncChatMarkActions.ACTIVATED,"production activation constant OFF");
        PurpleSyncChatMarkActions.State off=PurpleSyncChatMarkActions.production(p.row,ORIGIN,AUTH,true);must(off.reason.equals("QualificationHeld")&&!off.absent,"public OFF path holds without port");
        List<PurpleSyncChatMarkActions.State> states=new ArrayList<>();must(PurpleSyncChat.removeMark(-1,p.row,()->true,states::add)==null&&states.size()==1&&!states.get(0).absent,"public OFF entry cannot construct executor or transport");
    }
    static void wire(){
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}){
            TLObject request=PurpleSyncChatMoveRequests.delete(chat,inputPeer(chat),7);SerializedData data=PurpleSyncChatRequestCases.wire(request);
            must(data.readInt32(true)==(chat.equals(CHANNEL)?TLRPC.TL_channels_deleteMessages.constructor:TLRPC.TL_messages_deleteMessages.constructor),"typed delete namespace");
            if(chat.equals(CHANNEL)){TLRPC.InputChannel channel=TLRPC.InputChannel.TLdeserialize(data,data.readInt32(true),true);must(channel.channel_id==chat.id,"exact channel scope");}
            else must(data.readInt32(true)==1,"revoke own mark only");
            must(data.readInt32(true)==0x1cb5c415&&data.readInt32(true)==1&&data.readInt32(true)==7,"one exact id on wire");
        }
    }
    static void discovery(){
        PurpleSyncChatModelCases.World world=new PurpleSyncChatModelCases.World();world.add(mark(SELF,7,50));world.add(mark(CHANNEL,9,60));world.records.put(CHANNEL,RecordPresence.Present);
        PurpleSyncChatDiscovery model=PurpleSyncChatModelCases.model(null);model.start(1000);world.drain(model,1000);
        List<PurpleSyncChatMarkActions.Row> rows=model.staleMarks(AUTH);
        must(model.status().complete&&rows.size()==1&&rows.get(0).location.chat.equals(SELF),"complete discovery exposes supported own older row only");
        PurpleSyncChatMarkActions.Row captured=rows.get(0);world.self.get(0).message="edited after display";
        must(captured.text.equals("#purplechatsync"),"row full text is an immutable capture");
        PurpleSyncChatScreen.Frame shown=new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(model.status()),null,null,null,Collections.emptyList(),rows);rows.clear();
        must(shown.staleMarks.size()==1,"screen freezes candidate list");
        try{shown.staleMarks.clear();throw new AssertionError("mutable rows");}catch(UnsupportedOperationException expected){must(true,"screen list unmodifiable");}
        world=new PurpleSyncChatModelCases.World();world.add(mark(SELF,7,50));world.failSearch=true;model=PurpleSyncChatModelCases.model(null);model.start(1000);world.drain(model,1000);
        must(model.staleMarks(AUTH).isEmpty(),"incomplete search cannot expose removable rows");
        world=new PurpleSyncChatModelCases.World();world.add(mark(SELF,7,50));model=PurpleSyncChatModelCases.model(PurpleSyncChatModelCases.cache(SELF,7,50));model.startBeforePost(1000);world.drain(model,1000);
        must(model.staleMarks(AUTH).isEmpty(),"cached direct-only discovery cannot produce stale rows");
        for(String edit:new String[]{"foreign","newer","ordinary","media","wrongaccount"}){
            TLRPC.TL_message message=mark(CHANNEL,7,50);PurpleSyncChatClient.Info facts=info(CHANNEL);
            if(edit.equals("foreign")){message.from_id=peer(USER);message.flags|=1<<8;message.out=false;}
            if(edit.equals("newer"))message.message="#purplechatsync v999";
            if(edit.equals("ordinary"))message.message="ordinary";
            if(edit.equals("media"))message.media=new TLRPC.TL_messageMediaPhoto();
            PurpleSyncChatOrigin origin=edit.equals("wrongaccount")?new PurpleSyncChatOrigin(1,0,22,"other"):ORIGIN;
            must(PurpleSyncChatMarkActions.Row.capture(origin,AUTH,message,facts)==null,"unsupported capture refused "+edit);
        }
    }
    static void references(File root)throws Exception{
        Port p=new Port(CHANNEL);File directory=new File(root,"references");must(directory.mkdir(),"private reference directory");
        must(!PurpleSyncChatMoveStore.markReferenced(directory,ORIGIN.accountKey(),p.row),"no journal no reference");
        Files.write(new File(directory,"carry.json").toPath(),"invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8));must(PurpleSyncChatMoveStore.markReferenced(directory,ORIGIN.accountKey(),p.row),"corrupt durable journal conservatively holds");
        android.os.Looper.mainThread=Thread.currentThread();try{must(PurpleSyncChatMoveStore.markReferenced(directory,ORIGIN.accountKey(),p.row),"reference disk probe refuses GUI thread");}finally{android.os.Looper.mainThread=null;}
    }
    static void nativeRoutes(){
        for(String kind:new String[]{"updates","affected","wrong","error","cancel"}){
            PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,platform);List<TLObject> replies=new ArrayList<>();
            client.removeMark(new Cache(CHANNEL,7,50),(request,grant)->grant.accept(true),replies::add);
            must(platform.requests.size()==1&&platform.requests.get(0) instanceof TLRPC.TL_channels_deleteMessages,"real adapter uses typed single-id channel delete");
            TLRPC.TL_channels_deleteMessages sent=(TLRPC.TL_channels_deleteMessages)platform.requests.get(0);
            must(sent.channel.channel_id==CHANNEL.id&&sent.id.equals(Collections.singletonList(7)),"actual adapter never deletes another id or chat");
            TLObject reply=kind.equals("updates")?new TLRPC.TL_updates():kind.equals("affected")?new TLRPC.TL_messages_affectedMessages():messages();
            if(kind.equals("cancel"))client.cancel();
            if(kind.equals("error")){TLRPC.TL_error error=new TLRPC.TL_error();error.code=400;error.text="CHAT_ADMIN_REQUIRED";platform.replies.get(1).accept(null,error);}
            else {platform.reply(1,reply);platform.reply(1,reply);}
            must(replies.size()==(kind.equals("cancel")?0:1),"adapter once-only callback "+kind);
            if(!kind.equals("cancel"))must((replies.get(0)!=null)==(kind.equals("updates")||kind.equals("affected")),"only valid delete receipt accepted "+kind);
            client.cancel();must(platform.timers.isEmpty(),"shared controller owns and clears delete timers");
        }
        PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,platform);List<PurpleSyncChatClient.Exact> replies=new ArrayList<>();Cache selected=new Cache(SELF,7,50);
        client.exact(selected,replies::add);TLRPC.TL_messageEmpty empty=new TLRPC.TL_messageEmpty();empty.id=7;platform.reply(1,messages(empty));
        must(replies.isEmpty()&&platform.sent==2,"absence waits for independent readable peer information");
        PurpleSyncChatFacts.Input input=input(SELF);TLRPC.TL_users_userFull info=new TLRPC.TL_users_userFull();info.full_user=input.userFull;info.users.add(input.user);platform.reply(2,info);
        must(replies.size()==1&&selected.equals(replies.get(0).absentLocation)&&replies.get(0).state==DirectRead.MarkGone,"actual scoped request carries absence scope to consumer");client.cancel();
    }
    static void bridgeStrict(){
        Port p=new Port(CHANNEL);
        for(String kind:new String[]{"valid","numeric-id","bad-id","numeric-date","missing-complete","bad-complete","missing-hold","bad-hold","foreign-account","foreign-mark","unscoped-peer","newer","media","unknown-owner"}){
            JSONObject stale=json("chat",CHANNEL.token(),"id","7","date","50");
            JSONObject winner=json("chat",CHANNEL.token(),"id","9","date","60");
            JSONObject mark=json("account",ORIGIN.accountKey(),"chat",CHANNEL.token(),"id","7","date","50","text","#purplechatsync","hasMedia",false,"ownership","Own","outcome","Valid");
            JSONObject resolution=json("action","Use","postingHeld",false,"winner",winner,"cache",winner);
            JSONObject request=json("account",ORIGIN.accountKey(),"stale",stale,"mark",mark,"resolution",resolution,"complete",true);
            if(kind.equals("numeric-id"))put(stale,"id",7);if(kind.equals("bad-id"))put(stale,"id","07");if(kind.equals("numeric-date"))put(stale,"date",50);
            if(kind.equals("missing-complete"))request.remove("complete");if(kind.equals("bad-complete"))put(request,"complete","true");
            if(kind.equals("missing-hold"))resolution.remove("postingHeld");if(kind.equals("bad-hold"))put(resolution,"postingHeld",0);
            if(kind.equals("foreign-account"))put(request,"account","0-22");if(kind.equals("foreign-mark"))put(mark,"account","0-22");
            if(kind.equals("unscoped-peer"))put(stale,"chat","user:11");if(kind.equals("newer"))put(mark,"text","#purplechatsync v999");
            if(kind.equals("media"))put(mark,"hasMedia",true);if(kind.equals("unknown-owner"))put(mark,"ownership","Unknown");
            PurpleSyncChatMoveCore.Result result=PurpleSyncChatMoveCore.request("checkStaleMarkRemoval",request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            must((result.status.equals("Valid")&&result.fields.optBoolean("allowed"))==kind.equals("valid"),"strict standalone bridge "+kind);
        }
    }
    static void retryAdmission(){
        for(String kind:new String[]{"allowed","winner","edited","request","long"}){
            Port p=new Port(CHANNEL);PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,platform);
            int[] admissions={0};List<TLObject> replies=new ArrayList<>();
            client.removeMark(p.row.location,(request,grant)->{++admissions[0];grant.accept(PurpleSyncChatMarkActions.requestMatches(p.row,request)&&PurpleSyncChatMarkActions.authorized(p.row,p.frame,p.first));},replies::add);
            must(platform.sent==1&&admissions[0]==1,"first actual send has core-backed admission");
            TLRPC.TL_error flood=new TLRPC.TL_error();flood.code=420;flood.text=kind.equals("long")?"FLOOD_WAIT_45":"FLOOD_WAIT_1";platform.replies.get(1).accept(null,flood);
            must(platform.sent==1&&platform.timers.size()==1&&replies.isEmpty(),"shared scheduler owns transient retry");
            if(kind.equals("winner"))p.frame=frame(p.row.location,true,false);
            if(kind.equals("edited"))p.first=new PurpleSyncChatClient.Exact(DirectRead.Unknown,null,info(CHANNEL));
            if(kind.equals("request"))((TLRPC.TL_channels_deleteMessages)platform.requests.get(0)).id.add(99);
            Runnable retry=platform.timers.keySet().iterator().next();long delay=platform.timers.get(retry);must(delay==(kind.equals("long")?45000:2000),"existing FLOOD schedule retained");platform.clock+=delay;platform.timers.remove(retry);retry.run();
            boolean allowed=kind.equals("allowed")||kind.equals("long");
            must(admissions[0]==2&&platform.sent==(allowed?2:1),"every retry freshly re-admits "+kind);
            if(allowed)platform.reply(2,new TLRPC.TL_updates());
            must(replies.size()==1&&(replies.get(0)!=null)==allowed,"rejected retry reports hold without write "+kind);client.cancel();
        }
        for(String kind:new String[]{"duplicate","cancel","timeout"}){
            PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,platform);List<Consumer<Boolean>> grants=new ArrayList<>();List<TLObject> replies=new ArrayList<>();
            client.removeMark(new Cache(CHANNEL,7,50),(request,grant)->grants.add(grant),replies::add);
            must(platform.sent==0&&grants.size()==1&&platform.timers.size()==1,"async admission stays within request deadline");
            if(kind.equals("cancel"))client.cancel();
            if(kind.equals("timeout")){platform.clock+=30000;Runnable timer=platform.timers.keySet().iterator().next();platform.timers.remove(timer);timer.run();}
            grants.get(0).accept(true);grants.get(0).accept(true);
            must(platform.sent==(kind.equals("duplicate")?1:0),"late or duplicate grant cannot duplicate/revive send "+kind);
            if(kind.equals("duplicate")){platform.reply(1,new TLRPC.TL_updates());must(replies.size()==1,"one response after duplicate admission");}
            else must(replies.isEmpty(),"cancelled/expired admission has no completion write");client.cancel();must(platform.timers.isEmpty(),"all admission retry timers clear on close");
        }
    }
    static void throwingAdmission(){
        for(boolean after:new boolean[]{false,true}){
            PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,platform);List<TLObject> replies=new ArrayList<>();
            client.removeMark(new Cache(CHANNEL,7,50),(request,grant)->{if(after)grant.accept(true);throw new IllegalStateException("admission");},replies::add);
            must(platform.sent==(after?1:0)&&platform.timers.size()==1,"throwing admission preserves request ownership");
            if(after){platform.reply(1,new TLRPC.TL_updates());must(replies.size()==1&&replies.get(0)!=null&&platform.timers.isEmpty(),"post-grant throw cannot abandon or duplicate an issued request");}
            else must(replies.isEmpty(),"pre-grant throw has no outward write");client.cancel();must(platform.timers.isEmpty(),"throwing admission cleanup drains scheduler");
        }
    }
    static void durableReferences(File parent,String fixturePath)throws Exception{
        PurpleSyncChatMoveIOCases.rows=new ArrayList<>();for(String line:Files.readAllLines(new File(fixturePath).toPath()))PurpleSyncChatMoveIOCases.rows.add(new JSONObject(line));
        for(JSONObject item:PurpleSyncChatMoveIOCases.rows)if(item.getString("op").equals("begin")&&item.getJSONObject("expected").getString("status").equals("Valid")){PurpleSyncChatMoveIOCases.beginning=item.getJSONObject("input");break;}
        PurpleSyncChatMoveIOCases.base=PurpleSyncChatMoveIOCases.unblob(PurpleSyncChatMoveIOCases.rows.get(0).getJSONObject("input").getString("stagedRecord"));
        PurpleSyncChatMoveStoreCases.initial=PurpleSyncChatMoveIOCases.unblob(PurpleSyncChatMoveIOCases.beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=PurpleSyncChatMoveIOCases.base;
        PurpleModernSyncCases.Frame bootstrap=new PurpleModernSyncCases.Frame(parent,"mark-reference",true);PurpleSyncChatMoveIOCases.root=bootstrap.root;
        PurpleSyncChatMoveConsentCases.Flow active=new PurpleSyncChatMoveConsentCases.Flow("Follow");active.carry.stop="Effect";active.start("config");
        PurpleSyncChatOrigin origin=active.carry.origin;TLRPC.TL_message message=mark(active.carry.target,7,50);message.from_id=new TLRPC.TL_peerUser();message.from_id.user_id=origin.userId;message.flags|=1<<8;
        PurpleSyncChatMarkActions.Row row=PurpleSyncChatMarkActions.Row.capture(origin,PurpleSyncChatMoveIOCases.AUTH,message,active.carry.info(active.carry.target,0,false));
        must(row!=null&&new File(active.carry.files.root,"carry.json").isFile(),"active move reference fixture is durable "+active.reason());
        must(PurpleSyncChatMoveStore.markReferenced(active.carry.files.root,origin.accountKey(),row),"live active move prevents standalone deletion");active.close();
        must(PurpleSyncChatMoveStore.markReferenced(active.carry.files.root,origin.accountKey(),row),"reopened nonterminal journal still holds removal");
        PurpleSyncChatMoveConsentCases.Flow complete=new PurpleSyncChatMoveConsentCases.Flow("Follow");complete.start("config");complete.close();
        must(PurpleSyncChatMoveStore.completed(complete.carry.files.root,origin.accountKey()),"Done fixture passes exact retained validation");
        must(!PurpleSyncChatMoveStore.markReferenced(complete.carry.files.root,origin.accountKey(),row),"validated Done does not indefinitely block stale actions");
        must(PurpleSyncChatMoveStore.markReferenced(complete.carry.files.root,"0-22",row),"another account cannot admit this journal");
    }
    public static void main(String[] args)throws Exception{
        PurpleCore.ensureLoaded();success();changes();lifecycle();absence();coreAuthority();wire();nativeRoutes();bridgeStrict();retryAdmission();throwingAdmission();discovery();references(new File(args[0]));durableReferences(new File(args[0]),args[1]);System.out.println("STALE_MARK_CHECKS="+assertions);
    }
}
