package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The settings owner is planned only after every account slot has loaded.
 * ApplicationLoader.postInitApplication loads each slot's UserConfig in a
 * loop, which fills in the signed-in user without an identity event, and then
 * calls PurpleAccountOwner.onAccountsLoaded(). These cases drive the
 * production PurpleAccountOwner from AGP output against the real JNI library
 * through that order, calling the same hook ApplicationLoader calls; only
 * UserConfig, ConnectionsManager, preferences and the file root are host
 * ports. Each scenario runs in its own JVM, because the plan is process state.
 */
public final class PurpleOwnerLoadCases {
    static int checks;
    static final long A = 101, B = 202;

    static void check(boolean ok, String text) {
        if (!ok) throw new AssertionError(text);
        ++checks;
    }

    static void directory(File file) throws Exception {
        Files.createDirectories(file.toPath());
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rwx------"));
    }

    static void write(File file, String text) throws Exception {
        directory(file.getParentFile());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
    }

    /** A slot whose UserConfig.loadConfig() has not run yet: no user, whatever is on disk. */
    static void unloaded(int slot) {
        UserConfig.getInstance(slot).loaded = false;
        UserConfig.getInstance(slot).active = false;
        UserConfig.getInstance(slot).user = 0;
        ConnectionsManager.getInstance(slot).test = false;
    }

    /** What loadConfig() leaves behind: the user it read, if any, and no identity event. */
    static void load(int slot, long user) {
        UserConfig.getInstance(slot).loaded = true;
        UserConfig.getInstance(slot).active = user != 0;
        UserConfig.getInstance(slot).user = user;
        ConnectionsManager.getInstance(slot).test = false;
    }

    static File root(File parent) throws Exception {
        File app = new File(parent, "app");
        check(app.mkdir(), "exclusive fixture");
        directory(app);
        ApplicationLoader.files = app;
        MessagesController.reset();
        File root = new File(app, "purple");
        directory(root);
        // A phone from before account files: settings in the root file, no
        // local.toml, no accounts/, no sync binding.
        write(new File(root, "settings.toml"), "version=1\n[lists.work]\ntitle=\"Work\"\nmembers=[11]\n");
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) unloaded(i);
        UserConfig.selectedAccount = 0;
        return root;
    }

    static boolean recorded(File root) {
        return new File(root, "accounts/root.toml").exists() || new File(root, "local.toml").exists();
    }

    /** The diagnostic log text, waiting for the low-priority writer thread. */
    static String diagnostics(String expected) throws Exception {
        File log = new File(new File(ApplicationLoader.files, PurpleDiagLog.DIRECTORY), "purple-diagnostic.log");
        long end = System.currentTimeMillis() + 10000;
        String text = "";
        while (System.currentTimeMillis() < end) {
            text = log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
            if (text.contains(expected)) return text;
            Thread.sleep(50);
        }
        throw new AssertionError("diagnostic line never written: " + expected + " in " + text);
    }

    static void noIdentifiers(String text) {
        for (String line : text.split("\n")) {
            String body = line.substring(line.indexOf(' ') + 1);
            for (String forbidden : new String[]{"101", "202", "work", "settings", "toml", "/"}) {
                check(!body.contains(forbidden), "no id, list name or path in " + body);
            }
        }
    }

    static void held(File root, String what) {
        check(!PurpleAccountOwner.started() && !PurpleAccountOwner.ready() && PurpleAccountOwner.owner() == null,
                what + ": nothing is planned while a slot is loading");
        check(PurpleAccountOwner.eligible().isEmpty(), what + ": no owner is offered yet");
        check(PurpleAccountOwner.globalFiles() == null, what + ": readers stay on the app-wide file");
        check(!recorded(root), what + ": nothing is recorded");
    }

    /**
     * Two accounts, slot 0 loaded and slot 1 still loading when Purple first
     * asks about the owner. Before the fix the plan saw one account and made
     * it the owner automatically, writing accounts/root.toml, which no later
     * plan can undo. Now nothing is planned until both are loaded, and then the
     * two accounts wait for the user's choice.
     */
    static int midload(File parent) throws Exception {
        checks = 0;
        File root = root(parent);
        load(0, A);
        load(2, 0);
        load(3, 0);
        held(root, "slot 1 loading");
        PurpleAccountFiles.Identity first = PurpleAccountFiles.capture(0);
        check(first != null && !PurpleAccountOwner.chooseOwner(first.key), "no owner can be chosen before the plan");
        held(root, "after a refused choice");
        PurpleAccountOwner.accountsChanged();
        held(root, "an identity event while loading");

        load(1, B);
        PurpleAccountOwner.onAccountsLoaded();
        check(PurpleAccountOwner.started(), "the hook plans once every slot has loaded");
        check(!PurpleAccountOwner.ready() && PurpleAccountOwner.owner() == null,
                "two accounts and no record wait for the user's choice");
        check(PurpleAccountOwner.eligible().size() == 2, "both accounts are offered");
        check(!recorded(root), "the account seen first was not made the owner");
        synchronized (AndroidUtilities.class) {
            check(AndroidUtilities.pending.isEmpty(), "the gate never ran, so nothing is reloaded");
        }
        String text = diagnostics("owner planned accounts=2 state=needs_choice ready=0 owner=0");
        check(!text.contains("accounts=1"), "no plan was made from one account: " + text);
        noIdentifiers(text);

        check(PurpleAccountOwner.chooseOwner(PurpleAccountFiles.capture(1).key), "the user's choice still works");
        check(PurpleAccountOwner.ready() && PurpleAccountFiles.capture(1).key.equals(PurpleAccountOwner.owner()),
                "the chosen account owns settings.toml");
        return checks;
    }

    /**
     * Purple asks before any slot has loaded. Before the fix the plan waited
     * for a first sign-in that loading never reported, and every write stayed
     * held for the whole process. Now the hook plans after loading, and the one
     * signed-in account becomes the owner.
     */
    static int zero(File parent) throws Exception {
        checks = 0;
        File root = root(parent);
        held(root, "no slot loaded");
        load(0, A);
        held(root, "one slot of four loaded");
        for (int i = 1; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) load(i, 0);
        PurpleAccountOwner.onAccountsLoaded();
        check(PurpleAccountOwner.ready(), "the single account owns settings.toml after loading");
        check(PurpleAccountFiles.capture(0).key.equals(PurpleAccountOwner.owner()), "it is slot 0's account");
        check(new File(root, "accounts/root.toml").isFile(), "and it is recorded");
        String text = diagnostics("owner planned accounts=1 state=owned ready=1 owner=1");
        noIdentifiers(text);
        PurpleAccountOwner.onAccountsLoaded();
        check(PurpleAccountOwner.ready(), "a second call changes nothing");
        return checks;
    }

    /**
     * Every slot loaded, but one signed-in slot has no account key. Planning
     * from the rest could make the other account the owner, so the planner
     * holds; the next identity event plans again with both.
     */
    static int partial(File parent) throws Exception {
        checks = 0;
        File root = root(parent);
        load(0, A);
        load(1, 0);
        UserConfig.getInstance(1).active = true;
        load(2, 0);
        load(3, 0);
        PurpleAccountOwner.onAccountsLoaded();
        check(PurpleAccountOwner.started() && !PurpleAccountOwner.ready() && PurpleAccountOwner.owner() == null,
                "a slot without a key holds the owner");
        check(!recorded(root), "the other account was not made the owner");
        diagnostics("owner planned accounts=1 state=partial_view ready=0 owner=0");
        load(1, B);
        PurpleAccountOwner.accountsChanged();
        check(!PurpleAccountOwner.ready() && PurpleAccountOwner.eligible().size() == 2,
                "once named, both accounts wait for the user's choice");
        check(!recorded(root), "still nothing recorded");
        return checks;
    }

    public static int run(String scenario, File root) throws Exception {
        switch (scenario) {
            case "midload": return midload(root);
            case "zero": return zero(root);
            case "partial": return partial(root);
            default: throw new IllegalArgumentException(scenario);
        }
    }
}
