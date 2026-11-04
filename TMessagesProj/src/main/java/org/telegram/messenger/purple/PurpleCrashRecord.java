/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The crash records of Purple's diagnostic log, kept free of Android so host
 * tests run this production class unchanged. {@link PurpleCrashCapture} feeds
 * it a Java exception at crash time and the process exit reasons on the next
 * start.
 *
 * A record is one {@code crash start} line, the {@code crash exit} line of its
 * exit reason when there is one, a {@code crash library} line with the build
 * id of each of the app's own native libraries its frames name, its
 * {@code crash frame} lines (and {@code crash cause} lines with three frames
 * each for a Java exception's causes), and one {@code crash end frames=n}
 * line. Every line is built by the {@link PurpleDiagLogStore} factories, so
 * it holds code names, numbers that are codes or clamped counts, and words of
 * the vocabulary: never an exception message, an abort message, a thread
 * name, a path or anything of the user's.
 *
 * Exit reasons without a trace (low memory, a user stop and the others) are a
 * single {@code crash exit} line, not a record.
 */
final class PurpleCrashRecord {
    static final String PENDING = "pending-crash.log";
    /** The pending file stops growing at this size: a crash loop cannot fill the disk. */
    static final int PENDING_LIMIT = 64 * 1024;
    static final int FRAMES = 12;
    static final int CAUSE_FRAMES = 3;
    static final int CAUSES = 4;
    /** At most this many records per start; the rest are counted in one {@code crash repeated} line. */
    static final int RECORDS = 5;
    /** A Java crash and a {@code REASON_CRASH} exit this close in time are one crash. */
    static final long MATCH = 10_000L;
    /**
     * A tombstone is read up to this many bytes. Its threads (field 16) come
     * before the memory maps, the log buffers and the open files, so the
     * crashing thread's frames are well inside it.
     */
    static final int TOMBSTONE_LIMIT = 1024 * 1024;
    /** An ANR trace is read as a stream, at most this many bytes, and only up to the end of the main thread's block. */
    static final int ANR_LIMIT = 1024 * 1024;

    // ApplicationExitInfo reason codes, which are stable public API constants.
    static final int REASON_CRASH = 4;
    static final int REASON_CRASH_NATIVE = 5;
    static final int REASON_ANR = 6;

    private static final String[] REASONS = {"unknown", "exit_self", "signaled", "low_memory", "crash",
            "crash_native", "anr", "initialization_failure", "permission_change", "excessive_resource_usage",
            "user_requested", "user_stopped", "dependency_died", "other", "freezer", "package_state_change",
            "package_updated"};

    private PurpleCrashRecord() {
    }

    /**
     * Loads and initialises this class, {@link PurpleDiagLogStore} (with its
     * vocabulary and patterns) and the record path once, so the uncaught
     * exception handler later runs on loaded classes and allocates little in
     * a process that may be out of memory. Called from the collector thread.
     */
    static void preload() {
        Throwable probe = new Throwable();
        probe.setStackTrace(new StackTraceElement[]{new StackTraceElement("org.telegram.messenger.purple.Preload", "run", null, 1)});
        javaCrash(probe, false);
        pendingBody("crash end frames=0");
    }

    static String reasonName(int reason) {
        return reason >= 0 && reason < REASONS.length ? REASONS[reason] : "other";
    }

    private static String body(String event, PurpleDiagLogStore.Field... fields) {
        return PurpleDiagLogStore.body("crash", event, fields);
    }

    /**
     * One exit reason, as it was read from {@code ApplicationExitInfo}. A
     * native crash or an ANR builds its record here, from its trace stream,
     * so only the record's few lines are kept and the trace's bytes are
     * garbage before the next exit is read.
     */
    static final class Exit {
        final long time;
        final int reason;
        final int status;
        final int importance;
        final boolean main;
        final long pssKb;
        final long rssKb;
        /** The record of a native crash or an ANR, or null for any other reason. */
        final List<String> record;

        /** {@code trace} may be null; it is read here and not closed. */
        Exit(long time, int reason, int status, int importance, boolean main, long pssKb, long rssKb, InputStream trace) {
            this.time = time;
            this.reason = reason;
            this.status = status;
            this.importance = importance;
            this.main = main;
            this.pssKb = pssKb;
            this.rssKb = rssKb;
            this.record = reason == REASON_CRASH_NATIVE ? nativeCrash(this, trace)
                    : reason == REASON_ANR ? anr(this, trace) : null;
        }
    }

    /** Counts what is read and ends at {@code limit}, so a trace never costs more than its cap. */
    static final class Bounded extends InputStream {
        private final InputStream in;
        private final int limit;
        int consumed;

        Bounded(InputStream in, int limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (consumed >= limit) return -1;
            int b = in.read();
            if (b >= 0) ++consumed;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (consumed >= limit) return -1;
            int count = in.read(buffer, offset, Math.min(length, limit - consumed));
            if (count > 0) consumed += count;
            return count;
        }
    }

    /** At most {@code limit} bytes of {@code in}, or what was read before an error; null for no stream. */
    static byte[] readUpTo(InputStream in, int limit) {
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            InputStream bounded = new Bounded(in, limit);
            byte[] buffer = new byte[16384];
            int count;
            while ((count = bounded.read(buffer, 0, buffer.length)) != -1) out.write(buffer, 0, count);
        } catch (Throwable ignored) {
            // Keep what was read.
        }
        return out.toByteArray();
    }

    /** A record built at crash time and kept in the pending file until the next start. */
    static final class Pending {
        final long time;
        final List<String> bodies;

        Pending(long time, List<String> bodies) {
            this.time = time;
            this.bodies = bodies;
        }
    }

    private static int mb(long kb) {
        long mb = kb < 0 ? -1 : kb / 1024;
        return (int) Math.max(-1, Math.min(mb, Integer.MAX_VALUE));
    }

    static String exitBody(Exit exit) {
        return body("exit", PurpleDiagLogStore.reason(reasonName(exit.reason)),
                PurpleDiagLogStore.count("status", exit.status),
                PurpleDiagLogStore.count("importance", exit.importance),
                PurpleDiagLogStore.flag("main", exit.main),
                PurpleDiagLogStore.count("pss_mb", mb(exit.pssKb)),
                PurpleDiagLogStore.count("rss_mb", mb(exit.rssKb)));
    }

    // ---- Java exceptions -------------------------------------------------

    private static void javaFrames(StackTraceElement[] trace, int limit, List<String> out) {
        if (trace == null) return;
        for (int i = 0; i < trace.length && i < limit; ++i) {
            StackTraceElement e = trace[i];
            int line = e == null || e.isNativeMethod() ? -1 : e.getLineNumber();
            out.add(body("frame", PurpleDiagLogStore.count("n", i + 1),
                    PurpleDiagLogStore.frame(e == null ? null : e.getClassName(), e == null ? null : e.getMethodName(),
                            line)));
        }
    }

    /**
     * The record of an uncaught exception: its class (never its message),
     * whether it was on the main thread, its top {@value #FRAMES} frames, and
     * each cause's class and top {@value #CAUSE_FRAMES} frames, at most
     * {@value #CAUSES} causes deep.
     */
    static List<String> javaCrash(Throwable error, boolean mainThread) {
        List<String> out = new ArrayList<>();
        out.add(body("start", PurpleDiagLogStore.reason("crash"),
                PurpleDiagLogStore.codeName("class", error == null ? null : error.getClass().getName()),
                PurpleDiagLogStore.flag("main_thread", mainThread)));
        javaFrames(error == null ? null : error.getStackTrace(), FRAMES, out);
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        if (error != null) seen.put(error, true);
        Throwable cause = error == null ? null : error.getCause();
        for (int depth = 1; cause != null && depth <= CAUSES && !seen.containsKey(cause); ++depth) {
            seen.put(cause, true);
            out.add(body("cause", PurpleDiagLogStore.count("depth", depth),
                    PurpleDiagLogStore.codeName("class", cause.getClass().getName())));
            javaFrames(cause.getStackTrace(), CAUSE_FRAMES, out);
            cause = cause.getCause();
        }
        return end(out);
    }

    private static List<String> end(List<String> out) {
        int frames = 0;
        for (String line : out) if (line.startsWith("crash frame ")) ++frames;
        out.add(body("end", PurpleDiagLogStore.count("frames", frames)));
        return out;
    }

    // ---- Native crashes --------------------------------------------------

    /** The record of a native crash from its tombstone, or from the exit line alone when there is none. */
    static List<String> nativeCrash(Exit exit, InputStream trace) {
        byte[] data = readUpTo(trace, TOMBSTONE_LIMIT);
        PurpleCrashTombstone tombstone = PurpleCrashTombstone.parse(data, data == null ? 0 : data.length);
        data = null;
        List<String> out = new ArrayList<>();
        out.add(body("start", PurpleDiagLogStore.reason("crash_native"),
                tombstone.signal < 0 ? null : PurpleDiagLogStore.signal(tombstone.signal, tombstone.code),
                PurpleDiagLogStore.code("abort", tombstone.abort),
                tombstone.memory == null ? null : PurpleDiagLogStore.code("tool", tombstone.tool),
                tombstone.memory == null ? null : PurpleDiagLogStore.code("memory", tombstone.memory)));
        out.add(exitBody(exit));
        Map<String, String> libraries = new LinkedHashMap<>();
        for (PurpleCrashTombstone.Frame frame : tombstone.frames) {
            String lib = PurpleDiagLogStore.appLibrary(frame.file);
            if (lib != null && !libraries.containsKey(lib)) libraries.put(lib, frame.buildId);
        }
        for (Map.Entry<String, String> lib : libraries.entrySet()) {
            out.add(body("library", PurpleDiagLogStore.library(lib.getKey(), lib.getValue())));
        }
        int n = 0;
        for (PurpleCrashTombstone.Frame frame : tombstone.frames) {
            if (n >= FRAMES) break;
            out.add(body("frame", PurpleDiagLogStore.count("n", ++n),
                    PurpleDiagLogStore.nativeFrame(frame.file, frame.function, frame.relativePc)));
        }
        return end(out);
    }

    // ---- ANRs --------------------------------------------------------------

    /** A main thread state word from the last word of the thread's header line in an ANR trace. */
    static String threadState(String word) {
        if (word == null) return "other";
        switch (word) {
            case "Runnable": return "runnable";
            case "Sleeping": return "sleeping";
            case "Waiting": return "waiting";
            case "TimedWaiting": return "timed_waiting";
            case "Blocked": return "blocked";
            case "Native": return "native";
            case "Suspended": return "suspended";
            default: return word.contains("Gc") ? "gc" : "other";
        }
    }

    /**
     * The record of an ANR from its text trace: the main thread's state and
     * its top {@value #FRAMES} frames, Java ({@code at ...}) and native
     * ({@code native: #nn pc ...}) in their order. Lock lines, which hold
     * object addresses and thread numbers, and every other thread are skipped.
     * The trace is read line by line, at most {@value #ANR_LIMIT} bytes, and
     * reading stops at the end of the main thread's block, which ART prints
     * first.
     */
    static List<String> anr(Exit exit, InputStream trace) {
        String state = null;
        List<String> frames = new ArrayList<>();
        Map<String, String> libraries = new LinkedHashMap<>();
        boolean inMain = false;
        BufferedReader reader = trace == null ? null
                : new BufferedReader(new InputStreamReader(new Bounded(trace, ANR_LIMIT), StandardCharsets.UTF_8), 4096);
        while (reader != null) {
            String raw;
            try {
                raw = reader.readLine();
            } catch (Throwable e) {
                raw = null;
            }
            if (raw == null) break;
            String line = raw.trim();
            if (!inMain) {
                if (line.startsWith("\"main\" ") && line.contains(" tid=1 ")) {
                    inMain = true;
                    int space = line.lastIndexOf(' ');
                    state = threadState(line.substring(space + 1));
                }
                continue;
            }
            if (line.isEmpty()) break;
            if (frames.size() >= FRAMES) break;
            if (line.startsWith("at ")) {
                frames.add(javaTraceFrame(line.substring(3), frames.size() + 1));
            } else if (line.startsWith("native: ")) {
                String frame = nativeTraceFrame(line, frames.size() + 1, libraries);
                if (frame != null) frames.add(frame);
            }
        }
        List<String> out = new ArrayList<>();
        out.add(body("start", PurpleDiagLogStore.reason("anr"),
                state == null ? null : PurpleDiagLogStore.code("state", state)));
        out.add(exitBody(exit));
        for (Map.Entry<String, String> lib : libraries.entrySet()) {
            out.add(body("library", PurpleDiagLogStore.library(lib.getKey(), lib.getValue())));
        }
        out.addAll(frames);
        return end(out);
    }

    /** {@code org.x.Cls.method(File.java:12)} or {@code (Native method)}, as one scrubbed frame line. */
    static String javaTraceFrame(String text, int n) {
        int paren = text.indexOf('(');
        String name = paren < 0 ? text : text.substring(0, paren);
        int dot = name.lastIndexOf('.');
        String cls = dot < 0 ? null : name.substring(0, dot);
        String method = dot < 0 ? null : name.substring(dot + 1);
        int line = -1;
        if (paren >= 0) {
            int close = text.indexOf(')', paren);
            String location = text.substring(paren + 1, close < 0 ? text.length() : close);
            int colon = location.lastIndexOf(':');
            if (colon >= 0) {
                try {
                    line = Integer.parseInt(location.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                    line = -1;
                }
            }
        }
        return body("frame", PurpleDiagLogStore.count("n", n), PurpleDiagLogStore.frame(cls, method, line));
    }

    /**
     * {@code native: #05 pc 00000000000a1234  /path/libx.so (offset 0x1000) (function+40) (BuildId: abc)}
     * as one scrubbed frame line; the path is reduced to an app library's
     * base name or {@code system}, and the build id is remembered for the
     * record's library lines.
     */
    static String nativeTraceFrame(String line, int n, Map<String, String> libraries) {
        String[] words = line.split("\\s+");
        int pc = -1;
        for (int i = 0; i < words.length; ++i) {
            if (words[i].equals("pc")) {
                pc = i;
                break;
            }
        }
        if (pc < 0 || pc + 2 >= words.length) return null;
        long relative = hex(words[pc + 1]);
        String path = words[pc + 2];
        String function = null;
        String build = null;
        int at = line.indexOf(path) + path.length();
        while (true) {
            int open = line.indexOf('(', at);
            if (open < 0) break;
            int close = line.indexOf(')', open);
            if (close < 0) break;
            String group = line.substring(open + 1, close);
            if (group.startsWith("BuildId: ")) build = group.substring("BuildId: ".length()).trim();
            else if (!group.startsWith("offset ") && function == null) function = group;
            at = close + 1;
        }
        String lib = PurpleDiagLogStore.appLibrary(path);
        if (lib != null && !libraries.containsKey(lib)) libraries.put(lib, build);
        return body("frame", PurpleDiagLogStore.count("n", n), PurpleDiagLogStore.nativeFrame(path, function, relative));
    }

    /** A hex number of at most 15 significant digits (Long.parseUnsignedLong needs API 26), or -1. */
    static long hex(String text) {
        if (text == null || text.isEmpty() || text.length() > 32) return -1;
        int first = 0;
        while (first < text.length() - 1 && text.charAt(first) == '0') ++first;
        text = text.substring(first);
        if (text.length() > 15) return -1;
        long value = 0;
        for (int i = 0; i < text.length(); ++i) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) return -1;
            value = value << 4 | digit;
        }
        return value;
    }

    // ---- The pending file --------------------------------------------------

    /** A line the pending file may hold: a crash body of printable ASCII that fits a stamped line. */
    static boolean pendingBody(String body) {
        if (body == null || !body.startsWith("crash ") || body.length() > PurpleDiagLogStore.LINE_LIMIT - 21) return false;
        for (int i = 0; i < body.length(); ++i) {
            char c = body.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    /**
     * Appends one record to the pending file and syncs it to disk before
     * returning, because the process dies right after. Called from the
     * uncaught exception handler, so it never throws; a file already at
     * {@value #PENDING_LIMIT} bytes is left as it is.
     */
    static boolean writePending(File file, long time, List<String> bodies) {
        try {
            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) return false;
            StringBuilder text = new StringBuilder("record ").append(time).append('\n');
            for (String body : bodies) if (pendingBody(body)) text.append(body).append('\n');
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            if (file.length() + bytes.length > PENDING_LIMIT) return false;
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** The records in the pending file, oldest first; damaged ones and lines that are not crash bodies are skipped. */
    static List<Pending> readPending(File file) {
        List<Pending> out = new ArrayList<>();
        if (file == null || !file.isFile()) return out;
        String text;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while (bytes.size() < PENDING_LIMIT && (count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            text = new String(bytes.toByteArray(), 0, Math.min(bytes.size(), PENDING_LIMIT), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return out;
        }
        Pending current = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("record ")) {
                current = null;
                try {
                    current = new Pending(Long.parseLong(line.substring(7).trim()), new ArrayList<>());
                    out.add(current);
                } catch (NumberFormatException ignored) {
                }
            } else if (current != null && pendingBody(line)) {
                current.bodies.add(line);
            }
        }
        List<Pending> valid = new ArrayList<>();
        for (Pending record : out) {
            if (!record.bodies.isEmpty() && record.bodies.get(0).startsWith("crash start ")) valid.add(record);
        }
        return valid;
    }

    // ---- One start's lines ---------------------------------------------------

    private static final class Item {
        final long time;
        final List<String> bodies;
        final boolean record;

        Item(long time, List<String> bodies, boolean record) {
            this.time = time;
            this.bodies = bodies;
            this.record = record;
        }
    }

    /**
     * The stamped lines for one start: every new exit reason and every
     * pending Java crash, oldest first. A {@code REASON_CRASH} exit within
     * {@value #MATCH} ms of a pending Java crash joins its record as the
     * record's exit line. At most {@value #RECORDS} records are written,
     * through a {@link PurpleDiagRepeats} limiter of their own; the rest are
     * counted in one {@code crash repeated count=n} line at {@code now}.
     * Exit lines without a record are not limited (there are at most 16).
     */
    static List<String> plan(List<Exit> exits, List<Pending> pending, long now) {
        List<Exit> sorted = new ArrayList<>(exits);
        Collections.sort(sorted, (a, b) -> Long.compare(a.time, b.time));
        List<Pending> unused = new ArrayList<>(pending);
        List<Item> items = new ArrayList<>();
        for (Exit exit : sorted) {
            if (exit.record != null) {
                items.add(new Item(exit.time, exit.record, true));
            } else if (exit.reason == REASON_CRASH) {
                Pending match = null;
                for (Pending candidate : unused) {
                    long gap = Math.abs(candidate.time - exit.time);
                    if (gap <= MATCH && (match == null || gap < Math.abs(match.time - exit.time))) match = candidate;
                }
                if (match != null) {
                    unused.remove(match);
                    List<String> bodies = new ArrayList<>(match.bodies);
                    bodies.add(1, exitBody(exit));
                    items.add(new Item(match.time, bodies, true));
                } else {
                    items.add(new Item(exit.time, Collections.singletonList(exitBody(exit)), false));
                }
            } else {
                items.add(new Item(exit.time, Collections.singletonList(exitBody(exit)), false));
            }
        }
        for (Pending record : unused) items.add(new Item(record.time, record.bodies, true));
        Collections.sort(items, (a, b) -> Long.compare(a.time, b.time));

        PurpleDiagRepeats limit = new PurpleDiagRepeats(Long.MAX_VALUE, RECORDS, 1);
        List<String> out = new ArrayList<>();
        for (Item item : items) {
            if (item.record && !limit.offer(now, "crash record", "crash", "start").write) continue;
            for (String body : item.bodies) out.add(PurpleDiagLogStore.stamped(item.time, body));
        }
        int dropped = 0;
        for (PurpleDiagRepeats.Summary summary : limit.flush()) dropped += summary.dropped;
        if (dropped > 0) {
            out.add(PurpleDiagLogStore.line(now, "crash", "repeated", PurpleDiagLogStore.count("count", dropped)));
        }
        return out;
    }
}
