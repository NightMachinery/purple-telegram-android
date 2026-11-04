package org.telegram.ui;
import org.telegram.messenger.*;import org.telegram.messenger.purple.*;import org.telegram.ui.Components.AlertsCreator;
import java.lang.reflect.*;
public class CompletionBoundaryCases {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError("completion boundary "+checks);}
 static Object field(PurpleRoundRecording r,String n)throws Exception{Field f=PurpleRoundRecording.class.getDeclaredField(n);f.setAccessible(true);return f.get(r);}
 static void drain(){for(int i=0;i<140&&!AndroidUtilities.later.isEmpty();i++)AndroidUtilities.tick();check(AndroidUtilities.later.isEmpty());}
 static void sequence(boolean legacy,boolean duplicate,boolean destroy)throws Exception{
  PurpleRecordingDelivery.revokeAll();PurpleRecordingRegistry.incomplete=true;AlertsCreator.defer=true;
  int sent=SendMessagesHelper.sends,attempts=PurpleRecordingRegistry.attempts,bin=PurpleRecordingRegistry.bin.size();
  ChatActivity c=new ChatActivity();PurpleRoundRecording r=new PurpleRoundRecording(c);r.started();r.beginFinalization();
  PurpleRecordingDock.state.begin(1,7,9,100,0,false,false,0);PurpleRecordingDock.state.round=true;PurpleRecordingDock.state.finishing=true;
  MediaController.PhotoEntry entry=new MediaController.PhotoEntry();VideoEditedInfo info=new VideoEditedInfo();info.notReadyYet=legacy;
  check(r.sendMedia(entry,info,true,0,0,0));
  check(!(Boolean)field(r,"completionReceived"));check(field(r,"finalizing")!=null);check(PurpleRecordingRegistry.bin.size()==bin);check(SendMessagesHelper.sends==sent);
  if(duplicate){check(r.sendMedia(entry,info,true,0,0,0));check(PurpleRecordingRegistry.bin.size()==bin);}
  if(destroy)r.destroy();
  AndroidUtilities.tick();check(PurpleRecordingRegistry.bin.size()==bin);check(SendMessagesHelper.sends==sent);
  if(legacy)check(PurpleRecordingRegistry.attempts==attempts);
  info.notReadyYet=false;PurpleRecordingRegistry.incomplete=false;drain();
  check((Boolean)field(r,"completionReceived"));check(field(r,"finalizing")==null);check(!PurpleRecordingDock.state.active());
  check(PurpleRecordingRegistry.bin.size()==bin+1);check(SendMessagesHelper.sends==sent);check(AlertsCreator.pending!=null);
  AlertsCreator.approve();check(SendMessagesHelper.sends==sent+1);check(PurpleRecordingRegistry.bin.size()==bin);
  check(r.sendMedia(entry,info,true,0,0,0));check(SendMessagesHelper.sends==sent+1);
 }
 static void failure(boolean legacy)throws Exception{
  PurpleRecordingRegistry.incomplete=true;AlertsCreator.pending=null;AlertsCreator.defer=true;
  int sent=SendMessagesHelper.sends,failed=PurpleRecordingRegistry.failed,bin=PurpleRecordingRegistry.bin.size();
  ChatActivity c=new ChatActivity();PurpleRoundRecording r=new PurpleRoundRecording(c);r.started();r.beginFinalization();
  VideoEditedInfo info=new VideoEditedInfo();info.notReadyYet=legacy;
  r.sendMedia(new MediaController.PhotoEntry(),info,true,0,0,0);r.destroy();drain();
  check(PurpleRecordingRegistry.failed==failed+1);check(PurpleRecordingRegistry.bin.size()==bin+1);check(SendMessagesHelper.sends==sent);
  check(AlertsCreator.pending==null);check(field(r,"finalizing")==null);check((Boolean)field(r,"completionReceived"));
  info.notReadyYet=false;PurpleRecordingRegistry.incomplete=false;drain();check(SendMessagesHelper.sends==sent);
 }
 public static void main(String[] args)throws Exception{
  sequence(true,false,false);sequence(true,true,true);sequence(false,true,false);sequence(false,false,true);
  failure(true);failure(false);
  System.out.println("COMPLETION_BOUNDARY_CHECKS="+checks);
 }
}
