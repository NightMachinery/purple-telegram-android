package org.telegram.ui.Components;

import android.view.MotionEvent;
import android.view.View;

public final class PurpleRecordingPanel {
    public static boolean refuseCamera(ChatActivityEnterView enter) {
        if (!org.telegram.messenger.purple.PurpleRecordingDock.refuseStart()) return false;
        enter.purpleReleaseRecordUi();
        return true;
    }

    public static void install(View panel, ChatActivityEnterView enter, Runnable dock) {
        panel.setOnTouchListener(new View.OnTouchListener() {
            private float x, y;
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { x = event.getX(); y = event.getY(); }
                if (event.getActionMasked() == MotionEvent.ACTION_UP && enter.isRecordLocked()
                        && Math.hypot(event.getX() - x, event.getY() - y) < android.view.ViewConfiguration.get(view.getContext()).getScaledTouchSlop()) dock.run();
                return true;
            }
        });
    }
}
