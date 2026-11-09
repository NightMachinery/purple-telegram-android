package org.telegram.ui;

import org.telegram.messenger.*;
import org.telegram.messenger.purple.*;
import org.telegram.ui.Components.*;
import org.telegram.utils.recorder.round.RoundVideoSession;

public class V2InterruptionCases {
    private static int checks;
    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    private static InstantCameraView2 start(LaunchActivity activity) {
        InstantCameraViewBase.v2=true;
        ChatActivity chat=new ChatActivity(activity,1,10);
        check(PurpleRoundCapture.command(chat,0,true,0,0,0,0,0),"managed start");
        return (InstantCameraView2)InstantCameraViewBase.last;
    }
    public static void main(String[] args) {
        for (RoundVideoSession.State transition : new RoundVideoSession.State[]{RoundVideoSession.State.RESUMING,RoundVideoSession.State.PAUSING}) {
            for (boolean background : new boolean[]{false,true}) {
                LaunchActivity activity=new LaunchActivity();
                InstantCameraView2 camera=start(activity);
                camera.emit(transition);
                check(!camera.session.finish(true),"actual native finish rejects transition");
                int retained=PurpleRecordingRegistry.retains;
                for(int repeat=0;repeat<3;repeat++) {
                    if(background)PurpleRoundCapture.activityPaused(activity);
                    else PurpleRoundCapture.command(new ChatActivity(activity,2,10),1,true,0,0,0,0,0);
                }
                check(!PurpleRecordingDock.roundFinalization,"deferred native finish must not reserve finalization");
                check(camera.session.accepted==0,"no finish accepted in transition");
                PurpleRoundCapture.togglePause(new ChatActivity(activity,3,10));
                check(camera.pauses==0,"pending finish consumes further capture controls");
                camera.emit(transition==RoundVideoSession.State.RESUMING?RoundVideoSession.State.RECORDING:RoundVideoSession.State.PREVIEWING);
                camera.onStateChanged(camera.stateInfo);
                AndroidUtilities.drain();
                check(camera.session.accepted==1,"one native accepted finish after transition");
                check(!camera.session.cameraLive&&!camera.session.micLive&&!camera.session.player,"native resources stopped after pending preservation");
                check(PurpleRecordingRegistry.retains==retained+1,"one local draft despite repeated Save/interruption");
                check(PurpleRecordingRegistry.retained[1]==10&&PurpleRecordingRegistry.retained[3]==8,"original target and reply retained");
                check(activity.root.children.isEmpty()&&!PurpleRecordingDock.roundFinalization,"owner root and finalization released");
                int created=InstantCameraViewBase.creations;
                PurpleRoundCapture.attach(new ChatActivity(activity,4,10));
                check(InstantCameraViewBase.creations==created,"foreground never automatically resumes");
            }
            for(RoundVideoSession.State terminal:new RoundVideoSession.State[]{RoundVideoSession.State.ERROR,RoundVideoSession.State.RELEASED}) {
                for(boolean queued:new boolean[]{false,true}) {
                    LaunchActivity activity=new LaunchActivity();
                    InstantCameraView2 old=start(activity);
                    old.emit(transition);PurpleRoundCapture.activityPaused(activity);
                    if(queued)old.emit(RoundVideoSession.State.RECORDING);
                    old.emit(terminal);AndroidUtilities.drain();
                    check(old.session.accepted==0,"terminal callback fences queued preservation");
                    check(activity.root.children.isEmpty(),"terminal transition releases pending owner");
                    InstantCameraView2 next=start(activity);
                    old.onStateChanged(new RoundVideoSession.StateInfo(terminal));
                    old.emit(RoundVideoSession.State.RECORDING);AndroidUtilities.drain();
                    check(next.starts==1&&!activity.root.children.isEmpty(),"late old callback cannot close a new take");
                    PurpleRoundCapture.activityPaused(activity);AndroidUtilities.drain();
                    check(activity.root.children.isEmpty(),"next take remains finishable");
                }
            }
        }
        check(NotificationCenter.resumes>0,"actual adapter routes resumed state through recordResumed");
        System.out.println("V2_INTERRUPTION_CHECKS="+checks);
    }
}
