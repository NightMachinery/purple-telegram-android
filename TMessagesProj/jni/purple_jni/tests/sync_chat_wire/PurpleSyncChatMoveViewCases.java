package org.telegram.messenger.purple;

import java.util.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

final class PurpleSyncChatMoveViewCases {
    static int checks;
    static void must(boolean value,String why){++checks;if(!value)throw new AssertionError(why);}
    static void change()throws Exception {
        PurpleSyncChatWireCases.Factory factory=new PurpleSyncChatWireCases.Factory();factory.change=new PurpleSyncChatChangeCases.Flow();
        PurpleSyncChatChangeCases.Flow f=factory.change;
        PurpleSyncChatScreen.ChangeSelection selected=PurpleSyncChatChangeCases.selection(f.screen,false,false);
        must(selected!=null,"OFF view regression starts with a valid Change selection");
        List<PurpleSyncChatChange.State> seen=new ArrayList<>();PurpleSyncChatFlows owner=new PurpleSyncChatFlows();
        PurpleSyncChatFlows.Handle handle;
        if(PurpleSyncChatChange.ACTIVATED)handle=owner.change(true,true,factory,selected,seen::add);
        else handle=PurpleSyncChat.change(0,selected,()->true,seen::add);
        f.drain();must(!seen.isEmpty(),"actual entry publishes a result");
        PurpleSyncChatChange.State state=seen.get(seen.size()-1);PurpleSyncChatChange.State progress=PurpleSyncChatMoveView.progress(state);
        if(!PurpleSyncChatChange.ACTIVATED){
            must(handle==null&&state.reason.equals("QualificationHeld")&&progress==null,"actual OFF facade rejection is a message, not progress");
            must(!PurpleSyncChatMoveView.retry(PurpleSyncChatMoveView.Feature.Change,progress,false),"valid OFF selection renders no Retry row");
            must(!PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.Change)&&factory.constructed==0,"valid OFF selection exposes no Change entry or backend");
        }else must(handle!=null&&handle.current()&&state.committed&&state.phase.equals("Complete"),"ON owner still completes the accepted selection");
        owner.close();f.drain();f.close();
    }
    static void follow()throws Exception {
        PurpleSyncChatWireCases.Factory factory=new PurpleSyncChatWireCases.Factory();factory.consent=new PurpleSyncChatMoveConsentCases.Flow("Follow");
        PurpleSyncChatMoveConsentCases.Flow f=factory.consent;PurpleSyncChatMoveConsent.Selection selected=f.accept(f.offer("config"),true);
        must(selected!=null,"OFF view regression starts with a valid Follow selection");
        List<PurpleSyncChatMoveConsent.State> seen=new ArrayList<>();PurpleSyncChatFlows owner=new PurpleSyncChatFlows();
        PurpleSyncChatFlows.Handle handle;
        if(PurpleSyncChatMoveConsent.ACTIVATED)handle=owner.consent(true,true,factory,selected,seen::add);
        else handle=PurpleSyncChat.followFill(0,selected,()->true,seen::add);
        f.drain();must(!seen.isEmpty(),"actual Follow entry publishes a result");
        PurpleSyncChatMoveConsent.State value=seen.get(seen.size()-1);
        PurpleSyncChatChange.State state=new PurpleSyncChatChange.State(value.phase,value.reason,value.committed,value.cleanup);
        PurpleSyncChatChange.State progress=PurpleSyncChatMoveView.progress(state);
        if(!PurpleSyncChatMoveConsent.ACTIVATED){
            must(handle==null&&state.reason.equals("QualificationHeld")&&progress==null,"actual OFF Follow rejection is not retained as progress");
            must(!PurpleSyncChatMoveView.retry(PurpleSyncChatMoveView.Feature.FollowFill,progress,false),"valid OFF Follow selection renders no Retry row");
            must(!PurpleSyncChatMoveView.enabled(PurpleSyncChatMoveView.Feature.FollowFill)&&factory.constructed==0,"valid OFF selection exposes no Follow entry or backend");
        }else must(handle!=null&&handle.current()&&state.committed&&state.phase.equals("Complete"),"ON Follow still completes accepted consent");
        owner.close();f.drain();f.close();
    }
    static void liveOwner()throws Exception {
        PurpleSyncChatWireCases.Factory factory=new PurpleSyncChatWireCases.Factory();factory.change=new PurpleSyncChatChangeCases.Flow();
        PurpleSyncChatChangeCases.Flow f=factory.change;f.carry.stop="Source";
        List<PurpleSyncChatChange.State> seen=new ArrayList<>();PurpleSyncChatFlows owner=new PurpleSyncChatFlows();
        PurpleSyncChatFlows.Handle handle=owner.change(true,true,factory,PurpleSyncChatChangeCases.selection(f.screen,false,false),seen::add);f.drain();
        must(handle!=null&&handle.current()&&f.carry.deferred!=null,"real ON controller owns the stopped operation");
        f.carry.deferred.accept(null);f.drain();PurpleSyncChatChange.State held=seen.get(seen.size()-1);
        must(held.phase.equals("Held")&&held.reason.equals("OutcomeUnknown"),"real failed read publishes a retryable ON hold");
        must(PurpleSyncChatMoveView.retry(true,held,handle.current()),"ON live operation retains Retry");
        must(!PurpleSyncChatMoveView.retry(false,held,handle.current()),"injected OFF cannot retry an otherwise live operation");
        must(PurpleSyncChatMoveView.retry(PurpleSyncChatMoveView.Feature.Change,held,handle.current())==PurpleSyncChatChange.ACTIVATED,"Retry uses this operation's feature switch");
        handle.close();f.drain();must(!handle.current()&&!PurpleSyncChatMoveView.retry(true,held,handle.current()),"closed operation exposes no retained Retry");
        owner.close();f.close();
    }
    interface Case { void run()throws Exception; }
    static void isolated(String name,Case body)throws Exception {
        java.io.File previous=root;root=new java.io.File(previous,name);PurpleModernSyncCases.directory(root);
        try{body.run();}finally{root=previous;}
    }
    static void run()throws Exception {
        for(int bits=0;bits<16;++bits){
            boolean change=(bits&1)!=0,consent=(bits&2)!=0,recovery=(bits&4)!=0,cleanup=(bits&8)!=0;
            for(PurpleSyncChatMoveView.Feature feature:PurpleSyncChatMoveView.Feature.values()){
                boolean expected=feature==PurpleSyncChatMoveView.Feature.Change?change:feature==PurpleSyncChatMoveView.Feature.FollowFill?consent:feature==PurpleSyncChatMoveView.Feature.Recovery?recovery:bits!=0;
                must(PurpleSyncChatMoveView.enabled(feature,change,consent,recovery,cleanup)==expected,"entry uses only its relevant switch "+feature+" "+bits);
            }
        }
        PurpleSyncChatChange.State qualification=new PurpleSyncChatChange.State("Held","QualificationHeld",false);
        for(boolean enabled:new boolean[]{false,true})for(boolean live:new boolean[]{false,true}){
            must(PurpleSyncChatMoveView.progress(qualification)==null&&!PurpleSyncChatMoveView.retry(enabled,qualification,live),"qualification is never actionable progress");
            must(!PurpleSyncChatMoveView.retry(enabled,new PurpleSyncChatChange.State("Complete","",true),live),"completed progress has no Retry");
        }
        must(!PurpleSyncChat.moveRetryAvailable(-1),"missing account has no live startup operation");
        isolated("view-change",PurpleSyncChatMoveViewCases::change);isolated("view-follow",PurpleSyncChatMoveViewCases::follow);isolated("view-live",PurpleSyncChatMoveViewCases::liveOwner);System.out.println("WIRE_VIEW_CHECKS="+checks);
    }
}
