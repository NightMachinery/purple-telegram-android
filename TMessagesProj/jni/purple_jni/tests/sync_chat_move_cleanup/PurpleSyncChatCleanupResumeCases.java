package org.telegram.messenger.purple;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.json.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveCleanupCases.*;
public final class PurpleSyncChatCleanupResumeCases {
 static int checks,failures;
 static void check(boolean value,String name){++checks;if(!value){++failures;System.out.println("REVIEWER_R1_FAILURE: "+name);}}
 static void waiting()throws Exception{
  for(String effect:new String[]{"Pin","Mute","Archive"}){
   PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();f.waitDecoration=effect;f.start(true,true);
   PurpleSyncChatChange.State state=f.states.get(f.states.size()-1);int steps=Arrays.asList("Pin","Mute","Archive").indexOf(effect)+1;
   check(state.cleanup!=null&&state.cleanup.pending&&state.cleanup.canAct,"Waiting decoration forwards actionable cleanup "+effect);
   check(state.phase.equals(effect)&&f.decorations==steps,"Waiting decoration preserves its step without repeat "+effect);
   state.cleanup.skip(true);f.drain();state=f.states.get(f.states.size()-1);
   System.out.println("WAIT_SKIP="+effect+" PHASE="+f.carry.diskJournal().getInt("phase")+" PENDING="+state.cleanup.pending+" SKIPPED="+state.cleanup.skipped+" REASON="+f.reason());
   check(state.phase.equals(effect)&&state.cleanup.skipped&&f.decorations==steps&&f.carry.diskJournal().getInt("phase")==16&&PurpleSyncChatMoveStore.completed(f.carry.files.root,ORIGIN.accountKey())&&!PurpleSyncOperations.carryHeld(f.carry.operationKey()),"Skip preserves waiting decoration and marks skipped cleanup "+effect);
   f.waitDecoration="";f.pendingDecoration.accept(true);f.drain();state=f.states.get(f.states.size()-1);
   check(state.phase.equals("Complete")&&state.cleanup.skipped&&f.decorations==3,"original decoration callback still completes remaining steps once "+effect);f.close();
  }
 }
 static void changeRecovery()throws Exception{
  for(boolean create:new boolean[]{false,true})for(boolean skip:new boolean[]{false,true}){
   PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();f.start(create,true);
   PurpleSyncChatChange.State before=f.states.get(f.states.size()-1);
   check(before.cleanup!=null&&before.cleanup.pending&&before.cleanup.canAct,"Change has actionable held cleanup "+create+":"+skip);
   int oldCommands=f.carry.commands.size();
   if(skip)before.cleanup.skip(true);else before.cleanup.retry();f.drain();PurpleSyncChatChange.State after=f.states.get(f.states.size()-1);
   int phase=f.carry.diskJournal().getInt("phase");
   System.out.println("CHANGE_RECOVERY CREATE="+create+" SKIP="+skip+" REASON="+after.reason+" JOURNAL_PHASE="+phase+" PENDING="+after.cleanup.pending+" SKIPPED="+after.cleanup.skipped);
   check(skip?phase==16&&after.cleanup.skipped&&PurpleSyncChatMoveStore.completed(f.carry.files.root,ORIGIN.accountKey())&&!PurpleSyncOperations.carryHeld(f.carry.operationKey()):!after.reason.equals("OpeningChanged")&&f.carry.commands.subList(oldCommands,f.carry.commands.size()).stream().anyMatch(c->c.action.equals("CleanupRead")),"Change validates committed journal instead of its pre-move opening "+create+":"+skip);f.close();
  }
 }
 static void firstOpening()throws Exception{
  for(boolean cache:new boolean[]{false,true}){
   PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();
   PurpleSyncChatScreen.Frame old=f.screen.frame;
   PurpleSyncChatService.Binding binding=cache?old.binding:new PurpleSyncChatService.Binding(true,true,O,old.binding.identity,"0".repeat(64));
   PurpleSyncChatCache.Loaded opening=cache?new PurpleSyncChatCache.Loaded(true,bytes(new String(old.opening.bytes,java.nio.charset.StandardCharsets.UTF_8)+"\n"),old.opening.data):old.opening;
   f.screen.frame=new PurpleSyncChatScreen.Frame(ORIGIN,old.status,binding,opening,old.current.info,old.refused);
   f.start(false,true);
   check(f.reason().equals("OpeningChanged"),"fresh start still compares exact opening "+cache+" "+f.reason());
   check(f.carry.effects==0&&!new File(f.carry.files.root,"carry.json").exists(),"changed fresh opening creates no journal or outward effect "+cache);f.close();
  }
 }
 static void changedChangeResume()throws Exception{
  for(boolean create:new boolean[]{false,true})for(boolean skip:new boolean[]{false,true})for(String changed:new String[]{"state","invalidState","cache","journal"}){
   PurpleSyncChatChangeCases.Flow f=new PurpleSyncChatChangeCases.Flow();f.start(create,true);
   PurpleSyncChatChange.State state=f.states.get(f.states.size()-1);int effects=f.carry.effects;
   File path=changed.equals("cache")?f.carry.files.cache:new File(f.carry.files.root,changed.equals("journal")?"carry.json":"state.json");
   byte[] before=Files.readAllBytes(path.toPath());
   if(changed.equals("cache")){
    PurpleSyncChatCache.Loaded loaded=PurpleSyncChatCache.load(PurpleSyncChatCache.file(path),ORIGIN,AUTH);Cache at=loaded.data.location;
    PurpleModernSyncCases.write(path,PurpleSyncChatCache.encode(ORIGIN,AUTH,loaded.data.next(new Cache(at.chat,at.markId+1,at.markDate+1),f.carry.targetInfo)));
   }else PurpleModernSyncCases.write(path,changed.equals("invalidState")||changed.equals("journal")?bytes("{}"):bytes(new String(before,java.nio.charset.StandardCharsets.UTF_8)+"\n"));
   if(skip)state.cleanup.skip(true);else state.cleanup.retry();f.drain();
   PurpleSyncChatChange.State after=f.states.get(f.states.size()-1);
   check(f.carry.effects==effects,"changed resume never deletes or posts "+create+":"+skip+":"+changed);
   check(after.cleanup!=null&&after.cleanup.pending&&!after.cleanup.skipped&&!PurpleSyncChatMoveStore.completed(f.carry.files.root,ORIGIN.accountKey()),"changed real journal/state/cache refuses recovery "+create+":"+skip+":"+changed+":"+after.reason);f.close();
  }
 }
 static void faultRecovery()throws Exception{
  for(int point:new int[]{1,5})for(boolean skip:new boolean[]{false,true})try(Flow q=new Flow()){
   q.stop="CleanupRead";q.start();q.f.opened.fault(point,new File(q.f.files.root,"carry.json"));q.done.accept(q.read(q.deferred));q.f.drain();
   PurpleSyncChatMove.Status state=q.f.statuses.get(q.f.statuses.size()-1);
   check(state.action.equals("Cleanup")&&state.committed!=null&&state.cleanup.pending&&state.cleanup.canAct,"fault presentation retains verified phase/proof "+point+":"+skip);
   q.stop="";if(skip)state.cleanup.skip(true);else state.cleanup.retry();q.f.drain();
   check(q.f.done()&&(skip?q.deleted.isEmpty()&&q.f.diskJournal().getInt("phase")==16:q.deleted.equals(Collections.singletonList(2))),"real-journal recovery completes chosen action once "+point+":"+skip);
  }
 }
 static void invalidDisk()throws Exception{
  for(String name:new String[]{"carry.json","state.json"})for(boolean skip:new boolean[]{false,true})try(Flow q=new Flow()){
   q.stop="CleanupRead";q.start();q.f.opened.fault(1,new File(q.f.files.root,"carry.json"));q.done.accept(q.read(q.deferred));q.f.drain();
   PurpleSyncChatMove.Status state=q.f.statuses.get(q.f.statuses.size()-1);
   PurpleModernSyncCases.write(new File(q.f.files.root,name),bytes("{}"));
   q.stop="";if(skip)state.cleanup.skip(true);else state.cleanup.retry();q.f.drain();
   check(!q.f.done()&&q.deleted.isEmpty(),"remembered proof cannot authorize action over changed invalid disk "+name+":"+skip);
  }
 }
 public static void main(String[] args)throws Exception{
  PurpleCore.ensureLoaded();rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));
  for(JSONObject row:rows)if(row.getString("op").equals("begin")&&row.getJSONObject("expected").getString("status").equals("Valid")){beginning=row.getJSONObject("input");break;}
  base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
  File parent=new File(args[1]);PurpleModernSyncCases.directory(parent);root=new PurpleModernSyncCases.Frame(parent,"routing",true).root;
  waiting();changeRecovery();faultRecovery();invalidDisk();firstOpening();changedChangeResume();System.out.println("CLEANUP_RESUME_CHECKS="+checks);System.out.println("CLEANUP_RESUME_FAILURES="+failures);if(failures!=0)throw new AssertionError("r1 extra failures="+failures);
 }
}
