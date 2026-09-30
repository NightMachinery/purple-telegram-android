package org.telegram.messenger;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

public final class MessagesController {
    public static final Map<String, String> VALUES = new HashMap<>();
    private static final SharedPreferences PREFERENCES = new SharedPreferences() {
        @Override
        public String getString(String key, String fallback) {
            return VALUES.getOrDefault(key, fallback);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, String> pending = new HashMap<>();

                @Override
                public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public boolean commit() {
                    VALUES.putAll(pending);
                    return true;
                }
            };
        }
    };

    private MessagesController() {
    }

    public static SharedPreferences getMainSettings(int account) {
        return PREFERENCES;
    }
}
