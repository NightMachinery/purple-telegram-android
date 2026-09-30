/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.telegram.tgnet.TLRPC;

public final class PurpleSyncAutoDownload {
    private PurpleSyncAutoDownload() {
    }

    public static boolean excludes(TLRPC.Document document) {
        if (document == null || document.attributes == null) {
            return false;
        }
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeFilename
                    && isRecordName(attribute.file_name)) {
                return true;
            }
        }
        return false;
    }

    static boolean isRecordName(String name) {
        return PurpleSyncPost.RECORD_FILE_NAME.equals(name)
                || PurpleSyncPost.PLAYLISTS_RECORD_FILE_NAME.equals(name);
    }
}
