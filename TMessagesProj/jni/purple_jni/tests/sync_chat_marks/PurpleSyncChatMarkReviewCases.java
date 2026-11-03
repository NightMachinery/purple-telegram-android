package org.telegram.messenger.purple;
import java.io.File;
import java.util.*;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatMarkCases.*;
public final class PurpleSyncChatMarkReviewCases {
 static int checks,failures;
 static void check(boolean b,String why){++checks;if(!b){++failures;System.out.println("REVIEWER_FAILURE: "+why);}}
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
 public static void main(String[] a)throws Exception{selectedRead();System.out.println("MARK_ACTIVATION_REVIEW_CHECKS="+checks);System.out.println("MARK_ACTIVATION_REVIEW_FAILURES="+failures);if(failures!=0)throw new AssertionError("reviewer failures="+failures);}
}
