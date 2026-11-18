package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class PurpleAccountFileSnapshot {
    enum Status { Present, Missing, ReadError }

    final Status status;
    final byte[] bytes;

    private PurpleAccountFileSnapshot(byte[] data) {
        status = data == null ? Status.Missing : Status.Present;
        bytes = data == null ? null : data.clone();
    }

    static PurpleAccountFileSnapshot read(File file) {
        byte[] data = null;
        if (file.equals(PurpleAccountFiles.file("local.toml"))) {
            data = ("[account_work_mode]\n"
                    + "\"0-101\"=\"own\"\n\"0-102\"=\"own\"\n"
                    + "\"0-103\"=\"own\"\n\"0-104\"=\"own\"\n").getBytes(StandardCharsets.UTF_8);
        } else if (file.getName().equals("settings.toml")) {
            data = PurpleGate.settings;
        }
        return new PurpleAccountFileSnapshot(data);
    }

    boolean matches(File file) { return sameRead(file); }
    boolean sameRead(File file) {
        PurpleAccountFileSnapshot other = read(file);
        return status == other.status && Arrays.equals(bytes, other.bytes);
    }
}
