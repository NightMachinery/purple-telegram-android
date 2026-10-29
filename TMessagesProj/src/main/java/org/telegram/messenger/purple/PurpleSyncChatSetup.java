package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatSetup implements AutoCloseable {
    enum Phase { Ready, Resolve, Create, ReconcileCreate, ApplyUpdates, PeerInfo, Inventory, PostMark, ReadMark, Pin, Mute, Archive, Complete, Provisioned, Adopted, Held, Limit, Cancelled }
    enum Step { Ready, CreateStarted, PeerInfo, MarkStarted, ReadMark, Commit, Pin, Mute, Archive, Complete, Provisioned }
    enum Error { None, Limit, Flood, Restricted, Unknown }
    enum Effect { Create, Mark, Pin, Mute, Archive }
    static final class Command {
        final PurpleSyncChatOrigin origin;
        final String operation;
        final long run,ticket,random;
        final Effect effect;
        final Step step;
        final Peer chat;
        final Cache mark;
        Command(PurpleSyncChatOrigin origin,Journal journal,long run,long ticket,Effect effect) {
            this.origin=origin;operation=journal.operation;this.run=run;this.ticket=ticket;this.effect=effect;
            step=journal.step;chat=journal.chat;mark=journal.mark;random=journal.random;
        }
    }
    static final class ResumeProof {
        PurpleSyncChatScreen.Frame resolution;
        PurpleSyncChatClient.Info first,second;
        PurpleSyncInventory inventory;
        ReadBack mark;
    }
    static final class Reply<T> {
        final PurpleSyncChatOrigin origin;
        final Error error;
        final T value;
        final int retryAfterSeconds;
        Reply(PurpleSyncChatOrigin origin,Error error,T value) { this(origin,error,value,0); }
        Reply(PurpleSyncChatOrigin origin,Error error,T value,int retryAfterSeconds) { this.origin=origin;this.error=error;this.value=value;this.retryAfterSeconds=retryAfterSeconds; }
    }
    static final class Created {
        final Peer chat;
        final TLRPC.Updates updates;
        final String operation;
        Created(Peer chat,TLRPC.Updates updates,String operation) { this.chat=chat;this.updates=updates;this.operation=operation; }
    }
    static final class Posted {
        final Cache mark;
        final TLRPC.Updates updates;
        final long random;
        Posted(Cache mark,TLRPC.Updates updates,long random) { this.mark=mark;this.updates=updates;this.random=random; }
    }
    static final class ReadBack {
        final TLObject response;
        final TLRPC.TL_error error;
        final PurpleSyncChatClient.Info info;
        ReadBack(TLObject response,TLRPC.TL_error error,PurpleSyncChatClient.Info info) { this.response=response;this.error=error;this.info=info; }
    }
    interface JournalIO {
        byte[] read() throws Exception;
        boolean save(byte[] expected,byte[] next,BooleanSupplier current);
    }
    interface Env {
        PurpleSyncChatOrigin origin();
        String authority();
        boolean current();
        boolean foreground();
        PurpleSyncChatService.Binding binding();
        boolean acquire(Object owner);
        boolean owned(Object owner);
        void release(Object owner);
        void dispatch(Runnable task);
        void delay(Runnable task,long millis);
        void cancelDelay(Runnable task);
        JournalIO journal();
        long randomId();
        long nowSeconds();
        void resolve(Object owner,Consumer<Reply<PurpleSyncChatScreen.Frame>> done);
        void create(Consumer<Reply<Created>> done);
        void reconcileCreate(String operation,Consumer<Reply<Created>> done);
        void applyUpdates(TLRPC.Updates updates,Consumer<Reply<Boolean>> done);
        void info(Peer chat,Consumer<Reply<PurpleSyncChatClient.Info>> done);
        void inventory(Peer chat,Consumer<Reply<PurpleSyncInventory>> done);
        void post(PurpleSyncChatClient.Info info,long randomId,Consumer<Reply<Posted>> done);
        void reconcileMark(Peer chat,long random,Consumer<Reply<Posted>> done);
        void exact(Cache mark,Consumer<Reply<ReadBack>> done);
        boolean commit(Cache mark,PurpleSyncChatClient.Info info,PurpleSyncChatCache.ReadersSnapshot baseline,boolean newReaders,BooleanSupplier current);
        void pin(Cache mark,Consumer<Reply<Boolean>> done);
        void mute(Peer chat,Consumer<Reply<Boolean>> done);
        void archive(Peer chat,Consumer<Reply<Boolean>> done);
        void cancel();
    }
    static final class Journal {
        Step step=Step.Ready;
        boolean create,created,newReaders,deferred;
        Peer chat;
        Cache mark;
        long random;
        long retryAt;
        int attempts;
        String operation=java.util.UUID.randomUUID().toString(),readers="",binding="",purpose="Setup",openingStamp="";
        JSONObject json(PurpleSyncChatOrigin origin,String authority) {
            return PurpleSyncChatCore.json("version",1,"account",origin.accountKey(),"authority",authority,"step",step.name(),
                    "create",create,"created",created,"chat",chat!=null?chat.token():"","markId",mark!=null?mark.markId:0,
                    "markDate",Long.toString(mark!=null?mark.markDate:0),"random",Long.toString(random),"readers",readers,"binding",binding,
                    "purpose",purpose,"openingStamp",openingStamp,"operation",operation,"attempts",attempts,"retryAt",Long.toString(retryAt),"newReaders",newReaders,"deferred",deferred);
        }
        PurpleSyncChatCache.ReadersSnapshot baseline() throws Exception {
            int suffix=readers.lastIndexOf(':');
            if(suffix<0||!readers.substring(suffix+1).matches("true|false"))throw new IllegalArgumentException("SetupJournalReaders");
            return PurpleSyncChatCache.ReadersSnapshot.parse(new JSONObject(readers.substring(0,suffix)));
        }
        static Journal parse(byte[] bytes,PurpleSyncChatOrigin origin,String authority)throws Exception {
            if(bytes.length>16384)throw new IllegalArgumentException("SetupJournalSize");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JSONObject value=new JSONObject(text);
            if(authority==null||!authority.matches("[0-9a-f]{64}")||!Integer.valueOf(1).equals(value.get("version"))||!origin.accountKey().equals(value.getString("account"))
                    ||!authority.equals(value.getString("authority")))throw new IllegalArgumentException("SetupJournalAuthority");
            Journal result=new Journal();result.step=Step.valueOf(value.getString("step"));
            Object create=value.get("create"),created=value.get("created"),id=value.get("markId");
            if(!(create instanceof Boolean)||!(created instanceof Boolean)||!(id instanceof Integer))throw new IllegalArgumentException("SetupJournalType");
            result.create=(Boolean)create;result.created=(Boolean)created;
            String token=value.getString("chat");result.chat=token.isEmpty()?null:Peer.parse(token,origin.userId);
            if(!token.isEmpty()&&(result.chat==null||!result.chat.token().equals(token)))throw new IllegalArgumentException("SetupJournalChat");
            long date=Long.parseLong(value.getString("markDate"));result.random=Long.parseLong(value.getString("random"));
            if(result.random==0||!Long.toString(result.random).equals(value.getString("random"))||date<0||!Long.toString(date).equals(value.getString("markDate"))
                    ||(Integer)id<0||((Integer)id==0)!=(date==0))throw new IllegalArgumentException("SetupJournalMark");
            result.mark=(Integer)id>0&&result.chat!=null?new Cache(result.chat,(Integer)id,date):null;
            result.readers=value.getString("readers");result.binding=value.getString("binding");
            if(value.has("newReaders")) {
                Object warning=value.get("newReaders");if(!(warning instanceof Boolean))throw new IllegalArgumentException("SetupJournalWarning");
                result.newReaders=(Boolean)warning;
            }
            if(value.has("deferred")){Object deferred=value.get("deferred");if(!(deferred instanceof Boolean))throw new IllegalArgumentException("SetupJournalDeferred");result.deferred=(Boolean)deferred;}
            result.purpose=value.optString("purpose","Setup");result.openingStamp=value.optString("openingStamp","");
            boolean provision=result.purpose.equals("MoveProvision");
            if(!provision&&!result.purpose.equals("Setup")||provision&&!result.openingStamp.matches("[0-9a-f]{64}")
                    ||!provision&&!result.openingStamp.isEmpty())throw new IllegalArgumentException("SetupJournalPurpose");
            result.operation=value.getString("operation");Object attempts=value.get("attempts");
            result.retryAt=Long.parseLong(value.getString("retryAt"));
            if(!(attempts instanceof Integer)||(Integer)attempts<0||(Integer)attempts>3||result.retryAt<0||!Long.toString(result.retryAt).equals(value.getString("retryAt"))
                    ||!result.operation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("SetupJournalOperation");
            result.attempts=(Integer)attempts;
            if(result.readers.length()>15500||result.binding.length()>1024||result.step==Step.CreateStarted&&result.attempts==0||result.created&&(!result.create||result.chat==null||result.chat.kind!=PeerKind.Channel)
                    ||!result.create&&result.chat==null||result.step.ordinal()>=Step.PeerInfo.ordinal()&&result.chat==null
                    ||!provision&&result.step.ordinal()>=Step.ReadMark.ordinal()&&result.mark==null
                    ||provision&&(!result.create||result.mark!=null||result.step!=Step.Ready&&result.step!=Step.CreateStarted&&result.step!=Step.PeerInfo&&result.step!=Step.Provisioned)
                    ||!provision&&result.step==Step.Provisioned)throw new IllegalArgumentException("SetupJournalState");
            if(!result.readers.isEmpty())result.baseline();
            return result;
        }
    }

    private java.util.function.BooleanSupplier parentCurrent=()->true;
    private final Env env;
    private final PurpleSyncChatScreen.ChangeSelection change;
    private final PurpleSyncChatOrigin origin;
    private final String authority;
    private final Consumer<Phase> observer;
    private PurpleSyncChatScreen.Selection selection;
    private Journal journal;
    private Cache observedWinner;
    private byte[] opening;
    private Phase phase=Phase.Ready;
    private String reason="";
    private long run,ticket;
    private boolean busy,closed,warningCommitted;
    private Runnable timeout;
    private long timeoutEpoch;
    private Command issued;

    PurpleSyncChatSetup(Env env,PurpleSyncChatScreen.Selection selection,Consumer<Phase> observer) {
        this.env=env;this.origin=env.origin();this.authority=authority(env.authority());this.selection=selection;this.observer=observer;change=null;
    }
    static PurpleSyncChatSetup provision(Env env,PurpleSyncChatScreen.ChangeSelection change,Consumer<Phase> observer) {return provision(env,change,observer,()->true);}
    static PurpleSyncChatSetup provision(Env env,PurpleSyncChatScreen.ChangeSelection change,Consumer<Phase> observer,java.util.function.BooleanSupplier parentCurrent) {
        if(change==null||!change.offer.createChannel)throw new IllegalArgumentException("ProvisionConsentRequired");
        PurpleSyncChatSetup result=new PurpleSyncChatSetup(env,change,observer,true);result.parentCurrent=parentCurrent;return result;
    }
    private PurpleSyncChatSetup(Env env,PurpleSyncChatScreen.ChangeSelection change,Consumer<Phase> observer,boolean provision) {
        this.env=env;origin=env.origin();authority=authority(env.authority());this.change=change;this.observer=observer;
    }
    Phase phase() { return phase; }
    boolean busy() { return busy; }
    Command command() { return issued; }
    Journal journal() { return journal; }
    String reason() { return reason; }
    boolean newReaders() { return journal!=null&&journal.newReaders; }
    boolean live() { return !closed&&busy&&parentCurrent.getAsBoolean()&&origin.equals(env.origin())&&env.current()&&env.foreground()&&env.owned(this); }
    private boolean writable() {
        if(!live())return false;
        PurpleSyncChatService.Binding binding=env.binding();
        if(change!=null)return change.current(origin,env.authority())&&binding!=null&&binding.ok&&change.offer.opening.binding.same(binding)
                &&change.offer.stateStamp.equals(binding.stateStamp)&&(journal==null||journal.binding.equals(binding.identity));
        return binding!=null&&binding.ok&&!binding.bound&&(journal==null||journal.binding.equals(binding.identity));
    }
    private boolean checkWritable() {
        if(writable())return true;
        finish(Phase.Held,"NotForegroundOrBindingChanged");return false;
    }
    void start() {
        if(closed||busy||!parentCurrent.getAsBoolean()||!env.current()||!env.foreground()||!origin.equals(env.origin())||!env.acquire(this))return;
        busy=true;warningCommitted=false;++run;
        try {
            if(!live()){finish(Phase.Held,"AccountUnavailable");return;}
            opening=env.journal().read();
            if(opening!=null)journal=Journal.parse(opening,origin,authority);
            else {
                if(change==null&&(selection==null||!origin.equals(selection.origin))){finish(Phase.Held,"ConsentRequired");return;}
                journal=new Journal();journal.create=change!=null||selection.createChannel;journal.chat=change==null?selection.chat:null;
                if(change!=null){journal.purpose="MoveProvision";journal.openingStamp=change.offer.stamp();}
                journal.readers=selection!=null&&selection.candidate!=null?selection.candidate.readers:"";journal.random=env.randomId();
                PurpleSyncChatService.Binding binding=env.binding();
                if(binding==null||!binding.ok||change==null&&binding.bound||journal.random==0){finish(Phase.Held,"BindingUnavailable");return;}
                journal.binding=binding.identity;
            }
            if(change==null&&!journal.purpose.equals("Setup")||change!=null&&(!journal.purpose.equals("MoveProvision")
                    ||!change.current(origin,env.authority())||!journal.openingStamp.equals(change.offer.stamp()))) {finish(Phase.Held,"OpeningOrPurposeChanged");return;}
            if(env.nowSeconds()<0){finish(Phase.Held,"ClockUnavailable");return;}
            if(journal.retryAt>env.nowSeconds()){finish(Phase.Held,"FloodWait");return;}
            resolve();
        } catch(Exception|LinkageError error) { finish(Phase.Held,"JournalUnavailable"); }
    }
    private void resolve() {
        if(!writable()){finish(Phase.Held,"BindingChanged");return;}
        emit(Phase.Resolve);if(!live())return;
        env.resolve(this,callback(reply->{
            PurpleSyncChatScreen.Frame frame=reply.value;
            if(reply.error!=Error.None||frame==null||!frame.status.complete||change==null&&frame.bound||!frame.binding.identity.equals(journal.binding)) {finish(Phase.Held,"ResolutionUnknown");return;}
            if(change!=null){
                if(!change.offer.matches(frame)){finish(Phase.Held,"OpeningChanged");return;}
                if(journal.step==Step.CreateStarted)reconcileCreate();
                else if(journal.step==Step.Ready)create();else inspect();
                return;
            }
            Cache winner=frame.opening!=null&&frame.opening.data!=null?frame.opening.data.location:null;
            observedWinner=winner;
            if(frame.status.phase==PurpleSyncChat.Phase.Use&&winner!=null) {
                boolean reconcileOwn=!journal.deferred&&journal.step==Step.MarkStarted&&journal.chat!=null&&journal.chat.equals(winner.chat);
                if(!reconcileOwn&&(journal.mark==null||!journal.mark.equals(winner))) {
                    boolean started=journal.created||journal.mark!=null||!journal.deferred&&(journal.step==Step.CreateStarted||journal.step==Step.MarkStarted);
                    finish(started?Phase.Held:Phase.Adopted,started?"WinnerChangedAfterSetupStarted":"ExistingWinner");return;
                }
            } else if(!frame.canSetup){finish(Phase.Held,"NotFresh");return;}
            if(journal.step==Step.Complete){finish(Phase.Complete,"");return;}
            if(journal.deferred&&(journal.step==Step.CreateStarted||journal.step==Step.MarkStarted)){
                journal.deferred=false;journal.retryAt=0;
                if(journal.step==Step.CreateStarted){journal.attempts=Math.max(0,journal.attempts-1);if(persist(Step.Ready))create();}
                else if(persist(Step.PeerInfo))inspect();
            } else if(journal.step==Step.CreateStarted)reconcileCreate();
            else if(journal.step==Step.Ready&&journal.create)create();
            else inspect();
        }));
    }
    private void create() {
        if(journal.attempts>=3){finish(Phase.Held,"CreationRetryLimit");return;}
        ++journal.attempts;
        if(!persist(Step.CreateStarted))return;
        emit(Phase.Create);if(!checkWritable())return;
        env.create(effectCallback(Effect.Create,reply->{
            if(reply.error==Error.Limit||reply.error==Error.Flood||reply.error==Error.Restricted) {
                if(reply.error==Error.Flood)journal.retryAt=retryDeadline(reply.retryAfterSeconds);
                if(!persist(Step.Ready))return;
                finish(reply.error==Error.Limit?Phase.Limit:Phase.Held,reply.error.name());return;
            }
            created(reply);
        }));
    }
    private void reconcileCreate() {
        emit(Phase.ReconcileCreate);if(!checkWritable())return;
        env.reconcileCreate(journal.operation,callback(this::created));
    }
    private void created(Reply<Created> reply) {
        Created value=reply.value;
        if(reply.error!=Error.None||value==null||value.chat==null||value.chat.kind!=PeerKind.Channel||value.updates==null) {finish(Phase.Held,"CreationUnknown");return;}
        if(phase==Phase.ReconcileCreate&&!journal.operation.equals(value.operation)){finish(Phase.Held,"CreationOwnershipUnknown");return;}
        apply(value.updates,()->{journal.chat=value.chat;journal.created=true;if(persist(Step.PeerInfo))inspect();});
    }
    private void inspect() {
        if(!writable()||journal.chat==null){finish(Phase.Held,"ChatUnavailable");return;}
        emit(Phase.PeerInfo);if(!checkWritable())return;
        env.info(journal.chat,callback(reply->{
            PurpleSyncChatScreen.Candidate candidate=new PurpleSyncChatScreen.Candidate(origin,journal.chat,reply.value);
            if(reply.error!=Error.None||!candidate.allowed){finish(Phase.Held,candidate.reason);return;}
            if(journal.created&&(candidate.info.peer.kind!=Kind.Broadcast
                    ||!candidate.info.peer.facts.creator||!candidate.info.peer.facts.soleAdmin)){finish(Phase.Held,"CreationRightsChanged");return;}
            if(journal.readers.isEmpty()) {
                boolean accepted=journal.mark==null&&journal.step.ordinal()<=Step.PeerInfo.ordinal()&&selection!=null
                        &&!selection.createChannel&&origin.equals(selection.origin)&&journal.chat.equals(selection.chat)
                        &&selection.candidate!=null&&selection.candidate.same(candidate);
                if((!journal.created&&!journal.chat.equals(Peer.self())||candidate.eligibility.readers.warningRequired)&&!accepted){finish(Phase.Held,"ReaderConsentRequired");return;}
                journal.readers=candidate.readers;if(!persist(journal.step))return;
            } else if((change!=null||journal.step.ordinal()<Step.MarkStarted.ordinal())&&!journal.readers.equals(candidate.readers)) {
                if(journal.mark==null&&journal.step.ordinal()<=Step.PeerInfo.ordinal()&&selection!=null&&origin.equals(selection.origin)
                        &&selection.candidate!=null&&journal.chat.equals(selection.chat)&&selection.candidate.same(candidate)) {
                    journal.readers=candidate.readers;if(!persist(journal.step))return;
                } else {finish(Phase.Held,"ReadersChanged");return;}
            }
            if(change==null&&journal.step.ordinal()>=Step.MarkStarted.ordinal()&&!retainConsent(candidate))return;
            switch(journal.step) {
                case Ready:case PeerInfo:case Provisioned:inventory(candidate);break;
                case MarkStarted:reconcileMark();break;
                case ReadMark:case Commit:readMark();break;
                case Pin:decorate(Phase.Pin,Step.Mute,done->env.pin(journal.mark,done));break;
                case Mute:decorate(Phase.Mute,Step.Archive,done->env.mute(journal.chat,done));break;
                case Archive:decorate(Phase.Archive,Step.Complete,done->env.archive(journal.chat,done));break;
                default:finish(Phase.Held,"InvalidStep");
            }
        }));
    }
    private void inventory(PurpleSyncChatScreen.Candidate disclosed) {
        emit(Phase.Inventory);if(!checkWritable())return;
        env.inventory(journal.chat,callback(reply->{
            PurpleSyncInventory inventory=reply.value;
            if(reply.error!=Error.None||inventory==null||!origin.equals(inventory.origin)
                    ||PurpleSyncChat.recordPresence(inventory,origin.userId,journal.chat)!=RecordPresence.Empty) {
                finish(Phase.Held,"TargetInventoryNotEmptyOrUnknown");return;
            }
            emit(Phase.PeerInfo);if(!checkWritable())return;
            env.info(journal.chat,callback(fresh->{
                PurpleSyncChatScreen.Candidate candidate=new PurpleSyncChatScreen.Candidate(origin,journal.chat,fresh.value);
                if(fresh.error!=Error.None||!disclosed.same(candidate)||!journal.readers.equals(candidate.readers)){finish(Phase.Held,"ReadersOrRightsChanged");return;}
                if(change!=null){if(persist(Step.Provisioned))finish(Phase.Provisioned,"");}
                else post(candidate.info);
            }));
        }));
    }
    private void post(PurpleSyncChatClient.Info info) {
        if(change!=null){finish(Phase.Held,"ProvisionOnly");return;}
        if(!persist(Step.MarkStarted))return;
        emit(Phase.PostMark);if(!checkWritable())return;
        env.post(info,journal.random,effectCallback(Effect.Mark,reply->{
            if(reply.error==Error.Flood||reply.error==Error.Restricted) {
                if(reply.error==Error.Flood)journal.retryAt=retryDeadline(reply.retryAfterSeconds);
                if(persist(Step.PeerInfo))finish(Phase.Held,reply.error.name());return;
            }
            Posted value=reply.value;
            if(reply.error!=Error.None||value==null||!valid(value.mark)||value.updates==null||value.random!=journal.random){finish(Phase.Held,"MarkUnknown");return;}
            apply(value.updates,()->{journal.mark=value.mark;if(persist(Step.ReadMark))readMark();});
        }));
    }
    private boolean valid(Cache mark) { return mark!=null&&journal.chat.equals(mark.chat)&&mark.markId>0&&mark.markDate>0; }
    private long retryDeadline(int seconds) { return Math.addExact(env.nowSeconds(),seconds>0?seconds:60); }
    private void apply(TLRPC.Updates updates,Runnable next) {
        emit(Phase.ApplyUpdates);if(!checkWritable())return;
        env.applyUpdates(updates,callback(reply->{
            if(reply.error!=Error.None||!Boolean.TRUE.equals(reply.value)){finish(Phase.Held,"UpdatesUnknown");return;}
            if(checkWritable())next.run();
        }));
    }
    private void reconcileMark() {
        emit(Phase.ReadMark);if(!checkWritable())return;
        env.reconcileMark(journal.chat,journal.random,callback(reply->{
            Posted found=reply.value;
            if(reply.error!=Error.None||found==null||!valid(found.mark)||found.random!=journal.random){finish(Phase.Held,"MarkUnknown");return;}
            if(observedWinner!=null&&!observedWinner.equals(found.mark)){finish(Phase.Held,"WinnerChangedAfterSetupStarted");return;}
            Runnable next=()->{journal.mark=found.mark;if(persist(Step.ReadMark))readMark();};
            if(found.updates!=null)apply(found.updates,next);else next.run();
        }));
    }
    private void readMark() {
        emit(Phase.ReadMark);if(!checkWritable())return;
        env.exact(journal.mark,callback(reply->{
            ReadBack raw=reply.value;
            if(raw==null||!(raw.response instanceof TLRPC.messages_Messages)){finish(Phase.Held,"MarkReadBackUnknown");return;}
            TLRPC.messages_Messages page=(TLRPC.messages_Messages)raw.response;
            if(page.messages.size()!=1||raw.response.getClass()==TLRPC.TL_messages_channelMessages.class&&page.count<page.messages.size()) {finish(Phase.Held,"MarkReadBackUnknown");return;}
            PurpleSyncChatClient.Exact exact=PurpleSyncChatTelegram.classify(origin,journal.mark,raw.response,raw.error,raw.info);
            PurpleSyncChatScreen.Candidate candidate=new PurpleSyncChatScreen.Candidate(origin,journal.chat,exact!=null?exact.info:null);
            TLRPC.Message message=exact!=null?exact.message:null;
            if(reply.error!=Error.None||exact==null||exact.state!=DirectRead.Valid||!candidate.allowed
                    ||message==null||message.id!=journal.mark.markId||message.date!=journal.mark.markDate||!journal.chat.equals(PurpleSyncChatFacts.token(message.peer_id,origin.userId))
                    ||!PurpleSyncChatSetupRequests.MARK.equals(message.message)||message.media!=null&&message.media.getClass()!=TLRPC.TL_messageMediaEmpty.class
                    ||ownership(PurpleSyncChatFacts.message(origin,exact.info.peer,message),Options.restricted())!=Ownership.Own) {
                finish(Phase.Held,"MarkReadBackUnknown");return;
            }
            if(!retainConsent(candidate)||!persist(Step.Commit))return;
            if(!commitConsent(exact.info))return;
            if(persist(journal.created?Step.Pin:Step.Complete)) {
                if(journal.created)inspect();else finish(Phase.Complete,"");
            }
        }));
    }
    private boolean retainConsent(PurpleSyncChatScreen.Candidate candidate) {
        try {
            PurpleSyncChatCache.ReadersSnapshot baseline=journal.baseline(),current=PurpleSyncChatCache.ReadersSnapshot.from(candidate.info);
            if(!candidate.allowed||current==null||baseline.creator&&!current.creator
                    ||journal.created&&(candidate.info.peer.kind!=Kind.Broadcast||!current.creator||!candidate.info.peer.facts.soleAdmin)) {
                finish(Phase.Held,"ReadersOrRightsUnknown");return false;
            }
            if(!journal.newReaders&&baseline.newReaders(current)) {
                journal.newReaders=true;if(!persist(journal.step))return false;
            }
            return journal.step.ordinal()<Step.Pin.ordinal()||!journal.newReaders||warningCommitted||commitConsent(candidate.info);
        } catch(Exception error) { finish(Phase.Held,"ReaderConsentRequired");return false; }
    }
    private boolean commitConsent(PurpleSyncChatClient.Info info) {
        try {
            if(writable()&&env.commit(journal.mark,info,journal.baseline(),journal.newReaders,this::writable)&&writable()) {
                warningCommitted=journal.newReaders;return true;
            }
        } catch(Exception error) { }
        finish(Phase.Held,"CacheUnavailable");return false;
    }
    private void decorate(Phase phase,Step next,Consumer<Consumer<Reply<Boolean>>> effect) {
        emit(phase);if(!checkWritable())return;
        effect.accept(effectCallback(Effect.valueOf(phase.name()),reply->{
            if(reply.error!=Error.None||!Boolean.TRUE.equals(reply.value)){finish(Phase.Held,phase.name()+"Unknown");return;}
            if(!persist(next))return;
            if(next==Step.Complete)finish(Phase.Complete,"");else inspect();
        }));
    }
    private boolean persist(Step step) {
        if(!writable()){finish(Phase.Held,"NotForeground");return false;}
        journal.step=step;byte[] next=journal.json(origin,authority).toString().getBytes(StandardCharsets.UTF_8);
        if(next.length>16384||!env.journal().save(opening,next,this::writable)||!writable()){finish(Phase.Held,"JournalUnavailable");return false;}
        opening=next;return true;
    }
    private <T> Consumer<Reply<T>> callback(Consumer<Reply<T>> done) {
        long capturedRun=run,capturedTicket=++ticket;cancelTimeout();
        armTimeout(capturedRun,capturedTicket,30000);
        return reply->env.dispatch(()->{
            if(capturedRun!=run||capturedTicket!=ticket||!busy)return;
            ++ticket;cancelTimeout();
            if(!live()){finish(Phase.Held,"NotForeground");return;}
            if(reply==null||!origin.equals(reply.origin)){finish(Phase.Held,"OriginChanged");return;}
            try { done.accept(reply); } catch(RuntimeException|LinkageError error) { finish(Phase.Held,"StepUnavailable"); }
        });
    }
    private <T> Consumer<Reply<T>> effectCallback(Effect effect,Consumer<Reply<T>> done) {
        Consumer<Reply<T>> callback=callback(reply->{issued=null;done.accept(reply);});
        issued=new Command(origin,journal,run,ticket,effect);
        return callback;
    }
    private void armTimeout(long capturedRun,long capturedTicket,long millis) {
        cancelTimeout();
        long capturedEpoch=timeoutEpoch;
        timeout=()->env.dispatch(()->{
            if(capturedEpoch!=timeoutEpoch)return;
            timeout=null;++timeoutEpoch;
            if(capturedRun==run&&capturedTicket==ticket&&busy)finish(Phase.Held,"RequestTimeout");
        });
        env.delay(timeout,millis);
    }
    boolean commandCurrent(Command command) {
        return command!=null&&issued==command&&live()&&command.run==run&&command.ticket==ticket
                &&origin.equals(command.origin)&&journal.operation.equals(command.operation)&&journal.step==command.step
                &&journal.random==command.random&&java.util.Objects.equals(journal.chat,command.chat)&&java.util.Objects.equals(journal.mark,command.mark);
    }
    boolean prepare(Command command) {
        if(!commandCurrent(command))return false;
        if(command.effect!=Effect.Create&&command.effect!=Effect.Mark||journal.deferred)return true;
        journal.deferred=true;return persist(journal.step)&&commandCurrent(command);
    }
    boolean waiting(Command command,long retryAt) {
        if(!commandCurrent(command)||retryAt<env.nowSeconds())return false;
        journal.retryAt=retryAt;journal.deferred=true;
        if(!persist(journal.step))return false;
        armTimeout(run,ticket,Math.max(30000,Math.addExact(Math.multiplyExact(retryAt-env.nowSeconds(),1000L),120000)));
        reason="TelegramWait";emit(phase);return commandCurrent(command);
    }
    void readWaiting(PurpleRpcRetry.Transient value) {
        if(!live()||value==null)return;
        long remaining=Math.max(0,value.retryAt-Math.multiplyExact(env.nowSeconds(),1000L));
        armTimeout(run,ticket,PurpleRpcRetry.add(remaining,120000));
    }
    boolean renew(Command command,ResumeProof proof) {
        if(!commandCurrent(command)||proof==null||proof.resolution==null||!writable())return false;
        try {
            Journal candidate=Journal.parse(journal.json(origin,authority).toString().getBytes(StandardCharsets.UTF_8),origin,authority);
            if(command.effect==Effect.Create){candidate.step=Step.Ready;candidate.attempts=Math.max(0,candidate.attempts-1);}
            else if(command.effect==Effect.Mark)candidate.step=Step.PeerInfo;
            else candidate.step=Step.ReadMark;
            candidate.retryAt=0;candidate.deferred=false;
            final byte[][] stored={candidate.json(origin,authority).toString().getBytes(StandardCharsets.UTF_8)};
            final boolean[] reached={false},committed={false};final int[] peers={0};
            Env verifierEnv=new Env() {
                public PurpleSyncChatOrigin origin(){return origin;}
                public String authority(){return env.authority();}
                public boolean current(){return commandCurrent(command)&&writable();}
                public boolean foreground(){return env.foreground();}
                public PurpleSyncChatService.Binding binding(){return proof.resolution.binding;}
                public boolean acquire(Object owner){return current();}
                public boolean owned(Object owner){return current();}
                public void release(Object owner){}
                public void dispatch(Runnable task){task.run();}
                public void delay(Runnable task,long millis){}
                public void cancelDelay(Runnable task){}
                public JournalIO journal(){return new JournalIO(){public byte[] read(){return stored[0];}public boolean save(byte[] expected,byte[] next,BooleanSupplier active){if(!active.getAsBoolean()||!java.util.Arrays.equals(expected,stored[0]))return false;stored[0]=next;return true;}};}
                public long randomId(){return command.random;}
                public long nowSeconds(){return env.nowSeconds();}
                public void resolve(Object owner,Consumer<Reply<PurpleSyncChatScreen.Frame>> done){done.accept(new Reply<>(origin,Error.None,proof.resolution));}
                private void effect(Effect effect){reached[0]=command.effect==effect;}
                public void create(Consumer<Reply<Created>> done){effect(Effect.Create);}
                public void reconcileCreate(String operation,Consumer<Reply<Created>> done){done.accept(new Reply<>(origin,Error.Unknown,null));}
                public void applyUpdates(TLRPC.Updates updates,Consumer<Reply<Boolean>> done){done.accept(new Reply<>(origin,Error.None,true));}
                public void info(Peer chat,Consumer<Reply<PurpleSyncChatClient.Info>> done){
                    PurpleSyncChatClient.Info info=peers[0]++==0?proof.first:proof.second!=null?proof.second:proof.mark!=null?proof.mark.info:proof.first;
                    done.accept(new Reply<>(origin,info!=null?Error.None:Error.Unknown,info));
                }
                public void inventory(Peer chat,Consumer<Reply<PurpleSyncInventory>> done){done.accept(new Reply<>(origin,proof.inventory!=null?Error.None:Error.Unknown,proof.inventory));}
                public void post(PurpleSyncChatClient.Info info,long random,Consumer<Reply<Posted>> done){effect(Effect.Mark);}
                public void reconcileMark(Peer chat,long random,Consumer<Reply<Posted>> done){done.accept(new Reply<>(origin,Error.Unknown,null));}
                public void exact(Cache mark,Consumer<Reply<ReadBack>> done){done.accept(new Reply<>(origin,proof.mark!=null?Error.None:Error.Unknown,proof.mark));}
                public boolean commit(Cache mark,PurpleSyncChatClient.Info info,PurpleSyncChatCache.ReadersSnapshot baseline,boolean warning,BooleanSupplier active){
                    boolean saved=current()&&env.commit(mark,info,baseline,warning,()->current()&&active.getAsBoolean());committed[0]|=saved;return saved;
                }
                public void pin(Cache mark,Consumer<Reply<Boolean>> done){if(command.effect==Effect.Pin)effect(Effect.Pin);else done.accept(new Reply<>(origin,Error.None,true));}
                public void mute(Peer chat,Consumer<Reply<Boolean>> done){if(command.effect==Effect.Mute)effect(Effect.Mute);else done.accept(new Reply<>(origin,Error.None,true));}
                public void archive(Peer chat,Consumer<Reply<Boolean>> done){effect(Effect.Archive);}
                public void cancel(){}
            };
            PurpleSyncChatSetup verifier=new PurpleSyncChatSetup(verifierEnv,null,ignored->{});verifier.start();
            Journal verified=verifier.journal;
            if(!reached[0]||!commandCurrent(command)||verified==null||!verified.operation.equals(command.operation)
                    ||verified.random!=command.random||!java.util.Objects.equals(verified.chat,command.chat)||!java.util.Objects.equals(verified.mark,command.mark))return false;
            journal.readers=verified.readers;journal.newReaders|=verified.newReaders;journal.retryAt=0;journal.deferred=false;
            if(!persist(command.step))return false;
            if(committed[0]&&journal.newReaders)warningCommitted=true;
            reason="";armTimeout(run,ticket,30000);return commandCurrent(command);
        } catch(Exception|LinkageError error) { if(commandCurrent(command))finish(Phase.Held,"RenewalUnavailable");return false; }
    }
    private void emit(Phase phase) {
        this.phase=phase;
        try { observer.accept(phase); } catch(RuntimeException error) { close(); }
        if(busy&&!live())finish(Phase.Held,"NotForeground");
    }
    private void cancelTimeout() {
        ++timeoutEpoch;Runnable cancelled=timeout;timeout=null;
        if(cancelled!=null)env.cancelDelay(cancelled);
    }
    private void finish(Phase phase,String reason) {
        if(!busy)return;
        this.phase=phase;this.reason=reason;busy=false;issued=null;++run;++ticket;
        try { cancelTimeout(); } catch(RuntimeException|LinkageError ignored) { timeout=null; }
        try { env.cancel(); } catch(RuntimeException|LinkageError ignored) { }
        finally { env.release(this); }
        if(!closed&&env.current()&&env.foreground()) {
            try { observer.accept(phase); } catch(RuntimeException|LinkageError ignored) { }
        }
    }
    void tryAgain() { if(phase==Phase.Limit||phase==Phase.Held)start(); }
    void useSavedMessages() {
        if(change!=null||busy||closed||!env.current()||!env.foreground())return;
        if(journal==null||!journal.create||journal.mark!=null||journal.created||journal.step!=Step.Ready||phase!=Phase.Limit&&phase!=Phase.Held)return;
        if(journal.retryAt>env.nowSeconds())return;
        if(!env.acquire(this))return;busy=true;++run;
        try {
            PurpleSyncChatService.Binding binding=env.binding();
            if(binding==null||!binding.ok||binding.bound){finish(Phase.Held,"BindingUnavailable");return;}
            journal=new Journal();journal.chat=Peer.self();journal.random=env.randomId();journal.binding=binding.identity;
            if(journal.random==0){finish(Phase.Held,"RandomUnavailable");return;}
            if(!persist(Step.Ready))return;
            env.info(Peer.self(),callback(reply->{
                PurpleSyncChatScreen.Candidate self=new PurpleSyncChatScreen.Candidate(origin,Peer.self(),reply.value);
                if(reply.error!=Error.None||!self.allowed){finish(Phase.Held,"SelfUnavailable");return;}
                journal.readers=self.readers;if(persist(Step.Ready))resolve();
            }));
        } catch(RuntimeException|LinkageError error) { finish(Phase.Held,"SelfUnavailable"); }
    }
    @Override public void close() {
        if(closed)return;
        closed=true;
        if(busy)finish(Phase.Cancelled,"Cancelled");else {phase=Phase.Cancelled;try{cancelTimeout();}finally{try{env.cancel();}finally{env.release(this);}}}
    }
    static JournalIO file(java.io.File path) {
        PurpleSyncChatCache.IO io=PurpleSyncChatCache.file(path);
        return new JournalIO() {
            public byte[] read()throws Exception { return io.read(); }
            public boolean save(byte[] expected,byte[] next,BooleanSupplier current) {
                return next.length<=16384&&current.getAsBoolean()&&io.write(expected,next)&&current.getAsBoolean();
            }
        };
    }
    static JournalIO provisionFile(java.io.File path) {
        return new JournalIO(){
            public byte[] read()throws Exception {
                PurpleAccountFileSnapshot.Durable value=PurpleAccountFileSnapshot.durable("read",path,null,null,16384,0);
                PurpleAccountFileSnapshot.durable("barrier",path,value,null,16384,0);return value.bytes==null?null:value.bytes.clone();
            }
            public boolean save(byte[] expected,byte[] next,BooleanSupplier current) {
                try {
                    if(next.length>16384||!current.getAsBoolean())return false;
                    PurpleAccountFileSnapshot.Durable prior=PurpleAccountFileSnapshot.durable("read",path,null,null,16384,0);
                    if(!java.util.Arrays.equals(expected,prior.bytes)||!current.getAsBoolean())return false;
                    PurpleAccountFileSnapshot.Durable written=PurpleAccountFileSnapshot.durable("write",path,prior,next,16384,0);
                    PurpleAccountFileSnapshot.durable("barrier",path,written,null,16384,0);
                    return current.getAsBoolean()&&java.util.Arrays.equals(next,PurpleAccountFileSnapshot.durable("read",path,null,null,16384,0).bytes);
                }catch(Exception|LinkageError e){return false;}
            }
        };
    }
    static String authority(String loginAuthority) {
        if(loginAuthority==null||!loginAuthority.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("SetupLoginAuthority");
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(("purple-sync-chat-setup-v1:"+loginAuthority).getBytes(StandardCharsets.UTF_8));
            StringBuilder value=new StringBuilder();for(byte b:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();
        } catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SetupAuthorityUnavailable"); }
    }
}
