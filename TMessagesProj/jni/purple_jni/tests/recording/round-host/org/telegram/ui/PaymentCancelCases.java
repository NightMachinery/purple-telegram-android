package org.telegram.ui;
import org.telegram.messenger.*;import org.telegram.messenger.purple.*;import org.telegram.ui.Components.AlertsCreator;
import java.lang.reflect.Field;
public class PaymentCancelCases {
 static int checks;
 static void check(boolean v){checks++;if(!v)throw new AssertionError("payment check "+checks);}
 static Object get(PurpleRoundRecording r,String n)throws Exception{Field f=PurpleRoundRecording.class.getDeclaredField(n);f.setAccessible(true);return f.get(r);}
 static void cancelled(String how)throws Exception{
  ChatActivity c=new ChatActivity();PurpleRoundRecording r=new PurpleRoundRecording(c);
  int sent=SendMessagesHelper.sends,ended=PurpleRecordingDock.ended,binned=PurpleRecordingRegistry.bin.size();
  check(PurpleRecordingDock.state.begin(1,7,9,100,0,false,false,0));PurpleRecordingDock.state.round=true;
  r.started();r.beginFinalization();PurpleRecordingDock.state.finishing=true;AlertsCreator.defer=true;
  check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,1));
  check(AlertsCreator.pending!=null);check(SendMessagesHelper.sends==sent);
  check(get(r,"finalizing")==null);check((Boolean)get(r,"completionReceived"));
  check(PurpleRecordingDock.ended==ended+1);check(!PurpleRecordingDock.state.active());
  check(PurpleRecordingRegistry.bin.size()==binned+1);
  if(how.equals("stars")){AlertsCreator.starsNeeded=true;AlertsCreator.approve();check(AlertsCreator.starsPending!=null);AlertsCreator.starsPending=null;AlertsCreator.starsNeeded=false;}
  else {AlertsCreator.pending=null;check(PurpleRecordingRegistry.bin.size()==binned+1);}
  r.destroy();check(get(r,"finalizing")==null);check(PurpleRecordingRegistry.bin.size()==binned+1);
  check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,1));check(SendMessagesHelper.sends==sent);
 }
 public static void main(String[] args)throws Exception{
  cancelled("cancel");cancelled("dismiss");cancelled("stars");
  ChatActivity c=new ChatActivity();PurpleRoundRecording r=new PurpleRoundRecording(c);r.started();r.beginFinalization();
  int bins=PurpleRecordingRegistry.bin.size(),sent=SendMessagesHelper.sends,acks=PurpleRecordingRegistry.acknowledged;
  MessagesStorage.immediate=false;MessagesStorage.stored=false;AlertsCreator.defer=true;
  r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,1);
  java.util.function.LongConsumer approval=AlertsCreator.pending;AlertsCreator.pending=null;
  check(PurpleRecordingRegistry.bin.size()==bins+1);check(c.cleanup==0);
  approval.accept(2);check(SendMessagesHelper.sends==sent+1);check(c.cleanup==0);check(!PurpleRecordingDelivery.allowed(SendMessagesHelper.last.sendMessageChatArguments));
  approval.accept(2);check(SendMessagesHelper.sends==sent+1);
  check(PurpleRecordingRegistry.acknowledged==acks);MessagesStorage.flush();
  check(PurpleRecordingRegistry.bin.size()==bins+1);check(c.cleanup==0);
  final SendMessageChatArguments old=SendMessagesHelper.last.sendMessageChatArguments;
  SendMessageChatArguments retry=new SendMessageChatArguments();String id="item"+PurpleRecordingRegistry.retained;
  PurpleRecordingDelivery.send(id,1,100,retry,()->{
   SendMessagesHelper.SendMessageParams params=new SendMessagesHelper.SendMessageParams();params.peer=100;params.sendMessageChatArguments=retry;
   org.telegram.tgnet.TLRPC.Message msg=new org.telegram.tgnet.TLRPC.Message();msg.id=-33;
   PurpleRecordingDelivery.queued(1,params,msg);
  },()->c.cleanup++);
  check(!PurpleRecordingDelivery.allowed(old));check(c.cleanup==0);
  MessagesStorage.stored=true;MessagesStorage.flush();check(c.cleanup==1);check(PurpleRecordingRegistry.bin.size()==bins);
  check(PurpleRecordingRegistry.acknowledged==acks+1);
  MessagesStorage.flush();check(c.cleanup==1);
  r.started();r.beginFinalization();r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,1);
  AlertsCreator.approve();r.destroy();MessagesStorage.flush();check(c.cleanup==1);check(PurpleRecordingRegistry.bin.size()==bins);
  r.started();r.beginFinalization();MediaController.PhotoEntry previous=new MediaController.PhotoEntry();
  r.sendMedia(previous,new VideoEditedInfo(),true,0,0,1);AlertsCreator.approve();
  c.chatActivityEnterView.text="new draft";int cleanup=c.cleanup;MessagesStorage.flush();check(c.cleanup==cleanup);
  int retained=PurpleRecordingRegistry.retained;r.started();
  check(r.sendMedia(previous,new VideoEditedInfo(),true,0,0,1));check(PurpleRecordingRegistry.retained==retained);
  MessagesStorage.immediate=true;
  System.out.println("PAYMENT_CANCEL_CHECKS="+checks);
 }
}
