/*
 * This is the source code of Purple Telegram for Android.
 *
 * recordings.json, the F10 registry's manifest, in files/purple-recordings/.
 * Pure Java with org.json, so the host suite runs it as is.
 */

package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The manifest and the directories next to it:
 *
 * <pre>
 * files/purple-recordings/recordings.json   the entries
 * files/purple-recordings/bin/              the bin's media files
 * </pre>
 *
 * Every write is atomic: the entries go to a temporary file that is synced and
 * then renamed over the manifest, so a crash leaves the old or the new one,
 * never half of either. Not thread safe: {@link PurpleRecordingRegistry} calls
 * it from its one thread.
 */
public final class PurpleRecordingManifest {

    static final String NAME = "recordings.json";
    /** The manifest's own layout; an unknown one is set aside, not overwritten. */
    static final int FORMAT = 1;

    public final File dir;
    public final File bin;
    final File file;

    public PurpleRecordingManifest(File dir) {
        this.dir = dir;
        this.bin = new File(dir, "bin");
        this.file = new File(dir, NAME);
    }

    /**
     * The entries, in the order they were written. A missing manifest is
     * empty. One that cannot be read, or of a format this build does not
     * know, is renamed aside (so its entries are not lost by the next write)
     * and reads as empty.
     */
    public List<PurpleRecordingEntry> load(long nowSeconds) {
        final List<PurpleRecordingEntry> entries = new ArrayList<>();
        if (!file.isFile()) return entries;
        try {
            final JSONObject root = new JSONObject(new String(read(file), StandardCharsets.UTF_8));
            if (root.optInt("format", 0) != FORMAT) {
                setAside();
                return entries;
            }
            final JSONArray list = root.optJSONArray("entries");
            for (int i = 0; list != null && i < list.length(); i++) {
                final JSONObject o = list.optJSONObject(i);
                final PurpleRecordingEntry e = o == null ? null : PurpleRecordingEntry.fromJson(o, nowSeconds);
                if (e != null) entries.add(e);
            }
        } catch (IOException | JSONException e) {
            setAside();
        }
        return entries;
    }

    /** Replaces the manifest with {@code entries}, atomically. */
    public void save(List<PurpleRecordingEntry> entries) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no recordings directory");
        final JSONArray list = new JSONArray();
        try {
            for (PurpleRecordingEntry e : entries) list.put(e.toJson());
            final JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("entries", list);
            write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Creates the bin directory. */
    public File binDir() throws IOException {
        if (!bin.isDirectory() && !bin.mkdirs()) throw new IOException("no bin directory");
        return bin;
    }

    private void setAside() {
        final File aside = new File(dir, NAME + ".unread-" + System.currentTimeMillis());
        if (!file.renameTo(aside)) file.delete();
    }

    /** Writes {@code bytes} to {@code target} through a synced temporary file and a rename. */
    static void write(File target, byte[] bytes) throws IOException {
        final File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("rename failed");
        }
    }

    static byte[] read(File source) throws IOException {
        try (InputStream in = new FileInputStream(source)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IOException("manifest too large");
            }
            return out.toByteArray();
        }
    }
}
