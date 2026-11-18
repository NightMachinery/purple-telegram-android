package org.telegram.messenger.purple;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.PurplePeekControls;

public final class PurplePeekForeground {
    private static final class Entry {
        final PurpleAccountFiles.Identity identity;
        final PurplePeekFocusModel focus = new PurplePeekFocusModel();
        boolean active, pending, forceEnd, reported;
        long deadline, session, lastRetry = -1000;
        Entry(PurpleAccountFiles.Identity identity) { this.identity = identity; }
    }
    private static final class Frame {
        final PurpleAccountFiles.Identity[] identities = new PurpleAccountFiles.Identity[UserConfig.MAX_ACCOUNT_COUNT];
        final PurplePeekLocalSettings.Snapshot policy = PurplePeekLocalSettings.snapshot();
        final int selected = UserConfig.selectedAccount;
        final long now = SystemClock.elapsedRealtime(), unix = System.currentTimeMillis() / 1000L;
        final boolean locked = policy.enabled && deviceLocked();
        Frame() {
            for (int slot = 0; slot < identities.length; ++slot) identities[slot] = PurpleAccountFiles.capture(slot);
        }
    }
    static final class Plan {
        final String[] stoppedKeys;
        final boolean stopAll;
        private final Frame frame;
        private final long version;
        private final boolean visible;
        private Plan(Frame frame) {
            this.frame = frame;
            synchronized (PurplePeekForeground.class) {
                version = revision;
                visible = foreground;
                stopAll = cold && frame.policy.enabled;
                stoppedKeys = dueKeys(frame);
            }
        }
        boolean current() {
            if (!frame.policy.current() || UserConfig.selectedAccount != frame.selected) return false;
            Frame current = new Frame();
            for (int slot = 0; slot < frame.identities.length; ++slot) {
                if (!same(frame.identities[slot], current.identities[slot])) return false;
            }
            synchronized (PurplePeekForeground.class) {
                return version == revision && visible == foreground && frame.policy == current.policy && frame.locked == current.locked
                        && stopAll == (cold && current.policy.enabled) && Arrays.equals(stoppedKeys, dueKeys(current));
            }
        }
        void rejected() {
            synchronized (PurplePeekForeground.class) {
                if (cold && stopAll) coldCleanupPending = true;
            }
            AndroidUtilities.runOnUIThread(PurplePeekForeground::reportFailure);
        }
        void published(boolean all) {
            synchronized (PurplePeekForeground.class) {
                cold = false;
                if (all) coldCleanupPending = coldCleanupReported = false;
                if (revision == version) {
                    for (Entry entry : entries.values()) if (all || Arrays.binarySearch(stoppedKeys, entry.identity.key) >= 0) {
                        entry.active = entry.pending = entry.forceEnd = entry.reported = false;
                        entry.focus.clear();
                    }
                    ++revision;
                }
            }
        }
    }
    private static final Map<String, Entry> entries = new LinkedHashMap<>();
    private static final ThreadLocal<Boolean> checking = new ThreadLocal<>();
    private static boolean initialized, foreground, cold = true;
    private static volatile boolean hasLive;
    private static volatile boolean coldCleanupPending;
    private static boolean coldCleanupReported;
    private static volatile long nextCheck;
    private static long revision;
    private static Runnable timer;

    private static boolean same(PurpleAccountFiles.Identity first, PurpleAccountFiles.Identity second) {
        return first == null ? second == null : second != null && first.slot == second.slot
                && first.lifetime == second.lifetime && first.key.equals(second.key);
    }
    private static boolean due(Entry entry, Frame frame) {
        return entry.pending || entry.forceEnd || entry.active && (entry.deadline > 0 && entry.deadline <= frame.unix
                || frame.policy.enabled && (frame.locked || entry.focus.expired(frame.now)));
    }
    private static String[] dueKeys(Frame frame) {
        TreeSet<String> result = new TreeSet<>();
        for (Entry entry : entries.values()) if (due(entry, frame)
                || !same(entry.identity, frame.identities[entry.identity.slot])) result.add(entry.identity.key);
        return result.toArray(new String[0]);
    }
    static Plan prepare() { return new Plan(new Frame()); }

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
        NotificationCenter.getGlobalInstance().addObserver((id, account, args) -> visibility(foreground),
                NotificationCenter.activeAccountChanged);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { beforeUse(true); }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }
    private static boolean deviceLocked() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return true;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return SharedConfig.appLocked || power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked();
    }
    public static void visibility(boolean visible) {
        PurplePeekControls.presenceChanged();
        Frame frame = new Frame();
        synchronized (PurplePeekForeground.class) {
            if (foreground != visible) { foreground = visible; ++revision; }
            observeFocus(frame);
        }
        nextCheck = 0;
        beforeUse();
        armOnly();
        PurpleScreenTime.accountPeekChanged();
    }
    private static void observeFocus(Frame frame) {
        for (Entry entry : entries.values()) {
            if (entry.pending || entry.forceEnd) continue;
            long prior = entry.focus.generation();
            if (frame.policy.enabled && entry.focus.expired(frame.now)) entry.forceEnd = true;
            else if (entry.focus.observe(frame.now, frame.policy.enabled, foreground && frame.selected == entry.identity.slot,
                    frame.locked, entry.active, entry.session)) entry.forceEnd = true;
            if (prior != entry.focus.generation() || entry.forceEnd) ++revision;
        }
    }
    public static void screenOff() {
        PurplePeekControls.presenceChanged();
        if (PurplePeekLocalSettings.snapshot().enabled) endForLock();
    }
    public static void appLocked() {
        PurplePeekControls.presenceChanged();
        if (PurplePeekLocalSettings.snapshot().enabled) endForLock();
        else PurpleGate.lockedAccounts();
    }
    private static void endForLock() {
        synchronized (PurplePeekForeground.class) {
            for (Entry entry : entries.values()) if (entry.active && !entry.forceEnd) { entry.forceEnd = true; ++revision; }
        }
        nextCheck = 0;
        beforeUse();
    }
    public static void policyChanged() {
        PurplePeekControls.cancel();
        Frame frame = new Frame();
        synchronized (PurplePeekForeground.class) { ++revision; observeFocus(frame); }
        nextCheck = 0;
        beforeUse();
        armOnly();
    }
    static void replaced(PurpleAccountFiles.Identity identity) {
        synchronized (PurplePeekForeground.class) {
            Entry entry = identity == null ? null : entries.get(identity.key);
            if (entry != null && same(identity, entry.identity)) { ++entry.session; entry.focus.clear(); ++revision; }
        }
    }
    public static synchronized boolean canDisable() {
        if (coldCleanupPending) return false;
        for (Entry entry : entries.values()) if (entry.pending) return false;
        return true;
    }
    static synchronized boolean mayUse(PurpleAccountFiles.Identity identity) {
        Entry entry = identity == null ? null : entries.get(identity.key);
        return entry == null || !same(identity, entry.identity) || !entry.pending;
    }
    static void requestEnd(PurpleAccountFiles.Identity identity) {
        if (identity == null) return;
        synchronized (PurpleAccountFiles.class) {
            if (!identity.current()) return;
            synchronized (PurplePeekForeground.class) {
            Entry entry = entries.get(identity.key);
            if (entry == null || !same(entry.identity, identity)) {
                entry = new Entry(identity); entries.put(identity.key, entry);
            }
            entry.pending = entry.forceEnd = true;
            entry.lastRetry = SystemClock.elapsedRealtime();
            hasLive = true;
            ++revision;
            }
            PurpleGate.withdrawPeek(identity);
        }
        armOnly();
    }
    public static void beforeUse() { beforeUse(false); }

    private static void beforeUse(boolean resumed) {
        if (Boolean.TRUE.equals(checking.get())) return;
        if (resumed && coldCleanupPending && PurpleGate.everLoaded()) {
            checking.set(true);
            try { PurpleGate.reload("retry startup Peek cleanup"); }
            catch (RuntimeException | LinkageError e) { FileLog.e(e); }
            finally { checking.remove(); }
        }
        if (!hasLive || !resumed && SystemClock.elapsedRealtime() < nextCheck) return;
        Frame frame = new Frame();
        boolean needed = false;
        synchronized (PurplePeekForeground.class) {
            observeFocus(frame);
            for (Entry entry : entries.values()) {
                if (ready(entry, frame, resumed)) { needed = true; break; }
            }
        }
        if (needed) refresh(frame, resumed); else armOnly();
    }
    public static void published() {
        Frame frame = new Frame();
        PurpleWorkMode.View[] views = new PurpleWorkMode.View[frame.identities.length];
        for (int slot = 0; slot < views.length; ++slot) views[slot] = PurpleWorkMode.view(slot);
        synchronized (PurplePeekForeground.class) {
            entries.entrySet().removeIf(item -> !same(item.getValue().identity, frame.identities[item.getValue().identity.slot]));
            for (PurpleWorkMode.View view : views) {
                if (view == null || !same(view.identity, frame.identities[view.identity.slot])) continue;
                Entry entry = entries.get(view.identity.key);
                if (entry == null) { entry = new Entry(view.identity); entries.put(view.identity.key, entry); ++revision; }
                boolean active = view.mode != 2 && view.clock.peeking;
                long deadline = view.clock.peekDeadline;
                if (entry.active != active || entry.deadline != deadline) { ++entry.session; ++revision; }
                entry.active = active; entry.deadline = deadline;
                if (!active) { entry.pending = entry.forceEnd = entry.reported = false; entry.focus.clear(); }
            }
            observeFocus(frame);
            hasLive = false;
            for (Entry entry : entries.values()) hasLive |= entry.active || entry.pending;
        }
        armOnly();
    }
    private static boolean ready(Entry entry, Frame frame, boolean resumed) {
        return entry.pending ? resumed || frame.now - entry.lastRetry >= 1000 : due(entry, frame);
    }
    private static void refresh(Frame frame, boolean resumed) {
        if (Boolean.TRUE.equals(checking.get())) return;
        checking.set(true);
        try {
            ArrayList<PurpleAccountFiles.Identity> withdrawn = new ArrayList<>();
            synchronized (PurplePeekForeground.class) {
                for (Entry entry : entries.values()) {
                    if (!ready(entry, frame, resumed)) continue;
                    entry.pending = entry.forceEnd = true;
                    entry.lastRetry = frame.now;
                    withdrawn.add(entry.identity);
                    ++revision;
                }
            }
            for (PurpleAccountFiles.Identity identity : withdrawn) {
                PurpleGate.withdrawPeek(identity);
                if (PurpleAccountPeek.stopWithdrawn(identity)) {
                    synchronized (PurplePeekForeground.class) {
                        Entry current = entries.get(identity.key);
                        if (current != null && same(current.identity, identity) && current.pending
                                && current.lastRetry == frame.now) {
                            current.active = current.pending = current.forceEnd = current.reported = false;
                            current.focus.clear();
                            ++revision;
                        }
                    }
                }
            }
            if (!withdrawn.isEmpty() && PurpleGate.everLoaded()) PurpleGate.reload("account Peek foreground policy");
        } catch (RuntimeException | LinkageError e) { FileLog.e(e); }
        finally { checking.remove(); armOnly(); }
    }
    private static long millisUntil(long deadline, long unix) {
        if (deadline <= unix) return 0;
        long seconds = deadline - unix;
        return seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
    }
    private static void armOnly() {
        Frame frame = new Frame();
        long delay = Long.MAX_VALUE;
        synchronized (PurplePeekForeground.class) {
            if (timer != null) { AndroidUtilities.cancelRunOnUIThread(timer); timer = null; }
            for (Entry entry : entries.values()) {
                if (entry.pending) delay = Math.min(delay, Math.max(1, 1000 - (frame.now - entry.lastRetry)));
                else if (entry.forceEnd) delay = 0;
                else if (entry.active) {
                    if (entry.deadline > 0) delay = Math.min(delay, millisUntil(entry.deadline, frame.unix));
                    if (frame.policy.enabled && entry.focus.deadline() >= 0) delay = Math.min(delay, Math.max(0, entry.focus.deadline() - frame.now));
                }
            }
            long checkDelay = Math.min(delay, 1000);
            nextCheck = frame.now > Long.MAX_VALUE - checkDelay ? Long.MAX_VALUE : frame.now + checkDelay;
            if (delay != Long.MAX_VALUE) {
                timer = () -> { beforeUse(); armOnly(); };
                AndroidUtilities.runOnUIThread(timer, delay);
            }
        }
        AndroidUtilities.runOnUIThread(PurplePeekForeground::reportFailure);
    }
    private static void reportFailure() {
        if (!foreground || deviceLocked()) return;
        boolean report = false;
        synchronized (PurplePeekForeground.class) {
            if (coldCleanupPending && !coldCleanupReported) {
                coldCleanupReported = true;
                report = true;
            }
            for (Entry entry : entries.values()) if (entry.pending && !entry.reported) { entry.reported = true; report = true; }
        }
        if (report) Toast.makeText(ApplicationLoader.applicationContext,
                LocaleController.getString(R.string.PurplePeekCleanupPending), Toast.LENGTH_LONG).show();
    }
    private PurplePeekForeground() { }
}
