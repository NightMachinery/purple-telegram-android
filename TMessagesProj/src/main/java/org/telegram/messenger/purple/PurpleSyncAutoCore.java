package org.telegram.messenger.purple;

import android.util.Base64;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleSyncAutoCore {
    enum Action { NoOp, Check, AutoApply, Publish, EditInPlace, Post, Reconcile, Retire, Hold, RetryAt }
    enum Event { Enable, Disable, SessionReady, Foreground, Unlock, LocalSaved, Retry, Completed }
    enum Outcome {
        Inventory,
        Applied,
        Staged,
        Sent,
        Committed,
        Deleted,
        NetworkFailure,
        OutcomeUnknown,
        EditRefused,
        DeletionRefused
    }
    enum Reason {
        None,
        InvalidRecord,
        AccountUnavailable,
        OwnershipUnanswered,
        WrongFileAccount,
        AccountUnbound,
        SyncChatNotResolved,
        ChatNotWritable,
        InvalidSettings,
        FileAbsent,
        LastGood,
        NoSavedBytes,
        NeedsReview,
        Conflict,
        UnsafeImpact,
        WorkModeScopeChanged,
        CloneDetected,
        StoreMismatch,
        OwnershipChanged,
        DeletionRefused,
        RetryStopped,
        InterruptedApply,
        StateChanged
    }
    static final class Record {
        final byte[] bytes;
        final JSONObject value;
        Record(byte[] bytes) throws Exception {
            this.bytes = bytes.clone();
            value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        }
        boolean enabled() {
            return value.optBoolean("enabled");
        }
        String nextEvent() throws Exception {
            BigInteger n = new BigInteger(value.getString("event")).add(BigInteger.ONE);
            if (n.bitLength() > 64)
                throw new IllegalStateException("auto event exhausted");
            return n.toString();
        }
        String ticket() {
            return value.optString("ticket");
        }
        long retryAt() {
            return Long.parseLong(value.optString("retryAt", "0"));
        }
        int messageId() {
            return value.optInt("messageId");
        }
        Reason held() {
            int n = value.optInt("held", -1);
            return n >= 0 && n < Reason.values().length ? Reason.values()[n] : Reason.InvalidRecord;
        }
    }
    static final class Plan {
        final Action action;
        final Reason reason;
        final Record record;
        final boolean notice, commitOnly;
        final String ticket;
        final long retryAt;
        final int messageId;
        final byte[] bytes, nextState, previous;
        Plan(String text) throws Exception {
            JSONObject json = new JSONObject(text);
            action = Action.values()[json.getInt("action")];
            reason = Reason.values()[json.getInt("reason")];
            record
            = new Record(decode(json.getString("record")));
            notice = json.getBoolean("notice");
            commitOnly = json.getBoolean("commitOnly");
            ticket = json.getString("ticket");
            retryAt = Long.parseLong(json.getString("retryAt"));
            messageId = json.getInt("messageId");
            bytes = decode(json.getString("bytes"));
            previous = decode(json.optString("previous"));
            nextState = action == Action.Retire ? decode(record.value.getString("expectedState"))
                                                : decode(json.getString("nextState"));
        }
    }
    static final class Facts {
        final JSONObject json = new JSONObject();
        PurpleConfigImpact.Input impact;
        Facts(String account, String session) throws Exception {
            json.put("account", account)
                .put("fileAccount", account)
                .put("session", session)
                .put("inFlightSession", "")
                .put("signedIn", false)
                .put("ownerAnswered", false)
                .put("accountLock", false)
                .put("chatResolved", false)
                .put("chatWritable", false)
                .put("saveDebounceFinished", false)
                .put("editSupported", false)
                .put("lastGood", false)
                .put("sendQueued", false)
                .put("impactComplete", false)
                .put("device", "")
                .put("bindingToken", "")
                .put("savedFingerprint", "")
                .put("state", "")
                .put("staged", "")
                .put("local", "")
                .put("localStatus", 2);
        }
    }
    static final class Inventory {
        final PurpleSyncInventory inventory;
        final JSONObject json;
        Inventory(PurpleSyncInventory inventory, String session, String ticket, long readAt, JSONArray reads)
            throws Exception {
            if(inventory==null || !PurpleSyncChatCore.Peer.self().equals(inventory.chat))
                throw new IllegalArgumentException("AutomaticNonSelfHeld");
            this.inventory = inventory;
            json = new JSONObject()
                       .put("session", session)
                       .put("ticket", ticket)
                       .put("readAt", Long.toString(readAt))
                       .put("fresh", true)
                       .put("reads", reads);
        }
    }
    static Record decodeRecord(byte[] bytes, String key) throws Exception {
        PurpleCore.ensureLoaded();
        JSONObject json = new JSONObject(codecNative(bytes, key));
        return json.getInt("status") == 0 ? new Record(decode(json.getString("record"))) : null;
    }
    static Plan plan(Record record, Facts facts, Event event, long now, String ticket, Outcome outcome,
        Inventory inventory) throws Exception {
        JSONObject request = new JSONObject()
                                 .put("record", encode(record.bytes))
                                 .put("facts", facts.json)
                                 .put("sequence", record.nextEvent())
                                 .put("event", event.ordinal())
                                 .put("now", Long.toString(now))
                                 .put("completedTicket", ticket == null ? "0" : ticket)
                                 .put("outcome", outcome.ordinal());
        if (inventory != null)
            request.put("inventory", inventory.json);
        PurpleSyncInventory inv = inventory != null
            ? inventory.inventory
            : new PurpleSyncInventory.Builder(Long.parseLong(facts.json.getString("account").substring(2)))
                  .build(false);
        PurpleConfigImpact.Input impact = facts.impact;
        return new Plan(
            planNative(request.toString(), inv.accountUserId, inv.scanComplete, inv.ids, inv.transport, inv.documentIds,
                inv.editDates, inv.bytes, impact != null && impact.appWide, impact != null ? impact.mode : 0,
                impact != null ? impact.global : null, impact != null ? impact.accounts : new String[0],
                impact != null ? impact.files : new byte[0][], impact != null ? impact.modes : new int[0]));
    }
    static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes == null ? new byte[0] : bytes, Base64.NO_WRAP);
    }
    static byte[] decode(String text) {
        return Base64.decode(text, Base64.NO_WRAP);
    }
    static native String codecNative(byte[] bytes, String account);
    static native String planNative(String request, long user, boolean complete, int[] ids, int[] transport,
        long[] documents, long[] edits, byte[][] records, boolean global, int mode, byte[] globalFile,
        String[] accounts, byte[][] files, int[] modes);
    private PurpleSyncAutoCore() {}
}
