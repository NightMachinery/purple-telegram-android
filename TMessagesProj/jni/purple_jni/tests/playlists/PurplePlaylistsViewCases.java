package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PurplePlaylistsViewCases {
    private static int checks;
    private static final String EMPTY = "{\"format\":1,\"playlists\":{},\"purged\":{}}";
    private static final String HIGH = "18446744073709551615";
    private static void check(boolean value, String name) { ++checks; if (!value) throw new AssertionError(name); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject json(byte[] value) throws Exception { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
    private static final class Model implements AutoCloseable {
        final PurplePlaylistsEdits editor = new PurplePlaylistsEdits(101);
        byte[] library = bytes(EMPTY), state = PurplePlaylistsLocalCore.defaults(101).canonicalBytes();
        String apply(PurplePlaylistsEdits.Command command) {
            PurplePlaylistsEdits.Result result = editor.prepare(library, state, command);
            check(result.valid(), "shared model setup " + command.kind + ":" + result.error);
            editor.finish(true, true, false); library = result.library(); state = result.state(); return result.playlistId;
        }
        String create(String title, int seed) {
            byte[] entropy = new byte[10]; Arrays.fill(entropy, (byte) seed);
            return apply(PurplePlaylistsEdits.Command.create(title, entropy, 100));
        }
        PurplePlaylistsView view(String id) { return PurplePlaylistsView.project(library, state, 101, id); }
        @Override public void close() { editor.close(); }
    }
    private static void refusals() {
        byte[] defaults = PurplePlaylistsLocalCore.defaults(101).canonicalBytes();
        for (byte[] bad : new byte[][] {null, bytes("bad"), bytes("null"), bytes("{\"format\":2}")}) {
            PurplePlaylistsView result = PurplePlaylistsView.project(bad, defaults, 101, "");
            check(!result.valid() && result.playlists == null && result.deleted == null && result.detail == null,
                    "invalid library is not an empty view");
            check(result.input == PurplePlaylistsView.Input.Library, "library refusal provenance");
        }
        PurplePlaylistsView newer = PurplePlaylistsView.project(bytes("{\"format\":2}"), defaults, 101, "");
        check(newer.status == PurplePlaylistsView.Status.NewerFormat, "newer library status retained");
        for (byte[] bad : new byte[][] {null, bytes("bad"), bytes("{\"format\":2}")}) {
            PurplePlaylistsView result = PurplePlaylistsView.project(bytes(EMPTY), bad, 101, "");
            check(!result.valid() && result.playlists == null && result.detail == null, "invalid local state grants no rows");
            check(result.input == PurplePlaylistsView.Input.Local, "local refusal provenance");
        }
        check(PurplePlaylistsView.project(bytes(EMPTY), bytes("{\"format\":2}"), 101, "").status
                == PurplePlaylistsView.Status.NewerFormat, "newer local status retained");
        check("InvalidSelfUserId".equals(PurplePlaylistsView.project(bytes(EMPTY), defaults, 0, "").error), "zero captured identity refused");
        boolean rejected = false;
        try { PurplePlaylistsView.project(bytes(EMPTY), defaults, 101, "\uD800"); }
        catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "selection rejects unpaired UTF16");
    }
    private static void projections() throws Exception {
        try (Model m = new Model()) {
            PurplePlaylistsView empty = m.view("");
            check(empty.valid() && empty.playlists.isEmpty() && empty.deleted.isEmpty() && empty.detail == null, "proved empty model has empty rows");
            String z = m.create("Zulu", 1), a = m.create("alpha", 2), music = m.create("موسيقى 🎵", 3);
            PurplePlaylistsView view = m.view(music);
            check(view.playlists.get(0).id.equals(a) && view.playlists.get(1).id.equals(z), "core case-folded library ordering");
            check(view.detail.row.title.equals("موسيقى 🎵") && view.selectedId.equals(music), "Unicode detail selection preserved");
            m.apply(PurplePlaylistsEdits.Command.add(z, new PurplePlaylistsEdits.Song[] {
                new PurplePlaylistsEdits.Song(HIGH, "user:101", 7, "", "曲 🎵", "演奏者", 23),
                new PurplePlaylistsEdits.Song("7", "channel:200", 9, "channelname", "Second", "Artist", 40) }, 200));
            m.apply(PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.ReorderSongs, z, new String[] {"7", HIGH}, 300));
            view = m.view(z);
            check(view.playlists.get(0).id.equals(z) && view.playlists.get(0).recentUseMs == 200, "recent use orders shared list before titles");
            check(view.detail.songs.size() == 2 && view.detail.row.songCount == 2, "detail/count use visible song model");
            check(view.detail.songs.get(0).documentId.equals("7") && view.detail.songs.get(1).documentId.equals(HIGH), "core saved song order and unsigned IDs");
            check(view.playlists.get(0).coverSong.documentId.equals("7") && view.playlists.get(0).coverSong.source.messageId == 9
                    && view.detail.row.coverSong.documentId.equals("7"),
                    "playlist cover uses the first actual song in saved order");
            check(view.playlists.get(1).coverSong == null, "empty playlist has no remote artwork source");
            PurplePlaylistsView.Song song = view.detail.songs.get(1);
            check(song.title.equals("曲 🎵") && song.performer.equals("演奏者") && song.duration == 23, "UTF8 metadata projected without modified UTF8");
            check(song.source.peer.equals("self") && song.source.messageId == 7, "source uses captured self normalization");
            check(view.detail.songs.get(0).source.username.equals("channelname"), "source username retained");
            check(view.unsyncedSinceMs == 100 && view.syncEnabled && view.syncResult.equals("Idle"), "typed local sync bookkeeping projected");
            boolean immutable = false;
            try { view.detail.songs.clear(); } catch (UnsupportedOperationException e) { immutable = true; }
            check(immutable, "projected song list is immutable");
            immutable = false;
            try { view.playlists.clear(); } catch (UnsupportedOperationException e) { immutable = true; }
            check(immutable, "projected library list is immutable");
            m.apply(PurplePlaylistsEdits.Command.songs(PurplePlaylistsEdits.Kind.RemoveSongs, z, new String[] {HIGH}, 400));
            check(m.view(z).detail.songs.size() == 1, "song tombstone omitted");
            m.apply(PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.Delete, z, 500));
            view = m.view(z);
            check(view.playlists.size() == 2 && view.deleted.size() == 1 && view.deleted.get(0).id.equals(z), "deleted playlist separated from visible core list");
            check(view.detail.row.deleted && view.detail.songs.size() == 1, "deleted detail remains inspectable for restore");
            m.apply(PurplePlaylistsEdits.Command.playlist(PurplePlaylistsEdits.Kind.DeleteForever, z, 600));
            check(m.view(z).detail == null && m.view(z).deleted.isEmpty(), "purged playlist never grants detail");
            JSONObject raw = json(m.library); raw.getJSONObject("playlists").getJSONObject(a).remove("title");
            m.library = bytes(raw.toString()); view = m.view(a);
            check(view.incompleteCount == 1 && view.playlists.size() == 1, "partial unknown title is explicit, not invented visible row");
            check(view.detail != null && view.detail.row.partial && view.detail.row.title.isEmpty(), "partial detail marked incomplete");
            check(m.view("pl-aaaaaaaaaaaaaaaa").detail == null, "missing selection never falls back to first playlist");
            byte[] padded = new byte[25 * 1024 * 1024]; Arrays.fill(padded, (byte) ' ');
            byte[] original = m.library.clone(); System.arraycopy(original, 0, padded, 0, original.length);
            check(PurplePlaylistsView.project(padded, m.state, 101, music).valid(), "projection preserves uncapped read policy");
            check(Arrays.equals(original, m.library), "projection leaves caller library unchanged");
        }
    }
    private static void directory(Path path) throws Exception {
        Files.createDirectory(path); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    private static void write(Path path, byte[] value) throws Exception {
        Files.write(path, value); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
    private static final class Ui implements PurplePlaylistsScreen.Listener {
        final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        final List<PurplePlaylistsScreen.State> states = new ArrayList<>();
        int refusals; Runnable afterState;
        @Override public void downloadRefused() { ++refusals; }
        @Override public void playlistsChanged(PurplePlaylistsScreen.State state) {
            states.add(state); Runnable action = afterState; afterState = null;
            if (action != null) action.run();
        }
        Runnable take() throws Exception {
            Runnable result = queue.poll(10, TimeUnit.SECONDS);
            if (result == null) throw new AssertionError("bounded screen completion");
            return result;
        }
        PurplePlaylistsScreen.State next() throws Exception { int before = states.size(); while (states.size() == before) take().run(); return states.get(states.size() - 1); }
    }
    private static org.telegram.messenger.MessageObject music(int account, int message, long document, String title) {
        org.telegram.tgnet.TLRPC.TL_message value = new org.telegram.tgnet.TLRPC.TL_message();
        value.id = message; value.peer_id = new org.telegram.tgnet.TLRPC.TL_peerUser(); value.peer_id.user_id = 101;
        value.media = new org.telegram.tgnet.TLRPC.TL_messageMediaDocument();
        value.media.document = new org.telegram.tgnet.TLRPC.TL_document(); value.media.document.id = document;
        org.telegram.tgnet.TLRPC.TL_documentAttributeAudio audio = new org.telegram.tgnet.TLRPC.TL_documentAttributeAudio();
        audio.title = title; audio.performer = "Artist 🎵"; audio.duration = 23;
        value.media.document.attributes.add(audio);
        return new org.telegram.messenger.MessageObject(account, value, false, false);
    }
    private static PurplePlaylistsAdd.Selection selection(org.telegram.messenger.MessageObject... messages) {
        return PurplePlaylistsAdd.capture(0, java.util.Arrays.asList(messages));
    }
    private static void captureRefusals() {
        org.telegram.messenger.purple.PurpleCopyPolicy.allowed = false;
        check(PurplePlaylistsAdd.capture(0, null) == null && selection() == null, "empty Add grants no selection");
        org.telegram.messenger.MessageObject value = music(0, 10, 99, "Music");
        check(selection(value) != null, "regular music can be captured");
        value.currentAccount = 1; check(selection(value) == null, "Add refuses another message account"); value.currentAccount = 0;
        value.eventId = 3; check(selection(value) == null, "event log cannot enter playlist Add"); value.eventId = 0;
        value.scheduled = true; check(selection(value) == null, "scheduled source cannot enter playlist Add"); value.scheduled = false;
        value.messageOwner.send_state = 1; check(selection(value) == null, "sending source cannot enter playlist Add"); value.messageOwner.send_state = 0;
        value.messageOwner.quick_reply_shortcut_id = 2; check(selection(value) == null, "quick reply cannot enter playlist Add"); value.messageOwner.quick_reply_shortcut_id = 0;
        value.messageOwner.media.ttl_seconds = 1; check(selection(value) == null, "expiring media cannot enter playlist Add"); value.messageOwner.media.ttl_seconds = 0;
        value.messageOwner.ttl = 1; check(selection(value) == null, "expiring message cannot enter playlist Add"); value.messageOwner.ttl = 0;
        value.messageOwner.destroyTime = 1; check(selection(value) == null, "self destruct source cannot enter playlist Add"); value.messageOwner.destroyTime = 0;
        value.messageOwner.destroyTimeMillis = 1; check(selection(value) == null, "millisecond self destruct cannot enter playlist Add"); value.messageOwner.destroyTimeMillis = 0;
        value.messageOwner.id = -1; check(selection(value) == null, "local source cannot enter playlist Add"); value.messageOwner.id = 10;
        value.messageOwner.peer_id.user_id = 0; check(selection(value) == null, "invalid peer cannot enter playlist Add"); value.messageOwner.peer_id.user_id = 101;
        value.getDocument().id = 0; check(selection(value) == null, "zero document cannot enter playlist Add"); value.getDocument().id = 99;
        value.getDocument().attributes.get(0).voice = true; check(selection(value) == null, "voice source cannot enter playlist Add"); value.getDocument().attributes.get(0).voice = false;
        value.getDocument().attributes.add(new org.telegram.tgnet.TLRPC.TL_documentAttributeVideo());
        check(selection(value) == null, "video source cannot enter playlist Add"); value.getDocument().attributes.remove(1);
        value.messageOwner.noforwards = true; check(selection(value) == null, "save policy blocks protected source");
        org.telegram.messenger.purple.PurpleCopyPolicy.allowed = true;
        PurplePlaylistsAdd.Selection protectedSelection = selection(value);
        check(protectedSelection != null, "existing unrestricted save policy allows protected source");
        org.telegram.messenger.purple.PurpleCopyPolicy.allowed = false;
        check(!protectedSelection.available(), "chooser rechecks changed save policy"); value.messageOwner.noforwards = false;
        org.telegram.tgnet.TLRPC.TL_channel chat = new org.telegram.tgnet.TLRPC.TL_channel(); chat.id = 200; chat.username = "musicfeed";
        value.messageOwner.peer_id = new org.telegram.tgnet.TLRPC.TL_peerChannel(); value.messageOwner.peer_id.channel_id = 200;
        check(selection(value) == null, "missing channel metadata cannot enter playlist Add");
        org.telegram.messenger.MessagesController.getInstance(0).chats.put(200L, chat);
        PurplePlaylistsAdd.Selection channelSelection = selection(value);
        check(channelSelection != null, "known channel source enters playlist Add");
        chat.min = true; check(!channelSelection.available() && selection(value) == null, "min channel retires chooser authority"); chat.min = false;
        chat.noforwards = true; check(!channelSelection.available() && selection(value) == null, "protected channel retires chooser authority"); chat.noforwards = false;
        org.telegram.tgnet.TLRPC.RestrictionReason reason = new org.telegram.tgnet.TLRPC.RestrictionReason(); reason.platform = "all";
        chat.restriction_reason.add(reason); check(!channelSelection.available() && selection(value) == null, "restricted channel retires chooser authority"); chat.restriction_reason.clear();
        value = music(0, 10, 99, "Music"); value.messageOwner.restriction_reason.add(reason);
        check(selection(value) == null, "message restriction cannot enter playlist Add"); value.messageOwner.restriction_reason.clear();
        value.getDocument().attributes.get(0).title = "\uD800";
        check(selection(value) == null, "malformed UTF16 metadata cannot crash the Add menu");
        value.getDocument().attributes.get(0).title = "Music";
        List<org.telegram.messenger.MessageObject> many = new ArrayList<>(); for (int i = 0; i < 101; ++i) many.add(value);
        check(PurplePlaylistsAdd.capture(0, many) == null, "Add has a bounded command selection");
        UserConfig.selectedAccount = 1; check(!channelSelection.available() && selection(value) == null, "chooser cannot follow selected account switch");
        UserConfig.selectedAccount = 0;
        org.telegram.messenger.purple.PurpleCopyPolicy.allowed = true;
    }
    private static void screen() throws Exception {
        Path files = Files.createTempDirectory("playlists-screen-");
        Files.setPosixFilePermissions(files, PosixFilePermissions.fromString("rwx------"));
        Path root = files.resolve("purple"); directory(root); directory(root.resolve("accounts"));
        write(root.resolve("local.toml"), bytes("root_file_account=\"0-101\"\nglobal_account=\"0-101\"\n"));
        write(root.resolve("accounts/root.toml"), bytes("root_file_account=\"0-101\"\n"));
        ApplicationLoader.files = files.toFile();
        PurpleAccountOwner.owner = "0-101"; PurpleAccountOwner.global = "0-101"; PurpleAccountOwner.ready = true;
        ++PurpleAccountOwner.revision; ++PurpleAccountOwner.role;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; ++i) {
            UserConfig config = new UserConfig(); config.active = i < 2; config.user = i == 0 ? 101 : 202;
            UserConfig.instances[i] = config; ConnectionsManager.instances[i] = new ConnectionsManager();
        }
        UserConfig.selectedAccount = 0; android.os.Looper.mainThread = Thread.currentThread();
        AtomicReference<Thread> storageThread = new AtomicReference<>();
        AtomicBoolean failLocal = new AtomicBoolean();
        PurplePlaylistsWorker worker = new PurplePlaylistsWorker(PurplePlaylistsAccount.capture(0), (captured, current) -> {
            storageThread.set(Thread.currentThread());
            PurplePlaylistsStore real = PurplePlaylistsAccount.open(captured, current);
            java.lang.reflect.Field field = PurplePlaylistsStore.class.getDeclaredField("physical");
            field.setAccessible(true);
            PurplePlaylistsStore.Physical physical = (PurplePlaylistsStore.Physical) field.get(real);
            return new PurplePlaylistsStore(captured.identity.userId, new PurplePlaylistsStore.Physical() {
                public boolean current() { return physical.current(); }
                public PurplePlaylistsStore.File read(PurplePlaylistsStore.Name name) throws Exception { return physical.read(name); }
                public boolean matches(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File expected) throws Exception { return physical.matches(name, expected); }
                public PurplePlaylistsStore.Publication confirm(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File expected, PurplePlaylistsStore.Current guard) throws Exception {
                    if (name == PurplePlaylistsStore.Name.Local && failLocal.get()) throw new java.io.IOException("synthetic local barrier failure");
                    return physical.confirm(name, expected, guard);
                }
                public PurplePlaylistsStore.Publication replace(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File expected, byte[] value, PurplePlaylistsStore.Current guard) throws Exception {
                    if (name == PurplePlaylistsStore.Name.Local && failLocal.get()) throw new java.io.IOException("synthetic local checkpoint failure");
                    return physical.replace(name, expected, value, guard);
                }
                public void close() throws Exception { physical.close(); }
            });
        });
        Ui ui = new Ui(); PurplePlaylistsScreen screen = new PurplePlaylistsScreen(0, ui, worker, ui.queue::add);
        try {
            check(screen.refresh(), "screen submits read without waiting on I/O");
            PurplePlaylistsScreen.State state = ui.next();
            check(state.ready() && state.view.playlists.isEmpty(), "screen gets typed actual-account projection");
            check(storageThread.get() != Thread.currentThread(), "screen native store work stays off main");
            check(screen.sync(), "screen admits captured manual sync");
            check(!screen.sync(), "screen prevents duplicate manual request");
            check(PurplePlaylistsSyncRuntime.manualCalls == 1, "screen delegates to captured runtime once");
            java.util.function.Consumer<PurplePlaylistsSyncResult> firstSync = PurplePlaylistsSyncRuntime.manualDone;
            firstSync.accept(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Wait, "SyntheticWait"));
            state = ui.next(); check(state.ready(), "manual completion refreshes actual store projection");
            PurplePlaylistsSyncRuntime.synchronous = true;
            check(screen.sync(), "synchronous refusal still admitted as completed request");
            state = ui.next(); check(state.ready(), "synchronous refusal cannot leave screen stuck");
            PurplePlaylistsSyncRuntime.synchronous = false;
            check(screen.sync(), "new sync admitted after refusal");
            java.util.function.Consumer<PurplePlaylistsSyncResult> duringEdit = PurplePlaylistsSyncRuntime.manualDone;
            check(screen.create("During sync"), "manual sync does not block queued local edits");
            duringEdit.accept(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Ready, "SyntheticReady"));
            state = ui.next(); check(state.ready() && state.libraryCommitted, "queued edit gets completion before sync refresh");
            state = ui.next(); check(state.ready() && state.view.playlists.size() == 1, "dirty refresh retains committed edit");
            String queuedId = state.view.playlists.get(0).id;
            check(screen.playlist(PurplePlaylistsScreen.PlaylistAction.Delete, queuedId), "delete isolated queued-edit setup"); ui.next();
            check(screen.playlist(PurplePlaylistsScreen.PlaylistAction.DeleteForever, queuedId), "remove isolated queued-edit setup");
            state = ui.next(); check(state.ready(), "queued edit setup removed");
            java.util.concurrent.CountDownLatch enteredSync = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseSync = new java.util.concurrent.CountDownLatch(1);
            check(worker.postStore(null, (store, self) -> {
                enteredSync.countDown();
                if (!releaseSync.await(5, TimeUnit.SECONDS)) throw new AssertionError("release actual sync write");
                PurplePlaylistsStore.Write changed = store.setSync(store.read(), false);
                worker.syncChanged(changed);
            }, () -> { throw new AssertionError("actual sync write refused"); }, false), "admit actual sync checkpoint before queued edit");
            check(enteredSync.await(5, TimeUnit.SECONDS), "actual sync write holds worker before queued edit");
            check(screen.create("Stale during sync"), "edit admitted behind actual sync write");
            releaseSync.countDown();
            state = ui.next(); check(state.status == PurplePlaylistsScreen.Status.Changed, "queued stale edit completes explicitly after actual sync invalidation");
            state = ui.next(); check(state.ready() && !state.view.syncEnabled
                    && state.view.playlists.stream().noneMatch(row -> row.title.equals("Stale during sync")),
                    "dirty screen refresh receives actual sync checkpoint without lost edit completion");
            check(screen.setSync(true), "restore automatic sync through actual screen worker");
            check(ui.next().view.syncEnabled, "automatic checkbox projection follows verified write");
            check(screen.create("Screen 🎵"), "screen creates typed playlist");
            check(!screen.create("Double"), "screen suppresses overlapping edits");
            state = ui.next(); String id = state.editedPlaylistId;
            check(state.ready() && state.libraryCommitted && state.stateCommitted && state.canUndo, "confirmed edit exposes owned Undo and honest flags");
            check(screen.select(id), "detail selection schedules actor read");
            state = ui.next(); check(state.view.detail.row.title.equals("Screen 🎵"), "screen detail projects selected actual playlist");
            check(state.keepPreference == PurplePlaylistsDownloadsCore.Preference.Inherit && Boolean.TRUE.equals(state.keepDefault),
                    "screen receives typed keep preference and effective default");
            check(screen.keepPreference(PurplePlaylistsDownloadsCore.Preference.Off), "screen admits typed keep choice on actor");
            state = ui.next(); check(state.ready() && state.keepPreference == PurplePlaylistsDownloadsCore.Preference.Off,
                    "screen changes keep label only after verified actor completion");
            PurplePlaylistsScreen.State beforeRemoval = state;
            check(screen.removeDownloads(state, false), "screen admits explicit download removal with current immutable state");
            state = ui.next();
            check(state.ready() && state.cleanup != null && state.cleanup.ready() && !state.cleanup.prune,
                    "screen reports explicit download cleanup separately from playlist mutation");
            check(!screen.removeDownloads(beforeRemoval, false), "stale removal confirmation cannot act on a new screen snapshot");
            check(screen.removeDownloads(state, true), "screen admits reference cleanup retry with current immutable state");
            state = ui.next();
            check(state.ready() && state.cleanup.prune, "reference cleanup retry preserves its operation kind");
            Path cleanupLocalFile=root.resolve("playlists/local.json");
            PurplePlaylistsDownloadsCore.Result pendingCleanup=PurplePlaylistsDownloadsCore.cleanup(Files.readAllBytes(cleanupLocalFile),101,id,true);
            check(pendingCleanup.ready(),"typed selected cleanup marker accepted");write(cleanupLocalFile,pendingCleanup.local());
            check(screen.refresh(),"screen refresh admits persisted cleanup read");state=ui.next();
            check(state.ready() && state.cleanup!=null && state.cleanup.status==PurplePlaylistsRemoval.Status.Pending && !state.cleanup.prune,
                    "screen refresh restores explicit Retry kind without destructive work");
            screen.close();screen=new PurplePlaylistsScreen(0,ui,worker,ui.queue::add);
            check(screen.select(id),"reopened screen selects original cleanup target");state=ui.next();
            check(state.cleanup!=null && !state.cleanup.prune,"reopened screen preserves selected cleanup indication");
            check(screen.removeDownloads(state,state.cleanup.prune),"pending cleanup retry uses current exact screen capability");state=ui.next();
            check(state.ready() && state.cleanup.ready(),"pending empty cleanup completes with confirmed marker clear");
            check(screen.refresh(),"screen refreshes confirmed cleanup");state=ui.next();
            check(state.cleanup==null,"confirmed marker clear removes Pending on refresh");
            pendingCleanup=PurplePlaylistsDownloadsCore.cleanup(Files.readAllBytes(cleanupLocalFile),101,null,true);write(cleanupLocalFile,pendingCleanup.local());
            check(screen.refresh(),"global prune indication refresh admitted");state=ui.next();
            check(state.cleanup!=null && state.cleanup.prune,"prune indication remains global and never becomes selected removal");
            check(screen.removeDownloads(state,true),"global prune Retry admitted");state=ui.next();
            check(state.ready() && state.cleanup.ready() && state.cleanup.prune,"global prune completes without replaying playlist edits");
            JSONObject markerState=new JSONObject(new String(Files.readAllBytes(cleanupLocalFile),StandardCharsets.UTF_8));
            JSONObject inventory=markerState.getJSONObject("android_playlist_downloads");
            inventory.put("cleanup",new JSONObject().put("format",2));write(cleanupLocalFile,bytes(markerState.toString()));
            check(screen.refresh(),"newer cleanup marker allows ordinary screen refresh");state=ui.next();
            check(state.ready() && state.downloadStatus==PurplePlaylistsDownloadsCore.Status.Newer && !screen.removeDownloads(state,false),
                    "newer cleanup subformat refuses download mutation while preserving ordinary library view");
            inventory.remove("cleanup");write(cleanupLocalFile,bytes(markerState.toString()));
            check(screen.refresh(),"restored supported cleanup format refreshes");state=ui.next();
            PurplePlaylistsDownloadControls.starts = 0;
            check(screen.download(null), "download read admission is distinct from eventual action acceptance");
            ui.next();
            check(ui.refusals == 1 && PurplePlaylistsDownloadControls.starts == 1,
                    "late download start refusal reaches the live screen listener");
            ui.afterState = () -> UserConfig.selectedAccount = 1;
            check(screen.download(null), "download read admitted before listener changes account"); ui.next();
            check(ui.refusals == 1 && PurplePlaylistsDownloadControls.starts == 1,
                    "account switch during state callback suppresses both start and refusal");
            UserConfig.selectedAccount = 0;
            check(screen.rename(id, ""), "invalid command can be refused by shared core");
            state = ui.next(); check(state.status == PurplePlaylistsScreen.Status.InvalidEdit && state.editError.equals("InvalidTitle") && state.canUndo,
                    "typed refusal keeps valid read and prior Undo");
            check(screen.rename(id, "Changed"), "valid edit after refusal admitted");
            state = ui.next(); check(state.view.detail.row.title.equals("Changed"), "edit refresh preserves selected detail");
            check(screen.undo(), "screen consumes owned Undo");
            state = ui.next(); check(state.ready() && state.view.detail.row.title.equals("Screen 🎵")
                    && !state.canUndo && state.canRedo && state.historyChanged && state.historyKind.equals("Rename"),
                    "screen Undo restores the shared record and exposes a labeled Redo");
            check(!screen.undo(), "consumed screen Undo refused");
            check(screen.redo(), "screen admits Redo for the same edit");
            state = ui.next();
            check(state.ready() && state.canUndo && !state.canRedo && state.view.detail.row.title.equals("Changed"),
                    "Redo reapplies the shared rename with a new Undo ticket");
            check(screen.undo(), "a redone edit can be undone again");
            state = ui.next();
            check(state.ready() && state.canRedo && state.view.detail.row.title.equals("Screen 🎵"),
                    "undoing the same rename again restores its original title");
            check(screen.copy(id), "screen copies current validated library entirely on actor");
            state = ui.next(); String copyId = state.editedPlaylistId;
            check(state.ready() && !copyId.equals(id) && state.view.playlists.size() == 2, "current-copy uses actual shared source with new identity");
            check(screen.undo(), "copy offers one owned Undo");
            state = ui.next(); check(state.ready() && state.canRedo && state.view.playlists.size() == 1
                    && state.view.deleted.stream().anyMatch(row -> row.id.equals(copyId)), "copy Undo hides only the new copy");
            captureRefusals();
            org.telegram.messenger.MessageObject first = music(0, 30, -1, "Original 🎵");
            org.telegram.messenger.MessageObject second = music(0, 31, 7, "Second");
            PurplePlaylistsAdd.Selection adding = selection(first, second, second, null);
            check(adding != null && adding.accepted == 2 && adding.skipped == 2, "Add counts duplicate and unavailable sources honestly");
            first.getDocument().id = 555; first.getDocument().attributes.get(0).title = "Mutated"; first.messageOwner.peer_id.user_id = 202;
            CountDownLatch backgroundRead = new CountDownLatch(1);
            check(worker.postStore(null, (store, self) -> {
                check(store.read().ready(), "background sync reads the existing library and state");
                backgroundRead.countDown();
            }, () -> { throw new AssertionError("read-only sync task refused"); }, false), "queue read-only background task");
            check(backgroundRead.await(5, TimeUnit.SECONDS), "read-only task completed before Add");
            check(screen.add(id, adding), "captured Add enters real actor edit after an independent read");
            check(adding.submitted() && !screen.add(id, adding), "accepted Add is consumed once before completion");
            state = ui.next();
            check(state.ready() && state.addOutcome == PurplePlaylistsScreen.AddOutcome.Saved
                    && state.added == 2 && state.relinked == 0 && state.canUndo,
                    "independent store read cannot refuse Add or lose its completion");
            check(state.view.detail.songs.get(0).documentId.equals(HIGH) && state.view.detail.songs.get(1).documentId.equals("7"), "captured Add preserves input saved order and unsigned document");
            check(state.view.detail.songs.get(0).title.equals("Original 🎵") && state.view.detail.songs.get(0).source.peer.equals("self")
                    && state.view.detail.songs.get(0).source.messageId == 30, "mutable source cannot rewrite captured Add metadata");
            check(!screen.add(id, adding), "completed Add cannot silently replay");
            org.telegram.messenger.MessageObject relink = music(0, 32, 7, "Second"); relink.messageOwner.peer_id.user_id = 202;
            check(screen.add(id, selection(relink)), "new source for an existing document enters actor");
            state = ui.next(); check(state.ready() && state.added == 0 && state.relinked == 1, "shared duplicate document relinks without duplicating song");
            check(screen.undo(), "relink offers actor-owned Undo"); state = ui.next();
            check(state.view.detail.songs.size() == 2 && state.view.detail.songs.get(1).source.peer.equals("self"), "relink Undo restores previous source");
            failLocal.set(true); PurplePlaylistsAdd.Selection partialAdd = selection(music(0, 40, 8, "Partial Add"));
            check(screen.add(id, partialAdd), "Add admitted before synthetic checkpoint failure"); state = ui.next();
            check(state.partial() && state.addOutcome == PurplePlaylistsScreen.AddOutcome.Partial
                    && state.libraryCommitted && !state.stateCommitted && state.added == 1 && state.canUndo,
                    "Add partial checkpoint truth retains actual counts and Undo");
            check(!screen.add(id, partialAdd), "partial Add cannot automatically replay"); failLocal.set(false);
            check(screen.undo(), "partial Add remains undoable"); state = ui.next();
            check(state.ready() && state.view.detail.songs.size() == 2, "partial Add Undo removes only the new song");
            PurplePlaylistsAdd.Selection wrong = selection(music(1, 50, 9, "Wrong"));
            check(wrong == null && !screen.add(id, wrong), "screen refuses missing or foreign-account selection");
            PurplePlaylistsAdd.Selection missing = selection(music(0, 51, 10, "Refused"));
            check(screen.add("missing", missing), "missing destination reaches typed refusal");
            state = ui.next();
            check(state.addOutcome == PurplePlaylistsScreen.AddOutcome.Refused && missing.available()
                    && state.view != null, "proved refusal leaves the readable library and selection available");
            check(screen.refresh(), "refresh admitted before account switch");
            Runnable stale = ui.take(); int before = ui.states.size(); UserConfig.selectedAccount = 1; stale.run();
            check(ui.states.size() == before, "late screen reply suppressed after selected-account switch");
            check(!screen.create("Wrong"), "screen cannot edit another selected account");
            UserConfig.selectedAccount = 0;
            check(screen.refresh(), "explicit refresh recovers canceled screen generation");
            Runnable older = ui.take(); check(screen.refresh(), "new refresh replaces pending read");
            Runnable newer = ui.take(); older.run();
            check(ui.states.size() == before, "replaced request cannot repaint old screen generation");
            newer.run(); check(ui.states.size() == before + 1, "latest screen request paints once");
            Path localFile = root.resolve("playlists/local.json");
            byte[] originalLocal = Files.readAllBytes(localFile);
            for (int format = 1; format <= 2; ++format) {
                JSONObject opaque = json(originalLocal);
                opaque.put("android_playlist_downloads", new JSONObject().put("format", format));
                write(localFile, bytes(opaque.toString()));
                check(screen.refresh(), "opaque inventory screen refresh admitted");
                state = ui.next();
                check(state.ready() && state.view.detail != null && state.keepPreference == null,
                        "opaque inventory leaves normal screen readable without keep choice");
                check(state.downloadStatus == (format == 1 ? PurplePlaylistsDownloadsCore.Status.Invalid : PurplePlaylistsDownloadsCore.Status.Newer),
                        "screen exposes truthful typed download status");
                check(!screen.keepPreference(PurplePlaylistsDownloadsCore.Preference.On) && !screen.download(null),
                        "opaque screen refuses keep and download commands");
                check(!screen.removeDownloads(state,false), "opaque screen refuses destructive download command");
                check(screen.rename(id, "Opaque screen"), "ordinary screen rename still admitted");
                check(ui.next().ready(), "ordinary screen rename succeeds over opaque inventory");
                check(screen.undo(), "ordinary screen Undo still admitted");
                check(ui.next().ready(), "ordinary screen Undo succeeds over opaque inventory");
            }
            write(localFile, originalLocal);
            check(screen.refresh(), "restored known inventory refresh admitted"); ui.next();
            failLocal.set(true);
            check(screen.create("Partial"), "screen admits edit before local checkpoint failure");
            state = ui.next();
            check(!state.ready() && state.partial() && state.libraryCommitted && !state.stateCommitted,
                    "screen never reports partial checkpoint as successful edit");
            check(state.canUndo && state.view.playlists.stream().anyMatch(row -> row.title.equals("Partial")), "partial result shows confirmed library and owned Undo");
            failLocal.set(false);
            check(screen.undo(), "confirmed partial edit remains undoable without blind retry");
            state = ui.next(); check(state.ready() && !state.canUndo && state.canRedo
                    && state.view.detail.row.title.equals("Screen 🎵"), "partial Undo creates Redo after confirmed repair");
            check(screen.refresh(), "screen read admitted before close");
            Runnable closed = ui.take(); screen.close(); before = ui.states.size(); closed.run();
            check(ui.states.size() == before && !screen.refresh() && !screen.create("Closed"), "closed screen cannot repaint or mutate");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            PurplePlaylistsScreen currentScreen = screen;
            Thread thread = new Thread(() -> { try { currentScreen.refresh(); } catch (Throwable e) { failure.set(e); } });
            thread.start(); thread.join(); check(failure.get() instanceof IllegalStateException, "screen entrypoints enforce main thread");
            Ui retiredUi = new Ui();
            PurplePlaylistsScreen retired = new PurplePlaylistsScreen(0, retiredUi, worker, retiredUi.queue::add);
            check(retired.refresh(), "second screen reads captured worker"); retiredUi.next();
            check(retired.sync(), "second screen owns manual request");
            java.util.function.Consumer<PurplePlaylistsSyncResult> late = PurplePlaylistsSyncRuntime.manualDone;
            int beforeClose = PurplePlaylistsSyncRuntime.manualCloses;
            retired.close(); check(PurplePlaylistsSyncRuntime.manualCloses == beforeClose + 1, "screen close cancels its manual request");
            int retiredStates = retiredUi.states.size();
            late.accept(new PurplePlaylistsSyncResult(PurplePlaylistsSyncResult.Status.Ready, "SyntheticLate"));
            while (!retiredUi.queue.isEmpty()) retiredUi.queue.remove().run();
            check(retiredUi.states.size() == retiredStates && retiredUi.queue.isEmpty(), "late manual completion cannot reopen retired screen");
        } finally {
            screen.close(); worker.close();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (worker.termination() == PurplePlaylistsWorker.Termination.Running && System.nanoTime() < end) Thread.sleep(2);
            check(worker.termination() == PurplePlaylistsWorker.Termination.Closed, "screen fixture creator closes native editor and store");
        }
    }
    public static void main(String[] args) throws Exception {
        refusals(); projections(); screen();
        PurplePlaylistsWorker registered = PurplePlaylistsWorkers.acquire(0);
        check(registered != null, "registry acquires explicit account for identity event");
        try {
            PurplePlaylistsAdd.Selection oldAccount = selection(music(0, 60, 10, "Old account"));
            check(oldAccount != null, "selection captures original account lifetime");
            PurpleAccountFiles.identityChanged(0);
            check(!oldAccount.available(), "same-slot identity event retires captured Add");
            check(registered.read(new PurplePlaylistsWorker.Target<>(new Object(), Runnable::run, () -> true, (view, result) -> { })).admission
                    == PurplePlaylistsWorker.Admission.Closed, "account identity event retires actor before next acquisition");
        } finally {
            registered.close();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (registered.termination() == PurplePlaylistsWorker.Termination.Running && System.nanoTime() < end) Thread.sleep(2);
            check(registered.termination() == PurplePlaylistsWorker.Termination.Closed, "identity-retired actor closes on creator thread");
        }
        System.out.println("PLAYLIST_VIEW_CHECKS=" + checks);
    }
}
