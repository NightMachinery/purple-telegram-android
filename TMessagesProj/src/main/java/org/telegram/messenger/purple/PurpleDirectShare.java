package org.telegram.messenger.purple;

import android.content.Intent;
import android.content.Context;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.PersistableBundle;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.OpenChatReceiver;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

public final class PurpleDirectShare {
    private static final String FORWARDED = "purple.shortcut.forwarded";
    private static final String ACCOUNT = "purple.shortcut.account";
    private static final String LOGIN = "purple.shortcut.login";
    private static final String PEER = "purple.shortcut.peer";
    private static final String OWNER = "purple.directShare.owner";
    private static final String SIGNATURE = "purple.directShare.signature";
    private static final PurpleDirectSharePublisher<Value> publisher = new PurpleDirectSharePublisher<>();
    private static boolean pending;
    private static final class Value {
        final String name;
        final Intent intent;
        final File photo;
        final boolean share;
        final ShortcutInfoCompat existing;
        Value(String name, Intent intent, File photo, boolean share, ShortcutInfoCompat existing) {
            this.name = name; this.intent = intent; this.photo = photo; this.share = share; this.existing = existing;
        }
    }
    public static boolean request() {
        if (Build.VERSION.SDK_INT < 23) return true;
        synchronized (publisher) {
            publisher.invalidate();
            if (!pending) { pending = true; AndroidUtilities.runOnUIThread(PurpleDirectShare::collect); }
        }
        return true;
    }
    private static boolean hidden(int slot, long peer) {
        return !SharedConfig.passcodeHash.isEmpty() || PurpleGate.hiddenEverywhere(slot, peer) || PurpleGate.hiddenFromSuggestions(slot, peer);
    }
    private static String id(PurpleAccountFiles.Identity identity, long peer, boolean conversation) {
        return (conversation ? "pndid_" : "pdid_") + identity.key + "_" + peer;
    }
    private static String login(PurpleAccountFiles.Identity identity) {
        try { return identity == null || !identity.current() ? null : PurpleArchiveLogin.context(identity.slot); }
        catch (Exception e) { return null; }
    }
    public static Intent bindShortcut(int slot, long peer, Intent intent) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        String nonce = login(identity);
        if (intent == null || nonce == null || !identity.current() || routePeer(intent) != peer) return null;
        return new Intent(intent).putExtra("currentAccount", slot).putExtra(ACCOUNT, identity.key).putExtra(LOGIN, nonce).putExtra(PEER, peer);
    }
    private static long routePeer(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) return intent.getLongExtra("dialogId", 0);
        if ("new_dialog".equals(action)) return 0;
        if (action != null && action.startsWith("com.tmessages.openshortcutbot")) return intent.getLongExtra("botId", 0);
        long user = intent.getLongExtra("userId", 0), chat = intent.getLongExtra("chatId", 0);
        int encrypted = intent.getIntExtra("encId", 0);
        if ((user != 0 ? 1 : 0) + (chat != 0 ? 1 : 0) + (encrypted != 0 ? 1 : 0) != 1) return Long.MIN_VALUE;
        return encrypted != 0 ? DialogObject.makeEncryptedDialogId(encrypted) : user != 0 ? user : -chat;
    }
    private static boolean boundIntent(Intent intent) {
        if (intent == null) return false;
        try {
            int slot = intent.getIntExtra("currentAccount", -1);
            if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT || !intent.hasExtra(PEER)) return false;
            PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
            String nonce = login(identity);
            long peer = intent.getLongExtra(PEER, Long.MIN_VALUE);
            return nonce != null && identity.key.equals(intent.getStringExtra(ACCOUNT)) && nonce.equals(intent.getStringExtra(LOGIN))
                && peer != Long.MIN_VALUE && peer == routePeer(intent) && identity.current();
        } catch (RuntimeException e) { return false; }
    }
    public static boolean currentIntent(Intent intent) {
        return boundIntent(intent) && (intent.getLongExtra(PEER, 0) == 0 || !hidden(intent.getIntExtra("currentAccount", -1), intent.getLongExtra(PEER, 0))) && boundIntent(intent);
    }
    private static boolean refuse(Context context) {
        Toast.makeText(context, LocaleController.getString(R.string.PurpleShortcutUnavailable), Toast.LENGTH_SHORT).show();
        return false;
    }
    public static void forwardShortcut(Intent intent) { intent.putExtra(FORWARDED, "1"); }
    public static boolean admitShortcut(Context context, Intent intent) {
        return currentIntent(intent) || refuse(context);
    }
    public static boolean admitIncoming(Context context, Intent intent) {
        if (intent == null) return true;
        boolean share = Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction());
        boolean routed = intent.hasExtra(FORWARDED) || intent.hasExtra(ACCOUNT) || intent.hasExtra(LOGIN) || intent.hasExtra(PEER)
            || share && (intent.hasExtra("dialogId") || intent.hasExtra("hash") || intent.hasExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID));
        return !routed || currentIntent(intent) || refuse(context);
    }
    public static long shareRecipient(Intent intent) {
        return intent != null && (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))
            && SharedConfig.directShare && SharedConfig.directShareHash != null && currentIntent(intent) && java.util.Objects.equals(SharedConfig.directShareHash, intent.getStringExtra("hash"))
            ? intent.getLongExtra(PEER, 0) : 0;
    }
    public static String conversationId(int slot, long peer) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        return identity == null ? "pndid_unavailable" : id(identity, peer, true);
    }
    public static boolean conversation(int slot, long peer, ShortcutInfoCompat target) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null || !target.getId().equals(id(identity, peer, true))) return false;
        synchronized (publisher) {
            try {
                if (!identity.current() || hidden(slot, peer)) return false;
                ensureHash();
                Intent intent = bindShortcut(slot, peer, new Intent(target.getIntent()).putExtra("dialogId", peer).putExtra("hash", SharedConfig.directShareHash));
                if (intent == null) return false;
                PersistableBundle extras = target.getExtras() == null ? new PersistableBundle() : new PersistableBundle(target.getExtras());
                extras.putString(OWNER, identity.key);
                ShortcutInfoCompat owned = new ShortcutInfoCompat.Builder(target).setIntent(intent).setExtras(extras).build();
                if (!identity.current() || hidden(slot, peer)) return false;
                boolean accepted = ShortcutManagerCompat.pushDynamicShortcut(ApplicationLoader.applicationContext, owned);
                if (accepted) publisher.supersede(target.getId());
                return accepted;
            } finally { request(); }
        }
    }

    public static void removeConversation(int slot, long peer, boolean conversation) {
        if (!conversation || Build.VERSION.SDK_INT < 23) return;
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        if (identity == null) return;
        synchronized (publisher) {
            try {
                if (!identity.current()) return;
                String id = id(identity, peer, true);
                ShortcutManagerCompat.removeDynamicShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(id));
                if (Build.VERSION.SDK_INT >= 30) ShortcutManagerCompat.removeLongLivedShortcuts(ApplicationLoader.applicationContext, Collections.singletonList(id));
                publisher.supersede(id);
            } catch (Exception e) { FileLog.e(e); }
            finally { request(); }
        }
    }
    public static Intent routeShare(Intent incoming) {
        if (incoming == null || !Intent.ACTION_SEND.equals(incoming.getAction()) && !Intent.ACTION_SEND_MULTIPLE.equals(incoming.getAction())) return null;
        Intent routed = new Intent(incoming);
        routed.removeExtra("currentAccount"); routed.removeExtra("dialogId"); routed.removeExtra("hash");
        routed.removeExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID);
        routed.removeExtra(ACCOUNT); routed.removeExtra(LOGIN); routed.removeExtra(PEER); routed.removeExtra(FORWARDED);
        String requested = incoming.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID);
        if (requested == null || !SharedConfig.directShare) return routed;
        try {
            for (ShortcutInfoCompat shortcut : ShortcutManagerCompat.getShortcuts(ApplicationLoader.applicationContext,
                    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC | ShortcutManagerCompat.FLAG_MATCH_CACHED)) {
                if (!requested.equals(shortcut.getId()) || !shortcut.isDynamic() && !shortcut.isCached()) continue;
                Intent target = shortcut.getIntent(); if (target == null) break;
                int slot = target.getIntExtra("currentAccount", -1);
                long peer = target.getLongExtra("dialogId", 0);
                if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT || peer == 0 || SharedConfig.directShareHash == null
                    || !SharedConfig.directShareHash.equals(target.getStringExtra("hash"))) break;
                PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
                if (identity == null || !currentIntent(target) || !requested.equals(id(identity, peer, false)) && !requested.equals(id(identity, peer, true))) break;
                int policy = PurpleGate.generation();
                if (!hidden(slot, peer) && identity.current() && policy == PurpleGate.generation()) {
                    routed.putExtra("currentAccount", slot).putExtra("dialogId", peer).putExtra("hash", SharedConfig.directShareHash)
                        .putExtra(ACCOUNT, target.getStringExtra(ACCOUNT)).putExtra(LOGIN, target.getStringExtra(LOGIN)).putExtra(PEER, peer);
                }
                break;
            }
        } catch (Exception e) { FileLog.e(e); }
        return routed;
    }
    private static PurpleDirectSharePublisher.Target<Value> target(String id, String signature, Value value, int rank, boolean conversation, boolean visible, boolean current) {
        return new PurpleDirectSharePublisher.Target<>(id, signature, value, rank, conversation, visible, current);
    }
    private static String fence(PurpleAccountFiles.Identity identity) { return identity.key + ":" + identity.slot + ":" + identity.lifetime; }
    private static void collect() {
        final long ticket = publisher.generation();
        final int policy = PurpleGate.generation(), selected = UserConfig.selectedAccount;
        final ArrayList<PurpleAccountFiles.Identity> identities = new ArrayList<>();
        final HashMap<String, String> logins = new HashMap<>();
        final ArrayList<List<PurpleDirectSharePublisher.Target<Value>>> accounts = new ArrayList<>();
        final ArrayList<PurpleDirectSharePublisher.Target<Value>> conversations = new ArrayList<>();
        PurpleDirectSharePublisher.Target<Value> compose = null;
        try {
            for (int step = 0; step < UserConfig.MAX_ACCOUNT_COUNT; ++step) {
                int slot = step == 0 ? selected : step <= selected ? step - 1 : step;
                PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
                if (identity == null) continue;
                identities.add(identity);
                String nonce = login(identity);
                logins.put(identity.key, nonce);
                Intent composeIntent = bindShortcut(slot, 0, new Intent(ApplicationLoader.applicationContext, LaunchActivity.class).setAction("new_dialog"));
                if (compose == null && composeIntent != null) compose = target("compose", identity.key + ":" + nonce + ":" + slot + ":" + LocaleController.getString(R.string.NewConversationShortcut),
                    new Value(LocaleController.getString(R.string.NewConversationShortcut), composeIntent, null, false, null), 0, false, true, true);
                ArrayList<PurpleDirectSharePublisher.Target<Value>> candidates = new ArrayList<>();
                if (SharedConfig.passcodeHash.isEmpty()) for (TLRPC.TL_topPeer hint : PurpleHints.visibleHints(slot)) {
                    if (candidates.size() == 64) break;
                    long peer = DialogObject.getPeerDialogId(hint.peer);
                    PurpleDirectSharePublisher.Target<Value> candidate = peer(identity, peer);
                    if (candidate != null) candidates.add(candidate);
                }
                accounts.add(candidates);
            }
            LinkedHashMap<String, ShortcutInfoCompat> known = new LinkedHashMap<>();
            for (ShortcutInfoCompat shortcut : ShortcutManagerCompat.getShortcuts(ApplicationLoader.applicationContext,
                    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC | ShortcutManagerCompat.FLAG_MATCH_CACHED | ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
                if (Build.VERSION.SDK_INT >= 25 && !shortcut.isDynamic() && !shortcut.isCached()) continue;
                known.put(shortcut.getId(), shortcut);
            }
            HashSet<String> observed = new HashSet<>(known.keySet());
            HashMap<String, PurpleDirectSharePublisher.Target<Value>> saved = new HashMap<>();
            for (PurpleDirectSharePublisher.Target<Value> retained : publisher.retained()) {
                saved.put(retained.id, retained); known.putIfAbsent(retained.id, retained.value.existing);
            }
            for (ShortcutInfoCompat shortcut : known.values()) {
                PurpleDirectSharePublisher.Target<Value> value = classify(shortcut, identities);
                if (value != null) {
                    if (!value.id.equals(shortcut.getId())) conversations.add(target(shortcut.getId(), "legacy",
                        new Value("", null, null, false, shortcut), shortcut.getRank(), true, false, false));
                    PurpleDirectSharePublisher.Target<Value> prior = saved.get(value.id);
                    if (prior != null && !prior.signature.equals(value.signature)) value = value.current(false);
                    conversations.add(value.observed(observed.contains(value.id)));
                }
            }
            final int limit = Math.max(0, ShortcutManagerCompat.getMaxShortcutCountPerActivity(ApplicationLoader.applicationContext));
            final PurpleDirectSharePublisher.Target<Value> launch = compose;
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    java.util.function.BooleanSupplier current = () -> {
                        if (policy != PurpleGate.generation() || selected != UserConfig.selectedAccount) return false;
                        int active = 0;
                        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; ++slot) if (UserConfig.getInstance(slot).isClientActivated()) ++active;
                        if (active != identities.size()) return false;
                        for (PurpleAccountFiles.Identity identity : identities) if (!identity.current() || !java.util.Objects.equals(logins.get(identity.key), login(identity))) return false;
                        return true;
                    };
                    publisher.publish(ticket, accounts, launch, conversations, limit, new AndroidPort(ticket, current), current);
                } catch (Exception e) { FileLog.e(e); }
                finally {
                    if (selected != UserConfig.selectedAccount || policy != PurpleGate.generation()) request();
                    finish(ticket);
                }
            });
        } catch (Exception e) { FileLog.e(e); finish(ticket); }
    }
    private static void finish(long ticket) {
        synchronized (publisher) {
            pending = false;
            if (ticket != publisher.generation()) { pending = true; AndroidUtilities.runOnUIThread(PurpleDirectShare::collect); }
        }
    }
    private static PurpleDirectSharePublisher.Target<Value> peer(PurpleAccountFiles.Identity identity, long peer) {
        if (hidden(identity.slot, peer)) return null;
        MessagesController controller = MessagesController.getInstance(identity.slot);
        TLRPC.User user = peer > 0 ? controller.getUser(peer) : null;
        TLRPC.Chat chat = peer < 0 ? controller.getChat(-peer) : null;
        if ((user == null || UserObject.isDeleted(user)) && chat == null) return null;
        String name = user != null ? ContactsController.formatName(user.first_name, user.last_name) : chat.title;
        if (name == null || name.isEmpty()) name = " ";
        TLRPC.FileLocation photo = user != null ? user.photo == null ? null : user.photo.photo_small : chat.photo == null ? null : chat.photo.photo_small;
        File file = photo == null ? null : FileLoader.getInstance(identity.slot).getPathToAttach(photo, true);
        Intent intent = new Intent(ApplicationLoader.applicationContext, OpenChatReceiver.class).setAction("com.tmessages.openchat" + identity.key + "_" + peer)
            .putExtra(peer > 0 ? "userId" : "chatId", peer > 0 ? peer : -peer).putExtra("currentAccount", identity.slot).putExtra("dialogId", peer)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent = bindShortcut(identity.slot, peer, intent);
        if (intent == null) return null;
        String signature = name + ":" + identity.slot + ":" + intent.getStringExtra(LOGIN) + ":" + SharedConfig.directShare + ":" + (file == null ? "" : file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified());
        return target(id(identity, peer, false), signature, new Value(name, intent, file, SharedConfig.directShare, null), 0, false, true, true);
    }
    private static PurpleDirectSharePublisher.Target<Value> classify(ShortcutInfoCompat shortcut, List<PurpleAccountFiles.Identity> identities) {
        if (shortcut == null) return null;
        String id = shortcut.getId(); boolean owned = id.startsWith("pndid_"), legacy = id.startsWith("ndid_");
        if (!owned && !legacy) return null;
        if (legacy) return target(id, "unproven legacy owner", new Value("", null, null, false, shortcut), shortcut.getRank(), true, false, false);
        PurpleAccountFiles.Identity owner = null; long peer;
        try {
            peer = Long.parseLong(id.substring(id.lastIndexOf('_') + 1));
            for (PurpleAccountFiles.Identity identity : identities) if (id.equals(id(identity, peer, true))) owner = identity;
        } catch (RuntimeException e) { return target(id, "unreadable", new Value("", null, null, false, shortcut), shortcut.getRank(), true, false, false); }
        boolean current = owner != null && boundIntent(shortcut.getIntent());
        String stored = shortcut.getExtras() == null ? null : shortcut.getExtras().getString(OWNER);
        if (current && stored != null && !stored.equals(owner.key)) current = false;
        Value value = new Value("", null, null, false, shortcut);
        return target(id, owner == null ? "unknown" : fence(owner), value, shortcut.getRank(), true, current && !hidden(owner.slot, peer), current);
    }
    private static void ensureHash() {
        if (SharedConfig.directShareHash == null) {
            SharedConfig.directShareHash = UUID.randomUUID().toString();
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", 0).edit().putString("directShareHash2", SharedConfig.directShareHash).commit();
        }
    }
    private static ShortcutInfoCompat build(PurpleDirectSharePublisher.Target<Value> target) {
        Value value = target.value;
        if (value.existing != null) return new ShortcutInfoCompat.Builder(value.existing).setIntent(
            new Intent(value.existing.getIntent()).putExtra("hash", SharedConfig.directShareHash)).build();
        Intent intent = new Intent(value.intent).putExtra("hash", SharedConfig.directShareHash);
        PersistableBundle extras = new PersistableBundle(); extras.putString(SIGNATURE, target.signature);
        ShortcutInfoCompat.Builder builder = new ShortcutInfoCompat.Builder(ApplicationLoader.applicationContext, target.id)
            .setShortLabel(value.name).setLongLabel(value.name).setIntent(intent).setRank(target.rank).setExtras(extras);
        if (value.share) builder.setCategories(Collections.singleton(MediaDataController.SHORTCUT_CATEGORY));
        Bitmap bitmap = icon(value.photo);
        builder.setIcon(bitmap != null ? IconCompat.createWithBitmap(bitmap) : IconCompat.createWithResource(ApplicationLoader.applicationContext,
            target.id.equals("compose") ? R.drawable.shortcut_compose : R.drawable.shortcut_user));
        return builder.build();
    }
    private static Bitmap icon(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.toString(), options);
            int size = AndroidUtilities.dp(48); options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / size); options.inJustDecodeBounds = false;
            Bitmap source = BitmapFactory.decodeFile(file.toString(), options); if (source == null) return null;
            Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(result);
            Path clip = new Path(); clip.addCircle(size / 2f, size / 2f, size / 2f - AndroidUtilities.dp(2), Path.Direction.CW); canvas.clipPath(clip);
            canvas.drawBitmap(source, null, new RectF(0, 0, size, size), null); canvas.setBitmap(null); return result;
        } catch (Exception e) { return null; }
    }
    private static final class AndroidPort implements PurpleDirectSharePublisher.Port<Value> {
        private final long ticket;
        private final java.util.function.BooleanSupplier current;
        AndroidPort(long ticket, java.util.function.BooleanSupplier current) { this.ticket = ticket; this.current = current; }
        private void check() { if (publisher.generation() != ticket || !current.getAsBoolean()) throw new IllegalStateException("Shortcut publication superseded"); }
        public List<PurpleDirectSharePublisher.Target<Value>> current() {
            ArrayList<PurpleDirectSharePublisher.Target<Value>> result = new ArrayList<>();
            for (ShortcutInfoCompat shortcut : ShortcutManagerCompat.getShortcuts(ApplicationLoader.applicationContext,
                    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC | ShortcutManagerCompat.FLAG_MATCH_CACHED | ShortcutManagerCompat.FLAG_MATCH_PINNED)) {
                if (Build.VERSION.SDK_INT >= 25 && !shortcut.isDynamic() && !shortcut.isCached()) continue;
                PersistableBundle extras = shortcut.getExtras(); String signature = extras == null ? "" : extras.getString(SIGNATURE, "");
                if (PurpleDirectSharePublisher.direct(shortcut.getId()) && (shortcut.getIntent() == null
                    || !java.util.Objects.equals(shortcut.getIntent().getStringExtra("hash"), SharedConfig.directShareHash))) signature = "stale-token:" + signature;
                result.add(target(shortcut.getId(), signature, new Value("", null, null, false, shortcut), shortcut.getRank(), false, true, true).dynamic(Build.VERSION.SDK_INT < 25 || shortcut.isDynamic()));
            }
            return result;
        }
        public void remove(List<String> ids, List<String> longLived) {
            check();
            if (!ids.isEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(ApplicationLoader.applicationContext, ids);
            check();
            if (Build.VERSION.SDK_INT >= 30 && !longLived.isEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(ApplicationLoader.applicationContext, longLived);
        }
        public void add(List<PurpleDirectSharePublisher.Target<Value>> values) { apply(values, false); }
        public void update(List<PurpleDirectSharePublisher.Target<Value>> values) { apply(values, true); }
        private void apply(List<PurpleDirectSharePublisher.Target<Value>> values, boolean update) {
            ensureHash();
            ArrayList<ShortcutInfoCompat> targets = new ArrayList<>(); for (PurpleDirectSharePublisher.Target<Value> value : values) targets.add(build(value));
            check();
            boolean accepted = update ? ShortcutManagerCompat.updateShortcuts(ApplicationLoader.applicationContext, targets) : ShortcutManagerCompat.addDynamicShortcuts(ApplicationLoader.applicationContext, targets);
            if (!accepted) throw new IllegalStateException("Shortcut publication refused");
        }
    }
    private PurpleDirectShare() {}
}
