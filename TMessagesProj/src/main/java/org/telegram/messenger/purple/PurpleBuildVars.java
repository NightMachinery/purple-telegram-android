package org.telegram.messenger.purple;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;

public final class PurpleBuildVars {
    private PurpleBuildVars() {
    }

    public static void apply() {
        BuildVars.APP_ID = BuildConfig.APP_ID;
        BuildVars.APP_HASH = BuildConfig.APP_HASH;
        BuildVars.SUPPORTS_PASSKEYS = false;
    }
}
