package org.telegram.messenger.purple;
import java.io.File;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
import java.nio.file.Files;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
public final class TornRecoveryProbe {
    static boolean recoveredComplete,recoveredGap;
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root=new File(args[1]); boolean seed=args[2].equals("seed");
        if(seed && !root.mkdir()) throw new AssertionError("exclusive fixture");
        ApplicationLoader.files=new File(root,"files"); if(seed && !ApplicationLoader.files.mkdir()) throw new AssertionError("files");
        if(!seed){JSONObject saved=new JSONObject(new String(Files.readAllBytes(new File(root,"test-platform-state.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));PurpleArchivePlatformProvider.keys.put(saved.getString("alias"),new javax.crypto.spec.SecretKeySpec(PurpleArchiveCore.unbase64(saved.getString("key")),"AES"));}
        UserConfig.getInstance(0).active=true; UserConfig.getInstance(0).user=11;
        PurpleArchiveLogin.source=slot->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{}); await(()->PurpleArchive.status(0).state.equals("Ready"),"worker ready"); worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        TLRPC.Message m=message(3300,"torn acquired cache",70001);
        if(seed) on(()->{
            byte[] original=bytes(70001), changed=original.clone(); for(int i=0;i<changed.length;++i)changed[i]^=0x55;
            File file=new File(root,"mutable-cache"); Files.write(file.toPath(),original);
            PurpleArchiveAcquiredSource.Entry entry=cachedEntry(m,file); String id=entry.staging.id;
            boolean refused=false;
            try {
                if(args.length>3 && args[3].equals("between")){for(int i=0;i<8&&entry.pass==0;++i)entry.fileStep();if(entry.pass!=1)throw new AssertionError("first pass not finished");}
                else entry.fileStep();
                Files.write(file.toPath(),changed);
                for(int i=0;i<20&&!entry.complete;++i)entry.fileStep();
            } catch(Exception expected){refused=true;}
            String coverage="refused";
            try { coverage=entry.staging.call("stagingInspect",new JSONObject().put("header",entry.staging.head)).getString("covered"); } catch(Exception ignored) {}
            if(!refused||entry.complete)throw new AssertionError("live source must refuse");
            Files.write(new File(root,"binding-id").toPath(),id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("SEED: changing source refused; Complete=false; durableCovered="+coverage+"/70001");
            JSONObject saved=new JSONObject().put("alias",worker.store.binding.alias()).put("key",PurpleArchiveCore.base64(PurpleArchivePlatformProvider.keys.get(worker.store.binding.alias()).getEncoded()));
            Files.write(new File(root,"test-platform-state.json").toPath(),saved.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            PurpleArchiveGaps.record(worker.store,"acquired_media_gap",System.currentTimeMillis()/1000);
            entry.close();return null;
        });
        else {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15); boolean complete=false;
            while(System.nanoTime()<until){complete=complete(m,"document_12300");if(complete)break;Thread.sleep(20);}
            final boolean result=complete;
            on(()->{
                String id=new String(Files.readAllBytes(new File(root,"binding-id").toPath()),java.nio.charset.StandardCharsets.UTF_8);
                byte[] original=bytes(70001), changed=original.clone();for(int i=0;i<changed.length;++i)changed[i]^=0x55;
                byte[] mixed=original.clone();System.arraycopy(changed,65536,mixed,65536,mixed.length-65536);
                if(args.length>3 && args[3].equals("between"))mixed=original;
                if(result){
                    PurpleArchiveStore store=worker.store;
                    String blob=PurpleArchiveCore.call("blobToken",store.arguments().put("blob",hash(mixed)),store.crypto()).getString("token");
                    byte[] frame=store.directory.child("blobs",true).child(blob,true).read("chunk-0",1048576+4608).bytes;
                    byte[] plain=PurpleArchiveCore.mediaNative(store.binding.account,store.binding.store,store.binding.key,blob,mixed.length,0,frame,false,store.crypto());
                    if(!Arrays.equals(plain,mixed))throw new AssertionError("Unexpected recovered bytes");
                    Arrays.fill(plain,(byte)0);
                }
                recoveredComplete=result; recoveredGap=worker.store.hasGaps();
                System.out.println("RECOVERY: gap="+recoveredGap+"; Complete="+result+"; published mixed image equals neither coherent source="+(!Arrays.equals(mixed,original)&&!Arrays.equals(mixed,changed)));
                return null;
            });
        }
        on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();
        if(!seed && (recoveredComplete || !recoveredGap))throw new AssertionError("Expected recovery to remain a gap, not Complete");
    }
}
