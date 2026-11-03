/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.telegram.messenger.purple.PurpleSyncChatCore.Peer;

/**
 * The manual sync screen's route. It reads and posts in the chat the Magic Purple Sync lookup bound (Saved
 * Messages or the magic chat). The store it opens moves the account's state into that chat first, so a
 * device that used Saved Messages publishes its settings in the magic chat on the next sync.
 */
final class PurpleSyncMagicManual extends PurpleSyncRunner.Platform implements PurpleSyncTransport {
    private final PurpleSyncChat.Holder holder;
    private final BooleanSupplier foreground;
    private volatile Peer chat;
    private volatile PurpleSyncTransport transport;
    private volatile Consumer<PurpleRpcRetry.Transient> waiting = ignored -> {};

    PurpleSyncMagicManual(PurpleSyncChat.Holder holder, BooleanSupplier foreground) {
        super(holder.env.context);
        this.holder = holder;
        this.foreground = foreground;
    }

    private boolean available() { return foreground.getAsBoolean() && context.current() && holder.env.current(); }

    @Override public void waiting(Consumer<PurpleRpcRetry.Transient> observer) { waiting = observer != null ? observer : ignored -> {}; }
    @Override public boolean current(int account, long user) {
        return context.current() && context.origin.slot == account && context.origin.userId == user;
    }
    @Override public PurpleAccountSyncCore.Result initialize(int account, String install, String device, String space) {
        Peer bound = chat;
        if (bound == null || !available()) return PurpleAccountSyncCore.inspectState(null);
        return Peer.self().equals(bound) ? PurpleAccountSyncCore.initializeBoundLocalState(account, install, device, space)
                : PurpleAccountSyncCore.initializeBoundLocalState(account, install, device, space, bound);
    }
    @Override public boolean workerEffects() { return true; }
    @Override public void checkEffects(BooleanSupplier effects, Consumer<Boolean> done) {
        holder.env.worker(() -> {
            boolean valid = false;
            try { valid = available() && effects.getAsBoolean(); } catch (RuntimeException | LinkageError ignored) { }
            boolean result = valid;
            holder.env.ui(() -> done.accept(result));
        });
    }
    @Override public boolean requiresRecovery() { Peer bound = chat; return bound == null || Peer.self().equals(bound); }
    @Override public PurpleSyncRecoveryModel.Offer recovery(PurpleSyncInventory inventory, PurpleSyncSettingsFile.Contents local) {
        return inventory != null && Peer.self().equals(inventory.chat) ? super.recovery(inventory, local) : null;
    }

    /** Ready when the lookup bound a chat; a different chat than last time gets its own transport. */
    @Override public void resolveChat(boolean beforePost, Object owner, String key, BooleanSupplier current, Consumer<Boolean> done) {
        if (!available() || !current.getAsBoolean()) { done.accept(false); return; }
        PurpleSyncChat.underLock(context, owner, key, beforePost, current, status -> {
            Peer bound = status.chat;
            boolean ready = bound != null && (beforePost ? status.postable(bound) : status.readable(bound));
            if (!ready || !available() || !current.getAsBoolean()) { done.accept(false); return; }
            if (!bound.equals(chat) || transport == null) {
                PurpleSyncClient client = Peer.self().equals(bound)
                        ? PurpleSyncTelegramClient.create(context.origin.slot, context.origin.userId)
                        : PurpleSyncChat.boundClient(context.origin.slot, bound, this::available);
                if (client == null) { done.accept(false); return; }
                client.transientWait(value -> { if (available()) waiting.accept(value); });
                PurpleSyncTransport previous = transport;
                transport = new PurpleSyncTelegramTransport(client);
                chat = bound;
                if (previous != null) previous.cancel();
            }
            done.accept(true);
        });
    }

    @Override public void check(Progress progress, CheckDone done) {
        PurpleSyncTransport active = transport;
        if (active == null || !available()) { done.onCheckDone(new CheckResult(CheckStatus.SyncChatHeld, null, false)); return; }
        active.check(progress, result -> { if (available()) done.onCheckDone(result); });
    }
    @Override public void post(byte[] bytes, PostDone done) { done.onPostDone(new PostResult(PostStatus.NeedsReview, 0, null)); }
    @Override public void post(byte[] bytes, PostAuthority authority, PostDone done) {
        PurpleSyncTransport active = transport;
        Peer bound = chat;
        if (active == null || bound == null || authority == null || !authority.current()) { post(bytes, done); return; }
        active.post(bytes, new PostAuthority() {
            public boolean current() { return available() && transport == active && authority.current(); }
            public void check(Consumer<Boolean> ok) { authority.check(fresh -> ok.accept(Boolean.TRUE.equals(fresh) && current())); }
            public void issued() { if (current()) authority.issued(); }
            public void waiting(PurpleRpcRetry.Transient value) { if (current()) authority.waiting(value); }
            public boolean permits(PurpleSyncChatOrigin origin, Peer target) {
                return current() && !Peer.self().equals(target) && bound.equals(target) && holder.env.origin.equals(origin);
            }
        }, done);
    }
    @Override public void cancel() { PurpleSyncTransport active = transport; if (active != null) active.cancel(); }

    /** The sync spaces of the records in {@code inventory}, or null when one of them cannot be read. */
    static java.util.Set<String> spaces(PurpleSyncInventory inventory) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (int i = 0; i < inventory.count(); ++i) {
            if (inventory.transport[i] != PurpleSyncInventory.FETCHED || inventory.bytes[i] == null) return null;
            PurpleAccountSyncCore.Result record = PurpleAccountSyncCore.inspectConfigRecord(inventory.bytes[i]);
            if (!"Valid".equals(record.envelopeStatus) || record.space == null || record.space.isEmpty()) return null;
            result.add(record.space);
        }
        return result;
    }
}
