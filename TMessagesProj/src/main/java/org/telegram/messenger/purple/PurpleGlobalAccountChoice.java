package org.telegram.messenger.purple;

import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PurpleGlobalAccountChoice {
    public interface Current { boolean current(); }
    public static final class Outcome {
        public final boolean ok, changed, activated;
        Outcome(PurpleGlobalChoiceModel.Result result) { ok = result.ok; changed = result.changed; activated = result.activated; }
    }
    private static boolean applying;
    static final PurpleGlobalChoiceFiles.Physical PHYSICAL = new PurpleGlobalChoiceFiles.Physical() {
        public PurpleGlobalChoiceFiles.Mark mark(File file, boolean directory) throws Exception {
            try {
                StructStat s = Os.lstat(file.getPath());
                if (directory ? !OsConstants.S_ISDIR(s.st_mode) : !OsConstants.S_ISREG(s.st_mode) || s.st_nlink != 1) {
                    throw new IllegalStateException("unsafe choice path");
                }
                String key = s.st_dev + ":" + s.st_ino;
                return new PurpleGlobalChoiceFiles.Mark(key, directory ? "" : s.st_size + ":" + s.st_mtime + ":" + s.st_ctime + ":" + file.lastModified(), s.st_mode);
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.ENOENT) throw e;
                return new PurpleGlobalChoiceFiles.Mark("missing", "", 0);
            }
        }
        public boolean write(File temporary, PurpleGlobalChoiceFiles.Mark owned, byte[] bytes) {
            return PurpleGlobalChoiceFiles.writeNative(temporary.getPath(), owned.identity, bytes);
        }
    };
    private static List<PurpleGlobalChoiceModel.Account> roster() {
        List<PurpleGlobalChoiceModel.Account> result = new ArrayList<>();
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) result.add(new PurpleGlobalChoiceModel.Account(
                id.slot, id.key, id.lifetime, UserConfig.getInstance(id.slot), ConnectionsManager.getInstance(id.slot), AccountInstance.getInstance(id.slot)));
        return result;
    }
    public static final class Session {
        public final List<PurpleAccountFiles.Identity> accounts;
        public final String owner, global;
        private final PurpleAccountFiles.Identity origin;
        private final List<PurpleGlobalChoiceModel.Account> roster;
        private final Current ui;
        private final long revision, role, generation, nativeEpoch;
        private final int gate;
        private final File root;
        Session(int account, Current ui) {
            this.ui = ui;
            origin = PurpleAccountFiles.capture(account);
            accounts = PurpleAccountOwner.signedIn();
            roster = roster();
            owner = PurpleAccountOwner.owner(); global = PurpleAccountOwner.global();
            revision = PurpleAccountOwner.revision(); role = PurpleAccountOwner.roleEpoch();
            generation = PurpleAccountSettings.generation(); nativeEpoch = PurpleAccountSettings.epochNative();
            gate = PurpleGate.generation(); root = PurpleAccountFiles.root();
        }
        private boolean identityCurrent() {
            return ui.current() && origin != null && origin.current()
                    && root.equals(PurpleAccountFiles.root()) && PurpleGlobalChoiceModel.sameRoster(roster, roster());
        }
        public boolean current() { return currentAfterAccountRefresh(false); }
        boolean currentAfterAccountRefresh(boolean refreshed) {
            int steps = refreshed ? 1 : 0;
            return identityCurrent() && Objects.equals(owner, PurpleAccountOwner.owner()) && Objects.equals(global, PurpleAccountOwner.global())
                    && revision == PurpleAccountOwner.revision() && role == PurpleAccountOwner.roleEpoch()
                    && generation + steps == PurpleAccountSettings.generation() && nativeEpoch + steps == PurpleAccountSettings.epochNative()
                    && gate == PurpleGate.generation();
        }
        public Choice preview(String key, Current confirmation) {
            if (applying || !current()) return null;
            boolean eligible = global.equals(key);
            for (PurpleAccountFiles.Identity id : accounts) if (id.key.equals(key)) eligible = true;
            if (!eligible) return null;
            try {
                RuntimeContext context = new RuntimeContext(this, key, confirmation);
                PurpleGlobalChoiceModel model = new PurpleGlobalChoiceModel(root, owner, global, key, PHYSICAL, context);
                context.model = model;
                PurpleCore.Loaded running = PurpleGate.state();
                if (running == null || !running.preset.equalsIgnoreCase(model.after.optString("runningPreset"))) return null;
                return model.current() ? new Choice(model) : null;
            } catch (Exception | UnsatisfiedLinkError e) { return null; }
        }
    }
    private static final class RuntimeContext implements PurpleGlobalChoiceModel.Context {
        final Session session;
        final String selected;
        final Current confirmation;
        int normalSteps, activationSteps;
        PurpleGlobalChoiceModel model;
        final PurpleGlobalChoiceModel.Route route;
        RuntimeContext(Session session, String selected, Current confirmation) {
            this.session = session; this.selected = selected; this.confirmation = confirmation;
            route = new PurpleGlobalChoiceModel.Route(session.owner, session.global, selected, session.revision,
                    session.role, session.generation, session.nativeEpoch, session.gate);
        }
        public boolean current(boolean activated) {
            int steps = normalSteps + activationSteps;
            return confirmation.current() && session.identityCurrent() && PurpleAccountOwner.ready()
                    && route.current(activated, steps, PurpleAccountOwner.owner(), PurpleAccountOwner.global(),
                    PurpleAccountOwner.revision(), PurpleAccountOwner.roleEpoch(), PurpleAccountSettings.generation(),
                    PurpleAccountSettings.epochNative(), PurpleGate.generation());
        }
        public boolean normal() {
            if (!current(false)) return false;
            boolean ok = PurpleGate.setPreset(null);
            ++normalSteps;
            return ok && current(false) && PurpleGate.state() != null && PurpleGate.state().normal;
        }
        public boolean activate(PurpleGlobalChoiceModel.Result result) {
            if (!current(false)) return false;
            PurpleAccountOwner.reloadGlobal();
            result.activated = Objects.equals(selected, PurpleAccountOwner.global());
            if (!result.activated || !model.committedCurrent(result)) return false;
            PurpleGate.reloadGlobalChoice("app-wide account choice", model.activationSource());
            ++activationSteps;
            PurpleWatcher.rebind();
            return current(true);
        }
        public void notifyChanged() {
            NotificationCenter.getInstance(session.origin.slot).postNotificationName(NotificationCenter.dialogFiltersUpdated);
        }
    }
    public static final class Choice {
        private final PurpleGlobalChoiceModel model;
        Choice(PurpleGlobalChoiceModel model) { this.model = model; }
        public String selectedKey() { return model.selected; }
        public String before(String key) { return String.valueOf(model.before.opt(key)); }
        public String after(String key) { return String.valueOf(model.after.opt(key)); }
        public boolean flag(String key) { return model.after.optBoolean(key); }
        public String path() { return model.selectedFile.getPath(); }
        public String currentPath() { return model.currentFile.getPath(); }
        public String preset() { return model.after.optString("runningPreset"); }
        public boolean current() { return model.current(); }
        public Outcome apply() {
            if (Looper.myLooper() != Looper.getMainLooper()) return new Outcome(new PurpleGlobalChoiceModel.Result());
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) {
                    synchronized (PurpleAccountSettings.class) {
                        if (applying) return new Outcome(new PurpleGlobalChoiceModel.Result());
                        applying = true;
                        try { return new Outcome(model.apply()); }
                        finally { applying = false; }
                    }
                }
            }
        }
    }
    public static Session begin(int account, Current ui) { return begin(account, ui, null); }
    /**
     * As {@link #begin(int, Current)}, and when it refuses, {@code reason[0]}
     * names the check that did: a diagnostic code, never shown with an id.
     */
    static Session begin(int account, Current ui, String[] reason) {
        PurpleAccountOwner.ensureStarted(); PurpleGate.ensureLoaded();
        String refused = applying ? "choice_applying" : ui == null || !ui.current() ? "view_not_current"
                : !PurpleAccountOwner.ready() ? "owner_not_ready" : null;
        Session session = null;
        if (refused == null) {
            session = new Session(account, ui);
            refused = session.owner == null ? "owner_missing" : session.global == null ? "global_missing"
                    : PurpleGate.state() == null ? "gate_not_loaded" : !session.current() ? "choice_stale" : null;
        }
        if (reason != null && reason.length > 0) reason[0] = refused;
        return refused == null ? session : null;
    }
    private PurpleGlobalAccountChoice() { }
}
