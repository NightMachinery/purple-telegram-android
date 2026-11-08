package org.telegram.messenger.purple;
import java.io.*;import java.nio.file.*;import org.telegram.messenger.*;import org.telegram.ui.Components.AlertsCreator;
public class RetryIdentityBoundaryCases {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static void reset(){UserConfig.identity=9;UserConfig.active=true;AndroidUtilities.defer=false;AndroidUtilities.ui.clear();PurpleRecordingRegistry.workerDefer=false;PurpleRecordingRegistry.pendingDefer=false;PurpleRecordingRegistry.workers.clear();PurpleRecordingRegistry.pending.clear();PurpleRecordingRegistry.bin.clear();PurpleRecordingDelivery.revokeAll();AlertsCreator.pending=null;AlertsCreator.defer=false;MessagesStorage.immediate=true;MessagesStorage.stored=true;MessagesStorage.jobs.clear();MessagesStorage.afterQuery=null;SendMessagesHelper.beforeNative=null;PurpleRecordingFiles.afterCopy=null;}
 static void changed(String step,File root)throws Exception{
  reset();PurpleRecordingEntry item=new PurpleRecordingEntry();File source=new File(root,"source-"+step+".mp4");Files.write(source.toPath(),new byte[]{1,2,3});item.media=source.getAbsolutePath();PurpleRecordingRegistry.bin.add(item.id);
  int sent=SendMessagesHelper.sends,ack=PurpleRecordingRegistry.ack,queries=MessagesStorage.queries;int[] done={0};
  if(step.equals("worker"))PurpleRecordingRegistry.workerDefer=true;
  if(step.equals("copy"))PurpleRecordingFiles.afterCopy=()->UserConfig.identity=10;
  if(step.equals("ui"))AndroidUtilities.defer=true;
  if(step.equals("approval"))AlertsCreator.defer=true;
  if(step.equals("pending"))PurpleRecordingRegistry.pendingDefer=true;
  if(step.equals("native"))SendMessagesHelper.beforeNative=()->UserConfig.identity=10;
  if(step.equals("storage"))MessagesStorage.immediate=false;
  if(step.equals("ack"))MessagesStorage.afterQuery=()->AndroidUtilities.defer=true;
  RetryRoute.send(item,1,100,0,0,true,ok->{if(ok)done[0]++;});
  if(step.equals("worker")){UserConfig.identity=10;PurpleRecordingRegistry.workers();}
  if(step.equals("ui")){UserConfig.identity=10;AndroidUtilities.defer=false;AndroidUtilities.flush();}
  if(step.equals("approval")){UserConfig.identity=10;AlertsCreator.approve();}
  if(step.equals("pending")){UserConfig.identity=10;PurpleRecordingRegistry.pending();}
  if(step.equals("storage")){UserConfig.identity=10;MessagesStorage.flush();check(MessagesStorage.queries==queries,"replacement storage never queried");}
  if(step.equals("ack")){UserConfig.identity=10;AndroidUtilities.defer=false;AndroidUtilities.flush();}
  boolean alreadyQueued=step.equals("storage")||step.equals("ack");
  check(SendMessagesHelper.sends==sent+(alreadyQueued?1:0),"replacement identity cannot prepare/send at "+step);
  check(SendMessagesHelper.sends==sent||SendMessagesHelper.sentUser==9,"successful pre-switch send used original identity");
  check(PurpleRecordingRegistry.bin.contains(item.id),"identity rejection retains recovery at "+step);
  check(PurpleRecordingRegistry.ack==ack&&done[0]==0,"identity rejection cannot ack at "+step);
  check(source.isFile()&&Files.readAllBytes(source.toPath()).length==3,"source survives at "+step);
  if(step.equals("storage")){
   UserConfig.identity=9;MessagesStorage.immediate=true;RetryRoute.send(item,1,100,0,0,true,ok->{});
   check(PurpleRecordingRegistry.bin.contains(item.id),"authorized retry retains bin copy");
  }
 }
 public static void main(String[] args)throws Exception{
  File root=new File(args[0]);root.mkdirs();FileLoader.directory=root;
  for(String step:new String[]{"ui","worker","copy","approval","pending","native","storage","ack"})changed(step,root);
  reset();UserConfig.identity=11;PurpleRecordingEntry item=new PurpleRecordingEntry();File source=new File(root,"alternate.mp4");Files.write(source.toPath(),new byte[]{7});item.media=source.getAbsolutePath();PurpleRecordingRegistry.bin.add(item.id);
  int sent=SendMessagesHelper.sends;RetryRoute.send(item,1,100,0,0,false,ok->{});
  check(SendMessagesHelper.sends==sent+1&&SendMessagesHelper.sentUser==11,"explicit alternate retry binds selected identity");check(PurpleRecordingRegistry.bin.contains(item.id),"actual queue ack retains alternate bin owner");check(source.isFile(),"alternate outgoing copy preserves source");
  reset();UserConfig.active=false;PurpleRecordingRegistry.bin.add(item.id);sent=SendMessagesHelper.sends;RetryRoute.send(item,1,100,0,0,true,ok->{});check(SendMessagesHelper.sends==sent&&PurpleRecordingRegistry.bin.contains(item.id),"inactive original account rejected");
  System.out.println("RETRY_IDENTITY_CHECKS="+checks);
 }
}
