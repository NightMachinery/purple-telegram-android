package org.telegram.ui;

import org.telegram.ui.Components.*;
import org.telegram.messenger.*;
import org.telegram.messenger.purple.*;

public class CrossChatCases {
    private static int checks;
    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    private static void start(ChatActivity chat) { check(PurpleRoundCapture.command(chat,0,true,0,0,0,0,0), "managed start"); }

    public static void main(String[] args) throws Exception {
        for (boolean v2 : new boolean[]{false,true}) {
            InstantCameraViewBase.v2=v2;
            LaunchActivity activity=new LaunchActivity();
            ChatActivity a=new ChatActivity(activity,1,10), b=new ChatActivity(activity,2,20), c=new ChatActivity(activity,3,30);
            start(a);
            InstantCameraViewBase camera=InstantCameraViewBase.last;
            Object surface=camera.texture.surface, parent=camera.getParent(), host=parent;
            int created=InstantCameraViewBase.creations;
            check(camera.delegate.getClassGuid()!=a.guid,"session guid independent of fragment");
            check(camera.delegate.getFragmentView()==parent && parent!=a,"native delegate is root owned");
            check(camera.starts==1,"one camera start");
            check(PurpleRecordingRegistry.target.target()[1]==10,"frozen target registered before navigation");
            check(PurpleRoundCapture.leave(a,true),"leave handles native recording panel");
            check(camera.stops==0 && camera.small,"docking releases composer, never capture");
            check(PurpleRoundCapture.detach(a),"source detaches");
            PurpleRoundCapture.attach(b);PurpleRoundCapture.detach(b);
            PurpleRoundCapture.attach(c);PurpleRoundCapture.detach(c);
            check(camera.texture.surface==surface && camera.getParent()==parent,"two switches preserve root and surface");
            check(camera.pauses==0&&camera.stops==0&&camera.starts==1,"navigation does not pause or restart");
            start(b);check(InstantCameraViewBase.creations==created,"second recording cannot create another adapter");
            check(PurpleRoundCapture.command(b,1,true,0,0,0,0,0),"other-chat finish is consumed");
            check(camera.stops==0,"other-chat finish cannot end the original take");
            check(PurpleRoundCapture.command(c,3,true,0,0,0,0,0),"other-chat preview cannot fall through to a new camera");
            check(camera.pauses==0,"other-chat preview cannot change capture state");
            ChatActivity returned=new ChatActivity(activity,4,10);
            PurpleRoundCapture.attach(returned);
            check(!PurpleRoundCapture.detach(a),"old fragment cannot unbind returned screen");
            check(PurpleRoundCapture.manages(returned),"return matches original identity not guid");
            PurpleRoundCapture.togglePause(returned);check(camera.paused,"pause operates root adapter");
            PurpleRoundCapture.togglePause(returned);check(!camera.paused,"resume operates same adapter");
            ChatActivity wrongAccount=new ChatActivity(activity,5,10);wrongAccount.account=1;
            check(!PurpleRoundCapture.manages(wrongAccount),"account switch cannot rebind target");
            check(PurpleRecordingRegistry.target.target()[1]==10,"browsing and compose did not retarget");
            PurpleRoundCapture.command(returned,1,true,0,0,0,0,0);
            PurpleRoundCapture.command(returned,1,true,0,0,0,0,0);
            check(camera.stops==1,"repeated save finalizes once");
            AndroidUtilities.drain();
            check(PurpleRecordingRegistry.retained[1]==10,"retained output belongs to original chat");
            check(PurpleRecordingRegistry.retained[3]==8,"original reply survives detachment");
            check(activity.root.children.isEmpty(),"completed take releases activity root");
            check(PurpleRoundCapture.command(a,4,true,0,0,0,0,0),"late finished-take controls cannot create a stock camera");
            java.lang.reflect.Field textureField=PurpleRoundCapture.class.getDeclaredField("lastTexture");textureField.setAccessible(true);
            check(textureField.get(camera.delegate)==null,"released owner drops Activity-backed preview observations");
            start(a);InstantCameraViewBase next=InstantCameraViewBase.last;
            check(next!=camera,"next take gets new session adapter");
            PurpleRoundCapture.activityPaused(new android.app.Activity());check(next.stops==0,"unrelated activity pause ignored");
            PurpleRoundCapture.activityPaused(activity);AndroidUtilities.drain();
            check(next.stops==2 && activity.root.children.isEmpty(),"background retains draft and closes capture");
            PurpleRoundCapture.attach(a);check(InstantCameraViewBase.last==next,"foreground does not auto-resume");
            start(a);next=InstantCameraViewBase.last;
            PurpleRoundCapture.activityDestroyed(activity);AndroidUtilities.drain();
            check(activity.root.children.isEmpty(),"recreation retains draft without old window");
        }
        PurpleRoundCaptureLease lease=new PurpleRoundCaptureLease(0,100,10,20,0,1000);
        long old=lease.attach(0,100,10,20,0), fresh=lease.attach(0,100,10,20,0);
        check(!lease.detach(old),"stale binding token rejected");
        check(lease.detach(fresh),"current binding token detaches");
        check(lease.attach(0,101,10,20,0)==0,"slot reused by another user rejected");
        check(lease.attach(0,100,10,21,0)==0,"another topic rejected");
        check(lease.attach(0,100,10,20,1)==0,"another chat mode rejected");
        check(lease.finish()&&!lease.finish(),"one finalization reservation");
        check(lease.attach(0,100,10,20,0)==0,"finishing take cannot rebind capture UI");
        lease.end();check(!lease.matches(0,100,10,20,0),"ended lease rejects all bindings");
        System.out.println("CROSS_CHAT_CAPTURE_CHECKS="+checks);
    }
}
