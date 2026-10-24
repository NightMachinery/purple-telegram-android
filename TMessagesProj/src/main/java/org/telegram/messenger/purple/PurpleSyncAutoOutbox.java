package org.telegram.messenger.purple;

import java.io.File;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

public final class PurpleSyncAutoOutbox {
    static boolean staged(String path) {
        if (path == null || path.isEmpty()) return false;
        File file = new File(path), parent = file.getParentFile();
        return file.getName().equals(PurpleSyncPost.RECORD_FILE_NAME) && parent != null
            && parent.getParentFile() != null
            && parent.getParentFile().getName().equals(PurpleSyncPost.STAGING_DIRECTORY);
    }
    static boolean staged(TLRPC.Message message) {
        if (message == null) return false;
        if (staged(message.attachPath)) return true;
        if (!message.out || !PurpleSyncPost.CAPTION.equals(message.message)
            || !(message.media instanceof TLRPC.TL_messageMediaDocument) || message.media.document == null) return false;
        for (TLRPC.DocumentAttribute attribute : message.media.document.attributes)
            if (attribute instanceof TLRPC.TL_documentAttributeFilename
                && PurpleSyncPost.RECORD_FILE_NAME.equals(attribute.file_name)) return true;
        return false;
    }
    public static boolean divert(int account, MessageObject message) {
        if (message == null || !staged(message.messageOwner)) return false;
        PurpleAccountFiles.Identity origin = PurpleAccountFiles.capture(account);
        if (origin != null && message.currentAccount == account && message.messageOwner.out
            && message.getDialogId() == origin.userId) PurpleSyncAuto.reconcile(origin);
        return true;
    }
    private PurpleSyncAutoOutbox() {}
}
