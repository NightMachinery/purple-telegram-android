package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatMarkActions implements AutoCloseable {
    public static final boolean ACTIVATED=false;
    public static final class Row {
        final PurpleSyncChatOrigin origin;
        final String authority,author;
        public final Cache location;
        public final String text,title;
        private Row(PurpleSyncChatOrigin origin,String authority,TLRPC.Message message,PurpleSyncChatClient.Info info){
            this.origin=origin;this.authority=authority;location=new Cache(info.chat,message.id,message.date);
            text=message.message;title=info.title;author=author(message);
        }
        static Row capture(PurpleSyncChatOrigin origin,String authority,TLRPC.Message message,PurpleSyncChatClient.Info info){
            if(origin==null||authority==null||!authority.matches("[0-9a-f]{64}")||message==null||message.getClass()!=TLRPC.TL_message.class
                    ||message.id<=0||message.date<=0||info==null||!origin.equals(info.origin)||!info.readable()
                    ||!info.chat.equals(PurpleSyncChatFacts.token(message.peer_id,origin.userId)))return null;
            if(((message.flags&(1<<8))!=0)!=(message.from_id!=null)
                    ||(message.flags&(1<<16))!=0&&message.post_author==null
                    ||(message.flags&(1<<16))==0&&message.post_author!=null&&!message.post_author.isEmpty())return null;
            Ownership own=ownership(PurpleSyncChatFacts.message(origin,info.peer,message),Options.restricted());
            if(own!=Ownership.Own||parseMark(message.message,media(message),own).status!=MarkStatus.Supported)return null;
            return new Row(origin,authority,message,info);
        }
        private static String author(TLRPC.Message m){
            boolean from=(m.flags&(1<<8))!=0,signature=(m.flags&(1<<16))!=0;
            TLRPC.Peer peer=from?m.from_id:null;
            return from+":"+(peer==null?"none":peer.getClass().getName()+":"+peer.user_id+":"+peer.chat_id+":"+peer.channel_id)
                    +":"+m.out+":"+m.post+":"+signature+":"+(signature?Objects.toString(m.post_author,""):"");
        }
        boolean same(Row other){return other!=null&&origin.equals(other.origin)&&authority.equals(other.authority)
                &&location.equals(other.location)&&text.equals(other.text)&&author.equals(other.author);}
    }
    public static final class State {
        public final String phase,reason;
        public final boolean absent;
        State(String phase,String reason,boolean absent){this.phase=phase;this.reason=reason;this.absent=absent;}
    }
    interface Port {
        PurpleSyncChatOrigin origin();
        String authority();
        boolean current();
        boolean begin(Object owner);
        boolean owned(Object owner);
        void end(Object owner);
        void resolve(Object owner,Consumer<PurpleSyncChatScreen.Frame> done);
        void referenced(Row row,Consumer<Boolean> done);
        void exact(Row row,Consumer<PurpleSyncChatClient.Exact> done);
        void delete(Row row,Consumer<TLObject> done);
        void apply(Row row,TLObject updates);
        void cancel(Object owner);
    }
    private final Port port;
    private final Consumer<State> changed;
    private Row selected;
    private long generation;
    private String phase="Idle";
    private boolean closed,owned;
    PurpleSyncChatMarkActions(Port port,Consumer<State> changed){this.port=port;this.changed=changed;}
    static State production(Row row,PurpleSyncChatOrigin origin,String authority,boolean current){
        return new State("Held",row==null||!current||!row.origin.equals(origin)||!row.authority.equals(authority)?"OpeningChanged":"QualificationHeld",false);
    }
    void remove(Row row){
        if(closed||owned||!phase.equals("Idle")||row==null)return;
        selected=row;
        if(!current()){close();return;}
        long run=++generation;phase="Acquire";owned=true;
        try{
            boolean acquired=port.begin(this);
            if(closed||run!=generation)return;
            if(!acquired){finish(run,"Held","AccountBusy",false);return;}
            if(!live(run,"Acquire"))return;
            phase="Resolve";
            port.resolve(this,reply(run,"Resolve",frame->resolved(run,row,frame)));
        }catch(RuntimeException|LinkageError error){finish(run,"Held","Unavailable",false);}
    }
    private void resolved(long run,Row row,PurpleSyncChatScreen.Frame frame){
        if(frame==null||!row.origin.equals(frame.origin)||!frame.status.complete||frame.status.postingHeld
                ||frame.status.resolution.action!=Action.Use||frame.status.resolution.postingHeld){
            finish(run,"Held","ResolutionIncomplete",false);return;
        }
        if(frame.status.resolution.winner==null||!outranks(frame.status.resolution.winner,row.location)){
            finish(run,"Held","CurrentMark",false);return;
        }
        phase="References";
        port.referenced(row,reply(run,"References",referenced->{
            if(!Boolean.FALSE.equals(referenced)){finish(run,"Held","MoveReference",false);return;}
            phase="Read";
            port.exact(row,reply(run,"Read",exact->{
                if(absent(row,exact)){finish(run,"Complete","AlreadyAbsent",true);return;}
                if(!authorized(row,frame,exact)){finish(run,"Held","MarkChanged",false);return;}
                if(!live(run,"Read"))return;
                phase="Delete";
                port.delete(row,reply(run,"Delete",updates->{
                    if(updates==null){finish(run,"Held","DeleteUncertain",false);return;}
                    phase="Apply";port.apply(row,updates);
                    if(!live(run,"Apply"))return;
                    phase="Verify";
                    port.exact(row,reply(run,"Verify",read->{
                        boolean gone=absent(row,read);
                        finish(run,gone?"Complete":"Held",gone?"Removed":"AbsenceUnknown",gone);
                    }));
                }));
            }));
        }));
    }
    private boolean current(){
        try{return !closed&&port.current()&&selected!=null&&selected.origin.equals(port.origin())&&selected.authority.equals(port.authority());}
        catch(RuntimeException|LinkageError error){return false;}
    }
    private boolean live(long run,String expected){
        if(closed||!owned||generation!=run||!phase.equals(expected))return false;
        boolean valid;
        try{valid=current()&&port.owned(this);}catch(RuntimeException|LinkageError error){valid=false;}
        if(!valid){close();return false;}
        return !closed&&owned&&generation==run&&phase.equals(expected);
    }
    private <T> Consumer<T> reply(long run,String expected,Consumer<T> next){
        return value->{
            if(!live(run,expected))return;
            try{next.accept(value);}catch(RuntimeException|LinkageError error){finish(run,"Held","Unavailable",false);}
        };
    }
    private void release(){
        boolean release=owned;owned=false;
        try{port.cancel(this);}catch(RuntimeException|LinkageError ignored){}
        finally{if(release)try{port.end(this);}catch(RuntimeException|LinkageError ignored){}}
    }
    private void finish(long run,String next,String reason,boolean absent){
        if(closed||run!=generation)return;
        long ended=++generation;phase=next;release();
        if(!closed&&ended==generation&&current())try{changed.accept(new State(next,reason,absent));}catch(RuntimeException|LinkageError ignored){}
    }
    static boolean absent(Row row,PurpleSyncChatClient.Exact exact){
        return row!=null&&exact!=null&&exact.state==DirectRead.MarkGone&&exact.message==null&&row.location.equals(exact.absentLocation)
                &&exact.info!=null&&row.origin.equals(exact.info.origin)&&row.location.chat.equals(exact.info.chat)&&exact.info.readable();
    }
    static boolean authorized(Row row,PurpleSyncChatScreen.Frame frame,PurpleSyncChatClient.Exact exact){
        Row fresh=exact!=null&&exact.state==DirectRead.Valid?Row.capture(row.origin,row.authority,exact.message,exact.info):null;
        return frame!=null&&row.origin.equals(frame.origin)&&frame.status.complete&&!frame.status.postingHeld&&row.same(fresh)&&allowed(row,frame);
    }
    static boolean requestMatches(Row row,TLObject value){
        if(row==null||value==null)return false;
        if(row.location.chat.kind==PeerKind.Channel){
            if(value.getClass()!=TLRPC.TL_channels_deleteMessages.class)return false;
            TLRPC.TL_channels_deleteMessages request=(TLRPC.TL_channels_deleteMessages)value;
            return request.channel!=null&&request.channel.getClass()==TLRPC.TL_inputChannel.class
                    &&request.channel.channel_id==row.location.chat.id&&request.channel.access_hash!=0
                    &&request.id.equals(java.util.Collections.singletonList(row.location.markId));
        }
        if(value.getClass()!=TLRPC.TL_messages_deleteMessages.class)return false;
        TLRPC.TL_messages_deleteMessages request=(TLRPC.TL_messages_deleteMessages)value;
        return request.revoke&&request.id.equals(java.util.Collections.singletonList(row.location.markId));
    }
    static boolean allowed(Row row,PurpleSyncChatScreen.Frame frame){
        try{
            Resolution r=frame.status.resolution;
            JSONObject resolution=json("action",r.action.name(),"postingHeld",r.postingHeld);
            if(r.winner!=null)put(resolution,"winner",cache(r.winner));
            if(r.cache!=null)put(resolution,"cache",cache(r.cache));
            JSONObject mark=json("account",row.origin.accountKey(),"chat",row.location.chat.token(),"id",Integer.toString(row.location.markId),
                    "date",Long.toString(row.location.markDate),"text",row.text,"hasMedia",false,"ownership","Own","outcome","Valid");
            JSONObject input=json("account",row.origin.accountKey(),"stale",cache(row.location),"mark",mark,"resolution",resolution,"complete",frame.status.complete);
            PurpleSyncChatMoveCore.Result result=PurpleSyncChatMoveCore.request("checkStaleMarkRemoval",input.toString().getBytes(StandardCharsets.UTF_8));
            return result.status.equals("Valid")&&result.fields.optBoolean("allowed");
        }catch(RuntimeException|LinkageError error){return false;}
    }
    private static JSONObject cache(Cache c){return json("chat",c.chat.token(),"id",Integer.toString(c.markId),"date",Long.toString(c.markDate));}
    private static boolean media(TLRPC.Message m){return m.media!=null&&m.media.getClass()!=TLRPC.TL_messageMediaEmpty.class;}
    @Override public void close(){
        if(closed)return;closed=true;++generation;phase="Closed";release();
    }
}
