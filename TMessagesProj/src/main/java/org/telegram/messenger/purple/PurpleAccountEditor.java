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
        public boolean dangerous() { return value.facts.optJSONObject("impact") != null
                && value.facts.optJSONObject("impact").optBoolean("dangerous"); }
        public String impactNotes() { return PurpleConfigImpactText.describe(value.facts.optJSONObject("impact"), owner.global, owner.accountKey, owner.globalKey); }
        public String warnings() {
            String warnings = lines(value.facts.optJSONArray("warnings"));
            if (value.facts.optBoolean("followMembers")) warnings += (warnings.isEmpty() ? "" : "\n\n")
                    + org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.PurpleEditorFollowMembers);
            return warnings;
        }
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
        private final java.util.List<PurpleAccountFiles.Identity> warningAccounts;
        private final PurpleAccountFiles globalFiles;
        private final PurpleAccountFiles files;
        private final PurpleAccountFiles.Identity destination;
        private volatile boolean retired;
        private volatile Current confirmation;
        Session(PurpleGlobalAccountChoice.Session context, PurpleAccountFiles.Identity origin,
                String key, PurpleAccountFiles files, PurpleAccountFiles globalFiles) throws Exception {
            accountKey = key; globalKey = context.global; global = key.equals(globalKey);
            warningAccounts = new java.util.ArrayList<>(context.accounts);
            this.globalFiles = globalFiles; this.files = files;
            PurpleAccountFiles.Identity destination = null;
            for (PurpleAccountFiles.Identity id : context.accounts) if (id.key.equals(key)) destination = id;
            this.destination = destination;
            java.util.ArrayList<java.io.File> authorities = new java.util.ArrayList<>(Arrays.asList(files.good, globalFiles.settings, globalFiles.good));
            for (PurpleAccountFiles.Identity id : warningAccounts) {
                PurpleAccountFiles accountFiles = PurpleAccountFiles.resolve(id.key, context.owner);
                if (accountFiles != null) { authorities.add(accountFiles.settings); authorities.add(accountFiles.good); }
            }
            model = new PurpleAccountEditorModel(PurpleAccountFiles.root(), files.settings, files.backup,
                    context.owner, context.global, key, authorities,
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
            if (model.capturedBytes(globalFiles.settings) == null && model.capturedBytes(globalFiles.good) != null) {
                throw new IllegalStateException("global last-good recovery requires review");
            }
            java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            java.util.ArrayList<byte[]> accountBytes = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> modes = new java.util.ArrayList<>();
            byte[] local = model.capturedBytes(PurpleAccountFiles.file("local.toml"));
            for (PurpleAccountFiles.Identity id : warningAccounts) {
                if (id.key.equals(globalKey)) continue;
                PurpleAccountFiles loaded = PurpleAccountFiles.resolve(id.key, context.owner);
                if (loaded == null) throw new IllegalStateException("impact account route unavailable");
                if (global && model.capturedBytes(loaded.settings) == null && model.capturedBytes(loaded.good) != null) {
                    throw new IllegalStateException("account last-good recovery requires review");
                }
                keys.add(id.key); accountBytes.add(model.capturedBytes(loaded.settings));
                modes.add(PurpleRoutedWriteModel.modeNative(local, id.key, globalKey));
            }
            int[] choices = new int[modes.size()];
            for (int i = 0; i < choices.length; ++i) choices[i] = modes.get(i);
            model.impact(new PurpleConfigImpact.Input(model.capturedBytes(files.settings), global, model.workMode,
                    model.capturedBytes(globalFiles.settings), keys.toArray(new String[0]), accountBytes.toArray(new byte[0][]), choices));
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
        public byte[] bytes() { return current() && !missing ? model.capturedBytes(files.settings) : null; }
        public Preview previewImport(byte[] bytes) {
            if (bytes == null) return null;
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (!Arrays.equals(bytes, PurpleAccountEditorModel.encode(text))) return null;
            return preview(text, true);
        }
        public Preview preview(String text) { return preview(text, false); }
        private Preview preview(String text, boolean imported) {
            PurpleAccountEditorModel.Preview result = current() ? model.preview(text, imported) : null;
            if (result == null) return null;
            return current() ? new Preview(this, result) : null;
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
                        try {
                            PurpleAccountEditorModel.Result result = model.save(preview.value);
                            if (result.ok && destination != null) {
                                boolean recorded = PurpleSync.afterFileWrite(destination, files.settings, preview.value.bytes,
                                        preview.value.imported, !missing && !preview.value.imported);
                                if (preview.value.imported && !recorded) result.ok = false;
                            }
                            return new Outcome(result);
                        }
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
