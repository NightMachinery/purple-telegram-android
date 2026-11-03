/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The Android side of {@link PurpleSyncMagicChat}: the binding file
 * sync/chat.json, the local chat lists of MessagesController and the four
 * Telegram requests the lookup may send (createChannel, getFullChannel,
 * editPeerFolders, updateNotifySettings). Every request goes straight to
 * ConnectionsManager with a 30 second timeout; the lookup itself decides
 * whether and when to retry, so a flood wait is never waited out in memory.
 * The lists are paged only through upstream's own dialog loader.
 *
 * The lists are read from the account's real dialogs ({@code dialogs_dict}),
 * never from the arrays Work Mode, folders or Purple's hidden sync chat
 * filter, so no view can hide a magic chat from the absence proof.
 */
final class PurpleSyncMagicTelegram implements PurpleSyncMagicChat.Port {
    static final String FILE = PurpleAccountSyncStore.MAGIC_BINDING;
    static final int LIMIT = 16 * 1024;
    static final String ABOUT = "Purple Telegram keeps sync data for your devices here.";
    private static final long TIMEOUT = 30000;
    /** Upstream's page size when the chat list scrolls (DialogsActivity). */
    private static final int PAGE = 100;

    private final PurpleSyncChat.Platform env;
    private final File file;

    PurpleSyncMagicTelegram(PurpleSyncChat.Platform env) {
        this.env = env;
        file = new File(env.context.files.syncDirectory, FILE);
    }

    private int slot() { return env.origin.slot; }

    @Override public boolean current() { return env.current(); }
    @Override public long now() { return System.currentTimeMillis() / 1000; }
    @Override public void post(Runnable task) { PurpleSyncChat.queue().postRunnable(task); }
    @Override public void delay(Runnable task, long millis) { PurpleSyncChat.queue().postRunnable(task, millis); }
    @Override public void cancelDelay(Runnable task) { PurpleSyncChat.queue().cancelRunnable(task); }

    @Override public String readBinding() {
        if (!current()) return null;
        PurpleAccountFileSnapshot snapshot = PurpleAccountFileSnapshot.read(file, LIMIT);
        if (snapshot.status == PurpleAccountFileSnapshot.Status.Missing) return "";
        return snapshot.status == PurpleAccountFileSnapshot.Status.Present ? new String(snapshot.bytes, StandardCharsets.UTF_8) : null;
    }

    @Override public boolean writeBinding(String expected, String next) {
        if (!current() || expected == null || next == null) return false;
        PurpleAccountFileSnapshot snapshot = PurpleAccountFileSnapshot.read(file, LIMIT);
        String now = snapshot.status == PurpleAccountFileSnapshot.Status.Missing ? ""
            : snapshot.status == PurpleAccountFileSnapshot.Status.Present ? new String(snapshot.bytes, StandardCharsets.UTF_8) : null;
        return expected.equals(now) && PurpleAccountFileSnapshot.replace(file, snapshot, next.getBytes(StandardCharsets.UTF_8));
    }

    @Override public void local(PurpleSyncChatCore.Peer bound, Consumer<PurpleSyncMagicChat.Local> done) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!current()) { done.accept(null); return; }
            MessagesController controller = MessagesController.getInstance(slot());
            List<PurpleSyncMagicCore.Candidate> visible = new ArrayList<>();
            // WHY: dialogs_dict holds every loaded dialog of both lists; the per-folder arrays and the filter views
            // skip what Work Mode or the list hiding leave out, and must not decide that no magic chat exists.
            for (int i = 0; i < controller.dialogs_dict.size(); ++i) {
                long dialogId = controller.dialogs_dict.keyAt(i);
                if (dialogId >= 0) continue;
                TLRPC.Chat chat = controller.getChat(-dialogId);
                if (chat == null || !PurpleSyncMagicCore.TITLE.equals(chat.title)) continue;
                PurpleSyncMagicCore.Candidate candidate = PurpleSyncChatFacts.magic(chat, null);
                if (candidate != null) visible.add(candidate);
            }
            PurpleSyncMagicCore.BoundState state = PurpleSyncMagicCore.BoundState.Unknown;
            PurpleSyncMagicCore.Candidate candidate = null;
            if (bound != null && bound.kind == PurpleSyncChatCore.PeerKind.Self) {
                candidate = new PurpleSyncMagicCore.Candidate();
                candidate.facts.kind = PurpleSyncChatCore.Kind.Self; candidate.facts.canSendFiles = true;
                candidate.facts.otherMembers = 0L; candidate.facts.otherAdmins = 0L;
                state = PurpleSyncMagicCore.BoundState.Present;
            } else if (bound != null && bound.kind == PurpleSyncChatCore.PeerKind.Channel) {
                TLRPC.Chat chat = controller.getChat(bound.id);
                candidate = chat != null ? PurpleSyncChatFacts.magic(chat, controller.getChatFull(bound.id)) : null;
                if (candidate == null) candidate = chat != null ? PurpleSyncChatFacts.magic(chat, null) : null;
                if (chat instanceof TLRPC.TL_channelForbidden) { state = PurpleSyncMagicCore.BoundState.Gone; candidate = null; }
                else if (candidate != null) state = PurpleSyncMagicCore.BoundState.Present;
            }
            done.accept(new PurpleSyncMagicChat.Local(visible, state, candidate,
                controller.isServerDialogsEndReached(0), controller.isServerDialogsEndReached(1)));
        });
    }

    @Override public void stored(long channelId, Consumer<PurpleSyncMagicChat.Local> done) {
        if (!current()) { done.accept(null); return; }
        MessagesStorage storage = MessagesStorage.getInstance(slot());
        storage.getStorageQueue().postRunnable(() -> {
            TLRPC.Chat found = null;
            try { if (current()) found = storage.getChat(channelId); } catch (RuntimeException ignored) { }
            TLRPC.Chat chat = found;
            AndroidUtilities.runOnUIThread(() -> {
                if (!current()) { done.accept(null); return; }
                MessagesController controller = MessagesController.getInstance(slot());
                if (chat != null && chat.id == channelId) controller.putChat(chat, true);
                PurpleSyncMagicCore.BoundState state = PurpleSyncMagicCore.BoundState.Unknown;
                PurpleSyncMagicCore.Candidate candidate = chat != null && chat.id == channelId ? PurpleSyncChatFacts.magic(chat, null) : null;
                if (chat instanceof TLRPC.TL_channelForbidden && chat.id == channelId) { state = PurpleSyncMagicCore.BoundState.Gone; candidate = null; }
                else if (candidate != null) state = PurpleSyncMagicCore.BoundState.Present;
                done.accept(new PurpleSyncMagicChat.Local(new ArrayList<>(), state, candidate, true, true));
            });
        });
    }

    @Override public void more(Runnable changed) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!current()) { changed.run(); return; }
            MessagesController controller = MessagesController.getInstance(slot());
            int folder = !controller.isServerDialogsEndReached(0) ? 0 : !controller.isServerDialogsEndReached(1) ? 1 : -1;
            if (folder < 0) { changed.run(); return; }
            NotificationCenter center = NotificationCenter.getInstance(slot());
            AtomicBoolean answered = new AtomicBoolean();
            NotificationCenter.NotificationCenterDelegate[] observer = { null };
            Runnable landed = () -> {
                if (!answered.compareAndSet(false, true)) return;
                center.removeObserver(observer[0], NotificationCenter.dialogsNeedReload);
                changed.run();
            };
            observer[0] = (id, account, args) -> {
                if (id == NotificationCenter.dialogsNeedReload && (!current() || !controller.isLoadingDialogs(folder))) landed.run();
            };
            center.addObserver(observer[0], NotificationCenter.dialogsNeedReload);
            // WHY: the observer must not outlive a page that never lands; the lookup's own cap answers by then.
            AndroidUtilities.runOnUIThread(landed, PurpleSyncMagicChat.LISTS_CAP * 1000);
            // As DialogsActivity pages: from the cache until its end, then from the server; one page in flight.
            if (!controller.isLoadingDialogs(folder)) controller.loadDialogs(folder, -1, PAGE, !controller.isDialogsEndReached(folder));
        });
    }

    @Override public void fullInfo(long channelId, Consumer<PurpleSyncMagicChat.Reply<PurpleSyncMagicCore.Candidate>> done) {
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController controller = MessagesController.getInstance(slot());
            TLRPC.InputChannel input = controller.getChat(channelId) != null ? controller.getInputChannel(channelId) : null;
            if (input == null || input instanceof TLRPC.TL_inputChannelEmpty) { done.accept(PurpleSyncMagicChat.Reply.failed("CHANNEL_INVALID", 400)); return; }
            TLRPC.TL_channels_getFullChannel request = new TLRPC.TL_channels_getFullChannel();
            request.channel = input;
            send(request, reply -> {
                if (!(reply.value instanceof TLRPC.TL_messages_chatFull)) { done.accept(failed(reply)); return; }
                TLRPC.TL_messages_chatFull data = (TLRPC.TL_messages_chatFull) reply.value;
                controller.putUsers(data.users, false); controller.putChats(data.chats, false);
                TLRPC.Chat chat = null;
                for (TLRPC.Chat item : data.chats) if (item != null && item.id == channelId) chat = item;
                PurpleSyncMagicCore.Candidate candidate = PurpleSyncChatFacts.magic(chat, data.full_chat);
                done.accept(candidate != null ? PurpleSyncMagicChat.Reply.ok(candidate) : PurpleSyncMagicChat.Reply.failed("CHAT_FULL_INVALID", 400));
            });
        });
    }

    @Override public void create(Consumer<PurpleSyncMagicChat.Reply<PurpleSyncMagicCore.Candidate>> done) {
        TLRPC.TL_channels_createChannel request = new TLRPC.TL_channels_createChannel();
        request.megagroup = true; request.title = PurpleSyncMagicCore.TITLE; request.about = ABOUT;
        send(request, reply -> {
            if (!(reply.value instanceof TLRPC.Updates)) { done.accept(failed(reply)); return; }
            TLRPC.Updates updates = (TLRPC.Updates) reply.value;
            PurpleSyncMagicCore.Candidate candidate = null;
            for (TLRPC.Chat chat : updates.chats) {
                PurpleSyncMagicCore.Candidate item = chat != null && chat.megagroup && chat.creator ? PurpleSyncChatFacts.magic(chat, null) : null;
                if (item != null && PurpleSyncMagicCore.TITLE.equals(item.title)) candidate = item;
            }
            PurpleSyncMagicCore.Candidate chosen = candidate;
            applied(updates, () -> done.accept(chosen != null ? PurpleSyncMagicChat.Reply.ok(chosen) : PurpleSyncMagicChat.Reply.ok(null)));
        });
    }

    @Override public void archive(long channelId, Consumer<PurpleSyncMagicChat.Reply<Boolean>> done) {
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.InputPeer peer = MessagesController.getInstance(slot()).getInputPeer(-channelId);
            if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) { done.accept(PurpleSyncMagicChat.Reply.failed("PEER_ID_INVALID", 400)); return; }
            TLRPC.TL_folders_editPeerFolders request = new TLRPC.TL_folders_editPeerFolders();
            TLRPC.TL_inputFolderPeer folder = new TLRPC.TL_inputFolderPeer();
            folder.peer = peer; folder.folder_id = 1; request.folder_peers.add(folder);
            send(request, reply -> {
                if (!(reply.value instanceof TLRPC.Updates)) { done.accept(failed(reply)); return; }
                applied((TLRPC.Updates) reply.value, () -> done.accept(PurpleSyncMagicChat.Reply.ok(true)));
            });
        });
    }

    @Override public void mute(long channelId, Consumer<PurpleSyncMagicChat.Reply<Boolean>> done) {
        AndroidUtilities.runOnUIThread(() -> {
            TLRPC.InputPeer peer = MessagesController.getInstance(slot()).getInputPeer(-channelId);
            if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) { done.accept(PurpleSyncMagicChat.Reply.failed("PEER_ID_INVALID", 400)); return; }
            TL_account.updateNotifySettings request = new TL_account.updateNotifySettings();
            TLRPC.TL_inputNotifyPeer notify = new TLRPC.TL_inputNotifyPeer(); notify.peer = peer; request.peer = notify;
            request.settings = new TLRPC.TL_inputPeerNotifySettings(); request.settings.flags = 4; request.settings.mute_until = Integer.MAX_VALUE;
            send(request, reply -> {
                if (!(reply.value instanceof TLRPC.TL_boolTrue)) { done.accept(reply.value instanceof TLRPC.Bool ? PurpleSyncMagicChat.Reply.ok(false) : failed(reply)); return; }
                if (current()) {
                    long dialogId = -channelId;
                    MessagesController.getNotificationsSettings(slot()).edit().putInt("notify2_" + NotificationsController.getSharedPrefKey(dialogId, 0), 2).commit();
                    TLRPC.Dialog dialog = MessagesController.getInstance(slot()).dialogs_dict.get(dialogId);
                    if (dialog != null) { if (dialog.notify_settings == null) dialog.notify_settings = new TLRPC.TL_peerNotifySettings(); dialog.notify_settings.mute_until = Integer.MAX_VALUE; }
                    NotificationCenter.getInstance(slot()).postNotificationName(NotificationCenter.notificationsSettingsUpdated);
                }
                done.accept(PurpleSyncMagicChat.Reply.ok(true));
            });
        });
    }

    @Override public void diag(String event, PurpleDiagLogStore.Field... fields) {
        PurpleDiagLogStore.Field[] all = new PurpleDiagLogStore.Field[fields.length + 1];
        all[0] = PurpleDiagLogStore.slot(slot());
        System.arraycopy(fields, 0, all, 1, fields.length);
        PurpleDiagLog.event("sync_chat", event, all);
    }

    /** Applies the reply's Updates like upstream does, then continues on the UI thread. */
    private void applied(TLRPC.Updates updates, Runnable next) {
        Utilities.stageQueue.postRunnable(() -> {
            try { if (current()) MessagesController.getInstance(slot()).processUpdates(updates, false); }
            catch (RuntimeException | LinkageError ignored) { }
            AndroidUtilities.runOnUIThread(() -> {
                if (current()) { MessagesController controller = MessagesController.getInstance(slot()); controller.putUsers(updates.users, false); controller.putChats(updates.chats, false); }
                next.run();
            });
        });
    }

    private static <T> PurpleSyncMagicChat.Reply<T> failed(PurpleSyncMagicChat.Reply<?> reply) {
        return reply.timeout ? PurpleSyncMagicChat.Reply.timedOut() : PurpleSyncMagicChat.Reply.failed(reply.error, reply.code != 0 ? reply.code : 400);
    }

    /** One request with a timeout; the reply arrives on the UI thread, exactly once, and never after the account changed. */
    private void send(TLObject request, Consumer<PurpleSyncMagicChat.Reply<TLObject>> done) {
        AtomicBoolean answered = new AtomicBoolean();
        int[] token = { 0 };
        Runnable timeout = () -> {
            if (!answered.compareAndSet(false, true)) return;
            if (token[0] != 0) ConnectionsManager.getInstance(slot()).cancelRequest(token[0], true);
            done.accept(PurpleSyncMagicChat.Reply.timedOut());
        };
        AndroidUtilities.runOnUIThread(() -> {
            if (!current()) { if (answered.compareAndSet(false, true)) done.accept(PurpleSyncMagicChat.Reply.failed(null, -1)); return; }
            AndroidUtilities.runOnUIThread(timeout, TIMEOUT);
            token[0] = ConnectionsManager.getInstance(slot()).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!answered.compareAndSet(false, true)) return;
                AndroidUtilities.cancelRunOnUIThread(timeout);
                if (!current()) { done.accept(PurpleSyncMagicChat.Reply.failed(null, -1)); return; }
                done.accept(error != null ? PurpleSyncMagicChat.Reply.failed(error.text, error.code) : PurpleSyncMagicChat.Reply.ok(response));
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        });
    }
}
