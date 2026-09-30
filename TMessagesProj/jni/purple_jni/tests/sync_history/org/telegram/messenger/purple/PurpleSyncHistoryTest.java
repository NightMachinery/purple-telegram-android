package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class PurpleSyncHistoryTest {
    private static int checks;
    private static int failures;
    private static File scratch;

    private static final Pattern KEY = Pattern.compile("[0-9]+\\.[0-9]+:[0-9a-f]{64}");

    static String oracle(byte[] bytes) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return bytes.length + ":" + hex;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static final class FakeCore implements PurpleSyncHistory.Core {
        boolean fingerprintNull;
        int corruptAfter = -1;
        int calls;

        @Override
        public String fingerprint(byte[] bytes) {
            ++calls;
            if (fingerprintNull) {
                return null;
            }
            if (corruptAfter >= 0 && calls > corruptAfter) {
                return "0:bad";
            }
            return oracle(bytes);
        }

        @Override
        public boolean isVersionKey(String key) {
            return KEY.matcher(key).matches();
        }
    }

    static final class TestClock implements PurpleSyncHistory.Clock {
        long now = 1759200000000L;

        @Override
        public long nowMs() {
            return now++;
        }
    }

    static void check(boolean condition, String what) {
        ++checks;
        if (!condition) {
            ++failures;
            System.out.println("FAIL: " + what);
        }
    }

    static File fresh(String name) throws IOException {
        final File dir = new File(scratch, name);
        Files.createDirectories(dir.toPath());
        return dir;
    }

    static File historyDir(File root) {
        return new File(new File(root, "purple"), "sync-history");
    }

    static String mode(File file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()));
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        scratch = Files.createTempDirectory(
                new File(System.getProperty("scratch")).toPath(), "run").toFile()
                .getCanonicalFile();
        switch (args[0]) {
        case "rules":
            historyBasics();
            historyRefusals();
            historyPrune();
            historyKeepId();
            historyClockBack();
            historyTamper();
            historyPermissions();
            historyDiscard();
            settingsRead();
            settingsWrite();
            break;
        case "native":
            nativeFingerprint();
            break;
        case "nocore":
            noCore();
            break;
        default:
            throw new AssertionError(args[0]);
        }
        System.out.println(args[0] + ": " + checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void historyBasics() throws Exception {
        final File root = fresh("basics");
        final FakeCore core = new FakeCore();
        final TestClock clock = new TestClock();
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), core, clock);
        check(history.list().isEmpty(), "empty list before any save");
        check(!historyDir(root).exists(), "list does not create the directory");
        check(history.read("0001759200000000-0000000000000000") == null, "read before any save");

        final byte[] text = utf8("version = 1\nname = 'a'\n");
        final PurpleSyncHistory.Entry saved = history.save(text,
                PurpleSyncHistory.Reason.BeforeUpdate, "Before update from Android 9c1d",
                "", null);
        check(saved != null, "save returns an entry");
        check(PurpleSyncHistory.isHistoryId(saved.id), "id shape");
        check(saved.id.startsWith("0001759200000000-"), "id time digits");
        check(saved.createdMs == 1759200000000L, "createdMs");
        check(saved.existed && saved.size == text.length, "existed and size");
        check(oracle(text).equals(saved.fingerprint), "fingerprint recorded");
        check(saved.reason == PurpleSyncHistory.Reason.BeforeUpdate, "reason");
        check(Arrays.equals(history.read(saved.id), text), "read exact bytes");
        check(mode(historyDir(root)).equals("rwx------"), "directory is owner-only: " + mode(historyDir(root)));
        check(mode(new File(historyDir(root), saved.id + ".toml")).equals("rw-------"), "text owner-only");
        check(mode(new File(historyDir(root), saved.id + ".json")).equals("rw-------"), "metadata owner-only");
        final String json = new String(Files.readAllBytes(new File(historyDir(root), saved.id + ".json").toPath()), StandardCharsets.UTF_8);
        check(json.contains("\"reason\":\"before_update\"") && json.contains("\"version\":1")
                && json.contains("\"existed\":true") && json.contains("\"version_key\":\"\""),
                "metadata fields: " + json);
        check(historyDir(root).list().length == 2, "exactly two files, no temporaries");

        final PurpleSyncHistory.Entry absent = history.save(null,
                PurpleSyncHistory.Reason.BeforeChoice, "Before using settings from macOS 1a2b", "", null);
        check(absent != null && !absent.existed && absent.size == 0, "absent file entry");
        check(history.read(absent.id) != null && history.read(absent.id).length == 0, "absent entry reads empty");

        final byte[] notText = new byte[] { (byte) 0xff, (byte) 0xfe, 'a', 0, (byte) 0x80 };
        final String key = "3." + oracle(notText);
        final PurpleSyncHistory.Entry binary = history.save(notText,
                PurpleSyncHistory.Reason.BeforeRestore, "Before restoring the version from 2026-09-30 03:00", key, null);
        check(binary != null && key.equals(binary.versionKey), "non-UTF-8 bytes and a version key");
        check(Arrays.equals(history.read(binary.id), notText), "non-UTF-8 bytes read back exactly");

        final List<PurpleSyncHistory.Entry> listed = history.list();
        check(listed.size() == 3, "three entries listed");
        check(listed.get(0).id.equals(binary.id) && listed.get(2).id.equals(saved.id), "newest first");

        final PurpleSyncHistory.Entry empty = history.save(new byte[0],
                PurpleSyncHistory.Reason.BeforeUndo, "", "", null);
        check(empty != null && empty.existed && empty.size == 0, "empty existing file is kept as existing");

        for (String bad : new String[] { null, "", "abc", "0001759200000000_0000000000000000",
                "0001759200000000-000000000000000A", "001759200000000-0000000000000000",
                "0001759200000000-00000000000000000", "000175920000000a-0000000000000000",
                "../0001759200000-0000000000000000" }) {
            check(history.read(bad) == null, "bad id refused: " + bad);
        }
    }

    static void historyRefusals() throws Exception {
        final File root = fresh("refusals");
        final FakeCore core = new FakeCore();
        final TestClock clock = new TestClock();
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), core, clock);
        final byte[] text = utf8("a = 1\n");
        final PurpleSyncHistory.Reason reason = PurpleSyncHistory.Reason.BeforeUpdate;
        check(history.save(new byte[PurpleSyncHistory.MAX_TEXT_BYTES + 1], reason, "", "", null) == null, "oversized text refused");
        check(history.save(new byte[PurpleSyncHistory.MAX_TEXT_BYTES], reason, "", "", null) != null, "256 KiB text accepted");
        final char[] long257 = new char[257];
        Arrays.fill(long257, 'x');
        check(history.save(text, reason, new String(long257), "", null) == null, "257-unit label refused");
        check(history.save(text, reason, new String(long257, 0, 256), "", null) != null, "256-unit label accepted");
        check(history.save(text, reason, "", "not-a-key", null) == null, "invalid version key refused");
        check(history.save(text, null, "", "", null) == null, "null reason refused");
        check(history.save(text, reason, null, "", null) == null, "null label refused");
        check(history.save(text, reason, "", null, null) == null, "null version key refused");
        clock.now = 0;
        check(history.save(text, reason, "", "", null) == null, "zero clock refused");
        clock.now = 9007199254740992L;
        check(history.save(text, reason, "", "", null) == null, "clock past the safe range refused");
        clock.now = 1759300000000L;
        core.fingerprintNull = true;
        check(history.save(text, reason, "", "", null) == null, "no core fingerprint refused");
        core.fingerprintNull = false;
        check(history.list().size() == 2, "refusals wrote nothing: " + history.list().size());

        final File linkedRoot = fresh("linked");
        final File real = fresh("linked-real");
        Files.createSymbolicLink(new File(linkedRoot, "purple").toPath(), real.toPath());
        final PurpleSyncHistory linked = new PurpleSyncHistory(historyDir(linkedRoot), new FakeCore(), new TestClock());
        check(linked.save(text, reason, "", "", null) == null, "symlinked parent refused");
        final File linkedHistoryRoot = fresh("linked-history");
        Files.createDirectories(new File(linkedHistoryRoot, "purple").toPath());
        final File target = fresh("linked-history-target");
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwx------"));
        Files.createSymbolicLink(historyDir(linkedHistoryRoot).toPath(), target.toPath());
        final PurpleSyncHistory linkedHistory = new PurpleSyncHistory(historyDir(linkedHistoryRoot), new FakeCore(), new TestClock());
        check(linkedHistory.save(text, reason, "", "", null) == null, "symlinked history directory refused");
        check(linkedHistory.list().isEmpty(), "symlinked history directory lists nothing");
        check(target.list().length == 0, "nothing written through the symlink");
    }

    static void historyPrune() throws Exception {
        final File root = fresh("prune");
        final TestClock clock = new TestClock();
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), new FakeCore(), clock);
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i != 35; ++i) {
            ids.add(history.save(utf8("n = " + i + "\n"), PurpleSyncHistory.Reason.BeforeUpdate, "e" + i, "", null).id);
        }
        final List<PurpleSyncHistory.Entry> listed = history.list();
        check(listed.size() == PurpleSyncHistory.LIMIT, "prune to 30: " + listed.size());
        check(listed.get(0).id.equals(ids.get(34)) && listed.get(29).id.equals(ids.get(5)), "newest 30 kept");
        check(historyDir(root).list().length == 60, "old files removed: " + historyDir(root).list().length);
        for (int i = 0; i != 5; ++i) {
            check(history.read(ids.get(i)) == null, "pruned entry " + i + " gone");
        }

        final File oldTemp = new File(historyDir(root), "0000000000000001-0000000000000000.toml.tmp");
        final File newTemp = new File(historyDir(root), "9999999999999999-0000000000000000.json.tmp");
        final File unrelated = new File(historyDir(root), "notes.txt");
        Files.write(oldTemp.toPath(), utf8("x"));
        Files.write(newTemp.toPath(), utf8("x"));
        Files.write(unrelated.toPath(), utf8("x"));
        history.save(utf8("n = 35\n"), PurpleSyncHistory.Reason.BeforeUpdate, "e35", "", null);
        check(!oldTemp.exists(), "old id-shaped leftover pruned when full");
        check(newTemp.exists(), "newer leftover kept");
        check(unrelated.exists(), "unrelated name untouched");
        check(history.list().size() == 30, "still 30 after leftovers");
    }

    static void historyKeepId() throws Exception {
        final File root = fresh("keep");
        final TestClock clock = new TestClock();
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), new FakeCore(), clock);
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i != 30; ++i) {
            ids.add(history.save(utf8("k = " + i + "\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null).id);
        }
        final String oldest = ids.get(0);
        final PurpleSyncHistory.Entry withKeep = history.save(utf8("k = restore\n"),
                PurpleSyncHistory.Reason.BeforeRestore, "", "", oldest);
        check(withKeep != null, "save with keepId");
        check(history.read(oldest) != null, "kept target survives the prune");
        check(history.read(ids.get(1)) == null, "second oldest pruned instead");
        check(history.list().size() == 30, "still 30 with keepId");
        history.save(utf8("k = next\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null);
        check(history.read(oldest) == null, "next ordinary save prunes the kept target");
        check(history.list().size() == 30, "30 after the ordinary save");
        final PurpleSyncHistory.Entry bogusKeep = history.save(utf8("k = bogus\n"),
                PurpleSyncHistory.Reason.BeforeUpdate, "", "", "0000000000000001-0000000000000000");
        check(bogusKeep != null && history.list().size() == 30, "keepId of no valid entry changes nothing");
    }

    static void historyClockBack() throws Exception {
        final File root = fresh("clock-back");
        final TestClock clock = new TestClock();
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), new FakeCore(), clock);
        final long ahead = 1790000000000L;
        clock.now = ahead;
        for (int i = 0; i != PurpleSyncHistory.LIMIT; ++i) {
            check(history.save(utf8("ahead = " + i + "\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null) != null,
                    "save " + i + " while the clock runs ahead");
        }
        clock.now = 1759200000000L;
        final PurpleSyncHistory.Entry first = history.save(utf8("back = 1\n"),
                PurpleSyncHistory.Reason.BeforeUpdate, "", "", null);
        final PurpleSyncHistory.Entry second = history.save(utf8("back = 2\n"),
                PurpleSyncHistory.Reason.BeforeUpdate, "", "", null);
        check(first != null && second != null, "saves after the clock went back");
        if (first == null || second == null) {
            return;
        }
        check(first.createdMs > ahead && second.createdMs > first.createdMs,
                "a save after the clock went back still sorts after every entry");
        check(history.read(first.id) != null, "the entry saved just before the newest survives the prune");
        final List<PurpleSyncHistory.Entry> listed = history.list();
        check(listed.size() == PurpleSyncHistory.LIMIT, "still 30 after the clock went back: " + listed.size());
        check(listed.get(0).id.equals(second.id) && listed.get(1).id.equals(first.id),
                "newest first in save order");
        check(history.read(listed.get(listed.size() - 1).id) != null
                && Arrays.equals(history.read(listed.get(listed.size() - 1).id), utf8("ahead = 2\n")),
                "the two oldest saves went first");
    }

    interface Mutation {
        void apply(File text, File metadata) throws Exception;
    }

    static void historyTamper() throws Exception {
        final String[] names = {
            "same-size text change", "truncated text", "metadata id", "version 2", "version 1.5",
            "created_ms", "reason", "label not a string", "label too long", "version key",
            "size negative", "existed not a bool", "existed false with size", "trailing garbage",
            "non-UTF-8 metadata", "metadata too large", "metadata not an object", "fingerprint",
            "text missing", "metadata missing", "text is a symlink", "text group-readable",
            "metadata world-readable", "text is a directory",
        };
        final Mutation[] mutations = {
            (t, m) -> Files.write(t.toPath(), utf8("version = 2\n")),
            (t, m) -> Files.write(t.toPath(), utf8("version = 1")),
            (t, m) -> replace(m, "\"id\":\"", "\"id\":\"1"),
            (t, m) -> replace(m, "\"version\":1", "\"version\":2"),
            (t, m) -> replace(m, "\"version\":1", "\"version\":1.5"),
            (t, m) -> replace(m, "\"created_ms\":1", "\"created_ms\":2"),
            (t, m) -> replace(m, "before_update", "before_lunch"),
            (t, m) -> replace(m, "\"label\":\"L\"", "\"label\":7"),
            (t, m) -> replace(m, "\"label\":\"L\"", "\"label\":\"" + repeat('y', 257) + "\""),
            (t, m) -> replace(m, "\"version_key\":\"\"", "\"version_key\":\"nope\""),
            (t, m) -> replace(m, "\"size\":12", "\"size\":-12"),
            (t, m) -> replace(m, "\"existed\":true", "\"existed\":\"true\""),
            (t, m) -> replace(m, "\"existed\":true", "\"existed\":false"),
            (t, m) -> append(m, " {}"),
            (t, m) -> append(m, "\u0000", new byte[] { (byte) 0xc3 }),
            (t, m) -> replace(m, "\"label\":\"L\"", "\"label\":\"L\",\"pad\":\"" + repeat('z', 17000) + "\""),
            (t, m) -> Files.write(m.toPath(), utf8("[1,2]")),
            (t, m) -> replace(m, "\"fingerprint\":\"12:", "\"fingerprint\":\"13:"),
            (t, m) -> Files.delete(t.toPath()),
            (t, m) -> Files.delete(m.toPath()),
            (t, m) -> {
                final File copy = new File(t.getParentFile().getParentFile(), "copy-" + t.getName());
                Files.copy(t.toPath(), copy.toPath());
                Files.setPosixFilePermissions(copy.toPath(), PosixFilePermissions.fromString("rw-------"));
                Files.delete(t.toPath());
                Files.createSymbolicLink(t.toPath(), copy.toPath());
            },
            (t, m) -> Files.setPosixFilePermissions(t.toPath(), PosixFilePermissions.fromString("rw-r-----")),
            (t, m) -> Files.setPosixFilePermissions(m.toPath(), PosixFilePermissions.fromString("rw----r--")),
            (t, m) -> { Files.delete(t.toPath()); Files.createDirectory(t.toPath()); },
        };
        for (int i = 0; i != mutations.length; ++i) {
            final File root = fresh("tamper-" + i);
            final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), new FakeCore(), new TestClock());
            final PurpleSyncHistory.Entry control = history.save(utf8("version = 1\n"),
                    PurpleSyncHistory.Reason.BeforeUpdate, "L", "", null);
            final PurpleSyncHistory.Entry victim = history.save(utf8("version = 1\n"),
                    PurpleSyncHistory.Reason.BeforeUpdate, "L", "", null);
            final File text = new File(historyDir(root), victim.id + ".toml");
            final File metadata = new File(historyDir(root), victim.id + ".json");
            mutations[i].apply(text, metadata);
            check(history.read(victim.id) == null, "tamper refused: " + names[i]);
            final List<PurpleSyncHistory.Entry> listed = history.list();
            check(listed.size() == 1 && listed.get(0).id.equals(control.id), "tamper skipped in list: " + names[i]);
        }
    }

    static String repeat(char c, int n) {
        final char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    static void replace(File file, String from, String to) throws IOException {
        final String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (!text.contains(from)) {
            throw new AssertionError("no " + from + " in " + text);
        }
        Files.write(file.toPath(), utf8(text.replace(from, to)));
    }

    static void append(File file, String text) throws IOException {
        append(file, text, new byte[0]);
    }

    static void append(File file, String text, byte[] raw) throws IOException {
        final byte[] current = Files.readAllBytes(file.toPath());
        final byte[] extra = utf8(text);
        final byte[] joined = Arrays.copyOf(current, current.length + extra.length + raw.length);
        System.arraycopy(extra, 0, joined, current.length, extra.length);
        System.arraycopy(raw, 0, joined, current.length + extra.length, raw.length);
        if (raw.length != 0) {
            final String body = new String(current, StandardCharsets.UTF_8);
            final byte[] head = utf8(body.substring(0, body.length() - 1) + ",\"x\":\"");
            final byte[] tail = utf8("\"}");
            final byte[] inside = new byte[head.length + raw.length + tail.length];
            System.arraycopy(head, 0, inside, 0, head.length);
            System.arraycopy(raw, 0, inside, head.length, raw.length);
            System.arraycopy(tail, 0, inside, head.length + raw.length, tail.length);
            Files.write(file.toPath(), inside);
            return;
        }
        Files.write(file.toPath(), joined);
    }

    static void historyPermissions() throws Exception {
        final File root = fresh("permissions");
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), new FakeCore(), new TestClock());
        final PurpleSyncHistory.Entry entry = history.save(utf8("p = 1\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null);
        check(entry != null, "permissions setup");
        Files.setPosixFilePermissions(historyDir(root).toPath(), PosixFilePermissions.fromString("rwxr-x---"));
        check(history.list().isEmpty(), "group-readable directory lists nothing");
        check(history.read(entry.id) == null, "group-readable directory reads nothing");
        check(history.save(utf8("p = 2\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null) == null,
                "group-readable directory refuses saves");
        Files.setPosixFilePermissions(historyDir(root).toPath(), PosixFilePermissions.fromString("rwx------"));
        check(history.list().size() == 1, "restored permissions list again");

        final File fileRoot = fresh("history-is-file");
        Files.createDirectories(new File(fileRoot, "purple").toPath());
        Files.write(historyDir(fileRoot).toPath(), utf8("x"));
        final PurpleSyncHistory blocked = new PurpleSyncHistory(historyDir(fileRoot), new FakeCore(), new TestClock());
        check(blocked.save(utf8("p = 3\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null) == null,
                "history path that is a file refused");
    }

    static void historyDiscard() throws Exception {
        final File root = fresh("discard");
        final FakeCore core = new FakeCore();
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), core, new TestClock());
        check(history.save(utf8("d = 1\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null) != null, "discard setup");
        core.calls = 0;
        core.corruptAfter = 1;
        check(history.save(utf8("d = 2\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null) == null,
                "a save that does not read back is refused");
        core.corruptAfter = -1;
        check(historyDir(root).list().length == 2, "the failed save left no files: " + Arrays.toString(historyDir(root).list()));
        check(history.list().size() == 1, "earlier entry intact");
    }

    static void settingsRead() throws Exception {
        final File dir = fresh("settings");
        final File file = new File(dir, "settings.toml");
        PurpleSyncSettingsFile.Contents contents = PurpleSyncSettingsFile.read(file);
        check(contents.status == PurpleSyncSettingsFile.Status.Absent && contents.bytes.length == 0, "absent");
        Files.write(file.toPath(), new byte[0]);
        check(PurpleSyncSettingsFile.read(file).status == PurpleSyncSettingsFile.Status.Present
                && PurpleSyncSettingsFile.read(file).bytes.length == 0, "empty file is present");
        final byte[] notText = { (byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', (byte) 0xff };
        Files.write(file.toPath(), notText);
        check(Arrays.equals(PurpleSyncSettingsFile.read(file).bytes, notText), "exact bytes, BOM and invalid UTF-8 kept");
        Files.write(file.toPath(), new byte[PurpleSyncSettingsFile.MAX_BYTES]);
        check(PurpleSyncSettingsFile.read(file).status == PurpleSyncSettingsFile.Status.Present, "256 KiB present");
        Files.write(file.toPath(), new byte[PurpleSyncSettingsFile.MAX_BYTES + 1]);
        check(PurpleSyncSettingsFile.read(file).status == PurpleSyncSettingsFile.Status.Invalid, "256 KiB + 1 invalid");
        Files.delete(file.toPath());
        final File real = new File(dir, "real.toml");
        Files.write(real.toPath(), utf8("a = 1\n"));
        Files.createSymbolicLink(file.toPath(), real.toPath());
        check(PurpleSyncSettingsFile.read(file).status == PurpleSyncSettingsFile.Status.Invalid, "symlink invalid");
        Files.delete(file.toPath());
        Files.createSymbolicLink(file.toPath(), new File(dir, "missing.toml").toPath());
        check(PurpleSyncSettingsFile.read(file).status == PurpleSyncSettingsFile.Status.Invalid, "dangling symlink invalid");
        Files.delete(file.toPath());
        Files.createDirectory(file.toPath());
        check(PurpleSyncSettingsFile.read(file).status == PurpleSyncSettingsFile.Status.Invalid, "directory invalid");
        final File underFile = new File(real, "settings.toml");
        check(PurpleSyncSettingsFile.read(underFile).status == PurpleSyncSettingsFile.Status.Invalid, "parent is a file: invalid, not absent");

        final PurpleSyncSettingsFile.Contents a = new PurpleSyncSettingsFile.Contents(PurpleSyncSettingsFile.Status.Present, utf8("x"));
        final PurpleSyncSettingsFile.Contents b = new PurpleSyncSettingsFile.Contents(PurpleSyncSettingsFile.Status.Present, utf8("x"));
        final PurpleSyncSettingsFile.Contents c = new PurpleSyncSettingsFile.Contents(PurpleSyncSettingsFile.Status.Present, utf8("y"));
        final PurpleSyncSettingsFile.Contents absent = new PurpleSyncSettingsFile.Contents(PurpleSyncSettingsFile.Status.Absent, new byte[0]);
        final PurpleSyncSettingsFile.Contents invalid = new PurpleSyncSettingsFile.Contents(PurpleSyncSettingsFile.Status.Invalid, new byte[0]);
        check(a.sameAs(b) && !a.sameAs(c) && absent.sameAs(absent) && !invalid.sameAs(invalid) && !a.sameAs(null)
                && !absent.sameAs(new PurpleSyncSettingsFile.Contents(PurpleSyncSettingsFile.Status.Present, new byte[0])),
                "sameAs rules");
    }

    static void settingsWrite() throws Exception {
        final File dir = fresh("settings-write");
        PurpleSettings.file = new File(dir, "settings.toml");
        final byte[] text = utf8("version = 1\n");
        PurpleSyncSettingsFile.WriteResult result = PurpleSyncSettingsFile.write(text, "sync apply", true);
        check(result.status == PurpleSyncSettingsFile.WriteStatus.Written && result.wrote()
                && Arrays.equals(result.readBack.bytes, text), "written and read back");
        check(PurpleSettings.calls == 1 && "sync apply".equals(PurpleSettings.lastReason) && PurpleSettings.lastFromImport,
                "production path goes through PurpleSettings.storeForSync with the caller's arguments");
        PurpleSyncSettingsFile.write(text, "sync restore", false);
        check(!PurpleSettings.lastFromImport, "fromImport false passes through");

        PurpleSettings.refuse = true;
        result = PurpleSyncSettingsFile.write(utf8("b = 2\n"), "r", true);
        check(result.status == PurpleSyncSettingsFile.WriteStatus.WriteFailed && !result.wrote(), "refused write");
        PurpleSettings.refuse = false;

        PurpleSettings.writeInstead = utf8("other = 1\n");
        result = PurpleSyncSettingsFile.write(utf8("c = 3\n"), "r", true);
        check(result.status == PurpleSyncSettingsFile.WriteStatus.ReadBackMismatch && result.wrote()
                && Arrays.equals(result.readBack.bytes, utf8("other = 1\n")), "read-back mismatch");
        PurpleSettings.writeInstead = null;

        PurpleSettings.throwAfterWrite = true;
        result = PurpleSyncSettingsFile.write(utf8("d = 4\n"), "r", true);
        check(result.status == PurpleSyncSettingsFile.WriteStatus.Written, "exception after the write still reads back");
        PurpleSettings.throwAfterWrite = false;

        final int calls = PurpleSettings.calls;
        check(PurpleSyncSettingsFile.write(new byte[PurpleSyncSettingsFile.MAX_BYTES + 1], "r", true).status
                == PurpleSyncSettingsFile.WriteStatus.WriteFailed, "oversized write refused");
        check(PurpleSyncSettingsFile.write(null, "r", true).status
                == PurpleSyncSettingsFile.WriteStatus.WriteFailed, "null write refused");
        check(PurpleSettings.calls == calls, "refused writes never reach PurpleSettings");
        check(PurpleSyncSettingsFile.read().status == PurpleSyncSettingsFile.Status.Present, "production read uses settingsFile()");
    }

    static void nativeFingerprint() throws Exception {
        final byte[][] samples = {
            new byte[0],
            utf8("version = 1\n"),
            { (byte) 0xff, 0, (byte) 0xc3, (byte) 0x28, '\n', '"', '\\' },
            new byte[PurpleSyncHistory.MAX_TEXT_BYTES],
        };
        for (byte[] sample : samples) {
            final String fingerprint = PurpleSyncHistory.NATIVE.fingerprint(sample);
            check(oracle(sample).equals(fingerprint), "core fingerprint of " + sample.length + " bytes: " + fingerprint);
        }
        check(PurpleSyncHistory.NATIVE.fingerprint(null) == null, "no bytes: no fingerprint");
        check(PurpleSyncHistory.NATIVE.isVersionKey("1." + oracle(new byte[0])), "a valid version key is accepted");
        check(PurpleSyncHistory.NATIVE.isVersionKey("42." + oracle(utf8("x"))), "a later generation is accepted");
        check(!PurpleSyncHistory.NATIVE.isVersionKey("0." + oracle(new byte[0])), "generation zero refused");
        check(!PurpleSyncHistory.NATIVE.isVersionKey("1." + oracle(new byte[0]).toUpperCase()), "upper-case hash refused");
        check(!PurpleSyncHistory.NATIVE.isVersionKey("1.x"), "malformed key refused");
        check(!PurpleSyncHistory.NATIVE.isVersionKey(""), "empty key refused");
        check(!PurpleSyncHistory.NATIVE.isVersionKey(null), "null key refused");

        final File root = fresh("native");
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), PurpleSyncHistory.NATIVE, new TestClock());
        final byte[] text = utf8("version = 1\nname = 'native'\n");
        final PurpleSyncHistory.Entry entry = history.save(text, PurpleSyncHistory.Reason.BeforeChoice, "native", "", null);
        check(entry != null && oracle(text).equals(entry.fingerprint), "save through the real core");
        check(entry != null && Arrays.equals(history.read(entry.id), text), "read through the real core");
        final String key = "1." + oracle(text);
        final PurpleSyncHistory.Entry keyed = history.save(text, PurpleSyncHistory.Reason.BeforeUpdate, "native", key, null);
        check(keyed != null && key.equals(keyed.versionKey), "a valid version key is saved");
        check(keyed != null && Arrays.equals(history.read(keyed.id), text), "an entry with a version key reads back");
        boolean listed = false;
        for (PurpleSyncHistory.Entry listedEntry : history.list()) {
            listed |= keyed != null && listedEntry.id.equals(keyed.id) && key.equals(listedEntry.versionKey);
        }
        check(listed, "an entry with a version key is listed");
        check(history.save(text, PurpleSyncHistory.Reason.BeforeChoice, "native", "1.x", null) == null,
                "an invalid version key is refused");
    }

    static void noCore() throws Exception {
        check(PurpleSyncHistory.NATIVE.fingerprint(utf8("a")) == null, "no library: no fingerprint");
        check(!PurpleSyncHistory.NATIVE.isVersionKey("1." + oracle(new byte[0])), "no library: no version key");
        final File root = fresh("nocore");
        final PurpleSyncHistory history = new PurpleSyncHistory(historyDir(root), PurpleSyncHistory.NATIVE, new TestClock());
        check(history.save(utf8("a = 1\n"), PurpleSyncHistory.Reason.BeforeUpdate, "", "", null) == null,
                "no library: save refused");
        check(!historyDir(root).exists(), "no library: nothing created");
    }
}
