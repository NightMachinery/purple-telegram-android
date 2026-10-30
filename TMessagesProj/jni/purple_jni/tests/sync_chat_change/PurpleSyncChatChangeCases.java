package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.*;
import org.json.JSONObject;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatChangeCases {
    static int checks,serial;
    static void must(boolean value,String message){++checks;if(!value)throw new AssertionError(message);}
    static PurpleSyncChatClient.Info broadcast(Peer chat){
        ChatFacts f=new ChatFacts();f.kind=Kind.Broadcast;f.creator=true;f.soleAdmin=true;f.admin=true;f.canSendFiles=true;f.otherMembers=0L;f.otherAdmins=0L;
        Set<Long> members=Collections.singleton(777L);
        return new PurpleSyncChatClient.Info(ORIGIN,chat,new PurpleSyncChatFacts.MappedPeer(ORIGIN,chat,Kind.Broadcast,Truth.True,Truth.True,f,members),PurpleSyncChatClient.Error.None,"created channel",members,true);
    }
    static final class Screen implements PurpleSyncChatScreen.Port {
        PurpleSyncChatScreen.Frame frame;
        PurpleSyncChatClient.Info target;
        PurpleSyncChatOrigin origin=ORIGIN;
        String authority=AUTH;
        boolean current=true,held,defer,throwCancel;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        int remembered;
        public PurpleSyncChatOrigin origin(){return origin;}public String authority(){return authority;}public boolean current(){return current;}
        public boolean begin(Object owner){if(held)return false;held=true;return true;}
        public void end(Object owner){held=false;}public void cancel(Object owner){if(throwCancel)throw new IllegalStateException("cancel");}
        void deliver(Runnable r){if(defer)tasks.add(r);else r.run();}
        public void resolve(Object owner,Consumer<PurpleSyncChatScreen.Frame> done){deliver(()->done.accept(frame));}
        public void inspect(Peer chat,Consumer<PurpleSyncChatClient.Info> done){deliver(()->done.accept(target));}
        public void remember(PurpleSyncChatScreen.Frame frame,PurpleSyncChatScreen.Candidate target,Consumer<Boolean> done){++remembered;done.accept(false);}
        void drain(){while(!tasks.isEmpty())tasks.remove().run();}
    }
    static PurpleSyncChatScreen.Frame screen(PurpleSyncChatMoveExecutorCases.Frame carry,Peer source)throws Exception{
        PurpleSyncChatClient.Info old=info(source,0,false);Cache location=new Cache(source,1,1001);
        ResolveInput r=new ResolveInput(777);r.settingsBound=true;r.settingsChat=source;r.cache=location;r.cacheRead=DirectRead.Valid;r.cachedEligibility=old.eligibility();r.settingsHint=SettingsHint.Found;
        r.marks=Collections.singletonList(new Mark(location,MarkStatus.Supported,old.eligibility(),RecordPresence.Present,true,true));
        PurpleSyncChatCache.Data data=new PurpleSyncChatCache.Data(location,"old",PurpleSyncChatCache.ReadersSnapshot.from(old),Collections.singleton("config"),new JSONObject(),false);
        byte[] state=Files.readAllBytes(new File(carry.files.root,"state.json").toPath());
        return new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Use,resolve(r),"",true,false,0)),
                new PurpleSyncChatService.Binding(true,true,source,"binding",hash(state)),new PurpleSyncChatCache.Loaded(true,PurpleSyncChatCache.encode(ORIGIN,AUTH,data),data),old,Collections.emptyList());
    }
    static PurpleSyncChatScreen.ChangeSelection selection(Screen p,boolean create,boolean cleanup){
        PurpleSyncChatScreen.Control c=new PurpleSyncChatScreen.Control(p);PurpleSyncChatScreen.ChangeOffer[] offer={null};
        c.offerChange(create?null:p.target.chat,create,x->offer[0]=x);must(offer[0]!=null,"offer admitted");
        PurpleSyncChatScreen.ChangeSelection[] accepted={null};c.acceptChange(offer[0],true,true,cleanup,x->accepted[0]=x);
        must(accepted[0]!=null,"selection freshly accepted");return accepted[0];
    }
    static Screen initial(PurpleSyncChatMoveExecutorCases.Frame carry)throws Exception{Screen s=new Screen();s.frame=screen(carry,O);s.target=broadcast(carry.target);return s;}
    static final class Provision implements PurpleSyncChatSetup.Env {
        final Flow flow;
        final File file;
        final PurpleSyncChatSetup.JournalIO journal;
        PurpleSyncChatSetup setup;
        Object owner;
        boolean landed,matched=true,duplicate,throwCancel;
        int creates,reconciles,applies,infos,inventory,writes;
        String hold="",error="";
        Runnable pending;
        Provision(Flow f,File file){flow=f;this.file=file;journal=PurpleSyncChatSetup.provisionFile(file);}
        public PurpleSyncChatOrigin origin(){return flow.origin();}public String authority(){return flow.authority();}
        public boolean current(){return flow.current();}public boolean foreground(){return flow.foreground();}
        public PurpleSyncChatService.Binding binding(){return flow.screen.frame.binding;}
        public boolean acquire(Object next){if(owner!=null)return false;owner=next;return true;}public boolean owned(Object next){return owner==next;}
        public void release(Object next){if(owner==next)owner=null;}
        public void dispatch(Runnable r){flow.worker(r);}public void delay(Runnable r,long millis){}public void cancelDelay(Runnable r){}
        public PurpleSyncChatSetup.JournalIO journal(){return journal;}public long randomId(){return 12345678L;}public long nowSeconds(){return 1700000000L;}
        <T> PurpleSyncChatSetup.Reply<T> ok(T value){return new PurpleSyncChatSetup.Reply<>(origin(),PurpleSyncChatSetup.Error.None,value);}
        void deliver(String name,Runnable r){if(hold.equals(name))pending=r;else flow.worker(()->{r.run();if(duplicate)r.run();});}
        public void resolve(Object owner,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatScreen.Frame>> done){deliver("resolve",()->done.accept(ok(flow.screen.frame)));}
        TLRPC.TL_updates updates(){TLRPC.TL_updates u=new TLRPC.TL_updates();TLRPC.TL_channel c=new TLRPC.TL_channel();c.id=flow.carry.target.id;c.creator=true;c.broadcast=true;c.title="renamed";u.chats.add(c);return u;}
        public void create(Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Created>> done){
            try{PurpleSyncChatSetup.Journal j=PurpleSyncChatSetup.Journal.parse(journal.read(),origin(),PurpleSyncChatSetup.authority(authority()));must(j.step==PurpleSyncChatSetup.Step.CreateStarted&&j.purpose.equals("MoveProvision"),"durable child intent before create");}catch(Exception e){throw new RuntimeException(e);}
            ++creates;landed=true;flow.events.add("create");
            deliver("create",()->done.accept(error.equals("unknown")?new PurpleSyncChatSetup.Reply<>(origin(),PurpleSyncChatSetup.Error.Unknown,null):ok(PurpleSyncChatSetupRequests.created(updates()))));
        }
        public void reconcileCreate(String operation,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Created>> done){++reconciles;flow.events.add("reconcile");deliver("reconcile",()->done.accept(ok(landed?new PurpleSyncChatSetup.Created(flow.carry.target,updates(),matched?operation:"wrong"):null)));}
        public void applyUpdates(TLRPC.Updates updates,Consumer<PurpleSyncChatSetup.Reply<Boolean>> done){++applies;flow.events.add("updates");deliver("updates",()->done.accept(ok(true)));}
        public void info(Peer chat,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatClient.Info>> done){++infos;flow.events.add("info");deliver("info",()->done.accept(ok(flow.screen.target)));}
        public void inventory(Peer chat,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncInventory>> done){++inventory;flow.events.add("inventory");deliver("inventory",()->done.accept(ok(new PurpleSyncInventory.Builder(777,chat,origin()).build(true))));}
        public void post(PurpleSyncChatClient.Info i,long r,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Posted>> d){throw new AssertionError("provision posted mark");}
        public void reconcileMark(Peer p,long r,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.Posted>> d){throw new AssertionError("provision read mark");}
        public void exact(Cache c,Consumer<PurpleSyncChatSetup.Reply<PurpleSyncChatSetup.ReadBack>> d){throw new AssertionError("provision exact mark");}
        public boolean commit(Cache c,PurpleSyncChatClient.Info i,PurpleSyncChatCache.ReadersSnapshot b,boolean w,BooleanSupplier live){throw new AssertionError("provision cache commit");}
        public void pin(Cache c,Consumer<PurpleSyncChatSetup.Reply<Boolean>> d){throw new AssertionError("provision pin");}
        public void mute(Peer c,Consumer<PurpleSyncChatSetup.Reply<Boolean>> d){throw new AssertionError("provision mute");}
        public void archive(Peer c,Consumer<PurpleSyncChatSetup.Reply<Boolean>> d){throw new AssertionError("provision archive");}
        public void cancel(){if(throwCancel)throw new IllegalStateException("cancel");}
    }
    static final class Flow implements PurpleSyncChatChange.Port {
        final PurpleSyncChatMoveExecutorCases.Frame carry;
        final Screen screen;
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        final List<String> events=new ArrayList<>();
        final List<PurpleSyncChatChange.State> states=new ArrayList<>();
        final Provision provision;
        boolean live=true,foreground=true,closeObserver,throwObserver;
        PurpleSyncChatChange flow;
        int carries,decorations,cancels,resolves,resolveAt;
        Runnable resolveHook,afterInspect,beforeBegin;
        String failDecoration="",waitDecoration="",closeDecoration="";
        Consumer<Boolean> pendingDecoration;
        Flow()throws Exception{this(Peer.parse("channel:20",777));}
        Flow(Peer target)throws Exception{carry=new PurpleSyncChatMoveExecutorCases.Frame(false,false,"Switch",target);screen=initial(carry);if(target.kind==PeerKind.Self)screen.target=info(target,0,false);PurpleModernSyncCases.write(carry.files.cache,screen.frame.opening.bytes);carry.targetInfo=screen.target;provision=new Provision(this,new File(carry.files.root,"sync-chat-move-create.json"));}
        public PurpleSyncChatOrigin origin(){return screen.origin;}public String authority(){return screen.authority;}
        public boolean current(){return live;}public boolean foreground(){return foreground;}
        public void worker(Runnable r){tasks.add(r);}public void resolve(Consumer<PurpleSyncChatScreen.Frame> d){worker(()->{if(++resolves==resolveAt&&resolveHook!=null)resolveHook.run();d.accept(screen.frame);});}
        public void inspect(Peer target,Consumer<PurpleSyncChatClient.Info> d){worker(()->{if(afterInspect!=null)afterInspect.run();d.accept(screen.target);});}
        public PurpleSyncChatSetup.Env provision(){return provision;}
        public JSONObject begin(PurpleSyncChatScreen.ChangeSelection s,PurpleSyncChatClient.Info t)throws Exception{if(beforeBegin!=null)beforeBegin.run();return carry.begin();}
        public PurpleSyncChatMove.Port carry(){++carries;events.add("carry");return carry;}
        public void decorate(String effect,PurpleSyncChatMove.Committed proof,Consumer<Boolean> d){
            try{JSONObject j=carry.diskJournal();must(j.getBoolean("destination_verified")&&j.getBoolean("mark_verified")&&j.getBoolean("state_committed")&&j.getBoolean("cache_completed"),"decorations require actual safe Carry commit");
                must(proof.operation.equals(j.getString("operation")),"sealed proof exact operation");if(effect.equals("Pin"))must(PurpleSyncChatSetupRequests.pin(proof.mark,input(proof.mark.chat)).silent,"pin is silent");
            }catch(Exception e){throw new RuntimeException(e);}
            ++decorations;events.add(effect);if(effect.equals(waitDecoration))pendingDecoration=d;else worker(()->d.accept(!effect.equals(failDecoration)));
        }
        public void cancel(){++cancels;}public void status(PurpleSyncChatChange.State s){states.add(s);if(closeObserver&&s.phase.equals("Carrying"))flow.close();if(throwObserver)throw new IllegalStateException("observer");if(s.phase.equals(closeDecoration))flow.close();}
        void start(boolean create,boolean cleanup){flow=new PurpleSyncChatChange(this,selection(screen,create,cleanup));flow.start();drain();}
        void drain(){int cap=20000;while(!tasks.isEmpty()||!carry.tasks.isEmpty()){must(--cap>0,"bounded change queue");if(!tasks.isEmpty())tasks.remove().run();else carry.tasks.remove().run();}}
        void close(){if(flow!=null)flow.close();drain();screen.current=false;}
        String reason(){return states.isEmpty()?"":states.get(states.size()-1).reason;}
    }
    static void ui()throws Exception{
        for(String change:new String[]{"none","state","cache","winner","readers","rights","origin","login","foreground","cloud","consent","cancel"}){
            Flow f=new Flow();Screen p=f.screen;PurpleSyncChatScreen.Control c=new PurpleSyncChatScreen.Control(p);PurpleSyncChatScreen.ChangeOffer[] offer={null};
            android.os.Looper.mainThread=Thread.currentThread();
            c.offerChange(p.target.chat,false,x->offer[0]=x);must(offer[0]!=null&&offer[0].cleanupDefault,"eligible non-Self cleanup checked");
            if(change.equals("state"))p.frame=new PurpleSyncChatScreen.Frame(ORIGIN,p.frame.status,new PurpleSyncChatService.Binding(true,true,O,"binding","changed"),p.frame.opening,p.frame.current.info,p.frame.refused);
            if(change.equals("cache"))p.frame=new PurpleSyncChatScreen.Frame(ORIGIN,p.frame.status,p.frame.binding,new PurpleSyncChatCache.Loaded(true,new byte[]{4},p.frame.opening.data),p.frame.current.info,p.frame.refused);
            if(change.equals("winner")){ResolveInput r=new ResolveInput(777);r.settingsBound=true;r.settingsChat=O;r.marks=Collections.singletonList(new Mark(new Cache(p.target.chat,8,2000),MarkStatus.Supported,p.target.eligibility(),RecordPresence.Present,true,true));p.frame=new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Use,resolve(r),"",true,false,0)),p.frame.binding,p.frame.opening,p.frame.current.info,p.frame.refused);}
            if(change.equals("readers"))p.target=info(p.target.chat,1,false);
            if(change.equals("rights"))p.target=info(p.target.chat,0,true);
            if(change.equals("origin"))p.origin=new PurpleSyncChatOrigin(0,0,777,"newlogin");
            if(change.equals("login"))p.authority="b".repeat(64);
            if(change.equals("foreground"))p.current=false;
            if(change.equals("cancel"))c.close();
            int[] accepted={0};c.acceptChange(offer[0],!change.equals("cloud"),!change.equals("consent"),true,x->{if(x!=null)++accepted[0];});
            must(accepted[0]==(change.equals("none")||change.equals("consent")?1:0),"capture/fresh acceptance "+change);
            must(p.remembered==0&&!p.held,"Change writes no ordinary consent "+change);c.close();android.os.Looper.mainThread=null;f.close();
        }
        Flow f=new Flow();f.screen.frame=screen(f.carry,Peer.self());PurpleSyncChatScreen.Control c=new PurpleSyncChatScreen.Control(f.screen);PurpleSyncChatScreen.ChangeOffer[] offer={null};
        c.offerChange(f.screen.target.chat,false,x->offer[0]=x);must(offer[0]!=null&&!offer[0].cleanupDefault&&offer[0].cleanupAvailable,"Saved Messages cleanup unticked");
        Map<String,String> strings=new HashMap<>();org.w3c.dom.NodeList nodes=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(System.getProperty("purple.test.strings"))).getElementsByTagName("string");
        for(int i=0;i<nodes.getLength();++i){org.w3c.dom.Element e=(org.w3c.dom.Element)nodes.item(i);strings.put(e.getAttribute("name"),e.getTextContent());}
        String disclosure=PurpleSyncChatScreen.changeDisclosure(offer[0],(key,args)->String.format(Locale.ROOT,strings.get("PurpleSyncChat"+key),args));
        must(disclosure.contains("Older Purple builds read settings from Saved Messages. Deleting them makes those devices pause or offer to start over."),"exact Saved Messages warning");
        must(disclosure.contains("state.toml stays on this device.")&&disclosure.contains("not end-to-end encrypted"),"r3 cloud disclosure");c.close();f.close();
        for(boolean create:new boolean[]{false,true}){f=new Flow();PurpleSyncChatScreen.ChangeSelection selected=selection(f.screen,create,false);
            PurpleSyncChatChange.State held=PurpleSyncChatChange.production(selected,ORIGIN,AUTH,()->true);
            must(!PurpleSyncChatChange.ACTIVATED&&held.reason.equals("QualificationHeld")&&!held.committed&&f.carries==0&&f.provision.creates==0&&!f.provision.file.exists(),"constant OFF creates no intent/transport/executor "+create);f.close();}
    }
    static void provisioning()throws Exception{
        Flow f=new Flow();PurpleSyncChatScreen.ChangeSelection selected=selection(f.screen,true,false);List<PurpleSyncChatSetup.Phase> phases=new ArrayList<>();
        PurpleSyncChatSetup setup=PurpleSyncChatSetup.provision(f.provision,selected,phases::add);setup.start();f.drain();
        must(phases.contains(PurpleSyncChatSetup.Phase.Provisioned)&&f.provision.creates==1&&f.provision.applies==1&&f.provision.infos==2&&f.provision.inventory==1,"provisioning ordering: "+phases+" reason="+setup.reason()+" creates="+f.provision.creates+" applies="+f.provision.applies+" infos="+f.provision.infos+" inventory="+f.provision.inventory);
        must(!phases.contains(PurpleSyncChatSetup.Phase.Complete)&&f.carries==0&&f.decorations==0,"Provisioned is not committed");
        byte[] saved=f.provision.journal.read();setup.close();
        PurpleSyncChatSetup again=PurpleSyncChatSetup.provision(f.provision,selected,phases::add);again.start();f.drain();must(f.provision.creates==1,"reopening Provisioned never creates twice");again.close();
        PurpleSyncChatSetup wrong=new PurpleSyncChatSetup(f.provision,null,phases::add);wrong.start();f.drain();must(wrong.reason().equals("OpeningOrPurposeChanged"),"fresh setup refuses provision child purpose");wrong.close();f.close();
        for(boolean proof:new boolean[]{false,true}){
            f=new Flow();selected=selection(f.screen,true,false);f.provision.error="unknown";
            setup=PurpleSyncChatSetup.provision(f.provision,selected,x->{});setup.start();f.drain();must(f.provision.creates==1&&setup.phase()==PurpleSyncChatSetup.Phase.Held,"uncertain creation held");setup.close();
            f.provision.error="";f.provision.matched=proof;again=PurpleSyncChatSetup.provision(f.provision,selected,x->{});again.start();f.drain();
            must(f.provision.creates==1&&f.provision.reconciles==1,"uncertain create reconciles, never blindly creates again");
            must((again.phase()==PurpleSyncChatSetup.Phase.Provisioned)==proof,"matched operation required for uncertain creation "+proof);again.close();f.close();
        }
    }
    static void flows()throws Exception{
        Flow saved=new Flow(Peer.self());saved.start(false,false);must(saved.carries==1&&saved.states.stream().anyMatch(v->v.committed)&&saved.decorations==0,"existing Saved Messages moves with no new-channel decorations");saved.close();
        for(boolean create:new boolean[]{false,true}){Flow f=new Flow();f.start(create,false);must(f.carries==1,"Carry starts after proven selection");
            must(f.states.stream().anyMatch(s->s.committed),"actual Carry commit reaches UI "+f.reason());
            must(f.decorations==(create?3:0),"only new channel decorated after safe commit");
            if(create)must(f.events.indexOf("updates")<f.events.indexOf("carry")&&f.events.indexOf("inventory")<f.events.indexOf("carry")&&f.events.indexOf("Pin")<f.events.indexOf("Mute")&&f.events.indexOf("Mute")<f.events.indexOf("Archive"),"provision/Carry/decorations order");f.close();}
        for(String boundary:new String[]{"observer","throw","background","account","state"}){Flow f=new Flow();
            if(boundary.equals("observer"))f.closeObserver=true;if(boundary.equals("throw"))f.throwObserver=true;
            PurpleSyncChatScreen.ChangeSelection selected=selection(f.screen,true,false);f.flow=new PurpleSyncChatChange(f,selected);
            if(boundary.equals("background"))f.foreground=false;if(boundary.equals("account"))f.screen.origin=new PurpleSyncChatOrigin(0,0,888,"other");
            if(boundary.equals("state"))f.screen.frame=new PurpleSyncChatScreen.Frame(ORIGIN,f.screen.frame.status,new PurpleSyncChatService.Binding(true,true,O,"binding","changed"),f.screen.frame.opening,f.screen.frame.current.info,f.screen.frame.refused);
            f.flow.start();f.drain();must(f.carry.effects==0&&f.decorations==0,"revocation before actual write "+boundary);f.close();}
        Flow f=new Flow();f.start(false,true);
        JSONObject wrapper=new JSONObject(new String(Files.readAllBytes(new File(f.carry.files.root,"carry.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        must(wrapper.getBoolean("cleanup_settings"),"settings checkbox preserved in carry wrapper");f.close();
    }
    static void retryDecorations(Flow f)throws Exception{
        PurpleSyncChatChange.class.getDeclaredMethod("retryDecorations").invoke(f.flow);f.drain();
    }
    static void decorationFailures()throws Exception{
        String[] effects={"Pin","Mute","Archive"};
        for(int index=0;index<effects.length;++index){
            String effect=effects[index];Flow f=new Flow();f.failDecoration=effect;f.start(true,false);
            must(f.decorations==index+1&&f.reason().equals(effect+"Unknown"),"ordinary failed "+effect+" stays held across later Carry statuses without implicit redispatch");
            must(f.states.get(f.states.size()-1).phase.equals("Held")&&f.states.get(f.states.size()-1).committed,"failed decoration retains committed Held status "+effect);
            must(f.carry.diskJournal().getBoolean("cache_completed"),"Carry cache commit remains durable after decoration failure "+effect);
            f.drain();must(f.decorations==index+1,"draining unrelated Carry work does not retry "+effect);
            f.failDecoration="";retryDecorations(f);
            must(f.decorations==4&&f.states.get(f.states.size()-1).phase.equals("Complete"),"explicit retry finishes from failed step "+effect);
            for(int before=0;before<index;++before)must(Collections.frequency(f.events,effects[before])==1,"successful earlier step is never replayed "+effects[before]);
            must(Collections.frequency(f.events,effect)==2,"only failed step retried "+effect);
            retryDecorations(f);must(f.decorations==4,"retry after completion cannot replay decorations "+effect);f.close();
        }
        for(int index=0;index<effects.length;++index){
            String effect=effects[index];Flow f=new Flow();f.waitDecoration=effect;f.start(true,false);
            must(f.decorations==index+1&&f.pendingDecoration!=null&&f.states.get(f.states.size()-1).phase.equals(effect),"waiting "+effect+" remains distinct from not started across Carry progress");
            retryDecorations(f);must(f.decorations==index+1,"explicit retry cannot overlap a waiting callback "+effect);
            Consumer<Boolean> old=f.pendingDecoration;old.accept(false);f.drain();must(f.reason().equals(effect+"Unknown"),"waiting failure stays held "+effect);
            f.waitDecoration="";retryDecorations(f);must(f.decorations==4,"explicit retry preserves waiting step progress "+effect);
            old.accept(false);old.accept(true);f.drain();must(f.decorations==4&&f.states.get(f.states.size()-1).phase.equals("Complete"),"stale failed/success callback cannot alter completed retry "+effect);f.close();
        }
        for(int index=0;index<effects.length;++index){
            Flow f=new Flow();f.closeDecoration=effects[index];f.start(true,false);
            must(f.decorations==index,"foreground close before decoration blocks current and later effects "+effects[index]);
            must(f.provision.file.exists()&&f.carry.diskJournal().getBoolean("cache_completed"),"cancellation preserves committed state and orphan child "+effects[index]);
            retryDecorations(f);must(f.decorations==index,"closed flow cannot retry decorations "+effects[index]);f.close();
        }
    }
    static PurpleSyncChatScreen.Frame changedState(PurpleSyncChatScreen.Frame f){
        return new PurpleSyncChatScreen.Frame(ORIGIN,f.status,new PurpleSyncChatService.Binding(true,true,O,"binding","changed"),f.opening,f.current.info,f.refused);
    }
    static void eligibility()throws Exception{
        for(boolean denied:new boolean[]{false,true}){
            Flow f=new Flow();f.screen.target=info(f.carry.target,1,denied);PurpleSyncChatScreen.Control c=new PurpleSyncChatScreen.Control(f.screen);
            PurpleSyncChatScreen.ChangeOffer[] offer={null};c.offerChange(f.screen.target.chat,false,x->offer[0]=x);
            must((offer[0]!=null)==!denied,"same candidate eligibility gates Change offer "+denied);
            if(!denied){
                must(offer[0].candidate.eligibility.readers.warningRequired,"known readers need explicit consent");int[] accepted={0};
                c.acceptChange(offer[0],true,false,false,x->{if(x!=null)++accepted[0];});must(accepted[0]==0,"reader decline sends nothing");
                c.acceptChange(offer[0],true,true,false,x->{if(x!=null)++accepted[0];});must(accepted[0]==1,"same projection accepts disclosed readers");
            }
            c.close();f.close();
        }
        Flow f=new Flow();PurpleSyncChatScreen.Frame old=f.screen.frame;
        f.screen.frame=new PurpleSyncChatScreen.Frame(ORIGIN,old.status,old.binding,old.opening,info(O,0,true),old.refused);
        PurpleSyncChatScreen.Control c=new PurpleSyncChatScreen.Control(f.screen);PurpleSyncChatScreen.ChangeOffer[] offer={null};
        c.offerChange(f.carry.target,false,x->offer[0]=x);must(offer[0]!=null&&!offer[0].cleanupAvailable&&!offer[0].cleanupDefault,"ineligible old chat cleanup unavailable and unticked");
        int[] accepted={0};c.acceptChange(offer[0],true,true,true,x->{if(x!=null)++accepted[0];});must(accepted[0]==0,"unavailable cleanup cannot be authorized");
        c.acceptChange(offer[0],true,true,false,x->{if(x!=null)++accepted[0];});must(accepted[0]==1,"ineligible source can Change without cleanup");
        PurpleSyncChatScreen.ChangeOffer[] same={null};c.offerChange(O,false,x->same[0]=x);must(same[0]==null,"same chat is not a Switch");c.close();f.close();
        for(DirectRead read:new DirectRead[]{DirectRead.MarkGone,DirectRead.Unknown}){
            f=new Flow();old=f.screen.frame;ResolveInput input=new ResolveInput(777);input.settingsBound=true;input.settingsChat=O;input.cache=old.opening.data.location;input.cacheRead=read;input.searchRetriesCompleted=4;input.settingsHint=SettingsHint.NotRun;input.cachedEligibility=old.current.eligibility;
            Resolution r=resolve(input);PurpleSyncChatDiscovery.Phase phase=r.action==Action.Lost?PurpleSyncChatDiscovery.Phase.Lost:PurpleSyncChatDiscovery.Phase.Searching;
            f.screen.frame=new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(phase,r,"",true,false,0)),old.binding,old.opening,old.current.info,old.refused);
            must(f.screen.frame.canChange(),"complete stable cached Lost/Searching source supports Switch "+r.action);
            PurpleSyncChatScreen.ChangeSelection chosen=selection(f.screen,false,false);must(chosen.offer.source.equals(O)&&chosen.offer.action==r.action,"source captured from stable cache without readChat");f.close();
        }
    }
    static void races()throws Exception{
        Flow stale=new Flow();PurpleSyncChatScreen.Control oldControl=new PurpleSyncChatScreen.Control(stale.screen);PurpleSyncChatScreen.ChangeOffer[] shown={null};PurpleSyncChatScreen.ChangeSelection[] accepted={null};
        oldControl.offerChange(null,true,x->shown[0]=x);oldControl.acceptChange(shown[0],true,true,false,x->accepted[0]=x);must(accepted[0]!=null,"foreground control issues selection");oldControl.close();
        stale.flow=new PurpleSyncChatChange(stale,accepted[0]);stale.flow.start();stale.drain();must(stale.provision.creates==0&&stale.carries==0&&!accepted[0].current(ORIGIN,AUTH),"closed foreground owner revokes accepted consent");stale.close();
        for(String boundary:new String[]{"state","diskstate","cache","resolve","target"}){
            Flow f=new Flow();final Flow own=f;
            if(boundary.equals("state"))f.afterInspect=()->own.screen.frame=changedState(own.screen.frame);
            if(boundary.equals("resolve")){f.resolveAt=2;f.resolveHook=()->own.screen.frame=changedState(own.screen.frame);}
            if(boundary.equals("diskstate"))f.beforeBegin=()->{try{File state=new File(own.carry.files.root,"state.json");PurpleModernSyncCases.write(state,(new String(Files.readAllBytes(state.toPath()),java.nio.charset.StandardCharsets.UTF_8)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);}};
            if(boundary.equals("cache"))f.beforeBegin=()->{try{PurpleModernSyncCases.write(own.carry.files.cache,new byte[]{1});}catch(Exception e){throw new RuntimeException(e);}};
            if(boundary.equals("target"))f.afterInspect=()->own.screen.target=info(own.carry.target,1,false);
            f.start(false,false);must(f.carry.effects==0&&f.decorations==0,"changed opening/final target refuses before outward write "+boundary);f.close();
        }
        for(String boundary:new String[]{"close","background","account"}){
            Flow f=new Flow();PurpleSyncChatScreen.ChangeSelection selected=selection(f.screen,true,false);f.provision.hold="create";
            PurpleSyncChatSetup setup=PurpleSyncChatSetup.provision(f.provision,selected,x->{});setup.start();f.drain();
            must(f.provision.creates==1&&f.provision.file.exists()&&f.provision.pending!=null,"intent durable before suspended creation "+boundary);
            if(boundary.equals("close"))setup.close();if(boundary.equals("background"))f.foreground=false;if(boundary.equals("account"))f.screen.origin=new PurpleSyncChatOrigin(0,0,888,"other");
            f.provision.pending.run();f.drain();must(f.provision.applies==0&&f.carries==0&&f.decorations==0&&f.provision.file.exists(),"late create reply cannot write after revocation; orphan intent retained "+boundary);setup.close();f.close();
        }
        Flow f=new Flow();f.provision.duplicate=true;f.start(true,false);must(f.provision.creates==1&&f.carries==1&&f.decorations==3,"duplicate provisioning callbacks cannot repeat create/Carry/decorations");f.close();
        f=new Flow();PurpleSyncChatScreen.ChangeSelection selected=selection(f.screen,true,false);PurpleSyncChatSetup setup=PurpleSyncChatSetup.provision(f.provision,selected,x->{});setup.start();f.drain();
        must(setup.phase()==PurpleSyncChatSetup.Phase.Provisioned,"provision control reached durable boundary");byte[] orphan=f.provision.journal.read();f.provision.throwCancel=true;
        try{setup.close();}catch(IllegalStateException expected){}must(f.provision.owner==null&&Arrays.equals(orphan,f.provision.journal.read()),"throwing cleanup releases exact ownership and keeps orphan journal");f.provision.throwCancel=false;f.close();
        f=new Flow();selected=selection(f.screen,true,false);setup=PurpleSyncChatSetup.provision(f.provision,selected,x->{});setup.start();f.drain();setup.close();
        JSONObject invalid=new JSONObject(new String(f.provision.journal.read(),java.nio.charset.StandardCharsets.UTF_8));invalid.put("purpose","Setup").put("openingStamp","").put("step","Ready").put("created",false).put("chat","").put("readers","");
        byte[] prior=f.provision.journal.read();must(f.provision.journal.save(prior,invalid.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),()->true),"purpose control persisted");
        setup=PurpleSyncChatSetup.provision(f.provision,selected,x->{});setup.start();f.drain();must(setup.reason().equals("OpeningOrPurposeChanged")&&f.provision.creates==1,"provision refuses ordinary Setup purpose without new create");setup.close();f.close();
    }
    public static void main(String[] args)throws Exception{
        rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));
        for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        PurpleModernSyncCases.directory(new File(args[1]));PurpleModernSyncCases.Frame bootstrap=new PurpleModernSyncCases.Frame(new File(args[1]),"change",true);root=bootstrap.root;
        ui();eligibility();provisioning();flows();races();decorationFailures();PurpleSyncChatConsecutiveChangeCases.run();System.out.println("SYNC_CHAT_CHANGE_CHECKS="+checks);
    }
}
