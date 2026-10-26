package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.tl.TL_update;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatSetupCases {
    static final class Memory implements PurpleSyncChatSetup.JournalIO {
        byte[] bytes;int writes;boolean fail;
        public byte[] read(){return bytes!=null?bytes.clone():null;}
        public boolean save(byte[] expected,byte[] next,BooleanSupplier current) {
            if(fail||!current.getAsBoolean()||!Arrays.equals(expected,bytes))return false;
            bytes=next.clone();++writes;return current.getAsBoolean();
        }
    }
    static final class Server {
        boolean created,marked,proof;
        Cache committed;
        long random;
        PurpleSyncChatCache.ReadersSnapshot baseline;
        boolean newReaders;
    }
    static PurpleSyncChatScreen.Selection selection(Peer chat,boolean create) {
        PurpleSyncChatScreenCases.Port p=new PurpleSyncChatScreenCases.Port();p.info=info(chat!=null?chat:SELF);
        PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(p);
        PurpleSyncChatScreen.Selection[] selected={null};
        if(create)control.create(true,value->selected[0]=value);
        else control.accept(new PurpleSyncChatScreen.Candidate(ORIGIN,chat,p.info),true,true,value->selected[0]=value);
        control.close();check(selected[0]!=null,"setup receives actual accepted screen selection");return selected[0];
    }
    static final class Env implements PurpleSyncChatSetup.Env {
        final Memory file;
        final Server server;
        final String key="setup-test:"+UUID.randomUUID();
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        final Set<Runnable> timers=new HashSet<>();
        final List<String> effects=new ArrayList<>();
        PurpleSyncChatOrigin origin=ORIGIN;
        String authority=PurpleSyncChatServiceCases.AUTH;
        PurpleSyncChatService.Binding binding=new PurpleSyncChatService.Binding(true,false,SELF,"binding-a");
        boolean current=true,foreground=true,unknownResolution,readersChange,badRights,unknownReaders,duplicate,discoverMark;
        String hold="",badExact="",inventoryMode="empty";Runnable pending;
        int creates,posts,commits,pins,mutes,archives,applies,reconciles,releases,inventories;
        PurpleSyncChatSetup.Error createError=PurpleSyncChatSetup.Error.None,postError=PurpleSyncChatSetup.Error.None;
        Cache otherWinner;
        PurpleSyncInventory actualInventory;
        long clock=1700000000;
        Object owner;
        Env(){this(new Memory(),new Server());}
        Env(Memory file,Server server){this.file=file;this.server=server;}
        public PurpleSyncChatOrigin origin(){return origin;}public String authority(){return authority;}
        public boolean current(){return current;}public boolean foreground(){return foreground;}
        public PurpleSyncChatService.Binding binding(){return binding;}
        public boolean acquire(Object owner){if(!PurpleSyncOperations.begin(key,owner,this::current))return false;this.owner=owner;return true;}
        public boolean owned(Object owner){return this.owner==owner&&PurpleSyncOperations.owned(key,owner);}
        public void release(Object owner){if(this.owner==owner){++releases;this.owner=null;PurpleSyncOperations.end(key,owner);}}
        public void dispatch(Runnable task){tasks.add(task);}
        public void delay(Runnable task,long millis){check(millis==30000,"setup has bounded per-step request");timers.add(task);}
        public void cancelDelay(Runnable task){timers.remove(task);}
        public PurpleSyncChatSetup.JournalIO journal(){return file;}
        public long randomId(){return 123456789L;}
        public long nowSeconds(){return clock;}
        <T> PurpleSyncChatSetup.Reply<T> ok(T value){return new PurpleSyncChatSetup.Reply<>(origin,PurpleSyncChatSetup.Error.None,value);}
        void deliver(String name,Runnable task){if(hold.equals(name))pending=task;else tasks.add(()->{task.run();if(duplicate)task.run();});}
        PurpleSyncChatClient.Info facts(Peer chat) {
            PurpleSyncChatClient.Info old=PurpleSyncChatRequestCases.info(chat);ChatFacts f=old.peer.facts;
            if(badRights&&chat.kind==PeerKind.Channel)f.soleAdmin=false;
            if(readersChange){f.otherMembers=1L;f.otherAdmins=0L;}
            PurpleSyncChatFacts.MappedPeer mapped=new PurpleSyncChatFacts.MappedPeer(origin,chat,old.peer.kind,old.peer.creator,old.peer.soleAdmin,f,old.members);
            return new PurpleSyncChatClient.Info(origin,chat,mapped,old.error,old.title,old.members,!unknownReaders);
        }
        PurpleSyncChatScreen.Frame frame() {
            Cache winner=otherWinner!=null?otherWinner:server.committed!=null?server.committed:discoverMark&&server.marked?new Cache(journalState().chat,7,50):null;
            if(winner==null&&!unknownResolution)return PurpleSyncChatScreenCases.frame(binding.bound,SELF,false,false);
            ResolveInput input=new ResolveInput(origin.userId);input.cache=winner;input.cacheRead=DirectRead.Valid;input.readerWarningAccepted=true;
            PurpleSyncChatClient.Info info=winner!=null?facts(winner.chat):null;
            input.cachedEligibility=info!=null?info.eligibility():unknownEligibility();
            PurpleSyncChatCache.Data data=winner!=null?new PurpleSyncChatCache.Data(winner,"synthetic",PurpleSyncChatCache.ReadersSnapshot.from(info),Collections.singleton("config"),new org.json.JSONObject(),false):null;
            return new PurpleSyncChatScreen.Frame(origin,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(winner!=null?PurpleSyncChatDiscovery.Phase.Use:PurpleSyncChatDiscovery.Phase.Paused,PurpleSyncChatCore.resolve(input),"",!unknownResolution,true,0)),binding,
                    new PurpleSyncChatCache.Loaded(true,null,data),info,Collections.emptyList());
        }
        public void resolve(Object owner,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatScreen.Frame>> done){deliver("resolve",()->done.accept(ok(frameWithBinding())));}
        PurpleSyncChatScreen.Frame frameWithBinding() {
            PurpleSyncChatScreen.Frame f=frame();
            return new PurpleSyncChatScreen.Frame(origin,f.status,binding,f.opening,f.current!=null?facts(f.current.chat):null,f.refused);
        }
        TLRPC.TL_updates createdUpdates(){
            TLRPC.TL_updates updates=new TLRPC.TL_updates();TLRPC.TL_channel channel=new TLRPC.TL_channel();channel.id=CHANNEL.id;channel.creator=true;channel.broadcast=true;channel.title="renamed by user";updates.chats.add(channel);return updates;
        }
        public void create(Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Created>> done) {
            ++creates;effects.add("create");check(step()==PurpleSyncChatSetup.Step.CreateStarted,"creation intent is durable before dispatch");
            deliver("create",()->{if(createError==PurpleSyncChatSetup.Error.None||createError==PurpleSyncChatSetup.Error.Unknown)server.created=true;
                done.accept(new PurpleSyncChatSetup.Reply<>(origin,createError,createError==PurpleSyncChatSetup.Error.None?PurpleSyncChatSetupRequests.created(createdUpdates()):null));});
        }
        public void reconcileCreate(String operation,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Created>> done){
            ++reconciles;deliver("reconcile-create",()->done.accept(server.created?ok(new PurpleSyncChatSetup.Created(CHANNEL,createdUpdates(),server.proof?operation:null)):new PurpleSyncChatSetup.Reply<>(origin,PurpleSyncChatSetup.Error.Unknown,null)));
        }
        public void applyUpdates(TLRPC.Updates updates,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){check(current&&foreground,"updates applied only for live origin foreground operation");++applies;effects.add("updates");deliver("apply",()->done.accept(ok(true)));}
        public void info(Peer chat,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatClient.Info>> done){deliver("info",()->done.accept(ok(facts(chat))));}
        public void inventory(Peer chat,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncInventory>> done) {
            ++inventories;effects.add("inventory");check(file.bytes==null||step().ordinal()<PurpleSyncChatSetup.Step.MarkStarted.ordinal(),"target inventory precedes mark intent/dispatch");
            deliver("inventory",()->{
                if(actualInventory!=null){done.accept(ok(actualInventory));return;}
                Peer target=inventoryMode.equals("peer")?USER:chat;
                PurpleSyncChatOrigin scope=inventoryMode.equals("origin")?new PurpleSyncChatOrigin(0,0,11,"other-origin"):inventoryMode.equals("account")?new PurpleSyncChatOrigin(0,0,99,"other-account"):origin;
                PurpleSyncInventory.Builder builder=new PurpleSyncInventory.Builder(inventoryMode.equals("account")?99:11,target,scope);
                if(inventoryMode.equals("present")||inventoryMode.equals("bytes"))builder.fetched(9,109,0,inventoryMode.equals("bytes")?new byte[]{1}:record());
                if(inventoryMode.equals("reader-change"))readersChange=true;
                done.accept(ok(builder.build(!inventoryMode.equals("partial"))));
            });
        }
        public void post(PurpleSyncChatClient.Info info,long random,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Posted>> done) {
            ++posts;effects.add("post");check(step()==PurpleSyncChatSetup.Step.MarkStarted&&random==journalState().random,"mark random id/intention durable before dispatch");
            if(server.random!=0)check(server.random==random,"retry retains random id");server.random=random;
            check(PurpleSyncChatSetupRequests.mark(info,inputPeer(info.chat),random).silent,"silent owned mark request");
            deliver("post",()->{if(postError==PurpleSyncChatSetup.Error.None||postError==PurpleSyncChatSetup.Error.Unknown)server.marked=true;
                done.accept(new PurpleSyncChatSetup.Reply<>(origin,postError,postError==PurpleSyncChatSetup.Error.None?new PurpleSyncChatSetup.Posted(new Cache(info.chat,7,50),new TLRPC.TL_updates(),random):null));});
        }
        public void reconcileMark(Peer chat,long random,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Posted>> done){deliver("reconcile-mark",()->done.accept(server.marked?ok(new PurpleSyncChatSetup.Posted(new Cache(chat,7,50),null,server.random)):new PurpleSyncChatSetup.Reply<>(origin,PurpleSyncChatSetup.Error.Unknown,null)));}
        public void exact(Cache cached,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.ReadBack>> done) {
            effects.add("read");deliver("exact",()->{
                TLRPC.TL_message message=mark(cached.chat,7,50);message.message=PurpleSyncChatSetupRequests.MARK;
                if(badExact.equals("text"))message.message="#purplechatsync v1";
                if(badExact.equals("peer"))message.peer_id=peer(USER);
                if(badExact.equals("date"))message.date=99;
                if(badExact.equals("forward"))message.fwd_from=new TLRPC.TL_messageFwdHeader();
                if(badExact.equals("media"))message.media=new TLRPC.TL_messageMediaDocument();
                if(badExact.equals("ownership"))message.via_bot_id=99;
                if(badExact.equals("id"))message.id=8;
                TLRPC.messages_Messages page=cached.chat.kind==PeerKind.Channel?new TLRPC.TL_messages_channelMessages():messages();page.count=1;page.messages.add(message);
                if(badExact.equals("empty")){page.messages.clear();TLRPC.TL_messageEmpty empty=new TLRPC.TL_messageEmpty();empty.id=7;page.messages.add(empty);}
                if(badExact.equals("zero"))page.messages.clear();
                if(badExact.equals("duplicate")){page.messages.add(message);page.count=2;}
                if(badExact.equals("inexact"))page.inexact=true;
                if(badExact.equals("flood-flag"))page.flags|=8;
                if(badExact.equals("partial-flag"))page.flags|=2;
                if(badExact.equals("flood-object"))page.search_flood=new TLRPC.SearchPostsFlood();
                if(badExact.equals("count"))page.count=0;
                TLRPC.TL_error error=null;if(badExact.equals("flood-error")){error=new TLRPC.TL_error();error.text="FLOOD_WAIT_60";}
                TLObject response=page;
                if(badExact.equals("notmodified"))response=new TLRPC.TL_messages_messagesNotModified();
                if(badExact.equals("slice")){TLRPC.TL_messages_messagesSlice slice=new TLRPC.TL_messages_messagesSlice();slice.count=1;slice.messages.add(message);response=slice;}
                done.accept(ok(new PurpleSyncChatSetup.ReadBack(response,error,facts(cached.chat))));
            });
        }
        public boolean commit(Cache mark,PurpleSyncChatClient.Info info,PurpleSyncChatCache.ReadersSnapshot baseline,boolean newReaders,BooleanSupplier current){
            check(current.getAsBoolean()&&step().ordinal()>=PurpleSyncChatSetup.Step.Commit.ordinal(),"exact read precedes guarded cache commit");++commits;effects.add("commit");server.committed=mark;server.baseline=baseline;server.newReaders=newReaders;return true;
        }
        public void pin(Cache mark,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){++pins;effects.add("pin");check(PurpleSyncChatSetupRequests.pin(mark,inputPeer(mark.chat)).silent,"silent pin request");deliver("pin",()->done.accept(ok(true)));}
        public void mute(Peer chat,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){++mutes;effects.add("mute");check(PurpleSyncChatSetupRequests.mute(chat,inputPeer(chat)).settings.mute_until==Integer.MAX_VALUE,"mute forever request");deliver("mute",()->done.accept(ok(true)));}
        public void archive(Peer chat,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){++archives;effects.add("archive");check(PurpleSyncChatSetupRequests.archive(chat,inputPeer(chat)).folder_peers.get(0).folder_id==1,"archive target folder");deliver("archive",()->done.accept(ok(true)));}
        public void cancel(){timers.clear();}
        PurpleSyncChatSetup.Journal journalState(){try{return PurpleSyncChatSetup.Journal.parse(file.bytes,origin,PurpleSyncChatSetup.authority(authority));}catch(Exception e){throw new AssertionError(e);}}
        PurpleSyncChatSetup.Step step(){return journalState().step;}
        void drain(){int cap=1000;while(!tasks.isEmpty()){check(--cap>0,"bounded setup work");tasks.remove().run();}}
    }
    static void run(File root)throws Exception {
        int before=checks;wireCases();postMarkReaders();
        Env env=new Env();PurpleSyncChatSetup setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
        check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&env.creates==1&&env.posts==1&&env.pins==1&&env.mutes==1&&env.archives==1,"complete injected creation sequence");
        check(env.effects.equals(Arrays.asList("create","updates","inventory","post","updates","read","commit","pin","mute","archive")),"apply Updates and target inventory before mark; exact mark before cache/pin/mute/archive");
        check(env.owner==null&&env.releases==1&&env.timers.isEmpty(),"complete releases resources before completion");
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
            env=new Env();setup=new PurpleSyncChatSetup(env,selection(chat,false),ignored->{});setup.start();env.drain();
            check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&env.creates==0&&env.posts==1,"existing fresh selection completes "+chat.token());
            check(env.pins+env.mutes+env.archives==0,"existing/Self selection does not mute/archive unrelated chats");
            check(env.inventories==1,"each selected typed target has an independent complete inventory "+chat.token());
        }
        for(String failure:new String[]{"unknown","bound","winner","rights","readers","foreign-journal","corrupt","journal-write"}) {
            env=new Env();
            if(failure.equals("unknown"))env.unknownResolution=true;
            if(failure.equals("bound"))env.binding=new PurpleSyncChatService.Binding(true,true,SELF,"binding-a");
            if(failure.equals("winner"))env.otherWinner=new Cache(USER,9,99);
            if(failure.equals("rights"))env.badRights=true;
            if(failure.equals("readers"))env.readersChange=true;
            if(failure.equals("corrupt"))env.file.bytes=new byte[]{(byte)255};
            if(failure.equals("foreign-journal")){PurpleSyncChatSetup.Journal journal=new PurpleSyncChatSetup.Journal();journal.create=true;journal.random=1;env.file.bytes=journal.json(ORIGIN,"wrong-login").toString().getBytes(StandardCharsets.UTF_8);}
            if(failure.equals("journal-write"))env.file.fail=true;
            setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
            check(setup.phase()==(failure.equals("winner")?PurpleSyncChatSetup.Phase.Adopted:PurpleSyncChatSetup.Phase.Held),"unsafe setup held "+failure);
            check(env.posts+env.pins+env.mutes+env.archives==0&&env.owner==null,"no data/decorations after refusal "+failure);
            if(!failure.equals("rights")&&!failure.equals("readers"))check(env.creates==0,"resolve/journal refusal precedes creation "+failure);
        }
        for(String bad:new String[]{"text","peer","id","date","forward","media","ownership","empty","zero","duplicate","inexact","flood-flag","partial-flag","flood-object","flood-error","count","slice","notmodified"}) {
            env=new Env();env.badExact=bad;setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
            check(setup.phase()==PurpleSyncChatSetup.Phase.Held&&env.commits+env.pins+env.mutes+env.archives==0,"strict own canonical mark readback "+bad);
        }
        targetInventory(root);restart();limits();lifetimes();notifications();realFile(root);
        System.out.println("SYNC_CHAT_SETUP_CHECKS="+(checks-before));
    }
    static void restart() {
        for(String stop:new String[]{"create","info","inventory","post","exact","pin","mute","archive"}) {
            Env first=new Env();first.hold=stop;PurpleSyncChatSetup setup=new PurpleSyncChatSetup(first,selection(null,true),ignored->{});setup.start();first.drain();
            check(first.pending!=null,"owned setup checkpoint "+stop);setup.close();int writes=first.file.writes;first.pending.run();first.drain();
            check(first.file.writes==writes&&first.owner==null,"late completion after cancellation cannot write "+stop);
            first.server.proof=stop.equals("create");
            if(stop.equals("create"))first.server.created=true;
            if(stop.equals("post"))first.server.marked=true;
            Env next=new Env(first.file,first.server);setup=new PurpleSyncChatSetup(next,null,ignored->{});setup.start();next.drain();
            check(setup.phase()==PurpleSyncChatSetup.Phase.Complete,"explicit restart resumes idempotent step "+stop);
            check(next.creates==0,"restart never creates duplicate channel "+stop);
            if(stop.equals("post")||stop.equals("exact")||stop.equals("pin")||stop.equals("mute")||stop.equals("archive"))check(next.posts==0,"restart reconciles/readbacks mark without duplicate posting "+stop);
            if(stop.equals("inventory"))check(next.inventories==1,"resume before unissued mark rereads target inventory");
            if(stop.equals("post"))check(next.inventories==0,"uncertain issued mark reconciles rather than repeating fresh-setup inventory/post");
        }
        Env env=new Env();env.createError=PurpleSyncChatSetup.Error.Unknown;PurpleSyncChatSetup setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
        check(setup.reason().equals("CreationUnknown")&&env.creates==1,"ambiguous create stays in started journal");setup.tryAgain();env.drain();
        check(env.creates==1&&setup.reason().equals("CreationOwnershipUnknown"),"title-only/uncorrelated creation never adopted or retried");
        env.server.proof=true;setup.tryAgain();env.drain();check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&env.creates==1,"unique operation-owned creation proof control");
        env=new Env();env.hold="info";setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();setup.close();
        Env moved=new Env(env.file,env.server);moved.otherWinner=new Cache(USER,9,99);setup=new PurpleSyncChatSetup(moved,null,ignored->{});setup.start();moved.drain();
        check(setup.reason().equals("WinnerChangedAfterSetupStarted")&&moved.posts==0&&moved.creates==0,"a new winner after own creation holds instead of silently following");
        for(boolean wrongRandom:new boolean[]{false,true}) {
            env=new Env();env.postError=PurpleSyncChatSetup.Error.Unknown;setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();setup.close();
            Env found=new Env(env.file,env.server);found.discoverMark=true;if(wrongRandom)++found.server.random;
            setup=new PurpleSyncChatSetup(found,null,ignored->{});setup.start();found.drain();
            check(setup.phase()==(wrongRandom?PurpleSyncChatSetup.Phase.Held:PurpleSyncChatSetup.Phase.Complete)&&found.posts==0,"discovery of same-target uncertain mark still requires saved random-id evidence "+wrongRandom);
            if(wrongRandom)check(found.commits==0,"caption/history match cannot invent random-id authority");
        }
    }
    static void limits() {
        for(boolean self:new boolean[]{false,true}) {
            Env env=new Env();env.createError=PurpleSyncChatSetup.Error.Limit;PurpleSyncChatSetup setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
            check(setup.phase()==PurpleSyncChatSetup.Phase.Limit&&env.owner==null&&env.step()==PurpleSyncChatSetup.Step.Ready,"definite limit releases before showing sheet/offer");
            env.createError=PurpleSyncChatSetup.Error.None;
            if(self)setup.useSavedMessages();else setup.tryAgain();env.drain();
            check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&env.posts==1,"limit explicit self/retry control "+self);
            check(env.creates==(self?1:2),"self avoids second create, retry resolves before creating");
        }
        Env env=new Env();env.postError=PurpleSyncChatSetup.Error.Flood;PurpleSyncChatSetup setup=new PurpleSyncChatSetup(env,selection(SELF,false),ignored->{});setup.start();env.drain();
        check(setup.phase()==PurpleSyncChatSetup.Phase.Held&&env.step()==PurpleSyncChatSetup.Step.PeerInfo,"definite flood before mark can retry safely");
        env.postError=PurpleSyncChatSetup.Error.None;setup.tryAgain();env.drain();check(setup.reason().equals("FloodWait")&&env.posts==1,"persisted flood wait refuses early retry");
        env.clock+=60;setup.tryAgain();env.drain();check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&env.posts==2,"known failure retries same persisted random id after deadline");
        env=new Env();env.createError=PurpleSyncChatSetup.Error.Limit;setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});
        for(int attempt=0;attempt<5;++attempt){if(attempt==0)setup.start();else setup.tryAgain();env.drain();}
        check(env.creates==3&&setup.reason().equals("CreationRetryLimit"),"creation has at most three explicit attempts per operation");
        setup.useSavedMessages();env.drain();check(setup.phase()==PurpleSyncChatSetup.Phase.Complete,"budget exhaustion retains explicit Self escape");
        env=new Env();env.createError=PurpleSyncChatSetup.Error.Flood;setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();setup.close();
        Env resumed=new Env(env.file,env.server);setup=new PurpleSyncChatSetup(resumed,null,ignored->{});setup.start();resumed.drain();
        check(setup.reason().equals("FloodWait")&&resumed.creates==0,"flood deadline survives restart");
        resumed.clock+=60;setup.tryAgain();resumed.drain();check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&resumed.creates==1,"explicit retry after durable flood deadline control");
        env=new Env();env.createError=PurpleSyncChatSetup.Error.Limit;setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();env.hold="info";setup.useSavedMessages();env.drain();setup.close();
        resumed=new Env(env.file,env.server);setup=new PurpleSyncChatSetup(resumed,null,ignored->{});setup.start();resumed.drain();
        check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&resumed.creates==0,"Self escape resumes after its pre-info journal checkpoint");
    }
    static void lifetimes() {
        for(String stop:new String[]{"resolve","create","apply","info","inventory","post","exact","pin","mute","archive"}) {
            for(String cause:new String[]{"background","logout","binding","close"}) {
                Env env=new Env();env.hold=stop;PurpleSyncChatSetup setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
                check(env.pending!=null,"lifetime checkpoint "+stop);
                int writes=env.file.writes,commits=env.commits,decorations=env.pins+env.mutes+env.archives;
                if(cause.equals("background"))env.foreground=false;
                if(cause.equals("logout"))env.current=false;
                if(cause.equals("binding"))env.binding=new PurpleSyncChatService.Binding(true,true,USER,"replacement");
                if(cause.equals("close"))setup.close();
                env.pending.run();env.drain();
                check(env.file.writes==writes&&env.commits==commits&&env.pins+env.mutes+env.archives==decorations,"no setup writes after "+cause+" during "+stop);
                check(env.owner==null,"lifetime loss releases lock "+cause+" "+stop);
            }
        }
        Env env=new Env();env.duplicate=true;PurpleSyncChatSetup setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
        check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&env.creates==1&&env.posts==1&&env.commits==1,"literal duplicate callbacks dispatch each effect once");
        env=new Env();env.hold="create";setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
        Runnable timeout=env.timers.iterator().next();timeout.run();check(setup.reason().equals("RequestTimeout")&&env.owner==null,"watchdog releases uncertain creation intent");env.pending.run();env.drain();check(env.posts==0,"late timeout response cannot advance setup");
        env=new Env();final PurpleSyncChatSetup[] closing={null};closing[0]=new PurpleSyncChatSetup(env,selection(null,true),phase->{if(phase==PurpleSyncChatSetup.Phase.Create)closing[0].close();});
        closing[0].start();env.drain();check(env.creates==0&&env.owner==null,"observer can synchronously destroy setup before dispatch");
        env=new Env();env.hold="create";setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
        env.origin=new PurpleSyncChatOrigin(0,0,11,"slot-reused-login");env.pending.run();env.drain();
        check(env.posts==0&&env.owner==null,"same-user login origin replacement fences stale creation callback");
    }
    static void postMarkReaders() throws Exception {
        for(boolean resume:new boolean[]{false,true})for(String mode:new String[]{"unchanged","addition","rights","unknown"}) {
            Env first=new Env();first.hold="exact";
            PurpleSyncChatSetup setup=new PurpleSyncChatSetup(first,selection(null,true),ignored->{});setup.start();first.drain();
            check(first.posts==1&&first.pending!=null,"review R1 pauses after acknowledged mark");
            String baseline=first.journalState().readers;
            Env active=first;
            if(resume){setup.close();active=new Env(first.file,first.server);setup=new PurpleSyncChatSetup(active,null,ignored->{});}
            active.readersChange=mode.equals("addition");active.badRights=mode.equals("rights");active.unknownReaders=mode.equals("unknown");
            if(resume)setup.start();else {active.hold="";active.pending.run();}
            active.drain();
            boolean held=mode.equals("rights")||mode.equals("unknown");
            check(setup.phase()==(held?PurpleSyncChatSetup.Phase.Held:PurpleSyncChatSetup.Phase.Complete),"post-mark known readers continue and unknown/rights hold "+resume+" "+mode);
            check(active.posts==(resume?0:1)&&active.creates==(resume?0:1),"post-mark readers never repeat create/send "+resume+" "+mode);
            check(active.journalState().readers.equals(baseline),"issued-mark original readers baseline retained "+resume+" "+mode);
            if(!held) {
                check(active.commits==1&&active.server.baseline.otherMembers==0L,"original baseline passed to cache commit");
                check(setup.newReaders()==mode.equals("addition")&&active.server.newReaders==mode.equals("addition"),"sticky warning reaches journal and cache "+resume+" "+mode);
                if(mode.equals("addition")) {
                    setup.close();Env restarted=new Env(active.file,active.server);
                    setup=new PurpleSyncChatSetup(restarted,null,ignored->{});setup.start();restarted.drain();
                    check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&setup.newReaders()&&restarted.journalState().readers.equals(baseline),"departure/restart cannot erase persisted warning or original snapshot");
                }
            } else check(active.commits==0,"unknown/rights loss never commits consent");
            setup.close();
        }
        for(String stop:new String[]{"pin","mute","archive"}) {
            Env first=new Env();first.hold=stop;
            PurpleSyncChatSetup setup=new PurpleSyncChatSetup(first,selection(null,true),ignored->{});setup.start();first.drain();setup.close();
            Env second=new Env(first.file,first.server);second.readersChange=true;second.hold=stop;
            setup=new PurpleSyncChatSetup(second,null,ignored->{});setup.start();second.drain();
            check(second.journalState().newReaders&&second.server.newReaders&&second.server.baseline.otherMembers==0L,"decoration restart persists addition in journal/cache before dispatch "+stop);
            setup.close();Env third=new Env(second.file,second.server);
            setup=new PurpleSyncChatSetup(third,null,ignored->{});setup.start();third.drain();
            check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&setup.newReaders()&&third.posts+third.creates==0,"sticky warning survives decoration restart and departure "+stop);setup.close();
        }
    }
    static byte[] record() {
        PurpleAccountSyncCore.Result record=PurpleAccountSyncCore.buildConfigRecord("version=2\n".getBytes(StandardCharsets.UTF_8),new byte[0][],
                PurpleAccountSyncCore.formatSpaceId(new byte[16]).id,PurpleAccountSyncCore.formatInstallId(new byte[16]).id,"synthetic","Android","synthetic",1,1700000000);
        check(record.isValid(),"target inventory fixture uses actual core record bytes");return record.record;
    }
    static void targetInventory(File root)throws Exception {
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL})for(String mode:new String[]{"present","partial","origin","account","bytes"}) {
            Env env=new Env();env.inventoryMode=mode;PurpleSyncChatSetup setup=new PurpleSyncChatSetup(env,selection(chat,false),ignored->{});setup.start();env.drain();
            check(setup.reason().equals("TargetInventoryNotEmptyOrUnknown")&&env.posts+env.commits==0,"independent target inventory refuses "+chat.token()+" "+mode);
        }
        Env env=new Env();env.inventoryMode="peer";PurpleSyncChatSetup setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
        check(env.posts==0&&setup.phase()==PurpleSyncChatSetup.Phase.Held,"wrong typed target cannot prove Empty");
        env=new Env();env.inventoryMode="reader-change";setup=new PurpleSyncChatSetup(env,selection(null,true),ignored->{});setup.start();env.drain();
        check(env.posts==0&&setup.reason().equals("ReadersOrRightsChanged"),"full info is refreshed after potentially long inventory");setup.close();
        Env resumed=new Env(env.file,env.server);resumed.readersChange=true;
        PurpleSyncChatScreenCases.Port port=new PurpleSyncChatScreenCases.Port();port.info=resumed.facts(CHANNEL);
        PurpleSyncChatScreen.Control control=new PurpleSyncChatScreen.Control(port);PurpleSyncChatScreen.Selection[] reviewed={null};
        control.accept(new PurpleSyncChatScreen.Candidate(ORIGIN,CHANNEL,port.info),true,true,value->reviewed[0]=value);control.close();
        setup=new PurpleSyncChatSetup(resumed,reviewed[0],ignored->{});setup.start();resumed.drain();
        check(setup.phase()==PurpleSyncChatSetup.Phase.Complete&&resumed.creates==0,"explicit same-target fresh reader consent can resume pre-mark journal");
        File payload=new File(root,"unmarked-target-record.json");Files.write(payload.toPath(),record());
        for(Peer chat:new Peer[]{USER,GROUP,CHANNEL}) {
            PurpleSyncChatIOCases.Client nativeClient=new PurpleSyncChatIOCases.Client(chat,payload);
            TLRPC.TL_message row=PurpleSyncChatIOCases.doc(chat,9,payload.length());
            nativeClient.pages.add(PurpleSyncChatIOCases.page(chat,1,row));nativeClient.pages.add(PurpleSyncChatIOCases.page(chat,1));
            nativeClient.exact.add(messages(row));nativeClient.exact.add(messages(row));
            PurpleSyncInventory records=nativeClient.inventory();
            check(PurpleSyncChat.recordPresence(records,11,chat)==RecordPresence.Present,"genuine typed scanner/reader finds unmarked target record "+chat.token());
            env=new Env();env.actualInventory=records;setup=new PurpleSyncChatSetup(env,selection(chat,false),ignored->{});setup.start();env.drain();
            check(setup.reason().equals("TargetInventoryNotEmptyOrUnknown")&&env.posts==0&&env.commits==0,"complete Self Unset cannot authorize marking another populated chat "+chat.token());
        }
    }
    static void notifications() {
        final PurpleSyncChatOrigin inactive=new PurpleSyncChatOrigin(3,0,11,"inactive-origin");
        final ArrayList<Integer> accounts=new ArrayList<>();final boolean[] active={true};
        PurpleSyncChatSetupNative.Notifications port=new PurpleSyncChatSetupNative.Notifications() {
            public boolean current(PurpleSyncChatOrigin origin){return active[0]&&inactive.equals(origin);}
            public boolean preferences(int account,long did){accounts.add(account);check(did==-CHANNEL.id,"origin typed channel notification dialog");return true;}
            public void remove(int account,long did){accounts.add(account);}public void flags(int account,long did){accounts.add(account);}
            public void dialog(int account,long did){accounts.add(account);}public void changed(int account){accounts.add(account);}
        };
        check(PurpleSyncChatSetupNative.muteLocal(inactive,CHANNEL,port)&&accounts.equals(Arrays.asList(3,3,3,3,3)),"inactive origin owns preferences, notifications, flags, dialog and event");
        accounts.clear();active[0]=false;check(!PurpleSyncChatSetupNative.muteLocal(inactive,CHANNEL,port)&&accounts.isEmpty(),"retired origin cannot write any notification state");
    }
    static void realFile(File root)throws Exception {
        File path=new File(root,"purple/sync-chat/setup-case.json");PurpleSyncChatSetup.JournalIO io=PurpleSyncChatSetup.file(path);
        PurpleSyncChatSetup.Journal journal=new PurpleSyncChatSetup.Journal();journal.create=true;journal.random=123;journal.binding="synthetic";
        byte[] bytes=journal.json(ORIGIN,PurpleSyncChatSetup.authority(PurpleSyncChatServiceCases.AUTH)).toString().getBytes(StandardCharsets.UTF_8);
        check(io.save(null,bytes,()->true),"private setup journal atomic create");
        check(PurpleSyncChatSetup.Journal.parse(io.read(),new PurpleSyncChatOrigin(0,0,11,"new-process-ram-origin"),PurpleSyncChatSetup.authority(PurpleSyncChatServiceCases.AUTH)).step==PurpleSyncChatSetup.Step.Ready,"persisted login authority admits same-login restart, separate RAM callback fence");
        check(!io.save(bytes,bytes,()->false),"background deferred journal write refused");
        Files.write(path.toPath(),"changed".getBytes(StandardCharsets.UTF_8));check(!io.save(bytes,bytes,()->true),"stale setup CAS preserves external writer");
        boolean refused=false;try{PurpleSyncChatSetup.Journal.parse(bytes,ORIGIN,"different-login");}catch(Exception error){refused=true;}check(refused,"login nonce authority cannot be reused after relogin");
    }
    static void wireCases() {
        TLRPC.TL_channels_createChannel create=PurpleSyncChatSetupRequests.create();SerializedData bytes=wire(create);
        check(bytes.readInt32(true)==TLRPC.TL_channels_createChannel.constructor&&bytes.readInt32(true)==1,"private broadcast only, no geo/TTL/forum/import flags");
        check(bytes.readString(true).equals(PurpleSyncChatSetupRequests.TITLE)&&bytes.readString(true).equals(PurpleSyncChatSetupRequests.ABOUT),"canonical private channel shape");
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
            TLRPC.TL_messages_sendMessage mark=PurpleSyncChatSetupRequests.mark(info(chat),inputPeer(chat),123);
            check(mark!=null&&mark.silent&&mark.random_id==123&&mark.message.equals(PurpleSyncChatSetupRequests.MARK),"typed silent mark builder "+chat.token());wire(mark);
            check(PurpleSyncChatSetupRequests.mark(info(chat),inputPeer(Peer.of(PeerKind.User,99)),123)==null,"wrong peer mark refused");
        }
        Cache at=new Cache(CHANNEL,7,50);TLRPC.TL_messages_updatePinnedMessage pin=PurpleSyncChatSetupRequests.pin(at,inputPeer(CHANNEL));bytes=wire(pin);
        check(bytes.readInt32(true)==TLRPC.TL_messages_updatePinnedMessage.constructor&&bytes.readInt32(true)==1,"pin silent flag on actual TL bytes");
        wire(PurpleSyncChatSetupRequests.mute(CHANNEL,inputPeer(CHANNEL)));wire(PurpleSyncChatSetupRequests.archive(CHANNEL,inputPeer(CHANNEL)));
        Env env=new Env();TLRPC.TL_updates response=env.createdUpdates();check(PurpleSyncChatSetupRequests.created(response)!=null,"actual creation response control");
        response.chats.add(response.chats.get(0));check(PurpleSyncChatSetupRequests.created(response)==null,"ambiguous creation response refused");
        check(PurpleSyncChatSetupRequests.created(new TLRPC.TL_updatesTooLong())==null,"unsupported creation reply Unknown");
        TLRPC.TL_error flood=new TLRPC.TL_error();flood.text="FLOOD_WAIT_120";
        check(PurpleSyncChatSetupRequests.error(flood)==PurpleSyncChatSetup.Error.Flood&&PurpleSyncChatSetupRequests.floodSeconds(flood)==120,"real flood duration feeds durable retry deadline");
        flood.text="FLOOD_WAIT_bad";check(PurpleSyncChatSetupRequests.error(flood)==PurpleSyncChatSetup.Error.Unknown,"malformed flood duration stays Unknown");
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}) {
            TLRPC.TL_updates updates=new TLRPC.TL_updates();TL_update.TL_updateMessageID mapping=new TL_update.TL_updateMessageID();mapping.id=7;mapping.random_id=123;updates.updates.add(mapping);
            TLRPC.TL_message message=mark(chat,7,50);message.message=PurpleSyncChatSetupRequests.MARK;
            if(chat.kind==PeerKind.Channel){TL_update.TL_updateNewChannelMessage fresh=new TL_update.TL_updateNewChannelMessage();fresh.message=message;updates.updates.add(fresh);}
            else {TL_update.TL_updateNewMessage fresh=new TL_update.TL_updateNewMessage();fresh.message=message;updates.updates.add(fresh);}
            check(PurpleSyncChatSetupRequests.posted(info(chat),updates,123)!=null,"actual typed owned mark receipt "+chat.token());
            check(PurpleSyncChatSetupRequests.posted(info(chat),updates,124)==null,"wrong random-id is not mark evidence");
            message.message="#purplechatsync v1";check(PurpleSyncChatSetupRequests.posted(info(chat),updates,123)==null,"noncanonical text cannot confirm this posted mark");
        }
    }
}
