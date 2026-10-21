package org.telegram.messenger.purple;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class PurpleAccountRepairJniCases {
    private static byte[] bytes(String text) { return text == null ? null : text.getBytes(StandardCharsets.UTF_8); }
    private static Object call(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }
    private static void require(boolean value, String name) { if (!value) throw new AssertionError(name); }
    private static JSONObject transition(String settings, String good) throws Exception {
        return new JSONObject((String) call(PurpleAccountOwner.class, "globalTransitionNative",
                new Class<?>[]{byte[].class, String.class, byte[].class, byte[].class},
                bytes(settings), "accounts/0-202/settings.toml", bytes(good),
                bytes("active_preset = \"work\"\nactive_preset_source = \"manual\"\n")));
    }
    private static long epoch() throws Exception {
        return (Long) call(PurpleAccountSettings.class, "epochNative", new Class<?>[]{});
    }
    private static long create(long epoch) throws Exception {
        return (Long) call(PurpleAccountSettings.class, "createNative",
                new Class<?>[]{long.class, byte[].class, String.class, byte[].class,
                        byte[].class, String.class, byte[].class},
                epoch, bytes("version = 1\n"), "settings.toml", null,
                bytes("version = 2\n"), "accounts/0-202/settings.toml", null);
    }
    private static String describe(long handle) throws Exception {
        return (String) call(PurpleAccountSettings.class, "describeNative", new Class<?>[]{long.class}, handle);
    }
    private static void release(long handle) throws Exception {
        call(PurpleAccountSettings.class, "releaseNative", new Class<?>[]{long.class}, handle);
    }
    public static void run() throws Exception {
        String work = "version = 1\n[presets.work]\n";
        for (String settings : new String[]{null, "", "version = 1\n", "version = 1\n[presets.other]\n"}) {
            JSONObject result = transition(settings, null);
            require(result.getBoolean("ok") && result.getBoolean("normal")
                    && result.getString("preset").equals("normal"), "explicit missing preset becomes Normal");
        }
        JSONObject matching = transition(work, null);
        require(matching.getBoolean("ok") && !matching.getBoolean("normal")
                && matching.getString("preset").equals("work"), "matching preset preserved");
        for (String primary : new String[]{null, "[broken"}) {
            JSONObject result = transition(primary, work);
            require(result.getBoolean("ok") && result.getBoolean("good")
                    && !result.getBoolean("normal") && result.getString("preset").equals("work"), "own valid good preserves preset");
        }
        require(!transition("[broken", null).getBoolean("ok"), "malformed current without good refused");
        require(!transition(null, "[broken").getBoolean("ok"), "malformed good without current refused");
        require(transition(null, null).getBoolean("missing"), "missing preview");
        require(transition("", null).getBoolean("empty"), "empty preview");
        long started = epoch();
        long winner = create(started);
        long loser = create(started);
        require(winner != 0 && loser != 0 && winner != loser, "distinct native candidates");
        release(loser);
        require(describe(loser) == null && describe(winner) != null, "loser disposal preserves winner");
        release(loser);
        require(describe(winner) != null, "duplicate stale disposal does not affect winner");
        call(PurpleAccountSettings.class, "clearNative", new Class<?>[]{});
        require(describe(winner) == null && epoch() != started && create(started) == 0,
                "retirement clears handles and rejects stale allocations");
        for (int i = 0; i != 64; ++i) {
            long candidate = create(epoch());
            require(candidate != 0 && describe(candidate) != null, "new candidate after retirement");
            release(candidate);
            require(describe(candidate) == null, "unpublished candidate cleanup");
        }
        java.lang.reflect.Constructor<PurpleAccountFileSnapshot> constructor = PurpleAccountFileSnapshot.class
                .getDeclaredConstructor(PurpleAccountFileSnapshot.Status.class, byte[].class);
        constructor.setAccessible(true);
        PurpleAccountFileSnapshot primary = constructor.newInstance(PurpleAccountFileSnapshot.Status.Present, bytes("captured"));
        PurpleAccountFileSnapshot same = constructor.newInstance(PurpleAccountFileSnapshot.Status.Present, bytes("captured"));
        PurpleAccountFileSnapshot changed = constructor.newInstance(PurpleAccountFileSnapshot.Status.Present, bytes("changed"));
        PurpleAccountFileSnapshot empty = constructor.newInstance(PurpleAccountFileSnapshot.Status.Present, new byte[0]);
        PurpleAccountFileSnapshot missing = constructor.newInstance(PurpleAccountFileSnapshot.Status.Missing, null);
        PurpleAccountFileSnapshot error = constructor.newInstance(PurpleAccountFileSnapshot.Status.ReadError, null);
        require(primary.same(same) && !primary.same(changed), "strict captured bytes remain bound");
        require(missing.same(constructor.newInstance(PurpleAccountFileSnapshot.Status.Missing, null)), "missing remains distinct and stable");
        require(!missing.same(empty) && !empty.same(missing), "missing differs from readable empty");
        require(!error.same(error) && !error.same(missing) && !primary.same(error), "read error never authorizes a captured input");
        require(PurplePeekSnapshot.waitSeconds(10, null) == 10, "activation friction unchanged");
        require(PurplePeekSnapshot.waitSeconds(10, 3) == 10, "delay reduction waits old value");
        require(PurplePeekSnapshot.waitSeconds(10, 17) == 17, "delay increase waits new value");
        require(PurplePeekSnapshot.waitSeconds(0, null) == 0, "zero delay unchanged");
        System.out.println("REPAIR_NATIVE_TRANSITION_LIFETIME_AND_PEEK_FRICTION_COMPLETED");
    }
}
