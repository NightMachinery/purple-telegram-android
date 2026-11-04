import org.telegram.messenger.purple.PurpleRecordingDockState;
import org.telegram.messenger.purple.PurpleRoundBubbleGeometry;
import org.telegram.messenger.purple.PurpleRecordingTarget;
import org.telegram.messenger.purple.PurpleRecordingSendIdentity;
import org.telegram.messenger.purple.PurpleRecordingPreviewHandoff;

public final class DockCases {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        PurpleRecordingTarget target = new PurpleRecordingTarget();
        long[] original = {1, 101, 22, 31, 9, 0};
        target.capture(7, original);
        original[1] = 999;
        check(target.forEvent(7, false, false) == null);
        check(target.forEvent(7, false, true) == null);
        target.once(true); target.dock();
        long[] docked = target.forEvent(7, true, false);
        check(docked[1] == 101 && docked[2] == 22 && docked[3] == 31 && docked[4] == 9 && docked[5] == 1);
        docked[1] = 888;
        check(target.forEvent(7, true, false)[1] == 101);
        check(target.forEvent(8, true, true) == null);
        check(target.forEvent(7, false, true)[5] == 1);
        check(target.forEvent(7, false, false) == null);
        target.capture(7, new long[]{2, 202, 0, 0, 10, 0});
        check(target.forEvent(7, false, true) == null);
        target.dock(); check(target.forEvent(7, true, false)[1] == 202);
        target.once(true); target.once(false); check(target.forEvent(7, true, false)[5] == 0);
        target.clear(8); check(target.forEvent(7, true, false) != null);
        target.clear(7); check(target.forEvent(7, true, true) == null);
        PurpleRecordingDockState state = new PurpleRecordingDockState();
        check(!state.active()); check(!state.canDock()); check(!state.accepts(0)); check(!state.requestPause()); check(!state.requestSend());
        check(state.begin(1, 7, 9, 101, 22, true, true, 1000));
        long token = state.generation;
        String oldSend = state.actionIdentity("send");
        check(oldSend.equals(state.actionIdentity("send")));
        check(!oldSend.equals(state.actionIdentity("pause")));
        check(!oldSend.equals(state.actionIdentity("discard")));
        check(state.canDock());
        check(state.matches(1, 7)); check(!state.matches(0, 7)); check(!state.matches(1, 8));
        check(state.user == 9 && state.dialog == 101 && state.topic == 22 && state.once && state.scheduled);
        check(!state.begin(2, 8, 10, 202, 0, false, false, 1100));
        check(state.account == 1 && state.dialog == 101);
        check(state.duration(3500) == 2500);
        state.pause(true, 3500); check(!state.canDock());
        state.pause(false, 3500);
        state.docked = true; check(state.canDock());
        check(state.requestPause()); check(!state.requestPause()); check(!state.requestSend());
        state.pause(true, 4000); check(state.paused && !state.transitioning);
        check(state.duration(10000) == 3000);
        check(!state.requestSend()); check(state.sendPending && state.transitioning && !state.finishing);
        state.pause(false, 12000);
        check(state.sendPending); state.sendPending = false;
        check(state.duration(13000) == 4000);
        check(state.requestSend()); check(state.finishing); check(!state.canDock()); check(!state.accepts(token));
        check(!state.requestSend()); check(!state.requestPause());
        state.clear(); check(!state.active()); check(!state.accepts(token));
        check(state.begin(2, 9, 10, 202, 0, false, false, 20000));
        check(!state.accepts(token)); check(!state.once && !state.scheduled && !state.docked && !state.sendPending);
        check(!oldSend.equals(state.actionIdentity("send")));
        check(!new PurpleRecordingDockState().actionIdentity("send").equals(state.actionIdentity("send")));
        check(!state.confirmDiscard(21000)); check(state.discardArmed(22000));
        check(!state.discardArmed(26001)); check(!state.confirmDiscard(26001));
        check(state.confirmDiscard(31001)); check(!state.discardArmed(31001));
        state.pause(true, 33000); state.pause(true, 34000); check(state.duration(40000) == 13000);
        state.pause(false, 41000); check(state.duration(42000) == 14000);
        for (int corner = 0; corner != 4; corner++) {
            for (int width : new int[]{0, 70, 120, 320, 1080}) {
                for (int height : new int[]{0, 80, 120, 280, 1920}) {
                    int x = PurpleRoundBubbleGeometry.x(corner, width, 120, 12);
                    int y = PurpleRoundBubbleGeometry.y(corner, height, 120, 12);
                    check(x >= 0 && x <= Math.max(0, width - 120));
                    check(y >= 0 && y <= Math.max(0, height - 120));
                }
            }
        }
        check(PurpleRoundBubbleGeometry.corner(10, 10, 600, 900, 120) == 0);
        check(PurpleRoundBubbleGeometry.corner(470, 10, 600, 900, 120) == 1);
        check(PurpleRoundBubbleGeometry.corner(10, 770, 600, 900, 120) == 2);
        check(PurpleRoundBubbleGeometry.corner(470, 770, 600, 900, 120) == 3);
        PurpleRecordingDockState round = new PurpleRecordingDockState();
        check(!round.retarget(22, 0));
        check(round.begin(1, 5, 9, 100, 7, true, false, 10));
        round.round = true; check(!round.canDock());
        long roundToken = round.generation;
        round.amplitude(20); check(round.levelIndex == 1 && Math.abs(round.levels[0] - .2f) < .001f);
        round.amplitude(Double.NaN); check(round.levelIndex == 1);
        round.amplitude(Double.POSITIVE_INFINITY); check(round.levelIndex == 1);
        round.amplitude(-1); check(round.levels[1] == 0);
        round.amplitude(1000); check(round.levels[2] == 1);
        round.pause(true, 100); round.amplitude(50); check(round.levelIndex == 3);
        check(round.retarget(200, 8)); check(round.account == 1 && round.user == 9 && round.once && round.generation != roundToken);
        check(round.dialog == 200 && round.topic == 8);
        check(!round.accepts(roundToken));
        check(!round.retarget(0, 0)); check(round.dialog == 200);
        round.transitioning = true; check(!round.retarget(300, 0)); round.transitioning = false;
        round.finishing = true; check(!round.retarget(300, 0)); round.clear();
        check(round.begin(2, 6, 10, 300, 0, false, false, 200));
        check(!round.round && round.levelIndex == 0 && round.levels[0] == 0);
        PurpleRecordingSendIdentity identity = new PurpleRecordingSendIdentity(1, 9, -100, -200);
        check(identity.matches(1, 9, -100));
        check(!identity.matches(0, 9, -100));
        check(!identity.matches(1, 10, -100));
        check(!identity.matches(1, 9, -101));
        check(identity.peer == -200);
        PurpleRecordingSendIdentity replacementIdentity = new PurpleRecordingSendIdentity(1, 9, -101, 9);
        check(replacementIdentity.matches(1, 9, -101));
        check(!replacementIdentity.matches(1, 9, -100));
        check(identity.peer == -200 && replacementIdentity.peer == 9);
        PurpleRecordingPreviewHandoff handoff = new PurpleRecordingPreviewHandoff();
        long[] sendTarget = {1, 202, 8, 0, 9, 1};
        long[] captureTarget = {1, 101, 7, 31, 9, 1};
        handoff.arm(sendTarget, captureTarget); sendTarget[1] = 999; captureTarget[3] = 0;
        check(handoff.take(0, 9, 202, 8) == null);
        check(handoff.take(1, 10, 202, 8) == null);
        check(handoff.take(1, 9, 101, 8) == null);
        check(handoff.take(1, 9, 202, 7) == null);
        long[][] adopted = handoff.take(1, 9, 202, 8);
        check(adopted != null && adopted[0][1] == 202 && adopted[0][5] == 1);
        check(adopted[1][1] == 101 && adopted[1][3] == 31);
        check(handoff.take(1, 9, 202, 8) == null);
        handoff.arm(sendTarget, captureTarget); handoff.clear(); check(!handoff.matches(1, 9, 999, 8));
        System.out.println("RECORDING_DOCK_CHECKS=" + checks);
    }
}
