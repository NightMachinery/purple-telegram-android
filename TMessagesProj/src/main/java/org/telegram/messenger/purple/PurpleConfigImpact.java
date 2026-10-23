package org.telegram.messenger.purple;

import org.json.JSONObject;

final class PurpleConfigImpact {
    static final class Input {
        final byte[] current, global;
        final boolean appWide;
        final int mode;
        final String[] accounts;
        final byte[][] files;
        final int[] modes;
        Input(byte[] current, boolean appWide, int mode, byte[] global,
                String[] accounts, byte[][] files, int[] modes) {
            this.current = copy(current); this.appWide = appWide; this.mode = mode; this.global = copy(global);
            this.accounts = accounts.clone(); this.modes = modes.clone(); this.files = new byte[files.length][];
            for (int i = 0; i < files.length; ++i) this.files[i] = copy(files[i]);
        }
        JSONObject analyze(byte[] proposed) throws Exception {
            return new JSONObject(analyzeNative(current, proposed, appWide, mode, global, accounts, files, modes));
        }
    }
    static boolean ready(JSONObject impact) { return impact != null && impact.optInt("status", -1) == 0; }
    private static byte[] copy(byte[] value) { return value == null ? null : value.clone(); }
    static native String analyzeNative(byte[] current, byte[] proposed, boolean global, int mode,
            byte[] globalFile, String[] accounts, byte[][] files, int[] modes);
    private PurpleConfigImpact() { }
}
