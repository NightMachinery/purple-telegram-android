package org.telegram.messenger.purple;
import java.io.File;
import java.nio.file.Files;
import java.security.Security;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
public final class PurpleArchiveSelectionCases {
 static volatile boolean missing;
 static int checks;
 static void check(boolean v,String s){++checks;if(!v)throw new AssertionError(s);}
 static void drain(){ArrayList<Runnable> x;synchronized(AndroidUtilities.class){x=new ArrayList<>(AndroidUtilities.pending);AndroidUtilities.pending.clear();}for(Runnable r:x)r.run();}
 static void await(java.util.function.BooleanSupplier p)throws Exception{long end=System.nanoTime()+10_000_000_000L;while(!p.getAsBoolean()&&System.nanoTime()<end){drain();Thread.sleep(5);}check(p.getAsBoolean(),"fixture timed out");}
 static void refresh(int slot)throws Exception{AtomicReference<Boolean> done=new AtomicReference<>();PurpleArchive.refresh(slot,()->done.set(true));await(()->done.get()!=null);}
 static byte[] sha(File f)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));}
 static File db(PurpleArchive.Worker w)throws Exception{return w.queue.submit(()->w.store.directory.file("archive.sqlite")).get(10,TimeUnit.SECONDS);}
 public static void main(String[] args)throws Exception{
  System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());
  File root=new File(args[1]);check(root.mkdir(),"exclusive fixture");ApplicationLoader.files=new File(root,"files");check(ApplicationLoader.files.mkdir(),"app files");
  for(int slot=0;slot<2;slot++){UserConfig.getInstance(slot).active=true;UserConfig.getInstance(slot).user=slot==0?11:22;}
  UserConfig.selectedAccount=0;
  PurpleArchiveLogin.source=slot->slot==0&&missing?null:"11111111111111111111111111111111";
  refresh(0);refresh(1);check(PurpleArchive.status(0).state.equals("Ready")&&PurpleArchive.status(1).state.equals("Ready"),"ready fixtures");
  PurpleArchive.Worker a=PurpleArchive.existing(PurpleAccountFiles.capture(0)),b=PurpleArchive.existing(PurpleAccountFiles.capture(1));File aDb=db(a),bDb=db(b),bWal=new File(bDb+"-wal");byte[] bBefore=sha(bDb),bWalBefore=sha(bWal);
  if(args[2].equals("viewer")||args[2].equals("hook")){
   TLRPC.TL_message message=new TLRPC.TL_message();message.id=77;message.message="synthetic A body";message.date=100;message.dialog_id=55;message.peer_id=new TLRPC.TL_peerUser();message.peer_id.user_id=55;message.from_id=new TLRPC.TL_peerUser();message.from_id.user_id=44;message.flags=256;
   ArrayList<TLRPC.Message> list=new ArrayList<>();list.add(message);PurpleArchiveCapture.beforeWrite(0,new org.telegram.SQLite.SQLiteDatabase((sql,values)->new Object[0][]),PurpleArchiveCapture.observations(0,list,0));
   PurpleArchiveViewerModel viewer=new PurpleArchiveViewerModel(0,message);AtomicReference<PurpleArchiveViewerModel.Page> page=new AtomicReference<>();
   boolean admitted=viewer.next(page::set);a.queue.submit(()->{}).get(10,TimeUnit.SECONDS);
   UserConfig.selectedAccount=1;if(args[2].equals("hook"))PurpleArchiveViewerModel.selectionChanged(1);drain();
   check(admitted&&page.get()==null,"queued A page is revoked after account switch");
   check(!viewer.next(page::set),"Older refuses after selection changes");
   boolean refused=false;try{viewer.restart();}catch(IllegalStateException expected){refused=true;}check(refused,"Refresh refuses after selection changes");
   UserConfig.selectedAccount=0;PurpleArchiveViewerModel.selectionChanged(0);
   check(!viewer.next(page::set),"switching back cannot revive old viewer");refused=false;try{viewer.restart();}catch(IllegalStateException expected){refused=true;}check(refused,"old Refresh remains revoked after switch back");
   viewer.close();System.out.println("SELECTION_CASE queued_page_revoked=true older_and_refresh_refuse=true");
  }else throw new AssertionError("mode");
  check(Arrays.equals(bBefore,sha(bDb))&&Arrays.equals(bWalBefore,sha(bWal)),"B DB and WAL unchanged");System.out.println("B_ISOLATION: db_and_wal_unchanged=true");System.out.println("ARCHIVE_SELECTION_CHECKS="+checks);
 }
}
