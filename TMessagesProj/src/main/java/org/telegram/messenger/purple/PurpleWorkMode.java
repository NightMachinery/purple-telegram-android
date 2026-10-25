package org.telegram.messenger.purple;

import androidx.collection.LongSparseArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import java.io.File;
import java.util.ArrayList;

public final class PurpleWorkMode {
    static final class View {
        final PurpleAccountFiles.Identity identity;
        final int mode;
        final long handle;
        final LongSparseArray<Integer> modes = new LongSparseArray<>();
        final LongSparseArray<Integer> stories = new LongSparseArray<>();
        volatile ArrayList<MessagesController.DialogFilter> filters = new ArrayList<>();
        volatile boolean pinsIncomplete;
        View(PurpleAccountFiles.Identity identity, int mode, long handle) {
            this.identity = identity; this.mode = mode; this.handle = handle;
        }
    }
    private static final class Generation {
        final View[] views;
        final long role;
        Generation(View[] views, long role) { this.views = views; this.role = role; }
        long[] handles() {
            long[] result = new long[views.length];
            for (int i = 0; i < views.length; ++i) result[i] = views[i] == null ? 0 : views[i].handle;
            return result;
        }
    }
    private static final PurpleWorkModeModel.Publication<Generation> publication =
            new PurpleWorkModeModel.Publication<>(new Generation(new View[UserConfig.MAX_ACCOUNT_COUNT], -1));
    static void withdrawPeek() {
        synchronized (PurpleAccountFiles.class) {
            publication.read(generation -> {
                PurpleWorkModeModel.withdrawPeekNative();
                for (View view : generation.views) {
                    if (view == null) continue;
                    view.modes.clear(); view.stories.clear();
                }
                return null;
            });
        }
    }
    private static byte[] acceptedLocal;
    private static long acceptedLocalRole = -1;
    private static View view(Generation generation, int account) {
        View result = account < 0 || account >= generation.views.length ? null : generation.views[account];
        return result != null && generation.role == PurpleAccountOwner.observedRoleEpoch() && result.identity.matchesSlot() ? result : null;
    }
    static View view(int account) {
        synchronized (PurpleAccountFiles.class) { return publication.read(generation -> view(generation, account)); }
    }
    public static int mode(int account) { View view = view(account); return view == null ? 2 : view.mode; }
    public static boolean stock(int account) { return mode(account) == 2; }
    static void reload(byte[] acceptedGlobal, File globalFile, PurpleAccountFileSnapshot global, PurpleAccountFileSnapshot good) {
        synchronized (PurpleAccountOwner.class) { reloadLocked(acceptedGlobal, globalFile, global, good); }
    }
    private static void reloadLocked(byte[] acceptedGlobal, File globalFile, PurpleAccountFileSnapshot global, PurpleAccountFileSnapshot good) {
        View[] next = new View[UserConfig.MAX_ACCOUNT_COUNT];
        ArrayList<Long> created = new ArrayList<>();
        ArrayList<java.util.function.BooleanSupplier> checks = new ArrayList<>();
        long started = PurpleAccountOwner.observedRevision();
        long startedRole = PurpleAccountOwner.observedRoleEpoch();
        try {
            File localFile = PurpleAccountFiles.file("local.toml");
            PurpleAccountFileSnapshot local = PurpleAccountFileSnapshot.read(localFile);
            File goodFile = new File(globalFile.getPath() + ".good");
            byte[] localBytes = PurpleWorkModeModel.localForReload(local.bytes,
                    local.status == PurpleAccountFileSnapshot.Status.Missing,
                    acceptedLocalRole == startedRole ? acceptedLocal : null, PurpleAccountOwner.global());
            checks.add(() -> started == PurpleAccountOwner.observedRevision()
                    && startedRole == PurpleAccountOwner.observedRoleEpoch() && local.sameRead(localFile)
                    && global.sameRead(globalFile) && good.sameRead(goodFile));
            for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
                checks.add(id::current);
                PurpleAccountFiles files = PurpleAccountFiles.resolve(id.key, PurpleAccountOwner.owner());
                if (files == null) {
                    if (!PurpleAccountOwner.ready()) next[id.slot] = new View(id, 0, -1);
                    else throw new IllegalStateException("missing account route");
                    continue;
                }
                PurpleAccountFileSnapshot own = PurpleAccountFileSnapshot.read(files.settings);
                PurpleAccountFileSnapshot ownGood = PurpleAccountFileSnapshot.read(files.good);
                checks.add(() -> own.sameRead(files.settings) && ownGood.sameRead(files.good));
                JSONObject result = new JSONObject(PurpleWorkModeModel.createNative(localBytes, id.key,
                        PurpleAccountOwner.global(), acceptedGlobal, globalFile.getPath(), null,
                        own.bytes, files.settings.getPath(), ownGood.bytes));
                long handle = Long.parseLong(result.getString("handle"));
                created.add(handle);
                View added = new View(id, result.getInt("mode"), handle);
                View old = view(id.slot);
                if (old != null && old.identity.key.equals(id.key) && old.identity.lifetime == id.lifetime) added.filters = old.filters;
                next[id.slot] = added;
            }
            final boolean published;
            synchronized (PurpleAccountFiles.class) {
                published = publication.publish(new Generation(next, startedRole), () -> {
                    for (java.util.function.BooleanSupplier check : checks) if (!check.getAsBoolean()) return false;
                    return true;
                }, previous -> PurpleWorkModeModel.retireNative(previous.handles()));
            }
            if (published) {
                acceptedLocal = localBytes; acceptedLocalRole = startedRole;
                return;
            }
        } catch (Exception | UnsatisfiedLinkError e) { }
        long[] abandoned = new long[created.size()];
        for (int i = 0; i < abandoned.length; ++i) abandoned[i] = created.get(i);
        try { PurpleWorkModeModel.retireNative(abandoned); } catch (UnsatisfiedLinkError e) { }
    }
    public static int visible(int account, long peer, int kind) {
        synchronized (PurpleAccountFiles.class) {
            return publication.read(generation -> {
                View view = view(generation, account);
                return view == null || view.mode == 2 ? PurpleCore.SHOW_ALWAYS | PurpleCore.NOTIFY_BIT
                        : PurpleWorkModeModel.visibleNative(view.handle, peer, kind);
            });
        }
    }
    public static int unpeeked(int account, long peer, int kind) {
        synchronized (PurpleAccountFiles.class) {
            return publication.read(generation -> {
                View view = view(generation, account);
                return view == null || view.mode == 2 ? PurpleCore.SHOW_ALWAYS | PurpleCore.NOTIFY_BIT
                        : PurpleWorkModeModel.visibleUnpeekedNative(view.handle, peer, kind);
            });
        }
    }
    public static String decider(int account, long peer, int kind) {
        synchronized (PurpleAccountFiles.class) {
            return publication.read(generation -> {
                View view = view(generation, account);
                return view == null || view.mode == 2 ? null : PurpleWorkModeModel.deciderNative(view.handle, peer, kind);
            });
        }
    }
    static boolean story(int account, long peer, int kind, boolean unseen, int folder, boolean exempt) {
        synchronized (PurpleAccountFiles.class) {
            return publication.read(generation -> {
                View view = view(generation, account);
                return view == null || view.mode == 2 || PurpleWorkModeModel.storyShownNative(view.handle, peer, kind, unseen, folder, exempt);
            });
        }
    }
    private PurpleWorkMode() { }
}
