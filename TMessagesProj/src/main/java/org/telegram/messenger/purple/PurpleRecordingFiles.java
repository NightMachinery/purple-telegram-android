/*
 * This is the source code of Purple Telegram for Android.
 *
 * Which files are recordings: the names upstream's recorders give them, and
 * the moving and copying the registry and the bin do. Pure Java, so the host
 * suite runs it as is.
 */

package org.telegram.messenger.purple;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.regex.Pattern;

/**
 * File names, from upstream 13.1.1:
 *
 * <ul>
 * <li>legacy voice: {@code <time>_<attach name>.ogg} in MEDIA_DIR_AUDIO
 *     ({@code MediaController.startRecording});</li>
 * <li>V2 voice: {@code audio_recording_<role>_<time>_<n>.ogg} in
 *     MEDIA_DIR_AUDIO ({@code AudioRecordingSession.createTemporaryFile}; the
 *     role is {@code voice});</li>
 * <li>legacy round: {@code <time>_<local id>.mp4} in MEDIA_DIR_DOCUMENT, or
 *     {@code camera_tmp.mp4} in the files directory when the media
 *     directory is on an SD card ({@code InstantCameraView});</li>
 * <li>V2 round: {@code round_video_*.mp4} in {@code files/cache}
 *     ({@code RoundVideoSession.createTemporaryFile}).</li>
 * </ul>
 */
public final class PurpleRecordingFiles {

    private PurpleRecordingFiles() {
    }

    private static final Pattern LEGACY_VOICE = Pattern.compile("\\d{10,}_.+\\.ogg");
    private static final Pattern V2_VOICE = Pattern.compile("audio_recording_[a-z]+_\\d+_\\d+\\.ogg");
    private static final Pattern LEGACY_ROUND = Pattern.compile("\\d{10,}_-?\\d+\\.mp4");
    private static final Pattern V2_ROUND = Pattern.compile("round_video_(?!preview_).*\\.mp4");

    /** A file a voice recorder writes while it records. */
    public static boolean voiceName(String name) {
        return name != null && (V2_VOICE.matcher(name).matches() || LEGACY_VOICE.matcher(name).matches());
    }

    /** A file the orphan scan considers for voice: V2's temporary files always, an .ogg only if unfinished. */
    public static boolean orphanVoiceName(String name) {
        return name != null && (name.startsWith("audio_recording_") && name.endsWith(".ogg") || LEGACY_VOICE.matcher(name).matches());
    }

    /** A file the orphan scan considers for rounds. */
    public static boolean roundName(String name) {
        return name != null && (V2_ROUND.matcher(name).matches() || LEGACY_ROUND.matcher(name).matches()
                || name.equals("camera_tmp.mp4"));
    }

    /**
     * The voice file a recording that started at {@code startedAt} writes: the
     * newest recorder-named file in {@code dir} modified since shortly before
     * the start, or null. Upstream's V2 recorder names its file only in a
     * listener private to MediaController, so the registry looks for it.
     */
    public static File newestVoice(File dir, long startedAt) {
        final File[] files = dir == null ? null : dir.listFiles();
        File best = null;
        if (files == null) return null;
        for (File f : files) {
            if (!f.isFile() || !voiceName(f.getName())) continue;
            final long modified = f.lastModified();
            if (modified < startedAt - 2000) continue;
            if (best == null || modified > best.lastModified()) best = f;
        }
        return best;
    }

    /**
     * Whether an MP4 file has a top-level {@code moov} box, which a recorder
     * writes only when it finishes: a file with {@code ftyp} and {@code mdat}
     * but no {@code moov} is a crashed recording. This walks box headers only;
     * the media data is read by purple-core's repair.
     *
     * @return 1 finished, 0 unfinished ({@code ftyp} then {@code mdat}, no
     *     {@code moov}), -1 not an MP4 or unreadable
     */
    public static int mp4Finished(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            final long length = in.length();
            long at = 0;
            int boxes = 0;
            boolean mdat = false;
            final byte[] head = new byte[16];
            while (at + 8 <= length && boxes < 100000) {
                in.seek(at);
                in.readFully(head, 0, 8);
                long size = ((head[0] & 0xffL) << 24) | ((head[1] & 0xffL) << 16) | ((head[2] & 0xffL) << 8) | (head[3] & 0xffL);
                final String type = new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (boxes == 0 && !type.equals("ftyp")) return -1;
                if (type.equals("moov")) return 1;
                if (type.equals("mdat")) mdat = true;
                if (size == 1) {
                    if (at + 16 > length) break;
                    in.readFully(head, 8, 8);
                    size = 0;
                    for (int i = 8; i < 16; i++) size = (size << 8) | (head[i] & 0xffL);
                } else if (size == 0) {
                    break;
                }
                if (size < 8 || !printable(type)) break;
                at += size;
                boxes++;
            }
            return boxes > 0 && mdat ? 0 : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static boolean printable(String type) {
        for (int i = 0; i < type.length(); i++) {
            final char c = type.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    /** Moves {@code from} to {@code to}: a rename, or a copy and a delete across file systems. */
    public static void move(File from, File to) throws IOException {
        if (from.renameTo(to)) return;
        copy(from, to);
        if (!from.delete()) {
            // The bin has a full copy; the original stays where upstream left it.
        }
    }

    /** Copies a file, synced. */
    public static void copy(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from)) {
            copy(in.getChannel(), to);
        }
    }

    /**
     * Copies everything a channel holds into {@code to}, synced. The registry
     * copies a discarded recording from the descriptor it held open while
     * recording, which still reads after upstream deleted the file's name.
     */
    public static void copy(FileChannel from, File to) throws IOException {
        try (FileOutputStream out = new FileOutputStream(to)) {
            final FileChannel channel = out.getChannel();
            final long size = from.size();
            long done = 0;
            while (done < size) {
                final long n = from.transferTo(done, size - done, channel);
                if (n <= 0) break;
                done += n;
            }
            out.flush();
            out.getFD().sync();
            if (done < size) throw new IOException("short copy");
        }
    }
}
