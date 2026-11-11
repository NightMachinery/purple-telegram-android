package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;
import android.os.Bundle;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleMediaReply;
import org.telegram.messenger.purple.PurpleMediaReplyPresentation;
import org.telegram.messenger.purple.PurpleWriter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import java.util.ArrayList;

public final class PurpleMediaReplySettingsActivity extends UniversalFragment {
    public static final int ENTRY = 0x70750800;
    private static final int TEXT = ENTRY + 1, ADD = ENTRY + 2, VOICE = ENTRY + 3,
            FOLLOW = ENTRY + 4, ROUND = ENTRY + 5, PEERS = ENTRY + 6, CLEAR = ENTRY + 7, RESET = ENTRY + 8, STATUS = ENTRY + 9;
    private final String row;
    private PurpleAccountFiles.Identity owner;
    private JSONObject policy, shown;
    public PurpleMediaReplySettingsActivity() { this(""); }
    private PurpleMediaReplySettingsActivity(String row) { this.row = row; }
    @Override protected CharSequence getTitle() { return getString(R.string.PurpleMediaReply); }
    private boolean live() { return owner != null && owner.current() && owner.slot == currentAccount; }
    @Override public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (owner == null) owner = PurpleAccountFiles.capture(currentAccount);
        policy = live() ? PurpleMediaReply.policy(currentAccount) : null;
        if (policy == null) { items.add(UItem.asShadow(getString(R.string.PurpleMediaReplyUnavailable))); return; }
        PurpleMediaReply.Status status = PurpleMediaReply.status(currentAccount);
        items.add(UItem.asButton(STATUS, getString(R.string.PurpleMediaReplyStatus)));
        items.add(UItem.asShadow(statusText(status)));
        if (row.isEmpty()) {
            items.add(UItem.asButton(TEXT, getString(R.string.PurpleMediaReplyText), policy.optString("text")));
            String[] types = {getString(R.string.PurpleMediaReplyPrivate), getString(R.string.PurpleMediaReplyGroups), getString(R.string.PurpleMediaReplyChannels)};
            for (int i = 0; i < types.length; ++i) items.add(UItem.asButton(ENTRY + 20 + i, types[i]));
            items.add(UItem.asShadow(getString(R.string.PurpleMediaReplyInfo)));
            items.add(UItem.asHeader(getString(R.string.PurpleMediaReplySets)));
            JSONArray sets = policy.optJSONArray("sets");
            if (sets != null) for (int i = 0; i < sets.length(); ++i) {
                JSONObject set = sets.optJSONObject(i);
                if (set != null) items.add(UItem.asButton(ENTRY + 100 + i, (i + 1) + ". " + set.optString("name")));
            }
            items.add(UItem.asButton(ADD, getString(R.string.PurpleMediaReplyAdd)));
            items.add(UItem.asShadow(getString(R.string.PurpleMediaReplyPriority)));
            return;
        }
        shown = null;
        if (row.startsWith("sets.")) {
            JSONArray sets = policy.optJSONArray("sets");
            if (sets != null) for (int i = 0; i < sets.length(); ++i) {
                JSONObject set = sets.optJSONObject(i);
                if (set != null && row.substring(5).equals(set.optString("name"))) shown = set;
            }
        } else {
            int index = row.equals("private") ? 0 : row.equals("groups") ? 1 : 2;
            shown = policy.optJSONArray("defaults").optJSONObject(index);
        }
        if (shown == null) return;
        items.add(UItem.asButton(VOICE, getString(R.string.PurpleMediaReplyVoice), shown.isNull("voice") && row.startsWith("sets.") ? getString(R.string.PurpleMediaReplyInherit) : mode(shown.optInt("voice", 0))));
        boolean inheritedRound = row.startsWith("sets.") && shown.isNull("round_follow_voice");
        UItem follow = inheritedRound ? UItem.asButton(FOLLOW, getString(R.string.PurpleMediaReplyFollow),
                getString(R.string.PurpleMediaReplyInherit)) : UItem.asCheck(FOLLOW, getString(R.string.PurpleMediaReplyFollow));
        follow.checked = shown.optBoolean("round_follow_voice", true);
        items.add(follow);
        int roundSelection = PurpleMediaReplyPresentation.modeSelection(shown, "round", row.startsWith("sets."));
        boolean disabled = PurpleMediaReplyPresentation.roundDisabled(policy, shown, row.startsWith("sets."));
        items.add(UItem.asButton(ROUND, getString(R.string.PurpleMediaReplyRound), disabled
                ? getString(R.string.PurpleMediaReplyFollowing) : roundSelection == 4
                ? getString(R.string.PurpleMediaReplyInherit) : mode(roundSelection)).setEnabled(!disabled));
        if (row.startsWith("sets.")) {
            JSONArray defaults = policy.optJSONArray("defaults");
            String[] types = {getString(R.string.PurpleMediaReplyPrivate), getString(R.string.PurpleMediaReplyGroups), getString(R.string.PurpleMediaReplyChannels)};
            StringBuilder summary = new StringBuilder(getString(R.string.PurpleMediaReplyDefaults));
            for (int i = 0; i < 3; ++i) {
                JSONObject value = defaults.optJSONObject(i);
                summary.append("\n").append(types[i]).append(": ").append(mode(value.optInt("effective_voice")))
                        .append(" / ").append(mode(value.optInt("effective_round"))).append("; ")
                        .append(getString(value.optBoolean("effective_follow") ? R.string.PurpleMediaReplyFollowing : R.string.PurpleMediaReplyIndependent));
            }
            items.add(UItem.asShadow(summary.toString()));
            JSONArray peers = shown.optJSONArray("peers");
            items.add(UItem.asButton(PEERS, getString(R.string.PurpleMediaReplySelect), String.valueOf(peers == null ? 0 : peers.length())));
            items.add(UItem.asButton(CLEAR, getString(R.string.PurpleMediaReplyClear)));
            items.add(UItem.asButton(RESET, getString(R.string.PurpleMediaReplyInherit)));
            if (peers != null) for (int i = 0; i < peers.length(); ++i) {
                String token = peers.optString(i);
                String[] parts = token.split(":", 2);
                String label = getString(R.string.PurpleMediaReplyUnavailableChat);
                try {
                    long id = Long.parseLong(parts[1]);
                    int kind = parts[0].equals("user") ? 0 : parts[0].equals("chat") ? 1 : -1;
                    if (parts[0].equals("user")) {
                        org.telegram.tgnet.TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(id);
                        if (user != null) label = org.telegram.messenger.UserObject.getUserName(user);
                    } else {
                        org.telegram.tgnet.TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(id);
                        if (chat != null) { label = chat.title; kind = ChatObject.isChannel(chat) && !chat.megagroup ? 2 : 1; }
                    }
                    JSONObject effective = kind < 0 ? null : PurpleMediaReply.resolved(currentAccount, token, kind);
                    if (effective != null) label += ": " + mode(effective.optInt("voice")) + " / " + mode(effective.optInt("round"));
                } catch (Exception e) { }
                items.add(UItem.asButton(ENTRY + 1000 + i, label));
            }
            items.add(UItem.asShadow(getString(R.string.PurpleMediaReplyPriority)));
        }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!live() || policy == null) return;
        if (item.id == STATUS) {
            AlertDialog.Builder notice = new AlertDialog.Builder(getParentActivity());
            notice.setTitle(getString(R.string.PurpleMediaReplyStatus));
            notice.setMessage(statusText(PurpleMediaReply.status(currentAccount)) + "\n\n" + getString(R.string.PurpleMediaReplyStatusInfo));
            notice.setPositiveButton(getString(R.string.OK), null); showDialog(notice.create());
        } else if (item.id == TEXT) edit(getString(R.string.PurpleMediaReplyText), policy.optString("text"), text -> write("", "text", text));
        else if (item.id == ADD) edit(getString(R.string.PurpleMediaReplyName), "", name -> {
            if (name.trim().isEmpty() || name.length() > 128) { error(getString(R.string.PurpleMediaReplyNameInfo)); return; }
            JSONArray sets = policy.optJSONArray("sets");
            if (sets != null) for (int i = 0; i < sets.length(); ++i) {
                if (name.equals(sets.optJSONObject(i).optString("name"))) { error(getString(R.string.PurpleMediaReplyNameInfo)); return; }
            }
            if (write("sets." + name, "peers", new JSONArray())) open("sets." + name);
        });
        else if (item.id >= ENTRY + 20 && item.id < ENTRY + 23) open(new String[]{"private", "groups", "channels"}[item.id - ENTRY - 20]);
        else if (item.id >= ENTRY + 1000 && shown != null) {
            JSONArray peers = shown.optJSONArray("peers"), next = new JSONArray();
            if (peers != null) for (int i = 0; i < peers.length(); ++i) if (i != item.id - ENTRY - 1000) next.put(peers.optString(i));
            write(row, "peers", next);
        } else if (item.id >= ENTRY + 100 && row.isEmpty()) {
            JSONObject set = policy.optJSONArray("sets").optJSONObject(item.id - ENTRY - 100);
            if (set != null) open("sets." + set.optString("name"));
        } else if (item.id == VOICE || item.id == ROUND) {
            if (item.id == ROUND && PurpleMediaReplyPresentation.roundDisabled(policy, shown, row.startsWith("sets."))) return;
            String[] options = row.startsWith("sets.")
                    ? new String[]{mode(0), mode(1), mode(2), mode(3), getString(R.string.PurpleMediaReplyInherit)}
                    : new String[]{mode(0), mode(1), mode(2), mode(3)};
            String key = item.id == VOICE ? "voice" : "round";
            showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(), options,
                    getString(item.id == VOICE ? R.string.PurpleMediaReplyVoice : R.string.PurpleMediaReplyRound),
                    PurpleMediaReplyPresentation.modeSelection(shown, key, row.startsWith("sets.")),
                    (d, which) -> write(row, key, which == 4 ? JSONObject.NULL : new String[]{"off", "own", "received", "both"}[which])));
        } else if (item.id == FOLLOW) {
            if (!row.startsWith("sets.")) write(row, "round_follow_voice", !item.checked);
            else showDialog(AlertsCreator.createSingleChoiceDialog(getParentActivity(),
                    new String[]{getString(R.string.PurpleMediaReplyFollowing), getString(R.string.PurpleMediaReplyIndependent), getString(R.string.PurpleMediaReplyInherit)},
                    getString(R.string.PurpleMediaReplyFollow), PurpleMediaReplyPresentation.followSelection(shown, true),
                    (d, which) -> write(row, "round_follow_voice", which == 2 ? JSONObject.NULL : which == 0)));
        }
        else if (item.id == CLEAR) write(row, "peers", new JSONArray());
        else if (item.id == RESET) {
            if (write(row, "voice", JSONObject.NULL)
                    && write(row, "round", JSONObject.NULL)) write(row, "round_follow_voice", JSONObject.NULL);
        } else if (item.id == PEERS) select();
    }
    private void open(String row) {
        PurpleMediaReplySettingsActivity next = new PurpleMediaReplySettingsActivity(row);
        next.setCurrentAccount(currentAccount);
        presentFragment(next);
    }
    private void select() {
        final PurpleAccountFiles.Identity openingOwner = owner;
        final String openingPolicy = policy.toString();
        final JSONArray openingPeers = new JSONArray();
        JSONArray members = shown.optJSONArray("peers");
        if (members != null) for (int i = 0; i < members.length(); ++i) openingPeers.put(members.optString(i));
        Bundle args = new Bundle(); args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("allowSwitchAccount", false);
        DialogsActivity picker = new DialogsActivity(args); picker.setCurrentAccount(openingOwner.slot);
        picker.setDelegate((fragment, dids, message, param, notify, schedule, repeat, topics) -> {
            if (!openingOwner.current() || fragment.getCurrentAccount() != openingOwner.slot || dids == null || topics != null) return false;
            JSONArray peers = new JSONArray();
            for (int i = 0; i < openingPeers.length(); ++i) peers.put(openingPeers.optString(i));
            for (org.telegram.messenger.MessagesStorage.TopicKey selected : dids) {
                long did = selected.dialogId;
                if (DialogObject.isEncryptedDialog(did) || DialogObject.isFolderDialogId(did)) return false;
                if (did > 0) peers.put("user:" + did);
                else {
                    org.telegram.tgnet.TLRPC.Chat chat = MessagesController.getInstance(openingOwner.slot).getChat(-did);
                    if (chat == null) return false;
                    peers.put((ChatObject.isChannel(chat) ? "channel:" : "chat:") + (-did));
                }
            }
            if (writeFrom(fragment, openingOwner, openingPolicy, row, "peers", peers)) fragment.finishFragment();
            return true;
        });
        presentFragment(picker);
    }
    private interface Edited { void done(String text); }
    private void edit(String title, String value, Edited callback) {
        if (getParentActivity() == null) return;
        EditTextBoldCursor input = new EditTextBoldCursor(getParentActivity());
        input.setText(value); input.setSingleLine(true); input.setTextSize(18);
        input.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        input.setCursorColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        input.setPadding(org.telegram.messenger.AndroidUtilities.dp(24), 0, org.telegram.messenger.AndroidUtilities.dp(24), 0);
        AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity());
        dialog.setTitle(title); dialog.setView(input);
        dialog.setPositiveButton(getString(R.string.Save), (d, which) -> { if (live()) callback.done(input.getText().toString()); });
        dialog.setNegativeButton(getString(R.string.Cancel), null); showDialog(dialog.create());
    }
    private boolean write(String row, String key, Object value) {
        return live() && writeFrom(this, owner, null, row, key, value);
    }
    private boolean writeFrom(BaseFragment origin, PurpleAccountFiles.Identity openingOwner,
            String expectedPolicy, String row, String key, Object value) {
        if (!openingOwner.current() || origin.getCurrentAccount() != openingOwner.slot) return false;
        PurpleWriter.Session session = PurpleWriterUI.captureMediaReplyOrExplain(origin, this::rebuild);
        if (session == null) return false;
        final JSONObject capturedPolicy = session.mediaReplyPolicy();
        session.guardBeforePublish(() -> openingOwner.current() && capturedPolicy != null
                && (expectedPolicy == null || expectedPolicy.equals(capturedPolicy.toString())));
        String error = PurpleWriter.setMediaReplyValue(session, row, key, value);
        if (error != null) BulletinFactory.of(origin).createErrorBulletin(error).show();
        rebuild(); return error == null;
    }
    private static String statusText(PurpleMediaReply.Status status) {
        int reason;
        switch (status.reason) {
            case JournalFull: reason = R.string.PurpleMediaReplyJournalFull; break;
            case JournalUnreadable: reason = R.string.PurpleMediaReplyJournalUnreadable; break;
            case StorageRefused: reason = R.string.PurpleMediaReplyStorageRefused; break;
            case Permission: reason = R.string.PurpleMediaReplyPermission; break;
            case Flood: reason = R.string.PurpleMediaReplyFlood; break;
            case ApprovalRequired: reason = R.string.PurpleMediaReplyApproval; break;
            case Uncertain: reason = R.string.PurpleMediaReplyUncertain; break;
            case Cancelled: reason = R.string.PurpleMediaReplyCancelled; break;
            default: reason = status.disabled ? R.string.PurpleMediaReplyDisabled
                    : status.inFlight > 0 ? R.string.PurpleMediaReplyInFlight : R.string.PurpleMediaReplyReady;
        }
        return (status.disabled && status.reason != PurpleMediaReply.Reason.None ? getString(R.string.PurpleMediaReplyDisabled) + "\n" : "")
                + getString(reason) + "\n" + org.telegram.messenger.LocaleController.formatString(R.string.PurpleMediaReplyCapacity,
                status.reserved < 0 ? getString(R.string.PurpleMediaReplyNotRead) : String.valueOf(status.reserved), status.inFlight);
    }
    @Override public void onResume() { super.onResume(); rebuild(); }
    private void rebuild() { if (listView != null && listView.adapter != null) listView.adapter.update(true); }
    private void error(String error) { BulletinFactory.of(this).createErrorBulletin(error).show(); }
    private static String mode(int value) {
        int[] ids = {R.string.PurpleMediaReplyOff, R.string.PurpleMediaReplyOwn, R.string.PurpleMediaReplyReceived, R.string.PurpleMediaReplyBoth};
        return getString(ids[Math.max(0, Math.min(3, value))]);
    }
    @Override public void onFragmentDestroy() {
        PurpleWriterUI.retire(this);
        super.onFragmentDestroy();
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
}
