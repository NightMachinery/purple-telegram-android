package org.telegram.messenger.purple;

import java.io.File;

public final class PurpleMediaSaveSource {
    public final File file;
    public final boolean encrypted;
    public final String name;
    public final String mime;

    private PurpleMediaSaveSource(File file, boolean encrypted, String name, String mime) {
        this.file = file;
        this.encrypted = encrypted;
        this.name = name;
        this.mime = mime;
    }

    public static PurpleMediaSaveSource find(File path, boolean encryptedCache, String name, String mime) {
        if (path == null) {
            return null;
        }
        if (path.isFile()) {
            return new PurpleMediaSaveSource(path, false, name, mime);
        }
        if (encryptedCache) {
            File encrypted = new File(path.getPath() + ".enc");
            if (encrypted.isFile()) {
                return new PurpleMediaSaveSource(encrypted, true, name, mime);
            }
        }
        return null;
    }
}
