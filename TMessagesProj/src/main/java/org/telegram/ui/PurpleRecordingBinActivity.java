/*
 * This is the source code of Purple Telegram for Android.
 *
 * The Recording bin (F10): partial and unsent recordings, newest first, with
 * play, send, save and delete. See docs/media-recording-design.md, "The
 * Recording bin".
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.purple.PurpleGate;
import org.telegram.messenger.purple.PurpleMediaSave;
import org.telegram.messenger.purple.PurpleRecordingEntry;
import org.telegram.messenger.purple.PurpleRecordingRegistry;
import org.telegram.messenger.purple.PurpleRecordingSend;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class PurpleRecordingBinActivity extends UniversalFragment {

    /** The action bar's Clear bin item. */
    static final int MENU_CLEAR = 0x70750510;
    /** Bin rows: item i has the id ITEM_FIRST + i. */
    static final int ITEM_FIRST = 0x70750600;
    private static final int ITEM_LAST = 0x707506ff;

    private final List<PurpleRecordingEntry> items = new ArrayList<>();
    private boolean loaded;
    private final Runnable changed = this::reload;
    private final java.util.Map<String, PurpleRecordingBinRow> rows = new java.util.HashMap<>();

    @Override
    public boolean onFragmentCreate() {
        PurpleRecordingRegistry.addListener(changed);
        reload();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        PurpleRecordingRegistry.removeListener(changed);
        stopPlayers();
        super.onFragmentDestroy();
    }

    @Override
    public View createView(android.content.Context context) {
        final View view = super.createView(context);
        actionBar.createMenu().addItem(MENU_CLEAR, R.drawable.msg_delete);
        actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CLEAR) {
                    confirmClear();
                }
            }
        });
        return view;
    }

    private void reload() {
        PurpleRecordingRegistry.bin(list -> {
            java.util.Set<String> present = new java.util.HashSet<>();
            for (PurpleRecordingEntry entry : list) present.add(entry.id);
            rows.entrySet().removeIf(row -> {
                if (present.contains(row.getKey())) return false;
                row.getValue().stop(); return true;
            });
            items.clear();
            items.addAll(list);
            loaded = true;
            if (listView != null) listView.adapter.update(true);
        });
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.PurpleRecordingBin);
    }

    @Override
    protected void fillItems(ArrayList<UItem> list, UniversalAdapter adapter) {
        if (!loaded) return;
        if (items.isEmpty()) {
            list.add(UItem.asShadow(getString(R.string.PurpleRecordingBinEmpty)));
            return;
        }
        for (int i = 0; i < items.size() && ITEM_FIRST + i <= ITEM_LAST; i++) {
            final PurpleRecordingEntry e = items.get(i);
            PurpleRecordingBinRow row = rows.get(e.id);
            if (row == null) {
                row = new PurpleRecordingBinRow(getContext(), getResourceProvider(), e, chatName(e), mark(e),
                        () -> showActions(e), () -> pauseOthers(e.id), () -> error(R.string.PurpleRecordingBinPlayFailed));
                rows.put(e.id, row);
            }
            list.add(UItem.asCustom(row, org.telegram.ui.Components.LayoutHelper.WRAP_CONTENT));
        }
        list.add(UItem.asShadow(getString(R.string.PurpleRecordingBinInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final int index = item.id - ITEM_FIRST;
        if (index < 0 || index >= items.size()) return;
        showActions(items.get(index));
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        onClick(item, view, position, x, y);
        return true;
    }

    // ---- What a row says ---------------------------------------------------------

    /**
     * The chat's name, "Hidden chat" when the running Work Mode preset hides
     * it (never its name), or "Unknown chat" for an orphan.
     */
    /** The recording's account is still the one in its slot (review P2-4). */
    private static boolean sameAccount(PurpleRecordingEntry e) {
        if (e.account < 0 || e.account >= UserConfig.MAX_ACCOUNT_COUNT) return false;
        final UserConfig config = UserConfig.getInstance(e.account);
        return e.sameAccount(config.isClientActivated(), config.getClientUserId());
    }

    private String chatName(PurpleRecordingEntry e) {
        if (!e.hasChat() || !sameAccount(e)) return getString(R.string.PurpleRecordingBinUnknownChat);
        if (PurpleGate.hiddenEverywhere(e.account, e.dialogId)) return getString(R.string.PurpleRecordingBinHiddenChat);
        final MessagesController controller = MessagesController.getInstance(e.account);
        String name = null;
        if (DialogObject.isEncryptedDialog(e.dialogId)) {
            final TLRPC.EncryptedChat chat = controller.getEncryptedChat(DialogObject.getEncryptedChatId(e.dialogId));
            final TLRPC.User user = chat == null ? null : controller.getUser(chat.user_id);
            name = user == null ? null : UserObject.getUserName(user);
        } else if (DialogObject.isUserDialog(e.dialogId)) {
            final TLRPC.User user = controller.getUser(e.dialogId);
            name = user == null ? null : UserObject.getUserName(user);
        } else {
            final TLRPC.Chat chat = controller.getChat(-e.dialogId);
            name = chat == null ? null : chat.title;
        }
        if (name == null) name = getString(R.string.PurpleRecordingBinUnknownChat);
        if (e.account != currentAccount && UserConfig.getActivatedAccountsCount() > 1) {
            final TLRPC.User self = UserConfig.getInstance(e.account).getCurrentUser();
            if (self != null) name = name + " (" + UserObject.getFirstName(self) + ")";
        }
        return name;
    }

    private static String mark(PurpleRecordingEntry e) {
        final String kind = getString(e.round() ? R.string.PurpleRecordingBinRound : R.string.PurpleRecordingBinVoice);
        final String state;
        if (PurpleRecordingEntry.UNRECOVERABLE.equals(e.state)) state = getString(R.string.PurpleRecordingBinUnrecoverable);
        else if (e.longOriginal) state = getString(R.string.PurpleLongRoundOriginalKept);
        else if (e.discarded) state = getString(R.string.PurpleRecordingBinDiscarded);
        else if (e.partial) state = getString(R.string.PurpleRecordingBinPartial);
        else if (PurpleRecordingEntry.RECOVERED.equals(e.state)) state = getString(R.string.PurpleRecordingBinRecovered);
        else state = getString(R.string.PurpleRecordingBinNotSent);
        final long when = e.startedAt != 0 ? e.startedAt : e.binnedAt * 1000;
        return kind + " · " + duration(e) + "\n" + LocaleController.formatDateAudio(when / 1000, true) + "\n" + state;
    }

    private static String duration(PurpleRecordingEntry e) {
        return AndroidUtilities.formatShortDuration((int) (e.durationMs / 1000));
    }

    // ---- Actions -------------------------------------------------------------

    private void showActions(PurpleRecordingEntry e) {
        final Activity activity = getParentActivity();
        if (activity == null) return;
        final boolean playable = !PurpleRecordingEntry.UNRECOVERABLE.equals(e.state);
        final boolean secret = e.hasChat() && DialogObject.isEncryptedDialog(e.dialogId);
        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        if (playable) {
            labels.add(getString(R.string.PurpleRecordingBinPlay));
            actions.add(() -> play(e));
        }
        if (playable && e.hasChat() && sameAccount(e) && !e.quickReply) {
            labels.add(getString(R.string.PurpleRecordingBinSendOriginal));
            actions.add(() -> sendOriginal(e));
        }
        if (playable && !secret && !e.once) {
            labels.add(getString(R.string.PurpleRecordingBinSendSaved));
            actions.add(() -> sendSaved(e));
            labels.add(getString(R.string.PurpleRecordingSendElsewhere));
            actions.add(() -> sendElsewhere(e));
        }
        if (!secret && !e.once) {
            labels.add(getString(R.string.PurpleRecordingBinSave));
            actions.add(() -> save(e));
        }
        labels.add(getString(R.string.Delete));
        actions.add(() -> confirmDelete(e));
        final AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        dialog.setTitle(chatName(e));
        dialog.setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run());
        showDialog(dialog.create());
    }

    private void play(PurpleRecordingEntry e) {
        PurpleRecordingBinRow row = rows.get(e.id);
        if (row != null) row.toggle();
    }

    private void pauseOthers(String id) {
        for (java.util.Map.Entry<String, PurpleRecordingBinRow> row : rows.entrySet()) {
            if (!row.getKey().equals(id)) row.getValue().pause();
        }
    }

    private void stopPlayers() {
        for (PurpleRecordingBinRow row : rows.values()) row.stop();
    }

    @Override
    public void onPause() { stopPlayers(); super.onPause(); }

    @Override
    public void onResume() { super.onResume(); org.telegram.messenger.purple.PurpleLongRoundOriginals.retryPending(); }

    private void sendSaved(PurpleRecordingEntry entry) {
        final long self = UserConfig.getInstance(currentAccount).getClientUserId();
        sendItem(entry, currentAccount, self, 0, 0, false);
    }

    /**
     * Sends to the recording's own chat and account. A chat the running preset
     * hides is opened through the stock route instead, so the preset's
     * chat-open policy applies as everywhere else.
     */
    private void sendOriginal(PurpleRecordingEntry e) {
        if (PurpleGate.hiddenEverywhere(e.account, e.dialogId)) {
            final Bundle args = new Bundle();
            if (DialogObject.isEncryptedDialog(e.dialogId)) {
                args.putInt("enc_id", DialogObject.getEncryptedChatId(e.dialogId));
            } else if (DialogObject.isUserDialog(e.dialogId)) {
                args.putLong("user_id", e.dialogId);
            } else {
                args.putLong("chat_id", -e.dialogId);
            }
            if (e.account == currentAccount) presentFragment(new ChatActivity(args));
            return;
        }
        sendItem(e, e.account, e.dialogId, e.topicId, e.replyId, true);
    }

    private void sendItem(PurpleRecordingEntry item, int account, long dialog, long topic, int reply, boolean original) {
        if (!PurpleLongRoundSend.fromBin(this, item, account, dialog, topic, reply, original, this::sentResult)) {
            PurpleRecordingSend.send(item, account, dialog, topic, reply, original, this::sentResult);
        }
    }

    private void sendElsewhere(PurpleRecordingEntry e) {
        final int account = currentAccount;
        final long user = UserConfig.getInstance(account).getClientUserId();
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("canSelectTopics", true);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.isEmpty()) return false;
            final MessagesStorage.TopicKey key = dids.get(0);
            fragment.finishFragment();
            if (!org.telegram.messenger.purple.PurpleRecordingSendAs.sameUser(account, user)) { sentResult(false); return true; }
            sendItem(e, account, key.dialogId, key.topicId, 0, false);
            return true;
        });
        presentFragment(picker);
    }

    private void sentResult(boolean ok) {
        if (!ok) error(R.string.PurpleRecordingBinSendFailed);
    }

    private void save(PurpleRecordingEntry e) {
        final Activity activity = getParentActivity();
        if (activity == null || e.media == null) return;
        final File file = new File(e.media);
        final String name = "recording_" + e.id + (e.round() ? ".mp4" : ".ogg");
        final String mime = e.round() ? "video/mp4" : "audio/ogg";
        new Thread(() -> PurpleMediaSave.saveFileToDownloads(activity, file, mime, name)).start();
    }

    private void confirmDelete(PurpleRecordingEntry e) {
        final Activity activity = getParentActivity();
        if (activity == null) return;
        final AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        dialog.setMessage(getString(R.string.PurpleRecordingBinDeleteConfirm));
        dialog.setPositiveButton(getString(R.string.Delete), (d, w) -> PurpleRecordingRegistry.delete(e.id));
        dialog.setNegativeButton(getString(R.string.Cancel), null);
        final AlertDialog shown = dialog.create();
        showDialog(shown);
        final View button = shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private void confirmClear() {
        final Activity activity = getParentActivity();
        if (activity == null || items.isEmpty()) return;
        final AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
        dialog.setTitle(getString(R.string.PurpleRecordingBinClear));
        dialog.setMessage(getString(R.string.PurpleRecordingBinClearConfirm));
        dialog.setPositiveButton(getString(R.string.Delete), (d, w) -> PurpleRecordingRegistry.clear());
        dialog.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(dialog.create());
    }

    private void error(int text) {
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createErrorBulletin(getString(text)).show();
        }
    }
}
