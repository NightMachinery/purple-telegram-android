package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.List;
import org.telegram.messenger.UserConfig;

public final class PurpleAccountOwner {
    static long observedRevision() { return 0; }
    static long observedRoleEpoch() { return 0; }
    static boolean ready() { return true; }
    static String owner() { return "0-101"; }
    static String global() { return owner(); }

    static List<PurpleAccountFiles.Identity> signedIn() {
        List<PurpleAccountFiles.Identity> result = new ArrayList<>();
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
            if (identity != null) result.add(identity);
        }
        return result;
    }
}
