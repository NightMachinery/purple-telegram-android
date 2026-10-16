package org.telegram.messenger.purple;

import org.telegram.messenger.SharedConfig;

public final class PurplePasscode {
    private static boolean checking;

    private PurplePasscode() {
    }

    public static boolean checkMapped(String passcode) {
        if (checking) {
            return false;
        }
        checking = true;
        try {
            if (SharedConfig.checkPasscode(passcode)) {
                return true;
            }
            String mapped = PurpleCore.persianKeyboardToEnglish(passcode);
            return !mapped.equals(passcode) && SharedConfig.checkPasscode(mapped);
        } finally {
            checking = false;
        }
    }
}
