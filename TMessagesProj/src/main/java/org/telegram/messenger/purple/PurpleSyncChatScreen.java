package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatScreen {
    public interface Text {
        String get(String key,Object... args);
    }
    public static String disclosure(Candidate candidate,Text text) {
        String title=candidate.chat.kind==PeerKind.Self?text.get("Saved"):candidate.title;
        String value=text.get("Cloud",title);
        if(candidate.eligibility.readers.warningRequired) {
            value+="\n\n"+text.get(candidate.eligibility.readers.botOwner?"BotReader":candidate.eligibility.readers.privateRecipient?"PrivateReader":"MembersReader",
                    title,candidate.eligibility.readers.otherMembers!=null?candidate.eligibility.readers.otherMembers.toString():text.get("UnknownCount"));
            value+="\n"+text.get("SettingsReaders")+"\n"+text.get("ReaderConsequences");
        }
        return value;
    }
    public static final class Refused {
        public final String title;
        public final Refusal reason;
        Refused(String title, Refusal reason) { this.title=title;this.reason=reason; }
    }

    public static final class Candidate {
        final PurpleSyncChatClient.Info info;
        final String readers;
        public final Peer chat;
        public final String title;
        public final Eligibility eligibility;
        public final boolean allowed, hiddenPrehistory, memberListKnown;
        public final String reason;
        Candidate(PurpleSyncChatOrigin origin, Peer chat, PurpleSyncChatClient.Info info) {
            this.info=info;this.chat=chat;
            boolean scoped=info!=null&&origin.equals(info.origin)&&Objects.equals(chat,info.chat)&&info.readable();
            eligibility=scoped?info.eligibility():unknownEligibility();
            allowed=scoped&&eligibility.allowed()&&info.readersKnown;
            reason=!eligibility.allowed()?eligibility.refusal.name():!allowed?"UnknownReaders":"";
            title=scoped&&info.title!=null?info.title:"";
            hiddenPrehistory=scoped&&info.hiddenPrehistory;
            memberListKnown=scoped&&info.members!=null;
            PurpleSyncChatCache.ReadersSnapshot snapshot=scoped?PurpleSyncChatCache.ReadersSnapshot.from(info):null;
            readers=snapshot!=null?snapshot.json().toString()+":"+hiddenPrehistory:"";
        }
        boolean same(Candidate other) {
            return other!=null&&allowed&&other.allowed&&chat.equals(other.chat)&&readers.equals(other.readers);
        }
    }

    public static final class Frame {
        final PurpleSyncChatOrigin origin;
        final PurpleSyncChatCache.Loaded opening;
        final PurpleSyncChatService.Binding binding;
        public final PurpleSyncChat.Status status;
        public final Candidate current;
        public final List<Refused> refused;
        public final boolean canSetup, bound, newReaders;
        public final Long originalOtherMembers;
        Frame(PurpleSyncChatOrigin origin, PurpleSyncChat.Status status, PurpleSyncChatService.Binding binding,
                PurpleSyncChatCache.Loaded opening, PurpleSyncChatClient.Info info, List<Refused> refused) {
            this.origin=origin;this.status=status;this.binding=binding;this.opening=opening;
            bound=binding==null||!binding.ok||binding.bound;
            canSetup=!bound&&status.complete&&status.phase==PurpleSyncChat.Phase.Unset&&opening!=null&&opening.ok&&opening.data==null;
            current=info!=null?new Candidate(origin,info.chat,info):null;
            this.refused=Collections.unmodifiableList(new ArrayList<>(refused));
            PurpleSyncChatCache.Data data=opening!=null?opening.data:null;
            newReaders=status.newReaders||data!=null&&data.newReaders;
            originalOtherMembers=data!=null&&data.readers!=null?data.readers.otherMembers:null;
        }
        public boolean canChange() {
            return binding!=null&&binding.ok&&status.complete&&(status.resolution.action==Action.Use||status.resolution.action==Action.Searching||status.resolution.action==Action.Lost)
                    &&opening!=null&&opening.ok&&opening.bytes!=null&&opening.data!=null&&!opening.data.pendingMove()
                    &&status.resolution.cache!=null&&opening.data.location.equals(status.resolution.cache)
                    &&binding.bound&&binding.chat!=null&&binding.chat.equals(status.resolution.cache.chat)&&!binding.stateStamp.isEmpty();
        }
        public boolean locallyPaused(){return opening!=null&&opening.ok&&opening.paused;}
        public boolean canFollowFill(){
            if(binding==null||!binding.ok||binding.stateStamp.isEmpty()||!status.complete||opening==null||!opening.ok||opening.paused||opening.data!=null&&opening.data.pendingMove())return false;
            Resolution resolution=status.resolution;
            if(resolution.winner==null)return false;
            if(resolution.action==Action.Fill)return !binding.bound&&binding.stateStamp.equals("missing")&&resolution.cache==null&&resolution.source!=null&&opening.data==null;
            return resolution.action==Action.Follow&&binding.bound&&binding.chat!=null&&resolution.cache!=null&&binding.chat.equals(resolution.cache.chat)
                    &&(opening.data==null||opening.data.location.equals(resolution.cache));
        }
        boolean canConsent(Peer chat) {
            return status.complete&&status.resolution.action==Action.Use&&opening!=null&&opening.ok&&opening.data!=null
                    &&!opening.data.pendingMove()&&opening.data.location.chat.equals(chat)&&status.resolution.readChat.equals(chat);
        }
    }

    public static final class Selection {
        final PurpleSyncChatOrigin origin;
        final Candidate candidate;
        public final Peer chat;
        public final boolean createChannel;
        private Selection(PurpleSyncChatOrigin origin, Candidate candidate, boolean create) {
            this.origin=origin;this.candidate=candidate;chat=candidate!=null?candidate.chat:null;createChannel=create;
        }
    }

    public static final class ChangeOffer {
        final PurpleSyncChatOrigin origin;
        final String authority;
        final Control owner;
        final Frame opening;
        final byte[] cacheBytes;
        final Candidate candidate;
        final Cache winner,location;
        final Action action;
        final String sourceReaders,stateStamp,bindingIdentity;
        public final Peer source,target;
        public final boolean createChannel,cleanupAvailable,cleanupDefault;
        public final String sourceTitle,targetTitle;
        private ChangeOffer(PurpleSyncChatOrigin origin,String authority,Frame frame,Candidate target,Control owner) {
            this.owner=owner;
            this.origin=origin;this.authority=authority;opening=frame;candidate=target;
            cacheBytes=frame.opening.bytes==null?null:frame.opening.bytes.clone();
            winner=frame.status.resolution.winner;location=frame.opening.data.location;
            source=frame.status.resolution.cache.chat;action=frame.status.resolution.action;this.target=target==null?null:target.chat;createChannel=target==null;
            stateStamp=frame.binding.stateStamp;bindingIdentity=frame.binding.identity;
            sourceReaders=sourceReaders(frame);
            sourceTitle=frame.current==null?"":frame.current.title;targetTitle=target==null?"":target.title;
            cleanupAvailable=frame.current!=null&&source.equals(frame.current.chat)&&frame.current.allowed;
            cleanupDefault=cleanupAvailable&&source.kind!=PeerKind.Self;
        }
        private static String sourceReaders(Frame frame){return frame.current==null?"":frame.current.chat.token()+":"+frame.current.readers;}
        boolean matches(Frame frame) {
            return frame!=null&&origin.equals(frame.origin)&&frame.canChange()&&stateStamp.equals(frame.binding.stateStamp)&&bindingIdentity.equals(frame.binding.identity)
                    &&opening.binding.same(frame.binding)&&java.util.Arrays.equals(cacheBytes,frame.opening.bytes)
                    &&location.equals(frame.opening.data.location)&&Objects.equals(winner,frame.status.resolution.winner)
                    &&action==frame.status.resolution.action&&source.equals(frame.status.resolution.cache.chat)&&sourceReaders.equals(sourceReaders(frame));
        }
        String stamp() throws java.io.IOException {
            PurpleAccountFileSnapshot.worker();
            String value=origin.accountKey()+"\n"+authority+"\n"+stateStamp+"\n"+bindingIdentity+"\n"+source.token()+"\n"+location.chat.token()+":"+location.markId+":"+location.markDate
                    +"\n"+(winner==null?"":winner.chat.token()+":"+winner.markId+":"+winner.markDate)+"\n"+action.name()+"\n"+sourceReaders+"\n"+PurpleSyncRetainedBases.encode(cacheBytes==null?new byte[0]:cacheBytes);
            return PurpleSyncRetainedFiles.hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public static final class ChangeSelection {
        final ChangeOffer offer;
        public final boolean cleanupSettings;
        private ChangeSelection(ChangeOffer offer,boolean cleanup){this.offer=offer;cleanupSettings=cleanup;}
        boolean current(PurpleSyncChatOrigin origin,String authority){return offer.owner.current()&&offer.origin.equals(origin)&&offer.authority.equals(authority);}
    }
    public static String changeDisclosure(ChangeOffer offer,Text text) {
        String old=offer.source.kind==PeerKind.Self?text.get("Saved"):offer.sourceTitle;
        String target=offer.createChannel?text.get("NewChannel"):offer.target.kind==PeerKind.Self?text.get("Saved"):offer.targetTitle;
        String value=text.get("ChangeAbout",target)+"\n\n"+(offer.createChannel?text.get("Cloud",target):disclosure(offer.candidate,text));
        if(offer.source.kind==PeerKind.Self)value+="\n\n"+text.get("ChangeOlder");
        return value+"\n\n"+text.get("ChangeHeld");
    }

    interface Port {
        PurpleSyncChatOrigin origin();
        default String authority(){return "";}
        boolean current();
        boolean begin(Object owner);
        void end(Object owner);
        void resolve(Object owner, Consumer<Frame> done);
        default void resolve(Object owner,String stream,Consumer<Frame> done){resolve(owner,done);}
        default void pause(Frame frame,boolean paused,Consumer<Boolean> done){done.accept(false);}
        void inspect(Peer chat, Consumer<PurpleSyncChatClient.Info> done);
        void remember(Frame frame, Candidate candidate, Consumer<Boolean> done);
        void cancel(Object owner);
    }

    public static final class Control implements AutoCloseable {
        private final Port port;
        private long ticket;
        private boolean closed, busy;
        private Frame frame;
        Control(Port port) { this.port=port; }
        public boolean current() { return !closed&&port.current(); }
        public Frame frame() { return frame; }
        private long begin() {
            if(!current()||busy||!port.begin(this))return 0;
            busy=true;return ++ticket;
        }
        private boolean live(long run) { return busy&&run==ticket&&current(); }
        private void end(long run) {
            if(run!=ticket)return;
            busy=false;port.end(this);
        }
        public void refresh(Consumer<Frame> done) {
            long run=begin();if(run==0)return;
            try { port.resolve(this,value->{if(!live(run)){end(run);return;}frame=value;end(run);if(current())done.accept(value);}); }
            catch(RuntimeException|LinkageError error){end(run);}
        }
        public void inspect(Peer chat, Consumer<Candidate> done) {
            if(chat==null)return;
            long run=begin();if(run==0)return;
            try { port.resolve(this,value->{
                if(!live(run)){end(run);return;}frame=value;
                if(value==null||!value.canSetup&&!value.canConsent(chat)){end(run);if(current())done.accept(null);return;}
                port.inspect(chat,info->{if(!live(run)){end(run);return;}Candidate candidate=new Candidate(port.origin(),chat,info);end(run);if(current())done.accept(candidate);});
            }); } catch(RuntimeException|LinkageError error){end(run);}
        }
        public void accept(Candidate shown, boolean cloudAccepted, boolean readersAccepted, Consumer<Selection> done) {
            if(shown==null||!shown.allowed||!cloudAccepted||shown.eligibility.readers.warningRequired&&!readersAccepted)return;
            long run=begin();if(run==0)return;
            try { port.resolve(this,value->{
                if(!live(run)){end(run);return;}frame=value;
                if(value==null||!value.canSetup&&!value.canConsent(shown.chat)){end(run);if(current())done.accept(null);return;}
                port.inspect(shown.chat,info->{
                    if(!live(run)){end(run);return;}
                    Candidate fresh=new Candidate(port.origin(),shown.chat,info);
                    if(!shown.same(fresh)){end(run);if(current())done.accept(null);return;}
                    Selection selected=new Selection(port.origin(),fresh,false);
                    if(value.canSetup){end(run);if(current())done.accept(selected);return;}
                    port.remember(value,fresh,saved->{if(!live(run)){end(run);return;}end(run);if(current())done.accept(saved?selected:null);});
                });
            }); } catch(RuntimeException|LinkageError error){end(run);}
        }
        public void create(boolean cloudAccepted, Consumer<Selection> done) {
            if(!cloudAccepted)return;
            long run=begin();if(run==0)return;
            try { port.resolve(this,value->{if(!live(run)){end(run);return;}frame=value;boolean allowed=value!=null&&value.canSetup;end(run);if(current())done.accept(allowed?new Selection(port.origin(),null,true):null);}); }
            catch(RuntimeException|LinkageError error){end(run);}
        }
        public void offerChange(Peer target,boolean create,Consumer<ChangeOffer> done) {
            if(!create&&target==null)return;
            long run=begin();if(run==0)return;
            try {port.resolve(this,value->{
                if(!live(run)){end(run);return;}frame=value;
                if(value==null||!port.origin().equals(value.origin)||!value.canChange()||!port.authority().matches("[0-9a-f]{64}")||!create&&target.equals(value.status.resolution.cache.chat)){
                    end(run);if(current())done.accept(null);return;
                }
                if(create){ChangeOffer offer=new ChangeOffer(port.origin(),port.authority(),value,null,this);end(run);if(current())done.accept(offer);return;}
                port.inspect(target,info->{
                    if(!live(run)){end(run);return;}Candidate candidate=new Candidate(port.origin(),target,info);
                    ChangeOffer offer=candidate.allowed?new ChangeOffer(port.origin(),port.authority(),value,candidate,this):null;
                    end(run);if(current())done.accept(offer);
                });
            });}catch(RuntimeException|LinkageError e){end(run);}
        }
        public void acceptChange(ChangeOffer shown,boolean cloud,boolean readers,boolean cleanup,Consumer<ChangeSelection> done) {
            if(shown==null||shown.owner!=this||!shown.origin.equals(port.origin())||!shown.authority.equals(port.authority())||!cloud
                    ||shown.candidate!=null&&shown.candidate.eligibility.readers.warningRequired&&!readers||cleanup&&!shown.cleanupAvailable)return;
            long run=begin();if(run==0)return;
            try {port.resolve(this,value->{
                if(!live(run)){end(run);return;}frame=value;
                if(!shown.matches(value)){end(run);if(current())done.accept(null);return;}
                if(shown.createChannel){end(run);if(current())done.accept(new ChangeSelection(shown,cleanup));return;}
                port.inspect(shown.target,info->{
                    if(!live(run)){end(run);return;}Candidate fresh=new Candidate(port.origin(),shown.target,info);
                    boolean accepted=shown.candidate.same(fresh);end(run);
                    if(current())done.accept(accepted?new ChangeSelection(shown,cleanup):null);
                });
            });}catch(RuntimeException|LinkageError e){end(run);}
        }
        public void offerFollowFill(String stream,Consumer<PurpleSyncChatMoveConsent.Offer> done){
            if(!stream.equals("config")&&!stream.equals("library"))return;
            long run=begin();if(run==0)return;
            try{port.resolve(this,stream,value->{
                if(!live(run)){end(run);return;}frame=value;
                if(value==null||!port.origin().equals(value.origin)||!value.canFollowFill()||!port.authority().matches("[0-9a-f]{64}")){end(run);if(current())done.accept(null);return;}
                Peer target=value.status.resolution.winner.chat;
                port.inspect(target,info->{
                    if(!live(run)){end(run);return;}
                    Candidate candidate=new Candidate(port.origin(),target,info);
                    PurpleSyncChatMoveConsent.Offer offer=candidate.allowed?new PurpleSyncChatMoveConsent.Offer(port.origin(),port.authority(),this,value,stream,candidate):null;
                    end(run);if(current())done.accept(offer);
                });
            });}catch(RuntimeException|LinkageError e){end(run);}
        }
        public void acceptFollowFill(PurpleSyncChatMoveConsent.Offer offer,boolean readersAccepted,Consumer<PurpleSyncChatMoveConsent.Selection> done){
            if(offer==null||offer.owner!=this||!offer.current(port.origin(),port.authority())||offer.requiresReaders&&!readersAccepted)return;
            long run=begin();if(run==0)return;
            try{port.resolve(this,offer.stream,value->{
                if(!live(run)){end(run);return;}frame=value;
                if(!offer.matches(value)){end(run);if(current())done.accept(null);return;}
                port.inspect(offer.target,info->{
                    if(!live(run)){end(run);return;}
                    boolean same=offer.candidate.same(new Candidate(port.origin(),offer.target,info));end(run);
                    if(current())done.accept(same?PurpleSyncChatMoveConsent.Selection.accepted(offer):null);
                });
            });}catch(RuntimeException|LinkageError e){end(run);}
        }
        public void pauseHere(boolean paused,Consumer<Boolean> done){
            long run=begin();if(run==0)return;
            try{port.resolve(this,value->{
                if(!live(run)){end(run);return;}frame=value;
                if(value==null||!port.origin().equals(value.origin)||!paused&&!value.locallyPaused()||paused&&!value.canFollowFill()){end(run);if(current())done.accept(false);return;}
                port.pause(value,paused,saved->{if(!live(run)){end(run);return;}end(run);if(current())done.accept(saved);});
            });}catch(RuntimeException|LinkageError e){end(run);}
        }
        @Override public void close() {
            if(closed)return;
            closed=true;++ticket;try{port.cancel(this);}finally{busy=false;port.end(this);}
        }
    }
    private PurpleSyncChatScreen() { }
}
