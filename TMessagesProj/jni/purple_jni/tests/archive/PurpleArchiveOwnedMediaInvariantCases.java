package org.telegram.messenger.purple;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
import java.io.File;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
public final class PurpleArchiveOwnedMediaInvariantCases {
    static PurpleArchiveSqlite database()throws Exception{java.lang.reflect.Field f=PurpleArchiveStore.class.getDeclaredField("database");f.setAccessible(true);return (PurpleArchiveSqlite)f.get(worker.store);}
    static long leases()throws Exception{return on(()->database().run("SELECT lease_id FROM leases").rows);}
    static void settle()throws Exception{for(int i=0;i<5;++i)on(()->null);}
    public static void main(String[] args)throws Exception{
        String mode=args[2];System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(args[1]);if(!root.mkdir())throw new AssertionError("exclusive fixture");
        ApplicationLoader.files=new File(root,"files");if(!ApplicationLoader.files.mkdir())throw new AssertionError("app files");ApplicationLoader.applicationContext=new android.content.Context();
        UserConfig.selectedAccount=0;UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=s->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"A ready");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        TLRPC.Message m=message(4300,"owned invariant",113);byte[] bytes=bytes(113);PurpleArchiveB3bCases.retain(m,bytes);
        PurpleArchiveMediaAccess.Capability cap=PurpleArchiveB3bCases.open(m);if(cap==null)throw new AssertionError("Ready capability required");boolean failed=false;
        try{
            if(mode.startsWith("history-")){
                on(()->{for(int i=0;i<39;++i)capture(message(m.id,"archive caption "+i,113));return null;});
                TLRPC.Message latest=message(m.id,"archive caption 39",113);on(()->capture(latest));if(!complete(latest,"document_"+latest.media.document.id))PurpleArchiveB3bCases.retain(latest,bytes);
                PurpleArchiveViewerModel model=new PurpleArchiveViewerModel(0,latest);PurpleArchiveMediaAccess.Capability opened=null;
                try{
                    AtomicReference<PurpleArchiveViewerModel.Page> first=new AtomicReference<>();if(!model.next(first::set))throw new AssertionError("first page refused");await(()->first.get()!=null,"first history page");
                    if(!first.get().error.isEmpty()||!first.get().more||first.get().versions.size()!=32)throw new AssertionError("fixture needs a full first page");
                    if(!mode.equals("history-control")){
                    AtomicReference<PurpleArchiveMediaAccess.Capability> delivered=new AtomicReference<>();java.util.concurrent.atomic.AtomicBoolean done=new java.util.concurrent.atomic.AtomicBoolean();
                    if(model.media(first.get().versions.get(0).attachments.get(0),(value,error)->{delivered.set(value);done.set(true);})==null)throw new AssertionError("Open refused");
                    await(done::get,"owned Open ready");opened=delivered.get();if(opened==null)throw new AssertionError("owned Open failed");
                    if(mode.equals("history-interleaved")) on(()->capture(message(m.id,"interleaved payload",113)));
                    if(mode.equals("history-release-retry")) {
                        on(()->{PurpleArchiveCore.requireDone(database().run("PRAGMA query_only=ON"));return null;});
                        opened.close();settle();
                        if(opened.released)throw new AssertionError("failed release acknowledged");
                        on(()->{java.lang.reflect.Field f=PurpleArchiveViewerModel.class.getDeclaredField("reader");f.setAccessible(true);
                            if(!((PurpleArchiveStore.Reader)f.get(model)).current())throw new AssertionError("failed release changed history fence");
                            PurpleArchiveCore.requireDone(database().run("PRAGMA query_only=OFF"));return null;});
                    }
                    opened.close();settle();
                    if(mode.equals("history-release-retry")){PurpleArchiveMediaAccess.Capability releasing=opened;await(()->releasing.released,"release retry committed");}
                    if(mode.equals("history-payload"))on(()->capture(message(m.id,"changed after Open",113)));
                    if(mode.equals("history-purge"))on(()->{PurpleArchiveCore.requireDone(database().run("BEGIN IMMEDIATE"));
                        PurpleArchiveSqlite.Result changed=database().run("UPDATE archive_meta SET maintenance_id='probe-purge' WHERE singleton=1");
                        if(!changed.done()||changed.directChanges!=1)throw new AssertionError("purge barrier fixture");
                        PurpleArchiveCore.requireDone(database().run("COMMIT"));return null;});
                    if(mode.equals("history-other-view")){PurpleArchiveMediaAccess.Capability other=PurpleArchiveB3bCases.open(latest);if(other==null)throw new AssertionError("other view lease");other.close();settle();}

                    }
                    AtomicReference<PurpleArchiveViewerModel.Page> next=new AtomicReference<>();if(!model.next(next::set))throw new AssertionError("next refused");await(()->next.get()!=null,"older history page");
                    System.out.println(mode+": firstVersions="+first.get().versions.size()+"; firstMore="+first.get().more+"; afterOpenError="+next.get().error+"; nextVersions="+next.get().versions.size());
                    boolean stale=mode.equals("history-payload")||mode.equals("history-purge")||mode.equals("history-interleaved")||mode.equals("history-other-view");
                    failed=stale? !next.get().error.equals("stale")||!next.get().versions.isEmpty() : !next.get().error.isEmpty()||next.get().versions.size()!=9;
                    if(mode.equals("history-purge"))on(()->{PurpleArchiveSqlite.Result changed=database().run("UPDATE archive_meta SET maintenance_id='' WHERE singleton=1");if(!changed.done())throw new AssertionError("restore purge barrier");return null;});
                }finally{if(opened!=null)opened.close();model.close();settle();}
            }else if(mode.equals("late-save")){
                PurpleArchiveB3bCases.Destination destination=new PurpleArchiveB3bCases.Destination();AtomicReference<PurpleArchiveMediaExport.Result> result=new AtomicReference<>();
                if(PurpleArchiveMediaExport.save(cap,destination,result::set)==null)throw new AssertionError("export refused");
                if(!destination.closedSignal.await(10,TimeUnit.SECONDS))throw new AssertionError("output not closed");
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean queued=false;
                while(System.nanoTime()<until){synchronized(AndroidUtilities.class){queued=!AndroidUtilities.pending.isEmpty();}if(queued)break;Thread.sleep(5);}
                if(!queued||destination.deleted||!Arrays.equals(destination.bytes.toByteArray(),bytes))throw new AssertionError("must finish output before revocation");
                UserConfig.selectedAccount=1;PurpleArchiveViewerModel.selectionChanged(1);await(()->result.get()!=null,"late result");
                PurpleArchiveMediaExport.Result value=result.get();
                System.out.println("LATE_SAVE: saved="+value.saved+"; cancelled="+value.cancelled+"; cleanupFailed="+value.cleanupFailed+"; destinationDeleted="+destination.deleted+"; completedBytesRemain="+Arrays.equals(destination.bytes.toByteArray(),bytes));
                failed=!value.saved||!value.cancelled||value.cleanupFailed||value.removed||destination.deleted||!Arrays.equals(destination.bytes.toByteArray(),bytes);
                UserConfig.selectedAccount=0;PurpleArchiveViewerModel.selectionChanged(0);
            }else if(mode.equals("release-error")){
                try(PurpleArchiveMediaDataSource source=new PurpleArchiveMediaDataSource(cap)){
                    if(source.readAt(0,new byte[1],0,1)!=1)throw new AssertionError("read");
                    on(()->{PurpleArchiveCore.requireDone(database().run("PRAGMA query_only=ON"));return null;});cap.close();settle();
                    if(cap.available()||cap.released||leases()!=1)throw new AssertionError("failed release must preserve durable pin");
                    boolean refused=false;try{source.readAt(0,new byte[1],0,1);}catch(java.io.IOException expected){refused=true;}
                    if(!refused)throw new AssertionError("revoked cached page delivered");
                    on(()->{PurpleArchiveCore.requireDone(database().run("PRAGMA query_only=OFF"));return null;});cap.close();
                    await(()->cap.released,"release retried");if(leases()!=0)throw new AssertionError("lease leaked");System.out.println("RELEASE_ERROR: delivery refused; pin retained until durable release; leases=0");
                }
            }else if(mode.equals("bound-version")){
                try(PurpleArchiveMediaDataSource source=new PurpleArchiveMediaDataSource(cap)){
                    if(source.readAt(0,new byte[1],0,1)!=1)throw new AssertionError("read");on(()->capture(message(m.id,"newer head",113)));
                    if(source.readAt(0,new byte[1],0,1)!=1)throw new AssertionError("exact historical reader must survive newer head");
                    JSONObject q=on(()->PurpleArchiveCore.call("query",worker.store.arguments().put("query","version").put("key",key(m)).put("version",cap.attachment.version),worker.store.crypto()));
                    PurpleArchiveCore.Statement statement=new PurpleArchiveCore.Statement(q.getJSONObject("statement"));
                    byte[] original=on(()->database().run(statement.sql,statement.binds).values[0][0].bytes);byte[] corrupt=original.clone();corrupt[corrupt.length-1]^=1;
                    on(()->{PurpleArchiveSqlite.Result changed=database().run("UPDATE versions SET envelope=? WHERE message_key=? AND version_id=?",PurpleArchiveSqlite.Value.blob(corrupt),statement.binds[0],statement.binds[1]);if(!changed.done()||changed.directChanges!=1)throw new AssertionError("fixture version update refused");return null;});
                    boolean refused=false;try{source.readAt(0,new byte[1],0,1);}catch(java.io.IOException expected){refused=true;}
                    on(()->{PurpleArchiveSqlite.Result changed=database().run("UPDATE versions SET envelope=? WHERE message_key=? AND version_id=?",PurpleArchiveSqlite.Value.blob(original),statement.binds[0],statement.binds[1]);if(!changed.done()||changed.directChanges!=1)throw new AssertionError("fixture version update refused");return null;});
                    cap.close();await(()->cap.released,"lease releases after restoring authenticated row");if(!refused||leases()!=0)throw new AssertionError("bound-version mutation delivered or leaked");
                    System.out.println("BOUND_VERSION: newer head retained exact reader; mutated bound row refused cached read; leases=0");
                }
            }else if(mode.equals("login-change")){
                PurpleArchiveViewerModel.Attachment old=cap.attachment;String openingStore=worker.store.binding.store;
                try(PurpleArchiveMediaDataSource source=new PurpleArchiveMediaDataSource(cap)){
                    source.readAt(0,new byte[1],0,1);PurpleArchive.loginChanging(0,0,UserConfig.getInstance(0).getClientUserId());PurpleArchiveLogin.source=s->"22222222222222222222222222222222";java.util.concurrent.atomic.AtomicBoolean refreshed=new java.util.concurrent.atomic.AtomicBoolean();PurpleArchive.refresh(0,()->refreshed.set(true));await(refreshed::get,"replacement login Refresh completes");settle();
                    if(cap.available()||worker.store.binding.store.equals(openingStore))throw new AssertionError("new login retained old authority/store");
                    boolean refused=false;try{source.readAt(0,new byte[1],0,1);}catch(java.io.IOException expected){refused=true;}
                    if(!refused||PurpleArchiveMediaAccess.request(PurpleAccountFiles.capture(0),old,(v,e)->{})!=null)throw new AssertionError("old descriptor adopted new login");
                    System.out.println("LOGIN_CHANGE: old cached delivery and old descriptor refuse replacement login/store");
                }
            }else if(mode.equals("slot-reuse")){
                try(PurpleArchiveMediaDataSource source=new PurpleArchiveMediaDataSource(cap)){
                    source.readAt(0,new byte[1],0,1);UserConfig.getInstance(0).user=22;
                    if(cap.available())throw new AssertionError("slot reuse left capability available");boolean refused=false;try{source.readAt(0,new byte[1],0,1);}catch(java.io.IOException expected){refused=true;}
                    UserConfig.getInstance(0).user=11;cap.close();await(()->cap.released,"old lease release");if(!refused||leases()!=0)throw new AssertionError("slot reuse delivered or leaked");
                    System.out.println("SLOT_REUSE: old cached read refused before any identity callback; leases=0");
                }
            }else throw new AssertionError("unknown mode");
        }finally{cap.close();settle();on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();}
        if(failed)throw new AssertionError(mode.startsWith("history")?"Owned Open must not invalidate otherwise unchanged history pagination":"Save must not classify an existing completed export as cleanly removed failure");
    }
}
