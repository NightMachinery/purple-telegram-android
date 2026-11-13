package org.telegram.messenger.purple;

import android.os.SystemClock;
import java.util.Random;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

public final class PurpleMediaTrace {
    public enum Step {
        CAPTURE, STARTED, PAUSE_REQUEST, PAUSE_ACK, RESUME_REQUEST, RESUME_ACK,
        BACKGROUND, FOREGROUND, CHAT_HIDDEN, CHAT_VISIBLE, DOCKED, EXPANDED, DISCARD_MODAL, DISCARD_CANCEL,
        DISCARD_REQUEST, STOP_REQUEST, DEADLINE, AUDIO_INPUT_EOS, AUDIO_OUTPUT_EOS,
        VIDEO_INPUT_EOS, VIDEO_OUTPUT_EOS, DRAIN_RETURNED, MUXER_COMPLETE,
        AUDIO_ENCODER_RELEASE_RETURNED, VIDEO_ENCODER_RELEASE_RETURNED, RELEASE_RETURNED, FAILURE, FLIP_REQUEST,
        FLIP_CONFIGURED, FLIP_FRAME_SUBMITTED, FLIP_FIRST_FRAME, RETAIN_REQUEST, RETAIN_SAVED, RETAIN_FAILED,
        RETRY, SPLIT_CREATED, SPLIT_CANCELLED, SPLIT_CLEANUP, STATE_STARTING,
        STATE_RECORDING, STATE_PAUSING, STATE_PREVIEWING, STATE_RESUMING,
        STATE_FINISHING, STATE_COMPLETED, STATE_ERROR, STATE_RELEASED
    }
    private static final Random RANDOM = new Random();
    private static final int PROCESS = RANDOM.nextInt();
    private static final LinkedHashMap<String, Take> takes = new LinkedHashMap<>();
    private static final ThreadLocal<Take> operation = new ThreadLocal<>();
    private PurpleMediaTrace() { }
    private static long now() { try { return SystemClock.elapsedRealtime(); } catch (Throwable ignored) { return 0; } }
    public static synchronized void foreground(boolean visible) {
        for (Take take : takes.values()) take.event(visible ? Step.FOREGROUND : Step.BACKGROUND);
    }
    public static synchronized void detach(int account, int guid) { takes.remove(account + ":" + guid); }

    public static final class Take {
        private final int id = RANDOM.nextInt();
        private final long started = now();
        private final String backend;
        private boolean retired;
        private long nextRetry, nextFailure;
        private Take(String backend) { this.backend = backend; }
        public synchronized void event(Step step) { event(step, true); }
        public synchronized void event(Step step, boolean ok) {
            try {
                if (retired) return;
                long now = now();
                if (step == Step.RETRY) { if (now < nextRetry) return; nextRetry = now + 30_000; }
                if (step == Step.RETAIN_FAILED) { if (now < nextFailure) return; nextFailure = now + 30_000; }
                ArrayList<PurpleDiagLogStore.Field> fields = fields();
                fields.add(PurpleDiagLog.code("step", step.name().toLowerCase(Locale.US)));
                fields.add(PurpleDiagLog.code("outcome", ok ? "ok" : "error"));
                PurpleDiagLog.event("recordings", "media_trace", fields.toArray(new PurpleDiagLogStore.Field[0]));
            } catch (Throwable ignored) { }
        }
        public synchronized void cap(long maximumMs) { cap(maximumMs, maximumMs); }
        public synchronized void cap(long maximumMs, long stopMs) {
            try {
                if (retired) return;
                ArrayList<PurpleDiagLogStore.Field> fields = fields();
                fields.add(PurpleDiagLog.count("cap_s", (int) Math.min(300, Math.max(0, maximumMs / 1000))));
                fields.add(PurpleDiagLog.count("stop_s", (int) Math.min(300, Math.max(0, stopMs / 1000))));
                fields.add(PurpleDiagLog.count("stop_ms", (int) (Math.max(0, stopMs) % 1000)));
                PurpleDiagLog.event("recordings", "capture_budget", fields.toArray(new PurpleDiagLogStore.Field[0]));
            } catch (Throwable ignored) { }
        }
        private ArrayList<PurpleDiagLogStore.Field> fields() {
            ArrayList<PurpleDiagLogStore.Field> fields = new ArrayList<>();
            fields.add(PurpleDiagLog.count("process_hi", PROCESS >>> 16));
            fields.add(PurpleDiagLog.count("process_lo", PROCESS & 65535));
            fields.add(PurpleDiagLog.count("take_hi", id >>> 16));
            fields.add(PurpleDiagLog.count("take_lo", id & 65535));
            fields.add(PurpleDiagLog.code("backend", backend));
            long elapsed = Math.max(0, now() - started);
            fields.add(PurpleDiagLog.count("elapsed_s", (int) Math.min(99999, elapsed / 1000)));
            fields.add(PurpleDiagLog.count("elapsed_ms", (int) (elapsed % 1000)));
            return fields;
        }
        public synchronized void retire() { retired = true; }
    }

    public static Take begin(int account, int guid, String backend, long capMs) { return begin(account, guid, backend, capMs, capMs); }
    public static synchronized Take begin(int account, int guid, String backend, long capMs, long stopMs) {
        Take take = operation(backend);
        Take old = takes.put(account + ":" + guid, take);
        if (old != null) old.retire();
        if (takes.size() > 32) takes.remove(takes.keySet().iterator().next()).retire();
        take.cap(capMs, stopMs);
        return take;
    }
    public static Take operation(String backend) { return new Take(backend); }
    public static synchronized Take current(int account, int guid) { return takes.get(account + ":" + guid); }
    public static void event(int account, int guid, Step step) {
        Take take = current(account, guid);
        if (take != null) take.event(step);
    }
    public static void v2(PurpleLongRoundOriginals.Owner owner, String state) {
        try { if (owner != null) { owner.trace.event(Step.valueOf("STATE_" + state)); if ("RELEASED".equals(state)) owner.trace.retire(); } }
        catch (Throwable ignored) { }
    }
    static Take enter(Take take) { Take previous = operation.get(); operation.set(take); return previous; }
    static void leave(Take previous) { if (previous == null) operation.remove(); else operation.set(previous); }
    static void decorate(ArrayList<PurpleDiagLogStore.Field> fields) {
        Take take = operation.get();
        if (take != null) fields.addAll(take.fields());
    }
}
