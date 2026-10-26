package org.telegram.messenger.purple;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.Security;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.telegram.messenger.*;
public final class PurpleArchiveLogoutCrashCases {
 static PurpleArchive.Worker owner(String account)throws Exception{java.lang.reflect.Field f=PurpleArchive.class.getDeclaredField("workers");f.setAccessible(true);return (PurpleArchive.Worker)((Map<?,?>)f.get(null)).get(account);}
 static int checks;
 static void check(boolean v,String s){++checks;if(!v)throw new AssertionError(s);}
 static void await(java.util.function.BooleanSupplier p)throws Exception{long end=System.nanoTime()+10_000_000_000L;while(!p.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);check(p.getAsBoolean(),"timed out");}
 static byte[] sha(File f)throws Exception{return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()));}
 static PurpleArchiveBinding binding(String account)throws Exception{PurpleArchivePaths p=new PurpleArchivePaths(ApplicationLoader.files,account);return new PurpleArchiveBinding(account,PurpleArchiveBinding.json(p.metadata.read("binding.json",65536).bytes));}
 static File dbSafe(PurpleArchiveBinding b){return new File(new File(new File(ApplicationLoader.files,"purple/content-archive/"+b.account),b.store),"archive.sqlite");}
 static File db(PurpleArchiveBinding b)throws Exception{return new File(new File(new PurpleArchivePaths(ApplicationLoader.files,b.account).accountsRoot.file(b.account),b.store),"archive.sqlite");}
 public static void main(String[] args)throws Exception{
  System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(args[1]);boolean seed=args[2].startsWith("seed");if(seed)check(root.mkdir(),"exclusive fixture");ApplicationLoader.files=new File(root,"files");if(seed)check(ApplicationLoader.files.mkdir(),"app files");
  PurpleArchiveLogin.source=slot->"11111111111111111111111111111111";File fixture=new File(root,"synthetic-state.json");
  if(seed){
   for(int slot=0;slot<2;slot++){UserConfig.getInstance(slot).active=true;UserConfig.getInstance(slot).user=slot==0?11:22;PurpleArchive.refresh(slot,()->{});final int s=slot;await(()->PurpleArchive.status(s).state.equals("Ready"));}
   PurpleArchiveBinding a=binding("0-11"),b=binding("0-22");PurpleArchive.Worker owner=owner("0-11");
   owner.queue.submit(()->{
    try {
     for(String name:new String[]{"lifecycle","staging"}) {
      PurpleArchiveFiles.Directory directory=owner.store.directory.child(name,true).child("crash-owned",true);
      byte[] frame=PurpleArchiveCore.unbase64(PurpleArchiveCore.call("journalSeal",owner.store.arguments().put("token","eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee").put("bytes",PurpleArchiveCore.base64(new byte[]{1,2,3})),owner.store.crypto()).getString("bytes"));
      directory.publish("retained",directory.read("retained",65536),frame);
     }
    }catch(Exception e){throw new RuntimeException(e);}
   }).get(10,TimeUnit.SECONDS);
   File adb=db(a),bdb=db(b),bwal=new File(bdb+"-wal");JSONObject state=new JSONObject().put("a",a.value).put("b",b.value).put("bDbHash",PurpleArchiveCore.base64(sha(bdb))).put("bWalHash",PurpleArchiveCore.base64(sha(bwal)));JSONObject keys=new JSONObject();for(Map.Entry<String,javax.crypto.SecretKey> e:PurpleArchivePlatformProvider.keys.entrySet())keys.put(e.getKey(),PurpleArchiveCore.base64(e.getValue().getEncoded()));state.put("syntheticKeys",keys);
   CountDownLatch blocked=new CountDownLatch(1);owner.queue.execute(()->{blocked.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){throw new AssertionError(e);}});check(blocked.await(5,TimeUnit.SECONDS),"owner blocked");
   long start=System.nanoTime();PurpleArchive.beforeLogout(0);UserConfig.getInstance(0).active=false;UserConfig.getInstance(0).user=0;PurpleAccountFiles.identityChanged(0);check(System.nanoTime()-start<500_000_000L,"logout returns promptly");
   PurpleArchivePaths paths=new PurpleArchivePaths(ApplicationLoader.files,"0-11");check(paths.metadata.read("removing.json",65536).bytes==null&&adb.exists(),"no durable intent before queued cleanup");state.put("aLoggedOut",true);Files.write(fixture.toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
   System.out.println("LOGOUT_CRASH_CASE signed_out=true pre_intent_process_death=true");
   System.out.println("ARCHIVE_LOGOUT_CRASH_CHECKS="+checks);System.exit(0);
  }else{
   JSONObject state=new JSONObject(new String(Files.readAllBytes(fixture.toPath()),StandardCharsets.UTF_8));check(state.getBoolean("aLoggedOut"),"signed-out state persisted");UserConfig.getInstance(0).active=false;UserConfig.getInstance(0).user=0;UserConfig.getInstance(1).active=true;UserConfig.getInstance(1).user=22;
   JSONObject keys=state.getJSONObject("syntheticKeys");for(String alias:keys.keySet())PurpleArchivePlatformProvider.keys.put(alias,new SecretKeySpec(PurpleArchiveCore.unbase64(keys.getString(alias)),"AES"));
   if(args[2].equals("reuse")){
    java.lang.reflect.Method factory=PurpleArchive.class.getDeclaredMethod("worker",String.class);factory.setAccessible(true);PurpleArchive.Worker queued=(PurpleArchive.Worker)factory.invoke(null,"0-11");
    CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);queued.queue.execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}});check(blocked.await(5,TimeUnit.SECONDS),"inactive owner held before recovery");
    PurpleArchive.initialize();java.lang.reflect.Field scheduler=PurpleArchive.class.getDeclaredField("recovery");scheduler.setAccessible(true);((ScheduledExecutorService)scheduler.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);
    check(!queued.queue.getQueue().isEmpty(),"inactive cleanup queued before slot reuse");UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;release.countDown();await(()->queued.retired);
    PurpleArchiveBinding retained=new PurpleArchiveBinding("0-11",state.getJSONObject("a"));check(db(retained).exists()&&PurpleArchivePlatformProvider.keys.containsKey(retained.alias()),"queued cleanup refuses a newer active login");
    PurpleArchive.loginChanging(0,0,UserConfig.getInstance(0).getClientUserId());PurpleArchiveLogin.source=slot->slot==0?"22222222222222222222222222222222":"11111111111111111111111111111111";PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"));PurpleArchiveBinding fresh=binding("0-11");check(!fresh.store.equals(retained.store),"new login uses a new store");
    java.lang.reflect.Method scan=PurpleArchive.class.getDeclaredMethod("recoverRetained");scan.setAccessible(true);scan.invoke(null);check(db(fresh).exists()&&PurpleArchivePlatformProvider.keys.containsKey(fresh.alias()),"startup cleanup preserves newer login store");
    PurpleArchiveBinding other=new PurpleArchiveBinding("0-22",state.getJSONObject("b"));check(Arrays.equals(PurpleArchiveCore.unbase64(state.getString("bDbHash")),sha(db(other)))&&Arrays.equals(PurpleArchiveCore.unbase64(state.getString("bWalHash")),sha(new File(db(other)+"-wal"))),"B unchanged after slot reuse");System.out.println("ARCHIVE_LOGOUT_REUSE_CHECKS="+checks);return;
   }
   PurpleArchive.initialize();java.lang.reflect.Field field=PurpleArchive.class.getDeclaredField("recovery");field.setAccessible(true);((ScheduledExecutorService)field.get(null)).submit(()->{}).get(10,TimeUnit.SECONDS);
   PurpleArchiveBinding a=new PurpleArchiveBinding("0-11",state.getJSONObject("a")),b=new PurpleArchiveBinding("0-22",state.getJSONObject("b"));await(()->!dbSafe(a).getParentFile().exists()&&!PurpleArchivePlatformProvider.keys.containsKey(a.alias()));check(PurpleArchive.status(0).state.equals("Signed out"),"A remains signed out");
   check(!dbSafe(a).getParentFile().exists(),"restart erases lifecycle and nested staging directories with the old store");
   check(Arrays.equals(PurpleArchiveCore.unbase64(state.getString("bDbHash")),sha(db(b)))&&Arrays.equals(PurpleArchiveCore.unbase64(state.getString("bWalHash")),sha(new File(db(b)+"-wal"))),"B db and WAL unchanged");
   System.out.println("ARCHIVE_LOGOUT_CRASH_CHECKS="+checks);
  }
 }
}
