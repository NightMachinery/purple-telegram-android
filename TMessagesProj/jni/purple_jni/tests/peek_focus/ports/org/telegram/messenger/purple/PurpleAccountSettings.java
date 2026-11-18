package org.telegram.messenger.purple;

import java.io.File;

public final class PurpleAccountSettings {
    static File globalFile() { return PurpleAccountFiles.file("settings.toml"); }
}
