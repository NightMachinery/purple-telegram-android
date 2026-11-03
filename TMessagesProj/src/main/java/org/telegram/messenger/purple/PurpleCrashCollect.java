/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One start's crash collection, kept free of Android so host tests run this
 * production class: {@link PurpleCrashCapture} reads the exit reasons and
 * hands them here with where to write and how to remember the newest one.
 *
 * The pending file is <em>taken</em> before it is read: renamed to
 * {@code pending-crash.log.taken-<time>}, so a crash during collection makes
 * its handler append to a fresh {@code pending-crash.log} instead of a file
 * the collector is about to delete. Taken files left by a start that died
 * mid-collection, or whose write failed, are picked up by the next start.
 *
 * Nothing is lost on a failed write: the taken files are deleted and the
 * exit cursor is advanced only when every line was appended. A process death
 * after the write and before the delete logs the same records again on the
 * next start; a duplicate was chosen over a lost record.
 */
final class PurpleCrashCollect {
    static final String TAKEN = PurpleCrashRecord.PENDING + ".taken-";
    /** Taken files kept for a retry at most; older ones are dropped, so a store that never writes cannot pile them up. */
    static final int TAKEN_FILES = 4;

    /** Where a start's lines go; true only when every line was appended. */
    interface Sink {
        boolean append(List<String> lines);
    }

    /** Remembers the newest exit time handled. */
    interface Cursor {
        void advance(long newest);
    }

    private PurpleCrashCollect() {
    }

    /** Renames the pending file to a fresh taken name; returns whether there was one to take. */
    static boolean take(File pending, long now) {
        if (pending == null || !pending.isFile()) return false;
        File directory = pending.getParentFile();
        for (int i = 0; i < 100; ++i) {
            File taken = new File(directory, TAKEN + now + (i == 0 ? "" : "-" + i));
            if (!taken.exists()) return pending.renameTo(taken);
        }
        return false;
    }

    /** Every taken file next to {@code pending}, oldest first, after dropping all but the newest {@value #TAKEN_FILES}. */
    static List<File> taken(File pending) {
        List<File> out = new ArrayList<>();
        File directory = pending == null ? null : pending.getParentFile();
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return out;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith(TAKEN)) out.add(file);
        }
        File[] sorted = out.toArray(new File[0]);
        Arrays.sort(sorted, (a, b) -> {
            int byLength = Integer.compare(a.getName().length(), b.getName().length());
            return byLength != 0 ? byLength : a.getName().compareTo(b.getName());
        });
        out = new ArrayList<>(Arrays.asList(sorted));
        while (out.size() > TAKEN_FILES) {
            out.remove(0).delete();
        }
        return out;
    }

    /**
     * Takes the pending file, merges every taken file's records with
     * {@code exits} ({@link PurpleCrashRecord#plan}) and writes them. Only
     * when {@code sink} wrote every line are the taken files deleted and the
     * cursor advanced to {@code newest} (when it moved past {@code last}).
     * Returns whether the start's records are now in the log.
     */
    static boolean run(File pending, List<PurpleCrashRecord.Exit> exits, long last, long newest, long now,
            Sink sink, Cursor cursor) {
        take(pending, now);
        List<File> files = taken(pending);
        List<PurpleCrashRecord.Pending> records = new ArrayList<>();
        for (File file : files) records.addAll(PurpleCrashRecord.readPending(file));
        if (!exits.isEmpty() || !records.isEmpty()) {
            List<String> lines = PurpleCrashRecord.plan(exits, records, now);
            if (!sink.append(lines)) return false;
        }
        for (File file : files) file.delete();
        if (newest > last) cursor.advance(newest);
        return true;
    }
}
