/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

public interface PurpleSyncTransport {
    enum Phase { Scanning, Reading }

    enum CheckStatus { Finished, Cancelled, AccountChanged }

    enum PostStatus { Confirmed, OutcomeUnknown, NeedsReview, Cancelled, InvalidRecord }

    final class CheckResult {
        public final CheckStatus status;
        public final PurpleSyncInventory inventory;
        public final boolean sendQueued;

        public CheckResult(CheckStatus status, PurpleSyncInventory inventory,
                boolean sendQueued) {
            if ((status == CheckStatus.Finished) != (inventory != null)) {
                throw new IllegalArgumentException(
                        "an inventory comes with Finished and only then");
            }
            this.status = status;
            this.inventory = inventory;
            this.sendQueued = sendQueued;
        }
    }

    final class PostResult {
        public final PostStatus status;
        public final int messageId;
        public final byte[] readBack;

        public PostResult(PostStatus status, int messageId, byte[] readBack) {
            if ((status == PostStatus.Confirmed)
                    != (messageId > 0 && readBack != null)) {
                throw new IllegalArgumentException(
                        "a message id and read-back come with Confirmed and only then");
            }
            this.status = status;
            this.messageId = messageId;
            this.readBack = readBack;
        }
    }

    interface Progress {
        void onProgress(Phase phase, int done, int total);
    }

    interface CheckDone {
        void onCheckDone(CheckResult result);
    }

    interface PostDone {
        void onPostDone(PostResult result);
    }

    void check(Progress progress, CheckDone done);

    void post(byte[] staged, PostDone done);

    void cancel();
}
