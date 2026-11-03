package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Host cases for crash capture (docs/media-recording-design.md, F11) on the
 * AGP-compiled PurpleDiagLogStore, PurpleDiagRepeats, PurpleCrashRecord and
 * PurpleCrashTombstone: frame scrubbing (non-identifier characters, foreign
 * packages become other, lambda suffixes dropped, the line limit), native
 * frames and build ids, signal names, the Java crash record, tombstone
 * protobuf parsing from a synthetic tombstone (and every truncation of it),
 * ANR text parsing, the pending file and its merge with exit reasons, and the
 * repeat limit. Run by run_recording.py.
 */
public final class PurpleCrashCaptureCases {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }

    private static final String SECRET_PHONE = "+1 555 0100 2345";
    private static final String SECRET_PATH = "/data/user/0/org.telegram.messenger/files/secret.txt";
    private static final String SECRET_TEXT = "hello alice";

    /**
     * What every crash line must satisfy: bounded, printable ASCII, no path
     * separator, no secret, and a run of six or more digits only inside a pc
     * or a build id (open question 3: hex code offsets of the app's own
     * libraries).
     */
    private static void clean(String line, String what) {
        String problem = null;
        if (line.length() > PurpleDiagLogStore.LINE_LIMIT) problem = "over the line limit";
        if (line.contains(SECRET_TEXT) || line.contains("alice") || line.contains("555") || line.contains("secret")) {
            problem = "a secret";
        }
        for (String word : line.split(" ")) {
            boolean hex = word.startsWith("pc=0x") || word.startsWith("build_id=");
            int digits = 0;
            for (int i = 0; i < word.length(); ++i) {
                char c = word.charAt(i);
                if (c < 0x20 || c > 0x7e) problem = "not printable ASCII";
                if (c == '/' || c == '\\' || c == '@' || c == '"') problem = "a path, handle or quote";
                digits = c >= '0' && c <= '9' ? digits + 1 : 0;
                if (!hex && !word.startsWith("20") && digits >= 6) problem = "a long digit run in " + word;
            }
        }
        check(problem == null, what + ": " + problem + " in " + line);
    }

    private static String frameAt(String className, String method, int line) {
        return PurpleDiagLogStore.body("crash", "frame", PurpleDiagLogStore.frame(className, method, line));
    }

    private static void frames() {
        check(frameAt("org.telegram.ui.ChatActivity", "onPause", 30303)
                .equals("crash frame at=org.telegram.ui.ChatActivity.onPause line=30303"), "a plain frame");
        check(PurpleDiagLogStore.body("crash", "frame", PurpleDiagLogStore.count("n", 3),
                PurpleDiagLogStore.frame("org.telegram.ui.ChatActivity", "onPause", 30303))
                .equals("crash frame n=3 at=org.telegram.ui.ChatActivity.onPause line=30303"), "the design's layout");
        for (String pkg : new String[]{"org.telegram.x.A", "android.os.Looper", "androidx.core.B", "java.lang.Thread",
                "javax.crypto.Cipher", "kotlin.collections.C", "com.google.android.exoplayer2.D"}) {
            check(frameAt(pkg, "run", 1).equals("crash frame at=" + pkg + ".run line=1"), "kept package " + pkg);
        }
        for (String foreign : new String[]{"com.example.Tracker", "okhttp3.Call", "dalvik.system.VMStack",
                "org.telegramx.Fake", "androidxy.Z", "javafoo.Bar", "Main", "com.googlex.Y"}) {
            check(frameAt(foreign, "secretMethod", 7).equals("crash frame at=other line=7"), "foreign becomes other: " + foreign);
        }
        check(frameAt("org.telegram.ui.ChatActivity$$ExternalSyntheticLambda12", "run", 5)
                .equals("crash frame at=org.telegram.ui.ChatActivity.run line=5"), "D8 lambda class suffix dropped");
        check(frameAt("org.telegram.ui.-$$Lambda$ChatActivity", "run", 5)
                .equals("crash frame at=org.telegram.ui.ChatActivity.run line=5"), "old desugar lambda prefix dropped");
        check(frameAt("java.lang.Thread$$Lambda$12/0x0000000800c0b000", "run", 5)
                .equals("crash frame at=java.lang.Thread.run line=5"), "runtime lambda class suffix dropped");
        check(frameAt("org.telegram.ui.ChatActivity", "lambda$onPause$12", 9)
                .equals("crash frame at=org.telegram.ui.ChatActivity.lambda$onPause line=9"), "lambda method suffix dropped");
        check(frameAt("org.telegram.ui.ChatActivity", "access$000", 9)
                .equals("crash frame at=org.telegram.ui.ChatActivity.access line=9"), "accessor suffix dropped");
        check(frameAt("org.telegram.ui.ChatActivity$5", "<init>", 1)
                .equals("crash frame at=org.telegram.ui.ChatActivity$5.<init> line=1"), "constructors kept");
        check(frameAt("org.telegram.ui.ChatActivity", "<clinit>", -1)
                .equals("crash frame at=org.telegram.ui.ChatActivity.<clinit>"), "an unknown line is left out");
        for (String bad : new String[]{"org.telegram.ui.Chat Activity", "org.telegram.ui.Chät", "org.telegram.ui.A/B",
                "org.telegram..A", ".org.telegram.A", "org.telegram.A.", "", "org.telegram.\"x\""}) {
            check(frameAt(bad, "run", 1).equals("crash frame at=invalid line=1"), "non-identifier class is invalid: " + bad);
        }
        for (String bad : new String[]{"run now", "rün", "a.b", "", "x(1)", "<sneaky>"}) {
            check(frameAt("org.telegram.A", bad, 1).equals("crash frame at=invalid line=1"), "non-identifier method: " + bad);
        }
        check(frameAt(null, null, 1).equals("crash frame at=invalid line=1"), "null names");
        check(frameAt("org.telegram.A", "run", 123456789).endsWith("line=99999+"), "line clamped");
        check(frameAt("org.telegram.A", "run", 0).endsWith("line=0"), "line zero");
        StringBuilder huge = new StringBuilder("org.telegram");
        while (huge.length() < 300) huge.append(".abcdefghij");
        check(frameAt(huge.toString(), "run", 1).equals("crash frame at=invalid line=1"), "an over-long class is invalid");
        StringBuilder longMethod = new StringBuilder();
        while (longMethod.length() < 90) longMethod.append("m");
        check(frameAt("org.telegram.A", longMethod.toString(), 1).equals("crash frame at=invalid line=1"), "an over-long method");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.codeName("class", "java.lang.IllegalStateException"))
                .equals("crash start class=java.lang.IllegalStateException"), "an exception class");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.codeName("class", "com.vendor.SecretError"))
                .equals("crash start class=other"), "a foreign exception class");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.codeName("bogus_key", "java.lang.Error"))
                .equals("crash start invalid=java.lang.Error"), "a key outside the vocabulary");
    }

    private static String nativeAt(String library, String function, long pc) {
        return PurpleDiagLogStore.body("crash", "frame", PurpleDiagLogStore.nativeFrame(library, function, pc));
    }

    private static void nativeFrames() {
        String apk = "/data/app/~~Zx1=/org.telegram.messenger.web-Ab2=/lib/arm64/libtmessages.49.so";
        check(nativeAt(apk, "Java_org_telegram_messenger_Utilities_aesIgeEncryption", 0x1a2b3c)
                .equals("crash frame lib=libtmessages.49.so fn=Java_org_telegram_messenger_Utilities_aesIgeEncryption pc=0x1a2b3c"),
                "an app library keeps its base name and pc: " + nativeAt(apk, "x", 1));
        check(nativeAt("/data/app/x/base.apk!libtmessages.49.so", null, 0x10)
                .equals("crash frame lib=libtmessages.49.so pc=0x10"), "a library inside the APK");
        check(nativeAt("/data/app/x/lib/arm64/libpurplecore.so", "purple::Config::read", 0x20)
                .equals("crash frame lib=libpurplecore.so fn=purple::Config::read pc=0x20"), "libpurplecore");
        check(nativeAt("/data/app/x/lib/arm64/libQt6Core_arm64-v8a.so", "QString::fromUtf8(char const*, long)", 0x30)
                .equals("crash frame lib=libQt6Core_arm64-v8a.so fn=QString::fromUtf8 pc=0x30"), "Qt, parameters cut");
        check(nativeAt("/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 0x5000)
                .equals("crash frame lib=system fn=abort"), "a system library has no name and no pc");
        check(nativeAt("/data/app/x/lib/arm64/libsecretvendor.so", null, 0x5000).equals("crash frame lib=system"),
                "another app library is system");
        check(nativeAt("/data/app/x/lib/arm64/libtmessages.", null, 1).equals("crash frame lib=system"), "bare prefix");
        check(nativeAt("/data/app/x/libtmessages.49 .so", null, 1).equals("crash frame lib=system"), "bad characters");
        check(nativeAt(null, null, 1).equals("crash frame lib=system"), "no library");
        check(nativeAt(apk, "__epoll_pwait+8", 1).equals("crash frame lib=libtmessages.49.so fn=__epoll_pwait pc=0x1"),
                "an offset cut");
        check(nativeAt(apk, "std::__1::vector<int>::at", 1).equals("crash frame lib=libtmessages.49.so fn=invalid pc=0x1"),
                "template brackets are invalid");
        check(nativeAt(apk, "  ", 1).equals("crash frame lib=libtmessages.49.so pc=0x1"), "an empty function is left out");
        check(nativeAt(apk, "x", -1).equals("crash frame lib=libtmessages.49.so fn=x"), "an unknown pc is left out");
        StringBuilder longFn = new StringBuilder();
        while (longFn.length() < 400) longFn.append("abc_");
        String longLine = nativeAt(apk, longFn.toString(), Long.MAX_VALUE);
        check(longLine.contains(" fn=" + longFn.substring(0, PurpleDiagLogStore.FUNCTION_LIMIT) + " "), "function bounded");
        check(longLine.length() < 300, "a native frame stays short");

        check(PurpleDiagLogStore.body("crash", "library", PurpleDiagLogStore.library(apk, "ABCDEF0123456789abcdef0123456789abcdef01"))
                .equals("crash library lib=libtmessages.49.so build_id=abcdef0123456789abcdef0123456789abcdef01"),
                "a build id, lowercased");
        check(PurpleDiagLogStore.body("crash", "library", PurpleDiagLogStore.library(apk, "not hex!"))
                .equals("crash library lib=libtmessages.49.so build_id=?"), "a build id that is not hex");
        StringBuilder longId = new StringBuilder();
        while (longId.length() < 70) longId.append('a');
        check(PurpleDiagLogStore.library(apk, longId.toString()) != null
                && PurpleDiagLogStore.body("crash", "library", PurpleDiagLogStore.library(apk, longId.toString())).endsWith("build_id=?"),
                "an over-long build id");
        check(PurpleDiagLogStore.library(apk, null) != null, "no build id still names the library");
        check(PurpleDiagLogStore.library("/system/lib64/libc.so", "abcd") == null, "no build id line for system");
    }

    private static void signals() {
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.signal(11, 1))
                .equals("crash start signal=sigsegv sigcode=segv_maperr"), "SIGSEGV SEGV_MAPERR");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.signal(6, -6))
                .equals("crash start signal=sigabrt sigcode=si_tkill"), "SIGABRT SI_TKILL");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.signal(7, 2))
                .equals("crash start signal=sigbus sigcode=bus_adrerr"), "SIGBUS");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.signal(8, 1))
                .equals("crash start signal=sigfpe sigcode=fpe_intdiv"), "SIGFPE");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.signal(11, 9))
                .equals("crash start signal=sigsegv sigcode=segv_mteserr"), "MTE");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.signal(11, 6))
                .equals("crash start signal=sigsegv sigcode=other"), "a gap in the SEGV codes");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.signal(99, 12345))
                .equals("crash start signal=other sigcode=other"), "unknown signal");
        check(PurpleDiagLogStore.body("crash", "start", PurpleDiagLogStore.signal(31, 1))
                .equals("crash start signal=sigsys sigcode=sys_seccomp"), "seccomp");
        for (int signo = -2; signo < 40; ++signo) {
            for (int code = -10; code < 140; ++code) {
                String name = PurpleDiagLogStore.signalName(signo);
                String codeName = PurpleDiagLogStore.signalCode(signo, code);
                check(PurpleDiagLogStore.VOCABULARY.contains(name) && PurpleDiagLogStore.VOCABULARY.contains(codeName),
                        "signal words are vocabulary: " + name + " " + codeName);
            }
        }
        for (int reason = -1; reason < 20; ++reason) {
            check(PurpleDiagLogStore.VOCABULARY.contains(PurpleCrashRecord.reasonName(reason)), "reason words: " + reason);
        }
        check(PurpleCrashRecord.reasonName(5).equals("crash_native") && PurpleCrashRecord.reasonName(6).equals("anr")
                && PurpleCrashRecord.reasonName(3).equals("low_memory") && PurpleCrashRecord.reasonName(99).equals("other"),
                "reason names");
        for (int type = -1; type < 8; ++type) {
            check(PurpleDiagLogStore.VOCABULARY.contains(PurpleCrashTombstone.memoryType(type)), "memory words");
        }
        for (String state : new String[]{"Runnable", "Sleeping", "Waiting", "TimedWaiting", "Blocked", "Native",
                "Suspended", "WaitingForGcToComplete", "Starting", null}) {
            check(PurpleDiagLogStore.VOCABULARY.contains(PurpleCrashRecord.threadState(state)), "state words: " + state);
        }
        String[][] aborts = {{null, "none"}, {"", "none"}, {"JNI DETECTED ERROR IN APPLICATION: " + SECRET_TEXT, "jni_error"},
                {"FORTIFY: read: count > SSIZE_MAX", "fortify"}, {"Scudo ERROR: use after free", "scudo"},
                {"GWP-ASan detected", "gwp_asan"}, {"[FATAL:x.cc(12)] Check failed: " + SECRET_PATH, "check_failed"},
                {"Out of memory allocating", "out_of_memory"}, {"assertion \"x\" failed", "assertion"},
                {"FORTIFY pthread_mutex_lock called on a destroyed mutex", "fortify"},
                {"pthread_mutex_lock called on a destroyed mutex", "mutex"}, {SECRET_TEXT + " " + SECRET_PHONE, "other"}};
        for (String[] abort : aborts) {
            check(PurpleCrashTombstone.abortClass(abort[0]).equals(abort[1]), "abort class of " + abort[0]);
            check(PurpleDiagLogStore.VOCABULARY.contains(abort[1]), "abort words are vocabulary");
        }
    }

    // ---- Java crashes --------------------------------------------------------

    private static StackTraceElement[] trace(int count, String cls) {
        StackTraceElement[] out = new StackTraceElement[count];
        for (int i = 0; i < count; ++i) out[i] = new StackTraceElement(cls, "step" + i, "F.java", 100 + i);
        return out;
    }

    private static void javaCrash() {
        IllegalStateException error = new IllegalStateException(SECRET_TEXT + " " + SECRET_PHONE + " " + SECRET_PATH);
        StackTraceElement[] top = trace(20, "org.telegram.ui.ChatActivity");
        top[1] = new StackTraceElement("com.vendor.Secret", "leak", "S.java", 9);
        top[2] = new StackTraceElement("org.telegram.ui.ChatActivity$$ExternalSyntheticLambda3", "run", null, -2);
        error.setStackTrace(top);
        RuntimeException cause = new RuntimeException("cause: " + SECRET_TEXT);
        cause.setStackTrace(trace(10, "android.os.Handler"));
        error.initCause(cause);
        java.io.IOException root = new java.io.IOException(SECRET_PATH);
        root.setStackTrace(trace(2, "java.io.FileInputStream"));
        cause.initCause(root);
        List<String> record = PurpleCrashRecord.javaCrash(error, true);
        for (String line : record) clean(line, "java record");
        check(record.get(0).equals("crash start reason=crash class=java.lang.IllegalStateException main_thread=1"),
                "the start line: " + record.get(0));
        check(record.get(1).equals("crash frame n=1 at=org.telegram.ui.ChatActivity.step0 line=100"), "frame 1");
        check(record.get(2).equals("crash frame n=2 at=other line=9"), "a foreign frame");
        check(record.get(3).equals("crash frame n=3 at=org.telegram.ui.ChatActivity.run"), "native method: no line");
        check(record.get(12).equals("crash frame n=12 at=org.telegram.ui.ChatActivity.step11 line=111"), "frame 12");
        check(record.get(13).equals("crash cause depth=1 class=java.lang.RuntimeException"), "the cause");
        check(record.get(14).startsWith("crash frame n=1 at=android.os.Handler.step0") && record.get(16).startsWith("crash frame n=3 ")
                && record.get(17).equals("crash cause depth=2 class=java.io.IOException"), "three frames per cause");
        check(record.get(18).equals("crash frame n=1 at=java.io.FileInputStream.step0 line=100")
                && record.get(19).equals("crash frame n=2 at=java.io.FileInputStream.step1 line=101"), "a short cause");
        check(record.get(record.size() - 1).equals("crash end frames=17") && record.size() == 21, "the end line: " + record);

        // A cycle in the cause chain, a deep chain, an empty trace, a null error and a foreign class.
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        a.setStackTrace(new StackTraceElement[0]);
        b.setStackTrace(new StackTraceElement[0]);
        List<String> cycle = PurpleCrashRecord.javaCrash(a, false);
        check(cycle.equals(Arrays.asList("crash start reason=crash class=java.lang.Exception main_thread=0",
                "crash cause depth=1 class=java.lang.Exception", "crash end frames=0")), "a cycle ends: " + cycle);
        Throwable deep = new Error("0");
        for (int i = 0; i < 10; ++i) deep = new Error("x", deep);
        int causes = 0;
        for (String line : PurpleCrashRecord.javaCrash(deep, false)) if (line.startsWith("crash cause ")) ++causes;
        check(causes == PurpleCrashRecord.CAUSES, "causes bounded");
        check(PurpleCrashRecord.javaCrash(null, false).get(0).equals("crash start reason=crash class=invalid main_thread=0"),
                "a null error");
        class LocalSecretError extends RuntimeException {
        }
        check(PurpleCrashRecord.javaCrash(new LocalSecretError(), false).get(0).contains("class=org.telegram.messenger.purple."),
                "a nested class of a kept package");
    }

    // ---- Tombstones -----------------------------------------------------------

    private static final class Proto {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Proto varint(long value) {
            while ((value & ~0x7fL) != 0) {
                out.write((int) ((value & 0x7f) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
            return this;
        }

        Proto field(int number, int wire) {
            return varint((long) number << 3 | wire);
        }

        Proto uint(int number, long value) {
            return field(number, 0).varint(value);
        }

        Proto bytes(int number, byte[] value) {
            field(number, 2).varint(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        Proto string(int number, String value) {
            return bytes(number, value.getBytes(StandardCharsets.UTF_8));
        }

        Proto message(int number, Proto value) {
            return bytes(number, value.out.toByteArray());
        }

        Proto fixed64(int number, long value) {
            field(number, 1);
            for (int i = 0; i < 8; ++i) out.write((int) (value >>> (8 * i)));
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    private static Proto frame(long relPc, String function, String file, String buildId) {
        Proto frame = new Proto().uint(1, relPc).uint(2, 0x7f00000000L + relPc).uint(3, 0x7ffff000L);
        if (function != null) frame.string(4, function).uint(5, 40);
        frame.string(6, file).uint(7, 0);
        if (buildId != null) frame.string(8, buildId);
        return frame;
    }

    private static Proto thread(int id, String name, List<Proto> frames) {
        Proto thread = new Proto().uint(1, id).string(2, name)
                .message(3, new Proto().string(1, "x0").uint(2, 0x1234567890L));
        for (Proto frame : frames) thread.message(4, frame);
        thread.message(5, new Proto().string(1, "x0").bytes(4, new byte[64]));
        return thread;
    }

    static final String TM = "/data/app/~~Zx1=/org.telegram.messenger.web-Ab2=/lib/arm64/libtmessages.49.so";
    static final String TM_ID = "0123456789abcdef0123456789abcdef01234567";

    static byte[] tombstone() {
        List<Proto> other = new ArrayList<>();
        other.add(frame(0x100, "other_thread_function", "/system/lib64/libc.so", "ffff"));
        List<Proto> crashing = new ArrayList<>();
        crashing.add(frame(0x4f3c4, "abort", "/apex/com.android.runtime/lib64/bionic/libc.so", "aaaa"));
        crashing.add(frame(0x1a2b3c, "Java_org_telegram_messenger_Utilities_aesIgeEncryption", TM, TM_ID));
        crashing.add(frame(0x2b3c4d, null, TM, TM_ID));
        crashing.add(frame(0x10, "purple::Config::read(std::string const&)", "/data/app/x/base.apk!libpurplecore.so", "bbbbcccc"));
        for (int i = 0; i < 12; ++i) crashing.add(frame(0x1000 + i, "art_quick_invoke_stub", "/apex/com.android.art/lib64/libart.so", "dddd"));
        Proto top = new Proto()
                .uint(1, 1)
                .string(2, "google/device/" + SECRET_TEXT + ":14/AP1A/123456:user/release-keys")
                .string(4, "2026-11-03 10:00:00.123456789+0000")
                .uint(5, 12345).uint(6, 12345).uint(7, 10123)
                .string(8, "u:r:untrusted_app:s0:c512,c768")
                .string(9, "org.telegram.messenger").string(9, SECRET_PATH)
                .message(10, new Proto().uint(1, 11).string(2, "SIGSEGV").uint(3, 1).string(4, "SEGV_MAPERR")
                        .uint(8, 1).uint(9, 0xdeadbeefL))
                .string(14, "JNI DETECTED ERROR IN APPLICATION: " + SECRET_TEXT + " " + SECRET_PHONE)
                .message(15, new Proto().string(1, "[GWP-ASan]: Use After Free " + SECRET_PATH)
                        .message(2, new Proto().uint(1, 1).uint(2, 1).message(3, new Proto().uint(1, 0x1000).uint(2, 32))))
                .message(16, new Proto().uint(1, 12399).message(2, thread(12399, "other", other)))
                .message(16, new Proto().uint(1, 12345).message(2, thread(12345, SECRET_TEXT, crashing)))
                .message(17, new Proto().uint(1, 0x7000).uint(2, 0x8000).string(7, SECRET_PATH))
                .message(18, new Proto().string(1, "main").message(2, new Proto().string(7, SECRET_TEXT + SECRET_PHONE)))
                .fixed64(99, 42L)
                .field(98, 5).varint(0);
        // field 98 is fixed32: four bytes, of which varint(0) wrote one; pad the rest.
        top.out.write(0);
        top.out.write(0);
        top.out.write(0);
        return top.toByteArray();
    }

    private static PurpleCrashRecord.Exit exit(long time, int reason, byte[] trace) {
        return new PurpleCrashRecord.Exit(time, reason, reason == 5 ? 11 : 0, 100, true, 150 * 1024, 300 * 1024 + 512,
                trace, trace == null ? 0 : trace.length);
    }

    private static void tombstones() {
        byte[] data = tombstone();
        PurpleCrashTombstone parsed = PurpleCrashTombstone.parse(data, data.length);
        check(parsed.signal == 11 && parsed.code == 1, "signal and code");
        check(parsed.abort.equals("jni_error"), "abort class");
        check("scudo".equals(parsed.tool) && "use_after_free".equals(parsed.memory), "memory error");
        check(parsed.frames.size() == PurpleCrashTombstone.FRAMES, "frames bounded: " + parsed.frames.size());
        check(parsed.frames.get(0).function.equals("abort") && parsed.frames.get(1).relativePc == 0x1a2b3c
                && parsed.frames.get(2).function == null && TM_ID.equals(parsed.frames.get(1).buildId),
                "the crashing thread's frames, not the other thread's");

        List<String> record = PurpleCrashRecord.nativeCrash(exit(1792886400000L, 5, data));
        for (String line : record) clean(line, "native record");
        List<String> expected = new ArrayList<>(Arrays.asList(
                "crash start reason=crash_native signal=sigsegv sigcode=segv_maperr abort=jni_error tool=scudo memory=use_after_free",
                "crash exit reason=crash_native status=11 importance=100 main=1 pss_mb=150 rss_mb=300",
                "crash library lib=libtmessages.49.so build_id=" + TM_ID,
                "crash library lib=libpurplecore.so build_id=bbbbcccc",
                "crash frame n=1 lib=system fn=abort",
                "crash frame n=2 lib=libtmessages.49.so fn=Java_org_telegram_messenger_Utilities_aesIgeEncryption pc=0x1a2b3c",
                "crash frame n=3 lib=libtmessages.49.so pc=0x2b3c4d",
                "crash frame n=4 lib=libpurplecore.so fn=purple::Config::read pc=0x10"));
        for (int i = 5; i <= 12; ++i) expected.add("crash frame n=" + i + " lib=system fn=art_quick_invoke_stub");
        expected.add("crash end frames=12");
        check(record.equals(expected), "the native record: " + record);

        // Every truncation parses without throwing and keeps only whole frames.
        for (int cut = 0; cut <= data.length; ++cut) {
            PurpleCrashTombstone partial = PurpleCrashTombstone.parse(data, cut);
            check(partial.frames.size() <= PurpleCrashTombstone.FRAMES, "truncated at " + cut);
            for (String line : PurpleCrashRecord.nativeCrash(exit(1L, 5, Arrays.copyOf(data, cut)))) clean(line, "truncated");
        }
        // Damage: a corrupt length, a bad wire type, and random bytes.
        byte[] damaged = data.clone();
        damaged[damaged.length / 2] = (byte) 0xff;
        PurpleCrashTombstone.parse(damaged, damaged.length);
        check(PurpleCrashTombstone.parse(new byte[]{(byte) 0x0f, 1, 2}, 3).frames.isEmpty(), "bad wire type");
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 300; ++i) {
            byte[] noise = new byte[random.nextInt(200)];
            random.nextBytes(noise);
            PurpleCrashTombstone noisy = PurpleCrashTombstone.parse(noise, noise.length);
            for (String line : PurpleCrashRecord.nativeCrash(exit(1L, 5, noise))) clean(line, "noise");
            check(noisy.frames.size() <= PurpleCrashTombstone.FRAMES, "noise is bounded");
        }
        check(PurpleCrashTombstone.parse(null, 0).frames.isEmpty(), "no trace");
        List<String> bare = PurpleCrashRecord.nativeCrash(exit(1L, 5, null));
        check(bare.equals(Arrays.asList("crash start reason=crash_native abort=none",
                "crash exit reason=crash_native status=11 importance=100 main=1 pss_mb=150 rss_mb=300", "crash end frames=0")),
                "a native crash without a tombstone: " + bare);
        // A negative signal code is a 10-byte varint.
        byte[] tkill = new Proto().uint(6, 1).message(10, new Proto().uint(1, 6).uint(3, -6L)).toByteArray();
        PurpleCrashTombstone abort = PurpleCrashTombstone.parse(tkill, tkill.length);
        check(abort.signal == 6 && abort.code == -6, "negative code");
        // No crashing thread id: the first thread.
        byte[] noTid = new Proto().message(16, new Proto().uint(1, 3).message(2,
                thread(3, "t", Collections.singletonList(frame(1, "f", TM, null))))).toByteArray();
        check(PurpleCrashTombstone.parse(noTid, noTid.length).frames.size() == 1, "first thread when tid is missing");
    }

    // ---- ANRs ---------------------------------------------------------------

    static String anrTrace() {
        return "----- pid 12345 at 2026-11-03 10:00:00.000000000+0000 -----\n"
                + "Cmd line: org.telegram.messenger\n"
                + "Build fingerprint: 'google/device/" + SECRET_TEXT + "'\n"
                + "\n"
                + "\"Signal Catcher\" daemon prio=10 tid=7 Runnable\n"
                + "  at org.telegram.ui.Wrong.thread(Wrong.java:1)\n"
                + "\n"
                + "\"main\" prio=5 tid=1 Blocked\n"
                + "  | group=\"main\" sCount=1 ucsCount=0 flags=1 obj=0x72a3d000 self=0xb400007a1234\n"
                + "  | sysTid=12345 nice=-10 cgrp=top-app sched=0/0 handle=0x7b12345678\n"
                + "  | stack=0x7ff0000000-0x7ff0002000 stackSize=8188KB\n"
                + "  native: #00 pc 00000000000a1234  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28) (BuildId: abcabc)\n"
                + "  native: #01 pc 00000000001b2c3d  " + TM + " (offset 0x1000) (Java_org_telegram_tgnet_ConnectionsManager_native_1x+40) (BuildId: " + TM_ID + ")\n"
                + "  at org.telegram.messenger.MessagesStorage.getDialogs(MessagesStorage.java:12345)\n"
                + "  - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 42\n"
                + "  at org.telegram.messenger.MessagesStorage.lambda$loadDialogs$7(MessagesStorage.java:999)\n"
                + "  at org.telegram.messenger.MessagesStorage$$ExternalSyntheticLambda1.run(D8$$SyntheticClass:0)\n"
                + "  at com.vendor.Tracker.track(Tracker.java:5)\n"
                + "  - locked <0x0f0f0f0f> (a " + SECRET_TEXT + ")\n"
                + "  at android.os.Handler.handleCallback(Handler.java:958)\n"
                + "  at android.os.Looper.loop(Looper.java:294)\n"
                + "  at android.app.ActivityThread.main(ActivityThread.java:8177)\n"
                + "  at java.lang.reflect.Method.invoke(Native method)\n"
                + "  at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:552)\n"
                + "  at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:971)\n"
                + "  at extra.Frame.thirteen(X.java:13)\n"
                + "\n"
                + "\"Thread-2\" prio=5 tid=12 Native\n"
                + "  at org.telegram.ui.AfterMain.run(AfterMain.java:1)\n";
    }

    private static void anrs() {
        byte[] data = anrTrace().getBytes(StandardCharsets.UTF_8);
        List<String> record = PurpleCrashRecord.anr(exit(1792886400000L, 6, data));
        for (String line : record) clean(line, "anr record");
        List<String> expected = Arrays.asList(
                "crash start reason=anr state=blocked",
                "crash exit reason=anr status=0 importance=100 main=1 pss_mb=150 rss_mb=300",
                "crash library lib=libtmessages.49.so build_id=" + TM_ID,
                "crash frame n=1 lib=system fn=syscall",
                "crash frame n=2 lib=libtmessages.49.so fn=Java_org_telegram_tgnet_ConnectionsManager_native_1x pc=0x1b2c3d",
                "crash frame n=3 at=org.telegram.messenger.MessagesStorage.getDialogs line=12345",
                "crash frame n=4 at=org.telegram.messenger.MessagesStorage.lambda$loadDialogs line=999",
                "crash frame n=5 at=org.telegram.messenger.MessagesStorage.run line=0",
                "crash frame n=6 at=other line=5",
                "crash frame n=7 at=android.os.Handler.handleCallback line=958",
                "crash frame n=8 at=android.os.Looper.loop line=294",
                "crash frame n=9 at=android.app.ActivityThread.main line=8177",
                "crash frame n=10 at=java.lang.reflect.Method.invoke",
                "crash frame n=11 at=other line=552",
                "crash frame n=12 at=other line=971",
                "crash end frames=12");
        check(record.equals(expected), "the ANR record: " + record);
        for (String line : record) check(!line.contains("Wrong") && !line.contains("AfterMain") && !line.contains("0x0a1b"),
                "only the main thread, no lock lines");
        List<String> none = PurpleCrashRecord.anr(exit(1L, 6, "no threads at all\n".getBytes(StandardCharsets.UTF_8)));
        check(none.equals(Arrays.asList("crash start reason=anr",
                "crash exit reason=anr status=0 importance=100 main=1 pss_mb=150 rss_mb=300", "crash end frames=0")),
                "an ANR trace without a main thread: " + none);
        check(PurpleCrashRecord.anr(exit(1L, 6, null)).size() == 3, "an ANR without a trace");
        check(PurpleCrashRecord.javaTraceFrame("broken", 1).equals("crash frame n=1 at=invalid"), "a broken at line");
        check(PurpleCrashRecord.javaTraceFrame("a.b.C.d(C.java:x)", 2).equals("crash frame n=2 at=other"), "a bad line number");
        check(PurpleCrashRecord.nativeTraceFrame("native: #00 pc", 1, new java.util.LinkedHashMap<>()) == null, "a short native line");
        check(PurpleCrashRecord.hex("00000000000a1234") == 0xa1234 && PurpleCrashRecord.hex("zz") == -1
                && PurpleCrashRecord.hex("") == -1 && PurpleCrashRecord.hex("1234567890abcdef1") == -1, "hex");
        String gc = "\"main\" prio=5 tid=1 WaitingForGcToComplete\n  at a.B.c(B.java:1)\n";
        check(PurpleCrashRecord.anr(exit(1L, 6, gc.getBytes(StandardCharsets.UTF_8))).get(0)
                .equals("crash start reason=anr state=gc"), "a GC state");
    }

    // ---- The pending file and the merge ---------------------------------------

    private static void pending(File root) throws Exception {
        File file = new File(new File(root, "purple-diagnostics"), PurpleCrashRecord.PENDING);
        check(PurpleCrashRecord.readPending(file).isEmpty(), "no pending file");
        IllegalStateException error = new IllegalStateException(SECRET_TEXT);
        error.setStackTrace(trace(3, "org.telegram.ui.ChatActivity"));
        List<String> first = PurpleCrashRecord.javaCrash(error, true);
        check(PurpleCrashRecord.writePending(file, 1792886400000L, first), "the first record is written");
        List<String> second = PurpleCrashRecord.javaCrash(new NullPointerException(SECRET_PHONE), false);
        check(PurpleCrashRecord.writePending(file, 1792886500000L, second), "the second record is appended");
        List<PurpleCrashRecord.Pending> read = PurpleCrashRecord.readPending(file);
        check(read.size() == 2 && read.get(0).time == 1792886400000L && read.get(0).bodies.equals(first)
                && read.get(1).bodies.equals(second), "both records read back");
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        check(!text.contains(SECRET_TEXT) && !text.contains("555"), "the pending file holds no message");

        // Damage: foreign lines, non-ASCII, a record without a start, a bad time, an over-long line.
        StringBuilder longLine = new StringBuilder("crash frame ");
        while (longLine.length() < 600) longLine.append('x');
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(("garbage " + SECRET_TEXT + "\ncrash frame n=1 at=ä\n" + longLine + "\nrecord 12x\ncrash start reason=crash\n"
                    + "record 1792886600000\ncrash frame n=1 at=other\ncrash end frames=1\n"
                    + "record 1792886700000\ncrash start reason=crash class=other main_thread=0\n" + SECRET_PATH + "\ncrash end frames=0\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        read = PurpleCrashRecord.readPending(file);
        check(read.size() == 3, "damaged records skipped: " + read.size());
        check(read.get(1).bodies.equals(second), "lines after the second record that are not crash bodies are dropped: "
                + read.get(1).bodies);
        check(read.get(2).time == 1792886700000L && read.get(2).bodies.equals(Arrays.asList(
                "crash start reason=crash class=other main_thread=0", "crash end frames=0")), "the path line is dropped");
        check(!PurpleCrashRecord.pendingBody("crash " + SECRET_TEXT + "é") && !PurpleCrashRecord.pendingBody("diag x")
                && !PurpleCrashRecord.pendingBody(null) && PurpleCrashRecord.pendingBody("crash end frames=0"), "pending bodies");

        // The size bound.
        File full = new File(root, "full/" + PurpleCrashRecord.PENDING);
        List<String> big = new ArrayList<>();
        for (int i = 0; i < 40; ++i) big.add(PurpleDiagLogStore.body("crash", "frame", PurpleDiagLogStore.count("n", i),
                PurpleDiagLogStore.frame("org.telegram.ui.ChatActivityEnterView", "onTouchEventAndSomeMore", 12345)));
        int written = 0;
        for (int i = 0; i < 200; ++i) if (PurpleCrashRecord.writePending(full, i, big)) ++written;
        check(full.length() <= PurpleCrashRecord.PENDING_LIMIT && written > 10 && written < 200, "the pending file is bounded");
        check(!PurpleCrashRecord.writePending(new File(full, "under-a-file/x"), 1, first), "an impossible path fails quietly");
    }

    private static void merge() {
        long at = 1792886400000L; // 2026-10-25T00:00:00Z
        IllegalStateException error = new IllegalStateException(SECRET_TEXT);
        error.setStackTrace(trace(2, "org.telegram.ui.ChatActivity"));
        PurpleCrashRecord.Pending java = new PurpleCrashRecord.Pending(at + 2000, PurpleCrashRecord.javaCrash(error, true));
        IllegalArgumentException bare = new IllegalArgumentException();
        bare.setStackTrace(new StackTraceElement[0]);
        PurpleCrashRecord.Pending lonely = new PurpleCrashRecord.Pending(at + 600_000, PurpleCrashRecord.javaCrash(bare, false));
        List<PurpleCrashRecord.Exit> exits = Arrays.asList(
                exit(at + 900_000, 3, null),
                new PurpleCrashRecord.Exit(at + 5000, 4, 0, 100, true, 200 * 1024, 400 * 1024, null, 0),
                exit(at + 300_000, 4, null),
                exit(at + 1_200_000, 6, anrTrace().getBytes(StandardCharsets.UTF_8)),
                new PurpleCrashRecord.Exit(at + 1_500_000, 10, -1, 400, false, -1, 123456789L * 1024, null, 0));
        List<String> lines = PurpleCrashRecord.plan(exits, Arrays.asList(lonely, java), at + 2_000_000);
        for (String line : lines) clean(line, "plan");
        check(lines.get(0).equals("2026-10-25T00:00:02Z crash start reason=crash class=java.lang.IllegalStateException main_thread=1"),
                "the matched Java crash keeps its own time: " + lines.get(0));
        check(lines.get(1).equals("2026-10-25T00:00:02Z crash exit reason=crash status=0 importance=100 main=1 pss_mb=200 rss_mb=400"),
                "its exit line joins the record: " + lines.get(1));
        check(lines.get(2).startsWith("2026-10-25T00:00:02Z crash frame n=1 ") && lines.get(4).equals("2026-10-25T00:00:02Z crash end frames=2"),
                "then its frames");
        check(lines.get(5).equals("2026-10-25T00:05:00Z crash exit reason=crash status=0 importance=100 main=1 pss_mb=150 rss_mb=300"),
                "an unmatched crash exit is one line: " + lines.get(5));
        check(lines.get(6).equals("2026-10-25T00:10:00Z crash start reason=crash class=java.lang.IllegalArgumentException main_thread=0")
                && lines.get(7).equals("2026-10-25T00:10:00Z crash end frames=0"), "an unmatched Java crash is its own record");
        check(lines.get(8).equals("2026-10-25T00:15:00Z crash exit reason=low_memory status=0 importance=100 main=1 pss_mb=150 rss_mb=300"),
                "low memory is one line");
        check(lines.get(9).equals("2026-10-25T00:20:00Z crash start reason=anr state=blocked"), "the ANR record");
        String last = lines.get(lines.size() - 1);
        check(last.equals("2026-10-25T00:25:00Z crash exit reason=user_requested status=neg importance=400 main=0 pss_mb=neg rss_mb=99999+"),
                "a user request, with clamped counts: " + last);
        check(lines.size() == 9 + 16 + 1, "every line once: " + lines.size());

        // A crash 11 s away from the Java record does not join it.
        List<String> apart = PurpleCrashRecord.plan(Collections.singletonList(exit(at + 2000 + PurpleCrashRecord.MATCH + 1, 4, null)),
                Collections.singletonList(java), at);
        check(apart.size() == 1 + java.bodies.size() && apart.get(1).contains("crash frame"), "too far apart to match");
        // Two Java records and one exit: the closer one matches.
        PurpleCrashRecord.Pending near = new PurpleCrashRecord.Pending(at + 4000, PurpleCrashRecord.javaCrash(new Error(), true));
        List<String> closer = PurpleCrashRecord.plan(Collections.singletonList(exit(at + 5000, 4, null)), Arrays.asList(java, near), at);
        int exitAt = -1;
        for (int i = 0; i < closer.size(); ++i) if (closer.get(i).contains(" crash exit ")) exitAt = i;
        check(exitAt > 0 && closer.get(exitAt - 1).contains("class=java.lang.Error"), "the closest Java record matches");

        // The repeat limit: at most five records per start, then one count.
        List<PurpleCrashRecord.Exit> loop = new ArrayList<>();
        byte[] data = tombstone();
        for (int i = 0; i < 8; ++i) loop.add(exit(at + i * 1000L, 5, data));
        for (int i = 0; i < 3; ++i) loop.add(exit(at + 100_000 + i, 3, null));
        List<String> limited = PurpleCrashRecord.plan(loop, Collections.singletonList(lonely), at + 999_000);
        int starts = 0;
        int exitsOnly = 0;
        for (String line : limited) {
            clean(line, "limited");
            if (line.contains(" crash start ")) ++starts;
            if (line.contains(" crash exit reason=low_memory")) ++exitsOnly;
        }
        check(starts == PurpleCrashRecord.RECORDS, "five records: " + starts);
        check(exitsOnly == 3, "exit lines are not limited");
        check(limited.get(limited.size() - 1).equals("2026-10-25T00:16:39Z crash repeated count=4"),
                "the rest are counted: " + limited.get(limited.size() - 1));
        check(PurpleCrashRecord.plan(Collections.<PurpleCrashRecord.Exit>emptyList(),
                Collections.<PurpleCrashRecord.Pending>emptyList(), at).isEmpty(), "nothing to write");
        List<String> five = PurpleCrashRecord.plan(loop.subList(0, 5), Collections.<PurpleCrashRecord.Pending>emptyList(), at);
        for (String line : five) check(!line.contains("repeated"), "five records need no count");
    }

    private static void origins(File expected) throws Exception {
        for (String name : new String[]{"PurpleDiagLogStore", "PurpleDiagRepeats", "PurpleCrashRecord", "PurpleCrashTombstone"}) {
            File origin = new File(Class.forName("org.telegram.messenger.purple." + name).getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getCanonicalFile();
            check(origin.equals(expected.getCanonicalFile()), "business class shadow: " + name + " from " + origin);
            System.out.println(name + " origin=" + origin);
        }
    }

    public static void main(String[] args) throws Exception {
        checks = 0;
        origins(new File(args[0]));
        File root = new File(args[1]);
        if (!root.mkdir()) throw new AssertionError("exclusive fixture");
        frames();
        nativeFrames();
        signals();
        javaCrash();
        tombstones();
        anrs();
        pending(root);
        merge();
        System.out.println("CRASH_CAPTURE_CHECKS=" + checks);
    }
}
