package org.telegram.messenger.purple;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveConsentCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;

public final class PurpleSyncChatMoveConsentReviewCases {
 static int checks,failures;
 static void check(boolean b,String why){++checks;if(!b){++failures;System.out.println("REVIEWER_FAILURE: "+why);}}
 static void pauseLogout()throws Exception{
  for(boolean withCache:new boolean[]{false,true}){
   PurpleSyncChatServiceCases.Env e=new PurpleSyncChatServiceCases.Env();
   if(withCache)e.file.seed(PurpleSyncChatModelCases.cache(Peer.self(),7,50));
   PurpleSyncChatCache.Loaded before=PurpleSyncChatCache.load(e.file,e.origin(),e.authority());
   check(PurpleSyncChatCache.pause(e.file,before,e.origin(),e.authority(),true,()->true),"persist explicit local Pause");
   PurpleSyncChatService service=new PurpleSyncChatService(e);List<PurpleSyncChatDiscovery.Status> out=new ArrayList<>();
   service.checkBeforePost("config",null,out::add);check(out.size()==1&&out.get(0).reason.equals("LocalPause")&&e.clients==0,"Pause blocks before logout");
   service.close(true);
   PurpleSyncChatCache.Loaded after=PurpleSyncChatCache.load(e.file,e.origin(),e.authority());
   System.out.println("PAUSE_AFTER_LOGOUT cache="+withCache+" paused="+after.paused+" removed="+e.file.removes);
   check(after.paused,"logout without Resume must preserve local Pause (cache="+withCache+")");
   e.authority=PurpleSyncChatCache.authority("22222222222222222222222222222222");
   after=PurpleSyncChatCache.load(e.file,e.origin(),e.authority());
   check(after.paused,"new login without Resume must preserve account/device Pause (cache="+withCache+")");
   check(after.data==null,"new login never admits old location, readers or move grants");
   check(!PurpleSyncChatCache.load(e.file,new PurpleSyncChatOrigin(1,0,22,"other-account"),e.authority()).paused,"another account cannot inherit Pause");
   PurpleSyncChatService nextService=new PurpleSyncChatService(e);List<PurpleSyncChatDiscovery.Status> nextOut=new ArrayList<>();
   nextService.checkBeforePost("library",null,nextOut::add);
   check(nextOut.size()==1&&nextOut.get(0).reason.equals("LocalPause")&&e.clients==0,"new-login service holds every stream before constructing a client");
   check(!PurpleSyncChatCache.pause(e.file,after,e.origin(),e.authority(),false,()->false),"stale or background Resume cannot clear Pause");
   check(PurpleSyncChatCache.load(e.file,e.origin(),e.authority()).paused,"refused Resume retains Pause");
   check(PurpleSyncChatCache.pause(e.file,after,e.origin(),e.authority(),false,()->true),"explicit foreground Resume clears Pause");
   check(!PurpleSyncChatCache.load(e.file,e.origin(),e.authority()).paused&&PurpleSyncChatCache.load(e.file,e.origin(),e.authority()).data==null,"Resume grants no old reader consent");nextService.close(false);

  }
 }
 static void departingCountOnly()throws Exception{
  for(String mode:new String[]{"Follow","Fill"}){
   Flow f=new Flow(mode);PurpleSyncChatClient.Info template=f.carry.info(f.carry.target,2,false);
   f.screen.target=new PurpleSyncChatClient.Info(f.origin(),template.chat,template.peer,template.error,template.title,null,true);
   f.carry.targetInfo=f.screen.target;f.carry.stop="Effect";f.start("config");
   check(f.carry.deferredCommand!=null&&f.carry.effects==0,"count-only baseline reaches first intent");
   JSONObject baseline=f.carry.opened.transport().getJSONObject("readers").getJSONObject("snapshot");
   check(!baseline.has("memberHashes")&&baseline.getString("otherMembers").equals("2"),"baseline honestly lacks member roster");
   Carry next=new Carry(f.carry);next.targetInfo=next.info(next.target,1,false);f.close();next.resume();
   check(next.done(),"known lower-count readers can complete "+mode+" "+next.reason());
   PurpleSyncChatCache.Loaded cache=PurpleSyncChatCache.load(PurpleSyncChatCache.file(next.files.cache),next.origin,AUTH);
   System.out.println("DEPARTURE_WARNING mode="+mode+" warning="+cache.data.newReaders);
   check(!cache.data.newReaders,"newly available roster with only a lower count must not invent additions "+mode);next.close();
  }
 }
 static void countOnlyAddition()throws Exception{
  for(String mode:new String[]{"Follow","Fill"}){
   Flow f=new Flow(mode);PurpleSyncChatClient.Info template=f.carry.info(f.carry.target,1,false);
   f.screen.target=new PurpleSyncChatClient.Info(f.origin(),template.chat,template.peer,template.error,template.title,null,true);
   f.carry.targetInfo=f.screen.target;f.carry.stop="Effect";f.start("config");
   check(f.carry.deferredCommand!=null&&f.carry.effects==0,"count-only baseline reserves before addition");
   Carry next=new Carry(f.carry);next.targetInfo=next.info(next.target,2,false);f.close();next.resume();
   check(next.done(),"known count addition continues after original intent "+mode+" "+next.reason());
   PurpleSyncChatCache.Loaded cache=PurpleSyncChatCache.load(PurpleSyncChatCache.file(next.files.cache),next.origin,AUTH);
   check(cache.data.newReaders,"real count addition still warns persistently "+mode);next.close();
  }
 }
 static void logoutThreadFence()throws Exception{
  PurpleSyncChatServiceCases.Env e=new PurpleSyncChatServiceCases.Env();e.file.seed(PurpleSyncChatModelCases.cache(Peer.self(),7,50));byte[] before=e.file.bytes.clone();
  android.os.Looper.mainThread=Thread.currentThread();
  try{PurpleSyncChatCache.forgetLogin(e.file,e.origin(),e.authority());check(Arrays.equals(before,e.file.bytes)&&e.file.removes==0,"logout cache disk work refuses GUI thread");}
  finally{android.os.Looper.mainThread=null;}
  PurpleSyncChatCache.forgetLogin(e.file,e.origin(),e.authority());check(e.file.bytes==null&&e.file.removes==1,"worker logout invalidates unpaused cache");
 }
 static void emptySource()throws Exception{
  Flow f=new Flow("Fill");f.carry.oldRecords.clear();f.carry.oldBodies.clear();f.screen.refresh();f.start("config");
  check(f.states.stream().anyMatch(s->s.committed)&&f.carry.effects==0,"complete readable Empty old source permits no-op Fill "+f.reason());
  check(!new File(f.carry.files.root,"state.json").exists(),"empty-source Fill keeps state absent");f.close();
 }
 static void missingAdminCount()throws Exception{
  Flow f=new Flow("Follow");f.carry.stop="Effect";f.start("config");Carry next=new Carry(f.carry);PurpleSyncChatClient.Info target=next.info(next.target,0,false);target.peer.facts.otherAdmins=null;next.targetInfo=target;f.close();next.resume();
  check(!next.done()&&next.effects==0,"unknown admin count after intent holds");next.close();
 }
 public static void main(String[] a)throws Exception{PurpleSyncChatMoveConsentCases.main(a);pauseLogout();departingCountOnly();countOnlyAddition();logoutThreadFence();emptySource();missingAdminCount();System.out.println("MOVE_CONSENT_REVIEW_CHECKS="+checks);System.out.println("MOVE_CONSENT_REVIEW_FAILURES="+failures);if(failures!=0)throw new AssertionError("reviewer failures="+failures);}
}
