/*
 * This is the source code of Purple Telegram for Android.
 *
 * One recording in the F10 registry (docs/media-recording-design.md, "The
 * registry"). Pure Java with org.json, so the host suite runs it as is.
 */

package org.telegram.messenger.purple;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A recording from its start to its end in the bin. The registry writes one
 * at start and on every state change; the bin lists the ones that ended in
 * {@link #UNSENT}, {@link #RECOVERED} or {@link #UNRECOVERABLE}.
 *
 * It holds the chat, topic and reply ids, because "Send to the original chat"
 * needs them; they stay in Purple's own directory and never reach a log.
 */
public final class PurpleRecordingEntry {

    /** The entry layout this build writes and reads. */
    public static final int VERSION = 1;

    public static final String VOICE = "voice";
    public static final String ROUND = "round";

    /** Being recorded (or paused). Still in this state at a start, it belongs to a crash. */
    public static final String RECORDING = "recording";
    /** Handed to upstream's send path; dropped once the message is queued. */
    public static final String SENDING = "sending";
    public static final String SENT = "sent";
    /** Ended without being sent: discarded (kept by keep_discarded_p). */
    public static final String UNSENT = "unsent";
    /** Recovered after a crash. */
    public static final String RECOVERED = "recovered";
    /** Found after a crash but could not be made playable; kept raw. */
    public static final String UNRECOVERABLE = "unrecoverable";

    public String id;
    /** The account slot. */
    public int account = -1;
    public long dialogId;
    public long topicId;
    public int replyId;
    public String kind = VOICE;
    /** Wall-clock start, in milliseconds. */
    public long startedAt;
    public long durationMs;
    /** The view-once flag, read from the chat's enter view at start and at each stop. */
    public boolean once;
    /** The account's client user id when it was recorded: Send to the original chat only from the same account. */
    public long userId;
    /** Recorded in the quick-reply editor: never sent to a chat as a message. */
    public boolean quickReply;
    /** The media file: the recorder's while recording, the bin's afterwards. */
    public String media;
    /** The round's journal ({@code <media>.pjr}), or null. */
    public String journal;
    public String state = RECORDING;
    /** When the item entered the bin, in Unix seconds; 0 while not in the bin. */
    public long binnedAt;
    /** Repair stopped early: "Partly recovered". */
    public boolean partial;
    /** The user discarded it: "Discarded". */
    public boolean discarded;
    /** Found by the orphan scan: no chat is known ("Unknown chat"). */
    public boolean orphan;
    /** The layout version this entry was read with; an unknown one is kept raw. */
    public int version = VERSION;

    /** Whether the bin lists it. */
    public boolean inBin() {
        return UNSENT.equals(state) || RECOVERED.equals(state) || UNRECOVERABLE.equals(state);
    }

    /** Whether a start finds it unfinished, so it belongs to a crash. */
    public boolean unfinished() {
        return RECORDING.equals(state) || SENDING.equals(state);
    }

    public boolean round() {
        return ROUND.equals(kind);
    }

    /** Whether "Send to the original chat" has a chat to go to. */
    public boolean hasChat() {
        return !orphan && account >= 0 && dialogId != 0;
    }

    /**
     * Whether its account slot still holds the account that recorded it: the
     * slot is signed in with the same client user id. After a logout, or with
     * another account in the slot, the item stays in the bin as "Unknown
     * chat" and cannot be sent to its original chat (ids are global, so the
     * dialog would resolve from the other identity).
     */
    public boolean sameAccount(boolean activated, long clientUserId) {
        return activated && userId != 0 && userId == clientUserId;
    }

    JSONObject toJson() throws JSONException {
        final JSONObject o = new JSONObject();
        o.put("v", version);
        o.put("id", id);
        o.put("account", account);
        o.put("dialog", dialogId);
        o.put("topic", topicId);
        o.put("reply", replyId);
        o.put("kind", kind);
        o.put("started", startedAt);
        o.put("duration", durationMs);
        o.put("once", once);
        o.put("user", userId);
        o.put("quick_reply", quickReply);
        if (media != null) o.put("media", media);
        if (journal != null) o.put("journal", journal);
        o.put("state", state);
        o.put("binned", binnedAt);
        o.put("partial", partial);
        o.put("discarded", discarded);
        o.put("orphan", orphan);
        return o;
    }

    /**
     * Reads one entry. An entry of a version this build does not know keeps
     * its id and files, and is listed as unrecoverable so its bytes stay
     * where they are until auto-clear; null when even that cannot be read.
     */
    static PurpleRecordingEntry fromJson(JSONObject o, long now) {
        final PurpleRecordingEntry e = new PurpleRecordingEntry();
        e.id = o.optString("id", null);
        if (e.id == null || e.id.isEmpty()) return null;
        e.version = o.optInt("v", 0);
        e.media = o.optString("media", null);
        e.journal = o.optString("journal", null);
        if (e.version != VERSION) {
            e.state = UNRECOVERABLE;
            e.orphan = true;
            e.kind = ROUND.equals(o.optString("kind")) ? ROUND : VOICE;
            e.binnedAt = o.optLong("binned", 0) > 0 ? o.optLong("binned") : now;
            return e;
        }
        e.account = o.optInt("account", -1);
        e.dialogId = o.optLong("dialog", 0);
        e.topicId = o.optLong("topic", 0);
        e.replyId = o.optInt("reply", 0);
        e.kind = ROUND.equals(o.optString("kind")) ? ROUND : VOICE;
        e.startedAt = o.optLong("started", 0);
        e.durationMs = o.optLong("duration", 0);
        e.once = o.optBoolean("once", false);
        e.userId = o.optLong("user", 0);
        e.quickReply = o.optBoolean("quick_reply", false);
        e.state = o.optString("state", RECORDING);
        e.binnedAt = o.optLong("binned", 0);
        e.partial = o.optBoolean("partial", false);
        e.discarded = o.optBoolean("discarded", false);
        e.orphan = o.optBoolean("orphan", false);
        return e;
    }
}
