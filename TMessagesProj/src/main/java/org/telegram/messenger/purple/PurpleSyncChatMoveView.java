package org.telegram.messenger.purple;

public final class PurpleSyncChatMoveView {
    public enum Feature { Change, FollowFill, Recovery, Startup }
    public static boolean enabled(Feature feature){
        return enabled(feature,PurpleSyncChatChange.ACTIVATED,PurpleSyncChatMoveConsent.ACTIVATED,
                PurpleSyncChatMoveRecovery.ACTIVATED,PurpleSyncChatMoveCleanup.ACTIVATED);
    }
    static boolean enabled(Feature feature,boolean change,boolean consent,boolean recovery,boolean cleanup){
        if(feature==null)return false;
        switch(feature){
        case Change:return change;
        case FollowFill:return consent;
        case Recovery:return recovery;
        case Startup:return change||consent||recovery||cleanup;
        default:return false;
        }
    }
    public static PurpleSyncChatChange.State progress(PurpleSyncChatChange.State state){
        return state!=null&&!state.reason.equals("QualificationHeld")?state:null;
    }
    public static boolean retry(Feature feature,PurpleSyncChatChange.State state,boolean live){return retry(enabled(feature),state,live);}
    static boolean retry(boolean enabled,PurpleSyncChatChange.State state,boolean live){
        return enabled&&live&&progress(state)!=null&&state.phase.equals("Held");
    }
}
