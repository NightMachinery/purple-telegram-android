package org.telegram.messenger.purple;
import java.io.File;
import java.security.Security;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
public final class PurpleArchiveReleasePressureCases {
 public static void main(String[] args)throws Exception{
  System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(args[1]);check(root.mkdir(),"exclusive release-pressure fixture");ApplicationLoader.files=new File(root,"files");check(ApplicationLoader.files.mkdir(),"release fixture app files");ApplicationLoader.applicationContext=new android.content.Context();
  UserConfig.selectedAccount=0;UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=slot->"11111111111111111111111111111111";PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"release fixture Ready");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
  org.telegram.tgnet.TLRPC.Message message=message(4200,"release pressure",17);PurpleArchiveB3bCases.retain(message,bytes(17));PurpleArchiveMediaAccess.Capability cap=PurpleArchiveB3bCases.open(message);check(cap!=null,"release fixture capability Ready");
  CountDownLatch entered=new CountDownLatch(1),unlock=new CountDownLatch(1);worker.queue.execute(()->{entered.countDown();try{unlock.await();}catch(InterruptedException e){throw new RuntimeException(e);}});check(entered.await(5,TimeUnit.SECONDS),"release owner is blocked");
  try{for(int i=0;i<1000;++i)cap.close();System.out.println("RELEASE_QUEUE_SIZE="+worker.queue.getQueue().size());check(worker.queue.getQueue().size()<=2,"repeated close queues at most one release plus an earlier validation");}finally{unlock.countDown();}
  await(()->cap.released,"release completes when owner resumes");on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();System.out.println("ARCHIVE_RELEASE_PRESSURE_CHECKS="+checks);
 }
}
