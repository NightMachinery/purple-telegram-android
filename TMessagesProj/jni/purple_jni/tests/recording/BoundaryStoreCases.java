package org.telegram.messenger.purple;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;
public class BoundaryStoreCases {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static final PurpleRecordingStore.Core CORE=new PurpleRecordingStore.Core(){
  public PurpleRecordingCore.OggFinish finaliseOgg(File f){throw new AssertionError();}
  public PurpleRecordingCore.OggScan scanOgg(File f){throw new AssertionError();}
  public boolean binItemExpired(long b,long n,int k){return false;}
  public PurpleRecordingStore.RoundResult recoverRound(File m,File j,File o){return null;}
 };
 static File unfinished(File source,File dest)throws Exception{
  byte[] bytes=Files.readAllBytes(source.toPath());int at=0;
  while(at+8<=bytes.length){int size=ByteBuffer.wrap(bytes,at,4).getInt();String type=new String(bytes,at+4,4,"US-ASCII");if(type.equals("moov"))break;if(size<8)throw new AssertionError();at+=size;}
  check(at>8&&at<bytes.length,"moov found");Files.write(dest.toPath(),Arrays.copyOf(bytes,at));check(PurpleRecordingFiles.mp4Finished(dest)==0,"pre-moov fixture");return dest;
 }
 public static void main(String[] args)throws Exception{
  File source=new File(args[1]),root=new File(args[2]);root.mkdirs();
  for(boolean error:new boolean[]{false,true}){
   PurpleRecordingManifest manifest=new PurpleRecordingManifest(new File(root,error?"error":"finish"));PurpleRecordingStore store=new PurpleRecordingStore(manifest,CORE,400);
   PaymentStoreCases.Env env=new PaymentStoreCases.Env();PurpleRecordingSessions sessions=new PurpleRecordingSessions(store,env);
   File raw=unfinished(source,new File(root,error?"round_video_error.mp4":"round_video_pending.mp4"));File journal=new File(raw.getPath()+".pjr");Files.write(journal.toPath(),new byte[]{1,2,3});
   long[] target={1,100,8,4,9,1};sessions.roundRegistered(raw,journal);sessions.started("1:7",1,target,false,env.now());sessions.stopped("1:7",5,target);
   boolean rejected=false;try{sessions.completedForQueue("1:7",raw,target);}catch(IOException expected){rejected=true;}
   check(rejected,"unfinished cannot queue");check(sessions.count()==1&&store.bin().isEmpty(),"failed retain is pending");check(store.entries().get(0).awaitingQueue,"durable ownership retained");
   if(error){
    File snapshot=new File(root,"cancel-error.snapshot");check(raw.renameTo(snapshot),"snapshot before V2 erase");sessions.snapshotDone("1:7",snapshot,-1);sessions.stopped("1:7",7,target);
    check(sessions.count()==0,"accepted encode error settled");check(store.bin().size()==1,"error retained even keepDiscarded false");
    PurpleRecordingEntry e=store.bin().get(0);check(e.state.equals(PurpleRecordingEntry.UNRECOVERABLE),"failed recovery retained raw");check(!e.discarded,"error not user discard");check(e.awaitingQueue&&e.userId==9&&e.replyId==4&&e.once,"frozen error metadata");
    check(new File(e.media).length()>0,"raw snapshot bytes retained");check(e.journal!=null&&new File(e.journal).length()==3,"independent journal retained");
    store=new PurpleRecordingStore(manifest,CORE,401);store.recoverAtStart(401);check(store.bin().size()==1,"raw error survives restart");
   }else{
    Files.write(raw.toPath(),Files.readAllBytes(source.toPath()));String id=sessions.completedForQueue("1:7",raw,target);
    check(sessions.count()==0&&store.bin().size()==1,"retry finished same live session");check(store.find(id).awaitingQueue,"not acknowledged by preparation");
    store=new PurpleRecordingStore(manifest,CORE,401);store.recoverAtStart(401);check(store.bin().size()==1,"finished retry survives restart");
   }
  }
  PurpleRecordingManifest manifest=new PurpleRecordingManifest(new File(root,"no-session"));PurpleRecordingStore store=new PurpleRecordingStore(manifest,CORE,400);PurpleRecordingSessions s=new PurpleRecordingSessions(store,new PaymentStoreCases.Env());File missing=unfinished(source,new File(root,"unregistered.mp4"));
  for(int i=0;i<3;i++)try{s.completedForQueue("1:7",missing,new long[]{1,100,0,0,9,0});}catch(IOException expected){}
  check(store.entries().size()==1&&s.count()==1,"retries cannot multiply recovery entries");
  System.out.println("BOUNDARY_STORE_CHECKS="+checks);
 }
}
