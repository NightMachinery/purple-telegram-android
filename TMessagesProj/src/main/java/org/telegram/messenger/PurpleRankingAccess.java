package org.telegram.messenger;

public final class PurpleRankingAccess {
    private PurpleRankingAccess() {
    }

    public static boolean loading(MediaDataController controller) {
        return controller.loading;
    }
}
