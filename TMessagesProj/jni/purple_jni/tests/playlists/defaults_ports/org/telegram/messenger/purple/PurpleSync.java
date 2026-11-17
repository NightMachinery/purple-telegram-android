package org.telegram.messenger.purple;
import java.io.File;import java.nio.file.*;import java.util.Arrays;
public class PurpleSync {static void afterRoutedWrite(PurpleAccountFiles.Identity actor,File file,byte[] bytes,String reason){try{if(!actor.current()||!Arrays.equals(bytes,Files.readAllBytes(file.toPath())))throw new AssertionError("post-write sync snapshot");++NativeWriterFixture.synced;}catch(Exception e){throw new AssertionError(e);}}}
