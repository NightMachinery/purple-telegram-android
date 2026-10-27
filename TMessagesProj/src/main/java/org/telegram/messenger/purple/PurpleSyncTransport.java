/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

public interface PurpleSyncTransport {
    enum Phase { Scanning, Reading }

    enum CheckStatus { Finished, Cancelled, AccountChanged, SyncChatHeld }

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
        public final PurpleSyncChatCore.Peer chat;
        final PurpleSyncChatOrigin origin;
        public final PurpleRpcRetry.Transient waiting;

        public PostResult(PostStatus status, int messageId, byte[] readBack) {
            this(status,messageId,readBack,PurpleSyncChatCore.Peer.self(),null);
        }

        PostResult(PostStatus status,int messageId,byte[] readBack,PurpleSyncChatCore.Peer chat,PurpleSyncChatOrigin origin) {
            this(status,messageId,readBack,chat,origin,null);
        }
        PostResult(PostStatus status,int messageId,byte[] readBack,PurpleSyncChatCore.Peer chat,PurpleSyncChatOrigin origin,PurpleRpcRetry.Transient waiting){
            this.chat=java.util.Objects.requireNonNull(chat);this.origin=origin;this.waiting=waiting;
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

    interface PostAuthority {
        boolean current();
        void check(java.util.function.Consumer<Boolean> done);
        default boolean permits(PurpleSyncChatOrigin origin,PurpleSyncChatCore.Peer chat) { return false; }
        default void issued(){}
        default void waiting(PurpleRpcRetry.Transient value){}
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

    default void post(byte[] staged,PostAuthority authority,PostDone done) {
        if(authority==null || !authority.current()){done.onPostDone(new PostResult(PostStatus.OutcomeUnknown,0,null));return;}
        java.util.concurrent.atomic.AtomicBoolean answered=new java.util.concurrent.atomic.AtomicBoolean();
        authority.check(ready->{
            if(!answered.compareAndSet(false,true))return;
            if(Boolean.TRUE.equals(ready) && authority.current())post(staged,done);
            else done.onPostDone(new PostResult(PostStatus.OutcomeUnknown,0,null));
        });
    }

    void cancel();
}
