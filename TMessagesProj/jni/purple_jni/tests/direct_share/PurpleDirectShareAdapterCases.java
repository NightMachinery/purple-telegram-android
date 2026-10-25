package org.telegram.messenger.purple;

import android.content.Intent;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

public final class PurpleDirectShareAdapterCases {
    private static int checks;
    private static void check(boolean value, String why) { ++checks; if (!value) throw new AssertionError(why); }
    private static void hint(int slot, long peer) {
        TLRPC.TL_topPeer hint = new TLRPC.TL_topPeer(); hint.peer = new TLRPC.TL_peerUser(); hint.peer.user_id = peer;
        MediaDataController.getInstance(slot).hints.add(hint);
        TLRPC.TL_user user = new TLRPC.TL_user(); user.id = peer; user.first_name = "synthetic-" + slot + "-" + peer; user.last_name = "";
        MessagesController.getInstance(slot).users.put(peer, user);
    }
    private static void drain() {
        int rounds = 0;
        while (!AndroidUtilities.pending.isEmpty() || !Utilities.globalQueue.pending.isEmpty()) {
            check(++rounds < 10, "bounded coalescing reaches idle"); AndroidUtilities.drain(); Utilities.globalQueue.drain();
        }
    }
    private static void reload() { PurpleDirectShare.request(); drain(); }
    private static ShortcutInfoCompat shortcut(String id, long peer) {
        return new ShortcutInfoCompat.Builder(ApplicationLoader.applicationContext, id).setShortLabel("Synthetic conversation")
            .setLongLabel("Synthetic conversation").setIntent(new Intent().putExtra("userId", peer)).build();
    }
    public static void main(String[] args) {
        UserConfig.getInstance(0).active = true; UserConfig.getInstance(0).user = 11;
        UserConfig.getInstance(1).active = true; UserConfig.getInstance(1).user = 22; UserConfig.selectedAccount = 1;
        hint(0, 7); hint(0, 8); hint(1, 7); hint(1, 9);
        for (int i = 0; i < 100; ++i) PurpleDirectShare.request();
        check(AndroidUtilities.pending.size() == 1 && Utilities.globalQueue.pending.isEmpty(), "100 rebuilds coalesce to one UI snapshot");
        AndroidUtilities.drain(); check(Utilities.globalQueue.pending.size() == 1, "one bounded OS publication is queued"); Utilities.globalQueue.drain();
        check(ShortcutManagerCompat.targets.size() == 5 && ShortcutManagerCompat.adds == 1, "single publication retains both activated accounts");
        ShortcutInfoCompat a = ShortcutManagerCompat.targets.get("pdid_0-11_7"), b = ShortcutManagerCompat.targets.get("pdid_0-22_7");
        check(a != null && b != null && a.getIntent().getIntExtra("currentAccount", -1) == 0 && b.getIntent().getIntExtra("currentAccount", -1) == 1, "qualified IDs and intents distinguish identical peers");
        check(b.getRank() == 1 && a.getRank() == 2, "selected account leads each rank round");
        Intent share = new Intent().setAction(Intent.ACTION_SEND).setFlags(1).putExtra("payload", "synthetic content")
            .putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "pdid_0-11_7").putExtra("currentAccount", 1).putExtra("dialogId", 99L).putExtra("hash", "forged");
        Intent routed = PurpleDirectShare.routeShare(share);
        check(routed.getIntExtra("currentAccount", -1) == 0 && routed.getLongExtra("dialogId", 0) == 7, "share relay resolves the shortcut account before LaunchActivity");
        check(routed.getFlags() == share.getFlags() && routed.getStringExtra("payload").equals("synthetic content") && share.getIntExtra("currentAccount", -1) == 1, "relay copies shared content and grant flags without mutating the input");
        check(!routed.hasExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID) && routed.getStringExtra("hash").equals(org.telegram.messenger.SharedConfig.directShareHash), "only verified account and routing token reach the receiver");
        check(PurpleDirectShare.routeShare(new Intent().setAction("unsupported")) == null, "relay refuses unrelated exported actions");
        ShortcutManagerCompat.pin(a);
        Intent pinned = new Intent(ShortcutManagerCompat.pins.get(a.getId()).getIntent());
        Intent initialHandoff = new Intent(routed);
        check(PurpleDirectShare.currentIntent(pinned) && PurpleDirectShare.admitShortcut(ApplicationLoader.applicationContext, pinned), "fresh launcher target passes the receiver fence");
        check(PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext, routed), "fresh relay handoff passes final receiver admission");
        Intent batch = PurpleDirectShare.routeShare(new Intent().setAction(Intent.ACTION_SEND_MULTIPLE).putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, a.getId()));
        check(PurpleDirectShare.shareRecipient(batch) == 7 && PurpleDirectShare.shareRecipient(routed) == 7, "the common receiver consumes SEND and SEND_MULTIPLE recipients");
        check(PurpleDirectShare.shareRecipient(new Intent().setAction(Intent.ACTION_SEND_MULTIPLE)) == 0, "ordinary multiple share keeps the generic picker");
        UserConfig.getInstance(0).active = false;
        check(!PurpleDirectShare.currentIntent(pinned) && !PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext, routed), "logout revokes both launcher pin and relay handoff at final receiver");
        UserConfig.getInstance(0).active = true;
        ++UserConfig.getInstance(0).loginSession;
        check(!PurpleDirectShare.currentIntent(pinned) && !PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext, batch), "new login of the same user revokes old persisted intents without relying on memory lifetime");
        --UserConfig.getInstance(0).loginSession;
        check(PurpleDirectShare.currentIntent(pinned), "same-login restart evidence remains valid");
        Intent installedPin = PurpleDirectShare.bindShortcut(0, 7, new Intent().setAction("com.tmessages.openchat7").putExtra("userId", 7L));
        check(installedPin != null && PurpleDirectShare.currentIntent(installedPin), "explicit launcher pin construction carries the same fence");
        Intent forgedPeer = new Intent(installedPin).putExtra("userId", 8L);
        check(!PurpleDirectShare.currentIntent(forgedPeer), "the bound peer must match the receiver payload");
        check(PurpleDirectShare.bindShortcut(0, 8, new Intent().setAction("com.tmessages.openchat7").putExtra("userId", 7L)) == null, "pin publication refuses inconsistent peer metadata");
        UserConfig.getInstance(0).missingLogin = true;
        check(PurpleDirectShare.bindShortcut(0, 7, pinned) == null && !PurpleDirectShare.currentIntent(pinned), "missing persistent login identity refuses rather than guessing a session");
        UserConfig.getInstance(0).missingLogin = false;
        check(!PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext, new Intent().setAction(Intent.ACTION_SEND).putExtra("dialogId", 7L)), "an unbound old share recipient cannot select an account");
        int calls = ShortcutManagerCompat.calls(); reload(); check(ShortcutManagerCompat.calls() == calls, "adapter repeats are idempotent");
        org.telegram.messenger.SharedConfig.directShareHash = "rotated-synthetic-token"; reload();
        check(ShortcutManagerCompat.targets.get("pdid_0-11_7").getIntent().getStringExtra("hash").equals("rotated-synthetic-token"), "incremental updates refresh changed routing tokens");
        org.telegram.messenger.LocaleController.label = "Translated compose"; reload();
        check(ShortcutManagerCompat.targets.get("compose").getShortLabel().equals("Translated compose"), "compose locale changes update its existing identity");
        calls = ShortcutManagerCompat.calls(); reload(); check(ShortcutManagerCompat.calls() == calls, "token and locale updates become idempotent");
        PurpleDirectShare.request(); AndroidUtilities.drain(); calls = ShortcutManagerCompat.calls();
        ++PurpleGate.generation; PurpleGate.hidden.add("0:7"); PurpleDirectShare.request(); Utilities.globalQueue.drain();
        check(ShortcutManagerCompat.calls() == calls, "superseded queued Android publication makes no OS calls"); drain();
        check(!ShortcutManagerCompat.targets.containsKey("pdid_0-11_7") && ShortcutManagerCompat.targets.containsKey("pdid_0-22_7"), "Work Mode hides only A's matching peer");
        routed = PurpleDirectShare.routeShare(share);
        check(!routed.hasExtra("dialogId") && !routed.hasExtra("currentAccount") && !routed.hasExtra("hash"), "hidden or retired shortcut falls back to an unqualified picker");
        String conversation = PurpleDirectShare.conversationId(0, 777);
        check(conversation.equals("pndid_0-11_777"), "notification conversations are account-qualified");
        check(PurpleDirectShare.conversation(0, 777, shortcut(conversation, 777)), "visible notification target admitted by the shared owner"); drain();
        check(ShortcutManagerCompat.targets.get(conversation).getIntent().getIntExtra("currentAccount", -1) == 0, "notification shortcut routing carries its opening account");
        ++PurpleGate.generation; PurpleGate.hidden.add("0:777"); reload();
        check(!ShortcutManagerCompat.targets.containsKey(conversation), "existing hidden conversation removed immediately");
        calls = ShortcutManagerCompat.calls(); reload(); check(ShortcutManagerCompat.calls() == calls, "hidden conversation removal is not repeated");
        ++PurpleGate.generation; PurpleGate.hidden.clear(); reload();
        check(ShortcutManagerCompat.targets.containsKey(conversation) && ShortcutManagerCompat.targets.containsKey("pdid_0-11_7"), "unhide restores retained conversation and direct hint without another notification");
        int adds = ShortcutManagerCompat.adds, removes = ShortcutManagerCompat.removes;
        java.util.Collections.swap(MediaDataController.getInstance(1).hints, 0, 1); reload();
        check(ShortcutManagerCompat.targets.get("pdid_0-22_9").getRank() == 2 || ShortcutManagerCompat.targets.get("pdid_0-22_9").getRank() == 1, "surviving hints follow changed rank order");
        check(ShortcutManagerCompat.removes == removes, "rank-only changes preserve shortcut identities");
        PurpleDirectShare.request(); AndroidUtilities.drain(); calls = ShortcutManagerCompat.calls(); UserConfig.selectedAccount = 0;
        Utilities.globalQueue.drain(); check(ShortcutManagerCompat.calls() == calls, "account switch revokes a queued publication even without another caller"); drain();
        check(ShortcutManagerCompat.targets.get("compose").getIntent().getIntExtra("currentAccount", -1) == 0, "revoked selection is recollected for the newly selected account");
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog(); dialog.id = 888; MessagesController.getInstance(0).dialogs_dict.put(888, dialog);
        ShortcutManagerCompat.targets.put("ndid_888", shortcut("ndid_888", 888)); ShortcutManagerCompat.limit = 8; reload();
        check(!ShortcutManagerCompat.targets.containsKey("ndid_888") && !ShortcutManagerCompat.targets.containsKey("pndid_0-11_888"), "a unique loaded cache does not prove a legacy descriptor owner");
        MessagesController.getInstance(0).dialogs_dict.put(999, dialog); MessagesController.getInstance(1).dialogs_dict.put(999, dialog);
        ShortcutManagerCompat.targets.put("ndid_999", shortcut("ndid_999", 999)); reload();
        check(!ShortcutManagerCompat.targets.containsKey("ndid_999") && !ShortcutManagerCompat.targets.containsKey("pndid_0-11_999") && !ShortcutManagerCompat.targets.containsKey("pndid_0-22_999"), "ambiguous legacy owner is removed without restoration");
        PurpleGate.hidden.add("0:777"); ++PurpleGate.generation; reload();
        ++UserConfig.getInstance(0).lifetime;
        PurpleGate.hidden.clear(); ++PurpleGate.generation;
        AndroidUtilities.immediate = true;
        check(PurpleDirectShare.conversation(0, 777, shortcut(conversation, 777)), "fresh same-account notification admitted after a new login");
        AndroidUtilities.immediate = false; drain();
        check(ShortcutManagerCompat.targets.containsKey(conversation), "older retained lifetime cannot remove a freshly published conversation");
        Intent conversationShare = new Intent().setAction(Intent.ACTION_SEND_MULTIPLE).putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, conversation);
        check(PurpleDirectShare.routeShare(conversationShare).getLongExtra("dialogId", 0) == 777, "qualified conversation share has a valid current token");
        PurpleDirectShare.removeConversation(0, 777, true); drain();
        check(!ShortcutManagerCompat.targets.containsKey(conversation) && ShortcutManagerCompat.targets.containsKey("pdid_0-22_7"), "uninstall removes only the qualified account conversation");
        reload(); check(!ShortcutManagerCompat.targets.containsKey(conversation), "uninstall cannot be undone by retained metadata");
        check(PurpleDirectShare.conversation(0, 777, shortcut(conversation, 777)), "a later notification may explicitly recreate an uninstalled descriptor"); drain();
        PurpleGate.hidden.add("0:777"); ++PurpleGate.generation; reload();
        UserConfig.getInstance(0).user = 33; ++UserConfig.getInstance(0).lifetime; MediaDataController.getInstance(0).hints.clear(); hint(0, 45);
        PurpleGate.hidden.clear(); ++PurpleGate.generation; reload();
        check(!ShortcutManagerCompat.targets.containsKey(conversation) && !ShortcutManagerCompat.targets.containsKey("pdid_0-11_7"), "slot reuse cannot restore the former account's retained targets");
        check(ShortcutManagerCompat.pins.containsKey(a.getId()), "synthetic launcher retains its pin after dynamic and long-lived removal");
        int toasts = android.widget.Toast.shown;
        check(!PurpleDirectShare.admitShortcut(ApplicationLoader.applicationContext, pinned) && !PurpleDirectShare.admitIncoming(ApplicationLoader.applicationContext, initialHandoff), "retained pin and already validated handoff cannot open replacement C");
        check(android.widget.Toast.shown == toasts + 2, "stale target refusal has a short user-visible explanation");
        check(ShortcutManagerCompat.targets.containsKey("pdid_0-33_45") && ShortcutManagerCompat.targets.containsKey("pdid_0-22_7"), "replacement account and unaffected B remain represented");
        ShortcutManagerCompat.targets.put("pdid_0-11_7", new ShortcutInfoCompat.Builder(a).setIntent(
            new Intent(a.getIntent()).putExtra("hash", org.telegram.messenger.SharedConfig.directShareHash)).build());
        routed = PurpleDirectShare.routeShare(share);
        check(!routed.hasExtra("dialogId") && !routed.hasExtra("currentAccount"), "slot reuse cannot route an old account-qualified target into its replacement");
        ShortcutManagerCompat.targets.remove("pdid_0-11_7");
        android.os.Build.VERSION.SDK_INT = 22; calls = ShortcutManagerCompat.calls(); PurpleDirectShare.request(); drain();
        check(ShortcutManagerCompat.calls() == calls, "unsupported API performs no publication");
        System.out.println("DIRECT_SHARE_ADAPTER_CHECKS=" + checks);
    }
}
