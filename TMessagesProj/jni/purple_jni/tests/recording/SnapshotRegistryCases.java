package org.telegram.messenger.purple;
import java.io.*;import java.nio.file.*;import java.util.*;
public class SnapshotRegistryCases {
 static int checks,cases;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static long[] target={1,100,8,4,9,1};static File full,root;
 static final PurpleRecordingStore.Core CORE=new PurpleRecordingStore.Core(){
  public PurpleRecordingCore.OggFinish finaliseOgg(File f){return new PurpleRecordingCore.OggFinish(new long[]{0,1000,0,1,0});}
  public PurpleRecordingCore.OggScan scanOgg(File f){return new PurpleRecordingCore.OggScan(new long[]{1,1,0,f.length(),f.length(),1000,1,1});}
  public boolean binItemExpired(long b,long n,int k){return false;}
  public PurpleRecordingStore.RoundResult recoverRound(File m,File j,File o){return null;}
 };
 static class Env extends PaymentStoreCases.Env{boolean keep=true;public boolean keepDiscarded(){return keep;}}
 static class Setup{
  File dir,raw,journal;PurpleRecordingManifest manifest;PurpleRecordingStore store;PurpleRecordingSessions sessions;Env env;String id;byte[] bytes;
  Setup(boolean start)throws Exception{
   dir=new File(root,"case-"+(cases++));dir.mkdirs();manifest=new PurpleRecordingManifest(new File(dir,"registry"));store=new PurpleRecordingStore(manifest,CORE,400);env=new Env();sessions=new PurpleRecordingSessions(store,env);
   raw=BoundaryStoreCases.unfinished(full,new File(dir,"round_video_a.mp4"));journal=new File(raw.getPath()+".pjr");Files.write(journal.toPath(),new byte[]{3,1,4});bytes=Files.readAllBytes(raw.toPath());
   sessions.roundRegistered(raw,journal);if(start)start();
   RegistryStopRoute.sessions=sessions;RegistryStopRoute.root=manifest.dir;RegistryStopRoute.context=target;RegistryStopRoute.worker.tasks.clear();
  }
  void start(){sessions.started("1:7",1,target,false,env.now());id=store.entries().get(0).id;}
  PurpleRecordingStore restart()throws Exception{SnapshotProbe.armed=null;PurpleRecordingStore recovered=new PurpleRecordingStore(manifest,CORE,401);recovered.recoverAtStart(401);return recovered;}
 }
 static void owned(PurpleRecordingStore store,String id,byte[] bytes,byte[] journal)throws Exception{
  PurpleRecordingEntry e=store.find(id);check(e!=null&&e.inBin(),"snapshot has original visible owner");check(Arrays.equals(bytes,Files.readAllBytes(new File(e.media).toPath())),"owner retains exact original bytes");
  check(e.journal!=null&&new File(e.journal).isFile(),"original journal retained");check(Arrays.equals(journal,Files.readAllBytes(new File(e.journal).toPath())),"journal belongs to original capture");
 }
 static void crash(String point)throws Exception{
  boolean reservation=point.contains("reservation");Setup s=new Setup(!reservation);SnapshotProbe.armed=point;int hits=SnapshotProbe.hits;
  try{if(reservation)s.start();else{RegistryStopRoute.stop(1,7,7);if(!RegistryStopRoute.worker.tasks.isEmpty())RegistryStopRoute.worker.tasks.get(0).run();}}catch(SnapshotProbe.Crash expected){}
  check(SnapshotProbe.hits==hits+1,"actual production boundary reached: "+point);
  String id=s.id==null?s.store.entries().get(0).id:s.id;owned(s.restart(),id,s.bytes,new byte[]{3,1,4});
 }
 static void replacement(boolean voice,boolean reverse,boolean crashBefore)throws Exception{
  Setup s=new Setup(true);String a=s.id;RegistryStopRoute.stop(1,7,7);check(RegistryStopRoute.worker.tasks.size()==1,"actual registry schedules exactly one stop");Runnable old=RegistryStopRoute.worker.tasks.get(0);
  RegistryStopRoute.stop(1,7,7);check(RegistryStopRoute.worker.tasks.size()==1,"duplicate stop claims one original token");
  File b=new File(s.dir,voice?"voice-b.ogg":"round_video_b.mp4");byte[] bytes=s.bytes.clone();bytes[bytes.length-1]^=1;Files.write(b.toPath(),bytes);File bj=new File(b.getPath()+".pjr");Files.write(bj.toPath(),new byte[]{2,7});
  if(!voice)s.sessions.roundRegistered(b,bj);
  s.sessions.started("1:7",1,target,voice,s.env.now()+1);
  if(voice)s.sessions.didSent("1:7",true,b.getPath(),1,target);
  PurpleRecordingEntry newer=null;for(PurpleRecordingEntry e:s.store.entries())if(!e.id.equals(a))newer=e;
  check(newer!=null&&!newer.id.equals(a),"new capture gets independent entry identity");String bid=newer.id;
  check(s.store.find(a)!=null,"old owner survives replacement before callback");check(s.journal.isFile(),"replacement preserves old journal");
  if(crashBefore){PurpleRecordingStore recovered=s.restart();owned(recovered,a,s.bytes,new byte[]{3,1,4});PurpleRecordingEntry e=recovered.find(bid);check(e!=null&&e.inBin(),"new owner survives restart before old callback");check(Arrays.equals(bytes,Files.readAllBytes(new File(e.media).toPath())),"restart cannot exchange owner bytes");return;}
  if(reverse){RegistryStopRoute.stop(1,7,7);check(RegistryStopRoute.worker.tasks.size()==2,"new capture has separate delayed callback");Runnable recent=RegistryStopRoute.worker.tasks.get(1);recent.run();recent.run();old.run();old.run();check(s.sessions.count()==0,"reverse/duplicate stops settle each original once");}
  else{old.run();old.run();check(s.sessions.count()==1,"old callbacks cannot remove newer round or voice");check(s.store.find(bid)!=null&&s.store.find(bid).media.equals(b.getAbsolutePath()),"newer source remains manifest-owned");check(b.isFile()&&Arrays.equals(bytes,Files.readAllBytes(b.toPath())),"new bytes are untouched");}
  owned(s.store,a,s.bytes,new byte[]{3,1,4});PurpleRecordingStore recovered=s.restart();owned(recovered,a,s.bytes,new byte[]{3,1,4});
  PurpleRecordingEntry e=recovered.find(bid);check(e!=null&&e.inBin(),"new owner survives restart");check(Arrays.equals(bytes,Files.readAllBytes(new File(e.media).toPath())),"new owner never receives old bytes");
 }
 static void claimBeforeRename()throws Exception{
  Setup s=new Setup(true);File b=new File(s.dir,"round_video_before_rename.mp4");byte[] bytes=s.bytes.clone();bytes[bytes.length-1]^=1;Files.write(b.toPath(),bytes);File bj=new File(b.getPath()+".pjr");Files.write(bj.toPath(),new byte[]{8});String[] bid={null};
  SnapshotProbe.onBoundary=()->{s.sessions.roundRegistered(b,bj);s.sessions.started("1:7",1,target,false,s.env.now()+1);for(PurpleRecordingEntry e:s.store.entries())if(!e.id.equals(s.id))bid[0]=e.id;};
  SnapshotProbe.armed="before-rename";try{RegistryStopRoute.stop(1,7,7);}catch(SnapshotProbe.Crash expected){}finally{SnapshotProbe.onBoundary=null;}
  check(s.store.find(s.id)!=null&&s.journal.exists(),"claimed original preserved before filesystem invalidation");PurpleRecordingStore recovered=s.restart();owned(recovered,s.id,s.bytes,new byte[]{3,1,4});check(recovered.find(bid[0])!=null,"replacement owner survives claim-before-rename crash");
 }
 static void policy(int reason,boolean keep,boolean restart)throws Exception{
  Setup s=new Setup(true);s.env.keep=keep;RegistryStopRoute.stop(1,7,reason);check(RegistryStopRoute.worker.tasks.size()==1,"policy uses actual scheduled handoff");
  PurpleRecordingStore result;
  if(restart)result=s.restart();else{Runnable callback=RegistryStopRoute.worker.tasks.get(0);callback.run();callback.run();result=s.store;}
  if(reason==7||keep){owned(result,s.id,s.bytes,new byte[]{3,1,4});check(result.find(s.id).discarded==(reason!=7),"accepted error and ordinary discard labels/policy remain distinct");}
  else check(result.find(s.id)==null&&result.bin().isEmpty(),"keep-off discard never becomes retained error");
 }
 public static void main(String[] args)throws Exception{
  full=new File(args[0]);root=new File(args[1]);root.mkdirs();
  crash("after-rename");crash("before-rename");crash("before-reservation");crash("after-reservation");crash("before-manifest");crash("after-manifest");
  claimBeforeRename();replacement(false,false,false);replacement(true,false,false);replacement(false,true,false);replacement(false,false,true);replacement(true,false,true);
  for(int reason:new int[]{0,6,7})for(boolean keep:new boolean[]{false,true})for(boolean restart:new boolean[]{false,true})policy(reason,keep,restart);
  Setup s=new Setup(true);RegistryStopRoute.stop(1,7,7);SnapshotProbe.armed="before-manifest";PurpleRecordingStore recovering=new PurpleRecordingStore(s.manifest,CORE,401);
  try{recovering.recoverAtStart(401);}catch(SnapshotProbe.Crash expected){}owned(s.restart(),s.id,s.bytes,new byte[]{3,1,4});
  s=new Setup(true);RegistryStopRoute.stop(1,7,7);SnapshotProbe.armed="after-manifest";recovering=new PurpleRecordingStore(s.manifest,CORE,401);
  try{recovering.recoverAtStart(401);}catch(SnapshotProbe.Crash expected){}owned(s.restart(),s.id,s.bytes,new byte[]{3,1,4});
  System.out.println("SNAPSHOT_REGISTRY_CHECKS="+checks);
 }
}
