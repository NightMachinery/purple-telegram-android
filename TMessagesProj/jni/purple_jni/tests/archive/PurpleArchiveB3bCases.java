package org.telegram.messenger.purple;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;

public final class PurpleArchiveB3bCases {
    static PurpleArchiveViewerModel.Attachment attachment(TLRPC.Message message) throws Exception {
        return on(() -> {
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(message), null)) {
                return new PurpleArchiveViewerModel.Attachment(slice.head, slice.head.getJSONArray("media").getJSONObject(0), key(message).toString(), worker.store,
                    worker.loginContext, slice.facts.getString("accountGeneration"), slice.facts.getString("chatGeneration"), PurpleArchiveViewerModel.selectionGeneration());
            }
        });
    }
    static PurpleArchiveMediaAccess.Capability open(PurpleArchiveViewerModel.Attachment attachment) throws Exception {
        AtomicReference<PurpleArchiveMediaAccess.Capability> result = new AtomicReference<>(); AtomicBoolean done = new AtomicBoolean();
        PurpleArchiveMediaAccess.Capability pending = PurpleArchiveMediaAccess.request(PurpleAccountFiles.capture(0), attachment, (value,error)->{result.set(value);done.set(true);});
        check(pending!=null,"original Complete descriptor is admitted asynchronously"); await(done::get,"media preparation delivers");
        return result.get();
    }
    static PurpleArchiveMediaAccess.Capability open(TLRPC.Message message) throws Exception { return open(attachment(message)); }
    static void retain(TLRPC.Message message, byte[] bytes) throws Exception { on(()->{PurpleArchiveStaging s=stage(message,new AtomicBoolean(true));add(s,bytes);check(s.finish(),"fixture retains exact Complete bytes");return null;}); }
    static void releaseLease(PurpleArchiveMediaAccess.Capability cap) throws Exception {
        on(()->{try(PurpleArchiveSlice slice=cap.slice(true)){
            JSONObject plan=PurpleArchiveCore.call("accessRelease",cap.arguments(slice),worker.store.crypto());
            check(worker.store.execute(new PurpleArchiveCore.Program(plan)).committed,"real core lease release commits through ordinary execute");
        }return null;});
    }
    static File chunk(PurpleArchiveMediaAccess.Capability cap,int number)throws Exception{return on(()->worker.store.directory.child("blobs",true).child(cap.blobToken,true).file("chunk-"+number));}
    static void reads(TLRPC.Message message,byte[] bytes)throws Exception{
        PurpleArchiveMediaAccess.Capability cap=open(message);check(cap!=null&&cap.available(),"all chunks and whole hash verify before Ready");
        try(PurpleArchiveMediaDataSource source=new PurpleArchiveMediaDataSource(cap)){
            byte[] out=new byte[70];check(source.readAt(1048566,out,5,60)==10,"random access stops at authenticated chunk boundary");
            check(Arrays.equals(Arrays.copyOfRange(out,5,15),Arrays.copyOfRange(bytes,1048566,1048576)),"random bytes match original plaintext");
            check(source.readAt(1048576,out,0,70)==70&&Arrays.equals(out,Arrays.copyOfRange(bytes,1048576,1048646)),"random seek crosses to the next canonical chunk");
            check(source.readAt(bytes.length,out,0,1)==-1,"verified EOF");
            android.os.Looper.mainForFixture(true);refuses(()->source.readAt(0,out,0,1),"main-thread read refuses rather than waiting");android.os.Looper.mainForFixture(false);
            Thread current=Thread.currentThread();String name=current.getName();try{current.setName("Binder:fixture");refuses(()->source.readAt(0,out,0,1),"Binder read refuses rather than waiting");}finally{current.setName(name);}
            TLRPC.Message newer=message(message.id,"newer caption",bytes.length);on(()->capture(newer));
            check(source.readAt(0,out,0,1)==1&&out[0]==bytes[0],"new head leaves exact historical Complete source readable");
            releaseLease(cap);check(!cap.available(),"a committed lease revocation withdraws cached authority immediately");
            refuses(()->source.readAt(0,out,0,1),"even a cached plaintext page refuses after lease withdrawal");
        }
        cap.close();on(()->null);
    }
    static void provider(TLRPC.Message message,byte[] bytes)throws Exception{
        PurpleArchiveMediaAccess.Capability cap=open(message);check(cap!=null,"provider capability Ready");
        android.net.Uri uri=PurpleArchiveMediaProvider.uri(cap.token);PurpleArchiveMediaProvider provider=new PurpleArchiveMediaProvider();
        check(PurpleArchiveMediaProvider.lookup(uri)==cap,"canonical opaque URI resolves its process-local capability");
        for(String bad:new String[]{uri+"?raw=1",uri+"#other",uri+"/",uri.toString().replace("content:","file:"),"content://other/"+cap.token,"content://org.telegram.fixture.purple_archive_media/%"+cap.token})
            check(PurpleArchiveMediaProvider.lookup(android.net.Uri.parse(bad))==null,"noncanonical or foreign URI refuses");
        CountDownLatch entered=new CountDownLatch(1),unlock=new CountDownLatch(1);
        worker.queue.execute(()->{entered.countDown();try{unlock.await();}catch(InterruptedException e){throw new RuntimeException(e);}});check(entered.await(5,TimeUnit.SECONDS),"account worker deliberately blocked");
        android.os.Looper.mainForFixture(true);Thread thread=Thread.currentThread();String old=thread.getName();thread.setName("Binder:fixture");
        android.os.ParcelFileDescriptor proxy;
        try{
            check(provider.getType(uri).equals(cap.mime),"Binder MIME query uses only admitted memory");
            android.database.MatrixCursor row=(android.database.MatrixCursor)provider.query(uri,null,null,null,null);
            check(row.row[0].equals(cap.name)&&((Long)row.row[1])==bytes.length,"Binder metadata query uses only admitted memory");
            android.os.Binder.uid=999;refuses(()->provider.openFile(uri,"r"),"foreign UID has no archive URI authority");android.os.Binder.uid=101;
            proxy=provider.openFile(uri,"r");check(proxy.handler!=null&&proxy.callback!=null&&proxy.callback.onGetSize()==bytes.length,"API26 proxy setup/size never waits for the blocked archive worker");
        }finally{thread.setName(old);android.os.Looper.mainForFixture(false);unlock.countDown();}
        byte[] output=new byte[31];check(proxy.callback.onRead(13,31,output)==31&&Arrays.equals(output,Arrays.copyOfRange(bytes,13,44)),"proxy callback reads checked random bytes off Binder");proxy.callback.onRelease();
        android.os.Build.VERSION.SDK_INT=23;android.os.ParcelFileDescriptor pipe=provider.openFile(uri,"r");
        await(()->pipe.closed,"API23 sequential pump closes the owned writer");check(pipe.error==null&&Arrays.equals(pipe.pipe.toByteArray(),bytes),"API23 pipe streams exact verified bytes without plaintext spooling");android.os.Build.VERSION.SDK_INT=36;
        refuses(()->provider.openFile(uri,"rw"),"provider has no write mode");cap.close();
        check(PurpleArchiveMediaProvider.lookup(uri)==null,"closing capability revokes its URI immediately");refuses(()->provider.openFile(uri,"r"),"stale URI cannot reopen a reader");on(()->null);
    }
    static final class Destination implements PurpleArchiveMediaExport.Destination {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();volatile boolean closed,deleted;boolean failOpen,failWrite,failClose,failDelete;
        Runnable writeHook;CountDownLatch closedSignal=new CountDownLatch(1);
        @Override public OutputStream open()throws Exception{
            if(failOpen)throw new IOException("fixture open failure");
            return new OutputStream(){
                public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
                public void write(byte[] b,int at,int n)throws IOException{if(failWrite)throw new IOException("fixture write failure");bytes.write(b,at,n);Runnable hook=writeHook;writeHook=null;if(hook!=null)hook.run();}
                public void close()throws IOException{closed=true;closedSignal.countDown();if(failClose)throw new IOException("fixture close failure");}
            };
        }
        @Override public boolean delete(){deleted=!failDelete;if(deleted)bytes.reset();return deleted;}
    }
    static PurpleArchiveMediaExport.Result save(PurpleArchiveMediaAccess.Capability cap,Destination destination)throws Exception{
        AtomicReference<PurpleArchiveMediaExport.Result> result=new AtomicReference<>();
        check(PurpleArchiveMediaExport.save(cap,destination,result::set)!=null,"bounded exporter admits current capability");await(()->result.get()!=null,"export delivers a final result");return result.get();
    }
    static void exports(TLRPC.Message message,byte[] bytes)throws Exception{
        Destination destination=new Destination();PurpleArchiveMediaExport.Result result=save(open(message),destination);
        check(result.saved&&!result.cancelled&&!result.cleanupFailed&&destination.closed&&!destination.deleted&&Arrays.equals(destination.bytes.toByteArray(),bytes),"Save verifies full length/hash and close before success");
        for(int failure=0;failure<4;++failure){
            Destination failed=new Destination();failed.failOpen=failure==0;failed.failWrite=failure==1;failed.failClose=failure>=2;failed.failDelete=failure==3;
            result=save(open(message),failed);check(!result.saved&&result.cleanupFailed==(failure==3)&&failed.deleted==(failure!=3),"open/write/close failure attempts partial cleanup and reports failure honestly");
        }
        PurpleArchiveMediaAccess.Capability cancelled=open(message);Destination partial=new Destination();partial.writeHook=cancelled::close;result=save(cancelled,partial);
        check(!result.saved&&result.cancelled&&partial.deleted&&partial.bytes.size()==0,"revocation during write removes partial output and never reports success");
        PurpleArchiveMediaAccess.Capability late=open(message);Destination complete=new Destination();AtomicReference<PurpleArchiveMediaExport.Result> delivered=new AtomicReference<>();
        check(PurpleArchiveMediaExport.save(late,complete,delivered::set)!=null,"late delivery export queued");check(complete.closedSignal.await(10,TimeUnit.SECONDS),"all bytes closed before queued UI delivery");
        UserConfig.selectedAccount=1;PurpleArchiveViewerModel.selectionChanged(1);await(()->delivered.get()!=null,"late result delivered after switch");
        check(delivered.get().saved&&delivered.get().cancelled&&!delivered.get().removed&&!complete.deleted&&Arrays.equals(complete.bytes.toByteArray(),bytes),"late selection change keeps factual completed copy without current-selection authority");
        UserConfig.selectedAccount=0;PurpleArchiveViewerModel.selectionChanged(0);
        android.net.Uri target=android.net.Uri.parse("content://fixture.documents/new");PurpleArchiveMediaDestination saf=PurpleArchiveMediaDestination.create(target);
        check(saf!=null,"explicit external content destination admitted");try(OutputStream stream=saf.open()){stream.write(7);}check(saf.delete(),"SAF deletion uses the exact selected URI");
        check(PurpleArchiveMediaDestination.create(android.net.Uri.parse("file:///tmp/archive"))==null&&PurpleArchiveMediaDestination.create(PurpleArchiveMediaProvider.uri(late.token))==null,"raw paths and self-provider destinations refuse");
    }
    static void broken(TLRPC.Message message)throws Exception{
        PurpleArchiveViewerModel.Attachment descriptor=attachment(message);PurpleArchiveMediaAccess.Capability cap=open(descriptor);File file=chunk(cap,0);byte[] original=Files.readAllBytes(file.toPath());cap.close();on(()->null);
        byte[] bad=original.clone();bad[bad.length-1]^=1;Files.write(file.toPath(),bad);check(open(descriptor)==null,"tampered canonical ciphertext never yields a Ready capability");
        Files.write(file.toPath(),original);cap=open(descriptor);check(cap!=null,"restored authentic ciphertext is readable");File later=chunk(cap,1);byte[] saved=Files.readAllBytes(later.toPath());
        Files.delete(later.toPath());try(PurpleArchiveMediaDataSource source=new PurpleArchiveMediaDataSource(cap)){byte[] out=new byte[1];refuses(()->source.readAt(1048576,out,0,1),"a missing later chunk refuses after Ready");}
        cap.close();Files.write(later.toPath(),saved);Files.setPosixFilePermissions(later.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));on(()->null);
    }
    static void passcode(TLRPC.Message message)throws Exception{
        for(int cause=0;cause<3;++cause){
            PurpleArchiveMediaAccess.Capability cap=open(message);check(cap!=null,"unlocked archive access Ready");
            try(PurpleArchiveMediaDataSource source=new PurpleArchiveMediaDataSource(cap)){
                byte[] one=new byte[1];check(source.readAt(0,one,0,1)==1,"reader caches one authenticated plaintext page before lock");
                if(cause==0)org.telegram.messenger.SharedConfig.appLocked=true;
                else if(cause==1)org.telegram.messenger.SharedConfig.isWaitingForPasscodeEnter=true;
                else AndroidUtilities.needPasscode=true;
                check(!cap.available()&&PurpleArchiveMediaProvider.lookup(PurpleArchiveMediaProvider.uri(cap.token))==null,"passcode state withdraws URI and memory admission");
                refuses(()->source.readAt(0,one,0,1),"cached plaintext refuses while Telegram requires a passcode");
            }finally{
                cap.close();org.telegram.messenger.SharedConfig.appLocked=false;org.telegram.messenger.SharedConfig.isWaitingForPasscodeEnter=false;AndroidUtilities.needPasscode=false;
            }
            on(()->null);
        }
    }
    static void limits(TLRPC.Message message)throws Exception{
        PurpleArchiveViewerModel.Attachment source=attachment(message);PurpleArchiveMediaAccess.Capability[] caps=new PurpleArchiveMediaAccess.Capability[4];
        for(int i=0;i<caps.length;++i){caps[i]=open(source);check(caps[i]!=null,"bounded registry admits four current account readers");}
        check(PurpleArchiveMediaAccess.request(PurpleAccountFiles.capture(0),source,(value,error)->{})==null,"fifth account reader refuses before allocating a lease");
        try(PurpleArchiveMediaDataSource first=new PurpleArchiveMediaDataSource(caps[0]);PurpleArchiveMediaDataSource second=new PurpleArchiveMediaDataSource(caps[0])){
            refuses(()->new PurpleArchiveMediaDataSource(caps[0]),"third stream refuses without growing plaintext caches");
        }
        for(PurpleArchiveMediaAccess.Capability cap:caps)cap.close();on(()->null);
        await(()->{try{return on(()->{for(PurpleArchiveMediaAccess.Capability cap:caps)if(!cap.released)return false;return true;});}catch(Exception e){throw new RuntimeException(e);}},"all shared-blob reader leases release durably");
    }
    static void logout()throws Exception{
        UserConfig.getInstance(1).active=true;UserConfig.getInstance(1).user=22;PurpleArchive.refresh(1,()->{});
        await(()->PurpleArchive.status(1).state.equals("Ready"),"independent account B archive is Ready");PurpleArchive.Worker other=PurpleArchive.existing(PurpleAccountFiles.capture(1));
        String before=other.queue.submit(()->PurpleArchiveDiscard.seal(other.store.paths)).get(10,TimeUnit.SECONDS);String alias=other.store.binding.alias();
        TLRPC.Message message=message(4010,"logout pending reader",73);retain(message,bytes(73));PurpleArchiveMediaAccess.Capability cap=open(message);check(cap!=null,"logout fixture reader Ready");
        PurpleArchiveMediaDataSource source=new PurpleArchiveMediaDataSource(cap);CountDownLatch entered=new CountDownLatch(1),unlock=new CountDownLatch(1);
        worker.queue.execute(()->{entered.countDown();try{unlock.await();}catch(InterruptedException e){throw new RuntimeException(e);}});check(entered.await(5,TimeUnit.SECONDS),"logout worker blocked before queued read");
        java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();java.util.concurrent.Future<Boolean> refused=io.submit(()->{try{source.readAt(0,new byte[1],0,1);return false;}catch(IOException e){return true;}});
        await(()->!worker.queue.getQueue().isEmpty(),"read is queued before logout");long start=System.nanoTime();PurpleArchive.beforeLogout(0);UserConfig.getInstance(0).active=false;
        check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1)&&!cap.available(),"logout revokes in memory without waiting for blocked IO");unlock.countDown();
        check(refused.get(10,TimeUnit.SECONDS),"a queued read cannot deliver after logout");source.close();io.shutdown();
        await(()->worker.retired,"logout drains reader leases and removes account A");
        String after=other.queue.submit(()->PurpleArchiveDiscard.seal(other.store.paths)).get(10,TimeUnit.SECONDS);
        check(before.equals(after)&&PurpleArchivePlatformProvider.keys.containsKey(alias)&&other.status.state.equals("Ready"),"account B catalogue, data and key remain untouched by A logout");
        other.queue.submit(()->{other.store.drainReaders();other.store.close();other.store=null;return null;}).get(10,TimeUnit.SECONDS);other.queue.shutdown();
    }
    static void discard(TLRPC.Message message)throws Exception{
        PurpleArchiveViewerModel.Attachment old=attachment(message);PurpleArchiveMediaAccess.Capability cap=open(old);
        check(cap!=null,"discard opens an authentic, private retained chunk set");
        PurpleArchive.RemovalRequest removal=PurpleArchive.prepareRemoval(0);check(!cap.available(),"prepare discard withdraws every pending/open reader in memory");
        check(PurpleArchive.remove(removal,()->{}),"confirmed archive removal admitted");await(()->removal.completed,"old store removed with released readers");
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"explicit refresh prepares a fresh empty archive");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        check(PurpleArchiveMediaAccess.request(PurpleAccountFiles.capture(0),old,(value,error)->{})==null,"old descriptor cannot adopt a replacement store");
    }
    public static void main(String[] args)throws Exception{
        System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(args[1]);check(root.mkdir(),"exclusive B3b fixture");
        ApplicationLoader.files=new File(root,"files");check(ApplicationLoader.files.mkdir(),"fixture files");ApplicationLoader.applicationContext=new android.content.Context();
        UserConfig.selectedAccount=0;UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=slot->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"offline archive ready");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        byte[] data=bytes(2*1048576+19);TLRPC.Message first=message(4000,"owned reader",data.length);retain(first,data);reads(first,data);
        TLRPC.Message shared=message(4001,"shared canonical blob",data.length);retain(shared,data);provider(shared,data);exports(shared,data);broken(shared);limits(shared);passcode(shared);
        TLRPC.Message empty=message(4002,"known empty",0);retain(empty,new byte[0]);Destination zero=new Destination();check(save(open(empty),zero).saved&&zero.closed&&zero.bytes.size()==0,"zero-byte Save verifies the authenticated empty manifest");
        discard(shared);logout();
        System.out.println("ARCHIVE_B3B_CHECKS="+checks);
    }
}
