package org.telegram.ui;
import org.telegram.messenger.*;import org.telegram.ui.Components.AlertsCreator;
public class PaymentNextCaptureCases {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("capture generation check "+checks);}
 public static void main(String[] args){
  ChatActivity c=new ChatActivity();PurpleRoundRecording r=new PurpleRoundRecording(c);
  MessagesStorage.immediate=false;MessagesStorage.stored=true;AlertsCreator.defer=false;
  r.started();r.beginFinalization();r.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0);
  check(c.cleanup==0);
  r.started();c.backdrop=true;MessagesStorage.flush();
  check(c.cleanup==0);check(c.backdrop);
  ChatActivity next=new ChatActivity();PurpleRoundRecording old=new PurpleRoundRecording(next);
  old.started();old.beginFinalization();old.sendMedia(new MediaController.PhotoEntry(),new VideoEditedInfo(),true,0,0,0);
  check(next.cleanup==0);
  check(org.telegram.messenger.purple.PurpleRecordingDock.state.begin(1,7,9,100,0,false,false,0));
  MessagesStorage.flush();check(next.cleanup==0);
  check(org.telegram.messenger.purple.PurpleRecordingDock.state.active()&&!org.telegram.messenger.purple.PurpleRecordingDock.state.round);
  System.out.println("PAYMENT_CAPTUREGEN_CHECKS="+checks);
 }
}
