package org.telegram.messenger.purple;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;

public final class PurpleAccountJniCases {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject call(Class<?> type, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return new JSONObject((String) method.invoke(null, args));
    }
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    private static JSONObject plan(byte[] local, byte[] owner, boolean evidence,
            String[] keys, boolean[] dirs, String choice) throws Exception {
        return call(PurpleAccountOwner.class, "planNative",
                new Class<?>[]{byte[].class, byte[].class, boolean.class, String[].class,
                        boolean[].class, boolean[].class, String.class},
                local, owner, evidence, keys, dirs, new boolean[keys.length], choice);
    }
    public static void run() throws Exception {
        JSONObject ambiguous = plan(null, null, false, new String[]{"0-101", "1-202"},
                new boolean[]{false, false}, null);
        require(ambiguous.getInt("status") == 3 && !ambiguous.getBoolean("automatic"));
        JSONObject first = plan(null, null, false, new String[]{"1-202"}, new boolean[]{false}, null);
        JSONArray writes = first.getJSONArray("writes");
        require(first.getInt("status") == 1 && writes.length() == 2);
        require(writes.getJSONObject(0).getString("path").equals("accounts/root.toml"));
        JSONObject interrupted = plan(null, bytes(writes.getJSONObject(0).getString("text")), true,
                new String[]{"0-101"}, new boolean[]{false}, null);
        require(interrupted.getString("owner").equals("1-202"));
        require(interrupted.getJSONArray("writes").length() == 1);
        require(plan(null, null, true, new String[]{"0-101"}, new boolean[]{false}, null)
                .getInt("status") == 4);
        require(plan(null, null, true, new String[]{"0-101"}, new boolean[]{true}, "0-101")
                .getInt("status") == 5);
        JSONObject layer = call(PurpleAccountSettings.class, "layerNative",
                new Class<?>[]{byte[].class, String.class, byte[].class, byte[].class, String.class, byte[].class},
                bytes("version = 1\n[last_seen]\ntrade_p = false\n"), "settings.toml", null,
                bytes("version = 2\n[last_seen]\nreasons_p = false\n"), "accounts/1-202/settings.toml", null);
        require(!layer.getBoolean("trade") && !layer.getBoolean("reasons"));
        JSONObject fallback = call(PurpleAccountSettings.class, "layerNative",
                new Class<?>[]{byte[].class, String.class, byte[].class, byte[].class, String.class, byte[].class},
                bytes("version = 1\n"), "settings.toml", null,
                bytes("[broken"), "accounts/1-202/settings.toml",
                bytes("version = 2\n[last_seen]\ntrade_p = false\n"));
        require(fallback.getBoolean("accountGood") && !fallback.getBoolean("trade"));
    }
}
