package org.telegram.messenger;
import java.io.File;
public final class ApplicationLoader {
    public static File files;
    public static android.content.Context applicationContext;
    public static File getFilesDirFixed(){if(files==null)throw new IllegalStateException("fixture root missing");return files;}
}
