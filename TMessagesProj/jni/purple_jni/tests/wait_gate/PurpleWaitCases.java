package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.telegram.messenger.UserConfig;

public final class PurpleWaitCases {
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        ++checks;
    }

    private static void machine(File parent) throws Exception {
        check(!PurpleWaitGate.presetWait(true, "normal", "work"), "leaving Normal is immediate");
        check(!PurpleWaitGate.presetWait(true, "normal", null), "Normal no-op");
        check(!PurpleWaitGate.presetWait(false, "work", "WORK"), "case-insensitive no-op");
        check(PurpleWaitGate.presetWait(false, "work", "strict"), "even a stricter preset waits");
        check(PurpleWaitGate.presetWait(false, "work", null), "entering Normal waits");
        check(PurpleWaitGate.presetWait(false, "work", ""), "empty preset means Normal");
        for (int before = 0; before < 3; ++before) for (int after = 0; after < 3; ++after) {
            check(PurpleWaitGate.modeWait(before, after) == (before != after && before != 2),
                    "Own/Follow/Stock matrix " + before + "/" + after);
        }
        check(PurpleWaitGate.sourceWait(false, true), "source that enters Normal waits");
        check(!PurpleWaitGate.sourceWait(true, true), "source already in Normal is immediate");
        check(!PurpleWaitGate.sourceWait(false, false), "source preserving restrictive state is immediate");
        check(PurplePeekSnapshot.waitSeconds(10, 1) == 10, "lowering the delay still waits for the old value");
        check(PurplePeekSnapshot.waitSeconds(1, 10) == 10, "raising the delay waits for the new value");
        File state = new File(parent, "state.toml");
        byte[] active = "active_preset=\"work\"\n[peek]\nactive_p=true\ndeadline=9999999999\n".getBytes(StandardCharsets.UTF_8);
        Files.write(state.toPath(), active);
        for (int seconds : new int[]{0, 1, 10, Integer.MAX_VALUE}) {
            long started = 1000L, end = started + seconds * 1000L;
            PurpleWaitGate gate = new PurpleWaitGate(started, seconds);
            check(gate.remaining(started) == seconds * 1000L, "monotonic deadline has no int overflow");
            if (seconds > 0) {
                check(gate.poll(end - 1, true, true, false) == PurpleWaitGate.Outcome.WAITING, "no early reveal");
                check(gate.complete(end - 1, true, true, false, true) == PurpleWaitGate.Outcome.WAITING, "early completion cannot write");
            }
            check(gate.poll(end, true, true, false) == PurpleWaitGate.Outcome.READY, "expiry is ready");
            check(gate.complete(end, true, true, false, true) == PurpleWaitGate.Outcome.READY, "valid completion consumes once");
            check(gate.complete(end, true, true, false, true) == PurpleWaitGate.Outcome.FINISHED, "duplicate callback cannot apply twice");
            check(gate.abort() == PurpleWaitGate.Outcome.FINISHED, "dismissal after completion cannot change outcome");
        }
        for (int cause = 0; cause < 5; ++cause) {
            PurpleWaitGate gate = new PurpleWaitGate(1000, 10);
            PurpleWaitGate.Outcome outcome = cause == 0 ? gate.abort()
                    : gate.poll(11000, cause != 1, cause != 2, cause == 3);
            if (cause == 4) outcome = gate.complete(11000, true, true, false, false);
            check(outcome == (cause == 4 ? PurpleWaitGate.Outcome.HELD : PurpleWaitGate.Outcome.ABORTED),
                    "abort/back/close, context, background, lock or held write is terminal");
            check(gate.complete(12000, true, true, false, true) == PurpleWaitGate.Outcome.FINISHED, "late callback after abort does nothing");
            check(Arrays.equals(active, Files.readAllBytes(state.toPath())), "abort preserves the active Peek byte for byte");
        }
        PurpleWaitGate zero = new PurpleWaitGate(1000, 0);
        check(zero.complete(1000, false, true, false, true) == PurpleWaitGate.Outcome.ABORTED,
                "zero delay still revalidates");
    }

    private static void account(File parent, boolean changeContext) throws Exception {
        File root = PurpleListCaptureCases.root(parent);
        PurpleListCaptureCases.signedIn(101, 202);
        check(PurpleAccountOwner.chooseOwner(PurpleAccountFiles.capture(0).key), "explicit settings owner");
        UserConfig.selectedAccount = 1;
        PurpleAccountFiles.Identity target = PurpleAccountFiles.capture(1);
        check(target != null && target.current(), "capture the opening account");
        File own = new File(root, "accounts/0-202/settings.toml");
        PurpleListCaptureCases.write(own, "version=2\n");
        PurpleAccountEditorModel.Context context = new PurpleAccountEditorModel.Context() {
            public boolean current(boolean refreshed) { return target.current(); }
            public void refresh() { }
            public void notifyChanged() { }
        };
        PurpleRoutedWriteModel opening = new PurpleRoutedWriteModel(root, own, new File(root, "settings.toml"),
                "0-101", "0-101", target.key, false, PurpleGlobalAccountChoice.PHYSICAL, context, PurpleEditorDirectories.NATIVE);
        check(opening.mode == 0 && opening.current(), "opening route is Own");
        byte[] local = Files.readAllBytes(new File(root, "local.toml").toPath());
        PurpleWaitGate gate = new PurpleWaitGate(1000, 10);
        UserConfig.selectedAccount = 0;
        check(target.current(), "selecting another account keeps the opening identity current");
        if (changeContext) {
            PurpleListCaptureCases.write(own, "version=2\n[last_seen]\nreasons_p=false\n");
            check(!opening.current(), "a changed account file invalidates the request");
        }
        PurpleWaitGate.Outcome outcome = gate.complete(11000, opening.current(), true, false, true);
        if (changeContext) {
            check(outcome == PurpleWaitGate.Outcome.ABORTED, "context change aborts");
            check(Arrays.equals(local, Files.readAllBytes(new File(root, "local.toml").toPath())), "abort leaves local choice untouched");
        } else {
            check(outcome == PurpleWaitGate.Outcome.READY, "account switch alone permits completion");
            PurpleRoutedWriteModel fresh = new PurpleRoutedWriteModel(root, own, new File(root, "settings.toml"),
                    "0-101", "0-101", target.key, false, PurpleGlobalAccountChoice.PHYSICAL, context, PurpleEditorDirectories.NATIVE);
            check(fresh.current() && fresh.setMode(2), "recaptured writer applies to opening account");
            byte[] after = Files.readAllBytes(new File(root, "local.toml").toPath());
            check(PurpleRoutedWriteModel.modeNative(after, "0-202", "0-101") == 2, "opening account becomes Stock");
            check(PurpleRoutedWriteModel.modeNative(after, "0-101", "0-101") == 0, "selected account stays Own");
        }
    }

    private static void held(File parent) throws Exception {
        File root = PurpleListCaptureCases.root(parent);
        PurpleListCaptureCases.signedIn(101, 202);
        PurpleWriter.Capture refused = PurpleWriter.captureResult(0, false, () -> true, null);
        check(refused.session == null && "owner_not_ready".equals(refused.reason), "held capture is named before any countdown");
        check(!new File(root, "local.toml").exists(), "refusal writes no local choice");
        check(!new File(root, "state.toml").exists(), "refusal starts no Peek");
    }

    public static int run(String scenario, File parent) throws Exception {
        checks = 0;
        switch (scenario) {
            case "machine": machine(parent); break;
            case "account": account(parent, false); break;
            case "context": account(parent, true); break;
            case "held": held(parent); break;
            default: throw new AssertionError(scenario);
        }
        return checks;
    }
}
