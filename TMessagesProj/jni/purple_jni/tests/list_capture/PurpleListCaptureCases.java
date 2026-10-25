package org.telegram.messenger.purple;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * BUG-WMLIST: the Work Mode lists box showed no rows and its New list row did
 * nothing. The box read its rows only through PurpleWriter's write session,
 * and the session is refused while the settings owner is held. These cases
 * drive the production PurpleAccountOwner, PurpleWriter, PurpleListSource and
 * PurpleDiagLog classes from AGP output against the real JNI library; only
 * UserConfig, ConnectionsManager, preferences, the file root and the main
 * Looper are host ports. Every slot counts as loaded unless a case says
 * otherwise, as after ApplicationLoader's loading loop.
 *
 * Each scenario runs in its own JVM, because the owner plan is process state.
 */
public final class PurpleListCaptureCases {
    static int checks;
    static final long A = 101, B = 202;
    static final String SETTINGS = "version=1\n"
            + "[lists.work]\ntitle=\"Work\"\nmembers=[11,22]\n"
            + "[lists.family]\ntitle=\"Family\"\nmembers=[33]\n"
            + "[presets.work]\nchats=[\"work\"]\n";

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

    /** Which slots are signed in, the way UserConfig.loadConfig fills them: with no identity-change event. */
    static void signedIn(long... users) {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) {
            UserConfig.getInstance(i).active = i < users.length;
            UserConfig.getInstance(i).user = i < users.length ? users[i] : 0;
            ConnectionsManager.getInstance(i).test = false;
        }
        UserConfig.selectedAccount = 0;
    }

    static File root(File parent) throws Exception {
        File app = new File(parent, "app");
        check(app.mkdir(), "exclusive fixture");
        directory(app);
        ApplicationLoader.files = app;
        MessagesController.reset();
        File root = new File(app, "purple");
        directory(root);
        // A phone that ran a build from before account files: lists in the
        // root settings.toml, no local.toml, no accounts/, no sync binding.
        write(new File(root, "settings.toml"), SETTINGS);
        return root;
    }

    static PurpleWriter.Capture capture(int slot, boolean appWide) {
        return PurpleWriter.captureResult(slot, appWide, () -> true, null);
    }

    /** What the box showed before the fix: rows only from a session. */
    static int oldRows(PurpleWriter.Session writer, long peer) {
        return writer == null ? 0 : writer.lists(peer).size();
    }

    static void listsMatch(List<PurpleCore.ListEntry> lists, String what) {
        check(lists.size() == 2, what + ": both lists are read, got " + lists.size());
        PurpleCore.ListEntry work = null, family = null;
        for (PurpleCore.ListEntry list : lists) {
            if (list.name.equals("work")) work = list;
            if (list.name.equals("family")) family = list;
        }
        check(work != null && family != null, what + ": the lists are the file's");
        check(work.member && !family.member, what + ": the chat's membership is read");
    }

    static boolean member(List<PurpleCore.ListEntry> lists, String name) {
        for (PurpleCore.ListEntry list : lists) if (list.name.equals(name)) return list.member;
        throw new AssertionError("no list " + name);
    }

    /** The diagnostic log text, waiting for the low-priority writer thread. */
    static String diagnostics(File app, String expected) throws Exception {
        File log = new File(new File(app, PurpleDiagLog.DIRECTORY), "purple-diagnostic.log");
        long end = System.currentTimeMillis() + 10000;
        String text = "";
        while (System.currentTimeMillis() < end) {
            text = log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
            if (text.contains(expected)) return text;
            Thread.sleep(50);
        }
        throw new AssertionError("diagnostic line never written: " + expected + " in " + text);
    }

    /** Every line after its UTC timestamp, which is the one place digits like 2026 belong. */
    static void noIdentifiers(String text) {
        for (String line : text.split("\n")) {
            check(line.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ .*"), "each line starts with its timestamp: " + line);
            String body = line.substring(line.indexOf(' ') + 1);
            for (String forbidden : new String[]{"101", "202", "work", "Work", "family", "Family", "settings", "toml", "/"}) {
                check(!body.contains(forbidden), "the diagnostic log holds no id, list name or path: " + forbidden + " in " + body);
            }
        }
    }

    /**
     * Two signed-in accounts, no owner record and no sync binding. The core
     * plans NeedsChoice, so the owner is held until the user chooses one, and
     * every write session is refused. This is the branch a normal two-account
     * phone meets after updating to the account-file builds.
     */
    static int multi(File parent) throws Exception {
        checks = 0;
        File root = root(parent);
        signedIn(A, B);
        check(!PurpleAccountOwner.ready() && PurpleAccountOwner.owner() == null, "two accounts without a record hold the owner");
        check(PurpleAccountOwner.eligible().size() == 2, "both accounts are offered as owner");
        check(!new File(root, "local.toml").exists() && !new File(root, "accounts").exists(), "a held owner writes nothing");

        PurpleWriter.Capture refused = capture(0, false);
        check(refused.session == null && "owner_not_ready".equals(refused.reason), "the session is refused as owner_not_ready: " + refused.reason);
        check(PurpleWriter.capture(0, false, () -> true, null) == null, "the null-returning API is unchanged");
        PurpleWriter.Capture appWide = capture(1, true);
        check(appWide.session == null && "owner_not_ready".equals(appWide.reason), "New list's app-wide session is refused the same way");
        check(oldRows(refused.session, 11) == 0, "reproduced: the old box had no rows to show");

        PurpleListSource.Lists source = PurpleListSource.read(0, 11, false);
        check(PurpleListSource.APP_WIDE.equals(source.source), "read-only rows come from the running app-wide file: " + source.source);
        listsMatch(source.entries, "held owner");
        PurpleListSource.Lists other = PurpleListSource.read(1, 33, false);
        check(other.entries.size() == 2 && member(other.entries, "family") && !member(other.entries, "work"),
                "the other account reads the same file for its chat");

        String text = diagnostics(ApplicationLoader.files, "slot=1 app_wide=1 reason=owner_not_ready");
        check(text.contains("writer capture_refused slot=0 app_wide=0 reason=owner_not_ready"), "the refusal names the slot and branch: " + text);
        check(text.contains("writer capture_refused slot=1 app_wide=1 reason=owner_not_ready"), "the app-wide refusal is logged too");
        check(text.contains("owner planned accounts=2 state=needs_choice ready=0 owner=0"), "the plan says why: " + text);
        int lines = 0;
        for (String line : text.split("\n")) if (line.contains(" writer capture_refused ")) ++lines;
        check(lines == 2, "a repeated refusal within seconds is one line, got " + lines);
        noIdentifiers(text);

        // Choosing the owner, which the box's button opens, makes the rows editable.
        check(PurpleAccountOwner.chooseOwner(PurpleAccountFiles.capture(0).key), "the user's choice makes the owner ready");
        check(PurpleAccountOwner.ready() && PurpleAccountFiles.capture(0).key.equals(PurpleAccountOwner.owner()), "the chosen account owns settings.toml");
        PurpleListSource.Lists routed = PurpleListSource.read(0, 11, false);
        check(PurpleListSource.ROUTED.equals(routed.source), "with an owner the rows are the account's routed view: " + routed.source);
        listsMatch(routed.entries, "owner account");
        PurpleListSource.Lists second = PurpleListSource.read(1, 11, false);
        check(PurpleListSource.ROUTED.equals(second.source), "the second account reads its routed view");
        listsMatch(second.entries, "second account");
        return checks;
    }

    /** One signed-in account: the owner is automatic, so this phone never meets the held branch. */
    static int single(File parent) throws Exception {
        checks = 0;
        File root = root(parent);
        signedIn(A);
        check(PurpleAccountOwner.ready(), "one account owns settings.toml automatically");
        check(new File(root, "local.toml").isFile() && new File(root, "accounts/root.toml").isFile(), "the owner record is written");
        PurpleListSource.Lists routed = PurpleListSource.read(0, 11, false);
        check(PurpleListSource.ROUTED.equals(routed.source), "a ready owner reads the routed view");
        listsMatch(routed.entries, "single account");
        return checks;
    }

    /**
     * Purple is asked for a write session while ApplicationLoader is still
     * loading the account slots. Before the fix the owner was planned there,
     * from no account, and waited for a first sign-in that loading never
     * reports. Now nothing is planned until the loop's hook,
     * PurpleAccountOwner.onAccountsLoaded(), which this calls as
     * ApplicationLoader does; the refusal meanwhile says the accounts are
     * loading, and the rows come from the app-wide file.
     */
    static int early(File parent) throws Exception {
        checks = 0;
        File root = root(parent);
        signedIn(A);
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) UserConfig.getInstance(i).loaded = false;
        check(!PurpleAccountOwner.ready() && !PurpleAccountOwner.started(), "nothing is planned while the slots load");
        PurpleWriter.Capture refused = capture(0, false);
        check(refused.session == null && "accounts_loading".equals(refused.reason),
                "the session is refused as accounts_loading: " + refused.reason);
        PurpleListSource.Lists loading = PurpleListSource.read(0, 11, false);
        check(PurpleListSource.APP_WIDE.equals(loading.source) && loading.membership(), "rows from the app-wide file meanwhile");
        listsMatch(loading.entries, "loading accounts");
        check(!new File(root, "accounts/root.toml").exists(), "nothing is recorded while loading");
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) UserConfig.getInstance(i).loaded = true;
        PurpleAccountOwner.onAccountsLoaded();
        check(PurpleAccountOwner.ready(), "the hook makes the single account the owner");
        check(new File(root, "accounts/root.toml").isFile(), "and records it");
        check(PurpleListSource.ROUTED.equals(PurpleListSource.read(0, 11, false).source), "the rows then come from the routed view");
        String text = diagnostics(ApplicationLoader.files, "owner planned accounts=1 state=owned ready=1 owner=1");
        check(text.contains("writer capture_refused slot=0 app_wide=0 reason=accounts_loading"), "the loading refusal is logged: " + text);
        noIdentifiers(text);
        return checks;
    }

    static void noMembership(PurpleListSource.Lists lists, String what) {
        check(PurpleListSource.NAMES_ONLY.equals(lists.source) && !lists.membership(), what + ": names only, got " + lists.source);
        check(lists.entries.size() == 2, what + ": both list names are kept");
        for (PurpleCore.ListEntry entry : lists.entries) check(!entry.member && !entry.ownMembers, what + ": no checkmark for " + entry.name);
    }

    /**
     * Read-only rows show the membership the running resolution uses, and
     * never checkmarks from a different file. Two accounts, the first owns
     * settings.toml and is the app-wide account; the second has its own Work
     * Mode file, which names chat 44 in "work" where the app-wide file names
     * 11 and 22.
     */
    static int names(File parent) throws Exception {
        checks = 0;
        File root = root(parent);
        signedIn(A, B);
        check(PurpleAccountOwner.chooseOwner(PurpleAccountFiles.capture(0).key), "slot 0 owns settings.toml");
        File own = new File(root, "accounts/0-202/settings.toml");
        File good = new File(own.getPath() + ".good");

        // Never written: no account layer, as in the running view.
        PurpleListSource.Lists fresh = PurpleListSource.read(1, 11, false);
        check(PurpleListSource.ROUTED.equals(fresh.source), "an own file never written adds no layer: " + fresh.source);
        listsMatch(fresh.entries, "own file never written");

        // The account's own membership, not the app-wide one.
        write(own, "version=2\n[lists.work]\nmembers=[44]\n");
        PurpleListSource.Lists routed = PurpleListSource.read(1, 44, false);
        check(PurpleListSource.ROUTED.equals(routed.source), "the own file is read: " + routed.source);
        check(member(routed.entries, "work") && !member(routed.entries, "family"), "44 is in work by the own file");
        check(!member(PurpleListSource.read(1, 11, false).entries, "work"), "11 is not, although the app-wide file says so");
        check(member(PurpleListSource.read(0, 11, false).entries, "work"), "the app-wide account still has 11 in work");

        // Broken: the running view used the own file's good copy.
        write(own, "version=2\n[lists.work\nmembers=[44\n");
        noMembership(PurpleListSource.read(1, 11, false), "unparseable own file");
        noMembership(PurpleListSource.read(1, 44, false), "unparseable own file, own member");

        // Gone with its good copy still there: it has to be recovered.
        Files.delete(own.toPath());
        write(good, "version=2\n[lists.work]\nmembers=[44]\n");
        noMembership(PurpleListSource.read(1, 11, false), "own file to recover");
        Files.delete(good.toPath());

        // local.toml unreadable: the running view kept an earlier copy.
        File local = new File(root, "local.toml");
        byte[] choice = Files.readAllBytes(local.toPath());
        write(local, "[account_work_mode\n");
        noMembership(PurpleListSource.read(1, 11, false), "unparseable local.toml");
        Files.write(local.toPath(), choice);
        check(PurpleListSource.ROUTED.equals(PurpleListSource.read(1, 11, false).source), "a good local.toml reads again");

        // The gate running from the app-wide good copy.
        check(PurpleListSource.NONE.equals(PurpleListSource.read(1, 11, true).source), "no good copy, nothing to show");
        write(new File(root, "settings.toml.good"), SETTINGS);
        noMembership(PurpleListSource.read(1, 11, true), "gate on the app-wide good copy");

        // Following the app-wide file: membership from it, whatever the own file says.
        write(own, "version=2\n[lists.work]\nmembers=[44]\n");
        String text = new String(choice, StandardCharsets.UTF_8);
        String line = "\"0-202\"=\"follow\"\n";
        String follow = text.contains("[account_work_mode]\n") ? text.replace("[account_work_mode]\n", "[account_work_mode]\n" + line)
                : text + "\n[account_work_mode]\n" + line;
        write(local, follow);
        PurpleListSource.Lists following = PurpleListSource.read(1, 11, false);
        check(PurpleListSource.ROUTED.equals(following.source), "a following account reads the routed view: " + following.source);
        listsMatch(following.entries, "following account");
        return checks;
    }

    /** The exception-to-code mapping for refusals inside the session constructor. */
    static int failures(File parent) throws Exception {
        checks = 0;
        String[][] cases = {
                {"missing settings route", "route_missing"}, {"invalid Work Mode choice", "local_choice_invalid"},
                {"editor Work Mode choice unavailable", "local_choice_invalid"}, {"write route changed", "route_changed"},
                {"editor target changed", "route_changed"}, {"editor context changed", "route_changed"},
                {"input changed during read", "files_changed"}, {"account file recovery required", "recovery_required"},
                {"unsafe choice path", "unsafe_file"}, {"editor path outside account root", "unsafe_file"},
                {"input exceeds bound", "file_too_large"}, {"anything else", "session_failed"}};
        for (String[] each : cases) {
            check(each[1].equals(PurpleWriter.failure(new IllegalStateException(each[0]))), "mapped: " + each[0]);
        }
        check("file_not_utf8".equals(PurpleWriter.failure(new java.nio.charset.MalformedInputException(1))), "malformed UTF-8");
        check("file_unreadable".equals(PurpleWriter.failure(new java.io.IOException("x"))), "I/O failure");
        check("file_unreadable".equals(PurpleWriter.failure(new android.system.ErrnoException("lstat", 13))), "errno failure");
        check("native_unavailable".equals(PurpleWriter.failure(new UnsatisfiedLinkError("x"))), "missing native library");
        // Every refusal code is a word of the diagnostic log's vocabulary, so
        // none of them is logged as "invalid".
        java.util.Set<String> reasons = new java.util.HashSet<>(java.util.Arrays.asList(PurpleWriter.REASONS));
        check(reasons.size() == PurpleWriter.REASONS.length, "each refusal code is listed once");
        for (String[] each : cases) check(reasons.contains(each[1]), "listed: " + each[1]);
        for (String code : new String[]{"file_not_utf8", "file_unreadable", "native_unavailable"}) check(reasons.contains(code), "listed: " + code);
        for (String code : PurpleWriter.REASONS) {
            check(PurpleDiagLogStore.VOCABULARY.contains(code) && PurpleDiagLogStore.tokenShape(code), "a vocabulary word: " + code);
        }
        for (String code : new String[]{"lists_unreadable", PurpleListSource.ROUTED, PurpleListSource.APP_WIDE,
                PurpleListSource.APP_WIDE_GOOD, PurpleListSource.NAMES_ONLY, PurpleListSource.NONE}) {
            check(PurpleDiagLogStore.VOCABULARY.contains(code), "a vocabulary word: " + code);
        }
        return checks;
    }

    public static int run(String scenario, File root) throws Exception {
        switch (scenario) {
            case "multi": return multi(root);
            case "single": return single(root);
            case "early": return early(root);
            case "names": return names(root);
            case "failures": return failures(root);
            default: throw new IllegalArgumentException(scenario);
        }
    }
}
