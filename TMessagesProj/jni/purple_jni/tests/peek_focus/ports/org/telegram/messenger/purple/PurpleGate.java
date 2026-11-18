package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.PurplePeekControls;

public final class PurpleGate {
    public static byte[] settings = "version=1\n[peek]\nstart_delay_seconds=2\n[presets.work]\n".getBytes(StandardCharsets.UTF_8);
    public static PurpleCore.Loaded loaded;
    public static int starts, reloads, legacyLocks;
    public static PeekSettings snapshot;
    public static boolean loadedOnce, rejectReload, refuseStart, crashBeforeWrite, crashAfterWrite, hidden;
    private static byte[] acceptedSettings;

    public static final class PeekSettings {
        public final PurpleCore.Loaded state;
        public final int account, generation;
        public final PurpleAccountPeek.Session session;
        public final PurpleAccountPeek.Clock clock;
        private final byte[] source;

        public PeekSettings(PurpleCore.Loaded state) { this(UserConfig.selectedAccount, state); }

        private PeekSettings(int account, PurpleCore.Loaded state) {
            this.account = account;
            this.state = state;
            generation = reloads;
            source = settings.clone();
            session = PurpleAccountPeek.capture(account);
            clock = session == null ? null : session.clock();
        }
    }

    public static boolean everLoaded() { return loadedOnce; }
    public static void ensureLoaded() { if (!loadedOnce) reload("fixture load"); }

    public static void reload(String why) {
        if (rejectReload) return;
        if (Thread.holdsLock(PurplePeekForeground.class)) throw new AssertionError("Gate reload under foreground monitor");
        ++reloads;
        loadedOnce = true;
        PurpleCore.Loaded[] previous = new PurpleCore.Loaded[UserConfig.MAX_ACCOUNT_COUNT];
        for (int account = 0; account < previous.length; account++) previous[account] = PurpleWorkMode.project(account, loaded);
        try (PurpleGateLoad.Batch batch = new PurpleGateLoad.Batch()) {
            PurpleCore.Loaded next = batch.load(settings, PurpleState.read());
            if (next == null || batch.commit(next) != PurpleGateLoad.Commit.Published) {
                batch.repairNeeded();
                return;
            }
            loaded = next;
            acceptedSettings = settings.clone();
            File file = PurpleAccountSettings.globalFile();
            if (!PurpleWorkMode.reload(settings, file, PurpleAccountFileSnapshot.read(file),
                    PurpleAccountFileSnapshot.read(new File(file.getPath() + ".good"))))
                throw new AssertionError("fixture work mode publication refused");
            batch.published();
        }
        snapshot = new PeekSettings(UserConfig.selectedAccount, state(UserConfig.selectedAccount));
        PurplePeekForeground.published();
        for (int account = 0; account < previous.length; account++) {
            PurpleCore.Loaded next = PurpleWorkMode.project(account, loaded);
            PurplePeekControls.onAccountGateReload(account, previous[account], next);
        }
    }

    public static PeekSettings peekSettings() { return peekSettings(UserConfig.selectedAccount); }
    public static PeekSettings peekSettings(int account) {
        ensureLoaded();
        return new PeekSettings(account, state(account));
    }

    public static boolean currentPeekSettings(PeekSettings candidate) {
        return candidate != null && candidate.state != null && candidate.state.ok
                && Arrays.equals(candidate.source, acceptedSettings)
                && (candidate.session == null ? candidate.generation == reloads
                        : PurplePeekForeground.mayUse(candidate.session.identity) && candidate.session.matches(candidate.clock));
    }
    public static boolean currentPeekEditSettings(PeekSettings candidate) { return currentPeekSettings(candidate); }
    public static boolean peeking() { return peeking(UserConfig.selectedAccount); }
    public static boolean peeking(int account) { return state(account) != null && state(account).clock.peeking; }
    public static PurpleCore.Loaded state() { return state(UserConfig.selectedAccount); }
    public static PurpleCore.Loaded state(int account) { return PurpleWorkMode.project(account, loaded); }
    public static boolean persistedPeek() {
        PurpleAccountPeek.Session session = PurpleAccountPeek.capture(UserConfig.selectedAccount);
        if (session == null && loaded == null)
            return new String(PurpleState.read(), StandardCharsets.UTF_8).contains("\nactive=true\n");
        PurpleAccountPeek.Clock clock = session == null ? null : session.clock();
        return clock != null && clock.peeking;
    }

    public static void withdrawPeek(PurpleAccountFiles.Identity identity) {
        if (identity == null) return;
        PurpleCore.Loaded before = state(identity.slot);
        if (PurpleWorkMode.withdrawPeek(identity)) {
            PurplePeekControls.onAccountGateReload(identity.slot, before, state(identity.slot));
        }
    }

    private static PurpleCore.PeekChange editResult(PurpleAccountPeek.Result result) {
        return result == null ? null : new PurpleCore.PeekChange(false, result.clock.peeking, result.extended,
                result.seconds, result.left, null);
    }

    public static PurpleCore.PeekChange stopPeek() { return stopPeek(UserConfig.selectedAccount); }
    public static PurpleCore.PeekChange stopPeek(int account) {
        PeekSettings captured = peekSettings(account);
        PurplePeekForeground.requestEnd(PurpleAccountFiles.capture(account));
        PurpleAccountPeek.Result result = captured.session == null ? null : captured.session.stop(captured.clock);
        reload("end");
        return editResult(result);
    }

    public static PurpleCore.PeekChange startPeek(int seconds, PeekSettings candidate) {
        ++starts;
        if (refuseStart) return new PurpleCore.PeekChange(true, false, false, 0, 0, null);
        if (crashBeforeWrite) throw new AssertionError("pre-state crash cut");
        if (!currentPeekSettings(candidate) || candidate.session == null) return null;
        PurpleAccountPeek.Result result = candidate.session.start(candidate.clock, seconds);
        if (result == null) return null;
        if (crashAfterWrite) throw new AssertionError("post-state crash cut");
        PurplePeekForeground.replaced(candidate.session.identity);
        reload("start");
        return editResult(result);
    }
    public static PurpleCore.PeekChange extendPeek(int seconds, PeekSettings candidate) {
        if (!currentPeekSettings(candidate) || candidate.session == null) return null;
        PurpleAccountPeek.Result result = candidate.session.extend(candidate.clock, seconds);
        if (result != null && result.extended) reload("extend");
        return editResult(result);
    }
    public static void locked() { PurplePeekForeground.appLocked(); }
    public static void lockedLegacy() { lockedAccounts(); }
    public static void lockedAccounts() {
        ++legacyLocks;
        for (PurpleAccountFiles.Identity identity : PurpleAccountOwner.signedIn()) {
            PeekSettings candidate = peekSettings(identity.slot);
            if (candidate.session == null || candidate.clock == null || !candidate.clock.peeking) continue;
            PurpleAccountPeek.Result result = candidate.session.locked(candidate.clock, false);
            if (result != null && result.changed) reload("legacy lock");
        }
    }
    public static boolean setPreset(String value) { return true; }
    public static boolean shownWithoutRecent(int account, TLRPC.Dialog dialog) { return !hidden; }
    public static boolean hiddenEverywhereWithoutRecent(int account, TLRPC.Dialog dialog) { return hidden; }
    public static boolean hidingArchive(int account) { return false; }
    public static boolean reachableElsewhere(int account, long dialog,
            java.util.ArrayList<MessagesController.DialogFilter> others) { return true; }
}
