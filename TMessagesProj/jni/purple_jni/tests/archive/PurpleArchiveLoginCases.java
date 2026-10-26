package org.telegram.messenger.purple;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.telegram.messenger.UserConfig;

public final class PurpleArchiveLoginCases {
    private static int checks;
    private static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    static final class Preferences {
        Map<String, String> memory = new HashMap<>(), disk = new HashMap<>();
        boolean fail;
        final SharedPreferences value = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
            if (method.getName().equals("getString")) return memory.containsKey(args[0]) ? memory.get(args[0]) : args[1];
            if (method.getName().equals("getBoolean")) return memory.containsKey(args[0]) ? Boolean.parseBoolean(memory.get(args[0])) : args[1];
            if (method.getName().equals("edit")) return editor();
            throw new UnsupportedOperationException(method.getName());
        });
        SharedPreferences.Editor editor() {
            Map<String, String> changes = new HashMap<>(); boolean[] clear = {false};
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "putString": changes.put((String) args[0], (String) args[1]); return proxy;
                    case "putBoolean": changes.put((String) args[0], args[1].toString()); return proxy;
                    case "remove": changes.put((String) args[0], null); return proxy;
                    case "clear": clear[0] = true; return proxy;
                    case "commit": case "apply":
                        if (clear[0]) memory.clear();
                        for (Map.Entry<String, String> entry : changes.entrySet()) {
                            if (entry.getValue() == null) memory.remove(entry.getKey()); else memory.put(entry.getKey(), entry.getValue());
                        }
                        if (!fail) disk = new HashMap<>(memory);
                        return method.getName().equals("commit") ? !fail : null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        }
        void restart() throws Exception {
            memory = new HashMap<>(disk);
            java.lang.reflect.Field field = PurpleArchiveLogin.class.getDeclaredField("logins"); field.setAccessible(true); ((Map<?, ?>) field.get(null)).clear();
        }
    }
    static void save(int slot, long user, Preferences prefs) {
        SharedPreferences.Editor editor = prefs.value.edit().putString("user", Long.toString(user));
        PurpleArchive.writeLogin(slot, user, editor); editor.apply();
    }
    static void run() throws Exception {
        Preferences prefs = new Preferences(); UserConfig user = UserConfig.getInstance(0); user.user = 11; user.active = true; user.preferences = prefs.value;
        PurpleArchive.loginChanging(0, 0, 11);
        String opening = PurpleArchiveLogin.context(0);
        SharedPreferences.Editor editor = prefs.value.edit().putString("user", "11"); PurpleArchive.writeLogin(0, 11, editor);
        check(prefs.disk.isEmpty(), "login hook creates no separate preference commit"); editor.apply();
        check(prefs.disk.get("user").equals("11") && prefs.disk.get("purpleArchiveLoginV1").equals(opening), "same editor persists user and nonce together");
        PurpleArchive.loginChanging(0, 11, 11); save(0, 11, prefs);
        check(PurpleArchiveLogin.context(0).equals(opening), "same-user refresh and ordinary saves never rotate nonce");
        prefs.restart();
        check(PurpleArchiveLogin.context(0).equals(opening), "same-login cold restart retains persisted nonce offline");
        PurpleAccountFiles.identityChanged(0);
        check(PurpleArchiveLogin.context(0).equals(opening), "DC or key identity notification cannot rotate login nonce");
        prefs.fail = true; prefs.value.edit().clear().apply(); prefs.value.edit().remove("user").apply(); prefs.restart();
        check(prefs.memory.get("user").equals("11") && PurpleArchiveLogin.context(0).equals(opening), "all failed logout writes leave complete old login pair");
        PurpleArchive.loginChanging(0, 0, 11); String replacement = PurpleArchiveLogin.context(0);
        check(!replacement.equals(opening), "new login rotates despite identical retained user and transport keys");
        save(0, 11, prefs); prefs.restart();
        check(PurpleArchiveLogin.context(0).equals(opening) && prefs.memory.get("user").equals("11"), "failed login write cannot persist nonce without its user");
        PurpleArchive.loginChanging(0, 0, 22); user.user = 22; String other = PurpleArchiveLogin.context(0);
        prefs.fail = false; save(0, 22, prefs); prefs.restart();
        check(prefs.memory.get("user").equals("22") && PurpleArchiveLogin.context(0).equals(other), "successful different-user login persists complete new pair");
        PurpleArchive.loginChanging(0, 22, 33); user.user = 33;
        check(!PurpleArchiveLogin.context(0).equals(other), "different-user transition rotates even without prior clear");
        prefs.restart(); user.user = 22;
        prefs.value.edit().remove("purpleArchiveLoginV1").apply(); boolean refused = false;
        try { PurpleArchiveLogin.context(0); } catch (Exception expected) { refused = true; }
        check(refused, "missing persisted login nonce refuses without regeneration");
        System.out.println("ARCHIVE_LOGIN_CHECKS=" + checks);
    }
    public static void main(String[] args) throws Exception { run(); }
}
