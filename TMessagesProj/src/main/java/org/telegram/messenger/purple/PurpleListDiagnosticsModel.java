package org.telegram.messenger.purple;

final class PurpleListDiagnosticsModel {
    static native String factsNative(byte[] before, byte[] own, byte[] global);
    private PurpleListDiagnosticsModel() { }
}
