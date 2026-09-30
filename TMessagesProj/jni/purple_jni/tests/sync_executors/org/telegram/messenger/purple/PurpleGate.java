package org.telegram.messenger.purple;

public final class PurpleGate {
    static volatile boolean lastGood;

    private PurpleGate() {
    }

    public static boolean usedLastGood() {
        return lastGood;
    }
}
