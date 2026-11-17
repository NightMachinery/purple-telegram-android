package org.telegram.messenger;
import java.io.File;
public final class FileLoader {
 public static final int MEDIA_DIR_CACHE=4;
 public static File getDirectory(int type) {
  File value = new File(ApplicationLoader.files,"cache");
  try { java.nio.file.Files.createDirectories(value.toPath(),java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))); }
  catch(java.io.IOException error) { throw new IllegalStateException(error); }
  return value;
 }
}
