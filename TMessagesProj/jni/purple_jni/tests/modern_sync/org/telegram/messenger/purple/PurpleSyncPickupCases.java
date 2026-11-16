package org.telegram.messenger.purple;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;
import org.telegram.messenger.*;
import static org.telegram.messenger.purple.PurpleSyncAutoCases.*;
import static org.telegram.messenger.purple.PurpleSyncRuntimeCases.field;
import static org.telegram.messenger.purple.PurpleSyncRuntimeCases.set;
import static org.telegram.messenger.purple.PurpleSyncRuntimeCases.ui;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;

public final class PurpleSyncPickupCases {
    private static int checks, diagnosticFailures, behaviorFailures;

    private static void expect(boolean value, String text, boolean diagnostic) {
        ++checks;
        if (!value) {
            if (diagnostic) ++diagnosticFailures;
            else ++behaviorFailures;
            System.err.println((diagnostic ? "pickup decision diagnostic missing: " : "pickup behavior: ") + text);
        }
    }

    private static void resetLines() throws Exception {
        synchronized (PurpleDiagLog.repeats) {
            Field windows = PurpleDiagRepeats.class.getDeclaredField("windows");
            windows.setAccessible(true);
            ((Map<?, ?>) windows.get(PurpleDiagLog.repeats)).clear();
        }
        FileLog.lines.clear();
    }

    private static List<String> pickupLines() {
        List<String> result = new ArrayList<>();
        synchronized (FileLog.lines) {
            for (String line : FileLog.lines) {
                int start = line.indexOf("sync pickup ");
                if (start >= 0) result.add(line.substring(start));
            }
        }
        return result;
    }

    private static void decision(String stage, String action, String reason, int count, boolean overflow) {
        List<String> lines = pickupLines();
        String expected = "sync pickup slot=1 stage=" + stage + " action=" + action
            + " reason=" + reason + " count=" + count + " overflow=" + (overflow ? 1 : 0);
        expect(lines.size() == 1, "one decision for " + stage + "/" + reason, true);
        expect(lines.contains(expected), expected, true);
        for (String line : lines) {
            expect(line.matches("sync pickup slot=[0-9?]+ stage=(collect|check) action=(requested|rejected|wait) reason=[a-z_]+ count=[0-9]+ overflow=[01]"),
                "fixed typed fields only", true);
            expect(!line.contains("invalid") || reason.equals("invalid_input"), "all supplied tokens accepted", true);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, PurpleSyncChat.Holder> holders() throws Exception {
        Field field = PurpleSyncChat.class.getDeclaredField("accounts");
        field.setAccessible(true);
        return (Map<Integer, PurpleSyncChat.Holder>) field.get(null);
    }

    private static void bind(Map<Integer, PurpleSyncChat.Holder> holders) throws Exception {
        PurpleSyncChat.Holder holder = new PurpleSyncChat.Holder(new PurpleSyncChat.Platform(1));
        Field last = PurpleSyncMagicChat.class.getDeclaredField("last");
        last.setAccessible(true);
        last.set(holder.magic, new PurpleSyncMagicChat.Result(PurpleSyncMagicChat.Result.Kind.Use,
            PurpleSyncChatCore.Peer.self(), PurpleSyncMagicCore.Problem.None, false, 0, true, false, false));
        holders.put(1, holder);
    }

    private static void engine(File root) throws Exception {
        PurpleArchiveLogin.Source source = PurpleArchiveLogin.source;
        Map<Integer, PurpleSyncChat.Holder> holders = holders();
        Map<Integer, PurpleSyncChat.Holder> savedHolders = new HashMap<>(holders);
        PurpleArchiveLogin.source = slot -> "00000000000000000000000000000001";
        try {
            for (String mode : new String[]{"external", "own", "overflow", "backoff", "transient", "busy", "foreign", "closed", "off"}) {
                Port port = bound(root, "pickup-diagnostic-" + mode);
                PurpleSyncOperations.Capability token = null;
                Object owner = new Object();
                String operation = port.account.env.operationKey(1, 202);
                try {
                    initialPublish(port);
                    bind(holders);
                    int own = new JSONObject(Files.readString(new File(port.frame.sync(1), "state.json").toPath()))
                        .getJSONArray("own_messages").getJSONObject(0).getInt("message_id");
                    int[] ids = new int[]{mode.equals("own") ? own : own + 10000};
                    if (mode.equals("overflow")) {
                        ids = new int[129];
                        for (int i = 0; i < ids.length; ++i) ids[i] = own + 10000 + i;
                    }
                    if (mode.equals("backoff")) { port.testingBackoff = true; port.exceptionBackoff = new long[]{1, port.clock + 60}; }
                    if (mode.equals("transient")) port.transientAt = (port.clock + 60) * 1000;
                    if (mode.equals("busy")) {
                        token = PurpleSyncOperations.acquire(operation, owner, () -> true);
                        expect(token != null, "other operation acquired", false);
                    }
                    if (mode.equals("closed")) port.engine.close();
                    if (mode.equals("off")) port.enabled = false;
                    int before = port.checks;
                    resetLines();
                    PurpleSyncChatCore.Peer chat = mode.equals("foreign")
                        ? PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, 99) : PurpleSyncChatCore.Peer.self();
                    port.engine.chatUpdated(chat, ids, false);
                    if (mode.equals("external") || mode.equals("overflow")) {
                        expect(port.checks > before, "external intent checks " + mode, false);
                        decision("check", "requested", mode.equals("overflow") ? "overflow" : "remote_update", Math.min(128, ids.length), mode.equals("overflow"));
                    } else if (mode.equals("own")) {
                        expect(port.checks == before, "own-only suppressed", false);
                        decision("check", "rejected", "own_records", 1, false);
                    } else if (mode.equals("backoff") || mode.equals("transient")) {
                        expect(port.checks == before && port.delay == 60000, "wait preserves original deadline " + mode, false);
                        decision("check", "wait", mode.equals("backoff") ? "retry_at" : "transient_write_wait", 1, false);
                        Field idsField = PurpleSyncAutoEngine.class.getDeclaredField("pickupIds");
                        idsField.setAccessible(true);
                        expect(Arrays.equals((int[]) idsField.get(port.engine), ids), "waiting retains exact intent", false);
                    } else if (mode.equals("busy")) {
                        expect(port.checks == before, "busy waits without a check", false);
                        decision("check", "wait", "busy", 1, false);
                    } else {
                        expect(port.checks == before, "ineligible produces no check " + mode, false);
                        decision("check", "rejected", mode.equals("off") ? "off" : "ineligible", 1, false);
                    }
                    expect(port.posts == 1, "pickup never posts another local record", false);
                    port.frame.unchangedA();
                } finally {
                    port.engine.close();
                    if (token != null) {
                        token.revoke();
                        PurpleSyncOperations.end(operation, owner, token);
                    }
                    holders.clear();
                }
            }
        } finally {
            holders.clear(); holders.putAll(savedHolders);
            PurpleArchiveLogin.source = source;
        }
    }

    @SuppressWarnings("unchecked")
    private static void runtime(File root) throws Exception {
        Map<String, Object> saved = new HashMap<>();
        for (String key : new String[]{"started", "ready", "owner", "global"}) saved.put(key, field(PurpleAccountOwner.class, null, key));
        Map<String, Object> runtimes = (Map<String, Object>) field(PurpleSyncAuto.class, null, "accounts");
        expect(runtimes.isEmpty(), "runtime starts isolated", false);
        Thread prior = android.os.Looper.mainThread;
        boolean paused = ApplicationLoader.mainInterfacePaused;
        boolean locked = SharedConfig.appLocked, passcode = SharedConfig.isWaitingForPasscodeEnter;
        PurpleArchiveLogin.Source loginSource = PurpleArchiveLogin.source;
        PurpleArchiveLogin.source = slot -> "00000000000000000000000000000001";
        Object observing = field(PurpleSyncAuto.class, null, "observing");
        Map<Integer, PurpleSyncChat.Holder> holders = holders();
        Map<Integer, PurpleSyncChat.Holder> savedHolders = new HashMap<>(holders);
        PurpleSyncAuto.Control control = null;
        try {
            Frame frame = new Frame(root, "pickup-runtime", true); frame.initialize(1);
            set(PurpleAccountOwner.class, null, "started", true); set(PurpleAccountOwner.class, null, "ready", true);
            set(PurpleAccountOwner.class, null, "owner", A); set(PurpleAccountOwner.class, null, "global", A);
            set(PurpleSyncAuto.class, null, "observing", true);
            android.os.Looper.mainThread = Thread.currentThread(); ApplicationLoader.mainInterfacePaused = false;
            SharedConfig.appLocked = false; SharedConfig.isWaitingForPasscodeEnter = false;
            AndroidUtilities.pending.clear(); AndroidUtilities.delayed.clear(); DispatchQueue.pending.clear();
            control = PurpleSyncAuto.control(1, status -> {}); DispatchQueue.flush(); ui();
            Object runtime = runtimes.get(B); expect(runtime != null, "real captured runtime", false);
            Class<?> type = runtime.getClass();
            Method enabled = type.getDeclaredMethod("enabledHint", boolean.class); enabled.setAccessible(true); enabled.invoke(runtime, true);
            Method update = type.getDeclaredMethod("updated", PurpleSyncChatCore.Peer.class, List.class); update.setAccessible(true);
            Set<Integer> ids = (Set<Integer>) field(type, runtime, "pickupIds");
            bind(holders);
            resetLines();
            update.invoke(runtime, PurpleSyncChatCore.Peer.self(), Collections.emptyList());
            expect(field(type, runtime, "pickup") == null && ids.isEmpty(), "empty update queues nothing", false);
            decision("collect", "rejected", "invalid_input", 0, false);

            ids.add(7654321); set(type, runtime, "pickupChat", PurpleSyncChatCore.Peer.self());
            resetLines();
            update.invoke(runtime, PurpleSyncChatCore.Peer.self(), Collections.emptyList());
            Runnable task = (Runnable) field(type, runtime, "pickup");
            expect(task != null && Long.valueOf(500).equals(AndroidUtilities.delayed.get(task)), "actual 500ms debounce", false);
            decision("collect", "requested", "pending", 1, false);

            resetLines(); update.invoke(runtime, PurpleSyncChatCore.Peer.self(), Collections.emptyList());
            expect(field(type, runtime, "pickup") == task && ids.size() == 1, "existing task retained", false);
            decision("collect", "wait", "busy", 1, false);

            ApplicationLoader.mainInterfacePaused = true; resetLines();
            update.invoke(runtime, PurpleSyncChatCore.Peer.self(), Collections.emptyList());
            expect(field(type, runtime, "pickup") == task && ids.size() == 1, "paused notification leaves original pending batch", false);
            decision("collect", "rejected", "ineligible", 1, false);

            int queued = DispatchQueue.pending.size(); AndroidUtilities.delayed.remove(task); resetLines(); task.run();
            expect(DispatchQueue.pending.size() == queued && ids.isEmpty() && field(type, runtime, "pickup") == null,
                "stale callback consumes batch without engine work", false);
            decision("collect", "rejected", "stale", 1, false);
            frame.unchangedA();
        } finally {
            if (control != null) control.close();
            for (Object runtime : new ArrayList<>(runtimes.values())) {
                ((PurpleSyncAutoEngine) field(runtime.getClass(), runtime, "engine")).close();
                Object timer = field(runtime.getClass(), runtime, "backstop");
                Method close = timer.getClass().getDeclaredMethod("close"); close.setAccessible(true); close.invoke(timer);
            }
            runtimes.clear(); holders.clear(); holders.putAll(savedHolders);
            set(PurpleSyncAuto.class, null, "observing", observing);
            DispatchQueue.pending.clear(); AndroidUtilities.pending.clear(); AndroidUtilities.delayed.clear();
            android.os.Looper.mainThread = prior; ApplicationLoader.mainInterfacePaused = paused;
            SharedConfig.appLocked = locked; SharedConfig.isWaitingForPasscodeEnter = passcode;
            PurpleArchiveLogin.source = loginSource;
            for (Map.Entry<String, Object> entry : saved.entrySet()) set(PurpleAccountOwner.class, null, entry.getKey(), entry.getValue());
        }
    }

    public static int run(File root) throws Exception {
        checks = 0; diagnosticFailures = 0; behaviorFailures = 0;
        engine(root); runtime(root);
        System.out.println("PICKUP_BEHAVIOR_FAILURES=" + behaviorFailures);
        System.out.println("PICKUP_DIAGNOSTIC_FAILURES=" + diagnosticFailures);
        System.out.println("PICKUP_TOTAL_CHECKS=" + checks);
        if (behaviorFailures != 0 || diagnosticFailures != 0) throw new AssertionError("pickup checks failed");
        return checks;
    }
}
