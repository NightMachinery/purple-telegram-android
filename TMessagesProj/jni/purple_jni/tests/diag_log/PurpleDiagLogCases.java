package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Host cases for the production PurpleDiagLogStore: the vocabulary, the line
 * format, the no-identifier rules of every field factory, message scrubbing
 * (including the review's probe), and the size bound with one rotated file.
 * Run against the AGP-compiled class by run_diag_log.py.
 */
public final class PurpleDiagLogCases {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }

    /**
     * Everything after the timestamp: no run of six or more digits (every
     * Telegram user, chat and account id is longer; clamped counts stop at
     * five digits), no path, printable ASCII.
     */
    private static void clean(String line, String what) {
        String body = line.substring(line.indexOf(' ') + 1);
        int digits = 0;
        for (int i = 0; i < body.length(); ++i) {
            char c = body.charAt(i);
            digits = c >= '0' && c <= '9' ? digits + 1 : 0;
            check(digits < 6, what + ": no digit run of six or more in " + body);
            check(c != '/' && c != '\\', what + ": no path separator in " + body);
            check(c >= 0x20 && c <= 0x7e, what + ": printable ASCII only");
        }
    }

    public static int run(File root) throws Exception {
        checks = 0;
        check(PurpleDiagLogStore.LIMIT == 256 * 1024, "the production bound is 256 KB");

        // Vocabulary.
        check(!PurpleDiagLogStore.VOCABULARY.isEmpty(), "the vocabulary is not empty");
        for (String word : PurpleDiagLogStore.VOCABULARY) {
            check(PurpleDiagLogStore.tokenShape(word), "every vocabulary word is a lowercase token: " + word);
            check(PurpleDiagLogStore.token(word).equals(word), "a vocabulary word passes: " + word);
        }
        check(!PurpleDiagLogStore.VOCABULARY.contains(PurpleDiagLogStore.INVALID), "invalid is not a word");

        // Format.
        long at = 1792886400000L; // 2026-10-25T00:00:00Z
        String line = PurpleDiagLogStore.line(at, "diag", "exported",
                PurpleDiagLogStore.slot(0), PurpleDiagLogStore.reason("export_failed"),
                PurpleDiagLogStore.flag("lines", false), PurpleDiagLogStore.count("lines", 3),
                PurpleDiagLogStore.mode(1), PurpleDiagLogStore.version("lines", 2));
        check(line.equals("2026-10-25T00:00:00Z diag exported slot=0 reason=export_failed lines=0 lines=3 mode=1 lines=2"),
                "exact line format: " + line);
        check(PurpleDiagLogStore.line(at, "diag", "export").equals("2026-10-25T00:00:00Z diag export"), "fieldless line");
        check(PurpleDiagLogStore.line(at, "diag", "export", PurpleDiagLogStore.code("lines", "cleared"),
                PurpleDiagLogStore.code("lines", "Work"), PurpleDiagLogStore.code("Who", "export"))
                .endsWith("lines=cleared lines=invalid invalid=export"), "code fields take vocabulary words only");

        // Identifiers cannot pass through any field.
        String hostile = PurpleDiagLogStore.line(at, "Lists", "user_123456",
                PurpleDiagLogStore.slot(123456789), PurpleDiagLogStore.slot(-1),
                PurpleDiagLogStore.count("lines", 1234567890), PurpleDiagLogStore.count("lines", -5),
                PurpleDiagLogStore.mode(77), PurpleDiagLogStore.version("lines", 123456),
                PurpleDiagLogStore.reason("John Smith"), PurpleDiagLogStore.reason("0-123456789"),
                PurpleDiagLogStore.reason("peer_42"), PurpleDiagLogStore.count("Name", 1),
                PurpleDiagLogStore.flag("token=abc", true));
        clean(hostile, "hostile fields");
        check(hostile.contains(" invalid invalid "), "bad area and event become invalid: " + hostile);
        check(hostile.contains("slot=? slot=?"), "out-of-range slots are masked");
        check(hostile.contains("lines=99999+ lines=neg"), "counts are clamped");
        check(hostile.contains("mode=? lines=?"), "modes and versions are bounded");
        check(hostile.contains("reason=invalid reason=invalid reason=invalid"), "names, keys and unknown codes are refused");
        check(hostile.contains("invalid=1 invalid=1"), "unknown field names are refused");
        // The reviewer's probe: lowercase names and list keys look like codes but are not words.
        String probe = PurpleDiagLogStore.line(at, "diag", "export", PurpleDiagLogStore.reason("alice"),
                PurpleDiagLogStore.code("list", "work_projects"), PurpleDiagLogStore.reason("johnsmith"));
        check(probe.endsWith("diag export reason=invalid invalid=invalid reason=invalid"), "probe codes are refused: " + probe);

        // Exception messages are scrubbed; JSON messages are dropped.
        String errors = PurpleDiagLogStore.line(at, "diag", "export_failed",
                PurpleDiagLogStore.error(new IllegalStateException(
                        "open failed: /data/user/0/org.purple/files/purple/accounts/0-123456789/settings.toml: ENOENT")),
                PurpleDiagLogStore.error(new org.json.JSONException("Value Alice of type java.lang.String")),
                PurpleDiagLogStore.error(new RuntimeException("list 'Alice and Bob' for \"Carol\" id 5550123\nnext")),
                PurpleDiagLogStore.error(null));
        clean(errors, "sanitized errors");
        check(errors.contains("error=IllegalStateException:\"open failed: <path>: ENOENT\""), "paths are dropped: " + errors);
        check(errors.contains("error=JSONException "), "JSON exceptions keep only their class");
        check(!errors.contains("Alice") && !errors.contains("Bob") && !errors.contains("Carol"), "quoted text is dropped");
        check(errors.contains("id #"), "digit runs become #");
        check(errors.endsWith("error=none"), "a null error is explicit");
        String[][] scrubbed = {
                {"peer -1001234567890 not found", "peer -# not found"},
                {"key 0-123456789 invalid", "key <phone> invalid"},
                {"hash deadbeefcafebabe0011 mismatch", "hash <hex> mismatch"},
                {"token a1b2c3d4e5f6a7b8 rejected", "token <hex> rejected"},
                {"token DEADBEEF rejected", "token <hex> rejected"},
                {"user alice@example.com refused", "user <email> refused"},
                {"username @johnsmith not resolved", "username <handle> not resolved"},
                {"phone +49 151 2345678", "phone <phone>"},
                {"call +1 (555) 12-34-567 now", "call <phone> now"},
                {"dial 555-12-34 or 55 51 23 4", "dial <phone> or <phone>"},
                {"can't open file /data/user/0/x/files/purple/settings.toml", "can<q>"},
                {"cannot open file /data/user/0/x/files/purple/settings.toml", "cannot open file <path>"},
                {"version 12.10.1 build 70389", "version 12.10.1 build #"},
                {"missing settings route", "missing settings route"},
        };
        for (String[] sample : scrubbed) {
            String got = PurpleDiagLogStore.sanitize(sample[0]);
            check(got.equals(sample[1]), "scrub " + sample[0] + " -> " + got);
            String probed = PurpleDiagLogStore.line(at, "diag", "export_failed",
                    PurpleDiagLogStore.error(new RuntimeException(sample[0])));
            clean(probed, "probe " + sample[0]);
            for (String secret : new String[]{"alice", "example.com", "johnsmith", "a1b2c3d4", "deadbeef", "DEADBEEF",
                    "2345678", "151 ", "1234567890", "123456789"}) {
                check(!probed.contains(secret), "probe keeps no " + secret + ": " + probed);
            }
        }
        // What scrubbing does not promise: an unquoted name in free text stays.
        check(PurpleDiagLogStore.sanitize("list Work Projects missing members").equals("list Work Projects missing members"),
                "other words of a message are kept, as documented");
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; ++i) huge.append('x');
        String longest = PurpleDiagLogStore.line(at, "diag", "export_failed",
                PurpleDiagLogStore.error(new RuntimeException(huge.toString())),
                PurpleDiagLogStore.error(new RuntimeException(huge.toString())),
                PurpleDiagLogStore.error(new RuntimeException(huge.toString())),
                PurpleDiagLogStore.error(new RuntimeException(huge.toString())),
                PurpleDiagLogStore.error(new RuntimeException(huge.toString())));
        check(longest.length() <= PurpleDiagLogStore.LINE_LIMIT, "a line is at most 512 characters");

        // Bounds and rotation.
        File directory = new File(root, "store");
        PurpleDiagLogStore store = new PurpleDiagLogStore(directory, 2048);
        check(store.read().isEmpty(), "an absent log reads empty");
        for (int i = 0; i < 300; ++i) {
            check(store.append(PurpleDiagLogStore.line(at + i * 1000L, "diag", "exported", PurpleDiagLogStore.count("lines", i))),
                    "append succeeds");
            check(store.current().length() <= 2048, "current file never exceeds the bound");
            check(!store.rotated().exists() || store.rotated().length() <= 2048, "rotated file never exceeds the bound");
        }
        check(store.rotated().isFile(), "the log rotated");
        String[] files = directory.list();
        check(files != null && files.length == 2, "exactly one current and one rotated file");
        String text = store.read();
        check(text.length() <= 4096 && text.endsWith("\n"), "the whole log is at most twice the bound");
        String[] lines = text.split("\n");
        check(lines[lines.length - 1].endsWith("lines=299"), "the newest line is last");
        check(!text.contains(" lines=0\n"), "the oldest lines were dropped");
        int previous = -1;
        for (String each : lines) {
            check(each.matches("2026-10-25T\\d\\d:\\d\\d:\\d\\dZ diag exported lines=\\d+"), "every kept line is whole: " + each);
            int n = Integer.parseInt(each.substring(each.lastIndexOf('=') + 1));
            check(n == previous + 1 || previous == -1, "kept lines are contiguous and ordered");
            previous = n;
        }
        check(store.append("first\nsecond") && store.read().endsWith("first second\n"), "a newline cannot split a line");
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 4000; ++i) oversized.append('y');
        check(store.append(oversized.toString()), "an oversized line is accepted truncated");
        check(store.current().length() <= 2048, "an oversized line still respects the bound");

        // Clearing and failures.
        check(store.clear() && store.read().isEmpty() && !store.current().exists() && !store.rotated().exists(),
                "clear removes both files");
        File blocked = new File(root, "blocked");
        Files.write(blocked.toPath(), "not a directory".getBytes(StandardCharsets.UTF_8));
        PurpleDiagLogStore broken = new PurpleDiagLogStore(blocked, 2048);
        check(!broken.append("line"), "an unwritable directory fails quietly");
        check(broken.read().isEmpty(), "an unreadable log reads empty");
        PurpleDiagLogStore tiny = new PurpleDiagLogStore(new File(root, "tiny"), 1);
        for (int i = 0; i < 50; ++i) tiny.append(PurpleDiagLogStore.line(at, "diag", "export"));
        check(tiny.current().length() <= PurpleDiagLogStore.LINE_LIMIT * 2L, "a tiny bound is raised to two lines");
        return checks;
    }
}
