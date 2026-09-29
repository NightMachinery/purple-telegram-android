package org.json;

public class JSONObject {
    public JSONObject(String source) throws JSONException {
    }

    public String getString(String name) throws JSONException {
        return "";
    }

    public String optString(String name, String fallback) {
        return fallback;
    }

    public int optInt(String name, int fallback) {
        return fallback;
    }

    public boolean optBoolean(String name, boolean fallback) {
        return fallback;
    }

    public boolean has(String name) {
        return false;
    }

    public Object get(String name) throws JSONException {
        return null;
    }
}
