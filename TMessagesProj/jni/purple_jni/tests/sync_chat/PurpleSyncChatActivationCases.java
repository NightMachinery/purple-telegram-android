package org.telegram.messenger.purple;

import java.util.*;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatActivationCases {
    static byte[] journal(Peer chat,PurpleSyncChatSetup.Step step)throws Exception{
        PurpleSyncChatSetup.Journal j=new PurpleSyncChatSetup.Journal();j.chat=chat;j.mark=new Cache(chat,7,50);j.step=step;j.random=123;
        j.readers=new PurpleSyncChatScreen.Candidate(ORIGIN,chat,info(chat)).readers;
        return j.json(ORIGIN,PurpleSyncChatSetup.authority(PurpleSyncChatServiceCases.AUTH)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static PurpleSyncChatScreen.Frame frame(Peer chat,PurpleSyncChatClient.Info info){
        Cache mark=new Cache(chat,7,50);ResolveInput input=new ResolveInput(ORIGIN.userId);input.cache=mark;input.cacheRead=DirectRead.Valid;input.cachedEligibility=info.eligibility();
        PurpleSyncChatCache.Data data=new PurpleSyncChatCache.Data(mark,"synthetic",PurpleSyncChatCache.ReadersSnapshot.from(info),Collections.singleton("config"),new JSONObject(),false);
        return new PurpleSyncChatScreen.Frame(ORIGIN,new PurpleSyncChat.Status(new PurpleSyncChatDiscovery.Status(PurpleSyncChatDiscovery.Phase.Use,resolve(input),"",true,false,0)),new PurpleSyncChatService.Binding(true,false,SELF),new PurpleSyncChatCache.Loaded(true,null,data),info,Collections.emptyList());
    }
    static void run()throws Exception{
        int before=checks;
        for(Peer chat:new Peer[]{SELF,USER,GROUP,CHANNEL}){
            Cache mark=new Cache(chat,7,50);PurpleSyncChatScreen.Candidate candidate=new PurpleSyncChatScreen.Candidate(ORIGIN,chat,info(chat));
            byte[] complete=journal(chat,PurpleSyncChatSetup.Step.Complete);PurpleSyncChatActivation a=new PurpleSyncChatActivation(ORIGIN,mark,candidate,complete,PurpleSyncChatServiceCases.AUTH);
            PurpleSyncInventory inventory=new PurpleSyncInventory.Builder(ORIGIN.userId,chat,ORIGIN).build(true);
            check(a.accepts(inventory)&&a.renew(frame(chat,info(chat)),false),"Complete exact origin/chat/settings consent activates "+chat.token());
            check(!a.accepts(new PurpleSyncInventory.Builder(ORIGIN.userId,chat,ORIGIN).build(false)),"partial inventory cannot activate");
            check(!a.accepts(new PurpleSyncInventory.Builder(ORIGIN.userId,chat,new PurpleSyncChatOrigin(0,0,11,"other-login")).build(true)),"other callback login cannot activate");
            for(String refusal:new String[]{"unfinished","foreign-authority","other-mark","corrupt"}){
                byte[] bytes=refusal.equals("unfinished")?journal(chat,PurpleSyncChatSetup.Step.PeerInfo):refusal.equals("corrupt")?new byte[]{1}:complete;
                String authority=refusal.equals("foreign-authority")?PurpleSyncChatCache.authority("22222222222222222222222222222222"):PurpleSyncChatServiceCases.AUTH;
                Cache expected=refusal.equals("other-mark")?new Cache(chat,8,51):mark;
                check(!PurpleSyncChatActivation.complete(bytes,ORIGIN,authority,expected),"journal refuses "+refusal+" "+chat.token());
            }
            PurpleSyncChatClient.Info changed=info(chat);changed.peer.facts.otherMembers=2L;
            check(!a.renew(frame(chat,changed),false),"unissued settings document requires current reader consent");
            check(a.renew(frame(chat,changed),true),"issued settings document retains prior consent for known additions");
            PurpleSyncChatClient.Info unknown=new PurpleSyncChatClient.Info(ORIGIN,chat,changed.peer,changed.error,changed.title,changed.members,false);
            check(!a.renew(frame(chat,unknown),true),"unknown readers always hold");
            check(PurpleSyncChatManual.sameInventory(inventory,new PurpleSyncInventory.Builder(ORIGIN.userId,chat,ORIGIN).build(true)),"fresh identical typed Empty inventory matches");
            check(!PurpleSyncChatManual.sameInventory(inventory,new PurpleSyncInventory.Builder(ORIGIN.userId,chat,ORIGIN).build(false)),"fresh incomplete inventory never matches");
        }
        PurpleSyncChatClient.Info old=info(GROUP);old.peer.facts.creator=true;old.peer.facts.admin=true;
        PurpleSyncChatActivation a=new PurpleSyncChatActivation(ORIGIN,new Cache(GROUP,7,50),new PurpleSyncChatScreen.Candidate(ORIGIN,GROUP,old),null,PurpleSyncChatServiceCases.AUTH);
        PurpleSyncChatClient.Info lost=info(GROUP);lost.peer.facts.creator=false;lost.peer.facts.admin=false;
        check(!a.renew(frame(GROUP,lost),true),"issued flow refuses lost creator/admin rights even if ordinary sending remains eligible");
        System.out.println("MANUAL_ACTIVATION_CHECKS="+(checks-before));
    }
}
