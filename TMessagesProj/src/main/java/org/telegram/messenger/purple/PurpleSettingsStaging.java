/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

final class PurpleSettingsStaging {
    static final String DIRECTORY = "purple-sync";
    static final String NO_MEDIA = ".nomedia";
    static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    private final String fileName;
    private final File root;
    private final File former;

    PurpleSettingsStaging(String fileName, File filesDirectory, File cacheDirectory) {
        this.fileName = fileName;
        this.root = filesDirectory != null ? new File(filesDirectory, DIRECTORY) : null;
        this.former = cacheDirectory != null ? new File(cacheDirectory, DIRECTORY) : null;
    }

    File newDirectory(long now) {
        if (former != null) {
            prune(former, now);
            former.delete();
        }
        if (root == null) {
            return null;
        }
        prune(root, now);
        final File directory = new File(root, UUID.randomUUID().toString());
        if (!directory.mkdirs()) {
            return null;
        }
        try {
            new File(root, NO_MEDIA).createNewFile();
        } catch (IOException | SecurityException ignored) {
        }
        return directory;
    }

    File rootOf(File staged) {
        final File directory = staged.getParentFile();
        if (directory == null || !fileName.equals(staged.getName())) {
            return null;
        }
        final File parent = directory.getParentFile();
        if (root != null && root.equals(parent)) {
            return root;
        }
        if (former != null && former.equals(parent)) {
            return former;
        }
        return null;
    }

    boolean isCanonical(File staged, File stagingRoot) throws IOException {
        final String uuid = staged.getParentFile().getName();
        return UUID.fromString(uuid).toString().equals(uuid)
                && staged.getCanonicalFile().equals(new File(
                        new File(stagingRoot.getCanonicalFile(), uuid), fileName));
    }

    static void remove(File staged) {
        if (staged.delete()) {
            staged.getParentFile().delete();
        }
    }

    private void prune(File directoryRoot, long now) {
        final File[] dirs = directoryRoot.listFiles();
        if (dirs == null) {
            return;
        }
        final long oldest = now - MAX_AGE_MS;
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.lastModified() >= oldest) {
                continue;
            }
            final File staged = new File(dir, fileName);
            if (staged.isFile() && staged.lastModified() < oldest) {
                remove(staged);
            } else if (!staged.exists()) {
                dir.delete();
            }
        }
    }
}
