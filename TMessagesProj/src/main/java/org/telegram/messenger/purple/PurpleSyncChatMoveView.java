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
    /**
     * The status line for move progress, as a string key. FinishedForFollow never reads as a completed move: it says
     * the settings went to the new chat and points to the separate Follow offer. MarkRecoveryWaiting says a mark
     * check is pending; its time is {@code recheckAt}, and Retry runs the check (D-M2B5-13).
     */
    public static String line(PurpleSyncChatChange.State state){
        switch(state.phase){
        case "FinishedForFollow":return state.supersededBy!=null?"MoveFinishedForFollow":"MoveFinishedSearching";
        case "Complete":return "ChangeComplete";
        case "Held":return state.reason.equals("MarkRecoveryWaiting")&&state.recheckAt>0?"MoveRecoveryWaiting":"MoveHeld";
        default:return "MoveWorking";
        }
    }
    /** Whether a move state is final, so the screen reloads its frame; after FinishedForFollow that shows the Follow offer. */
    public static boolean terminal(PurpleSyncChatChange.State state){return state!=null&&(state.phase.equals("Complete")||state.phase.equals("FinishedForFollow"));}
    public static PurpleSyncChatChange.State progress(PurpleSyncChatChange.State state){
        return state!=null&&!state.reason.equals("QualificationHeld")?state:null;
    }
    public static boolean retry(Feature feature,PurpleSyncChatChange.State state,boolean live){return retry(enabled(feature),state,live);}
    static boolean retry(boolean enabled,PurpleSyncChatChange.State state,boolean live){
        return enabled&&live&&progress(state)!=null&&state.phase.equals("Held");
    }
}
