package org.telegram.messenger.purple;

import android.app.KeyguardManager;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.PurplePeekControls;

public final class PurplePeekForeground {
    public interface Resolver { PurpleCore.Loaded load(byte[] state); }
    private static final PurplePeekFocusModel focus = new PurplePeekFocusModel();
    private static final ThreadLocal<Boolean> checking = new ThreadLocal<>();
    private static boolean initialized, foreground, cold = true, forceEnd, cleanupPending, resolving, reported;
    private static boolean lastActive;
    private static long lastDeadline, session, lastRetry = -1000;
    private static Runnable timer;

    public static void init(Application application) {
        if (initialized) return;
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector == null) return;
        initialized = true;
        foreground = detector.isForeground();
        detector.addListener(new ForegroundDetector.Listener() {
            @Override public void onBecameForeground() { visibility(true); }
            @Override public void onBecameBackground() { visibility(false); }
        });
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { if (locked()) screenOff(); else resumed(); }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static boolean locked() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return true;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return SharedConfig.appLocked || power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked();
    }

    public static void visibility(boolean visible) {
        PurplePeekControls.presenceChanged();
        boolean enabled = PurplePeekLocalSettings.snapshot().enabled;
        boolean due;
        synchronized (PurplePeekForeground.class) {
            due = visible && enabled && (focus.expired(SystemClock.elapsedRealtime()) || locked());
        }
        if (due) refresh();
        synchronized (PurplePeekForeground.class) {
            foreground = visible;
            due = visible && cleanupPending;
            if (!cleanupPending && !forceEnd) due |= focus.observe(SystemClock.elapsedRealtime(), enabled, foreground, locked(), lastActive, session);
        }
        if (due) refresh();
        published();
    }

    public static void screenOff() {
        PurplePeekControls.presenceChanged();
        if (PurplePeekLocalSettings.snapshot().enabled) endForLock();
    }

    public static void appLocked() {
        PurplePeekControls.presenceChanged();
        if (PurplePeekLocalSettings.snapshot().enabled) endForLock();
        else PurpleGate.lockedLegacy();
    }

    private static void endForLock() {
        boolean ending;
        synchronized (PurplePeekForeground.class) {
            ending = lastActive || cleanupPending;
            if (ending) forceEnd = true;
        }
        if (ending) refresh();
    }

    public static void policyChanged() {
        PurplePeekControls.cancel();
        synchronized (PurplePeekForeground.class) {
            if (focus.observe(SystemClock.elapsedRealtime(), PurplePeekLocalSettings.snapshot().enabled, foreground, locked(), lastActive, session)) forceEnd = true;
        }
        refresh();
        published();
    }

    public static synchronized void replaced() { ++session; }
    public static synchronized boolean canDisable() { return !cleanupPending; }

    public static synchronized PurpleCore.PeekChange stopState(byte[] state) {
        PurpleCore.PeekChange change = PurpleCore.stopPeek(state);
        if (change != null && change.text != null) {
            boolean saved = PurpleState.write(change.text.getBytes(StandardCharsets.UTF_8));
            if (saved) { cleanupPending = false; reported = false; forceEnd = false; lastActive = false; focus.clear(); }
            else if (PurplePeekLocalSettings.snapshot().enabled) {
                cleanupPending = true;
                FileLog.e("Purple: Peek ended locally; state cleanup pending");
            }
        }
        return change;
    }

    public static synchronized PurpleCore.Loaded resolve(PurpleCore.Loaded next, byte[] state, Resolver resolver) {
        if (next == null || !next.ok) return next;
        resolving = true;
        try {
            boolean enabled = PurplePeekLocalSettings.snapshot().enabled;
            boolean startup = cold;
            cold = false;
            if (next.clock.peeking != lastActive || next.clock.peekDeadline != lastDeadline) ++session;
            lastActive = next.clock.peeking;
            lastDeadline = next.clock.peekDeadline;
            boolean end = focus.observe(SystemClock.elapsedRealtime(), enabled, foreground, locked(), lastActive, session);
            if (cleanupPending || enabled && lastActive && (startup || forceEnd || end)) {
                PurpleCore.PeekChange change = stopState(state);
                if (change == null || change.text == null) return null;
                next = resolver.load(change.text.getBytes(StandardCharsets.UTF_8));
                if (next == null || next.clock.peeking) return null;
                lastActive = false;
                lastDeadline = next.clock.peekDeadline;
                focus.clear();
                forceEnd = false;
            }
            if (!lastActive) forceEnd = false;
            return next;
        } finally { resolving = false; }
    }

    private static void resumed() {
        boolean pending;
        synchronized (PurplePeekForeground.class) { pending = cleanupPending || forceEnd; }
        if (pending) refresh(); else beforeUse();
    }

    public static void beforeUse() {
        if (Boolean.TRUE.equals(checking.get())) return;
        boolean due;
        synchronized (PurplePeekForeground.class) {
            if (resolving) return;
            long now = SystemClock.elapsedRealtime();
            due = PurplePeekLocalSettings.snapshot().enabled && lastActive && (locked() || focus.expired(now))
                    || cleanupPending && now - lastRetry >= 1000;
            if (due) lastRetry = now;
        }
        if (!due) return;
        checking.set(true);
        try { refresh(); } finally { checking.remove(); }
    }

    public static void published() {
        synchronized (PurplePeekForeground.class) { arm(); }
        AndroidUtilities.runOnUIThread(PurplePeekForeground::reportFailure);
    }

    private static void reportFailure() {
        synchronized (PurplePeekForeground.class) {
            if (!cleanupPending || reported || !foreground || locked()) return;
            reported = true;
        }
        Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.PurplePeekCleanupPending), Toast.LENGTH_LONG).show();
    }

    private static void refresh() {
        boolean nested = Boolean.TRUE.equals(checking.get());
        checking.set(true);
        try {
            boolean ending;
            synchronized (PurplePeekForeground.class) {
                ending = cleanupPending || PurplePeekLocalSettings.snapshot().enabled && lastActive
                        && (forceEnd || locked() || focus.expired(SystemClock.elapsedRealtime()));
                if (ending) { cleanupPending = true; forceEnd = true; }
            }
            if (ending) {
                PurpleGate.withdrawPeek();
                try {
                    byte[] state = PurpleState.read();
                    if (state == null) throw new IllegalStateException("Purple: Peek cleanup state unavailable");
                    stopState(state);
                }
                catch (RuntimeException | LinkageError e) { FileLog.e(e); }
            }
            try { if (PurpleGate.everLoaded()) PurpleGate.reload("peek foreground policy"); }
            catch (RuntimeException | LinkageError e) { FileLog.e(e); }
            published();
        } finally { if (nested) checking.set(true); else checking.remove(); }
    }

    private static void arm() {
        if (timer != null) { AndroidUtilities.cancelRunOnUIThread(timer); timer = null; }
        long deadline = focus.deadline();
        if (deadline < 0 || !PurplePeekLocalSettings.snapshot().enabled) return;
        long ticket = focus.generation();
        timer = () -> {
            synchronized (PurplePeekForeground.class) { if (ticket != focus.generation()) return; }
            PurplePeekControls.cancel();
            refresh();
            published();
        };
        long delay = deadline - SystemClock.elapsedRealtime();
        AndroidUtilities.runOnUIThread(timer, delay > 0 ? delay : 1000);
    }
    private PurplePeekForeground() { }
}
