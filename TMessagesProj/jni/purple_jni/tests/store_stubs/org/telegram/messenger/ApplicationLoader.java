package org.telegram.messenger;

import java.io.File;

public final class ApplicationLoader {
    public static File getFilesDirFixed() {
        throw new AssertionError("Tests inject a root");
    }
}
