package org.telegram.messenger.purple;

import android.os.Looper;
import org.json.JSONArray;
import org.telegram.messenger.NotificationCenter;
import java.util.Arrays;

public final class PurpleAccountEditor {
    public interface Current { boolean current(); }
    public static final class Outcome {
        public final boolean ok, changed, backupChanged, directoryAttempted;
        public final int directoriesCreated;
        Outcome(PurpleAccountEditorModel.Result result) { ok = result.ok; changed = result.changed; backupChanged = result.backupChanged;
            directoryAttempted = result.directoryAttempted; directoriesCreated = result.directoriesCreated; }
    }
    private static volatile boolean writing;
    public static final class Preview {
        private final PurpleAccountEditorModel.Preview value;
        private final Session owner;
        Preview(Session owner, PurpleAccountEditorModel.Preview value) { this.owner = owner; this.value = value; }
        public String warnings() { return lines(value.facts.optJSONArray("warnings")); }
        public String inactiveNotes() {
            JSONArray groups = value.facts.optJSONArray("inactive");
            StringBuilder text = new StringBuilder();
            if (groups != null) for (int i = 0; i < groups.length(); ++i) {
                org.json.JSONObject group = groups.optJSONObject(i);
                if (group == null) continue;
                if (text.length() > 0) text.append("\n\n");
                text.append(group.optString("label")).append(":\n").append(lines(group.optJSONArray("keys")));
            }
            return text.toString();
        }
    }
    public static final class Session {
        public final String accountKey, globalKey, openedText, path;
        public final boolean global, missing, needsDirectory;
        private final PurpleAccountEditorModel model;
        private volatile boolean retired;
        private volatile Current confirmation;
        Session(PurpleGlobalAccountChoice.Session context, PurpleAccountFiles.Identity origin,
                String key, PurpleAccountFiles files, PurpleAccountFiles globalFiles) throws Exception {
            accountKey = key; globalKey = context.global; global = key.equals(globalKey);
            model = new PurpleAccountEditorModel(PurpleAccountFiles.root(), files.settings, files.backup,
                    context.owner, context.global, key, Arrays.asList(files.good, globalFiles.settings, globalFiles.good),
                    PurpleGlobalAccountChoice.PHYSICAL, new PurpleAccountEditorModel.Context() {
                        public boolean current(boolean refreshed) {
                            return !retired && (confirmation == null || confirmation.current()) && origin.current() && PurpleAccountOwner.ready()
                                    && context.currentAfterAccountRefresh(refreshed);
                        }
                        public void refresh() { PurpleAccountSettings.invalidate(); }
                        public void notifyChanged() {
                            NotificationCenter.getInstance(origin.slot).postNotificationName(NotificationCenter.dialogFiltersUpdated);
                        }
                    }, PurpleEditorDirectories.NATIVE);
            openedText = model.openedText; missing = model.missing; needsDirectory = model.needsDirectory; path = files.settings.getPath();
        }
        public boolean current() { return !retired && model.current(); }
        public void retire() { retired = true; }
        public PurpleCore.ParseResult parse(String text) {
            byte[] bytes = PurpleAccountEditorModel.encode(text);
            if (bytes == null || !current()) return null;
            try {
                PurpleCore.ParseResult answer = PurpleCore.parse(bytes);
                return current() ? answer : null;
            } catch (RuntimeException | UnsatisfiedLinkError e) { return null; }
        }
        public Preview preview(String text) {
            PurpleAccountEditorModel.Preview result = current() ? model.preview(text) : null;
            return result == null ? null : new Preview(this, result);
        }
        public Outcome save(Preview preview, Current confirmation) {
            if (Looper.myLooper() != Looper.getMainLooper() || preview == null || preview.owner != this || confirmation == null || !confirmation.current()) {
                return new Outcome(new PurpleAccountEditorModel.Result());
            }
            synchronized (PurpleAccountOwner.class) {
                synchronized (PurpleAccountFiles.class) {
                    synchronized (PurpleAccountSettings.class) {
                        if (writing || !current()) return new Outcome(new PurpleAccountEditorModel.Result());
                        writing = true; this.confirmation = confirmation;
                        try { return new Outcome(model.save(preview.value)); }
                        finally { writing = false; retired = true; this.confirmation = null; }
                    }
                }
            }
        }
    }
    public static Session open(int account, boolean appWide, Current current) {
        if (writing || current == null || !current.current()) return null;
        PurpleGlobalAccountChoice.Session context = PurpleGlobalAccountChoice.begin(account, current::current);
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (context == null || origin == null || !context.current()) return null;
        String key = appWide ? context.global : origin.key;
        PurpleAccountFiles own = PurpleAccountFiles.resolve(key, context.owner);
        PurpleAccountFiles global = PurpleAccountFiles.resolve(context.global, context.owner);
        if (own == null || global == null) return null;
        try {
            Session result = new Session(context, origin, key, own, global);
            return result.current() ? result : null;
        } catch (Exception | UnsatisfiedLinkError e) { return null; }
    }
    private static String lines(JSONArray array) {
        StringBuilder result = new StringBuilder();
        if (array != null) for (int i = 0; i < array.length(); ++i) {
            if (result.length() > 0) result.append("\n");
            result.append(array.optString(i));
        }
        return result.toString();
    }
    private PurpleAccountEditor() { }
}
