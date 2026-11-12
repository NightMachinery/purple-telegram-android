package org.telegram.messenger.purple;

import java.io.IOException;
import java.util.ArrayList;

public final class PurpleLongRoundDiagnostics {
    private PurpleLongRoundDiagnostics() { }

    private static void time(ArrayList<PurpleDiagLogStore.Field> fields, String name, long us) {
        final long bounded = us < 0 ? -1 : Math.min(us, 306_000_000L);
        fields.add(PurpleDiagLog.count(name + "_s", bounded < 0 ? -1 : (int) (bounded / 1_000_000)));
        fields.add(PurpleDiagLog.count(name + "_ms", bounded < 0 ? -1 : (int) (bounded / 1000 % 1000)));
        fields.add(PurpleDiagLog.count(name + "_us", bounded < 0 ? -1 : (int) (bounded % 1000)));
    }

    private static ArrayList<PurpleDiagLogStore.Field> fields(String stage, int part) {
        final ArrayList<PurpleDiagLogStore.Field> fields = new ArrayList<>();
        fields.add(PurpleDiagLog.code("step", stage));
        fields.add(PurpleDiagLog.count("part", part < 0 ? -1 : Math.min(part, 6)));
        return fields;
    }

    private static void emit(String event, ArrayList<PurpleDiagLogStore.Field> fields) {
        PurpleDiagLog.event("recordings", event, fields.toArray(new PurpleDiagLogStore.Field[0]));
    }

    public static void failure(String stage, int part, Exception failure) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields(stage, part);
            fields.add(PurpleDiagLog.reason("io_failed"));
            fields.add(PurpleDiagLog.code("exception", failure instanceof IOException ? "io"
                    : failure instanceof IllegalArgumentException ? "illegal_argument"
                    : failure instanceof IllegalStateException ? "illegal_state"
                    : failure instanceof SecurityException ? "path_refused" : "unexpected"));
            emit("long_prepare", fields);
        } catch (Throwable ignored) { }
    }

    static void reject(String stage, int part, String reason) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields(stage, part);
            fields.add(PurpleDiagLog.reason(reason));
            emit("long_reject", fields);
        } catch (Throwable ignored) { }
    }

    static void range(int part, long start, long end) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields("conversion", part);
            time(fields, "start", start); time(fields, "end", end);
            emit("long_range", fields);
        } catch (Throwable ignored) { }
    }

    static void work(boolean cancelled, boolean expired) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields("work_check", -1);
            fields.add(PurpleDiagLog.flag("cancelled", cancelled));
            fields.add(PurpleDiagLog.flag("expired", expired));
            fields.add(PurpleDiagLog.reason("cancelled"));
            emit("long_work", fields);
        } catch (Throwable ignored) { }
    }

    static void conversion(int part, boolean error) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields("conversion", part);
            fields.add(PurpleDiagLog.flag("error", error));
            emit("long_convert", fields);
        } catch (Throwable ignored) { }
    }

    static void metadata(int part, long durationMs, int width, int height, int rotation) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields("metadata", part);
            time(fields, "duration", durationMs < 0 ? -1 : Math.min(durationMs, 306_000L) * 1000);
            fields.add(PurpleDiagLog.count("width", width));
            fields.add(PurpleDiagLog.count("height", height));
            fields.add(PurpleDiagLog.count("rotation", rotation));
            emit("long_metadata", fields);
        } catch (Throwable ignored) { }
    }

    static void format(int part, int track, String kind, long durationUs) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields("track_format", part);
            fields.add(PurpleDiagLog.count("track", track < 0 ? -1 : Math.min(track, 6)));
            fields.add(PurpleDiagLog.code("kind", kind));
            time(fields, "duration", durationUs);
            emit("long_format", fields);
        } catch (Throwable ignored) { }
    }

    static void track(int part, int track, String kind, String reason, long durationMs,
            long first, long last, long current, int count, int sync, boolean monotonic) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields("track_scan", part);
            fields.add(PurpleDiagLog.count("track", track < 0 ? -1 : Math.min(track, 6)));
            fields.add(PurpleDiagLog.code("kind", kind));
            fields.add(PurpleDiagLog.reason(reason));
            time(fields, "duration", durationMs < 0 ? -1 : Math.min(durationMs, 306_000L) * 1000);
            time(fields, "first", first); time(fields, "last", last); time(fields, "current", current);
            fields.add(PurpleDiagLog.count("count", count));
            fields.add(PurpleDiagLog.count("sync", sync));
            fields.add(PurpleDiagLog.flag("monotonic", monotonic));
            emit("long_track", fields);
        } catch (Throwable ignored) { }
    }

    static void duration(String stage, int part, long actualMs, long expectedMs) {
        try {
            final ArrayList<PurpleDiagLogStore.Field> fields = fields(stage, part);
            time(fields, "duration", actualMs < 0 ? -1 : Math.min(actualMs, 306_000L) * 1000);
            time(fields, "expected", expectedMs < 0 ? -1 : Math.min(expectedMs, 306_000L) * 1000);
            emit("long_duration", fields);
        } catch (Throwable ignored) { }
    }
}
