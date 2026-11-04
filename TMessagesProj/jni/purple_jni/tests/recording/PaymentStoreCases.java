package org.telegram.messenger.purple;
import java.io.*;import java.nio.file.*;import java.util.*;
public class PaymentStoreCases {
 static int checks;
 static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static void pending(PurpleRecordingEntry e,boolean value)throws Exception{
  try{e.getClass().getField("awaitingQueue").setBoolean(e,value);}catch(NoSuchFieldException old){}
 }
 static boolean pending(PurpleRecordingEntry e)throws Exception{
  try{return e.getClass().getField("awaitingQueue").getBoolean(e);}catch(NoSuchFieldException old){return false;}
 }
 static String completed(PurpleRecordingSessions s,String key,File media,long[] target)throws Exception{
  return (String)s.getClass().getMethod("completedForQueue",String.class,File.class,long[].class).invoke(s,key,media,target);
 }
 static final PurpleRecordingStore.Core CORE=new PurpleRecordingStore.Core(){
  public PurpleRecordingCore.OggFinish finaliseOgg(File f){throw new AssertionError();}
  public PurpleRecordingCore.OggScan scanOgg(File f){throw new AssertionError();}
  public boolean binItemExpired(long b,long n,int k){return false;}
  public PurpleRecordingStore.RoundResult recoverRound(File m,File j,File o){throw new AssertionError("finished movie must not be remuxed");}
 };
 static class Env implements PurpleRecordingSessions.Env{
  List<Runnable> later=new ArrayList<>();public long now(){return 400000;}
  public void schedule(Runnable r,long d){later.add(r);}public File newestVoice(long n){return null;}
  public boolean keepDiscarded(){return false;}public void flushJournal(String p){}public void closeJournal(String p){}
  public void changed(){}public void discarded(String k,boolean a,boolean b){}
  void tick(){List<Runnable> run=new ArrayList<>(later);later.clear();for(Runnable r:run)r.run();}
 }
 public static void main(String[] args)throws Exception{
  int offset=args.length==3&&args[0].equals("payment-store")?1:0;
  File source=new File(args[offset]),root=new File(args[offset+1]);root.mkdirs();byte[] bytes=Files.readAllBytes(source.toPath());
  check(PurpleRecordingFiles.mp4Finished(source)==1,"fixture finished");
  PurpleRecordingManifest manifest=new PurpleRecordingManifest(new File(root,"legacy"));
  PurpleRecordingStore store=new PurpleRecordingStore(manifest,CORE,400);
  PurpleRecordingEntry old=store.begin(1,100,8,4,PurpleRecordingEntry.ROUND,1000);store.attach(old,source,null);store.sending(old);
  check(store.recoverAtStart(401).finishedCleanly==1,"legacy inferred ownership unchanged");
  manifest=new PurpleRecordingManifest(new File(root,"pending"));store=new PurpleRecordingStore(manifest,CORE,400);
  File movie=new File(root,"pending.mp4");Files.copy(source.toPath(),movie.toPath());
  PurpleRecordingEntry entry=store.begin(1,100,8,4,PurpleRecordingEntry.ROUND,2000);pending(entry,true);entry.userId=9;entry.once=true;store.attach(entry,movie,null);store.sending(entry);
  store=new PurpleRecordingStore(manifest,CORE,401);
  check(store.entries().size()==1,"manifest saved");
  check(store.recoverAtStart(401).finishedCleanly==0,"unqueued completed movie survives restart");
  check(store.bin().size()==1,"completed file recovered into bin");entry=store.bin().get(0);
  check(pending(entry),"pending queue flag roundtrip");check(entry.dialogId==100&&entry.topicId==8&&entry.replyId==4&&entry.userId==9&&entry.once,"frozen recovery context");
  check(Arrays.equals(bytes,Files.readAllBytes(new File(entry.media).toPath())),"restart keeps every completed byte");
  manifest=new PurpleRecordingManifest(new File(root,"session"));store=new PurpleRecordingStore(manifest,CORE,400);Env env=new Env();PurpleRecordingSessions sessions=new PurpleRecordingSessions(store,env);
  File raw=new File(root,"round_video_pending.mp4");Files.copy(source.toPath(),raw.toPath());File journal=new File(raw.getPath()+".pjr");
  sessions.roundRegistered(raw,journal);long[] target={1,100,8,4,9,1};sessions.started("1:7",1,target,false,env.now());
  check(pending(store.entries().get(0)),"round starts with durable pending ownership");
  sessions.stopped("1:7",5,target);env.tick();check(store.entries().size()==1&&sessions.count()==1,"clean MP4 is not queue acknowledgment");
  File completed=new File(root,"trimmed-result.mp4");Files.copy(source.toPath(),completed.toPath());String id=completed(sessions,"1:7",completed,target);
  check(sessions.count()==0,"completed owner detached from fragment session");check(store.bin().size()==1,"durable completed bin available before approval");
  entry=store.find(id);check(pending(entry)&&entry.inBin(),"pending completed item persisted");
  check(entry.durationMs>0,"completed bin item has playable duration");
  check(completed.isFile()&&raw.isFile(),"native source paths not renamed away");
  check(!entry.media.equals(completed.getAbsolutePath()),"bin owns an independent file");
  check(Arrays.equals(bytes,Files.readAllBytes(new File(entry.media).toPath())),"completed copy exact");
  store=new PurpleRecordingStore(manifest,CORE,402);store.recoverAtStart(402);check(store.bin().size()==1,"Cancel/dismiss/Stars dismissal survives process restart");
  File outgoing=new File(root,"outgoing-copy.mp4");PurpleRecordingFiles.copy(new File(store.find(id).media),outgoing);
  check(store.bin().size()==1,"prepare alone retains recovery owner");
  store.sent(store.find(id));check(store.entries().isEmpty(),"queue acknowledgment removes manifest");
  check(outgoing.isFile()&&Arrays.equals(bytes,Files.readAllBytes(outgoing.toPath())),"acknowledgment cannot erase upstream copy");
  store=new PurpleRecordingStore(manifest,CORE,403);store.recoverAtStart(403);check(store.bin().isEmpty(),"acknowledged item does not return on restart");
  System.out.println("PAYMENT_STORE_CHECKS="+checks);
 }
}
