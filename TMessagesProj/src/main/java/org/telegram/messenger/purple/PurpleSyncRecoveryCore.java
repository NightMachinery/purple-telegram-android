package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.json.JSONArray;

final class PurpleSyncRecoveryCore {
    enum Status { Bound, AutoRelink, RootRelink, StartAgain, Setup, WaitingForSignIn, Incomplete, NeedsReview, Ambiguous, InvalidInput }
    enum Start { Ready, ConfirmationRequired, Busy, NotOffered, Incomplete, ArchiveUnavailable, InvalidDate }
    enum Completion { SetupReady, InvalidPlan, Incomplete, NeedsRecheck }
    static final class Plan {
        final JSONObject value;
        final Status status;
        final Start start;
        final Completion completion;
        final String token, archive;
        final boolean offerStartAgain, clearUndo;
        final JSONArray moves;
        Plan(String json) throws Exception {
            value = new JSONObject(json);
            if (value.optBoolean("invalid")) throw new IllegalStateException("invalid recovery observation");
            int recovery = value.getInt("recovery");
            status = Status.values()[recovery];
            start = value.has("start") ? Start.values()[value.getInt("start")] : Start.NotOffered;
            completion = value.has("completion") ? Completion.values()[value.getInt("completion")] : Completion.InvalidPlan;
            token = value.optString("token"); archive = value.optString("archive");
            offerStartAgain = value.optBoolean("offerStartAgain"); clearUndo = value.optBoolean("clearUndo");
            moves = value.optJSONArray("moves") != null ? value.getJSONArray("moves") : new JSONArray();
        }
    }
    static Plan plan(JSONObject request, PurpleSyncInventory inventory, PurpleSyncSettingsFile.Contents local) {
        try {
            PurpleCore.ensureLoaded();
            return new Plan(planNative(request.toString(), inventory != null ? inventory.accountUserId : 0,
                    inventory != null && inventory.scanComplete, inventory != null ? inventory.ids : null,
                    inventory != null ? inventory.transport : null, inventory != null ? inventory.documentIds : null,
                    inventory != null ? inventory.editDates : null, inventory != null ? inventory.bytes : null,
                    local.status.ordinal(), local.bytes, local.usingLastGood));
        } catch (Exception | LinkageError e) { return null; }
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) { out.append(Character.forDigit((value & 255) >>> 4, 16)); out.append(Character.forDigit(value & 15, 16)); }
        return out.toString();
    }
    static byte[] bytes(String hex) {
        if (hex.length() % 2 != 0 || hex.length() > 8 * 1024 * 1024) return null;
        byte[] out = new byte[hex.length()/2];
        for (int i=0;i<out.length;++i) {
            int a=Character.digit(hex.charAt(2*i),16), b=Character.digit(hex.charAt(2*i+1),16);
            if (a<0 || b<0) return null;
            out[i]=(byte)((a<<4)|b);
        }
        return hex(out).equals(hex) ? out : null;
    }
    private static native String planNative(String request, long accountUserId, boolean complete,
            int[] ids, int[] transport, long[] documentIds, long[] editDates, byte[][] records,
            int localStatus, byte[] localText, boolean lastGood);
    private PurpleSyncRecoveryCore() { }
}
