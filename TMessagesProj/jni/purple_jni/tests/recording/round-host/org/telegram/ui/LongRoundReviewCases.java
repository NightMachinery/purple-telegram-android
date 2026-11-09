package org.telegram.ui;
import org.telegram.messenger.*;import org.telegram.messenger.purple.*;
public class LongRoundReviewCases {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  ChatActivity chat=new ChatActivity();PurpleRoundRecording recording=new PurpleRoundRecording(chat);recording.started();
  MediaController.PhotoEntry media=new MediaController.PhotoEntry();media.duration=1;PurpleRecordingRegistry.durationMs=180000;
  VideoEditedInfo info=new VideoEditedInfo();info.estimatedDuration=1000;
  check(recording.sendMedia(media,info,true,0,0,0),"long completion intercepted");
  check(PurpleLongRoundSend.reviews==1&&SendMessagesHelper.sends==0,"full retained duration overrides short preview trim before any send");
  check(PurpleRecordingRegistry.bin.size()==1,"full original retained before review");
  check(chat.instantCameraView.hidden==1,"native recorder released before preparation");
  recording.destroy();
  PurpleLongRoundSend.sender.send(new PurpleLongRoundMedia.Output(),PurpleLongRoundSend.arguments,0);
  check(SendMessagesHelper.last.peer==100,"long send closure keeps captured target");
  check(PurpleRecordingRegistry.bin.size()==1,"derived copy cannot acknowledge original away");
  System.out.println("LONG_ROUND_REVIEW_CHECKS="+checks);
 }
}
