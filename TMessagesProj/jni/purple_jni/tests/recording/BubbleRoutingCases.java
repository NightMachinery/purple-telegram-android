import android.content.Context;
import android.view.*;
import android.widget.*;
import org.telegram.messenger.purple.*;
import org.telegram.ui.Components.*;
import org.telegram.utils.settings.SharedSettings;

public final class BubbleRoutingCases {
    private static int checks, failures;
    private static String implementation;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) { failures++; System.out.println("FAIL " + implementation + " " + name); }
    }
    private static MotionEvent event(int action, float x, float y) { return new MotionEvent(action, x, y); }
    private static void dock() {
        PurpleRecordingDock.state.clear();
        PurpleRecordingDock.state.begin(0, 1, 1, 1, 0, false, false, 0);
        PurpleRecordingDock.state.round = true;
        PurpleCore.settings = null;
    }
    private static final class Surface {
        final FrameLayout root = new FrameLayout(new Context());
        final HostCamera camera;
        final View overlay;
        int sends, deletes, resized;
        Surface(boolean v2, boolean paused) {
            dock();
            SharedSettings.roundVideoCamera2Enabled.value = v2;
            camera = (HostCamera) InstantCameraViewBase.create(root.getContext(), null, null, true);
            camera.paused = paused;
            overlay = new View(root.getContext());
            overlay.left = 400; overlay.top = 700; overlay.width = overlay.height = 280;
            camera.addView(overlay);
            PurpleRoundBubble.attachOverlay(camera, overlay);
            FrameLayout composer = new FrameLayout(root.getContext());
            composer.width = root.width = 1080;
            composer.height = root.height = 2400;
            View delete = new View(root.getContext());
            delete.left = 20; delete.top = 2200; delete.width = 120; delete.height = 116;
            delete.setOnClickListener(v -> deletes++);
            View send = new View(root.getContext());
            send.left = 800; send.top = 2200; send.width = 260; send.height = 116;
            send.setOnClickListener(v -> sends++);
            composer.addView(delete); composer.addView(send);
            root.addView(composer); root.addView(camera);
            PurpleRoundBubble.attach(camera, small -> resized++);
        }
        void tap(float x, float y) {
            root.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
            root.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x, y));
        }
        void shrink() { tap(100, 1500); }
    }
    private static void run(boolean v2) {
        implementation = v2 ? "v2" : "legacy";
        for (boolean paused : new boolean[]{false, true}) {
            for (boolean send : new boolean[]{false, true}) {
                Surface s = new Surface(v2, paused);
                s.tap(send ? 1000 : 80, 2255);
                check(send ? s.sends == 1 && s.deletes == 0 : s.deletes == 1 && s.sends == 0, "native composer click paused=" + paused);
                check(!PurpleRoundBubble.small(s.camera) && s.resized == 0, "composer tap cannot shrink");
                check(s.camera.normalDispatches == 1, "native down rejects bottom inset");
            }
        }
        Surface s = new Surface(v2, true);
        s.tap(100, 1500);
        check(!PurpleRoundBubble.small(s.camera) && s.resized == 0, "paused background stays native");
        check(s.camera.normalDispatches == 2, "paused background native stream");
        s = new Surface(v2, true);
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 1500));
        s.camera.paused = false;
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 110, 1510));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 110, 1510));
        check(s.camera.normalDispatches == 3 && s.resized == 0, "native gesture retained after resume");
        s = new Surface(v2, false);
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 1000, 2255));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 100, 1500));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 100, 1500));
        check(s.camera.normalDispatches == 3 && s.resized == 0, "inset native stream cannot be reclaimed");
        for (int gate = 0; gate < 5; gate++) {
            s = new Surface(v2, false);
            if (gate == 0) PurpleRecordingDock.state.clear();
            if (gate == 1) PurpleRecordingDock.state.round = false;
            if (gate == 2) PurpleRecordingDock.state.finishing = true;
            if (gate == 3) PurpleRecordingDock.state.transitioning = true;
            if (gate == 4) PurpleRecordingDock.state.paused = true;
            s.tap(100, 1500);
            check(!PurpleRoundBubble.small(s.camera) && s.resized == 0 && s.camera.normalDispatches == 2, "ineligible dock delegates gate=" + gate);
            check(!PurpleRoundBubble.background(s.camera, event(MotionEvent.ACTION_DOWN, 100, 1500)), "background same eligibility gate=" + gate);
        }
        s = new Surface(v2, false);
        s.shrink();
        check(PurpleRoundBubble.small(s.camera) && s.resized == 1, "active background shrinks");
        check(s.camera.normalDispatches == 0, "active background owned by bubble");
        s.tap(1000, 2255); s.tap(80, 2255);
        check(s.sends == 1 && s.deletes == 1, "small outside passes native controls");
        LinearLayout controls = (LinearLayout) s.camera.getChildAt(s.camera.getChildCount() - 1);
        check(controls.getChildAt(0).getWidth() == 48 && controls.getChildAt(1).getHeight() == 48, "48dp controls");
        float cx = controls.getX(), cy = controls.getY();
        s.tap(cx + 70, cy + 16);
        check(s.camera.pauses == 1 && s.camera.paused, "small pause control");
        check(PurpleRoundBubble.small(s.camera), "pause keeps small bubble");
        s.tap(cx + 20, cy + 16);
        check(s.camera.flips == 1 && PurpleRoundBubble.small(s.camera), "small flip control");
        for (String update : new String[]{"pause", "flip", "resume"}) {
            s.overlay.setScaleX(1); s.overlay.setScaleY(1); s.overlay.setTranslationX(0); s.overlay.setTranslationY(0);
            s.camera.redraw();
            View live = s.camera.getCameraContainer();
            check(s.overlay.getScaleX() == live.getScaleX() && s.overlay.getScaleX() < 1, update + " placeholder stays small");
            check(s.overlay.getX() == live.getX() && s.overlay.getY() == live.getY(), update + " placeholder follows bubble");
            check(s.camera.getButtonsLayout().getVisibility() == View.INVISIBLE, update + " stock buttons stay hidden");
        }
        int normal = s.camera.normalDispatches;
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, cx + 70, cy + 16));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, cx + 200, cy - 50));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, cx + 200, cy - 50));
        check(s.camera.normalDispatches == normal + 3 && s.camera.pauses == 1, "small control owns whole stream outside its bounds");
        View camera = s.camera.getCameraContainer();
        float x = camera.getX() + camera.getPivotX() * (1 - camera.getScaleX()) + 20;
        float y = camera.getY() + camera.getPivotY() * (1 - camera.getScaleY()) + 20;
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        normal = s.camera.normalDispatches;
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, cx + 70, cy + 16));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 20, 20));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 20, 20));
        check(s.camera.normalDispatches == normal && s.camera.pauses == 1, "bubble drag across controls retains gesture");
        check(PurpleRoundBubble.small(s.camera) && s.camera.getContext().prefs.corner == 0, "small drag persists top-left corner");
        camera = s.camera.getCameraContainer();
        x = camera.getX() + camera.getPivotX() * (1 - camera.getScaleX()) + 20;
        y = camera.getY() + camera.getPivotY() * (1 - camera.getScaleY()) + 20;
        s.tap(x, y);
        check(!PurpleRoundBubble.small(s.camera) && camera.getScaleX() == 1, "paused small expands");
        check(s.camera.getButtonsLayout().getVisibility() == View.VISIBLE, "expand restores native camera buttons");
        s = new Surface(v2, false);
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 1500));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, 100, 1500));
        check(!PurpleRoundBubble.small(s.camera), "cancel never shrinks");
        s.tap(1000, 2255);
        check(s.sends == 1, "new native gesture after cancel");
        s = new Surface(v2, true);
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 1500));
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, 100, 1500));
        s.camera.paused = false;
        s.shrink();
        check(s.camera.normalDispatches == 2 && PurpleRoundBubble.small(s.camera), "native cancel releases stream for next bubble gesture");
        s = new Surface(v2, false);
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 1500));
        s.camera.paused = true;
        s.camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 100, 1500));
        check(s.camera.normalDispatches == 0 && s.resized == 0, "eligibility rechecked before shrinking");
        s = new Surface(v2, false);
        check(PurpleRoundBubble.background(s.camera, event(MotionEvent.ACTION_DOWN, 100, 1500)), "background active shrink");
        s.camera.paused = true;
        check(PurpleRoundBubble.background(s.camera, event(MotionEvent.ACTION_DOWN, 100, 1500)) && PurpleRoundBubble.small(s.camera), "existing small background remains usable paused");
        s = new Surface(v2, true);
        check(!PurpleRoundBubble.background(s.camera, event(MotionEvent.ACTION_DOWN, 100, 1500)) && s.resized == 0, "paused background hook no-op");
        s = new Surface(v2, false);
        check(!PurpleRoundBubble.background(s.camera, event(MotionEvent.ACTION_DOWN, 1000, 2255)) && s.resized == 0, "background hook respects native inset");
        s = new Surface(v2, false);
        PurpleCore.settings = new int[]{1,1,0,1,172800,1,1,1,1,0,1800,1};
        s.tap(100, 1500);
        check(s.camera.normalDispatches == 2 && s.resized == 0, "floating disabled native dispatch");
        check(!PurpleRoundBubble.background(s.camera, event(MotionEvent.ACTION_DOWN, 100, 1500)), "floating disabled background");
    }
    private static void bounds(boolean v2) {
        implementation = v2 ? "v2 bounds" : "legacy bounds";
        for (int width : new int[]{240, 320, 1080}) for (int height : new int[]{400, 800, 2400})
                for (int bottom : new int[]{0, 280}) for (int corner = 0; corner < 4; corner++) {
            Surface s = new Surface(v2, false);
            s.camera.width = width; s.camera.height = height; s.camera.paddingBottom = bottom;
            s.camera.getContext().prefs.corner = corner;
            PurpleRoundBubble.attach(s.camera, small -> {});
            PurpleRoundBubble.background(s.camera, event(MotionEvent.ACTION_DOWN, 0, 1));
            s.camera.redraw();
            View controls = s.camera.getChildAt(s.camera.getChildCount() - 1);
            check(controls.getWidth() == 108 && controls.getHeight() == 60, "compact measured capsule contract");
            check(controls.getX() >= 0 && controls.getX() + controls.getWidth() <= width, "capsule inside horizontal bounds");
            check(controls.getY() >= 0 && controls.getY() + controls.getHeight() <= height - bottom, "capsule above composer or keyboard");
            check(PurpleRoundBubble.small(s.camera), "corner placement retains minimized state");
        }
    }

    public static void main(String[] args) {
        run(false); run(true);
        System.out.println("ROUND_ROUTING_CHECKS=" + checks);
        int before = checks;
        bounds(false); bounds(true);
        System.out.println("ROUND_CONTROLS_PLACEMENT_CHECKS=" + (checks - before));
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) throw new AssertionError("round routing failures=" + failures);
    }
}
