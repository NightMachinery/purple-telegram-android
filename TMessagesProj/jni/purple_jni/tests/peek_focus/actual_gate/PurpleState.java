package org.telegram.messenger.purple;
final class PurpleState {
 static byte[] data;
 static boolean failed;
 static Runnable beforeSave,afterSave;
 static int writes;
 static synchronized byte[] read(){return data==null?null:data.clone();}
 static synchronized boolean replaceIfUnchanged(byte[] before,byte[] next){
  if(failed||!java.util.Arrays.equals(before,data))return false;
  if(beforeSave!=null){Runnable r=beforeSave;beforeSave=null;r.run();}
  data=next.clone();++writes;
  if(afterSave!=null){Runnable r=afterSave;afterSave=null;r.run();}
  return true;
 }
}
