package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class PurplePlaylistsDownloadsCases {
    private static int checks;
    private static void check(boolean value, String name) {
        ++checks;
        if (!value) throw new AssertionError(name);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject object(byte[] value) { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
    private static String repeat(char c, int n) { char[] result = new char[n]; Arrays.fill(result, c); return new String(result); }
    private static final String ID = "pl-aaaaaaaaaaaaaaaa", TOKEN = repeat('a', 32), HASH = repeat('a', 64);

    public static void main(String[] args) throws Exception {
        byte[] initial = PurplePlaylistsLocalCore.defaults(1).canonicalBytes();
        JSONObject json = object(initial);
        json.put("unknown_root", new JSONObject().put("x", 14));
        JSONObject desktop = new JSONObject().put("format", 2).put("foreign", "opaque");
        json.put("desktop_playlist_downloads", desktop);
        initial = bytes(json.toString());
        PurplePlaylistsDownloadsCore.Result read = PurplePlaylistsDownloadsCore.read(initial, 1);
        check(read.ready(), "other-client newer state remains opaque");
        check(!object(read.local()).has("android_playlist_downloads"), "read never creates inventory");
        check(object(read.inventory()).getJSONObject("files").length() == 0, "read projects empty inventory");
        check(object(read.local()).getJSONObject("desktop_playlist_downloads").toString().equals(desktop.toString()), "desktop is untouched");
        byte[] mutation = read.local(); mutation[0] = 0;
        check(read.local()[0] != 0, "defensive result bytes");
        byte[] local = initial;
        for (PurplePlaylistsDownloadsCore.Preference value : PurplePlaylistsDownloadsCore.Preference.values()) {
            read = PurplePlaylistsDownloadsCore.preference(local, 1, ID, value);
            check(read.ready(), "typed preference");
            JSONObject prefs = object(read.inventory()).getJSONObject("preferences");
            check(value == PurplePlaylistsDownloadsCore.Preference.Inherit ? !prefs.has(ID)
                    : prefs.getString(ID).equals(value == PurplePlaylistsDownloadsCore.Preference.On ? "on" : "off"), "shared preference projection");
            check(object(read.local()).getJSONObject("unknown_root").getInt("x") == 14, "other local bookkeeping preserved");
            check(object(read.local()).getJSONObject("desktop_playlist_downloads").toString().equals(desktop.toString()), "preference preserves desktop");
            local = read.local();
        }
        read = PurplePlaylistsDownloadsCore.reserve(local, 1, ID, TOKEN, 44, "mp3", 123);
        check(read.ready(), "durable filename reservation proposal");
        local = read.local();
        check(object(read.inventory()).getJSONObject("pending").getJSONObject(TOKEN).getString("identity").isEmpty(), "uncreated identity empty");
        check(PurplePlaylistsDownloadsCore.reserve(local, 1, ID, TOKEN, 44, "mp3", 123).status == PurplePlaylistsDownloadsCore.Status.Busy, "duplicate token refused");
        check(PurplePlaylistsDownloadsCore.reserve(local, 1, ID, repeat('b', 32), 44, "mp3", 123).status == PurplePlaylistsDownloadsCore.Status.Busy, "duplicate document refused");
        check(PurplePlaylistsDownloadsCore.complete(local, 1, ID, TOKEN, 44, "mp3", 123, "", "").status == PurplePlaylistsDownloadsCore.Status.Invalid, "unverified file cannot complete");
        read = PurplePlaylistsDownloadsCore.checkpoint(local, 1, ID, TOKEN, 44, "mp3", 123, "5:9", "");
        check(read.ready(), "identity before transfer"); local = read.local();
        check(PurplePlaylistsDownloadsCore.checkpoint(local, 1, ID, TOKEN, 44, "mp3", 123, "5:10", "").status == PurplePlaylistsDownloadsCore.Status.Changed, "stolen file cannot checkpoint");
        check(PurplePlaylistsDownloadsCore.checkpoint(local, 1, ID, TOKEN, 44, "m4a", 123, "5:9", "").status == PurplePlaylistsDownloadsCore.Status.Changed, "metadata exact");
        read = PurplePlaylistsDownloadsCore.checkpoint(local, 1, ID, TOKEN, 44, "mp3", 123, "5:9", HASH);
        check(read.ready(), "completed hash checkpoint"); local = read.local();
        check(PurplePlaylistsDownloadsCore.complete(local, 1, ID, TOKEN, 44, "mp3", 123, "5:9", repeat('b', 64)).status == PurplePlaylistsDownloadsCore.Status.Changed, "hash cannot change during publish");
        JSONObject inventory = object(read.inventory()); inventory.put("future", new JSONObject().put("y", true));
        inventory.getJSONObject("pending").getJSONObject(TOKEN).put("unknown_intent", 23);
        JSONObject root = object(local); root.put("android_playlist_downloads", inventory); local = bytes(root.toString());
        read = PurplePlaylistsDownloadsCore.complete(local, 1, ID, TOKEN, 44, "mp3", 123, "5:9", HASH);
        check(read.ready(), "publication bookkeeping transition"); local = read.local();
        check(!object(read.inventory()).has("pending"), "completed intent removed");
        check(object(read.inventory()).getJSONObject("files").getJSONObject("44").getInt("unknown_intent") == 23, "entry unknowns preserved");
        check(object(read.inventory()).getJSONObject("future").getBoolean("y"), "inventory unknowns preserved");
        check(PurplePlaylistsDownloadsCore.forgetFile(local, 1, 44, "mp3", 123, "5:10", HASH).status == PurplePlaylistsDownloadsCore.Status.Changed, "forget exact identity");
        read = PurplePlaylistsDownloadsCore.forgetFile(local, 1, 44, "mp3", 123, "5:9", HASH);
        check(read.ready() && object(read.inventory()).getJSONObject("files").length() == 0, "explicit matching inventory release");
        for (String extension : new String[]{"../mp3", "MP3", "", repeat('a', 13)}) {
            read = PurplePlaylistsDownloadsCore.reserve(local, 1, ID, TOKEN, 45, extension, 123);
            check(read.status == PurplePlaylistsDownloadsCore.Status.Invalid && read.local() == null, "unsafe extension yields no write");
        }
        for (String token : new String[]{"../x", repeat('b', 31), repeat('A', 32), repeat('z', 32)}) {
            read = PurplePlaylistsDownloadsCore.reserve(local, 1, ID, token, 45, "mp3", 123);
            check(read.status == PurplePlaylistsDownloadsCore.Status.Invalid && read.local() == null, "unsafe token yields no write");
        }
        for (long size : new long[]{0, -1, Long.MIN_VALUE}) {
            read = PurplePlaylistsDownloadsCore.reserve(local, 1, ID, TOKEN, 45, "mp3", size);
            check(read.status == PurplePlaylistsDownloadsCore.Status.Invalid && read.local() == null, "invalid size yields no write");
        }
        root = object(local); root.put("android_playlist_downloads", new JSONObject().put("format", 2));
        read = PurplePlaylistsDownloadsCore.preference(bytes(root.toString()), 1, ID, PurplePlaylistsDownloadsCore.Preference.On);
        check(read.status == PurplePlaylistsDownloadsCore.Status.Newer && read.local() == null, "newer local download member refuses writes");
        for (byte[] invalid : new byte[][]{null, new byte[0], bytes("{"), bytes("[]"), bytes("null")}) {
            read = PurplePlaylistsDownloadsCore.read(invalid, 1);
            check(!read.ready() && read.local() == null, "invalid local yields no replacement");
        }
        check(!PurplePlaylistsDownloadsCore.read(initial, 0).ready(), "self user required");
        for (int n = 0; n < 9; ++n) {
            try (PurplePlaylistsDownloadsCore.Job job = new PurplePlaylistsDownloadsCore.Job(n);
                    PurplePlaylistsDownloadsCore.Job foreign = new PurplePlaylistsDownloadsCore.Job(1)) {
                int done = 0;
                while (!job.progress().terminal) {
                    PurplePlaylistsDownloadsCore.Ticket[] tickets = job.admit();
                    check(tickets.length <= 2 && job.progress().active <= 2, "native scheduler bounds actual JNI admissions");
                    check(job.admit().length == 0, "native no third admission");
                    check(!job.retry(), "cannot retry active generation");
                    for (PurplePlaylistsDownloadsCore.Ticket ticket : tickets) {
                        check(!foreign.finish(ticket, PurplePlaylistsDownloadsCore.Phase.Downloaded), "ticket belongs to exact job");
                        check(job.finish(ticket, PurplePlaylistsDownloadsCore.Phase.Downloaded), "native completion accepted");
                        check(!job.finish(ticket, PurplePlaylistsDownloadsCore.Phase.Failed), "duplicate native completion refused");
                        ++done;
                    }
                }
                check(job.progress().downloaded == done && done == n, "real shared progress counts");
                check(!job.retry(), "downloaded rows never restart");
                job.cancel();
                check(job.progress().canceled && job.progress().terminal && job.progress().downloaded == n, "cancel retains downloaded outcomes");
            }
        }
        try (PurplePlaylistsDownloadsCore.Job job = new PurplePlaylistsDownloadsCore.Job(1)) {
            PurplePlaylistsDownloadsCore.Ticket old = job.admit()[0];
            check(job.finish(old, PurplePlaylistsDownloadsCore.Phase.Busy), "native busy outcome");
            check(job.retry(), "explicit retry");
            PurplePlaylistsDownloadsCore.Ticket next = job.admit()[0];
            check(!job.finish(old, PurplePlaylistsDownloadsCore.Phase.Downloaded), "stale generation rejected");
            AtomicBoolean refused = new AtomicBoolean();
            Thread other = new Thread(() -> { try { job.progress(); } catch (IllegalStateException expected) { refused.set(true); } });
            other.start(); other.join();
            check(refused.get(), "creator thread enforced");
            job.cancel();
            check(!job.finish(next, PurplePlaylistsDownloadsCore.Phase.Downloaded), "post-cancel completion rejected");
        }
        PurplePlaylistsDownloadsCore.Job closed = new PurplePlaylistsDownloadsCore.Job(0);
        closed.close(); closed.close();
        boolean refused = false;
        try { closed.admit(); } catch (IllegalStateException expected) { refused = true; }
        check(refused, "closed JNI job cannot be reused");
        System.out.println("PLAYLIST_DOWNLOAD_JNI_CHECKS=" + checks);
    }
}
