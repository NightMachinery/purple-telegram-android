package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class PurpleAccountPeek {
    public static final class Clock {
        public final boolean peeking, untilStopped;
        public final long deadline;
        public final String ended;
        private final String context;

        private Clock(JSONObject object) throws Exception {
            peeking = object.getBoolean("peeking");
            untilStopped = object.getBoolean("untilStopped");
            deadline = Long.parseLong(object.getString("deadline"));
            ended = object.getString("ended");
            context = object.getString("context");
            if (deadline < 0) throw new IllegalArgumentException("invalid Peek deadline");
        }

        private boolean same(Clock other) {
            return other != null && peeking == other.peeking && untilStopped == other.untilStopped
                    && deadline == other.deadline && ended.equals(other.ended) && context.equals(other.context);
        }
    }

    public static final class Result {
        public final Clock clock;
        public final boolean changed, extended;
        public final int seconds, left;

        private Result(JSONObject object) throws Exception {
            clock = new Clock(object);
            changed = !object.isNull("text");
            extended = object.getBoolean("extended");
            seconds = object.getInt("seconds");
            left = object.getInt("left");
        }
    }

    public static final class Session {
        public final int account;
        public final String key;
        final PurpleAccountFiles.Identity identity;
        private final long role;
        private final int mode;
        private final String global;
        private final File[] files;
        private final PurpleAccountFileSnapshot[] snapshots;

        private Session(PurpleAccountFiles.Identity identity, PurpleWorkMode.View view,
                File[] files, PurpleAccountFileSnapshot[] snapshots) {
            this.identity = identity;
            account = identity.slot;
            key = identity.key;
            role = PurpleAccountOwner.observedRoleEpoch();
            mode = view.mode;
            global = PurpleAccountOwner.global();
            this.files = files;
            this.snapshots = snapshots;
        }

        private PurpleWorkMode.View currentView() {
            if (role != PurpleAccountOwner.observedRoleEpoch() || !identity.current()
                    || !java.util.Objects.equals(global, PurpleAccountOwner.global())) return null;
            PurpleWorkMode.View view = PurpleWorkMode.view(account);
            if (view == null || view.mode == 2 || view.mode != mode || view.handle <= 0
                    || view.identity.lifetime != identity.lifetime || !key.equals(view.identity.key)) return null;
            for (int i = 0; i < files.length; ++i) if (!snapshots[i].matches(files[i])) return null;
            return view;
        }

        public boolean matches(Clock expected) { return expected != null && expected.same(clock()); }

        public Result preview(Clock expected, int seconds, boolean extension) {
            if (expected == null || seconds < 0) return null;
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) {
                    try {
                        PurpleWorkMode.View view = currentView();
                        if (view == null) return null;
                        byte[] before = PurpleState.read();
                        JSONObject observed = invoke(before, view.handle, PurpleAccountPeekNative.READ, 0);
                        if (observed == null || !expected.same(new Clock(observed))) return null;
                        JSONObject made = invoke(before, view.handle, extension ? PurpleAccountPeekNative.EXTEND : PurpleAccountPeekNative.START, seconds);
                        return made == null ? null : new Result(made);
                    } catch (Exception | LinkageError e) { return null; }
                }
            }
        }

        public boolean current() {
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) { return currentView() != null; }
            }
        }

        private JSONObject invoke(byte[] state, long handle, int operation, int seconds) throws Exception {
            if (state == null) return null;
            String raw = PurpleAccountPeekNative.changeNative(state, handle, operation, seconds);
            if (raw == null) return null;
            JSONObject result = new JSONObject(raw);
            return result.optBoolean("refused", true) || !key.equals(result.optString("account")) ? null : result;
        }

        public Clock clock() {
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) {
                    try {
                        PurpleWorkMode.View view = currentView();
                        if (view == null) return null;
                        JSONObject result = invoke(PurpleState.read(), view.handle, PurpleAccountPeekNative.READ, 0);
                        return result == null ? null : new Clock(result);
                    } catch (Exception | LinkageError e) { return null; }
                }
            }
        }

        private Result change(Clock expected, int operation, int seconds) {
            if (expected == null || seconds < 0) return null;
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) {
                    try {
                        for (int attempt = 0; attempt < 3; ++attempt) {
                            PurpleWorkMode.View view = currentView();
                            if (view == null) return null;
                            byte[] before = PurpleState.read();
                            JSONObject observed = invoke(before, view.handle, PurpleAccountPeekNative.READ, 0);
                            if (observed == null || !expected.same(new Clock(observed))) return null;
                            JSONObject made = invoke(before, view.handle, operation, seconds);
                            if (made == null || currentView() != view) return null;
                            Result result = new Result(made);
                            if (!result.changed || PurpleState.compareAndWrite(before,
                                    made.getString("text").getBytes(StandardCharsets.UTF_8))) return result;
                        }
                    } catch (Exception | LinkageError e) { return null; }
                    return null;
                }
            }
        }

        public Result start(Clock expected, int seconds) { return change(expected, PurpleAccountPeekNative.START, seconds); }
        public Result extend(Clock expected, int seconds) { return change(expected, PurpleAccountPeekNative.EXTEND, seconds); }
        public Result stop(Clock expected) { return change(expected, PurpleAccountPeekNative.STOP, 0); }
        public Result locked(Clock expected, boolean screen) {
            return change(expected, screen ? PurpleAccountPeekNative.SCREEN_LOCK : PurpleAccountPeekNative.APP_LOCK, 0);
        }
    }

    static boolean stopWithdrawn(PurpleAccountFiles.Identity identity) {
        synchronized (PurpleAccountFiles.class) {
            try {
                PurpleWorkMode.View view = PurpleWorkMode.view(identity.slot);
                if (!identity.current() || view == null || view.handle <= 0 || view.clock.peeking
                        || view.identity.lifetime != identity.lifetime || !identity.key.equals(view.identity.key)) return false;
                byte[] before = PurpleState.read();
                if (before == null) return false;
                String raw = PurpleAccountPeekNative.changeNative(before, view.handle, PurpleAccountPeekNative.STOP, 0);
                if (raw == null) return false;
                JSONObject result = new JSONObject(raw);
                if (result.optBoolean("refused", true) || !identity.key.equals(result.optString("account"))) return false;
                return result.isNull("text") || PurpleState.replaceIfUnchanged(before,
                        result.getString("text").getBytes(StandardCharsets.UTF_8));
            } catch (Exception | LinkageError e) { return false; }
        }
    }

    public static Session capture(int account) {
        PurpleGate.ensureLoaded();
        synchronized (PurpleAccountOwner.class) {
            synchronized (PurpleAccountFiles.class) {
                PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(account);
                PurpleWorkMode.View view = PurpleWorkMode.view(account);
                if (identity == null || view == null || view.mode == 2 || view.handle <= 0
                        || view.identity.lifetime != identity.lifetime || !identity.key.equals(view.identity.key)) return null;
                PurpleAccountFiles own = PurpleAccountFiles.resolve(identity.key, PurpleAccountOwner.owner());
                if (own == null) return null;
                File global = PurpleAccountSettings.globalFile();
                File[] files = {global, new File(global.getPath() + ".good"), own.settings, own.good};
                PurpleAccountFileSnapshot[] snapshots = new PurpleAccountFileSnapshot[files.length];
                for (int i = 0; i < files.length; ++i) {
                    snapshots[i] = PurpleAccountFileSnapshot.read(files[i]);
                    if (snapshots[i].status == PurpleAccountFileSnapshot.Status.ReadError) return null;
                }
                Session result = new Session(identity, view, files, snapshots);
                return result.currentView() == view ? result : null;
            }
        }
    }

    private PurpleAccountPeek() { }
}
