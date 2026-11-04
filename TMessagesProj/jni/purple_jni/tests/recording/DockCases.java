import org.telegram.messenger.purple.PurpleRecordingDockState;
import org.telegram.messenger.purple.PurpleRoundBubbleGeometry;
import org.telegram.messenger.purple.PurpleRecordingTarget;

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
        System.out.println("RECORDING_DOCK_CHECKS=" + checks);
    }
}
