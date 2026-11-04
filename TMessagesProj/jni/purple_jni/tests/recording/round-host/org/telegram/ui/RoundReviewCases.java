package org.telegram.ui;
import org.telegram.messenger.*;
import org.telegram.ui.Components.PurpleRoundBubble;
import org.telegram.ui.Components.AlertsCreator;
public class RoundReviewCases {
 static int checks;
 static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
 static void begin(PurpleRoundRecording r) throws Exception {
  try { PurpleRoundRecording.class.getDeclaredMethod("beginFinalization").invoke(r); }
  catch (NoSuchMethodException baseline) { }
 }
 public static void main(String[] args) throws Exception {
  ChatActivity c = new ChatActivity(); PurpleRoundRecording r = new PurpleRoundRecording(c);
  r.started();
  if (args[0].equals("backdrop")) {
   check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0));
   check(SendMessagesHelper.sends==1); check(!c.backdrop); check(c.cleanup==1);
   r.started(); PurpleRoundBubble.small=true; r.resized(true);
   check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0));
   check(!c.backdrop); check(c.cleanup==1);
  } else if (args[0].equals("destroy")) {
   begin(r); r.destroy();
   check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0));
   check(SendMessagesHelper.sends==1);
   check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0));
   check(SendMessagesHelper.sends==1);
   r.started(); r.abandon();
   check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0)); check(SendMessagesHelper.sends==1);
  }
  if (args[0].equals("paid")) {
   AlertsCreator.defer=true; begin(r);
   check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0));
   check(!c.backdrop); check(c.cleanup==0); check(SendMessagesHelper.sends==0);
   AlertsCreator.approve(); check(c.cleanup==1); check(SendMessagesHelper.sends==1);
   r.started(); begin(r);
   check(r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0));
   r.destroy(); AlertsCreator.approve();
   check(SendMessagesHelper.sends==2); check(c.cleanup==1); check(c.instantCameraView.hidden==2);
  }
  System.out.println("ROUND_"+args[0].toUpperCase()+"_CHECKS="+checks);
 }
}
