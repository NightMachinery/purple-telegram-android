package org.telegram.ui;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ScreenReceiver;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.purple.*;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ForegroundDetector;

public final class PeekFocusCases {
    private static int checks;
    private static Application app;
    private static Activity activity;
    private static BaseFragment fragment;
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean yes, String message) { ++checks; if (!yes) throw new AssertionError(message); }
    private static void setup(boolean enabled, boolean active, boolean fail) {
        app = new Application();
        ApplicationLoader.applicationContext = app;
        if (enabled) { app.prefs.values.put("end_on_focus_loss", true); app.prefs.values.put("revision", 3L); }
        activity = new Activity(app);
        fragment = new BaseFragment(); fragment.activity = activity;
        PurpleState.bytes = bytes("active_preset=\"work\"\npeek_active=" + active + "\npeek_deadline_unix=0\n");
        PurpleState.fail = fail;
        new ForegroundDetector(app);
        PurplePeekForeground.init(app);
        app.start(activity);
        PurpleGate.reload("fixture startup");
        check(PurpleGate.loaded.ok && PurpleGate.loaded.preset.equals("work") && !PurpleGate.loaded.normal, "real native resolution preserves the Work Mode preset");
        check(PurpleGate.loaded.clock.peekStartDelay == 2, "real core resolves the fixture delay");
    }
    private static void active() {
        PurpleGate.startPeek(0, PurpleGate.snapshot);
        check(PurpleGate.peeking(), "a new active Peek is admitted");
    }
    private static Object pending() throws Exception {
        Field f = PurplePeekControls.class.getDeclaredField("pending"); f.setAccessible(true); return f.get(null);
    }
    static void complete(Object operation) throws Exception {
        Method m = operation.getClass().getDeclaredMethod("complete"); m.setAccessible(true); m.invoke(operation);
    }
    private static void screenOff() {
        app.power.interactive = false;
        activity.broadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        new ScreenReceiver().onReceive(app, new Intent(Intent.ACTION_SCREEN_OFF));
    }
    static void confirm(Object operation) throws Exception {
        Method tick = operation.getClass().getDeclaredMethod("tick"); tick.setAccessible(true); tick.invoke(operation);
        SystemClock.now += 500;
        complete(operation);
    }
    private static void now(long time) { SystemClock.now = time; }
    private static void due(long time) { now(time); AndroidUtilities.due(); }
    private static void disabling() {
        PurplePeekControls.changeFocusPolicy(fragment, false);
        check(PurplePeekControls.isPending(), "disabling starts the Work Mode wait");
        check(PurplePeekLocalSettings.snapshot().enabled, "protection remains enabled during the wait");
    }
    private static void model() {
        PurplePeekFocusModel m = new PurplePeekFocusModel();
        check(!m.observe(0, false, false, false, true, 1), "off never schedules ending");
        check(!m.observe(10000, false, false, true, true, 1), "off preserves Peek even on lock");
        check(!m.observe(0, true, false, false, true, 1) && m.deadline() == 2000, "background starts a 2 s deadline");
        check(!m.observe(1999, true, false, false, true, 1), "1999 ms keeps Peek");
        check(m.observe(2000, true, false, false, true, 1), "2000 ms ends Peek");
        m.clear(); m.observe(0, true, false, false, true, 1);
        check(!m.observe(1500, true, false, false, true, 2) && m.deadline() == 3500, "replacement Peek receives fresh grace");
        check(!m.observe(2000, true, false, false, true, 2), "old deadline cannot end replacement");
        check(!m.observe(3499, true, false, false, true, 2) && m.observe(3500, true, false, false, true, 2), "replacement uses its own exact boundary");
        m.clear(); m.observe(100, true, false, false, true, 3);
        check(!m.observe(1000, true, false, false, true, 3) && m.deadline() == 2100, "unrelated update does not extend grace");
        check(!m.observe(2000, true, true, false, true, 3) && m.deadline() < 0, "return within grace clears it");
        check(m.observe(2000, true, true, true, true, 3), "lock is immediate even with a started Activity");
        check(!m.observe(2000, true, false, false, false, 3), "no Peek clears the work");
    }
    private static void background() {
        setup(true, false, false); active();
        app.stop(activity);
        due(1999); check(PurpleGate.peeking(), "started-activity boundary keeps Peek at1999 ms");
        due(2000); check(!PurpleGate.peeking(), "background timer ends Peek at2000 ms");
        check(Toast.shown == 0, "normal automatic ending stays quiet");
    }
    private static void replacement() {
        setup(true, false, false); active(); app.stop(activity);
        Runnable old = AndroidUtilities.delayed.keySet().iterator().next();
        now(1500); long future = System.currentTimeMillis() / 1000 + 300;
        PurpleState.bytes = bytes("active_preset=\"work\"\npeek_active=true\npeek_deadline_unix=" + future + "\n");
        PurpleGate.reload("external replacement");
        now(2000); old.run(); check(PurpleGate.peeking(), "a stale scheduled callback cannot end a replacement Peek");
        now(2500); PurpleGate.reload("unrelated update");
        due(3499); check(PurpleGate.peeking(), "unrelated reload retains replacement deadline");
        due(3500); check(!PurpleGate.peeking(), "replacement ends at its own deadline");
    }
    private static void transitions() {
        setup(true, false, false); active();
        Activity overlay = new Activity(app); app.start(overlay); app.stop(activity);
        due(10000); check(PurpleGate.peeking(), "internal media Activity remains started and keeps Peek");
        app.stop(overlay); now(11999); app.start(activity);
        check(PurpleGate.peeking(), "rotation or a short zero-started gap is bridged");
        app.stop(activity); now(13999); app.start(activity);
        check(!PurpleGate.peeking(), "late resume enforces elapsed deadline before foreground even with suspended timers");
    }
    private static void off() {
        setup(false, true, false);
        check(!PurplePeekLocalSettings.snapshot().enabled && PurpleGate.peeking(), "missing option is OFF and restores existing Peek");
        app.stop(activity); due(10000); check(PurpleGate.peeking(), "optionOFF keeps an active Peek after10 s background");
        screenOff();
        check(PurpleGate.peeking(), "optionOFF preserves active Peek across screen-off");
        SharedConfig.appLocked = true; PurpleGate.locked();
        check(PurpleGate.peeking(), "optionOFF preserves the legacy default app-lock policy");
        check(Toast.shown == 0, "optionOFF lifecycle events are quiet");
    }
    private static void lock(String kind) {
        setup(true, false, false); active();
        if (kind.equals("screen")) screenOff();
        else { SharedConfig.appLocked = true; PurpleGate.locked(); }
        check(!PurpleGate.peeking(), "optionON ends Peek immediately on " + kind);
        check(Toast.shown == 0, "lock ending is quiet");
    }
    private static void legacyLock() {
        setup(false, false, false);
        PurpleGate.settings = bytes("version=1\n[peek]\nstart_delay_seconds=2\nend_on_app_lock_mobile_p=true\n[presets.work]\n");
        PurpleGate.reload("legacy mobile policy"); active(); SharedConfig.appLocked = true; PurpleGate.locked();
        check(!PurpleGate.peeking(), "existing independent mobile app-lock policy still ends Peek with optionOFF");
    }
    private static void startup() {
        setup(true, true, false);
        check(!PurpleGate.peeking(), "optionON process restart publishes Peek-off");
        check(!PurpleCore.load(PurpleGate.settings, PurpleState.read(), "fixture", "android", "mobile").clock.peeking, "startup ending is durable");
    }
    private static void failedState() {
        setup(true, true, true);
        check(!PurpleGate.peeking(), "failed state write still suppresses effective Peek");
        check(PurpleCore.load(PurpleGate.settings, PurpleState.read(), "fixture", "android", "mobile").clock.peeking, "the fixture confirms persisted bytes really remain stale");
        check(!PurplePeekForeground.canDisable(), "failed cleanup cannot be hidden by disabling the option");
        PurplePeekControls.changeFocusPolicy(fragment, false);
        check(!PurplePeekControls.isPending() && PurplePeekLocalSettings.snapshot().enabled, "disable refuses while cleanup is pending");
        check(Toast.shown == 1, "the foreground reports the durability failure once");
        PurpleState.fail = false; app.stop(activity); app.start(activity);
        check(!PurpleGate.peeking() && PurplePeekForeground.canDisable(), "foreground retries cleanup without exposing persisted Peek");
        check(!PurpleCore.load(PurpleGate.settings, PurpleState.read(), "fixture", "android", "mobile").clock.peeking, "retry clears persisted Peek");
        check(PurpleGate.loaded.preset.equals("work"), "failure and retry preserve the active preset");
    }
    private static void toggles() throws Exception {
        setup(false, false, false); PurpleWriter.held = true;
        PurplePeekControls.changeFocusPolicy(fragment, true);
        check(PurplePeekLocalSettings.snapshot().enabled && !PurplePeekControls.isPending(), "enabling is instant without file writer ownership");
        check(PurpleWriter.captures == 0, "local policy never captures a synced-file writer");
        disabling(); Object stale = pending(); int commits = app.prefs.commits, writes = PurpleState.writes;
        app.pause(activity); due(SystemClock.now + 300);
        check(!PurplePeekControls.isPending() && Dialog.dismissed == 1 && AndroidUtilities.delayed.isEmpty(), "pause dismisses the full-screen wait and removes ticks");
        now(3000); complete(stale);
        check(app.prefs.commits == commits && PurpleState.writes == writes, "cancelled completion cannot write");
        check(PurplePeekLocalSettings.snapshot().enabled && Toast.shown == 0, "abort preserves ON quietly");
        disabling(); long begin = SystemClock.now;
        due(begin + 1999); check(PurplePeekLocalSettings.snapshot().enabled, "disable remains ON at1999 ms");
        due(begin + 2000); check(PurplePeekLocalSettings.snapshot().enabled && PurplePeekControls.isPending(), "countdown ends at Ready without applying"); confirm(pending()); check(!PurplePeekLocalSettings.snapshot().enabled && !PurplePeekControls.isPending(), "disable commits on fresh confirmation");
        check(PurpleWriter.captures == 0, "Follow/Stock-style held writer does not block device-local toggles");
        PurpleGate.settings = bytes("version=1\n[peek]\nstart_delay_seconds=0\n[presets.work]\n"); PurpleGate.reload("zero delay");
        PurplePeekControls.changeFocusPolicy(fragment, true); int shown = Dialog.shown;
        PurplePeekControls.changeFocusPolicy(fragment, false);
        check(!PurplePeekLocalSettings.snapshot().enabled && Dialog.shown == shown, "configured zero delay is immediate");
        check(PurplePeekLocalSettings.snapshot().revision == 4, "local revision increments once per successful change");
    }
    private static void cancellation(String cut) throws Exception {
        boolean enabled = cut.endsWith("-on");
        if (enabled) cut = cut.substring(0, cut.length() - 3);
        setup(enabled, false, false);
        PurplePeekControls.start(fragment, 60); Object stale = pending();
        check(stale != null && Dialog.shown == 1, "real controller opens a start wait");
        int writes = PurpleState.writes;
        if (cut.equals("pause")) app.pause(activity);
        else if (cut.equals("stop")) app.stop(activity);
        else if (cut.equals("destroy")) app.destroy(activity);
        else if (cut.equals("screen")) screenOff();
        else { SharedConfig.appLocked = true; PurpleGate.locked(); }
        boolean lock = cut.equals("screen") || cut.equals("launch") || cut.equals("external");
        if (lock) {
            due(10000); complete(stale);
            check(PurplePeekControls.isPending() && PurpleGate.starts == 0, "lock keeps Ready without applying: " + cut);
            app.power.interactive = true; SharedConfig.appLocked = false;
            activity.broadcast(new Intent(Intent.ACTION_SCREEN_ON)); app.resume(activity);
            check(PurplePeekControls.isPending() && PurpleGate.starts == 0, "unlock rearms the guard");
            now(10500); complete(stale);
            check(!PurplePeekControls.isPending() && PurpleGate.starts == 1 && activity.receivers.isEmpty(), "unlocked confirmation applies and releases receiver");
            return;
        }
        if (!cut.equals("destroy")) due(300);
        check(!PurplePeekControls.isPending() && Dialog.dismissed == 1 && AndroidUtilities.delayed.isEmpty(), cut + " dismisses and unschedules");
        now(10000); complete(stale);
        check(PurpleGate.starts == 0 && PurpleState.writes == writes && Toast.shown == 0, cut + " stays quiet and never writes after cancel");
    }
    private static void localRevocation(String cut) throws Exception {
        setup(true, false, false); disabling(); Object stale = pending(); int commits = app.prefs.commits;
        if (cut.equals("selection")) { PurpleArchiveViewerModel.selectionChanged(1); PurpleArchiveViewerModel.selectionChanged(0); }
        else if (cut.equals("logout")) PurpleAccountFiles.signedIn = false;
        else if (cut.equals("settings")) PurpleGate.reload("delay source changed");
        else if (cut.equals("revision")) { PurplePeekLocalSettings.commitFocusPolicy(PurplePeekLocalSettings.snapshot(), false); commits = app.prefs.commits; }
        else if (cut.equals("view")) fragment.view.attached = false;
        else if (cut.equals("screen")) screenOff();
        else { SharedConfig.appLocked = true; PurpleGate.locked(); }
        now(3000); complete(stale);
        boolean lock = cut.equals("screen") || cut.equals("launch") || cut.equals("external");
        if (lock) {
            check(PurplePeekControls.isPending() && app.prefs.commits == commits, "lock preserves local wait: " + cut);
            PurplePeekControls.cancel();
        } else check(!PurplePeekControls.isPending() && Dialog.dismissed == 1 && app.prefs.commits == commits, "local policy wait revoked by " + cut);
        check(Toast.shown == 0, "revocation is quiet: " + cut);
    }
    private static void enableDuringWait() {
        setup(false, false, false); PurplePeekControls.start(fragment, 60);
        check(PurplePeekControls.isPending(), "start countdown is pending");
        PurplePeekControls.changeFocusPolicy(fragment, true);
        check(!PurplePeekControls.isPending() && PurplePeekLocalSettings.snapshot().enabled && PurpleGate.starts == 0, "enabling cancels an old wait and applies in the same request");
    }
    private static void failedPreference() throws Exception {
        setup(true, false, false); app.prefs.fail = true; disabling(); due(2000); confirm(pending());
        check(PurplePeekLocalSettings.snapshot().enabled && !PurplePeekControls.isPending(), "failed disable preserves cached protection");
        check(Boolean.TRUE.equals(app.prefs.values.get("end_on_focus_loss")), "failed commit's in-memory preference mutation is rolled back");
        check(PurpleWriterUI.explanations == 1, "failed local write is reported without claiming success");
    }
    private static void resumedLock() {
        setup(true, false, false); active();
        app.keyguard.locked = true; app.resume(activity);
        check(!PurpleGate.peeking(), "resume rechecks a missed keyguard signal while Activity remains started");
    }
    private static void gateBackstop() {
        setup(true, false, false); active(); app.stop(activity); now(2000);
        PurplePeekForeground.beforeUse();
        check(!PurpleGate.peeking(), "gate use enforces elapsed deadline when timer delivery is suspended");
    }
    private static void hiddenExit() {
        setup(true, false, false); active();
        ChatActivity chat = new ChatActivity(); chat.activity = activity; PurplePeekControls.chatResumed(chat);
        org.telegram.tgnet.TLRPC.TL_dialog dialog = new org.telegram.tgnet.TLRPC.TL_dialog(); dialog.id = 1;
        org.telegram.messenger.MessagesController.getInstance(0).dialogs_dict.put(1, dialog); PurpleGate.hidden = true;
        app.stop(activity); due(2000);
        check(chat.isFinished && PurpleGate.loaded.preset.equals("work"), "normal End Peek hidden-chat exit runs and preserves the preset");
    }
    private static void endButton() {
        setup(false, false, false); active();
        org.telegram.ui.ActionBar.ActionBarMenu menu = new org.telegram.ui.ActionBar.ActionBarMenu();
        PurplePeekControls.addEndButton(menu); check(menu.last.visibility == android.view.View.VISIBLE, "End Peek is available before a restart wait");
        PurplePeekControls.start(fragment, 60); check(menu.last.visibility == android.view.View.GONE, "pending wait temporarily hides End Peek");
        app.pause(activity); due(SystemClock.now + 300); check(menu.last.visibility == android.view.View.VISIBLE, "cancel immediately restores End Peek");
    }
    private static void refusedReload(String cut) {
        setup(true, false, false); active(); PurpleGate.rejectReload = true; PurpleState.fail = true;
        if (cut.equals("background")) { app.stop(activity); due(2000); }
        else if (cut.equals("late")) { app.stop(activity); now(2000); app.start(activity); }
        else { SharedConfig.appLocked = true; PurpleGate.locked(); }
        check(!PurpleGate.peeking() && !PurpleGate.state().clock.peeking, "refused reload withdraws the effective gate: " + cut);
        check(!PurplePeekForeground.canDisable(), "failed End Peek retains cleanup obligation");
        SharedConfig.appLocked = false; app.resume(activity);
        check(!PurpleGate.peeking(), "unlock/resume cannot restore the revealing gate");
        check(!PurplePeekForeground.canDisable(), "foreground cannot clear an unacknowledged end");
        PurpleState.fail = false; app.resume(activity);
        check(!PurpleGate.peeking() && PurplePeekForeground.canDisable(), "End Peek commits independently of refused settings reload");
        PurpleGate.rejectReload = false; PurpleGate.reload("source recovered");
        check(!PurpleGate.peeking(), "source recovery cannot resurrect the ended Peek");
    }
    public static void main(String[] args) throws Exception {
        PurpleCore.ensureLoaded();
        String scenario = args[0];
        if (scenario.startsWith("reload-failure-")) refusedReload(scenario.substring(15));
        else if (scenario.equals("resume-lock")) resumedLock();
        else if (scenario.equals("gate-backstop")) gateBackstop();
        else if (scenario.equals("hidden-exit")) hiddenExit();
        else if (scenario.equals("end-button")) endButton();
        else if (scenario.equals("model")) model();
        else if (scenario.equals("background")) background();
        else if (scenario.equals("replacement")) replacement();
        else if (scenario.equals("transitions")) transitions();
        else if (scenario.equals("off")) off();
        else if (scenario.equals("startup")) startup();
        else if (scenario.equals("failed-state")) failedState();
        else if (scenario.equals("toggles")) toggles();
        else if (scenario.equals("enable-during-wait")) enableDuringWait();
        else if (scenario.equals("failed-preference")) failedPreference();
        else if (scenario.equals("legacy-lock")) legacyLock();
        else if (scenario.startsWith("lock-")) lock(scenario.substring(5));
        else if (scenario.startsWith("cancel-")) cancellation(scenario.substring(7));
        else if (scenario.startsWith("revoke-")) localRevocation(scenario.substring(7));
        else throw new AssertionError(scenario);
        System.out.println("PEEK_FOCUS_" + scenario + "_CHECKS=" + checks);
    }
}
