package android.util;
import java.io.*;
public class AtomicFile {
 public static boolean failWrites;
 public static String silentFault="";
 public static int loggedFailures;
 private final File file,fresh;
 public AtomicFile(File file){this.file=file;fresh=new File(file.getPath()+".new");}
 public byte[] readFully()throws IOException{return java.nio.file.Files.readAllBytes(file.toPath());}
 public FileOutputStream startWrite()throws IOException{if(failWrites)throw new IOException("inert write refusal");return new FileOutputStream(fresh);}
 public void finishWrite(FileOutputStream out){
  try{if(silentFault.equals("sync"))++loggedFailures;else out.getFD().sync();}catch(IOException e){++loggedFailures;}
  try{out.close();}catch(IOException e){++loggedFailures;}
  if(silentFault.equals("rename")||!fresh.renameTo(file))++loggedFailures;
 }
 public void failWrite(FileOutputStream out){try{out.close();}catch(Exception e){}fresh.delete();}
}
