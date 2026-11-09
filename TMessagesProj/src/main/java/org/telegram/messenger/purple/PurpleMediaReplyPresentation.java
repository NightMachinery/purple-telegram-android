package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;

public final class PurpleMediaReplyPresentation {
    public static int modeSelection(JSONObject row, String key, boolean selectedSet) {
        return selectedSet && row.isNull(key) ? 4 : row.optInt(key, 0);
    }
    public static int followSelection(JSONObject row, boolean selectedSet) {
        return selectedSet && row.isNull("round_follow_voice") ? 2 : row.optBoolean("round_follow_voice", true) ? 0 : 1;
    }
    public static boolean roundDisabled(JSONObject policy, JSONObject row, boolean selectedSet) {
        if (!selectedSet || !row.isNull("round_follow_voice")) return row.optBoolean("round_follow_voice", true);
        JSONArray defaults = policy.optJSONArray("defaults");
        if (defaults == null || defaults.length() != 3) return false;
        for (int i = 0; i < 3; ++i) if (!defaults.optJSONObject(i).optBoolean("effective_follow", true)) return false;
        return true;
    }
    private PurpleMediaReplyPresentation() { }
}
