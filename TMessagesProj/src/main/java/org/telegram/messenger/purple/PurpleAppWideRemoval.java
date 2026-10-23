package org.telegram.messenger.purple;

import android.os.Looper;
import org.telegram.messenger.NotificationCenter;
import java.io.File;
import java.util.Arrays;

public final class PurpleAppWideRemoval {
    public static final class Outcome {
        public final boolean ok;
        public final boolean backupChanged;
        public final boolean changed;
        Outcome(PurpleAppWideRemovalModel.Result result) {
            ok = result.ok; backupChanged = result.backupChanged; changed = result.changed;
        }
    }
    private static boolean applying;
    private static final class Context implements PurpleAppWideRemovalModel.Context {
        final PurpleGlobalAccountChoice.Session session;
        final int account;
        PurpleAccountRowWrite.Current confirmation;
        Context(PurpleGlobalAccountChoice.Session session, int account) { this.session = session; this.account = account; }
        public boolean current(boolean refreshed) {
            return PurpleAccountOwner.ready() && (confirmation == null || confirmation.current())
                    && session.currentAfterAccountRefresh(refreshed);
        }
        public void refresh() { PurpleAccountSettings.invalidate(); }
        public void notifyChanged() {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);
        }
    }
    public static final class Preview {
        public final int account;
        public final String accountKey;
        public final String globalKey;
        private final Context context;
        private final PurpleAppWideRemovalModel model;
        Preview(int account, String accountKey, Context context, PurpleAppWideRemovalModel model) {
            this.account = account; this.accountKey = accountKey; globalKey = context.session.global;
            this.context = context; this.model = model;
        }
        public int count() { return model.count; }
        public int groupCount() { return model.groups.length(); }
        public String groupLabel(int index) { return model.groups.optJSONObject(index).optString("label"); }
        public int groupSize(int index) { return model.groups.optJSONObject(index).optInt("count"); }
        public String path() { return model.target.getPath(); }
        public String backupPath() { return model.backup.getPath(); }
        public boolean current() { return model.current(); }
        public Outcome apply(PurpleAccountRowWrite.Current confirmation) {
            if (Looper.myLooper() != Looper.getMainLooper() || confirmation == null || !confirmation.current()) {
                return new Outcome(new PurpleAppWideRemovalModel.Result());
            }
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) {
                    synchronized (PurpleAccountSettings.class) {
                        if (applying) return new Outcome(new PurpleAppWideRemovalModel.Result());
                        applying = true;
                        context.confirmation = confirmation;
                        try { return new Outcome(model.apply()); }
                        finally { applying = false; }
                    }
                }
            }
        }
    }
    public static Preview preview(int account, PurpleAccountRowWrite.Current current) {
        if (applying || current == null || !current.current() || !PurpleAccountOwner.started()
                || PurpleGate.state() == null || !PurpleAccountOwner.ready()) return null;
        PurpleGlobalAccountChoice.Session session = new PurpleGlobalAccountChoice.Session(account, current::current);
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (session.owner == null || session.global == null || !session.current() || origin == null
                || !PurpleAppWideRemovalModel.eligible(origin.key, session.global)) return null;
        PurpleAccountFiles own = PurpleAccountFiles.resolve(origin.key, session.owner);
        PurpleAccountFiles global = PurpleAccountFiles.resolve(session.global, session.owner);
        if (own == null || global == null || own.settings.equals(global.settings)) return null;
        try {
            Context context = new Context(session, account);
            PurpleAppWideRemovalModel model = new PurpleAppWideRemovalModel(PurpleAccountFiles.root(), own.settings, own.backup,
                    session.owner, session.global, origin.key,
                    Arrays.asList(own.good, global.settings, global.good, PurpleAccountFiles.file("local.toml"),
                            PurpleAccountFiles.file("accounts/root.toml")), PurpleGlobalAccountChoice.PHYSICAL, context);
            return origin.current() && session.current() && model.current() ? new Preview(account, origin.key, context, model) : null;
        } catch (Exception | UnsatisfiedLinkError e) { return null; }
    }
    private PurpleAppWideRemoval() { }
}
