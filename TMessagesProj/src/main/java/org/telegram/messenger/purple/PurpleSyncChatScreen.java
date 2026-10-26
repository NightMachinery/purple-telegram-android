package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

public final class PurpleSyncChatScreen {
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
        final PurpleSyncChatCache.Loaded opening;
        final PurpleSyncChatService.Binding binding;
        public final PurpleSyncChat.Status status;
        public final Candidate current;
        public final List<Refused> refused;
        public final boolean canSetup, bound, newReaders;
        public final Long originalOtherMembers;
        Frame(PurpleSyncChatOrigin origin, PurpleSyncChat.Status status, PurpleSyncChatService.Binding binding,
                PurpleSyncChatCache.Loaded opening, PurpleSyncChatClient.Info info, List<Refused> refused) {
            this.status=status;this.binding=binding;this.opening=opening;
            bound=binding==null||!binding.ok||binding.bound;
            canSetup=!bound&&status.complete&&status.phase==PurpleSyncChat.Phase.Unset&&opening!=null&&opening.ok&&opening.data==null;
            current=info!=null?new Candidate(origin,info.chat,info):null;
            this.refused=Collections.unmodifiableList(new ArrayList<>(refused));
            PurpleSyncChatCache.Data data=opening!=null?opening.data:null;
            newReaders=status.newReaders||data!=null&&data.newReaders;
            originalOtherMembers=data!=null&&data.readers!=null?data.readers.otherMembers:null;
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

    interface Port {
        PurpleSyncChatOrigin origin();
        boolean current();
        boolean begin(Object owner);
        void end(Object owner);
        void resolve(Object owner, Consumer<Frame> done);
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
        @Override public void close() {
            if(closed)return;
            closed=true;++ticket;port.cancel(this);busy=false;port.end(this);
        }
    }
    private PurpleSyncChatScreen() { }
}
