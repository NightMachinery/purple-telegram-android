package org.telegram.ui;

import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.*;
import org.telegram.utils.settings.SharedSettings;

public class ConstructionCases {
    private static int checks;
    private static Theme.ResourcesProvider first;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    public static void main(String[] args) {
        for(boolean dark:new boolean[]{false,true})for(boolean v2:new boolean[]{false,true})for(boolean explicit:new boolean[]{false,true}) {
            Theme.dark=dark;Theme.shift=10;
            SharedSettings.roundVideoCamera2Enabled.set=explicit;
            SharedSettings.roundVideoCamera2Enabled.value=v2;
            AppGlobalConfig.all[0].roundVideoRecorder2Allowed.value=v2;
            AppGlobalConfig.all[1].roundVideoRecorder2Allowed.value=!v2;
            LaunchActivity activity=new LaunchActivity();ChatActivity chat=new ChatActivity(activity,20,10);
            check(PurpleRoundCapture.command(chat,0,true,0,0,0,0,0),"production owner start");
            InstantCameraViewBase camera=InstantCameraViewBase.last;
            check((camera instanceof InstantCameraView2)==v2,"production factory respects captured-account/default or explicit adapter policy");
            if(!explicit)check(AppGlobalConfig.lastAccount==0,"factory policy belongs to captured account");
            Theme.ResourcesProvider provider=v2?((InstantCameraView2)camera).resourcesProvider:((InstantCameraView)camera).resourcesProvider;
            check(provider!=null,"native constructor receives root provider");
            check(provider.getClass().getDeclaredFields().length==0,"provider captures no chat/activity/theme instance");
            if(first==null)first=provider;else check(provider==first,"provider shared outside chat lifetime");
            check(provider.isDark()==dark,"stock interface default uses global dark policy");
            check(provider.getColor(7)==17&&provider.getCurrentColor(7)==17&&provider.getColorOrDefault(7)==17,"stock color methods use global palette");
            Theme.shift=100;check(provider.getColor(7)==107,"palette is read live");
            check(provider.getDrawable("unused")==null&&!provider.hasGradientService(),"stock default drawable/gradient policy");
            check(provider.getPaint("unused")==Theme.paint,"stock paint fallback");
            android.graphics.ColorFilter previous=provider.getAnimatedEmojiColorFilter();
            Theme.chat_animatedEmojiTextColorFilter=new android.graphics.ColorFilter();
            check(provider.getAnimatedEmojiColorFilter()==Theme.chat_animatedEmojiTextColorFilter&&provider.getAnimatedEmojiColorFilter()!=previous,"stock color filter read live");
            int matrix=Theme.matrixCalls;provider.applyServiceShaderMatrix(1,2,3,4);check(Theme.matrixCalls==matrix+1,"stock service shader delegates globally");
            float invert=v2?((InstantCameraView2)camera).switchCameraButton.invert:((InstantCameraView)camera).switchCameraButton.invert;
            check(invert==(dark?0f:.6f),"actual native new-design constructor light/dark branch");
            InstantCameraViewBase oldDesign=InstantCameraViewBase.create(activity,camera.delegate,provider,false);
            int adds=v2?((InstantCameraView2)oldDesign).flashViews.adds:((InstantCameraView)oldDesign).flashViews.adds;
            check(adds==2,"actual native old-design branch remains unchanged");
            PurpleRoundCapture.detach(chat);PurpleRoundCapture.attach(new ChatActivity(activity,21,10));
            check((v2?((InstantCameraView2)camera).resourcesProvider:((InstantCameraView)camera).resourcesProvider)==provider,"rebinding does not replace provider");
            PurpleRoundCapture.activityPaused(activity);AndroidUtilities.drain();check(activity.root.children.isEmpty(),"capture still releases after construction");
        }
        System.out.println("ROUND_CONSTRUCTION_CHECKS="+checks);
    }
}
