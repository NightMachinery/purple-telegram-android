/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.Locale;

/**
 * The reason code of a Check that refused (R4b item 3). A NeedsReview review carries core's own code
 * ({@code SyncConfigReviewReasonCode}: space_mismatch, unreadable, ambiguous, newer_schema, heads_unclear,
 * own_records); every other refusal is named after its status. The Sync screen shows one line per code
 * ({@code PurpleSyncText.describe}) and logs {@code sync check_refused reason=<code>}.
 */
public final class PurpleSyncCheckReason {
    /** The code of {@code review}, or null when it did not refuse. */
    public static String code(PurpleSyncCore.Review review) {
        if (review == null || review.status == null || review.status == PurpleSyncCore.ReviewStatus.Ready) return null;
        if (review.status == PurpleSyncCore.ReviewStatus.NeedsReview)
            return review.reason != null && !review.reason.isEmpty() ? review.reason : "ambiguous";
        return review.status.name().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** Logs a refused Check of account {@code slot}; a Check that did not refuse logs nothing. */
    public static void log(int slot, PurpleSyncCore.Review review) {
        String code = code(review);
        if (code != null) PurpleDiagLog.event("sync", "check_refused", PurpleDiagLogStore.slot(slot), PurpleDiagLogStore.reason(code));
    }

    private PurpleSyncCheckReason() { }
}
