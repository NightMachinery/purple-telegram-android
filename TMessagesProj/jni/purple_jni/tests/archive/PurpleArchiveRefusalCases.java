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
public final class PurpleArchiveRefusalCases {
 static PurpleArchive.Worker owner(String account)throws Exception{java.lang.reflect.Field f=PurpleArchive.class.getDeclaredField("workers");f.setAccessible(true);return (PurpleArchive.Worker)((java.util.Map<?,?>)f.get(null)).get(account);}
 static volatile boolean missing;
 static String mode;
 static int checks;
 static void check(boolean v,String s){++checks;if(!v)throw new AssertionError(s);}
 static void drain(){ArrayList<Runnable> x;synchronized(AndroidUtilities.class){x=new ArrayList<>(AndroidUtilities.pending);AndroidUtilities.pending.clear();}for(Runnable r:x)r.run();}
 static void await(java.util.function.BooleanSupplier p)throws Exception{long end=System.nanoTime()+10_000_000_000L;while(!p.getAsBoolean()&&System.nanoTime()<end){drain();Thread.sleep(5);}check(p.getAsBoolean(),"fixture timed out");}
 static void refresh(int slot)throws Exception{AtomicReference<Boolean> done=new AtomicReference<>();PurpleArchive.refresh(slot,()->done.set(true));await(()->done.get()!=null);}
 static byte[] sha(File f)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));}
 static File db(PurpleArchive.Worker w)throws Exception{return w.queue.submit(()->w.store.directory.file("archive.sqlite")).get(10,TimeUnit.SECONDS);}
 public static void main(String[] args)throws Exception{
  mode=args[2];System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());
  File root=new File(args[1]);check(root.mkdir(),"exclusive fixture");ApplicationLoader.files=new File(root,"files");check(ApplicationLoader.files.mkdir(),"app files");
  for(int slot=0;slot<2;slot++){UserConfig.getInstance(slot).active=true;UserConfig.getInstance(slot).user=slot==0?11:22;}
  UserConfig.selectedAccount=0;
  PurpleArchiveLogin.source=slot->slot==0&&missing?(mode.equals("invalid")?"invalid":null):"11111111111111111111111111111111";
  refresh(0);refresh(1);check(PurpleArchive.status(0).state.equals("Ready")&&PurpleArchive.status(1).state.equals("Ready"),"ready fixtures");
  PurpleArchive.Worker a=owner("0-11"),b=owner("0-22");File aDb=db(a),bDb=db(b),bWal=new File(bDb+"-wal");byte[] bBefore=sha(bDb),bWalBefore=sha(bWal);
  if(args[2].equals("fence")||args[2].equals("intent")){
   PurpleArchivePaths paths=new PurpleArchivePaths(ApplicationLoader.files,"0-11");File damaged=paths.metadata.file(mode.equals("fence")?"fence.json":"removing.json");Files.write(damaged.toPath(),new byte[65537]);refresh(0);check(!PurpleArchive.status(0).state.equals("Ready"),"damaged cleanup metadata refuses");PurpleArchive.RemovalRequest request=PurpleArchive.prepareRemoval(0);check(request!=null&&aDb.exists(),"damaged metadata retained until confirmation");check(PurpleArchive.remove(request,()->{}),"single confirmed discard admitted");await(()->request.completed);check(!aDb.exists(),"single confirmation erases refused account archive");
  }else if(args[2].equals("malformed")){
   PurpleArchivePaths paths=new PurpleArchivePaths(ApplicationLoader.files,"0-11");Files.write(paths.metadata.file("login.json").toPath(),new byte[]{123});
   refresh(0);check(!PurpleArchive.status(0).state.equals("Ready"),"malformed refuses");
   PurpleArchive.RemovalRequest request=PurpleArchive.prepareRemoval(0);check(request!=null,"positive-control discard request");check(PurpleArchive.remove(request,()->{}),"positive-control discard admitted");await(()->request.completed);check(!aDb.exists(),"positive-control erasure");
   System.out.println("REFUSAL_CASE malformed=true confirmed_discard=true");
  }else if((args[2].equals("oversized")||args[2].equals("unreadable"))){
   PurpleArchivePaths paths=new PurpleArchivePaths(ApplicationLoader.files,"0-11");File admission=paths.metadata.file("login.json");Files.write(admission.toPath(),new byte[65537]);byte[] before=sha(admission);if(mode.equals("unreadable"))Files.setPosixFilePermissions(admission.toPath(),java.util.Collections.emptySet());
   refresh(0);check(!PurpleArchive.status(0).state.equals("Ready"),"oversized admission refuses");
   PurpleArchive.RemovalRequest request=PurpleArchive.prepareRemoval(0);
   check(request!=null,"explicit discard survives unreadable or oversized admission");check(aDb.exists()&&Files.size(admission.toPath())==65537,"no automatic destruction before confirmation");if(!mode.equals("unreadable"))check(Arrays.equals(before,sha(admission)),"oversized metadata unchanged");check(PurpleArchive.remove(request,()->{}),"confirmed discard accepted");await(()->request.completed);check(!aDb.exists(),"refused archive erased after explicit confirmation");
   System.out.println("REFUSAL_CASE oversized_or_unreadable=true confirmed_discard=true");
  }else if((args[2].equals("missing")||args[2].equals("invalid"))){
   PurpleAccountFiles.identityChanged(0);await(()->a.retired);missing=true;refresh(0);
   check(!PurpleArchive.status(0).state.equals("Ready"),"missing current nonce refuses");PurpleArchive.RemovalRequest request=PurpleArchive.prepareRemoval(0);check(request!=null,"missing or invalid current nonce does not block explicit discard");check(aDb.exists(),"no automatic destruction before confirmation");check(PurpleArchive.remove(request,()->{}),"confirmed nonce-free discard accepted");await(()->request.completed);check(!aDb.exists(),"nonce-free explicit discard erases only A");
   System.out.println("REFUSAL_CASE missing_or_invalid_nonce=true confirmed_discard=true");
  }else throw new AssertionError("mode");
  check(Arrays.equals(bBefore,sha(bDb))&&Arrays.equals(bWalBefore,sha(bWal)),"B DB and WAL unchanged");System.out.println("B_ISOLATION: db_and_wal_unchanged=true");System.out.println("ARCHIVE_REFUSAL_CHECKS="+checks);
 }
}
