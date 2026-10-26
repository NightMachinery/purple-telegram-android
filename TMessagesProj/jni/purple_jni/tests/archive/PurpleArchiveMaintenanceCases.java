package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveMaintenanceCases {
    static JSONObject parent(){try{return new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(0,55)).put("aliases",new JSONArray());}catch(Exception e){throw new RuntimeException(e);}}
    static PurpleArchivePurgeModel.Scope scope(JSONObject value)throws Exception{return PurpleArchivePurgeModel.Scope.parent(PurpleArchiveQueryCases.scope(value));}
    static void settled()throws Exception{
        long[] printed={System.nanoTime()};
        await(()->{try{return on(()->{
            boolean idle=!PurpleArchiveMaintenance.busy(worker.store);
            if(!idle&&System.nanoTime()-printed[0]>5_000_000_000L){
                printed[0]=System.nanoTime();java.lang.reflect.Field field=PurpleArchiveMaintenance.class.getDeclaredField("runners");field.setAccessible(true);
                Map<?,?> runners=(Map<?,?>)field.get(null);PurpleArchiveMaintenance.Runner runner;
                synchronized(runners){runner=(PurpleArchiveMaintenance.Runner)runners.get(worker.store);}
                JSONObject info=new JSONObject().put("status",worker.status.state).put("reason",worker.status.reason).put("gate",worker.store.maintenance).put("queue",worker.queue.getQueue().size());
                if(runner!=null)info.put("renewed",runner.renewed).put("attempts",runner.renewalAttempts).put("jobsComplete",runner.jobsComplete).put("jobCursor",runner.jobCursor).put("pendingBlob",runner.pendingBlob).put("sweep",runner.sweep==null?-1:runner.sweep.stack.size());
                try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){info.put("durable",snapshot.inspection);}catch(Exception e){info.put("inspectionError",e.toString());}
                System.out.println("MAINTENANCE_DIAGNOSTIC="+info);
            }
            return idle;
        });}catch(Exception e){throw new RuntimeException(e);}},"bounded maintenance runner finishes");
    }
    static PurpleArchivePurgeModel.Preview review(PurpleArchivePurgeModel model)throws Exception{
        AtomicReference<PurpleArchivePurgeModel.State> delivered=new AtomicReference<>();check(model.prepare(delivered::set),"original model accepts one review");await(()->delivered.get()!=null,"review delivered");
        check(delivered.get().state.equals("Ready")&&delivered.get().preview!=null,"same snapshot yields counts and a core confirmation");return delivered.get().preview;
    }
    static PurpleArchivePurgeModel.State confirm(PurpleArchivePurgeModel model,PurpleArchivePurgeModel.Preview preview)throws Exception{
        AtomicReference<PurpleArchivePurgeModel.State> delivered=new AtomicReference<>();check(model.confirm(preview,delivered::set),"confirmation submitted once");
        check(!model.confirm(preview,v->{}),"duplicate submission refused");await(()->delivered.get()!=null,"confirmation result delivered");return delivered.get();
    }
    static long[] counts(JSONObject scope)throws Exception{return on(()->{try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){
        JSONObject plan=PurpleArchiveMaintenance.call(worker.store,"maintenanceQuery",worker.store.arguments().put("query","counts").put("scope",scope));
        PurpleArchiveSqlite.Result rows=PurpleArchiveMaintenance.read(snapshot,plan,7);check(rows.rows==1,"counts have authoritative row");long[] result=new long[7];for(int i=0;i<result.length;++i)result[i]=rows.values[0][i].integer;return result;
    }});}
    static JSONObject seed(long chat,int id,boolean deleted)throws Exception{
        JSONObject key=PurpleArchiveQueryCases.customKey(0,chat,id,0,"original");
        PurpleArchiveQueryCases.apply(key,message(id,"first "+id,-1),0,0);PurpleArchiveQueryCases.apply(key,message(id,"last "+id,-1),1,0);
        if(deleted)PurpleArchiveQueryCases.apply(key,null,2,0);return key;
    }
    static JSONObject beginOnly(JSONObject scope,int kind,PurpleArchivePurgeModel.Preview preview)throws Exception{
        JSONObject plan;try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){
            JSONObject args=snapshot.arguments().put("scope",scope).put("kind",kind).put("confirmation",new JSONObject(preview.confirmation)).put("operationId",PurpleArchiveBinding.token());
            PurpleArchiveMaintenance.epochs(worker.store,snapshot,args,scope);plan=PurpleArchiveMaintenance.call(worker.store,"maintenanceBegin",args);
        }
        check(worker.store.executeMaintenance(new PurpleArchiveCore.Program(plan),()->true).committed,"actual Begin commits via the sole Store boundary");check(worker.store.maintenance,"published gate follows durable Begin");return plan;
    }
    static JSONObject continueOne()throws Exception{
        JSONObject plan;try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){
            plan=PurpleArchiveMaintenance.call(worker.store,"maintenanceContinue",snapshot.arguments().put("rows",PurpleArchiveMaintenance.page(worker.store,snapshot)));
        }
        check(worker.store.executeMaintenance(new PurpleArchiveCore.Program(plan),()->true).committed,"actual bounded continuation commits");return plan;
    }
    static void complete()throws Exception{on(()->{PurpleArchiveMaintenance.resume(worker);return null;});settled();check(PurpleArchive.status(0).state.equals("Ready"),"capture admission and cleanup recover to Ready");}
    static Map<String,String> files(File root)throws Exception{
        Map<String,String> result=new TreeMap<>();try(java.util.stream.Stream<java.nio.file.Path> stream=Files.walk(root.toPath())){
            for(java.nio.file.Path path:(Iterable<java.nio.file.Path>)stream.filter(Files::isRegularFile)::iterator)result.put(root.toPath().relativize(path).toString(),PurpleArchiveCore.base64(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }return result;
    }
    static void ordinary(String mode)throws Exception{
        JSONObject[] keys=on(()->new JSONObject[]{seed(55,9100,true),seed(55,9101,false),seed(66,9102,true)});
        if(mode.equals("wide"))on(()->{
            for(int i=0;i<4;++i){JSONObject key=PurpleArchiveQueryCases.customKey(i==3?1:0,i==3?77:55,9110+i,i==1?1:i==2?2:0,i==2?"translated-en":"original");
                PurpleArchiveQueryCases.apply(key,message(9110+i,"all forms",-1),0,i==0?42:0);PurpleArchiveQueryCases.apply(key,null,2,0);}return null;
        });
        JSONObject value=parent();if(mode.equals("wide"))value.put("topic",new JSONObject().put("chat",value.getJSONObject("chat")).put("id","42")).put("collection",0).put("representation","original").getJSONArray("aliases").put(PurpleArchiveQueries.Scope.chat(1,77));
        int kind=mode.equals("history")?1:0;
        PurpleArchivePurgeModel.Scope opening=mode.equals("account")?PurpleArchivePurgeModel.Scope.account(0):scope(value);
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(opening,kind)){
            PurpleArchivePurgeModel.Preview preview=review(model);check(preview.counts.get(0)==(mode.equals("account")?3:mode.equals("wide")?6:2),"review counts cover actual parent/all forms rather than a topic filter");
            if(mode.equals("stale")){
                on(()->{seed(55,9120,false);return null;});PurpleArchivePurgeModel.State result=confirm(model,preview);check(!result.state.equals("Committed")&&!worker.store.maintenance,"ordinary revision change requires a new confirmation");check(counts(parent())[1]==1,"stale confirmation changes no trash");return;
            }
            if(mode.equals("policy"))throw new AssertionError("dedicated policy case required");
            check(confirm(model,preview).state.equals("Committed"),"valid original confirmation starts a durable purge");settled();
        }
        if(kind==0){check(counts(parent())[1]==0,"all target deleted payloads removed");check(counts(parent())[2]==2,"live exact versions retained");
            JSONObject other=new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(0,66)).put("aliases",new JSONArray());check(counts(other)[1]==(mode.equals("account")?0:1),"scope never guesses another chat");}
        else{check(counts(parent())[2]==2&&counts(parent())[3]==0&&counts(parent())[1]==1,"history keeps exact latest bodies, including deleted head");
            on(()->{for(JSONObject key:Arrays.asList(keys[0],keys[1]))try(PurpleArchiveSlice slice=new PurpleArchiveSlice(worker.store,key,null)){check(slice.head!=null&&slice.head.getJSONObject("content").getString("text").startsWith("last"),"latest captured body stays exact");}return null;});}
    }
    static void boundary(String mode)throws Exception{
        TLRPC.Message media=message(9130,"bound media",41);PurpleArchiveB3bCases.retain(media,bytes(41));on(()->{PurpleArchiveQueryCases.apply(key(media),null,2,0);return null;});
        PurpleArchiveDeletedModel parent=new PurpleArchiveDeletedModel(PurpleArchiveQueryCases.scope(0,55));AtomicReference<PurpleArchiveQueries.Page> first=new AtomicReference<>();check(parent.next(first::set),"Deleted page opens");await(()->first.get()!=null,"Deleted page ready");
        PurpleArchiveViewerModel history=parent.history(first.get().entries.get(0));check(history!=null,"linked history model captured");AtomicReference<PurpleArchiveViewerModel.Page> queued=new AtomicReference<>();
        PurpleArchiveMediaAccess.Capability cap=PurpleArchiveB3bCases.open(media);check(cap!=null&&cap.available(),"real leased media is initially available");
        PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0);PurpleArchivePurgeModel.Preview preview=review(model);
        PurpleArchiveCapture.Input old=new PurpleArchiveCapture.Input(key(media).toString(),PurpleArchiveNormalize.body(media).toString(),PurpleArchiveOrigin.baseline(),false);
        check(history.next(queued::set),"history page queued");on(()->null);check(queued.get()==null,"Ready history result held in UI queue");
        on(()->{beginOnly(parent(),0,preview);check(!parent.available()&&!history.available()&&!cap.available(),"same commit withdraws parent history and media authority");return null;});
        ArrayList<Runnable> ui;synchronized(AndroidUtilities.class){ui=new ArrayList<>(AndroidUtilities.pending);AndroidUtilities.pending.clear();}for(Runnable task:ui)task.run();
        check(queued.get()==null||!queued.get().error.isEmpty()||queued.get().versions.isEmpty(),"queued Ready history cannot deliver after Begin");check(!history.next(v->{}),"Older stops at committed boundary");
        on(()->{refuses(()->new PurpleArchiveSlice(worker.store,key(media),null),"normal readers refuse the maintenance gate");return null;});complete();
        if(mode.equals("ingress")){
            check(!old.ingress.current(worker),"pre-purge input never acquires a fresh generation");ArrayList<PurpleArchiveCapture.Input> oldBatch=new ArrayList<>();oldBatch.add(old);PurpleArchive.capture(PurpleAccountFiles.capture(0),oldBatch);
            check(counts(parent())[1]==0,"late baseline does not resurrect purged payload");
            TLRPC.Message fresh=message(9131,"fresh post-Finish observation",-1);ArrayList<PurpleArchiveCapture.Input> freshBatch=new ArrayList<>();freshBatch.add(new PurpleArchiveCapture.Input(key(fresh).toString(),PurpleArchiveNormalize.body(fresh).toString(),PurpleArchiveOrigin.baseline(),false));PurpleArchive.capture(PurpleAccountFiles.capture(0),freshBatch);
            on(()->{try(PurpleArchiveSlice slice=new PurpleArchiveSlice(worker.store,key(fresh),null)){check(slice.head!=null,"genuinely new post-Finish observations use fresh admission");}return null;});
        }
        await(()->cap.released,"revoked reader eventually releases its durable pin");cap.close();history.close();parent.close();model.close();
    }
    static void purge(JSONObject value)throws Exception{
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(value),0)){
            check(confirm(model,review(model)).state.equals("Committed"),"confirmed local trash purge starts");settled();
        }
    }
    static void sharedGarbage()throws Exception{
        TLRPC.Message a=message(9170,"A retained attachment",41),b=message(9171,"B shares the retained bytes",41);b.dialog_id=66;b.peer_id.user_id=66;
        byte[] data=bytes(41);PurpleArchiveB3bCases.retain(a,data);PurpleArchiveB3bCases.retain(b,data);
        PurpleArchiveMediaAccess.Capability first=PurpleArchiveB3bCases.open(a);check(first!=null,"shared canonical copy opens");String token=first.blobToken;
        first.close();await(()->first.released,"initial shared reader releases");settled();
        on(()->{PurpleArchiveQueryCases.apply(key(a),null,2,0);return null;});purge(parent());
        check(on(()->PurpleArchiveFiles.markAny(worker.store.directory.child("blobs",true).file(token))!=null),"purging A preserves the blob still referenced by B");
        PurpleArchiveMediaAccess.Capability retained=PurpleArchiveB3bCases.open(b);check(retained!=null&&retained.blobToken.equals(token),"other chat retains exact shared Complete bytes");
        check(Arrays.equals(retained.read(0,41,()->true),data),"shared bytes remain authenticated and readable");retained.close();await(()->retained.released,"shared reader releases before final purge");settled();
        on(()->{PurpleArchiveQueryCases.apply(key(b),null,2,0);return null;});
        JSONObject other=new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(0,66)).put("aliases",new JSONArray());purge(other);
        check(on(()->PurpleArchiveFiles.markAny(worker.store.directory.child("blobs",true).file(token))==null),"unreferenced and unleased canonical directory is removed before GC completion");
    }
    static void manyRows()throws Exception{
        for(int batch=0;batch<10;++batch){final int start=batch*13;on(()->{for(int i=start;i<start+13;++i)seed(55,9200+i,true);return null;});}
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);check(preview.counts.get(1)==130,"large confirmation reports every deleted message");
            on(()->{beginOnly(parent(),0,preview);continueOne();JSONObject plan=continueOne();int processed=plan.getInt("processedRows");check(processed>0&&processed<=128,"bounded planner commits only a nonempty prefix");
                try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){check(snapshot.inspection.getBoolean("pending")&&snapshot.inspection.getInt("phase")==1,"remaining rows keep the message phase pending");}return null;});complete();
        }
        check(counts(parent())[1]==0,"every row after the first bounded prefix is eventually purged");
    }
    static void scopeFences()throws Exception{
        on(()->{seed(55,9191,true);return null;});AtomicBoolean permitted=new AtomicBoolean(true);
        PurpleArchiveQueries.Scope original=PurpleArchiveQueryCases.scope(parent());
        JSONObject filtered=parent().put("collection",2).put("representation","translated").put("topic",new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(0,55)).put("id","42"));
        check(original.samePurgeScope(PurpleArchiveQueryCases.scope(filtered)),"topic and representation filters do not narrow the parent purge");
        JSONObject moved=parent();moved.getJSONArray("aliases").put(PurpleArchiveQueries.Scope.chat(1,77));
        check(!original.samePurgeScope(PurpleArchiveQueryCases.scope(moved)),"changed trusted migration aliases require a new review");
        moved=new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(0,66)).put("aliases",new JSONArray());
        check(!original.samePurgeScope(PurpleArchiveQueryCases.scope(moved)),"different typed parent cannot reuse the opening scope");
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(PurpleArchivePurgeModel.Scope.parent(original.withAuthority(permitted::get)),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);permitted.set(false);
            check(!model.confirm(preview,v->{}),"UI-owned revocation flag refuses an already reviewed confirmation");
            check(counts(parent())[1]==1&&!worker.store.maintenance,"scope refusal changes no data or maintenance state");
        }
        AtomicBoolean deliveryAllowed=new AtomicBoolean(true);try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(PurpleArchivePurgeModel.Scope.parent(original.withAuthority(deliveryAllowed::get)),0)){
            AtomicReference<PurpleArchivePurgeModel.State> delivered=new AtomicReference<>();check(model.prepare(delivered::set),"scope preview enqueued");on(()->null);deliveryAllowed.set(false);
            ArrayList<Runnable> pending;synchronized(AndroidUtilities.class){pending=new ArrayList<>(AndroidUtilities.pending);AndroidUtilities.pending.clear();}for(Runnable task:pending)task.run();
            check(delivered.get()==null&&!model.available(),"scope revocation suppresses an already queued Ready preview");
        }
    }
    static void removalRace(boolean discard)throws Exception{
        UserConfig.getInstance(1).active=true;UserConfig.getInstance(1).user=22;PurpleArchive.refresh(1,()->{});await(()->PurpleArchive.status(1).state.equals("Ready"),"account B opens independently");
        PurpleArchive.Worker other=PurpleArchive.existing(PurpleAccountFiles.capture(1));
        await(()->{try{return other.queue.submit(()->!PurpleArchiveMaintenance.busy(other.store)).get(40,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}},"account B initial maintenance settles");
        Map<String,String> before=other.queue.submit(()->files(other.store.directory.file)).get(40,java.util.concurrent.TimeUnit.SECONDS);
        Map<String,String> metadata=other.queue.submit(()->files(other.store.paths.metadata.file)).get(40,java.util.concurrent.TimeUnit.SECONDS);
        String alias=other.store.binding.alias();byte[] keyBefore=PurpleArchivePlatformProvider.keys.get(alias).getEncoded();
        on(()->{seed(55,9192,true);return null;});try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);on(()->{beginOnly(parent(),0,preview);return null;});
            File owned=worker.store.directory.file;
            if(discard){PurpleArchive.RemovalRequest request=PurpleArchive.prepareRemoval(0);check(request!=null&&PurpleArchive.remove(request,()->{}),"explicit discard accepts the interrupted purge");}
            else{long start=System.nanoTime();PurpleArchive.beforeLogout(0);UserConfig.getInstance(0).active=false;check(System.nanoTime()-start<1_000_000_000L,"logout revokes without waiting for purge or filesystem cleanup");}
            await(()->worker.retired,"removal wins over an interrupted purge");check(!owned.exists(),"all account-A purge/store artifacts are removed");
            Map<String,String> after=other.queue.submit(()->files(other.store.directory.file)).get(40,java.util.concurrent.TimeUnit.SECONDS);
            Map<String,String> metadataAfter=other.queue.submit(()->files(other.store.paths.metadata.file)).get(40,java.util.concurrent.TimeUnit.SECONDS);
            check(before.equals(after)&&metadata.equals(metadataAfter)&&PurpleArchivePlatformProvider.keys.containsKey(alias)
                &&Arrays.equals(keyBefore,PurpleArchivePlatformProvider.keys.get(alias).getEncoded()),"account B catalogue, data and key bytes remain unchanged");
        }finally{other.queue.submit(()->{other.store.drainReaders();other.store.close();other.store=null;return null;}).get(40,java.util.concurrent.TimeUnit.SECONDS);other.queue.shutdown();}
    }
    static void largeHistory()throws Exception{
        char[] a=new char[131073],b=new char[131077];Arrays.fill(a,'a');Arrays.fill(b,'b');String latest=new String(b);
        JSONObject key=PurpleArchiveQueryCases.customKey(0,55,9190,0,"original");
        on(()->{PurpleArchiveQueryCases.apply(key,message(9190,new String(a),-1),0,0);PurpleArchiveQueryCases.apply(key,message(9190,latest,-1),1,0);PurpleArchiveQueryCases.apply(key,null,2,0);return null;});
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),1)){check(confirm(model,review(model)).state.equals("Committed"),"split-body history purge starts");settled();}
        on(()->{try(PurpleArchiveSlice slice=new PurpleArchiveSlice(worker.store,key,null)){
            check(slice.head!=null&&slice.head.getJSONObject("content").getString("text").equals(latest),"latest split body survives byte-exactly");
        }return null;});
        check(counts(parent())[2]==1,"earlier split body version is removed without restoring it into maintenance evidence");
    }
    static void lostAcknowledgement()throws Exception{
        on(()->{seed(55,9180,true);return null;});
        PurpleArchiveDeletedModel parent=new PurpleArchiveDeletedModel(PurpleArchiveQueryCases.scope(0,55));AtomicReference<PurpleArchiveQueries.Page> ready=new AtomicReference<>();check(parent.next(ready::set),"lost-ack parent opens");await(()->ready.get()!=null,"lost-ack parent page delivered");
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);
            on(()->{
                JSONObject plan;String operation=PurpleArchiveBinding.token();
                try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){
                    JSONObject args=snapshot.arguments().put("scope",parent()).put("kind",0).put("confirmation",new JSONObject(preview.confirmation)).put("operationId",operation);
                    PurpleArchiveMaintenance.epochs(worker.store,snapshot,args,parent());plan=PurpleArchiveMaintenance.call(worker.store,"maintenanceBegin",args);
                }
                java.lang.reflect.Field field=PurpleArchiveStore.class.getDeclaredField("database");field.setAccessible(true);PurpleArchiveSqlite db=(PurpleArchiveSqlite)field.get(worker.store);
                PurpleArchiveCore.Execution result=worker.store.executeMaintenance(new PurpleArchiveCore.Program(plan),()->{
                    if(!db.autocommit())return true;PurpleArchiveSqlite.Result gate=db.run("SELECT maintenance_id FROM archive_meta WHERE singleton=1");return gate.rows==1&&gate.values[0][0].text().isEmpty();
                });
                check(!result.committed,"completion loses acknowledgement after the actual SQL commit");
                check(worker.store.maintenance&&!parent.available(),"durable gate and cached revocation publish even without an acknowledgement");
                try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){check(snapshot.inspection.getBoolean("pending")&&snapshot.inspection.getString("operation").equals(operation),"authenticated original operation survives lost acknowledgement");}
                return null;
            });complete();
        }finally{parent.close();}
        check(counts(parent())[1]==0,"lost acknowledgement resumes the sealed original operation");
    }
    static void emptyProof()throws Exception{
        on(()->{seed(55,9140,true);return null;});try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);on(()->{beginOnly(parent(),0,preview);continueOne();return null;});
            on(()->{JSONObject plan;String before;try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){
                check(snapshot.inspection.getInt("phase")==1,"message phase has an actual target");before=snapshot.inspection.toString();plan=PurpleArchiveMaintenance.call(worker.store,"maintenanceContinue",snapshot.arguments().put("rows",new JSONArray()));}
                check(!worker.store.executeMaintenance(new PurpleArchiveCore.Program(plan),()->true).committed,"fabricated empty page cannot advance while rows remain");
                try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){check(snapshot.inspection.toString().equals(before),"failed exhaustion proof leaves phase/cursor unchanged");}return null;});complete();
        }
    }
    static void selection()throws Exception{
        on(()->{seed(55,9150,true);return null;});try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            AtomicReference<PurpleArchivePurgeModel.State> delivered=new AtomicReference<>();check(model.prepare(delivered::set),"review queued");on(()->null);check(delivered.get()==null,"preview held before UI delivery");
            UserConfig.selectedAccount=1;PurpleArchiveViewerModel.selectionChanged(1);ArrayList<Runnable> ui;synchronized(AndroidUtilities.class){ui=new ArrayList<>(AndroidUtilities.pending);AndroidUtilities.pending.clear();}for(Runnable task:ui)task.run();
            check(delivered.get()==null&&!model.available(),"selection change revokes pending preview delivery");UserConfig.selectedAccount=0;PurpleArchiveViewerModel.selectionChanged(0);check(!model.prepare(v->{}),"A to B to A cannot revive old confirmation");
        }
    }
    static PurpleArchiveSqlite writer()throws Exception{
        java.lang.reflect.Field field=PurpleArchiveStore.class.getDeclaredField("database");field.setAccessible(true);return(PurpleArchiveSqlite)field.get(worker.store);
    }
    static void saveKeys(File root)throws Exception{
        JSONObject keys=new JSONObject();for(Map.Entry<String,javax.crypto.SecretKey> entry:PurpleArchivePlatformProvider.keys.entrySet())keys.put(entry.getKey(),PurpleArchiveCore.base64(entry.getValue().getEncoded()));
        Files.write(new File(root,"maintenance-fixture.json").toPath(),keys.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void committedSelection()throws Exception{
        on(()->{seed(55,9400,true);seed(66,9401,true);return null;});
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);on(()->{beginOnly(parent(),0,preview);return null;});
            UserConfig.selectedAccount=1;PurpleArchiveViewerModel.selectionChanged(1);model.close();
            check(!model.available(),"selection change closes the confirmation observer");complete();
            check(counts(parent())[1]==0,"closing the observer cannot cancel or retarget the committed purge");
            JSONObject other=new JSONObject().put("chat",PurpleArchiveQueries.Scope.chat(0,66)).put("aliases",new JSONArray());
            check(counts(other)[1]==1,"background continuation retains the original sealed parent scope");
        }finally{UserConfig.selectedAccount=0;PurpleArchiveViewerModel.selectionChanged(0);}
    }
    static void journalFailure(String mode)throws Exception{
        on(()->{seed(55,9410,true);return null;});
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);on(()->{
                beginOnly(parent(),0,preview);PurpleArchiveSqlite db=writer();
                String sql=mode.equals("journal-schema")?"UPDATE archive_meta SET schema_version=1":mode.equals("journal-envelope")?"UPDATE maintenance SET envelope=x'00'":mode.equals("journal-kind")?"UPDATE maintenance SET kind=99":"UPDATE maintenance SET cursor='fabricated'";
                check(db.run(sql).counts(0,0,1,1,true),"fixture corrupts exactly its retained maintenance row");return null;
            });
        }
        String before=on(()->PurpleArchiveCore.base64(writer().run("SELECT envelope FROM maintenance WHERE singleton=1").values[0][0].bytes));
        on(()->{PurpleArchiveMaintenance.resume(worker);return null;});settled();
        check(worker.status.state.equals("Maintenance held")&&worker.store.maintenance,"invalid or unauthenticated journal stays held behind the gate");
        on(()->{refuses(()->new PurpleArchiveSlice(worker.store,PurpleArchiveQueryCases.customKey(0,55,9410,0,"original"),null),"invalid journal cannot become an empty readable archive");
            check(writer().run("SELECT COUNT(*) FROM messages").values[0][0].integer==1,"journal refusal preserves the retained target message");return null;});
        on(()->{PurpleArchiveMaintenance.resume(worker);return null;});settled();
        check(before.equals(on(()->PurpleArchiveCore.base64(writer().run("SELECT envelope FROM maintenance WHERE singleton=1").values[0][0].bytes))),"retry never replaces or repairs an untrusted journal");
        File retained=worker.store.directory.file;PurpleArchive.RemovalRequest request=PurpleArchive.prepareRemoval(0);
        check(request!=null&&PurpleArchive.remove(request,()->{}),"explicit discard remains available for invalid maintenance");
        await(()->worker.retired,"invalid maintenance discard completes");check(!retained.exists(),"confirmed discard erases the invalid journal and account store");
    }
    static PurpleArchiveAcquiredSource.Entry adopted;
    static PurpleArchiveAcquiredSource.Entry registerSource(TLRPC.Message media,boolean initialize)throws Exception{
        PurpleArchiveAcquiredSource.Entry entry=new PurpleArchiveAcquiredSource.Entry(new org.telegram.messenger.FileLoadOperation(),PurpleAccountFiles.capture(0),new org.telegram.messenger.MessageObject(0,media),"document_"+media.media.document.id,"",media.media.document.size);
        entry.keyText=key(media).toString();entry.bodyText=PurpleArchiveNormalize.body(media).toString();
        java.lang.reflect.Field field=PurpleArchiveAcquiredSource.class.getDeclaredField("entries"),count=PurpleArchiveAcquiredSource.class.getDeclaredField("count");field.setAccessible(true);count.setAccessible(true);
        Map map=(Map)field.get(null);synchronized(map){ArrayList<PurpleArchiveAcquiredSource.Entry> list=new ArrayList<>();list.add(entry);map.put(entry.operation,list);count.setInt(null,count.getInt(null)+1);}
        if(initialize)entry.initialize(worker);return entry;
    }
    static void postFinishSource(String mode)throws Exception{
        allRecoverySettled();on(()->{seed(55,9800,true);return null;});
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);on(()->{beginOnly(parent(),0,preview);for(int i=0;worker.store.maintenance&&i<20;++i)continueOne();check(!worker.store.maintenance,"logical Finish commits before fresh acquisition");return null;});
        }
        TLRPC.Message media=message(9801,"new post-Finish source",41);byte[] data=bytes(41);AtomicReference<PurpleArchiveAcquiredSource.Entry> source=new AtomicReference<>();
        check(gapReasons().length()==0,"no retention failure precedes cleanup completion");
        on(()->{PurpleArchiveMaintenance.resume(worker);worker.control(()->worker.control(()->{try{
            PurpleArchiveAcquiredSource.Entry entry=registerSource(media,true);entry.staging.piece(0,mode.equals("post-finish-partial")?new byte[]{data[0]}:data);source.set(entry);
        }catch(Exception e){throw new RuntimeException(e);}}));return null;});
        allRecoverySettled();PurpleArchiveAcquiredSource.Entry entry=source.get();check(entry!=null&&entry.current(),"fresh acquisition retains exact account login store and job authority");allRecoverySettled();
        check(entry.staging.directory.file.exists()&&gapReasons().length()==0,"cleanup recovery neither gaps nor discards the live producer staging");
        try{on(()->{if(mode.equals("post-finish-partial"))entry.staging.piece(1,Arrays.copyOfRange(data,1,data.length));entry.staging.admitConsistency(hash(data));check(entry.staging.finish(),"same live producer can coherently finish the original bytes");return null;});}
        finally{on(()->{PurpleArchiveAcquiredSource.revoke(0);entry.close();return null;});}
    }
    static void partialRestart(File root,String mode)throws Exception{
        File fixture=new File(root,"orphan-source.json");
        if(mode.startsWith("seed")){
            allRecoverySettled();String id=on(()->{PurpleArchiveStaging source=stage(message(9610,"orphan partial",41),new AtomicBoolean(true));source.piece(0,new byte[]{bytes(41)[0]});if(mode.equals("seedcorruptpartial"))source.directory.publish("head",source.directory.read("head",70000),new byte[]{0});check(!PurpleArchiveStaging.orphaned(source.binding),"new source carries the authenticated current-process marker");return source.id;});
            saveKeys(root);Files.write(fixture.toPath(),new JSONObject().put("id",id).toString().getBytes(StandardCharsets.UTF_8));
        }else if(mode.equals("recoverpartial-live")){
            check(adopted!=null&&adopted.current(),"new process adopts the exact old orphan job as a live producer");
            check(on(()->PurpleArchiveStaging.orphaned(adopted.staging.binding)),"adopted binding keeps its original prior-process evidence");allRecoverySettled();
            check(gapReasons().length()==0&&adopted.staging.directory.file.exists(),"live ownership excludes even an old-process source from restart recovery");
            on(()->{byte[] data=bytes(41);adopted.staging.piece(1,Arrays.copyOfRange(data,1,data.length));adopted.staging.admitConsistency(hash(data));check(adopted.staging.finish(),"live adopted orphan completes without recovery replacing its bytes");PurpleArchiveAcquiredSource.revoke(0);adopted.close();return null;});
        }else{
            allRecoverySettled();String id=new JSONObject(new String(Files.readAllBytes(fixture.toPath()),StandardCharsets.UTF_8)).getString("id");
            on(()->{JSONObject binding=PurpleArchiveStaging.readBinding(worker.store,id);check(PurpleArchiveStaging.orphaned(binding),"real restart recognizes the prior process token");check(worker.store.directory.child("staging",true).child("stream",true).file(id).exists(),"incomplete orphan bytes are retained");return null;});
            check(gapReasons().has(mode.equals("recovercorruptpartial")?"staging_recovery_refused":"staging_partial_after_restart")&&on(()->worker.store.hasGaps()),"genuine orphan partial/corruption remains an explicit durable restart gap");
        }
    }
    static void allRecoverySettled()throws Exception{
        java.lang.reflect.Field field=PurpleArchiveStagingRecovery.class.getDeclaredField("runners");field.setAccessible(true);
        await(()->{try{return on(()->{Map<?,?> map=(Map<?,?>)field.get(null);synchronized(map){return !map.containsKey(worker.store)&&!PurpleArchiveMaintenance.busy(worker.store);}});}catch(Exception e){throw new RuntimeException(e);}},"maintenance and staging recovery both finish");
    }
    static JSONObject gapReasons()throws Exception{return on(()->{JSONObject result=new JSONObject();for(PurpleArchiveSqlite.Value[] row:writer().run("SELECT reason,COUNT(*) FROM capture_gaps GROUP BY reason").values)result.put(row[0].text(),row[1].integer);return result;});}
    static void intentionalPurge(String mode)throws Exception{
        allRecoverySettled();TLRPC.Message media=message(9600,"intentional purge",mode.equals("intentional-plain")?-1:41);
        PurpleArchiveMediaAccess.Capability cap=null;PurpleArchiveAcquiredSource.Entry entry=null;File stagedDirectory=null;
        if(mode.equals("intentional-leased"))PurpleArchiveB3bCases.retain(media,bytes(41));
        else if(mode.equals("intentional-staged")||mode.equals("staging-failure-control")){
            stagedDirectory=on(()->{PurpleArchiveStaging staged=stage(media,new AtomicBoolean(true));staged.piece(0,new byte[]{1});
                if(mode.equals("staging-failure-control"))staged.directory.publish("head",staged.directory.read("head",70000),new byte[]{0});return staged.directory.file;});
        }else if(mode.equals("intentional-active")){
            entry=on(()->{PurpleArchiveAcquiredSource.Entry pending=new PurpleArchiveAcquiredSource.Entry(new org.telegram.messenger.FileLoadOperation(),PurpleAccountFiles.capture(0),new org.telegram.messenger.MessageObject(0,media),"document_"+media.media.document.id,"",41);
                pending.keyText=key(media).toString();pending.bodyText=PurpleArchiveNormalize.body(media).toString();pending.initialize(worker);pending.staging.piece(0,new byte[]{1});return pending;});stagedDirectory=entry.staging.directory.file;
        }else on(()->{PurpleArchiveQueryCases.apply(key(media),media,0,0);return null;});
        check(gapReasons().length()==0,"ordinary source fixture begins without durable gaps");
        if(mode.equals("staging-failure-control")){
            PurpleArchiveAcquiredSource.Entry producer=on(()->registerSource(media,false));PurpleArchiveAcquiredSource.schedule(producer);
            await(()->producer.cancelled,"live producer refuses the corrupted retained head");
            await(()->{try{return gapReasons().has("acquired_media_gap");}catch(Exception e){throw new RuntimeException(e);}},"genuine producer failure is durably recorded");
            check(on(()->worker.store.hasGaps()),"genuine staging corruption outside a purge remains a durable retention gap");return;
        }
        on(()->{PurpleArchiveQueryCases.apply(key(media),null,2,0);return null;});allRecoverySettled();
        if(mode.equals("intentional-leased")){cap=PurpleArchiveB3bCases.open(media);check(cap!=null&&cap.available(),"ordinary valid leased reader opens before intentional purge");}
        JSONObject before=gapReasons();PurpleArchiveAcquiredSource.Entry active=entry;
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);on(()->{
                beginOnly(parent(),0,preview);
                if(active!=null){java.lang.reflect.Method cancel=PurpleArchiveAcquiredSource.class.getDeclaredMethod("cancel",PurpleArchiveAcquiredSource.Entry.class,boolean.class);cancel.setAccessible(true);cancel.invoke(null,active,true);}
                return null;
            });complete();
        }
        if(cap!=null){PurpleArchiveMediaAccess.Capability held=cap;await(()->held.released,"intentional purge eventually releases deferred pin");}
        allRecoverySettled();JSONObject after=gapReasons();System.out.println("INTENTIONAL_PURGE "+mode+" before="+before+" after="+after);
        check(before.toString().equals(after.toString())&&!on(()->worker.store.hasGaps()),"successful intentional removal never becomes a durable retention gap");
        if(stagedDirectory!=null)check(!stagedDirectory.exists(),"intentional retired staging is removed through bounded owned cleanup");
    }
    static void survivingReference()throws Exception{
        TLRPC.Message donor=message(9450,"complete donor",41);donor.dialog_id=66;donor.peer_id.user_id=66;PurpleArchiveB3bCases.retain(donor,bytes(41));
        TLRPC.Message target=message(9451,"older missing reference",41);target.media.document.id=donor.media.document.id;
        on(()->{PurpleArchiveQueryCases.apply(key(target),target,0,0);target.message="exact latest missing reference";PurpleArchiveQueryCases.apply(key(target),target,1,0);return null;});
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),1)){
            PurpleArchivePurgeModel.Preview preview=review(model);on(()->{
                beginOnly(parent(),1,preview);JSONObject before,bad;
                try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){
                    check(snapshot.inspection.getInt("phase")==0,"surviving latest reference is reconciled before history deletion");before=new JSONObject(snapshot.inspection.toString());
                    JSONArray page=PurpleArchiveMaintenance.page(worker.store,snapshot);check(page.length()==1&&page.getJSONObject(0).getJSONArray("copies").length()==1,"incomplete latest media finds the retained complete owner proof");
                    JSONObject proof=page.getJSONObject(0).getJSONArray("copies").getJSONObject(0);check(proof.has("identity")&&proof.has("kind")&&proof.has("blob")&&proof.has("owner")&&proof.has("position"),"all owned-copy proof fields reach the planner");
                    proof.put("position","99");bad=snapshot.arguments().put("rows",page);
                }
                boolean refused=false;try{JSONObject plan=PurpleArchiveMaintenance.call(worker.store,"maintenanceContinue",bad);refused=!worker.store.executeMaintenance(new PurpleArchiveCore.Program(plan),()->true).committed;}catch(Exception e){refused=true;}
                check(refused,"changed owned-copy position cannot hydrate or advance a purge");
                try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){check(before.toString().equals(snapshot.inspection.toString()),"refused owned-copy proof preserves the exact journal cursor");}
                return null;
            });complete();
        }
        PurpleArchiveMediaAccess.Capability cap=PurpleArchiveB3bCases.open(target);check(cap!=null&&Arrays.equals(cap.read(0,41,()->true),bytes(41)),"exact latest head retains the authenticated complete copy after history cleanup");cap.close();await(()->cap.released,"reconciled latest reader releases");settled();
        on(()->{try(PurpleArchiveSlice slice=new PurpleArchiveSlice(worker.store,key(target),null)){check(slice.head.getJSONObject("content").getString("caption").equals("exact latest missing reference"),"reconciliation never substitutes a donor body or a newer head");}return null;});
    }
    static void cleanupRetry()throws Exception{
        on(()->{
            PurpleArchiveFiles.Directory root=worker.store.directory.child("staging",true);root.exclusiveChild("cleanup-fixture");
            PurpleArchiveMaintenance.Runner runner=new PurpleArchiveMaintenance.Runner(worker);runner.renewed=true;runner.jobsComplete=true;
            runner.sweep=new PurpleArchiveMaintenance.Sweep(root,"cleanup-fixture");PurpleArchiveFiles.Listing listing=runner.sweep.stack.peek().listing;
            java.lang.reflect.Field owner=PurpleArchiveFiles.Listing.class.getDeclaredField("owner");owner.setAccessible(true);owner.set(listing,new Thread("unacknowledged-close-fixture"));
            java.lang.reflect.Field field=PurpleArchiveMaintenance.class.getDeclaredField("runners");field.setAccessible(true);Map<PurpleArchiveStore,PurpleArchiveMaintenance.Runner> runners=(Map<PurpleArchiveStore,PurpleArchiveMaintenance.Runner>)field.get(null);
            synchronized(runners){check(runners.put(worker.store,runner)==null,"one runner owns cleanup");}
            runner.run();check(runner.cleanupHeld&&!runner.closed&&PurpleArchiveMaintenance.busy(worker.store),"failed handle close retains ownership and a visible cleanup obligation");
            check(worker.status.state.equals("Maintenance held"),"unacknowledged close never reports Ready");
            owner.set(listing,Thread.currentThread());PurpleArchiveMaintenance.resume(worker);
            check(runner.cleanupRetryQueued,"explicit retry queues one bounded retained-handle cleanup");PurpleArchiveMaintenance.resume(worker);
            check(runner.cleanupRetryQueued,"repeated retry coalesces rather than replacing the retained owner");return null;
        });settled();check(worker.status.state.equals("Ready"),"acknowledged handle cleanup permits a fresh runner and admission");
    }
    static void cancelledStaging()throws Exception{
        TLRPC.Message media=message(9430,"incomplete acquired bytes",131072);AtomicBoolean active=new AtomicBoolean(true);
        PurpleArchiveStaging staged=on(()->{PurpleArchiveStaging value=stage(media,active);value.piece(0,bytes(65536));return value;});
        File retained=staged.directory.file;check(retained.exists(),"incomplete encrypted staging is retained before purge");
        on(()->{PurpleArchiveQueryCases.apply(key(media),null,2,0);return null;});purge(parent());
        check(!retained.exists(),"acknowledged cancelled job removes only its original staging identity");
        on(()->{refuses(()->staged.piece(65536,bytes(65536)),"old acquired source cannot refill its purged job");return null;});
        check(!retained.exists(),"stale source never recreates the cleaned staging directory");
    }
    static void pinnedReader(File root,String mode)throws Exception{
        if(mode.equals("recoverorphan")){
            settled();String token=new JSONObject(new String(Files.readAllBytes(new File(root,"garbage-fixture.json").toPath()),StandardCharsets.UTF_8)).getString("token");
            await(()->{try{return on(()->writer().run("SELECT COUNT(*) FROM leases").values[0][0].integer==0&&!PurpleArchiveMaintenance.busy(worker.store));}catch(Exception e){throw new RuntimeException(e);}},"startup releases orphan pins and settles garbage cleanup");
            check(on(()->PurpleArchiveFiles.markAny(worker.store.directory.child("blobs",true).file(token))==null),"orphan release permits restart garbage cleanup");return;
        }
        TLRPC.Message media=message(9440,"leased purged bytes",41);PurpleArchiveB3bCases.retain(media,bytes(41));PurpleArchiveMediaAccess.Capability cap=PurpleArchiveB3bCases.open(media);
        check(cap!=null&&cap.available(),"reader owns a committed pin before Begin");String token=cap.blobToken;
        on(()->{PurpleArchiveQueryCases.apply(key(media),null,2,0);return null;});
        try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
            PurpleArchivePurgeModel.Preview preview=review(model);on(()->{
                beginOnly(parent(),0,preview);for(int i=0;worker.store.maintenance&&i<12;++i)continueOne();
                check(!cap.available()&&!worker.store.maintenance,"logical purge finishes with memory authority already withdrawn");
                check(writer().run("SELECT COUNT(*) FROM leases WHERE revoked=1").values[0][0].integer==1,"revoked lease remains durable until actual release");
                try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){
                    check(PurpleArchiveMaintenance.read(snapshot,PurpleArchiveMaintenance.query(worker.store,"garbage"),2).rows==0,"revoked reader still pins its exact blob against garbage collection");
                }
                check(PurpleArchiveFiles.markAny(worker.store.directory.child("blobs",true).file(token))!=null,"owned ciphertext remains until release acknowledgement");
                if(mode.equals("seedorphan")){
                    saveKeys(root);Files.write(new File(root,"garbage-fixture.json").toPath(),new JSONObject().put("token",token).toString().getBytes(StandardCharsets.UTF_8));
                    worker.store.drainReaders();worker.store.close();worker.store=null;
                }
                return null;
            });
        }
        if(!mode.equals("seedorphan")){
            await(()->cap.released,"actual reader release completes after Finish");settled();
            check(on(()->PurpleArchiveFiles.markAny(worker.store.directory.child("blobs",true).file(token))==null),"release acknowledgement wakes deferred physical garbage cleanup");
        }
    }
    static void garbageRestart(File root,String mode)throws Exception{
        int cut=Integer.parseInt(mode.replace("seedgc","").replace("recovergc",""));
        if(mode.startsWith("seedgc")){
            TLRPC.Message media=message(9420,"purged owned bytes",41);byte[] bytes=bytes(41);PurpleArchiveB3bCases.retain(media,bytes);
            String token=on(()->PurpleArchiveCore.call("blobToken",worker.store.arguments().put("blob",hash(bytes)),worker.store.crypto()).getString("token"));
            on(()->{PurpleArchiveQueryCases.apply(key(media),null,2,0);return null;});
            try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
                PurpleArchivePurgeModel.Preview preview=review(model);on(()->{
                    beginOnly(parent(),0,preview);for(int i=0;worker.store.maintenance&&i<12;++i)continueOne();check(!worker.store.maintenance,"purge Finish is durable before garbage cleanup");
                    if(cut>0){JSONObject plan;try(PurpleArchiveStore.MaintenanceRead snapshot=worker.store.maintenanceRead()){
                        PurpleArchiveSqlite.Result candidate=PurpleArchiveMaintenance.read(snapshot,PurpleArchiveMaintenance.query(worker.store,"garbage"),2);
                        check(candidate.rows==1&&candidate.values[0][0].text().equals(token),"unreferenced garbage candidate is the exact retained blob");
                        plan=PurpleArchiveMaintenance.call(worker.store,"maintenanceGarbage",snapshot.arguments().put("blob",PurpleArchiveMaintenance.row(5,token,candidate.values[0][1])));
                    }check(worker.store.executeMaintenance(new PurpleArchiveCore.Program(plan),()->true).committed,"garbage intent is durable before physical deletion");}
                    if(cut==2)worker.store.directory.child("blobs",true).child(token,true).remove("chunk-0",false);
                    if(cut==3)try(PurpleArchiveMaintenance.Sweep sweep=new PurpleArchiveMaintenance.Sweep(worker.store.directory.child("blobs",true),token)){
                        check(sweep.step(worker.store,()->true),"physical owned bytes are removed before completion acknowledgement");
                    }
                    return null;
                });
            }
            Files.write(new File(root,"garbage-fixture.json").toPath(),new JSONObject().put("token",token).toString().getBytes(StandardCharsets.UTF_8));saveKeys(root);
        }else{
            settled();String token=new JSONObject(new String(Files.readAllBytes(new File(root,"garbage-fixture.json").toPath()),StandardCharsets.UTF_8)).getString("token");
            check(counts(parent())[1]==0,"restart keeps the completed logical purge");on(()->{
                check(PurpleArchiveFiles.markAny(worker.store.directory.child("blobs",true).file(token))==null,"restart finishes owned cleanup after the recorded garbage cut");
                check(writer().run("SELECT COUNT(*) FROM blobs").values[0][0].integer==0,"garbage acknowledgement follows physical cleanup on restart");return null;
            });
        }
    }
    static void restart(File root,String mode)throws Exception{
        int cut=Integer.parseInt(mode.replace("seed","").replace("recover",""));File fixture=new File(root,"maintenance-fixture.json");
        if(mode.startsWith("seed")){
            on(()->{seed(55,9160,true);seed(55,9161,false);return null;});try(PurpleArchivePurgeModel model=new PurpleArchivePurgeModel(scope(parent()),0)){
                PurpleArchivePurgeModel.Preview preview=review(model);on(()->{beginOnly(parent(),0,preview);for(int i=0;i<cut;++i)continueOne();return null;});
            }
            saveKeys(root);check(worker.store.maintenance==(cut<6),"process-death cut preserves its actual durable gate state");
        }else{
            settled();check(counts(parent())[1]==0&&counts(parent())[2]==2,"cold startup authenticates and resumes original sealed purge before capture/query access");
        }
    }
    public static void main(String[] args)throws Exception{
        System.load(args[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(args[1]);String mode=args[2];boolean recover=mode.startsWith("recover");
        if(!recover)check(root.mkdir(),"exclusive maintenance fixture");ApplicationLoader.files=new File(root,"files");if(!recover)check(ApplicationLoader.files.mkdir(),"files");ApplicationLoader.applicationContext=new android.content.Context();
        UserConfig.selectedAccount=0;UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=s->"11111111111111111111111111111111";
        if(recover){JSONObject keys=new JSONObject(new String(Files.readAllBytes(new File(root,"maintenance-fixture.json").toPath()),StandardCharsets.UTF_8));for(String alias:keys.keySet())PurpleArchivePlatformProvider.keys.put(alias,new SecretKeySpec(PurpleArchiveCore.unbase64(keys.getString(alias)),"AES"));}
        if(mode.equals("recoverpartial-live")){
            worker=PurpleArchive.ingressWorker(PurpleAccountFiles.capture(0).key);on(()->{worker.open(PurpleAccountFiles.capture(0));check(worker.store!=null&&worker.status.state.equals("Ready"),"cold open admits the original store before queued recovery");adopted=registerSource(message(9610,"orphan partial",41),true);return null;});
        }else{PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"production archive opens/resumes");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));}settled();
        try{
            if(mode.equals("seedorphanpartial")||mode.equals("recoverorphanpartial")||mode.equals("seedpartial-live")||mode.equals("recoverpartial-live")||mode.equals("seedcorruptpartial")||mode.equals("recovercorruptpartial"))partialRestart(root,mode);
            else if(mode.startsWith("post-finish-"))postFinishSource(mode);
            else if(mode.equals("pinned-reader")||mode.equals("seedorphan")||mode.equals("recoverorphan"))pinnedReader(root,mode);
            else if(mode.startsWith("intentional-")||mode.equals("staging-failure-control"))intentionalPurge(mode);
            else if(mode.equals("surviving-reference"))survivingReference();
            else if(mode.equals("cleanup-retry"))cleanupRetry();
            else if(mode.equals("cancelled-staging"))cancelledStaging();
            else if(mode.startsWith("seedgc")||mode.startsWith("recovergc"))garbageRestart(root,mode);
            else if(mode.startsWith("seed")||mode.startsWith("recover"))restart(root,mode);
            else if(mode.startsWith("journal-"))journalFailure(mode);else if(mode.equals("committed-selection"))committedSelection();
            else if(mode.equals("boundary")||mode.equals("ingress"))boundary(mode);
            else if(mode.equals("scope-fences"))scopeFences();else if(mode.equals("logout-race"))removalRace(false);else if(mode.equals("discard-race"))removalRace(true);else if(mode.equals("large-history"))largeHistory();else if(mode.equals("lost-ack"))lostAcknowledgement();else if(mode.equals("shared-gc"))sharedGarbage();else if(mode.equals("many"))manyRows();else if(mode.equals("empty-proof"))emptyProof();else if(mode.equals("selection"))selection();else ordinary(mode);
        }finally{if(!worker.retired&&worker.store!=null)on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();}
        System.out.println("ARCHIVE_MAINTENANCE_"+mode+"_CHECKS="+checks);
    }
}
