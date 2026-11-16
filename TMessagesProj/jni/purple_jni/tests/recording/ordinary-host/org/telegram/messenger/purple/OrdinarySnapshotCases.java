package org.telegram.messenger.purple;
import java.io.*;import java.nio.file.*;import org.telegram.messenger.*;
public class OrdinarySnapshotCases{
 static int checks,failures;static void check(boolean value,String why){checks++;if(!value){failures++;System.err.println(why);}}
 public static void main(String[] args)throws Exception{
  File root=new File(args[0]);if(!root.mkdir())throw new IOException("fresh fixture root");int index=0;
  for(boolean round:new boolean[]{false,true})for(int boundary=0;boundary<5;boundary++){
   File directory=new File(root,"case"+(index++));if(!directory.mkdir())throw new IOException("fresh case");FileLoader.directory=directory;
   File source=new File(directory,"source");Files.write(source.toPath(),new byte[]{1,2,3,4});
   UserConfig.identity=9;SendMessagesHelper.sends=0;SendMessagesHelper.sentUser=0;SendMessagesHelper.documentUser=0;
   MessagesStorage.replace=boundary==1;PurpleRecordingFiles.replace=boundary==2;PurpleRecordingFiles.copies=0;
   PurpleRecordingEntry entry=new PurpleRecordingEntry();entry.media=source.getPath();entry.video=round;
   int[] done={0};OrdinaryRoute.send(entry,0,100,0,1,false,ok->done[0]++);
   if(boundary==4)UserConfig.identity=10;
   PurpleRecordingRegistry.flush();if(boundary==3)UserConfig.identity=10;AndroidUtilities.flush();
   check(done[0]==1,"ordinary completion fires once");
   check(source.isFile()&&Files.readAllBytes(source.toPath()).length==4,"ordinary send preserves original bin file");
   if(boundary==0){check(SendMessagesHelper.sends==1&&SendMessagesHelper.sentUser==9,"unchanged account sends as captured user");if(!round)check(SendMessagesHelper.documentUser==9,"voice document records captured user");}
   else check(SendMessagesHelper.sends==0,"slot replacement refuses "+(round?"round":"voice")+" boundary "+boundary);
   if(boundary==4)check(PurpleRecordingFiles.copies==0,"replacement before worker refuses before copy");
  }
  if(failures!=0){System.err.println("ordinary bin snapshot: "+failures+" of "+checks+" checks failed");System.exit(1);}System.out.println("ORDINARY_BIN_SNAPSHOT_CHECKS="+checks);
 }
}
