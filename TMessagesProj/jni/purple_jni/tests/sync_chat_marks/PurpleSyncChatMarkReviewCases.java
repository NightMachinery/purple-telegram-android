package org.telegram.messenger.purple;
import java.io.File;
import java.util.*;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatMarkCases.*;
public final class PurpleSyncChatMarkReviewCases {
 static int checks,failures;static final int REMOVE_MARK=0x70750200;
 static void check(boolean b,String why){++checks;if(!b){++failures;System.out.println("REVIEWER_FAILURE: "+why);}}
 static String text(String key,Object... args){return key;}
 static void productionRows(){
  Port p=new Port(CHANNEL);PurpleSyncChatScreen.Frame old=p.frame;
  PurpleSyncChatScreen.Frame frame=new PurpleSyncChatScreen.Frame(ORIGIN,old.status,old.binding,old.opening,old.current.info,old.refused,Collections.singletonList(p.row));
  MarkActivationLayout layout=new MarkActivationLayout(frame);
  ArrayList<MarkActivationLayout.UItem> off=new ArrayList<>(),on=new ArrayList<>();
  layout.render(false,off);layout.render(true,on);
  check(off.stream().filter(x->x.button).count()==0,"production OFF has zero actionable rows");
  check(on.stream().filter(x->x.button).count()==1,"injected ON has one actionable row");
  layout.click(false,new MarkActivationLayout.UItem(REMOVE_MARK,true));
  check(layout.clicks==0,"OFF refuses stale or fabricated action id");
  layout.clicks=0;layout.click(true,new MarkActivationLayout.UItem(REMOVE_MARK,true));
  check(layout.clicks==1,"injected ON dispatches captured row");
  layout.confirm(false);check(layout.confirmations==0,"OFF opens no destructive confirmation");
  layout.confirmations=0;layout.confirm(true);check(layout.confirmations==1,"injected ON opens one confirmation");
  layout.submit(false);check(layout.submissions==0,"OFF refuses retained confirmation callback");
  layout.submissions=0;layout.submit(true);check(layout.submissions==1,"injected ON accepts current confirmation");
 }
 static void selectedRead(){
  for(Peer chat:new Peer[]{SELF,CHANNEL}){
   Port p=new Port(chat);PurpleSyncChatRequestCases.Native platform=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram client=new PurpleSyncChatTelegram(ORIGIN,platform);List<PurpleSyncChatClient.Exact> out=new ArrayList<>();client.exact(p.row.location,out::add);
   TLObject request=platform.requests.get(0);ArrayList<Integer> ids;
   if(chat.equals(CHANNEL)){check(request instanceof TLRPC.TL_channels_getMessages,"channel read uses captured channel namespace");TLRPC.TL_channels_getMessages exact=(TLRPC.TL_channels_getMessages)request;check(exact.channel.channel_id==chat.id,"channel exact read captures channel id");ids=exact.id;}
   else {check(request instanceof TLRPC.TL_messages_getMessages,"ordinary mark uses exact getMessages");ids=((TLRPC.TL_messages_getMessages)request).id;}
   check(ids.size()==1&&ids.get(0)==p.row.location.markId,"one captured message id on actual read");
   TLRPC.TL_messageEmpty absent=new TLRPC.TL_messageEmpty();absent.id=p.row.location.markId;platform.reply(1,messages(absent));
   check(out.isEmpty(),"peerless mark absence waits for independent chat readability");
   PurpleSyncChatFacts.Input facts=input(chat);
   if(chat.equals(CHANNEL)){TLRPC.TL_messages_chatFull full=new TLRPC.TL_messages_chatFull();full.full_chat=facts.groupFull;full.chats.add(facts.group);platform.reply(2,full);}
   else {TLRPC.TL_users_userFull full=new TLRPC.TL_users_userFull();full.full_user=facts.userFull;full.users.add(facts.user);platform.reply(2,full);}
   check(out.size()==1&&PurpleSyncChatMarkActions.absent(p.row,out.get(0)),"D12 selected own mark may conclude scoped absence");
   check(!PurpleSyncChatMarkActions.authorized(p.row,p.frame,out.get(0)),"D12 absence can never authorize deletion");
   p.first=out.get(0);p.start();check(p.complete()&&p.deletes==0,"already-absent action sends no delete");p.released();client.cancel();
   PurpleSyncChatIO records=new PurpleSyncChatIO(ORIGIN,chat,info(chat).peer);
   check(records.lookup(messages(absent),null,absent.id,PurpleSyncChatMoveIOCases::meta).status==PurpleSyncClient.LookupStatus.Failed,"D12 does not extend to record lookup");
  }
 }
 public static void main(String[] a)throws Exception{productionRows();selectedRead();System.out.println("MARK_ACTIVATION_REVIEW_CHECKS="+checks);System.out.println("MARK_ACTIVATION_REVIEW_FAILURES="+failures);if(failures!=0)throw new AssertionError("reviewer failures="+failures);}
}
