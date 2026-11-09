package android.util;
import java.io.*;
public class AtomicFile {
 public static boolean failWrites;
 private final File file;
 public AtomicFile(File file){this.file=file;}
 public byte[] readFully()throws IOException{return java.nio.file.Files.readAllBytes(file.toPath());}
 public FileOutputStream startWrite()throws IOException{if(failWrites)throw new IOException("inert write refusal");return new FileOutputStream(file);}
 public void finishWrite(FileOutputStream out)throws IOException{out.getFD().sync();out.close();}
 public void failWrite(FileOutputStream out){try{out.close();}catch(Exception e){}}
}
