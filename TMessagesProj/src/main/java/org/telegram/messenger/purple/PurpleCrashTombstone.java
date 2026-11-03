/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the few fields crash capture keeps from an Android tombstone in its
 * protobuf form (what {@code ApplicationExitInfo.getTraceInputStream()}
 * returns for a native crash on API 31 and later), without a protobuf
 * library. Kept free of Android so host tests run this production class.
 *
 * Field numbers are those of AOSP's {@code system/core/debuggerd/proto/tombstone.proto}:
 * {@code Tombstone.tid = 6}, {@code signal_info = 10} ({@code number = 1},
 * {@code code = 3}), {@code abort_message = 14}, {@code causes = 15}
 * ({@code memory_error = 2} with {@code tool = 1}, {@code type = 2}), and
 * {@code threads = 16}, a map from thread id to {@code Thread}
 * ({@code id = 1}, {@code current_backtrace = 4}), whose
 * {@code BacktraceFrame} has {@code rel_pc = 1}, {@code function_name = 4},
 * {@code file_name = 6} and {@code build_id = 8}. Every other field, among
 * them the command line, registers, memory, open files and the log buffers,
 * is skipped unread.
 *
 * The abort message is never kept: only {@link #abortClass} of it is. A
 * truncated or malformed stream yields what was read before the damage.
 */
final class PurpleCrashTombstone {
    /** One frame of the crashing thread, raw: the factories in {@link PurpleDiagLogStore} scrub it. */
    static final class Frame {
        final long relativePc;
        final String function;
        final String file;
        final String buildId;

        Frame(long relativePc, String function, String file, String buildId) {
            this.relativePc = relativePc;
            this.function = function;
            this.file = file;
            this.buildId = buildId;
        }
    }

    int signal = -1;
    int code = Integer.MIN_VALUE;
    /** A word of the diagnostic vocabulary, see {@link #abortClass}. */
    String abort = "none";
    /** The first memory error cause: a tool word and a type word, or null. */
    String tool;
    String memory;
    final List<Frame> frames = new ArrayList<>();

    static final int FRAMES = 12;

    private static final class Reader {
        final byte[] data;
        int pos;
        final int end;

        Reader(byte[] data, int pos, int end) {
            this.data = data;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IndexOutOfBoundsException();
                byte b = data[pos++];
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException();
        }

        /** The bounds of a length-delimited field, which must lie inside this reader. */
        Reader nested() {
            long length = varint();
            if (length < 0 || length > end - pos) throw new IndexOutOfBoundsException();
            Reader inner = new Reader(data, pos, pos + (int) length);
            pos += (int) length;
            return inner;
        }

        String string() {
            Reader inner = nested();
            return new String(data, inner.pos, inner.end - inner.pos, StandardCharsets.UTF_8);
        }

        void skip(int wire) {
            switch (wire) {
                case 0: varint(); break;
                case 1: advance(8); break;
                case 2: nested(); break;
                case 5: advance(4); break;
                default: throw new IllegalArgumentException();
            }
        }

        private void advance(int count) {
            if (end - pos < count) throw new IndexOutOfBoundsException();
            pos += count;
        }
    }

    /** Parses {@code length} bytes of {@code data}; never throws and never returns null. */
    static PurpleCrashTombstone parse(byte[] data, int length) {
        PurpleCrashTombstone out = new PurpleCrashTombstone();
        if (data == null) return out;
        Reader top = new Reader(data, 0, Math.max(0, Math.min(length, data.length)));
        long tid = -1;
        List<long[]> threads = new ArrayList<>(); // {id, start, end} of each Thread message
        try {
            while (top.more()) {
                long key = top.varint();
                int field = (int) (key >>> 3);
                int wire = (int) (key & 7);
                if (field == 6 && wire == 0) {
                    tid = top.varint() & 0xffffffffL;
                } else if (field == 10 && wire == 2) {
                    Reader signal = top.nested();
                    while (signal.more()) {
                        long k = signal.varint();
                        if ((k >>> 3) == 1 && (k & 7) == 0) out.signal = (int) signal.varint();
                        else if ((k >>> 3) == 3 && (k & 7) == 0) out.code = (int) signal.varint();
                        else signal.skip((int) (k & 7));
                    }
                } else if (field == 14 && wire == 2) {
                    out.abort = abortClass(top.string());
                } else if (field == 15 && wire == 2) {
                    Reader cause = top.nested();
                    while (cause.more()) {
                        long k = cause.varint();
                        if ((k >>> 3) == 2 && (k & 7) == 2 && out.memory == null) {
                            Reader error = cause.nested();
                            int tool = 0;
                            int type = 0;
                            while (error.more()) {
                                long e = error.varint();
                                if ((e >>> 3) == 1 && (e & 7) == 0) tool = (int) error.varint();
                                else if ((e >>> 3) == 2 && (e & 7) == 0) type = (int) error.varint();
                                else error.skip((int) (e & 7));
                            }
                            out.tool = tool == 0 ? "gwp_asan" : tool == 1 ? "scudo" : "other";
                            out.memory = memoryType(type);
                        } else {
                            cause.skip((int) (k & 7));
                        }
                    }
                } else if (field == 16 && wire == 2) {
                    Reader entry = top.nested();
                    long id = -1;
                    Reader thread = null;
                    while (entry.more()) {
                        long k = entry.varint();
                        if ((k >>> 3) == 1 && (k & 7) == 0) id = entry.varint() & 0xffffffffL;
                        else if ((k >>> 3) == 2 && (k & 7) == 2) thread = entry.nested();
                        else entry.skip((int) (k & 7));
                    }
                    if (thread != null) threads.add(new long[]{id, thread.pos, thread.end});
                } else {
                    top.skip(wire);
                }
            }
        } catch (RuntimeException ignored) {
            // Truncated or malformed: keep what was read.
        }
        long[] crashing = null;
        for (long[] thread : threads) {
            if (thread[0] == tid) crashing = thread;
        }
        if (crashing == null && !threads.isEmpty()) crashing = threads.get(0);
        if (crashing != null) readFrames(new Reader(data, (int) crashing[1], (int) crashing[2]), out.frames);
        return out;
    }

    private static void readFrames(Reader thread, List<Frame> frames) {
        try {
            while (thread.more() && frames.size() < FRAMES) {
                long k = thread.varint();
                if ((k >>> 3) == 4 && (k & 7) == 2) {
                    Reader frame = thread.nested();
                    long pc = -1;
                    String function = null;
                    String file = null;
                    String build = null;
                    while (frame.more()) {
                        long f = frame.varint();
                        int field = (int) (f >>> 3);
                        int wire = (int) (f & 7);
                        if (field == 1 && wire == 0) pc = frame.varint();
                        else if (field == 4 && wire == 2) function = frame.string();
                        else if (field == 6 && wire == 2) file = frame.string();
                        else if (field == 8 && wire == 2) build = frame.string();
                        else frame.skip(wire);
                    }
                    if (function != null && function.isEmpty()) function = null;
                    frames.add(new Frame(pc, function, file, build));
                } else {
                    thread.skip((int) (k & 7));
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the frames read so far.
        }
    }

    static String memoryType(int type) {
        switch (type) {
            case 1: return "use_after_free";
            case 2: return "double_free";
            case 3: return "invalid_free";
            case 4: return "buffer_overflow";
            case 5: return "buffer_underflow";
            default: return "unknown";
        }
    }

    /**
     * The class of an abort message, a vocabulary word: what kind of abort it
     * was, never any of its text. {@code none} for no message.
     */
    static String abortClass(String message) {
        if (message == null || message.trim().isEmpty()) return "none";
        String lower = message.toLowerCase(java.util.Locale.US);
        if (lower.contains("jni detected error")) return "jni_error";
        if (lower.startsWith("fortify")) return "fortify";
        if (lower.contains("scudo error")) return "scudo";
        if (lower.contains("gwp-asan")) return "gwp_asan";
        if (lower.contains("check failed")) return "check_failed";
        if (lower.contains("out of memory")) return "out_of_memory";
        if (lower.contains("assert")) return "assertion";
        if (lower.contains("pthread_mutex")) return "mutex";
        return "other";
    }
}
