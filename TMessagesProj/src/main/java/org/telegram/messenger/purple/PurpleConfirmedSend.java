package org.telegram.messenger.purple;

import java.util.function.BooleanSupplier;

final class PurpleConfirmedSend {
    static void note(BooleanSupplier current, BooleanSupplier fingerprint, Runnable watermark) {
        if (!current.getAsBoolean()) return;
        try {
            fingerprint.getAsBoolean();
        } finally {
            if (current.getAsBoolean()) watermark.run();
        }
    }
    private PurpleConfirmedSend() { }
}
