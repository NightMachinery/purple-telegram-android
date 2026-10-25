package org.telegram.messenger.purple;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
public final class B3aSeamProbe {
    public static void main(String[] args)throws Exception{
        System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());
        File root=new File(args[1]);if(!root.mkdir())throw new AssertionError("exclusive fixture");ApplicationLoader.files=new File(root,"files");if(!ApplicationLoader.files.mkdir())throw new AssertionError("files");
        UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=s->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"A ready");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        boolean bug=false;
        if(args[2].equals("logout")){
            UserConfig.getInstance(1).active=true;UserConfig.getInstance(1).user=22;PurpleArchive.refresh(1,()->{});await(()->PurpleArchive.status(1).state.equals("Ready"),"B ready");
            PurpleArchive.Worker other=PurpleArchive.existing(PurpleAccountFiles.capture(1));
            File bdb=other.queue.submit(()->other.store.directory.file("archive.sqlite")).get(20,TimeUnit.SECONDS);byte[] before=Files.readAllBytes(bdb.toPath());
            String[] a=on(()->{
                TLRPC.Message m=message(3400,"pending view once",17);m.media.ttl_seconds=Integer.MAX_VALUE;
                PurpleArchiveLifecycle.Event event=new PurpleArchiveLifecycle.Event(PurpleAccountFiles.capture(0),false,false);
                JSONObject value=PurpleArchiveLifecycle.pendingValue(worker.store,input(m),event,55,m.id,()->true);
                String token=PurpleArchiveCore.call("causeToken",worker.store.arguments().put("task","771"),worker.store.crypto()).getString("token");
                byte[] sealed=PurpleArchiveCore.unbase64(PurpleArchiveCore.call("journalSeal",worker.store.arguments().put("token",token).put("bytes",PurpleArchiveCore.base64(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))),worker.store.crypto()).getString("bytes"));
                PurpleArchiveFiles.Directory dir=worker.store.directory.child("lifecycle",true);dir.publish(token,dir.read(token,65536),sealed);
                return new String[]{worker.store.directory.file.getAbsolutePath(),worker.store.binding.alias()};
            });
            long start=System.nanoTime();PurpleArchive.beforeLogout(0);long elapsed=System.nanoTime()-start;
            UserConfig.getInstance(0).active=false;UserConfig.getInstance(0).user=0;PurpleAccountFiles.identityChanged(0);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end&&!worker.retired&&!worker.status.state.equals("Removal pending"))Thread.sleep(10);
            boolean retained=new File(a[0],"lifecycle").isDirectory(),untouched=Arrays.equals(before,Files.readAllBytes(bdb.toPath()));
            System.out.println("LOGOUT: state="+worker.status.state+"; lifecycle directory retained="+retained+"; A alias remains="+PurpleArchivePlatformProvider.keys.containsKey(a[1])+"; B database unchanged="+untouched+"; synchronous ms="+elapsed/1e6);
            bug=retained;
            other.queue.submit(()->{other.store.drainReaders();other.store.close();other.store=null;return null;}).get(20,TimeUnit.SECONDS);other.queue.shutdown();worker.queue.shutdown();
        }else if(args[2].equals("discard")){
            TLRPC.Message m=message(3402,"opening before explicit discard",97);
            org.telegram.messenger.MessagesStorage storage=cache(m);
            org.telegram.messenger.FileLoadOperation operation=bind(m);
            PurpleArchiveAcquiredSource.bytes(operation,0,ByteBuffer.wrap(bytes(97)),97,true);
            String previousStore=on(()->worker.store.binding.store);
            PurpleArchive.RemovalRequest request=PurpleArchive.prepareRemoval(0);
            if(!PurpleArchive.remove(request,()->{}))throw new AssertionError("confirmation refused");
            await(()->request.completed,"explicit discard completed");
            boolean staleCurrent=sources()>0;
            storage.getStorageQueue().drain();
            await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"old source drained after discard");
            worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
            boolean resurrected=worker!=null&&complete(m,"document_12402");
            String currentStore=worker==null?previousStore:on(()->worker.store.binding.store);
            System.out.println("DISCARD: confirmed=true; old source survived discard="+staleCurrent+"; fresh store="+!previousStore.equals(currentStore)+"; old observed media resurrected Complete="+resurrected);
            bug=resurrected;
            if(worker!=null){on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();}
        }else{
            TLRPC.Message opening=message(3401,"same text and edit date",97),replacement=message(3401,"same text and edit date",97);
            TLRPC.TL_messageEntityBold bold=new TLRPC.TL_messageEntityBold();bold.offset=0;bold.length=4;replacement.entities.add(bold);replacement.flags|=128;
            String oldHead=on(()->capture(opening).getString("head"));String newHead=on(()->capture(replacement).getString("head"));if(oldHead.equals(newHead))throw new AssertionError("fixture must change semantic version");
            org.telegram.messenger.MessagesStorage storage=cache(replacement);
            org.telegram.messenger.FileLoadOperation operation=bind(opening);
            PurpleArchiveAcquiredSource.bytes(operation,0,ByteBuffer.wrap(bytes(97)),97,true);storage.getStorageQueue().drain();
            await(()->{try{return sources()==0;}catch(Exception e){throw new RuntimeException(e);}},"source drained");
            boolean credited=complete(replacement,"document_12401");boolean oldCredited=on(()->{
                try(PurpleArchiveSlice slice=new PurpleArchiveSlice(worker.store,key(opening),null)){
                    JSONObject q=PurpleArchiveCore.call("query",worker.store.arguments().put("query","version").put("key",key(opening)).put("version",oldHead),worker.store.crypto());
                    PurpleArchiveSqlite.Result row=slice.read(q);JSONObject v=slice.add(3,q.getString("id"),0,row.values[0][0].bytes).getJSONObject("value").getJSONObject("snapshot");return v.getJSONArray("media").getJSONObject(0).getInt("state")==2;
                }
            });
            System.out.println("ORIGIN: old opening version Complete="+oldCredited+"; changed semantic head Complete="+credited+"; same message/edit/media/text=true");bug=credited&&!oldCredited;
            on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();
        }
        if(bug)throw new AssertionError("Reviewed seam contract violated");
    }
}
