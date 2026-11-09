package android.system;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class Os {
 public static String fault="";
 public static int syncs,renames,closes;
 private static final Map<FileDescriptor,FileOutputStream> streams=new IdentityHashMap<>();
 private static final Set<FileDescriptor> directories=Collections.newSetFromMap(new IdentityHashMap<FileDescriptor,Boolean>());
 public static FileDescriptor open(String path,int flags,int mode)throws ErrnoException {
  try { if(new File(path).isDirectory()){FileDescriptor fd=new FileDescriptor();directories.add(fd);return fd;}
   FileOutputStream out=new FileOutputStream(path);FileDescriptor fd=out.getFD();streams.put(fd,out);return fd;
  }catch(IOException e){throw new ErrnoException("open",5);}
 }
 public static StructStat fstat(FileDescriptor fd)throws ErrnoException {
  if(fault.equals("directory-stat"))throw new ErrnoException("fstat",5);
  return new StructStat(directories.contains(fd)&&!fault.equals("directory-kind")?0040000:0100000);
 }
 public static int write(FileDescriptor fd,byte[] bytes,int start,int length)throws ErrnoException {
  try { if(fault.equals("write"))throw new IOException();int size=Math.min(length,4096);streams.get(fd).write(bytes,start,size);return size; }catch(IOException e){throw new ErrnoException("write",5);}
 }
 public static void fsync(FileDescriptor fd)throws ErrnoException {
  ++syncs;
  if(fault.equals(directories.contains(fd)?"directory-sync":"sync"))throw new ErrnoException("fsync",5);
  try {if(!directories.contains(fd))fd.sync();}catch(IOException e){throw new ErrnoException("fsync",5);}
 }
 public static void close(FileDescriptor fd)throws ErrnoException {
  ++closes;boolean directory=directories.remove(fd);
  try {if(!directory)streams.remove(fd).close();}catch(IOException e){throw new ErrnoException("close",5);}
  if(!directory&&fault.equals("close"))throw new ErrnoException("close",5);
 }
 public static void rename(String source,String target)throws ErrnoException {
  ++renames;if(fault.equals("rename"))throw new ErrnoException("rename",5);
  try {Files.move(Paths.get(source),Paths.get(target),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
   if(fault.equals("readback"))Files.write(Paths.get(target),new byte[]{1});
  }catch(IOException e){throw new ErrnoException("rename",5);}
 }
}
