package org.telegram.ui;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleSettings;
import org.telegram.messenger.purple.PurpleSettingsMessage;

import java.io.File;
import java.util.ArrayList;

final class PurpleSettingsImport {
    private PurpleSettingsImport() {
    }

    static boolean importSettings(ChatActivity chat, MessageObject message) {
        if (!PurpleSettingsMessage.is(message)) {
            return false;
        }
        open(chat, message);
        return true;
    }

    static boolean importButton(ChatActivity chat, MessageObject message, int type) {
        if (type != PurpleImportButton.INSTANT_BUTTON_TYPE_PURPLE_IMPORT) {
            return false;
        }
        open(chat, message);
        return true;
    }

    static void relabel(MessageObject message, ArrayList<CharSequence> items, ArrayList<Integer> options, int optionId) {
        if (!PurpleSettingsMessage.is(message)) {
            return;
        }
        int index = options.indexOf(optionId);
        if (index >= 0 && index < items.size()) {
            items.set(index, LocaleController.getString(R.string.ImportPurpleSettings));
        }
    }

    private static void open(ChatActivity chat, MessageObject message) {
        if (message == null || chat.getParentActivity() == null) {
            return;
        }
        File locFile = null;
        if (message.messageOwner.attachPath != null && message.messageOwner.attachPath.length() != 0) {
            File f = new File(message.messageOwner.attachPath);
            if (f.exists()) {
                locFile = f;
            }
        }
        if (locFile == null) {
            File f = chat.getFileLoader().getPathToMessage(message.messageOwner);
            if (f.exists()) {
                locFile = f;
            }
        }
        if (locFile != null) {
            PurpleSettings.importFrom(chat.getParentActivity(), locFile, message);
        } else if (message.getDocument() != null) {
            message.loadingCancelled = false;
            chat.getFileLoader().loadFile(message.getDocument(), message, FileLoader.PRIORITY_NORMAL_UP, 0);
        }
        }
}
