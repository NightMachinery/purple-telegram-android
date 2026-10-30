package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.*;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatMoveCleanupCases {
    static int checks;
    static void must(boolean value,String why){++checks;if(!value)throw new AssertionError(why);}
    static final class Flow implements PurpleSyncChatMove.Port,AutoCloseable {
        final PurpleSyncChatMoveExecutorCases.Frame f;
        final List<Integer> deleted=new ArrayList<>();
        String stop="",changed="";
        boolean enabled=true,oldMark,dropDelete,peerless,zero,sourceDenied;
        PurpleSyncChatMove.Command deferred;
        Consumer<PurpleSyncChatMove.Reply> done;
        Flow()throws Exception {this("Switch");}
        Flow(String mode)throws Exception {f=new PurpleSyncChatMoveExecutorCases.Frame(false,false,mode);}
        Flow(Flow old){f=new PurpleSyncChatMoveExecutorCases.Frame(old.f);enabled=old.enabled;oldMark=old.oldMark;}
        public PurpleSyncChatOrigin origin(){return f.origin();}public String authority(){return f.authority();}
        public String operationKey(){return f.operationKey();}public boolean current(){return f.current();}public boolean foreground(){return f.foreground();}
        public void worker(Runnable task){f.worker(task);}public void quiesce(Runnable task){f.quiesce(task);}
        public PurpleSyncChatMoveStore open(BooleanSupplier current,boolean interrupted)throws Exception{return f.open(current,interrupted);}
        public void cancel(PurpleSyncChatMove.Command c){f.cancel(c);}
        public void status(PurpleSyncChatMove.Status s){f.status(s);}
        PurpleSyncChatMove.Reply read(PurpleSyncChatMove.Command c)throws Exception {
            PurpleSyncChatMove.Reply r=f.reply();r.destination=f.inventory(false);r.markRoute=route(O);
            TLRPC.Message m=f.oldRecords.stream().filter(v->v.id==c.cleanupItem.id).findFirst().orElse(null);
            if(m==null){TLRPC.TL_messageEmpty e=new TLRPC.TL_messageEmpty();e.id=c.cleanupItem.id;if(!peerless){e.flags=1;e.peer_id=peer(O);}r.response=page(O,zero?new TLRPC.Message[0]:new TLRPC.Message[]{e});}
            else {
                if(changed.equals("date"))m.date++;
                if(changed.equals("author")){m.from_id=peer(Peer.of(PeerKind.User,888));m.out=false;}
                r.response=page(O,m);
                byte[] body=f.oldBodies.get(m.id);
                if(body!=null){if(changed.equals("hash"))body=bytes("changed");PurpleSyncChatMoveIO.RecordReceipt receipt=receipt(route(O),m,m,body);if(receipt!=null)r.sourceRecords.add(receipt);}
            }
            if(changed.equals("chat"))r.markRoute=route(Peer.of(PeerKind.Channel,10));
            if(changed.equals("coverage"))r.destination=new PurpleSyncChatMoveExecutorCases.CloudClient(f.target,Collections.emptyList(),Collections.emptyMap()).inventory();
            if(sourceDenied){PurpleSyncChatMoveIO.Fresh before=r.fresh;r.fresh=new PurpleSyncChatMoveIO.Fresh(ORIGIN,AUTH,info(O,0,true),input(O),before.target,before.targetPeer,before.resolution,true,before.oldRead);}
            return r;
        }
        public void dispatch(PurpleSyncChatMove.Command c,Consumer<PurpleSyncChatMove.Reply> reply){
            try {
                if(c.action.equals("Effect")){
                    PurpleSyncChatMoveIO.Permit permit=c.permit;++f.effects;int id=50+f.effects;
                    TLRPC.TL_message posted=doc(f.target,id,2000,permit.payload());
                    if(permit.kind.equals("mark")){id=f.markId;posted.id=id;posted.media=null;posted.flags&=~(1<<9);posted.message=PurpleSyncChatSetupRequests.MARK;f.ownWinner=true;}
                    else f.newBodies.put(id,permit.payload());
                    f.newRecords.add(posted);PurpleSyncChatMove.Reply r=f.reply();r.response=ack(permit,id);reply.accept(r);return;
                }
                if(!c.action.startsWith("Cleanup")){f.dispatch(c,reply);return;}
                f.commands.add(c);
                if(c.action.equals(stop)){deferred=c;done=reply;return;}
                if(c.action.equals("Cleanup")){if(changed.equals("later")&&f.oldRecords.stream().noneMatch(m->m.id==99)){f.oldRecords.add(doc(O,99,3000,base));f.oldBodies.put(99,base);TLRPC.TL_message ordinary=doc(O,20,1000,base);ordinary.media=null;ordinary.message="ordinary sentinel";f.oldRecords.add(ordinary);TLRPC.TL_message legacy=doc(O,21,1000,base);legacy.media.document.attributes.get(0).file_name="settings.toml";f.oldRecords.add(legacy);}PurpleSyncChatMove.Reply r=f.reply();r.destination=f.inventory(false);reply.accept(r);return;}
                if(c.action.equals("CleanupRead")){reply.accept(read(c));return;}
                if(c.action.equals("CleanupDelete")){
                    must(c.cleanupPermit!=null&&c.cleanupPermit.current(),"current cleanup capability");
                    TLObject request=c.cleanupPermit.request();must(c.cleanupPermit.claim(request),"one selected exact delete claim");
                    must(!c.cleanupPermit.claim(request),"duplicate claim refused");deleted.add(c.messageId);
                    f.oldRecords.removeIf(m->m.id==c.messageId);f.oldBodies.remove(c.messageId);
                    if(dropDelete){deferred=c;done=reply;return;}
                    reply.accept(f.reply());return;
                }
                throw new AssertionError(c.action);
            }catch(Exception e){throw new RuntimeException(e);}
        }
        void start()throws Exception {f.move=new PurpleSyncChatMove(this);f.move.start(f.begin().put("cleanupSettings",enabled).put("removeOldMarkAuthorized",oldMark),info(f.target,0,false));f.drain();}
        void resume()throws Exception {f.move=new PurpleSyncChatMove(this);f.move.resume(info(f.target,0,false));f.drain();}
        @Override public void close(){f.close();}
    }
    static void offOn()throws Exception {
        must(!PurpleSyncChatMoveCleanup.ACTIVATED&&!PurpleSyncChatChange.ACTIVATED,"production constants OFF");
        for(boolean enabled:new boolean[]{false,true})try(Flow q=new Flow()){
            q.enabled=enabled;q.start();must(q.f.done(),"move completes cleanup choice "+enabled+" "+q.f.reason());
            must(q.deleted.equals(enabled?Collections.singletonList(2):Collections.emptyList()),"only explicit frozen record choice deleted");
            must(!PurpleSyncOperations.carryHeld(q.operationKey()),"core Done releases carry lease");
        }
    }
    static void scopes()throws Exception {
        try(Flow q=new Flow()){
            q.f.oldRecords.clear();q.f.oldBodies.clear();JSONArray messages=rows.get(28).getJSONObject("input").getJSONObject("oldInventory").getJSONArray("messages");
            for(int i=0;i<messages.length();++i){JSONObject m=messages.getJSONObject(i);byte[] body=unblob(m.getString("bytes"));int id=m.getInt("id");q.f.oldRecords.add(doc(O,id,m.getInt("date"),body));q.f.oldBodies.put(id,body);}
            q.changed="later";q.start();must(q.f.done(),"multi-install frozen cleanup completes "+q.f.reason());
            must(q.deleted.size()==7&&!q.deleted.contains(20)&&!q.deleted.contains(21)&&!q.deleted.contains(99),"all frozen ownership-passing settings across installs, no unrelated/legacy/later arrivals");
            must(q.f.oldRecords.stream().anyMatch(m->m.id==20)&&q.f.oldRecords.stream().anyMatch(m->m.id==21)&&q.f.oldRecords.stream().anyMatch(m->m.id==99),"sentinels survive");
        }
        try(Flow q=new Flow("Follow")){
            q.f.oldRecords.get(0).date=2001;
            JSONObject other=rows.get(28).getJSONObject("input").getJSONObject("oldInventory").getJSONArray("messages").getJSONObject(1);byte[] body=unblob(other.getString("bytes"));
            q.f.oldRecords.add(doc(O,3,2002,body));q.f.oldBodies.put(3,body);q.start();
            must(q.f.done()&&q.deleted.equals(Collections.singletonList(2)),"Follow only this install issued late post, not another install "+q.f.reason());
            must(q.f.oldRecords.stream().anyMatch(m->m.id==3),"other install late post survives");
        }
        try(Flow q=new Flow("Follow")){q.f.oldRecords.get(0).date=2000;q.start();must(q.f.done()&&q.deleted.isEmpty(),"winning-mark date equality is not late");}
        try(Flow q=new Flow()){
            q.oldMark=true;TLRPC.TL_message mark=doc(O,1,1001,base);mark.media=null;mark.flags&=~(1<<9);mark.message=PurpleSyncChatSetupRequests.MARK;q.f.oldRecords.add(mark);q.start();
            must(q.f.done()&&q.deleted.equals(Arrays.asList(2,1)),"old mark separate exact authorization "+q.f.reason());
        }
    }
    static void changes()throws Exception {
        for(String changed:new String[]{"date","hash","author","chat","coverage"})try(Flow q=new Flow()){
            q.changed=changed;q.start();must(!q.f.done()&&q.deleted.isEmpty(),"changed exact source or N holds "+changed+" "+q.f.reason());
            must(!PurpleSyncChatMoveStore.completed(q.f.files.root,"0-777"),"durable cleanup write hold "+changed);
        }
        try(Flow q=new Flow()){q.sourceDenied=true;q.start();must(!q.f.done()&&q.deleted.isEmpty(),"permission loss never deletes");}
    }
    static void crash()throws Exception {
        try(Flow q=new Flow()){
            q.dropDelete=true;q.start();must(q.deleted.equals(Collections.singletonList(2))&&!q.f.done(),"crash after exact delete before callback");q.close();
            try(Flow next=new Flow(q)){next.resume();must(next.f.done()&&next.deleted.isEmpty(),"reopen reconciles scoped absence without duplicate delete "+next.f.reason());}
        }
        for(boolean zero:new boolean[]{false,true})try(Flow q=new Flow()){
            q.dropDelete=true;q.start();q.close();
            try(Flow next=new Flow(q)){next.peerless=!zero;next.zero=zero;next.resume();must(!next.f.done()&&next.deleted.isEmpty(),"peerless/zero record absence remains unknown");}
        }
    }
    static JSONObject noPlaylist(Flow q){return obj("status","NotInUse","account",ORIGIN.accountKey(),"source",O.token(),"target",q.f.target.token(),"planStamp","","destinationStamp","");}
    static PurpleSyncChatMoveCleanup.Permit admit(Flow q,PurpleSyncChatMoveCleanup helper,PurpleSyncChatMoveCleanup.Item item)throws Exception {
        PurpleSyncChatMoveCleanup.Read ticket=helper.read(item);PurpleSyncChatMove.Reply r=q.read(q.deferred);
        return helper.inspect(ticket,r.fresh,r.destination,noPlaylist(q),r.markRoute,r.response,r.sourceRecords.isEmpty()?null:r.sourceRecords.get(0));
    }
    static void admission()throws Exception {
        try(Flow q=new Flow()){
            q.stop="CleanupRead";q.start();must(q.deferred!=null,"cleanup read checkpoint");
            PurpleSyncChatMoveCleanup helper=new PurpleSyncChatMoveCleanup(q.f.opened,ORIGIN,()->q.f.live);
            helper.prepare(q.f.fresh(),q.f.inventory(false),noPlaylist(q));PurpleSyncChatMoveCleanup.Item item=helper.next();
            PurpleSyncInventory old=q.f.inventory(false);PurpleSyncChatMoveCleanup.Read first=helper.read(item);PurpleSyncChatMove.Reply r=q.read(q.deferred);
            try{helper.inspect(first,r.fresh,old,noPlaylist(q),r.markRoute,r.response,r.sourceRecords.get(0));throw new AssertionError("old N proof");}catch(java.io.IOException expected){must(expected.getMessage().equals("StaleCleanupRead"),"post-ticket N scan required");}
            PurpleSyncChatMoveCleanup.Permit a=helper.inspect(first,r.fresh,r.destination,noPlaylist(q),r.markRoute,r.response,r.sourceRecords.get(0));must(a.current(),"fresh exact durable intent grants permit");
            try{helper.inspect(first,r.fresh,r.destination,noPlaylist(q),r.markRoute,r.response,r.sourceRecords.get(0));throw new AssertionError("reused cleanup ticket");}catch(java.io.IOException expected){must(expected.getMessage().equals("StaleCleanupRead"),"cleanup ticket consumed once");}
            must(!a.claim(PurpleSyncChatMoveRequests.delete(Peer.of(PeerKind.Channel,10),input(Peer.of(PeerKind.Channel,10)),item.id)),"wrong typed delete refused");
            android.os.Looper.mainThread=Thread.currentThread();try{must(a.current()&&a.request()!=null,"GUI cleanup permit builders use RAM only");}finally{android.os.Looper.mainThread=null;}
            PurpleSyncChatMoveCleanup.Permit b=admit(q,helper,item);must(!a.current()&&PurpleSyncChatMoveCleanup.successor(a,b),"strict same opening/revision renewal with newer attempt");
            class Writes implements PurpleSyncChatMoveCleanup.WritePort {
                Consumer<PurpleSyncChatMoveCleanup.Permit> renewal;PurpleRpcRequest.Reply reply;int sends,applied;final List<Runnable> delayed=new ArrayList<>();
                public boolean current(){return q.f.live;}public long now(){return 1;}public void dispatch(Runnable r){r.run();}
                public void delay(Runnable r,long millis){delayed.add(r);}public void cancelDelay(Runnable r){delayed.remove(r);}public void cancel(int id){}
                public int send(TLObject request,PurpleRpcRequest.Reply done){++sends;reply=done;return 8;}
                public void renew(PurpleSyncChatMoveCleanup.Permit p,Consumer<PurpleSyncChatMoveCleanup.Permit> done){renewal=done;}
                public void apply(PurpleSyncChatOrigin o,TLObject response){must(o.equals(ORIGIN),"apply captured account");++applied;}
            }
            Writes writes=new Writes();int[] completed={0};PurpleSyncChatMoveCleanup.Effect effect=new PurpleSyncChatMoveCleanup.Effect(b,writes,(response,error)->completed[0]++);effect.start();
            must(writes.sends==0&&writes.renewal!=null,"every actual delete waits for fresh worker admission");
            PurpleSyncChatMoveCleanup.Permit c=admit(q,helper,item);writes.renewal.accept(c);must(writes.sends==1,"newer exact admission sends once");writes.renewal.accept(c);must(writes.sends==1,"duplicate admission cannot resend");
            writes.reply.accept(new TLRPC.TL_messages_affectedMessages(),null);must(writes.applied==1&&completed[0]==1,"apply before effect completion");effect.close();
            PurpleSyncChatMoveCleanup.Permit d=admit(q,helper,item);Writes canceled=new Writes();PurpleSyncChatMoveCleanup.Effect closed=new PurpleSyncChatMoveCleanup.Effect(d,canceled,(response,error)->{});closed.start();closed.close();
            PurpleSyncChatMoveCleanup.Permit e=admit(q,helper,item);canceled.renewal.accept(e);must(canceled.sends==0,"late admission after close sends nothing");
            helper.close();must(!e.current(),"close revokes outstanding cleanup permit");
        }
    }
    static void progress()throws Exception {
        try(Flow q=new Flow()){
            q.changed="date";q.start();PurpleSyncChatMove.Status held=q.f.statuses.get(q.f.statuses.size()-1);
            must(held.cleanup!=null&&held.cleanup.pending&&held.cleanup.canAct&&held.committed!=null,"held cleanup reports moved/pending with explicit actions");
            q.f.oldRecords.get(0).date=1001;q.changed="";held.cleanup.retry();q.f.drain();must(q.f.done(),"explicit progress Retry resumes exact original cleanup "+q.f.reason());
            int count=q.deleted.size();held.cleanup.retry();held.cleanup.skip(true);q.f.drain();must(q.deleted.size()==count,"stale progress cannot restart terminal owner");
        }
        try(Flow q=new Flow()){
            q.stop="Cleanup";q.start();JSONObject plan=q.f.diskJournal().getJSONObject("plan"),inventory=PurpleSyncChatMoveIO.inventory(q.f.inventory(false),ORIGIN,q.f.target);
            must(!PurpleSyncChatMoveCleanup.uncopied(plan,inventory),"carried copies do not establish install moved");
            JSONObject post=json(base);post.put("at",1004).put("seq",2);byte[] body=bytes(post.toString());q.f.newRecords.add(doc(q.f.target,200,3000,body));q.f.newBodies.put(200,body);
            must(PurpleSyncChatMoveCleanup.uncopied(plan,PurpleSyncChatMoveIO.inventory(q.f.inventory(false),ORIGIN,q.f.target)),"uncopied current-install N record establishes install moved");
        }
    }
    static void uiAndDurability()throws Exception {
        PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();f.screen.frame=PurpleSyncChatChangeCases.screen(f.carry,Peer.self());
        PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(f.screen,true);PurpleSyncChatScreen.ChangeOffer[] offer={null};
        control.offerChange(f.screen.target.chat,false,x->offer[0]=x);must(offer[0]!=null&&!offer[0].cleanupDefault&&offer[0].cleanupAvailable,"Self cleanup is available but unchecked");
        String disclosure=PurpleSyncChatScreen.changeDisclosure(offer[0],(key,args)->key);
        must(disclosure.contains("ChangeOlder"),"Self always discloses older-device consequence");
        PurpleSyncChatScreen.ChangeSelection[] selection={null};control.acceptChange(offer[0],true,true,false,true,x->selection[0]=x);
        must(selection[0]!=null&&!selection[0].cleanupSettings&&selection[0].removeOldMark,"settings and old-mark checkboxes remain independent");control.close();f.close();
        for(int point:new int[]{1,5})try(Flow q=new Flow()){
            q.stop="CleanupRead";q.start();PurpleSyncChatMoveCleanup helper=new PurpleSyncChatMoveCleanup(q.f.opened,ORIGIN,()->true);
            helper.prepare(q.f.fresh(),q.f.inventory(false),noPlaylist(q));PurpleSyncChatMoveCleanup.Item item=helper.next();q.f.opened.fault(point);
            try{admit(q,helper,item);throw new AssertionError("faulty cleanup intent");}catch(java.io.IOException expected){must(expected.getMessage().equals("DurabilityFailed"),"failed intent barrier issues no capability "+point);}
            must(q.deleted.isEmpty(),"no delete after failed durable intent "+point);helper.close();
        }
    }
    static void partialAndMarkAbsence()throws Exception {
        try(Flow q=new Flow()){
            JSONObject other=rows.get(28).getJSONObject("input").getJSONObject("oldInventory").getJSONArray("messages").getJSONObject(1);byte[] body=unblob(other.getString("bytes"));
            q.f.oldRecords.add(doc(O,3,1003,body));q.f.oldBodies.put(3,body);q.dropDelete=true;q.start();must(q.deleted.equals(Collections.singletonList(2)),"partial cleanup stops after first uncertain delete");q.close();
            try(Flow next=new Flow(q)){
                next.sourceDenied=true;next.resume();must(!next.f.done()&&next.deleted.isEmpty(),"reopen confirms first absence but permission loss holds remaining record");
                next.f.move.skipCleanup(true);next.f.drain();JSONObject journal=next.f.diskJournal();
                must(next.f.done()&&journal.getInt("phase")==16&&journal.getJSONArray("abandoned_cleanup").length()==1,"Skip abandons only remaining cleanup reference "+next.f.reason());
                must(journal.getJSONArray("abandoned_cleanup").getJSONObject(0).getInt("id")==3,"already absent first item is retained as resolved");
            }
        }
        try(Flow q=new Flow()){
            q.enabled=false;q.oldMark=true;q.dropDelete=true;TLRPC.TL_message mark=doc(O,1,1001,base);mark.media=null;mark.flags&=~(1<<9);mark.message=PurpleSyncChatSetupRequests.MARK;q.f.oldRecords.add(mark);q.start();must(q.deleted.equals(Collections.singletonList(1)),"separate old-mark intent before lost callback");q.close();
            try(Flow next=new Flow(q)){next.peerless=true;next.resume();must(!next.f.done()&&next.deleted.isEmpty(),"D12 stale-mark exception does not broaden Switch old-mark absence");}
        }
    }
    static void skipAndGone()throws Exception {
        try(Flow q=new Flow()){
            q.changed="date";q.start();must(!q.f.done(),"skip held prerequisite");
            q.f.move.skipCleanup(false);q.f.drain();must(!q.f.done(),"skip requires explicit confirmation");
            q.f.move.skipCleanup(true);q.f.drain();must(q.f.done()&&q.f.diskJournal().getInt("phase")==16,"explicit skip records CleanupSkipped "+q.f.reason());
            must(q.deleted.isEmpty()&&!PurpleSyncOperations.carryHeld(q.operationKey()),"skip sends nothing and releases lease");
        }
        try(Flow q=new Flow()){
            q.stop="Cleanup";q.start();must(q.deferred!=null,"cleanup checkpoint");q.f.access="ChatGone";q.stop="";
            PurpleSyncChatMove.Reply r=q.f.reply();r.destination=q.f.inventory(false);q.done.accept(r);q.f.drain();
            must(q.f.done()&&q.deleted.isEmpty()&&q.f.diskJournal().getBoolean("cleanup_chat_gone"),"definite scoped ChatGone completes cleanup "+q.f.reason());
        }
    }
    public static void main(String[] args)throws Exception {
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));
        for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        File parent=new File(args[1]);PurpleModernSyncCases.directory(parent);root=new PurpleModernSyncCases.Frame(parent,"routing",true).root;
        offOn();scopes();changes();crash();admission();progress();uiAndDurability();partialAndMarkAbsence();skipAndGone();
        System.out.println("CARRY_CLEANUP_CHECKS="+checks);
    }
}
