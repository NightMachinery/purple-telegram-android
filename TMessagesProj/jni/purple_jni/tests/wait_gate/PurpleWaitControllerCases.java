package org.telegram.ui;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.telegram.messenger.purple.PurpleCore;
import sun.misc.Unsafe;

public final class PurpleWaitControllerCases {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        ++checks;
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static void field(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    public static int run(File expected) throws Exception {
        checks = 0;
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Unsafe unsafe = (Unsafe) singleton.get(null);
        Class<?> settingsClass = Class.forName("org.telegram.messenger.purple.PurpleGate$PeekSettings");
        Class<?> pendingClass = Class.forName("org.telegram.ui.PurplePeekControls$Pending");
        Class<?> actionClass = Class.forName("org.telegram.ui.PurplePeekControls$Action");
        for (Class<?> type : new Class<?>[]{settingsClass, pendingClass}) {
            check(expected.equals(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile()),
                    "the regression executes AGP production controller classes");
        }
        Object presetAction = null;
        for (Object action : actionClass.getEnumConstants()) if (action.toString().equals("PRESET")) presetAction = action;
        check(presetAction != null, "production preset action");
        Method noOp = pendingClass.getDeclaredMethod("noOp");
        noOp.setAccessible(true);
        byte[] config = bytes("version=1\n[presets.work]\n");
        byte[] missing = bytes("active_preset=\"missing\"\n");
        PurpleCore.Loaded loaded = PurpleCore.load(config, missing, "fixture", "android", "mobile");
        check(loaded.ok && loaded.normal && loaded.activeMissing && loaded.preset.equals("normal"),
                "real core falls back to Normal for an unresolved saved preset without a cache");
        Object settings = unsafe.allocateInstance(settingsClass);
        field(settings, "state", loaded);
        Object pending = unsafe.allocateInstance(pendingClass);
        field(pending, "action", presetAction);
        field(pending, "settings", settings);
        for (String target : new String[]{null, "", "normal", "NORMAL"}) {
            field(pending, "target", target);
            check(Boolean.FALSE.equals(noOp.invoke(pending)), "choosing Normal must clear the unresolved saved choice");
        }
        String cleared = PurpleCore.setPreset(missing, null);
        PurpleCore.Loaded normal = PurpleCore.load(config, bytes(cleared), "fixture", "android", "mobile");
        check(normal.ok && normal.normal && !normal.activeMissing, "the real Normal splice clears the missing choice");
        field(settings, "state", normal);
        for (String target : new String[]{null, "", "normal", "NORMAL"}) {
            field(pending, "target", target);
            check(Boolean.TRUE.equals(noOp.invoke(pending)), "an ordinary Normal choice remains a no-op");
        }
        return checks;
    }
}
