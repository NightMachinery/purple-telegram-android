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
        // The upgrade start order: no user is in memory, then the same user becomes current while the
        // slot still holds this user's persisted pair. Minting here would orphan the bound archive.
        PurpleArchive.loginChanging(0, 0, 11);
        check(PurpleArchiveLogin.context(0).equals(opening), "no user becoming the persisted user keeps its nonce");
        check(prefs.disk.get("purpleArchiveLoginUserV1").equals("11"), "the nonce's owner is persisted beside it");
        // A real login clears the preferences first (LoginActivity calls clearConfig), even when that write fails on disk.
        prefs.value.edit().clear().apply();
        PurpleArchive.loginChanging(0, 0, 11); String replacement = PurpleArchiveLogin.context(0);
        check(!replacement.equals(opening), "a login after logout rotates despite identical user and transport keys");
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
        upgrade();
        System.out.println("ARCHIVE_LOGIN_CHECKS=" + checks);
    }
    /** Nonces saved before their owner was kept beside them, and the owner check itself. */
    static void upgrade() throws Exception {
        Preferences prefs = new Preferences(); UserConfig user = UserConfig.getInstance(0); user.user = 44; user.active = true; user.preferences = prefs.value;
        prefs.restart();
        String legacy = "0123456789abcdef0123456789abcdef";
        prefs.value.edit().putString("user", "serialized-44").putString("purpleArchiveLoginV1", legacy).apply(); prefs.restart();
        PurpleArchive.loginChanging(0, 0, 44);
        check(!PurpleArchiveLogin.context(0).equals(legacy), "a nonce without a recorded owner is not adopted");
        prefs.restart();
        PurpleArchive.loginLoaded(0, prefs.value, "serialized-44", 44);
        check(prefs.disk.get("purpleArchiveLoginUserV1").equals("44") && prefs.disk.get("purpleArchiveLoginV1").equals(legacy),
            "loading the persisted user records the owner of its existing nonce");
        PurpleArchive.loginChanging(0, 0, 44);
        check(PurpleArchiveLogin.context(0).equals(legacy), "after that, the upgrade start order keeps the nonce");
        prefs.restart();
        PurpleArchive.loginChanging(0, 0, 55); user.user = 55;
        check(!PurpleArchiveLogin.context(0).equals(legacy), "another user's persisted nonce is never adopted");
        prefs.restart(); user.user = 44;
        prefs.value.edit().putString("purpleArchiveLoginV1", "not-a-nonce").apply(); prefs.restart();
        PurpleArchive.loginChanging(0, 0, 44);
        check(PurpleArchiveLogin.context(0).matches("[0-9a-f]{32}"), "an invalid persisted nonce is replaced, not adopted");
        Preferences fresh = new Preferences(); user.preferences = fresh.value; fresh.restart();
        fresh.value.edit().putString("user", "serialized-44").apply();
        PurpleArchive.loginLoaded(0, fresh.value, "serialized-44", 44);
        check(fresh.disk.get("purpleArchiveLoginUserV1").equals("44") && fresh.disk.get("purpleArchiveLoginV1").matches("[0-9a-f]{32}")
            && fresh.disk.get("purpleArchiveBootstrapV1").equals("true"), "a bootstrapped nonce records its owner in the same commit");
        String bootstrapped = fresh.disk.get("purpleArchiveLoginV1");
        PurpleArchive.loginChanging(0, 0, 44);
        check(PurpleArchiveLogin.context(0).equals(bootstrapped), "the upgrade start order keeps a bootstrapped nonce");
    }
    public static void main(String[] args) throws Exception { run(); }
}
