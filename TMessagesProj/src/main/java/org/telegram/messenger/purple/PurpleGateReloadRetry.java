package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

final class PurpleGateReloadRetry {
    private static boolean queued;
    static void request() {
        synchronized (PurpleGateReloadRetry.class) {
            if (queued) return;
            queued = true;
        }
        AndroidUtilities.runOnUIThread(() -> {
            synchronized (PurpleGateReloadRetry.class) { queued = false; }
            try { PurpleGate.reload("reload publication superseded"); }
            catch (RuntimeException | LinkageError e) { FileLog.e(e); request(); }
        }, 1000);
    }
    private PurpleGateReloadRetry() { }
}
