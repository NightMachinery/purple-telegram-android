package org.telegram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Button;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.purple.*;
import org.telegram.ui.ActionBar.BaseFragment;

public final class PeekReadyCases {
    private static int checks;
    private static void check(boolean value, String text) {
        ++checks;
        if (!value) throw new AssertionError(text);
    }
    private static Object field(Class<?> type, Object object, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static Object field(Object object, String name) throws Exception { return field(object.getClass(), object, name); }
    private static Object pending() throws Exception { return field(PurplePeekControls.class, null, "pending"); }
    private static Button button(Object operation, String name) throws Exception { return (Button) field(field(operation, "cover"), name); }
    private static void invoke(Object object, String method) throws Exception {
        Method m = object.getClass().getDeclaredMethod(method); m.setAccessible(true); m.invoke(object);
    }
    private static void tick(long now) { SystemClock.now = now; AndroidUtilities.due(); PurplePeekControls.presenceChanged(); }
    private static void key(int code, int repeat) { Dialog.latest.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code, repeat)); }
    public static void main(String[] args) throws Exception {
        Method setup = PeekFocusCases.class.getDeclaredMethod("setup", boolean.class, boolean.class, boolean.class);
        setup.setAccessible(true); setup.invoke(null, false, false, false);
        BaseFragment fragment = (BaseFragment) field(PeekFocusCases.class, null, "fragment");
        Activity owner = fragment.activity;
        String scenario = args[0];
        if (scenario.startsWith("late-show")) {
            boolean expired = scenario.equals("late-show-expired");
            PurpleState.readHook = () -> {
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (frame.getClassName().equals("org.telegram.ui.PurplePeekControls$Pending") && frame.getMethodName().equals("show")) {
                        SystemClock.now = expired ? 602000 : 2000;
                        PurpleState.readHook = null;
                        break;
                    }
                }
            };
            PurplePeekControls.start(fragment, 60);
            check(PurpleState.readHook == null && PurpleGate.starts == 0, "initial show validation stalled across countdown without applying");
            if (expired) {
                check(!PurplePeekControls.isPending() && owner.receivers.isEmpty(), "late initial show beyond ready timeout ends quietly");
            } else {
                Object operation = pending();
                check(operation != null && field(operation, "dialog") != null && field(operation, "cover") != null,
                        "late positive wait still constructs Ready and Abort");
                check(((PurpleWaitGate) field(operation, "gate")).ready() && !button(operation, "action").isEnabled(),
                        "late show starts a fresh Ready input guard");
                tick(2499); invoke(operation, "complete"); check(PurpleGate.starts == 0, "late show still guards499ms");
                tick(2500); button(operation, "action").performClick();
                check(PurpleGate.starts == 1 && !PurplePeekControls.isPending(), "late Ready applies only on a fresh confirmation");
            }
        } else if (scenario.equals("editor")) {
            PurplePeekControls.changeReadyTimeout(fragment, 9, new PurpleWriter.Session(), PurpleGate.peekSettings());
            check(!PurplePeekControls.isPending() && PurpleWriter.timeoutWrites == 0, "editor belowfloor refuses");
            PurplePeekControls.changeReadyTimeout(fragment, 600, new PurpleWriter.Session(), PurpleGate.peekSettings());
            check(!PurplePeekControls.isPending() && PurpleWriter.timeoutWrites == 0, "absent default no-op");
            PurplePeekControls.changeReadyTimeout(fragment, 10, new PurpleWriter.Session(), PurpleGate.peekSettings());
            check(!PurplePeekControls.isPending() && PurpleWriter.timeoutWrites == 1, "lower timeout applies immediately");
            PurplePeekControls.changeReadyTimeout(fragment, 601, new PurpleWriter.Session(), PurpleGate.peekSettings());
            Object p = pending(); check(p != null && PurpleWriter.timeoutWrites == 1, "raising waits");
            tick(2000); check(PurpleWriter.timeoutWrites == 1, "raising does not autoapply at deadline");
            tick(2500); button(p, "action").performClick();
            check(!PurplePeekControls.isPending() && PurpleWriter.timeoutWrites == 2, "raising applies only on confirmation");
        } else {
            if (scenario.equals("timeout")) {
                PurpleGate.settings = "version=2\n[peek]\nstart_delay_seconds=2\nready_timeout_seconds=10\n[presets.work]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                PurpleGate.reload("timeout fixture");
            }
            if (scenario.equals("end")) PurpleGate.startPeek(0, PurpleGate.peekSettings());
            PurplePeekControls.start(fragment, 60);
            Object p = pending(); check(p != null, "controller owns positive wait");
            Runnable stale = (Runnable) field(p, "tick");
            Button action = button(p, "action"), abort = button(p, "abort");
            if (scenario.equals("button")) {
                tick(2000); check(PurpleGate.starts == 0 && PurplePeekControls.isPending(), "Ready never automatically starts");
                check(!action.isEnabled() && !action.performClick(), "ready action disabled during guard");
                tick(2499); invoke(p, "complete"); check(PurpleGate.starts == 0, "queued tap still refused at499ms");
                tick(2500); action.performClick();
                check(PurpleGate.starts == 1 && !PurplePeekControls.isPending(), "fresh button starts once");
                check(owner.receivers.isEmpty(), "success unregisters exact receiver");
                action.performClick(); stale.run(); check(PurpleGate.starts == 1, "stale confirmation and timer cannot duplicate start");
            } else if (scenario.equals("cleanup")) {
                owner.throwUnregister = true; Dialog.throwDismiss = true;
                PurpleWriter.throwRetire = true;
                PurpleWriter.retireHook = () -> PurplePeekControls.start(fragment, 60);
                boolean threw = false;
                try { PurplePeekControls.cancel(); } catch (IllegalStateException e) { threw = true; }
                Object replacement = pending();
                check(threw && replacement != null && replacement != p, "retirement callback can reenter before throwing");
                check(owner.receivers.size() == 1 && !AndroidUtilities.delayed.containsKey(stale), "throwing cleanup releases only old receiver and timer");
                stale.run(); invoke(p, "complete"); abort.performClick();
                check(pending() == replacement, "late callbacks after throwing cleanup retain replacement");
                PurplePeekControls.cancel(); check(owner.receivers.isEmpty(), "replacement cancellation releases remaining receiver");
            } else if (scenario.equals("end")) {
                PurplePeekControls.end(fragment);
                check(!PurplePeekControls.isPending() && !PurpleGate.peeking() && owner.receivers.isEmpty(), "End cancels wait and ends existing Peek immediately");
            } else if (scenario.equals("keys")) {
                tick(2000); key(KeyEvent.KEYCODE_ENTER, 0); check(PurpleGate.starts == 0, "Enter inside guard ignored");
                tick(2500); key(KeyEvent.KEYCODE_ENTER, 1); check(PurpleGate.starts == 0, "autorepeat ignored");
                key(KeyEvent.KEYCODE_NUMPAD_ENTER, 0); check(PurpleGate.starts == 1 && !PurplePeekControls.isPending(), "fresh keypad Enter confirms");
            } else if (scenario.equals("abort")) {
                tick(2000); abort.performClick();
                check(!PurplePeekControls.isPending() && PurpleGate.starts == 0 && owner.receivers.isEmpty(), "Abort works during guard and releases");
                PurplePeekControls.start(fragment, 60); Object replacement = pending();
                stale.run(); invoke(p, "complete");
                abort.performClick();
                check(pending() == replacement, "old timer, action and Abort cannot cancel replacement");
                key(KeyEvent.KEYCODE_ENTER, 0);
                check(!PurplePeekControls.isPending(), "countdown key aborts");
                PurplePeekControls.start(fragment, 60); tick(4000); tick(4500); key(29, 0);
                check(!PurplePeekControls.isPending() && PurpleGate.starts == 0, "other Ready key aborts");
            } else if (scenario.equals("lock")) {
                tick(2000); tick(2500);
                SharedConfig.appLocked = true; PurpleGate.locked(); owner.app.pause(owner);
                check(PurplePeekControls.isPending() && !Dialog.latest.visible, "lock hides cover for passcode");
                tick(10000); action.performClick(); check(PurpleGate.starts == 0, "locked input cannot apply");
                SharedConfig.appLocked = false; owner.app.resume(owner);
                check(Dialog.latest.visible && !action.isEnabled(), "unlock shows Ready with new guard");
                tick(10499); action.performClick(); check(PurpleGate.starts == 0, "unlock guard at499ms");
                tick(10500); action.performClick(); check(PurpleGate.starts == 1, "unlock guard at500ms");
             } else if (scenario.equals("return-departure") || scenario.equals("return-overdue") || scenario.equals("relock-no-resume")
                    || scenario.equals("relock-resume-first") || scenario.equals("relock-locked-resume") || scenario.equals("return-duplicate-stop")) {
                tick(2000); tick(2500);
                SharedConfig.appLocked = true; PurpleGate.locked(); owner.app.pause(owner);
                owner.focused = false; Dialog.latest.onWindowFocusChanged(false);
                if (scenario.equals("relock-locked-resume")) owner.app.resume(owner);
                SharedConfig.appLocked = false;
                if (scenario.equals("relock-resume-first")) {
                    SystemClock.now = 10000; owner.app.resume(owner);
                    owner.broadcast(new Intent(Intent.ACTION_USER_PRESENT));
                } else tick(10000);
                check(PurplePeekControls.isPending(), "unlock while away starts bounded return");
                if (scenario.equals("return-departure") || scenario.equals("return-duplicate-stop")) {
                    owner.app.resume(owner);
                    SystemClock.now = 10100; owner.app.pause(owner);
                    if (scenario.equals("return-duplicate-stop")) {
                        SystemClock.now = 10399; owner.app.stop(owner); owner.app.stop(owner);
                        check(PurplePeekControls.isPending(), "duplicate stop does not end before original300ms");
                    }
                    tick(10400);
                    check(!PurplePeekControls.isPending(), "fresh unlocked departure expires at300ms even while Returning");
                    SharedConfig.appLocked = true; tick(10500); tick(12000);
                    check(!PurplePeekControls.isPending() && PurpleGate.starts == 0, "lock400ms after fresh departure cannot rescue wait");
                } else if (scenario.equals("return-overdue")) {
                    SystemClock.now = 12000; SharedConfig.appLocked = true; PurplePeekControls.presenceChanged();
                    check(!PurplePeekControls.isPending() && PurpleGate.starts == 0, "first relock sample at2s cannot rescue overdue return");
                } else if (scenario.equals("relock-no-resume") || scenario.equals("relock-locked-resume")) {
                    SharedConfig.appLocked = true; tick(10100);
                    check(!PurplePeekControls.isPending() && PurpleGate.starts == 0, "ambiguous relock without Telegram resume cancels");
                } else {
                    SharedConfig.appLocked = true; tick(10100); tick(12000);
                    check(PurplePeekControls.isPending() && !Dialog.latest.visible, "resume before unlock signal proves owner return for relock");
                    tick(20000); SharedConfig.appLocked = false; owner.focused = true; owner.app.resume(owner);
                    tick(20500); action.performClick(); check(PurpleGate.starts == 1, "resume-first relock returns to fresh guarded confirmation");
                }
            } else if (scenario.equals("relock")) {
                tick(2000); tick(2500);
                SharedConfig.appLocked = true; PurpleGate.locked(); owner.app.pause(owner);
                SharedConfig.appLocked = false; tick(10000);
                check(PurplePeekControls.isPending(), "unlock while paused starts bounded return");
                owner.focused = false; Dialog.latest.onWindowFocusChanged(false); owner.app.resume(owner);
                SharedConfig.appLocked = true; tick(10100); tick(12000);
                check(PurplePeekControls.isPending() && !Dialog.latest.visible && PurpleGate.starts == 0,
                        "relock during return remains Locked beyond original return deadline");
                tick(20000); SharedConfig.appLocked = false; owner.focused = true; owner.app.resume(owner);
                check(PurplePeekControls.isPending() && !action.isEnabled(), "second unlock returns to guarded Ready");
                tick(20500); action.performClick(); check(PurpleGate.starts == 1, "second unlock confirms after guard");
            } else if (scenario.startsWith("queued-focus") || scenario.startsWith("focus-handoff")) {
                tick(2000); tick(2500);
                SharedConfig.appLocked = true; PurpleGate.locked();
                AndroidUtilities.due();
                check(PurplePeekControls.isPending() && !Dialog.latest.visible, "hidden dialog asynchronously loses focus while locked");
                SharedConfig.appLocked = false; SystemClock.now = 10000;
                if (scenario.equals("focus-handoff-timeout")) Dialog.focusDelay = 1000;
                if (scenario.equals("queued-focus-first")) {
                    PurplePeekControls.presenceChanged(); AndroidUtilities.due();
                }
                invoke(p, "complete");
                check(PurplePeekControls.isPending() && PurpleGate.starts == 0 && !action.isEnabled(),
                        "queued unlock confirmation reveals Ready and only rearms guard");
                if (scenario.equals("focus-handoff-pause")) {
                    SystemClock.now = 10001; owner.app.pause(owner); tick(10301);
                    check(!PurplePeekControls.isPending() && PurpleGate.starts == 0, "unlocked owner departure during focus handoff still cancels");
                } else if (scenario.equals("focus-handoff-window")) {
                    SystemClock.now = 10001; Dialog.latest.onWindowFocusChanged(false); tick(10301);
                    check(!PurplePeekControls.isPending() && PurpleGate.starts == 0, "another window focus loss ends handoff and cancels");
                } else if (scenario.equals("focus-handoff-timeout")) {
                    tick(10300); tick(10600);
                    check(!PurplePeekControls.isPending() && PurpleGate.starts == 0, "missing revealed focus callback cannot preserve wait indefinitely");
                } else {
                    AndroidUtilities.due();
                    check(PurplePeekControls.isPending() && PurpleGate.starts == 0, "asynchronous returning focus preserves genuine unlock");
                    tick(10499); invoke(p, "complete"); check(PurpleGate.starts == 0, "focus handoff retains499ms input guard");
                    tick(10500); action.performClick();
                    check(PurpleGate.starts == 1 && !PurplePeekControls.isPending(), "fresh confirmation after returning focus and guard applies once");
                }
            } else if (scenario.equals("late-input")) {
                tick(2000); tick(2500);
                SharedConfig.appLocked = true; PurpleGate.locked();
                AndroidUtilities.due();
                SharedConfig.appLocked = false; SystemClock.now = 10000;
                invoke(p, "complete");
                check(PurpleGate.starts == 0 && !action.isEnabled(), "queued confirmation resamples unlock and rearms guard itself");
                tick(10500); action.performClick(); check(PurpleGate.starts == 1, "fresh confirmation after resumed guard succeeds");
            } else if (scenario.equals("screen")) {
                owner.broadcast(new Intent(Intent.ACTION_SCREEN_OFF)); owner.app.pause(owner);
                tick(2000); check(PurplePeekControls.isPending() && PurpleGate.starts == 0, "screen-off signal keeps countdown into Ready");
                owner.app.power.interactive = true; owner.app.resume(owner);
                check(PurplePeekControls.isPending() && Dialog.latest.visible && !action.isEnabled(), "resume resamples missed screen-on");
                tick(2500); action.performClick(); check(PurpleGate.starts == 1, "screen return requires confirmation");
            } else if (scenario.equals("switch") || scenario.equals("late-lock")) {
                owner.app.pause(owner); tick(299);
                check(PurplePeekControls.isPending(), "ordering settle window stays suspended");
                if (scenario.equals("late-lock")) SharedConfig.appLocked = true;
                tick(300);
                check(!PurplePeekControls.isPending() && PurpleGate.starts == 0 && owner.receivers.isEmpty(), "300ms switch cancels even when late lock arrives");
            } else if (scenario.equals("focus")) {
                Dialog.latest.onWindowFocusChanged(false); tick(300);
                check(!PurplePeekControls.isPending(), "another window cancels");
            } else if (scenario.equals("quick-return")) {
                owner.app.pause(owner); SystemClock.now = 1; owner.app.resume(owner);
                check(!PurplePeekControls.isPending(), "quick app switchback cancels");
            } else if (scenario.equals("timeout")) {
                tick(2000); tick(11999); check(PurplePeekControls.isPending() && PurpleGate.starts == 0, "Ready remains until originaldeadline+timeout");
                tick(12000); action.performClick();
                check(!PurplePeekControls.isPending() && PurpleGate.starts == 0 && owner.receivers.isEmpty(), "timeout quietly releases without apply");
            } else if (scenario.equals("stale")) {
                tick(2000); tick(2500); PurpleGate.reload("external settings revision"); action.performClick();
                check(!PurplePeekControls.isPending() && PurpleGate.starts == 0, "confirmation revalidates revision");
            } else throw new AssertionError(scenario);
        }
        System.out.println("PEEK_READY_" + scenario + "_CHECKS=" + checks);
    }
}
