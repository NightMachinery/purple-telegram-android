package org.telegram.messenger.purple;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class NativeWriterFixture {
 public static File root;public static int synced,notified,writes;static int sequence;public static Runnable duringWrite,afterRefresh;
 public static void write(File file,String text){try{Files.createDirectories(file.toPath().getParent());Files.write(file.toPath(),text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new AssertionError(e);}}
 public static String text(int slot){try{return Files.readString(PurpleAccountFiles.resolve(PurpleAccountFiles.capture(slot).key,"0-101").settings.toPath());}catch(Exception e){throw new AssertionError(e);}}
 public static void replace(int slot,String text){write(PurpleAccountFiles.resolve(PurpleAccountFiles.capture(slot).key,"0-101").settings,text);}
 public static void replaceAccount(int slot){PurpleAccountFiles.Identity previous=PurpleAccountFiles.identities[slot];PurpleAccountFiles.identities[slot]=new PurpleAccountFiles.Identity(slot,previous.key,previous.lifetime+1);}
 public static int mode(){return PurpleRoutedWriteModel.modeNative(bytes(local()),"0-202","0-101");}
 public static String local(){try{return Files.readString(new File(root,"local.toml").toPath());}catch(Exception e){throw new AssertionError(e);}}
 static byte[] bytes(String text){return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 public static void reset(String mode){
  root=new File(System.getProperty("scratch"),"writer-"+(++sequence));if(!root.mkdirs())throw new AssertionError("fresh fixture");
  PurpleAccountFiles.identities=new PurpleAccountFiles.Identity[]{new PurpleAccountFiles.Identity(0,"0-101",sequence),new PurpleAccountFiles.Identity(1,"0-202",sequence)};
  write(new File(root,"accounts/root.toml"),"root_file_account='0-101'\n");
  write(new File(root,"local.toml"),"root_file_account='0-101'\nglobal_account='0-101'\n[account_work_mode]\n'0-202'='"+mode+"'\n");
  replace(0,"version=1\n[media_reply.private]\nvoice='off'\n[media_reply.sets.Pick]\npeers=['user:8']\n");
  replace(1,"version=2\n[media_reply.sets.Pick]\npeers=['user:8']\n");
  synced=notified=writes=0;duringWrite=afterRefresh=null;PurpleAccountSettings.invalidate();
 }
 public static PurpleGlobalChoiceFiles.Physical physical(){return new PurpleGlobalChoiceFiles.Physical(){
  public PurpleGlobalChoiceFiles.Mark mark(File file,boolean directory)throws Exception{
   Path path=file.toPath();if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return new PurpleGlobalChoiceFiles.Mark("missing","",0);
   java.nio.file.attribute.BasicFileAttributes a=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);Map<String,Object> unix=Files.readAttributes(path,"unix:dev,ino,mode,nlink,ctime",LinkOption.NOFOLLOW_LINKS);
   if(a.isSymbolicLink()||(directory?!a.isDirectory():!a.isRegularFile())||!directory&&((Number)unix.get("nlink")).intValue()!=1)throw new IllegalStateException("unsafe choice path");
   return new PurpleGlobalChoiceFiles.Mark(unix.get("dev")+":"+unix.get("ino"),directory?"":a.size()+":"+a.lastModifiedTime()+":"+unix.get("ctime"),((Number)unix.get("mode")).intValue());
  }
  public boolean write(File file,PurpleGlobalChoiceFiles.Mark owned,byte[] bytes){++writes;Runnable hook=duringWrite;duringWrite=null;if(hook!=null)hook.run();return PurpleGlobalChoiceFiles.writeNative(file.getPath(),owned.identity,bytes);}
 };}
 public static void ordinaryFollowControl(){PurpleWriter.Capture captured=PurpleWriter.captureResult(1,false,()->true,null);if(captured.session==null||!captured.session.appWide)throw new AssertionError("ordinary Follow routing preserved");}
}
