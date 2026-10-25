package org.telegram.messenger.purple;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;
import java.io.File;
import java.security.Security;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
public final class PurpleArchiveDeletedBoundaryCases {
 static void demand(boolean v,String s){if(!v)throw new AssertionError(s);}
 static void drain(){ArrayList<Runnable> tasks;synchronized(AndroidUtilities.class){tasks=new ArrayList<>(AndroidUtilities.pending);AndroidUtilities.pending.clear();}for(Runnable r:tasks)r.run();}
 static void settle()throws Exception{for(int i=0;i<3;i++)on(()->null);}
 static PurpleArchiveQueries.Page page(PurpleArchiveDeletedModel m)throws Exception{AtomicReference<PurpleArchiveQueries.Page> r=new AtomicReference<>();demand(m.next(1,r::set),"page admitted");await(()->r.get()!=null,"page result");return r.get();}
 static JSONObject statement(String sql,boolean write)throws Exception{return new JSONObject().put("sql",sql).put("binds",new JSONArray()).put("expectation",write?2:0).put("counter",write?0:1).put("minRows","0").put("maxRows","0").put("minChanges",write?"1":"0").put("maxChanges",write?"1":"0");}
 static void barrier()throws Exception{
  JSONObject program=new JSONObject().put("status","Ready").put("statements",new JSONArray().put(statement("BEGIN IMMEDIATE",false)).put(statement("UPDATE archive_meta SET maintenance_id='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' WHERE singleton=1 AND maintenance_id=''",true)).put(statement("COMMIT",false)));
  demand(worker.store.execute(new PurpleArchiveCore.Program(program)).committed,"maintenance admission barrier committed through actual Store.execute");
 }
 public static void main(String[] a)throws Exception{
  String mode=a[2];System.load(a[0]);PurpleCore.ensureLoaded();Security.addProvider(new PurpleArchivePlatformProvider());File root=new File(a[1]);demand(root.mkdir(),"exclusive fixture");ApplicationLoader.files=new File(root,"files");demand(ApplicationLoader.files.mkdir(),"files");ApplicationLoader.applicationContext=new android.content.Context();UserConfig.selectedAccount=0;UserConfig.getInstance(0).active=true;UserConfig.getInstance(0).user=11;PurpleArchiveLogin.source=s->"11111111111111111111111111111111";PurpleArchive.refresh(0,()->{});await(()->PurpleArchive.status(0).state.equals("Ready"),"ready");worker=PurpleArchive.existing(PurpleAccountFiles.capture(0));PurpleArchiveDeletedModel model=null;PurpleArchiveViewerModel history=null;
  try{
   TLRPC.Message seed=message(8200,"retained body",-1);on(()->{capture(seed);capture(message(seed.id,"retained edit",-1));PurpleArchiveQueryCases.apply(key(seed),null,2,0);JSONObject k=PurpleArchiveQueryCases.customKey(0,55,8201,0,"original");PurpleArchiveQueryCases.apply(k,message(8201,"second deleted",-1),0,0);PurpleArchiveQueryCases.apply(k,null,2,0);return null;});
   if(mode.equals("partial")){
    TLRPC.Message partial=message(8210,"partial retained media",113);on(()->{stage(partial,new AtomicBoolean(true));PurpleArchiveQueryCases.apply(key(partial),null,2,0);try(PurpleArchiveQueries q=PurpleArchiveQueryCases.query(PurpleArchiveQueryCases.scope(0,55),0,new java.util.concurrent.atomic.AtomicLong())){PurpleArchiveQueries.Page p=q.next(16);boolean seen=false;for(PurpleArchiveQueries.Entry e:p.entries)seen|=e.media.contains(1);System.out.println("partial: status="+p.status+"; partialLabel="+seen+"; logicalBytes="+p.logicalBytes);demand(p.status.equals("Ready")&&seen,"ordinary partial archive must render Partial without a download");}return null;});return;
   }
   model=new PurpleArchiveDeletedModel(PurpleArchiveQueryCases.scope(0,55));PurpleArchiveDeletedModel opening=model;PurpleArchiveQueries.Page first=page(model);demand(first.status.equals("Ready")&&first.entries.size()==1&&first.more,"first page fixture");
   if(mode.equals("queued-history")||mode.equals("detached-history")||mode.equals("shown-history")){
    history=model.history(first.entries.get(0));demand(history!=null,"pinned history");PurpleArchiveViewerModel h=history;AtomicReference<PurpleArchiveViewerModel.Page> pending=new AtomicReference<>();demand(h.next(pending::set),"history request");settle();demand(pending.get()==null,"history Ready held in UI queue");
    if(mode.equals("shown-history")){drain();demand(pending.get()!=null&&pending.get().error.isEmpty(),"history was shown before the barrier");pending.set(null);}
    if(mode.equals("detached-history")){model.close();settle();}
    on(()->{barrier();return null;});boolean parent=model.available(),child=h.available();drain();boolean ready=pending.get()!=null&&pending.get().error.isEmpty()&&!pending.get().versions.isEmpty();System.out.println("queued-history: parentAvailable="+parent+"; historyAvailable="+child+"; queuedReadyDelivered="+ready);demand(!parent&&!child&&!ready,"committed admission change must revoke descendant history and its queued Ready result");
    demand(!h.next(v->{}),"Older cannot execute after the committed barrier");boolean refreshRefused=false;try{h.restart();}catch(Exception e){refreshRefused=true;}demand(refreshRefused,"Refresh and shown actions cannot revive the history");
   }else if(mode.equals("queued-deleted")){
    AtomicReference<PurpleArchiveQueries.Page> pending=new AtomicReference<>();AtomicBoolean revoked=new AtomicBoolean();model.onRevoked(()->revoked.set(true));demand(model.next(1,pending::set),"next queued");settle();demand(pending.get()==null,"Ready held");on(()->{barrier();return null;});boolean available=model.available();drain();boolean ready=pending.get()!=null&&pending.get().status.equals("Ready");System.out.println("queued-deleted: available="+available+"; queuedReadyDelivered="+ready+"; revokedCallback="+revoked.get());demand(!available&&!ready&&revoked.get(),"parent view revoked at commit, no sweep");
   }else if(mode.equals("ordinary-write")||mode.equals("same-chat")){
    AtomicReference<PurpleArchiveQueries.Page> pending=new AtomicReference<>();demand(model.next(1,pending::set),"old snapshot next queued");settle();on(()->{JSONObject k=PurpleArchiveQueryCases.customKey(0,mode.equals("same-chat")?55:66,8220,0,"original");PurpleArchiveQueryCases.apply(k,message(8220,"ordinary capture",-1),0,0);if(mode.equals("same-chat"))PurpleArchiveQueryCases.apply(k,null,2,0);return null;});boolean available=model.available();drain();String status=pending.get()==null?"no result":pending.get().status;System.out.println("ordinary-write: available="+available+"; queuedStatus="+status);demand(available&&status.equals("Ready"),"ordinary capture must preserve the original read snapshot");demand(model.refreshSuggested(),"new archive observations offer Refresh without revocation");demand(pending.get().counts.equals(first.counts),"the original snapshot retains its original counts");for(PurpleArchiveQueries.Entry entry:pending.get().entries)demand(!entry.text.equals("ordinary capture"),"newly deleted observations do not enter the old snapshot");
   }else if(mode.equals("lease-write")){
    TLRPC.Message media=message(8230,"ordinary lease",37);PurpleArchiveB3bCases.retain(media,bytes(37));demand(model.available(),"ordinary media capture retains the Deleted snapshot");
    PurpleArchiveMediaAccess.Capability capability=PurpleArchiveB3bCases.open(media);demand(capability!=null,"ordinary reader lease commits");demand(model.available(),"lease acquisition retains Deleted snapshot");capability.close();settle();demand(model.available(),"lease release retains Deleted snapshot");demand(model.refreshSuggested(),"lease revision may offer Refresh without revoking authority");
   }else throw new AssertionError("mode");
  }finally{if(history!=null)history.close();if(model!=null)model.close();settle();on(()->{worker.store.drainReaders();worker.store.close();worker.store=null;return null;});worker.queue.shutdown();}
 }
}
