package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PurpleLegacySendState {
    static byte[] read(PurpleAccountFiles files) {
        PurpleAccountFileSnapshot state = PurpleAccountFileSnapshot.read(files.fingerprint, (int) PurpleState.MAX_SIZE);
        if (state.status == PurpleAccountFileSnapshot.Status.ReadError) return null;
        byte[] bytes = state.bytes == null ? new byte[0] : state.bytes;
        try { return noteNative(bytes, new byte[0], false) == null ? null : bytes; }
        catch (RuntimeException | UnsatisfiedLinkError e) { return null; }
    }
    static boolean note(PurpleAccountFiles.Identity origin, File target, byte[] bytes, boolean imported) {
        synchronized (PurpleAccountOwner.class) {
            synchronized (PurpleAccountFiles.class) {
                if (origin == null || !origin.current() || !PurpleAccountOwner.ready()) return false;
                String owner = PurpleAccountOwner.owner();
                PurpleAccountFiles files = PurpleAccountFiles.resolve(origin.key, owner);
                if (files == null || !files.settings.equals(target)) return false;
                PurpleAccountFileSnapshot written = PurpleAccountFileSnapshot.read(target);
                PurpleAccountFileSnapshot state = PurpleAccountFileSnapshot.read(files.fingerprint, (int) PurpleState.MAX_SIZE);
                if (state.status == PurpleAccountFileSnapshot.Status.ReadError || written.bytes == null || !Arrays.equals(bytes, written.bytes)) return false;
                String text = noteNative(state.bytes, bytes, imported);
                return text != null && origin.current() && owner.equals(PurpleAccountOwner.owner())
                        && written.matches(target) && PurpleAccountFileSnapshot.replace(files.fingerprint, state, text.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
    static native String noteNative(byte[] state, byte[] bytes, boolean imported);
    private PurpleLegacySendState() { }
}
