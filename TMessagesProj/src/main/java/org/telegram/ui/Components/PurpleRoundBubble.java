package org.telegram.ui.Components;

import android.content.Context;
import android.view.*;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.messenger.purple.PurpleRecordingSettings;
import org.telegram.messenger.purple.PurpleRoundBubbleGeometry;
import org.telegram.ui.ActionBar.Theme;

public final class PurpleRoundBubble {
    public interface Dispatch { boolean run(); }
    public interface Visibility { void small(boolean small); }
    private static final int STATE_TAG = 0x70750521;
    private static State state(InstantCameraViewBase view) { return (State) view.getTag(STATE_TAG); }
    private static final class State {
        boolean small, tracking, dragging;
        float downX, downY, startX, startY;
        int corner;
        Visibility visibility;
        LinearLayout controls;
    }
    private PurpleRoundBubble() { }
    public static void attach(InstantCameraViewBase view, Visibility visibility) {
        State state = new State();
        state.visibility = visibility;
        state.corner = view.getContext().getSharedPreferences("purple_recordings", Context.MODE_PRIVATE).getInt("round_corner", 3);
        view.setTag(STATE_TAG, state);
        view.getViewTreeObserver().addOnPreDrawListener(() -> { if (state.small) place(view, state); return true; });
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (state.small) place(view, state); });
    }
    public static boolean animation(InstantCameraViewBase view, boolean open, boolean fromPaused) {
        State state = state(view);
        if (state != null && open && !fromPaused && state.small) resize(view, state, false);
        return open && (state == null || !state.small);
    }
    public static boolean small(InstantCameraViewBase view) {
        State state = state(view);
        return state != null && state.small;
    }
    public static void pause(InstantCameraViewBase view) {
        if (view != null && view.getVisibility() == View.VISIBLE && !view.isPaused()) view.togglePause();
    }
    public static boolean background(InstantCameraViewBase view, MotionEvent event) {
        if (view == null || view.getVisibility() != View.VISIBLE || !PurpleRecordingSettings.read().roundFloat) return false;
        State state = state(view);
        if (state == null) return false;
        if (!state.small && event.getActionMasked() == MotionEvent.ACTION_DOWN) resize(view, state, true);
        return state.small;
    }
    public static boolean dispatch(InstantCameraViewBase view, MotionEvent event, Dispatch normal) {
        State state = state(view);
        if (state == null || view.getVisibility() != View.VISIBLE || (!state.small && !PurpleRecordingSettings.read().roundFloat)) return normal.run();
        View camera = view.getCameraContainer();
        float left = camera.getX() + camera.getPivotX() * (1 - camera.getScaleX());
        float top = camera.getY() + camera.getPivotY() * (1 - camera.getScaleY());
        float side = camera.getWidth() * camera.getScaleX();
        boolean inside = event.getX() >= left && event.getX() <= left + side && event.getY() >= top && event.getY() <= top + side;
        if (state.small && state.controls != null && event.getY() >= state.controls.getY()
                && event.getY() <= state.controls.getY() + state.controls.getHeight()
                && event.getX() >= state.controls.getX() && event.getX() <= state.controls.getX() + state.controls.getWidth()) return normal.run();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (state.small && !inside) return false;
            if (!state.small && inside) return normal.run();
            state.tracking = true;
            state.dragging = false;
            state.downX = event.getX(); state.downY = event.getY();
            state.startX = camera.getTranslationX(); state.startY = camera.getTranslationY();
            return true;
        }
        if (!state.tracking) return state.small ? false : normal.run();
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && state.small) {
            float dx = event.getX() - state.downX, dy = event.getY() - state.downY;
            state.dragging |= Math.hypot(dx, dy) > ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            camera.setTranslationX(state.startX + dx);
            camera.setTranslationY(state.startY + dy);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            state.tracking = false;
            if (state.dragging) {
                state.corner = PurpleRoundBubbleGeometry.corner((int) left, (int) top, view.getWidth(), availableHeight(view), (int) side);
                view.getContext().getSharedPreferences("purple_recordings", Context.MODE_PRIVATE).edit().putInt("round_corner", state.corner).apply();
                place(view, state);
            } else resize(view, state, !state.small);
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            state.tracking = false;
            if (state.small) place(view, state);
        }
        return true;
    }
    private static int availableHeight(InstantCameraViewBase view) {
        android.graphics.Rect visible = new android.graphics.Rect();
        view.getWindowVisibleDisplayFrame(visible);
        int[] location = new int[2]; view.getLocationOnScreen(location);
        return Math.min(view.getHeight() - view.getPaddingBottom(), Math.max(0, visible.bottom - location[1])) - AndroidUtilities.dp(76);
    }
    private static void resize(InstantCameraViewBase view, State state, boolean small) {
        state.small = small;
        view.getButtonsLayout().setVisibility(small ? View.INVISIBLE : View.VISIBLE);
        if (small) {
            if (state.controls == null) {
                state.controls = new LinearLayout(view.getContext());
                TextView flip = control(view, "↺", LocaleController.getString(R.string.AccDescrSwitchCamera), () -> {
                    View buttons = view.getButtonsLayout();
                    if (buttons instanceof ViewGroup && ((ViewGroup) buttons).getChildCount() > 0) ((ViewGroup) buttons).getChildAt(0).performClick();
                });
                TextView pause = control(view, "Ⅱ / ▷", LocaleController.getString(R.string.PurpleRecordingPause), view::togglePause);
                state.controls.addView(flip, new LinearLayout.LayoutParams(AndroidUtilities.dp(44), AndroidUtilities.dp(32)));
                state.controls.addView(pause, new LinearLayout.LayoutParams(AndroidUtilities.dp(60), AndroidUtilities.dp(32)));
                view.addView(state.controls);
            }
            state.controls.setVisibility(View.VISIBLE);
            place(view, state);
        } else {
            View camera = view.getCameraContainer();
            camera.setScaleX(1); camera.setScaleY(1);
            camera.setTranslationX(0); camera.setTranslationY(0);
            if (state.controls != null) state.controls.setVisibility(View.GONE);
        }
        state.visibility.small(small);
    }
    private static TextView control(InstantCameraViewBase view, String label, String description, Runnable action) {
        TextView text = new TextView(view.getContext());
        text.setText(label); text.setContentDescription(description); text.setGravity(Gravity.CENTER);
        text.setTextColor(Theme.getColor(Theme.key_inappPlayerTitle));
        text.setBackgroundColor(Theme.getColor(Theme.key_inappPlayerBackground));
        text.setOnClickListener(v -> action.run());
        return text;
    }
    private static void place(InstantCameraViewBase view, State state) {
        View camera = view.getCameraContainer();
        int side = Math.min(AndroidUtilities.dp(120), Math.min(view.getWidth(), Math.max(1, availableHeight(view))));
        float scale = side / (float) Math.max(1, camera.getWidth());
        camera.setScaleX(scale); camera.setScaleY(scale);
        int x = PurpleRoundBubbleGeometry.x(state.corner, view.getWidth(), side, AndroidUtilities.dp(12));
        int y = PurpleRoundBubbleGeometry.y(state.corner, availableHeight(view), side, AndroidUtilities.dp(12));
        camera.setTranslationX(x - camera.getLeft() - camera.getPivotX() * (1 - scale));
        camera.setTranslationY(y - camera.getTop() - camera.getPivotY() * (1 - scale));
        if (state.controls != null) { state.controls.setX(x); state.controls.setY(y + side); state.controls.bringToFront(); }
    }
}
