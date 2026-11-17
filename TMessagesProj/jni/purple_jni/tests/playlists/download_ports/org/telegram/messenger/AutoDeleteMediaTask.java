package org.telegram.messenger;
import java.io.File;
public final class AutoDeleteMediaTask {
 public static final java.util.Set<String> usingFilePaths=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
 public static void lockFile(File file) { usingFilePaths.add(file.getAbsolutePath()); }
 public static void unlockFile(File file) { usingFilePaths.remove(file.getAbsolutePath()); }
}
