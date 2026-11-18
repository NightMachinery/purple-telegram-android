package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;

final class PurpleGateLoad {
    enum Commit { Published, Refused, PersistedUnpublished }

    static final class Prepared implements AutoCloseable {
        final PurpleCore.Loaded loaded;
        private final byte[] state, replacement;
        private long token;

        private Prepared(JSONObject value, byte[] input, byte[] expected) throws Exception {
            state = expected == null ? null : expected.clone();
            token = Long.parseLong(value.getString("token"));
            if (token <= 0) throw new IllegalArgumentException("invalid reload token");
            loaded = PurpleCore.Loaded.fromJson(value.getString("loaded"));
            replacement = loaded == null ? null : loaded.stateText != null
                    ? loaded.stateText.getBytes(StandardCharsets.UTF_8)
                    : !Arrays.equals(input, expected) && input != null ? input.clone() : null;
        }

        synchronized Commit commit() { return commit(() -> true); }

        synchronized Commit commit(java.util.function.BooleanSupplier guard) {
            if (token <= 0 || loaded == null) return Commit.Refused;
            synchronized (PurpleState.class) {
                if (!current(guard)) return Commit.Refused;
                boolean saved = replacement != null;
                if (saved) {
                    if (!PurpleState.replaceIfUnchanged(state, replacement)) return Commit.Refused;
                } else if (state == null || !Arrays.equals(state, PurpleState.read())) return Commit.Refused;
                if (!current(guard))
                    return saved ? Commit.PersistedUnpublished : Commit.Refused;
                long publishing = token;
                token = 0;
                return PurpleGateLoadNative.publishNative(publishing) ? Commit.Published
                        : saved ? Commit.PersistedUnpublished : Commit.Refused;
            }
        }

        private boolean current(java.util.function.BooleanSupplier guard) {
            try { return guard.getAsBoolean() && PurpleGateLoadNative.currentNative(token); }
            catch (RuntimeException | LinkageError e) { return false; }
        }

        @Override public synchronized void close() {
            if (token <= 0) return;
            long discarded = token;
            token = 0;
            PurpleGateLoadNative.discardNative(discarded);
        }
    }

    static final class Batch implements AutoCloseable {
        private static final class Source {
            final Prepared prepared;
            final java.io.File file;
            final PurpleAccountFileSnapshot snapshot;
            Source(Prepared prepared, java.io.File file, PurpleAccountFileSnapshot snapshot) {
                this.prepared = prepared; this.file = file; this.snapshot = snapshot;
            }
        }
        private final java.util.ArrayList<Source> candidates = new java.util.ArrayList<>();
        private final boolean stopAll;
        private boolean published;
        private final PurplePeekForeground.Plan foreground = PurplePeekForeground.prepare();
        Batch() { this(false); }
        Batch(boolean stopAll) { this.stopAll = stopAll || foreground.stopAll; }
        PurpleCore.Loaded load(byte[] settings, byte[] state) {
            PurpleCore.ensureLoaded();
            java.io.File local = PurpleAccountFiles.file("local.toml");
            PurpleAccountFileSnapshot snapshot = PurpleAccountFileSnapshot.read(local);
            if (snapshot.status == PurpleAccountFileSnapshot.Status.ReadError) return null;
            String normalized = PurpleAccountPeekNative.normalizeNative(state, foreground.stoppedKeys, stopAll,
                    snapshot.bytes, PurpleAccountOwner.global());
            if (normalized == null || !snapshot.matches(local)) return null;
            Prepared result = prepare(settings, normalized.getBytes(StandardCharsets.UTF_8), state,
                    PurpleDevice.id(), PurpleDevice.PLATFORM, PurpleDevice.CLASS);
            if (result == null) return null;
            candidates.add(new Source(result, local, snapshot));
            return result.loaded;
        }
        Commit commit(PurpleCore.Loaded selected) { return commit(selected, () -> true); }
        Commit commit(PurpleCore.Loaded selected, java.util.function.BooleanSupplier guard) {
            for (Source candidate : candidates) if (candidate.prepared.loaded == selected) {
                return candidate.prepared.commit(() -> guard.getAsBoolean() && foreground.current()
                        && candidate.snapshot.matches(candidate.file));
            }
            return Commit.Refused;
        }
        boolean repairNeeded() {
            if (published) return false;
            if (foreground.stopAll) foreground.rejected();
            return stopAll || foreground.stoppedKeys.length != 0;
        }
        void published() { foreground.published(stopAll); published = true; }
        @Override public void close() {
            for (Source candidate : candidates) candidate.prepared.close();
            candidates.clear();
        }
    }

    static Prepared prepare(byte[] settings, byte[] state, String deviceId, String platform, String deviceClass) {
        return prepare(settings, state, state, deviceId, platform, deviceClass);
    }

    static Prepared prepare(byte[] settings, byte[] input, byte[] state, String deviceId, String platform, String deviceClass) {
        if (settings != null && settings.length > PurpleSettings.MAX_SIZE
                || input != null && input.length > PurpleState.MAX_SIZE || state != null && state.length > PurpleState.MAX_SIZE) return null;
        PurpleCore.ensureLoaded();
        String result = PurpleGateLoadNative.prepareNative(settings, input, deviceId, platform, deviceClass);
        if (result == null) return null;
        long token = 0;
        try {
            JSONObject value = new JSONObject(result);
            token = Long.parseLong(value.getString("token"));
            return new Prepared(value, input, state);
        } catch (Exception e) {
            if (token > 0) PurpleGateLoadNative.discardNative(token);
            return null;
        }
    }

    private PurpleGateLoad() { }
}
