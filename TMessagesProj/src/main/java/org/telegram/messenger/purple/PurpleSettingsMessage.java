package org.telegram.messenger.purple;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleSettingsMessage {

    private PurpleSettingsMessage() {
    }

    public static boolean is(MessageObject message) {
        if (message == null) return false;
        TLRPC.Document document = message.getDocument();
        if (document == null || document.size > PurpleSettings.MAX_SIZE) {
            return false;
        }
        String name = message.getDocumentName();
        if (!PurpleSettings.fileName(name)) {
            return false;
        }
        long selfId = UserConfig.getInstance(message.currentAccount).getClientUserId();
        return selfId != 0 && message.getDialogId() == selfId;
    }
}
