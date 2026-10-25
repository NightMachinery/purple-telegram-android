package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

public final class PurpleArchiveLoginRestartCases {
    private static int checks;
    private static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    private static void await(BooleanSupplier ready, String label) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        while (!ready.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        check(ready.getAsBoolean(), label);
    }
    private static void refresh() throws Exception {
        PurpleArchive.Status before = PurpleArchive.status(0); PurpleArchive.refresh(0, () -> {});
        await(() -> PurpleArchive.status(0) != before && PurpleArchive.status(0).state.equals("Ready"), "production worker admits the login offline");
    }
    private static PurpleArchiveBinding binding(String account) throws Exception {
        PurpleArchivePaths paths = new PurpleArchivePaths(ApplicationLoader.files, account);
        return new PurpleArchiveBinding(account, PurpleArchiveBinding.json(paths.metadata.read("binding.json", 65536).bytes));
    }
    private static File db(PurpleArchiveBinding binding) throws Exception { return new PurpleArchivePaths(ApplicationLoader.files, binding.account).store(binding.store, false).file("archive.sqlite"); }
    private static byte[] digest(File path) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path.toPath())); }
    private static void close() throws Exception {
        java.lang.reflect.Field field = PurpleArchive.class.getDeclaredField("workers"); field.setAccessible(true);
        Map<?, ?> workers = (Map<?, ?>) field.get(null); PurpleAccountFiles.identityChanged(0); PurpleAccountFiles.identityChanged(1);
        await(workers::isEmpty, "identity close drains without deleting retained login archive");
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]); String mode = args[2]; if (mode.equals("seed")) check(root.mkdir(), "exclusive login restart fixture");
        ApplicationLoader.files = new File(root, "files"); if (mode.equals("seed")) check(ApplicationLoader.files.mkdir(), "exclusive login app files");
        File fixture = new File(root, "fixture.json"); JSONObject state = mode.equals("seed") ? new JSONObject() : new JSONObject(new String(Files.readAllBytes(fixture.toPath()), StandardCharsets.UTF_8));
        PurpleArchiveLoginCases.Preferences prefs = new PurpleArchiveLoginCases.Preferences(); UserConfig a = UserConfig.getInstance(0); a.user = 11; a.active = true; a.preferences = prefs.value;
        if (mode.equals("seed")) {
            PurpleArchive.loginChanging(0, 0, 11); PurpleArchiveLoginCases.save(0, 11, prefs); refresh();
            PurpleArchiveLoginCases.Preferences bp = new PurpleArchiveLoginCases.Preferences(); UserConfig b = UserConfig.getInstance(1); b.user = 22; b.active = true; b.preferences = bp.value;
            PurpleArchive.loginChanging(1, 0, 22); PurpleArchiveLoginCases.save(1, 22, bp); PurpleArchive.refresh(1, () -> {});
            await(() -> PurpleArchive.status(1).state.equals("Ready"), "B archive opens independently");
            PurpleArchiveBinding bound = binding("0-11"), other = binding("0-22"); close();
            state.put("binding", bound.value).put("bBinding", other.value).put("bDigest", PurpleArchiveCore.base64(digest(db(other))));
            JSONObject keys = new JSONObject(); for (Map.Entry<String, javax.crypto.SecretKey> entry : PurpleArchivePlatformProvider.keys.entrySet()) keys.put(entry.getKey(), PurpleArchiveCore.base64(entry.getValue().getEncoded()));
            state.put("keys", keys).put("prefs", new JSONObject(prefs.disk));
            prefs.fail = true; prefs.value.edit().clear().apply(); prefs.value.edit().remove("user").apply();
            check(prefs.disk.containsKey("user") && db(bound).exists(), "all failed logout writes retain old user nonce and physical archive");
            Files.write(fixture.toPath(), state.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            UserConfig.getInstance(1).active = true; UserConfig.getInstance(1).user = 22;
            JSONObject keys = state.getJSONObject("keys"); for (String alias : keys.keySet()) PurpleArchivePlatformProvider.keys.put(alias, new SecretKeySpec(PurpleArchiveCore.unbase64(keys.getString(alias)), "AES"));
            JSONObject stored = state.getJSONObject("prefs"); for (String key : stored.keySet()) prefs.disk.put(key, stored.getString(key)); prefs.restart();
            PurpleArchiveBinding old = new PurpleArchiveBinding("0-11", state.getJSONObject("binding")), other = new PurpleArchiveBinding("0-22", state.getJSONObject("bBinding"));
            File oldDb = db(old);
            String nonce = PurpleArchiveLogin.context(0);
            if (mode.equals("same")) {
                refresh(); check(binding("0-11").store.equals(old.store), "real process restart keeps same-login store");
                close(); check(PurpleArchiveLogin.context(0).equals(nonce), "DC/key identity change retains nonce");
                refresh(); check(binding("0-11").store.equals(old.store), "DC/key identity change keeps store offline"); close();
            } else {
                prefs.fail = true; prefs.value.edit().clear().apply(); a.active = false; a.user = 0;
                PurpleArchive.loginChanging(0, 0, 11); a.active = true; a.user = 11; prefs.fail = false; PurpleArchiveLoginCases.save(0, 11, prefs);
                check(!PurpleArchiveLogin.context(0).equals(nonce), "new login after failed logout and restart rotates anchor despite retained keys");
                refresh(); PurpleArchiveBinding fresh = binding("0-11");
                check(!fresh.store.equals(old.store) && !oldDb.exists() && !PurpleArchivePlatformProvider.keys.containsKey(old.alias()), "production admission erases authenticated old orphan before new store selection");
            }
            check(Arrays.equals(PurpleArchiveCore.unbase64(state.getString("bDigest")), digest(db(other))) && PurpleArchivePlatformProvider.keys.containsKey(other.alias()), "A login handling leaves B database and key untouched");
        }
        System.out.println("ARCHIVE_LOGIN_RESTART_CHECKS=" + checks + " mode=" + mode);
    }
}
