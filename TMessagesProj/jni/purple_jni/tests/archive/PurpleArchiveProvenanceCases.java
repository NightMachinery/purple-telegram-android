package org.telegram.messenger.purple;

import java.io.File;
import java.nio.ByteBuffer;
import java.security.Security;
import java.util.Arrays;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;

public final class PurpleArchiveProvenanceCases {
    static PurpleArchiveSqlite database()throws Exception{java.lang.reflect.Field field=PurpleArchiveStore.class.getDeclaredField("database");field.setAccessible(true);return (PurpleArchiveSqlite)field.get(worker.store);}
    public static void main(String[] args)throws Exception{
        System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(args[1]);check(root.mkdir(),"exclusive provenance fixture");
        ApplicationLoader.files=new File(root,"files");check(ApplicationLoader.files.mkdir(),"app files");UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=s->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"archive ready");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        byte[] data=bytes(70001);TLRPC.Message message=message(3600,"two producer sessions",data.length);
        org.telegram.messenger.MessagesStorage storage=cache(message);
        org.telegram.messenger.FileLoadOperation first=bind(message),second=bind(message);
        PurpleArchiveAcquiredSource.bytes(first,0,ByteBuffer.wrap(Arrays.copyOf(data,65536)),65536,true);
        storage.getStorageQueue().drain();on(()->null);on(()->null);
        PurpleArchiveAcquiredSource.bytes(second,65536,ByteBuffer.wrap(Arrays.copyOfRange(data,65536,data.length)),data.length-65536,true);
        on(()->null);on(()->null);on(()->null);
        check(!complete(message,"document_12600"),"two complementary producers cannot combine provenance into Complete");
        check(on(()->worker.store.hasGaps()),"mixed provenance records an explicit gap");
        PurpleArchiveAcquiredSource.failed(first);PurpleArchiveAcquiredSource.failed(second);on(()->null);on(()->null);
        org.telegram.messenger.FileLoadOperation fresh=bind(message);
        PurpleArchiveAcquiredSource.bytes(fresh,0,ByteBuffer.wrap(data),data.length,true);storage.getStorageQueue().drain();
        await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"one coherent fresh session drains");
        check(complete(message,"document_12600"),"one full fresh producer can validate its own bytes after terminal refusal");
        on(()->{
            TLRPC.Message unproven=message(3601,"full coverage without a source validator",97);
            PurpleArchiveStaging staging=stage(unproven,new java.util.concurrent.atomic.AtomicBoolean(true));staging.piece(0,bytes(97));
            refuses(staging::finish,"coverage alone has no consistency authority");check(staging.directory.read("consistency",70000).bytes==null,"full coverage never mints an admission");
            staging.refuse(()->true);refuses(()->PurpleArchiveStaging.reopen(worker.store,staging.id,()->true),"a terminal refused job cannot reopen");
            staging.closeFinalizer();return null;
        });
        on(()->{
            TLRPC.Message cancelled=message(3602,"validated cache cancelled before publication",97);File cache=new File(root,"coherent-cache");java.nio.file.Files.write(cache.toPath(),bytes(97));
            PurpleArchiveAcquiredSource.Entry entry=cachedEntry(cancelled,cache);while(!entry.complete)entry.fileStep();String id=entry.staging.id;
            entry.close();refuses(()->PurpleArchiveStaging.reopen(worker.store,id,()->true),"cancellation after source validation still refuses an unpublished job");
            try(PurpleArchiveSlice slice=new PurpleArchiveSlice(worker.store,key(cancelled),null)){check(slice.head.getJSONArray("media").getJSONObject(0).getInt("state")!=2,"validated but cancelled source is never Complete");}
            TLRPC.Message retry=message(3603,"refusal SQL retry",97);PurpleArchiveStaging pending=stage(retry,new java.util.concurrent.atomic.AtomicBoolean(true));pending.piece(0,bytes(97));
            PurpleArchiveCore.requireDone(database().run("PRAGMA query_only=ON"));
            try{refuses(()->pending.refuse(()->true),"failed SQL keeps the authenticated terminal obligation");}finally{PurpleArchiveCore.requireDone(database().run("PRAGMA query_only=OFF"));}
            refuses(()->PurpleArchiveStaging.reopen(worker.store,pending.id,()->true),"a durable terminal marker refuses reopen even before SQL acknowledgement");
            PurpleArchiveStaging freshJob=stage(retry,new java.util.concurrent.atomic.AtomicBoolean(true));check(!freshJob.id.equals(pending.id),"next admission retires the failed SQL obligation before a fresh job");
            freshJob.refuse(()->true);
            TLRPC.Message inline=message(3604,"inconsistent inline source",97);PurpleArchiveStaging old=stage(inline,new java.util.concurrent.atomic.AtomicBoolean(true));old.piece(0,new byte[]{bytes(97)[0]});
            byte[] changed=bytes(97);changed[0]^=1;JSONObject body=PurpleArchiveNormalize.body(inline).put("inlineMedia",new org.json.JSONArray().put(new JSONObject().put("identity","document_12604").put("bytes",PurpleArchiveCore.base64(changed))));
            PurpleArchiveInline.capture(worker.store,new PurpleArchiveCapture.Input(key(inline).toString(),body.toString(),PurpleArchiveOrigin.baseline(),false),()->true);
            refuses(()->PurpleArchiveStaging.reopen(worker.store,old.id,()->true),"inline source refusal is terminal too");return null;
        });
        on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();
        System.out.println("ARCHIVE_PROVENANCE_CHECKS="+checks);
    }
}
