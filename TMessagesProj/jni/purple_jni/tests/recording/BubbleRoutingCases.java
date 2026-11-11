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
        Surface expanded = new Surface(v2, false);
        View stock = expanded.camera.getButtonsLayout();
        expanded.tap(stock.getX() + 20, stock.getY() + 20);
        check(expanded.camera.flips == 1, "expanded stock flip click reaches native listener");
        check(!PurpleRoundBubble.small(expanded.camera) && expanded.resized == 0, "expanded flip retains presentation");
        expanded.camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, stock.getX() + 20, stock.getY() + 20));
        expanded.camera.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 100, 1500));
        expanded.camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 100, 1500));
        check(!PurpleRoundBubble.small(expanded.camera) && expanded.resized == 0, "stock control owns stream outside button bounds");
        check(!PurpleRoundBubble.background(expanded.camera, event(MotionEvent.ACTION_DOWN, stock.getX() + 20, stock.getY() + 20)), "parent background hit respects native controls");
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

    private static void nativeFilter(boolean v2) {
        Surface s = new Surface(v2, false);
        ImageView nativeButton = (ImageView) ((ViewGroup) s.camera.getButtonsLayout()).getChildAt(0);
        android.graphics.ColorFilter filter = new android.graphics.ColorFilter();
        nativeButton.setColorFilter(filter);
        s.shrink();
        PurpleRoundControls controls = (PurpleRoundControls) s.camera.getChildAt(s.camera.getChildCount() - 1);
        check(controls.tint.get() == filter, "actual bubble constructor wires selected native camera tint");
        nativeButton.setColorFilter(null);
        check(controls.tint.get() == null, "native untinted white state stays untinted");
        nativeButton.setColorFilter(filter);
        s.camera.paused = true;
        s.camera.redraw();
        check(controls.tint.get() == filter, "native tint remains live after pause");
    }

    private static void terminal(boolean v2) {
        implementation = v2 ? "v2 terminal" : "legacy terminal";
        dock();
        NativeTerminal camera = v2 ? new TerminalInstantCameraView2(new Context()) : new TerminalInstantCameraView(new Context());
        RetainedChat chat = new RetainedChat(camera);
        Runnable oldClose = null;
        for (int take = 0; take < 2; take++) {
            dock(); camera.setVisibility(View.VISIBLE);
            camera.startAnimation(true, false);
            if (oldClose != null) oldClose.run();
            check(camera.getVisibility() == View.VISIBLE, "old close listener cannot hide new recording");
            check(chat.roundVideoRecordBackground.alpha == 1, "new take owns feature backdrop");
            camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 1500));
            camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 100, 1500));
            View oldControls = camera.getChildAt(camera.getChildCount() - 1);
            check(PurpleRoundBubble.small(camera) && chat.roundVideoRecordBackground.alpha == 0, "minimize releases feature backdrop");
            chat.nativeDim = true;
            camera.cancel(false);
            check(!PurpleRecordingDock.state.active(), "native discard terminal before close animation completes");
            chat.leave();
            check(chat.roundVideoRecordBackground.alpha == 0 && chat.overlayView.getVisibility() == View.GONE, "pause during terminal animation cannot reacquire overlay or dim");
            oldClose = camera.closeCompletion;
            camera.closeCompletion.run();
            check(camera.getVisibility() == View.INVISIBLE, "native animation listener hides camera");
            chat.leave(); PurpleRoundBubble.pause(camera);
            camera.redraw();
            check(chat.roundVideoRecordBackground.alpha == 0 && chat.overlayView.getVisibility() == View.GONE, "same chat background and resume stays clear");
            check(!PurpleRoundBubble.small(camera) && oldControls.getVisibility() == View.GONE, "terminal presentation and old controls retired");
            check(chat.nativeDim, "unrelated native dim remains owned by native dialog");
            int resizes = chat.resizes, flips = camera.flips, pauses = camera.pauses;
            ((ViewGroup) oldControls).getChildAt(0).performClick();
            ((ViewGroup) oldControls).getChildAt(1).performClick();
            check(camera.flips == flips && camera.pauses == pauses, "late discarded controls cannot switch or pause camera");
            PurpleRoundBubble.expand(camera); camera.redraw();
            check(chat.resizes == resizes && chat.roundVideoRecordBackground.alpha == 0, "late expansion/layout callback cannot restore terminal dim");
        }
    }

    private static void secondTake(boolean v2, boolean background) {
        implementation = (v2 ? "v2" : "legacy") + " retained take background=" + background;
        dock();
        NativeTerminal camera = v2 ? new TerminalInstantCameraView2(new android.app.Activity()) : new TerminalInstantCameraView(new android.app.Activity());
        RetainedChat chat = new RetainedChat(camera);
        Runnable oldClose = null;
        for (int take = 0; take < 3; take++) {
            dock();
            int resized = chat.resizes;
            camera.setVisibility(View.VISIBLE);
            check(camera.getButtonsLayout().alpha == 0, "native visibility initialization resets alpha only");
            camera.startAnimation(true, false);
            if (oldClose != null) oldClose.run();
            View buttons = camera.getButtonsLayout();
            check(buttons.getVisibility() == View.VISIBLE && buttons.alpha == 1, "fresh expanded take exposes native camera bar take=" + take);
            check(camera.getVisibility() == View.VISIBLE && !PurpleRoundBubble.small(camera), "old close cannot hide fresh expanded take");
            check(chat.resizes == resized, "fresh open restores presentation without resize callback replay");
            int flips = camera.flips;
            camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, buttons.getX() + 20, buttons.getY() + 20));
            camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, buttons.getX() + 20, buttons.getY() + 20));
            check(camera.flips == flips + 1 && !PurpleRoundBubble.small(camera), "visible native flip target receives expanded stream take=" + take);
            camera.togglePause(); camera.startAnimation(true, true);
            check(buttons.getVisibility() == View.VISIBLE && chat.resizes == resized, "expanded paused open preserves native controls without resize");
            camera.togglePause(); camera.startAnimation(true, true);
            check(buttons.getVisibility() == View.VISIBLE && chat.roundVideoRecordBackground.alpha == 1, "expanded resumed open retains controls and feature backdrop");
            camera.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 100, 1500));
            camera.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 100, 1500));
            View compact = camera.getChildAt(camera.getChildCount() - 1);
            camera.startAnimation(true, true);
            check(PurpleRoundBubble.small(camera) && buttons.getVisibility() == View.INVISIBLE && compact.getVisibility() == View.VISIBLE, "compact paused open retains compact controls");
            check(chat.roundVideoRecordBackground.alpha == 0, "compact paused open cannot acquire expanded backdrop");
            camera.cancel(false);
            oldClose = camera.closeCompletion;
            if (background) chat.leave();
            check(!PurpleRecordingDock.state.active() && chat.roundVideoRecordBackground.alpha == 0 && chat.overlayView.getVisibility() == View.GONE, "discard terminal stays clear before native close");
            oldClose.run();
            if (background) { chat.leave(); PurpleRoundBubble.pause(camera); }
            PurpleRoundBubble.expand(camera); camera.redraw();
            check(camera.getVisibility() == View.INVISIBLE && !PurpleRoundBubble.small(camera) && compact.getVisibility() == View.GONE, "closed take stays retired through retained lifecycle");
            flips = camera.flips;
            int pauses = camera.pauses;
            ((ViewGroup) compact).getChildAt(0).performClick();
            ((ViewGroup) compact).getChildAt(1).performClick();
            check(camera.flips == flips && camera.pauses == pauses && chat.roundVideoRecordBackground.alpha == 0, "discarded controls cannot change terminal capture");
        }
    }

    public static void main(String[] args) {
        run(false); run(true);
        int terminalStart = checks; terminal(false); terminal(true);
        System.out.println("ROUND_TERMINAL_CHECKS=" + (checks - terminalStart));
        System.out.println("ROUND_ROUTING_CHECKS=" + terminalStart);
        int before = checks;
        bounds(false); bounds(true);
        System.out.println("ROUND_CONTROLS_PLACEMENT_CHECKS=" + (checks - before));
        before = checks;
        nativeFilter(false); nativeFilter(true);
        System.out.println("ROUND_CONTROLS_NATIVE_FILTER_CHECKS=" + (checks - before));
        before = checks;
        secondTake(false, false); secondTake(true, false);
        secondTake(false, true); secondTake(true, true);
        System.out.println("ROUND_SECOND_TAKE_CHECKS=" + (checks - before));
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) throw new AssertionError("round routing failures=" + failures);
    }
}
