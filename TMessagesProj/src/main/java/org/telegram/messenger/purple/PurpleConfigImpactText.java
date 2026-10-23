package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import java.util.LinkedHashSet;

final class PurpleConfigImpactText {
    interface Text {
        String get(int resource);
        String format(int resource, Object... args);
        String label(String key);
    }
    private static final Text LOCALIZED = new Text() {
        public String get(int resource) { return LocaleController.getString(resource); }
        public String format(int resource, Object... args) { return LocaleController.formatString(resource, args); }
        public String label(String key) { return PurpleConfigImpactText.label(key); }
    };
    static String label(String key) {
        for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) if (id.key.equals(key)) {
            String label = PurpleSettings.accountLabel(id.slot);
            if (label != null && !label.isEmpty()) return label;
        }
        return key;
    }
    static String describe(JSONObject impact, boolean global, String accountKey, String globalKey) {
        return describe(impact, global, accountKey, globalKey, LOCALIZED);
    }
    static String describe(JSONObject impact, boolean global, String accountKey, String globalKey, Text words) {
        if (!PurpleConfigImpact.ready(impact)) return words.get(R.string.PurpleImpactHeld);
        String account = words.label(accountKey), base = words.label(globalKey);
        StringBuilder text = new StringBuilder();
        JSONArray findings = impact.optJSONArray("findings");
        LinkedHashSet<String> used = new LinkedHashSet<>(), inactive = new LinkedHashSet<>(), unknown = new LinkedHashSet<>();
        StringBuilder risks = new StringBuilder();
        if (findings != null) for (int i = 0; i < findings.length(); ++i) {
            JSONObject item = findings.optJSONObject(i);
            if (item == null) continue;
            int kind = item.optInt("kind", -1), before = item.optInt("beforeUse"), after = item.optInt("afterUse");
            String address = item.optString("table") + (item.optString("key").isEmpty() ? "" : "." + item.optString("key"));
            if (address.startsWith(".")) address = address.substring(1);
            String entry = item.optString("label") + " (" + address + ")";
            if (kind == 0) {
                if (before == 1 || after == 1) used.add(entry);
                else if (before == 6 || after == 6) unknown.add(entry);
                else inactive.add(entry);
            } else if (kind == 1 || kind == 2) {
                boolean active = (kind == 1 ? after : before) == 1;
                int resource = kind == 1
                        ? (active ? R.string.PurpleImpactPinned : R.string.PurpleImpactStoredPin)
                        : (active ? R.string.PurpleImpactUnpinned : R.string.PurpleImpactStoredUnpin);
                append(risks, words.format(resource, account, base, entry));
            } else {
                int resource = kind == 3 ? R.string.PurpleImpactAppWideLoss : kind == 4 ? R.string.PurpleImpactListLoss
                        : kind == 5 ? R.string.PurpleImpactPresetLoss : kind == 6 ? R.string.PurpleImpactScheduleLoss
                        : kind == 7 ? R.string.PurpleImpactEmptyList : kind == 8 ? R.string.PurpleImpactStranded : R.string.PurpleImpactUnknownRemoval;
                String detail = kind == 8 ? words.label(item.optString("account")) + ": " + entry : entry;
                String line = words.format(resource, detail);
                if (global && kind == 7) append(text, words.format(R.string.PurpleImpactEmptyHere, account, entry));
                else append(risks, line);
            }
        }
        if (global && impact.optBoolean("turnsWorkModeOff")) text.insert(0,
                words.format(R.string.PurpleImpactWorkModeOff, account) + "\n\n");
        append(text, words.format(global ? R.string.PurpleImpactGlobalScope : R.string.PurpleImpactOwnScope, account));
        append(text, risks.toString());
        if (used.isEmpty() && unknown.isEmpty() && !inactive.isEmpty() && !impact.optBoolean("dangerous")) {
            append(text, words.get(R.string.PurpleImpactInactiveOnly));
        }
        group(text, R.string.PurpleImpactUsed, used, words); group(text, R.string.PurpleImpactUnused, inactive, words); group(text, R.string.PurpleImpactUnknown, unknown, words);
        return text.toString();
    }
    private static void group(StringBuilder text, int heading, LinkedHashSet<String> entries, Text words) {
        if (!entries.isEmpty()) append(text, words.get(heading) + "\n" + String.join("\n", entries));
    }
    private static void append(StringBuilder text, String line) {
        if (line.isEmpty()) return;
        if (text.length() > 0) text.append("\n\n");
        text.append(line);
    }
    private PurpleConfigImpactText() { }
}
