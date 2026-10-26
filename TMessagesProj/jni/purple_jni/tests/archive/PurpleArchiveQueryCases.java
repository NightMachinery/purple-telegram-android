package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
import java.io.File;
import java.math.BigInteger;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveQueryCases {
    static PurpleArchiveSqlite database() throws Exception {
        java.lang.reflect.Field field=PurpleArchiveStore.class.getDeclaredField("database");field.setAccessible(true);return (PurpleArchiveSqlite)field.get(worker.store);
    }
    static JSONObject customKey(int kind,long chat,long id,int collection,String representation)throws Exception {
        return PurpleArchiveNormalize.key("0-11",kind,chat,id).put("collection",collection).put("collectionId",collection==2?"17":"0").put("representation",representation);
    }
    static JSONObject apply(JSONObject key,TLRPC.Message message,int origin,long topic)throws Exception {
        try(PurpleArchiveSlice slice=new PurpleArchiveSlice(worker.store,key,null)){
            JSONObject event=new JSONObject().put("id",PurpleArchiveBinding.token()).put("order",PurpleArchiveBinding.next(slice.facts.getString("order")))
                .put("origin",origin).put("key",key).put("accountGeneration",slice.facts.getString("accountGeneration"))
                .put("chatGeneration",slice.facts.getString("chatGeneration")).put("observedAt","1700000000").put("serverTime","0");
            if(message!=null){JSONObject body=PurpleArchiveNormalize.body(message).put("topicId",Long.toString(topic));
                event.put("snapshot",PurpleArchiveCore.call("snapshot",worker.store.arguments().put("key",key).put("body",body),worker.store.crypto()).getString("snapshot"));}
            if(slice.facts.has("head"))event.put("targetVersion",slice.facts.getString("head")).put("targetRevision",slice.facts.getString("revisionId")).put("expectedContentHash",slice.facts.getString("hash"));
            JSONObject plan=PurpleArchiveCore.call("capture",worker.store.arguments().put("rows",slice.rows).put("event",event),worker.store.crypto());
            check(worker.store.execute(new PurpleArchiveCore.Program(plan)).committed,"fixture commits a real core capture");
            return plan;
        }
    }
    static PurpleArchiveQueries.Scope scope(int kind,long id)throws Exception {
        return scope(new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(kind,id)).put("aliases",new JSONArray()).put("collection",0).put("representation","original"));
    }
    static PurpleArchiveQueries.Scope scope(JSONObject value)throws Exception {
        return new PurpleArchiveQueries.Scope(PurpleAccountFiles.capture(0),value,"fixture",PurpleArchiveViewerModel.selectionGeneration());
    }
    static PurpleArchiveQueries query(PurpleArchiveQueries.Scope scope,int order,AtomicLong clock)throws Exception {
        PurpleArchiveQueries value=new PurpleArchiveQueries(worker.store,scope,worker.loginContext,order,"measured estimate",clock::get);
        try{value.open();return value;}catch(Exception|LinkageError e){value.close();throw e;}
    }
    static List<PurpleArchiveQueries.Entry> all(PurpleArchiveQueries.Scope scope,int order,int limit)throws Exception {
        try(PurpleArchiveQueries q=query(scope,order,new AtomicLong())){
            ArrayList<PurpleArchiveQueries.Entry> entries=new ArrayList<>();boolean more=true;int pages=0;
            while(more){PurpleArchiveQueries.Page page=q.next(limit);check(page.status.equals("Ready")&&page.counts!=null,"admitted query returns counts with the page");entries.addAll(page.entries);more=page.more;if(++pages>1000)throw new AssertionError("cursor loop");}
            return entries;
        }
    }
    static void pages()throws Exception {
        on(()->{
            for(int i=0;i<140;++i){TLRPC.Message m=message(6000+i,"retained "+i,-1);m.date=100+i%3;JSONObject key=customKey(0,55,m.id,0,"original");apply(key,m,0,0);apply(key,null,2,0);}
            JSONObject live=customKey(0,55,6300,0,"original");apply(live,message(6300,"still live",-1),0,0);
            List<PurpleArchiveQueries.Entry> ascending=all(scope(0,55),0,128),descending=all(scope(0,55),1,17);
            check(ascending.size()==140&&descending.size()==140,"129-plus rows paginate through both directions");
            HashSet<String> seen=new HashSet<>();for(int i=0;i<ascending.size();++i){check(seen.add(ascending.get(i).key),"tuple cursor has no duplicate");check(ascending.get(i).key.equals(descending.get(139-i).key),"reverse tuple cursor matches the exact full keys");}
            try(PurpleArchiveQueries q=query(scope(0,55),0,new AtomicLong())){PurpleArchiveQueries.Page p=q.next(1);check(p.counts.get(0)==141&&p.counts.get(1)==140&&p.counts.get(2)==141,"counts include live stored messages while deleted view excludes them");}
            JSONObject secret=customKey(3,77,Long.MIN_VALUE+123,0,"original");apply(secret,message(6400,"signed secret",-1),0,0);apply(secret,null,2,0);
            List<PurpleArchiveQueries.Entry> secretRows=all(scope(3,77),0,1);check(secretRows.size()==1&&new JSONObject(secretRows.get(0).key).getString("messageId").equals(Long.toString(Long.MIN_VALUE+123)),"full-width negative secret message identity survives");
            JSONObject original=customKey(2,88,6500,0,"original"),scheduled=customKey(2,88,6500,1,"original"),translated=customKey(2,88,6500,0,"translated"),alias=customKey(1,89,6503,0,"original");
            for(JSONObject key:Arrays.asList(original,scheduled,translated,alias)){apply(key,message(6500,"scoped body",-1),0,key==alias?0:7);apply(key,null,2,0);}
            JSONObject requested=scope(2,88).value();check(all(scope(requested),0,10).size()==1,"received original is the default scope");
            requested.put("aliases",new JSONArray().put(PurpleArchiveQueries.Scope.chat(1,89)));check(all(scope(requested),0,10).size()==2,"trusted parent aliases are included once");
            requested.put("topic",new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(2,88)).put("id","7"));check(all(scope(requested),0,10).size()==1,"topic remains bound to its parent, not an alias with another topic");
            requested.put("collection",JSONObject.NULL).put("representation",JSONObject.NULL);check(all(scope(requested),0,1).size()==3,"full date/id ties page by the final token with optional filters");
            requested.put("topic",new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(2,99)).put("id","7"));refuses(()->query(scope(requested),0,new AtomicLong()),"mismatched topic parent is refused");
            JSONObject tombstone=customKey(0,66,6600,0,"original");apply(tombstone,null,2,0);List<PurpleArchiveQueries.Entry> noBody=all(scope(0,66),0,16);
            check(noBody.size()==1&&!noBody.get(0).body&&noBody.get(0).status==1,"authenticated no-body tombstone stays visible without fabricated content");
            JSONObject expired=customKey(0,67,6601,0,"original"),consumed=customKey(0,67,6602,0,"original");
            apply(expired,message(6601,"expired",-1),0,0);apply(expired,null,4,0);apply(consumed,message(6602,"consumed",-1),0,0);apply(consumed,null,5,0);
            List<PurpleArchiveQueries.Entry> lifecycle=all(scope(0,67),0,16);check(lifecycle.size()==2&&lifecycle.get(0).status==2&&lifecycle.get(1).status==3,"expiry and consumption use authenticated core status");
            return null;
        });
    }
    static void usage()throws Exception {
        TLRPC.Message a=message(6700,"shared A",113),b=message(6701,"shared B",113);byte[] bytes=bytes(113);
        PurpleArchiveB3bCases.retain(a,bytes);PurpleArchiveB3bCases.retain(b,bytes);
        on(()->{apply(key(a),null,2,0);apply(key(b),null,2,0);
            try(PurpleArchiveQueries q=query(scope(0,55),0,new AtomicLong())){PurpleArchiveQueries.Page p=q.next(128);
                check(p.logicalComplete&&p.logicalBytes.equals("113")&&p.counts.get(5)==2&&p.counts.get(6)==1,"logical usage counts shared blobs once and separate Complete references");
                check(p.entries.size()==2&&p.entries.get(0).media.contains(2),"Complete attachment labels survive deleted decoding");}
            check(PurpleArchiveQueries.totalBytes(BigInteger.ZERO,"18446744073709551615").toString().equals("18446744073709551615"),"unsigned 64-bit logical bytes do not narrow to signed long");
            refuses(()->PurpleArchiveQueries.totalBytes(new BigInteger("18446744073709551615"),"1"),"logical sum overflow refuses without a wrapped total");
            for(String bad:Arrays.asList("-1","01","18446744073709551616"))refuses(()->PurpleArchiveQueries.totalBytes(BigInteger.ZERO,bad),"noncanonical or out-of-range blob bytes refuse");
            JSONObject missing=customKey(0,70,6702,0,"original");apply(missing,message(6702,"missing media",113),0,0);apply(missing,null,2,0);
            check(all(scope(0,70),0,16).get(0).media.contains(0),"Missing media is labeled and does not initiate a download");
            try(PurpleArchiveQueries q=query(scope(0,99),0,new AtomicLong())){PurpleArchiveQueries.Page p=q.next(16);check(p.entries.isEmpty()&&p.counts.get(0)==0&&p.logicalBytes.equals("0"),"admitted empty scope reports true zero counts");}
            return null;});
        for(int i=0;i<129;++i){TLRPC.Message distinct=message(7000+i,"unique blob "+i,2);byte[] content=new byte[]{(byte)i,(byte)(i>>>8)};PurpleArchiveB3bCases.retain(distinct,content);}
        on(()->{
            try(PurpleArchiveQueries q=query(scope(0,55),0,new AtomicLong())){
                PurpleArchiveQueries.Page first=q.next(128);check(!first.logicalComplete&&first.logicalBytes.equals("256")||!first.logicalComplete&&first.logicalBytes.equals("367"),"first usage page remains explicitly incomplete");
                PurpleArchiveQueries.Page last=q.next(128);check(last.logicalComplete&&last.logicalBytes.equals("371")&&last.counts.get(6)==130,"second unique-blob page completes without duplicates");}
            check(PurpleArchiveQueries.Page.failure("Unavailable").counts==null,"failure has unavailable counts rather than zero");return null;});
    }
    static void corrupt()throws Exception {
        on(()->{JSONObject k=customKey(0,55,6800,0,"original");apply(k,message(6800,"authenticated body",-1),0,0);apply(k,null,2,0);
            JSONObject plan=PurpleArchiveCore.call("query",worker.store.arguments().put("query","message").put("key",k),worker.store.crypto());String token=plan.getString("id");
            PurpleArchiveSqlite.Value bind=PurpleArchiveSqlite.Value.text(token);
            byte[] envelope=database().run("SELECT envelope FROM messages WHERE message_key=?",bind).values[0][0].bytes;
            byte[] bad=envelope.clone();bad[bad.length-1]^=1;database().run("UPDATE messages SET envelope=? WHERE message_key=?",PurpleArchiveSqlite.Value.blob(bad),bind);
            refuses(()->all(scope(0,55),0,16),"tampered shell envelope is unavailable, never an empty successful view");database().run("UPDATE messages SET envelope=? WHERE message_key=?",PurpleArchiveSqlite.Value.blob(envelope),bind);
            String head=database().run("SELECT head_version FROM messages WHERE message_key=?",bind).values[0][0].text();
            database().run("UPDATE messages SET head_version='missing' WHERE message_key=?",bind);refuses(()->all(scope(0,55),0,16),"nonempty missing head is corruption, not a bodyless tombstone");database().run("UPDATE messages SET head_version=? WHERE message_key=?",PurpleArchiveSqlite.Value.text(head),bind);
            JSONObject bigKey=customKey(0,56,6810,0,"original");StringBuilder large=new StringBuilder();for(int i=0;i<40000;++i)large.append('x');apply(bigKey,message(6810,large.toString(),-1),0,0);apply(bigKey,null,2,0);
            List<PurpleArchiveQueries.Entry> largeRows=all(scope(0,56),0,16);check(largeRows.size()==1&&largeRows.get(0).text.length()==8192&&largeRows.get(0).truncated,"full body authenticates before bounded display clipping");
            PurpleArchiveSqlite.Result chunk=database().run("SELECT message_key,version_id,chunk_index,envelope FROM body_chunks LIMIT 1");check(chunk.rows==1,"large fixture uses core body chunks");
            byte[] frame=chunk.values[0][3].bytes;byte[] broken=frame.clone();broken[broken.length-1]^=1;
            database().run("UPDATE body_chunks SET envelope=? WHERE message_key=? AND version_id=? AND chunk_index=?",PurpleArchiveSqlite.Value.blob(broken),chunk.values[0][0],chunk.values[0][1],chunk.values[0][2]);refuses(()->all(scope(0,56),0,16),"corrupt body chunk refuses the whole page");
            database().run("UPDATE body_chunks SET envelope=? WHERE message_key=? AND version_id=? AND chunk_index=?",PurpleArchiveSqlite.Value.blob(frame),chunk.values[0][0],chunk.values[0][1],chunk.values[0][2]);
            AtomicLong time=new AtomicLong();try(PurpleArchiveQueries q=query(scope(0,55),0,time)){time.set(PurpleArchiveQueries.IDLE_NANOS);check(q.expired(),"idle reader has a bounded lifetime");refuses(()->q.next(16),"expired query requires Refresh");}
            ArrayList<PurpleArchiveStore.Reader> readers=new ArrayList<>();try{for(int i=0;i<8;++i)readers.add(worker.store.reader());refuses(()->worker.store.reader(),"read connections are capped at eight");}finally{for(PurpleArchiveStore.Reader reader:readers)reader.close();}
            try(PurpleArchiveQueries q=query(scope(0,55),0,new AtomicLong())){q.next(16);apply(customKey(0,55,6801,0,"original"),message(6801,"writer change",-1),0,0);check(q.current()&&q.refreshSuggested,"ordinary writer revision retains the original snapshot and offers Refresh");check(q.next(16).status.equals("Ready"),"retained snapshot remains readable after an ordinary write");}
            try(PurpleArchiveQueries q=query(scope(0,55),0,new AtomicLong())){
                database().run("ALTER TABLE messages RENAME TO moved_messages");
                refuses(()->q.next(16),"changed current schema admission revokes the retained view even though its old snapshot still exists");
                refuses(()->query(scope(0,55),0,new AtomicLong()),"new snapshot refuses the changed schema");
            }
            return null;
        });
    }
    static PurpleArchiveQueries.Page page(PurpleArchiveDeletedModel model)throws Exception {
        AtomicReference<PurpleArchiveQueries.Page> value=new AtomicReference<>();check(model.next(value::set),"query model admits one request");await(()->value.get()!=null,"query model page delivered");return value.get();
    }
    static void drain()throws Exception {
        for(int i=0;i<4;++i){ArrayList<Runnable> pending;synchronized(AndroidUtilities.class){pending=new ArrayList<>(AndroidUtilities.pending);AndroidUtilities.pending.clear();}for(Runnable action:pending)action.run();Thread.sleep(5);}
    }
    static void model()throws Exception {
        on(()->{JSONObject k=customKey(0,55,6900,0,"original");apply(k,message(6900,"full-key history",-1),0,0);apply(k,null,2,0);return null;});
        PurpleArchiveQueries.Scope s=scope(0,55);PurpleArchiveDeletedModel first=new PurpleArchiveDeletedModel(s);
        PurpleArchiveQueries.Page p=page(first);check(p.status.equals("Ready")&&p.entries.size()==1,"dedicated model returns scoped retained entry");
        PurpleArchiveViewerModel history=first.history(p.entries.get(0));check(history!=null,"validated full-key entry opens pinned history");
        AtomicReference<PurpleArchiveViewerModel.Page> old=new AtomicReference<>();check(history.next(old::set),"pinned history read queued");await(()->old.get()!=null,"pinned history delivered");
        check(old.get().error.isEmpty()&&old.get().versions.get(0).text.equals("full-key history"),"linked history reads the exact opening message key");history.close();
        PurpleArchiveDeletedModel other=new PurpleArchiveDeletedModel(s);page(other);check(other.history(p.entries.get(0))==null,"an entry cannot be replayed through another query incarnation");other.close();
        AtomicReference<PurpleArchiveQueries.Page> queued=new AtomicReference<>();check(first.next(queued::set),"late page queued");on(()->null);
        UserConfig.selectedAccount=1;PurpleArchiveViewerModel.selectionChanged(1);UserConfig.selectedAccount=0;PurpleArchiveViewerModel.selectionChanged(0);drain();
        check(queued.get()==null&&!first.available()&&!first.next(v->{}),"A-B-A revokes queued pages and later page admission");refuses(first::restart,"old selection cannot refresh into new authority");first.close();on(()->null);
        AtomicLong time=new AtomicLong();PurpleArchiveDeletedModel idle=new PurpleArchiveDeletedModel(scope(0,55),worker,worker.store,worker.loginContext,0,time::get);page(idle);time.set(PurpleArchiveQueries.IDLE_NANOS);check(!idle.available(),"model expiry withdraws UI authority without IO");idle.close();on(()->null);
        AtomicBoolean policy=new AtomicBoolean(true);PurpleArchiveQueries.Scope guarded=new PurpleArchiveQueries.Scope(PurpleAccountFiles.capture(0),scope(0,55).value(),"guarded",PurpleArchiveViewerModel.selectionGeneration(),policy::get);
        PurpleArchiveDeletedModel gated=new PurpleArchiveDeletedModel(guarded);page(gated);policy.set(false);check(!gated.available(),"changed opening policy withdraws retained view");gated.close();on(()->null);
        PurpleArchiveDeletedModel changed=new PurpleArchiveDeletedModel(scope(0,55));page(changed);on(()->{apply(customKey(0,55,6901,0,"original"),message(6901,"new payload",-1),0,0);return null;});check(changed.available()&&changed.refreshSuggested(),"ordinary same-chat payload mutation preserves the snapshot and offers Refresh");changed.close();on(()->null);
        TLRPC.TL_user user=new TLRPC.TL_user();user.id=55;user.first_name="captured";
        PurpleArchiveQueries.Scope captured=PurpleArchiveQueries.Scope.capture(0,55,user,null,null,null,0);check(captured.value().getJSONObject("chat").getInt("kind")==0,"production scope captures typed peer metadata");
        refuses(()->PurpleArchiveQueries.Scope.capture(0,56,user,null,null,null,0),"production scope refuses guessed dialog ownership");
        java.util.concurrent.CountDownLatch held=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        Thread holder=new Thread(()->{synchronized(PurpleAccountOwner.class){held.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}},"ArchiveOwnerMonitorFixture");holder.setDaemon(true);holder.start();
        check(held.await(5,TimeUnit.SECONDS),"fixture holds owner monitor");
        try{long started=System.nanoTime();PurpleArchiveQueries.Scope memoryScope=PurpleArchiveQueries.Scope.capture(0,55,user,null,null,null,0);PurpleArchiveDeletedModel memory=new PurpleArchiveDeletedModel(memoryScope);check(memory.available()&&TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<250,"main-thread admission and authority checks do not wait on owner startup or its monitor");memory.close();}
        finally{release.countDown();holder.join(5000);}on(()->null);

        TLRPC.TL_channel channel=new TLRPC.TL_channel();channel.id=88;channel.title="channel";TLRPC.TL_channelFull info=new TLRPC.TL_channelFull();info.id=88;info.migrated_from_chat_id=89;
        PurpleArchiveQueries.Scope migrated=PurpleArchiveQueries.Scope.capture(0,-88,null,channel,null,info,7);check(migrated.value().getJSONArray("aliases").getJSONObject(0).getInt("kind")==1&&migrated.topic().equals("7"),"production scope uses actual migration metadata and the actual topic parent");
        TLRPC.TL_encryptedChat secret=new TLRPC.TL_encryptedChat();secret.id=77;check(PurpleArchiveQueries.Scope.capture(0,org.telegram.messenger.DialogObject.makeEncryptedDialogId(77),null,null,secret,null,0).value().getJSONObject("chat").getInt("kind")==3,"production secret scope uses the encrypted dialog identity");
        on(()->{database().run("UPDATE archive_meta SET schema_version=3 WHERE singleton=1");return null;});
        PurpleArchiveDeletedModel future=new PurpleArchiveDeletedModel(scope(0,55));PurpleArchiveQueries.Page futurePage=page(future);check(futurePage.status.equals("ReadOnly")&&futurePage.counts==null,"future SQL schema is explicitly ReadOnly with unavailable counts");future.close();on(()->{database().run("UPDATE archive_meta SET schema_version=2 WHERE singleton=1");return null;});
        String fingerprint=on(()->database().run("SELECT schema_fingerprint FROM archive_meta").values[0][0].text());on(()->{database().run("UPDATE archive_meta SET schema_fingerprint='bad' WHERE singleton=1");return null;});
        PurpleArchiveDeletedModel wrong=new PurpleArchiveDeletedModel(scope(0,55));PurpleArchiveQueries.Page mismatch=page(wrong);check(mismatch.status.equals("SchemaMismatch")&&mismatch.counts==null,"shape mismatch never becomes an empty successful view");wrong.close();on(()->{database().run("UPDATE archive_meta SET schema_fingerprint=? WHERE singleton=1",PurpleArchiveSqlite.Value.text(fingerprint));return null;});
        PurpleArchiveDeletedModel login=new PurpleArchiveDeletedModel(scope(0,55));page(login);String original=worker.store.binding.store;PurpleArchive.loginChanging(0,0,UserConfig.getInstance(0).getClientUserId());PurpleArchiveLogin.source=slot->"22222222222222222222222222222222";PurpleArchive.refresh(0,()->{});
        await(()->worker.store!=null&&!worker.store.binding.store.equals(original),"replacement login creates a new admitted store");check(!login.available(),"old query cannot adopt replacement login/store");refuses(login::restart,"Refresh cannot recapture a replacement login");login.close();on(()->null);
    }
    static void revoke(String mode)throws Exception {
        UserConfig.getInstance(1).active=true;UserConfig.getInstance(1).user=22;PurpleArchive.refresh(1,()->{});await(()->PurpleArchive.status(1).state.equals("Ready"),"B ready");
        PurpleArchive.Worker other=PurpleArchive.existing(PurpleAccountFiles.capture(1));File db=other.queue.submit(()->other.store.directory.file("archive.sqlite")).get(40,TimeUnit.SECONDS);File wal=new File(db+"-wal");byte[] before=java.nio.file.Files.readAllBytes(db.toPath()),beforeWal=java.nio.file.Files.readAllBytes(wal.toPath());
        on(()->{JSONObject k=customKey(0,55,6950,0,"original");apply(k,message(6950,"queued authority",-1),0,0);apply(k,null,2,0);return null;});
        PurpleArchiveDeletedModel model=new PurpleArchiveDeletedModel(scope(0,55));PurpleArchiveQueries.Page opened=page(model);PurpleArchiveViewerModel pinned=model.history(opened.entries.get(0));
        AtomicReference<PurpleArchiveQueries.Page> late=new AtomicReference<>();check(model.next(late::set),"revocation case queues a page");on(()->null);
        if(mode.equals("logout")){long start=System.nanoTime();PurpleArchive.beforeLogout(0);check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<250,"logout does not wait for query IO");UserConfig.getInstance(0).active=false;}
        else{PurpleArchive.RemovalRequest request=PurpleArchive.prepareRemoval(0);check(request!=null&&PurpleArchive.remove(request,()->{}),"confirmed discard admits original account");await(()->request.completed,"discard completes");}
        drain();check(late.get()==null||!late.get().status.equals("Ready"),"queued page cannot deliver old content after logout or discard");check(!model.available()&&!model.next(v->{}),"old query page admission stays revoked");check(pinned==null||!pinned.available(),"linked history does not adopt retired incarnation");if(pinned!=null)pinned.close();model.close();
        check(Arrays.equals(before,java.nio.file.Files.readAllBytes(db.toPath()))&&Arrays.equals(beforeWal,java.nio.file.Files.readAllBytes(wal.toPath())),"B database and WAL remain byte-identical");
        if(mode.equals("logout"))await(()->worker.retired,"logout cleanup retires only A");
        other.queue.submit(()->{other.store.drainReaders();other.store.close();other.store=null;return null;}).get(40,TimeUnit.SECONDS);other.queue.shutdown();
    }
    public static void main(String[] args)throws Exception {
        System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(args[1]);check(root.mkdir(),"exclusive query fixture");ApplicationLoader.files=new File(root,"files");check(ApplicationLoader.files.mkdir(),"app files");ApplicationLoader.applicationContext=new android.content.Context();
        UserConfig.selectedAccount=0;UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=slot->"11111111111111111111111111111111";
        PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"account ready");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));
        try{switch(args[2]){case "pages":pages();break;case "usage":usage();break;case "corrupt":corrupt();break;case "model":model();break;case "logout":case "discard":revoke(args[2]);break;default:throw new AssertionError("mode");}}
        finally{PurpleArchiveDeletedModel.revoke(-1);if(!worker.retired)on(()->{if(worker.store!=null){worker.store.drainReaders();worker.store.close();worker.store=null;}return null;});worker.queue.shutdown();}
        System.out.println("ARCHIVE_QUERY_"+args[2]+"_CHECKS="+checks);
    }
}
