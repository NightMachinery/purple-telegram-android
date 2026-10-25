package org.telegram.messenger.purple;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.Security;
import java.util.Arrays;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
public final class RefusedRetryAdmissionProbe {
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root=new File(args[1]); if(!root.mkdir())throw new AssertionError("exclusive fixture");
        ApplicationLoader.files=new File(root,"files");if(!ApplicationLoader.files.mkdir())throw new AssertionError("files");
        UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;
        PurpleArchiveLogin.source=s->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"A ready");
        worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        TLRPC.Message m=message(3500,"refused cache followed by incomplete fresh stream",70001);
        byte[] original=bytes(70001),changed=original.clone();for(int i=0;i<changed.length;++i)changed[i]^=0x55;
        byte[] mixed=original.clone();System.arraycopy(changed,65536,mixed,65536,mixed.length-65536);
        String[] retained=on(()->{
            File file=new File(root,"mutable-cache");Files.write(file.toPath(),original);
            PurpleArchiveAcquiredSource.Entry e=cachedEntry(m,file);e.fileStep();Files.write(file.toPath(),changed);
            boolean refused=false;try{for(int i=0;i<20&&!e.complete;++i)e.fileStep();}catch(Exception expected){refused=true;}
            if(!refused||e.complete)throw new AssertionError("cached copy must refuse");
            String coverage=e.staging.call("stagingInspect",new JSONObject().put("header",e.staging.head)).getString("covered");
            if(!coverage.equals("70001"))throw new AssertionError("fixture must retain refused coverage");
            if(e.staging.directory.read("consistency",70000).bytes!=null)throw new AssertionError("refusal wrote admission immediately");
            String[] result={e.staging.id,e.staging.directory.file.getAbsolutePath()};e.close();
            PurpleArchiveGaps.record(worker.store,"acquired_media_gap",System.currentTimeMillis()/1000);return result;
        });
        org.telegram.messenger.MessagesStorage storage=cache(m);
        org.telegram.messenger.FileLoadOperation operation=bind(m);
        PurpleArchiveAcquiredSource.bytes(operation,0,ByteBuffer.wrap(Arrays.copyOf(original,65536)),65536,true);
        storage.getStorageQueue().drain();
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until&&!complete(m,"document_12500"))Thread.sleep(20);
        boolean completed=complete(m,"document_12500");
        boolean admission=new File(retained[1],"consistency").isFile();
        if(completed)on(()->{
            String blob=PurpleArchiveCore.call("blobToken",worker.store.arguments().put("blob",hash(mixed)),worker.store.crypto()).getString("token");
            byte[] frame=worker.store.directory.child("blobs",true).child(blob,true).read("chunk-0",1048576+4608).bytes;
            byte[] plain=PurpleArchiveCore.mediaNative(worker.store.binding.account,worker.store.binding.store,worker.store.binding.key,blob,mixed.length,0,frame,false,worker.store.crypto());
            if(!Arrays.equals(plain,mixed))throw new AssertionError("unexpected canonical bytes");Arrays.fill(plain,(byte)0);return null;
        });
        System.out.println("RETRY: first copy refused=true; new observed bytes=65536/70001; admission="+admission+"; Complete="+completed+"; canonical mixture equals neither coherent source="+(!Arrays.equals(mixed,original)&&!Arrays.equals(mixed,changed)));
        PurpleArchiveAcquiredSource.failed(operation);
        on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();
        if(admission||completed)throw new AssertionError("A refused copy must not gain admission from an incomplete fresh stream");
    }
}
