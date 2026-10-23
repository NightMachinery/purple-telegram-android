package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class PurpleListDiagnostics {
    private static final Map<String, byte[]> previous = new HashMap<>();
    private static final Map<String, Set<String>> orphaned = new HashMap<>();
    private static String lastGlobal;
    public static String accountNotes(int account) {
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(account);
        if (id == null || id.key.equals(PurpleAccountOwner.global())) return "";
        PurpleAccountFiles files = PurpleAccountFiles.resolve(id.key, PurpleAccountOwner.owner());
        if (files == null) return "";
        PurpleAccountFileSnapshot own = PurpleAccountFileSnapshot.read(files.settings);
        PurpleAccountFileSnapshot global = PurpleAccountFileSnapshot.read(PurpleAccountSettings.globalFile());
        return notes(null, own.bytes, global.bytes, account, PurpleAccountOwner.global(), false);
    }
    static String notes(byte[] before, byte[] own, byte[] global, int account, String globalKey, boolean changes) {
        try {
            JSONObject facts = new JSONObject(PurpleListDiagnosticsModel.factsNative(before, own, global));
            return facts.optBoolean("ok") ? format(facts, account, globalKey, changes) : "";
        } catch (Exception | UnsatisfiedLinkError e) { return ""; }
    }
    private static String format(JSONObject facts, int account, String globalKey, boolean changes) {
        ArrayList<String> lines = new ArrayList<>();
        String own = PurpleSettings.accountLabel(account), global = label(globalKey);
        JSONArray missing = facts.optJSONArray("orphaned");
        if (missing != null) for (int i = 0; i < missing.length(); ++i) {
            JSONObject entry = missing.optJSONObject(i);
            if (entry != null) lines.add(LocaleController.formatString(R.string.PurpleListUndefined, own,
                    entry.optString("name"), entry.optInt("count"), global));
        }
        if (changes) {
            JSONArray empty = facts.optJSONArray("emptied");
            if (empty != null) for (int i = 0; i < empty.length(); ++i) lines.add(LocaleController.formatString(
                    R.string.PurpleListEmptied, own, empty.optString(i)));
        }
        return android.text.TextUtils.join("\n\n", lines);
    }
    private static String label(String key) {
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) if (id.key.equals(key)) return PurpleSettings.accountLabel(id.slot);
        return key;
    }
    static synchronized void afterReload() {
        String globalKey = PurpleAccountOwner.global();
        if (globalKey == null) return;
        java.io.File globalFile = PurpleAccountSettings.globalFile();
        PurpleAccountFileSnapshot global = PurpleAccountFileSnapshot.read(globalFile);
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
            PurpleAccountFiles files = PurpleAccountFiles.resolve(id.key, PurpleAccountOwner.owner());
            if (files == null) continue;
            PurpleAccountFileSnapshot own = PurpleAccountFileSnapshot.read(files.settings);
            try {
                JSONObject facts = new JSONObject(PurpleListDiagnosticsModel.factsNative(previous.get(id.key), own.bytes, global.bytes));
                if (!facts.optBoolean("ok") || !id.current() || !own.sameRead(files.settings) || !global.sameRead(globalFile)) continue;
                Set<String> names = new HashSet<>();
                JSONArray missing = facts.optJSONArray("orphaned");
                if (missing != null) for (int i = 0; i < missing.length(); ++i) names.add(missing.getJSONObject(i).toString());
                boolean newMissing = lastGlobal != null && (!globalKey.equals(lastGlobal)
                        || !orphaned.getOrDefault(id.key, java.util.Collections.emptySet()).containsAll(names));
                int count = id.key.equals(globalKey) ? 0 : facts.optInt("inactiveCount");
                String message = count == 0 ? "" : LocaleController.formatString(R.string.PurpleInactiveAppWideChanges,
                        count, PurpleSettings.accountLabel(id.slot));
                JSONObject shown = new JSONObject(facts.toString());
                if (!newMissing || id.key.equals(globalKey)) shown.put("orphaned", new JSONArray());
                String warnings = format(shown, id.slot, globalKey, true);
                if (!warnings.isEmpty()) message += (message.isEmpty() ? "" : "\n\n") + warnings;
                if (!message.isEmpty()) {
                    String notice = message;
                    AndroidUtilities.runOnUIThread(() -> org.telegram.ui.PurpleListWarningsUI.show(id, globalKey, notice));
                }
                previous.put(id.key, own.bytes); orphaned.put(id.key, names);
            } catch (Exception | UnsatisfiedLinkError e) { }
        }
        lastGlobal = globalKey;
    }
    private PurpleListDiagnostics() { }
}
