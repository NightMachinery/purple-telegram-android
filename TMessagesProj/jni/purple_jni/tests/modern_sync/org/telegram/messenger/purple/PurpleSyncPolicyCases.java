package org.telegram.messenger.purple;
import java.io.File;import java.nio.file.Files;import java.util.Arrays;
import static org.telegram.messenger.purple.PurpleModernSyncCases.*;
final class PurpleSyncPolicyCases {
 static void run(File parent)throws Exception {
  PurpleSyncAutoCases.Port p=PurpleSyncAutoCases.bound(parent,"deferred-enable-latest");int[] intent={1};p.transientAt=p.clock*1000+90000;
  p.engine.enabled(true,()->intent[0]==1);intent[0]=2;p.engine.enabled(true,()->intent[0]==2);
  check(!p.enabled&&p.checks==0&&p.posts==0&&p.retry!=null,"uncommitted Enable waits without network or false on hint");
  p.clock+=90;p.fireRetry();check(p.enabled&&p.disk().getBoolean("enabled"),"latest deferred Enable admission survives repeated request");
  p.saved(OWN);check(p.posts==1,"confirmed opt-in admits subsequent real Save");p.engine.close();
  p=PurpleSyncAutoCases.bound(parent,"deferred-enable-off");int[] toggle={1};p.transientAt=p.clock*1000+90000;p.engine.enabled(true,()->toggle[0]==1);Runnable old=p.retry;
  toggle[0]=2;p.engine.suspend();p.engine.enabled(false,()->toggle[0]==2);old.run();
  check(!p.enabled&&!p.disk().getBoolean("enabled")&&p.checks==0&&p.posts==0,"stale deferred Enable cannot follow newer durable Disable");p.engine.close();
  p=PurpleSyncAutoCases.bound(parent,"save-during-owned-post");p.enable();p.client.holdSend=true;p.saved(OWN);
  check((Boolean)PurpleSyncRuntimeCases.field(PurpleSyncAutoEngine.class,p.engine,"owned"),"in-flight post retains its actual operation capability");
  p.saved(REMOTE);check(PurpleSyncRuntimeCases.field(PurpleSyncAutoEngine.class,p.engine,"waitingSave")==null,"same-owner Save uses native active-run queue rather than a null-token waiter");
  byte[] checkpoint=Files.readAllBytes(new File(p.frame.sync(1),"auto.json").toPath());p.testingBackoff=true;p.exceptionBackoff=new long[]{4,0};p.transientAt=p.clock*1000+90000;p.client.finishSend(true);
  check(p.posts==1&&p.delay>=90000,"queued Save does not cause another issued post on uncertain completion");
  p.clock+=90;p.fireRetry();check(p.posts==1&&Arrays.equals(checkpoint,Files.readAllBytes(new File(p.frame.sync(1),"auto.json").toPath())),"early retry retains exponential gate and exact uncertain checkpoint");p.engine.close();
  check(!PurpleSyncFirstUse.due(PurpleSyncAutoCore.Event.Foreground,1800000899,1800000000)&&PurpleSyncFirstUse.due(PurpleSyncAutoCore.Event.Foreground,1800000900,1800000000),"documented first-use lifecycle cooldown ends at fifteen minutes");
  for(PurpleSyncAutoCore.Event event:new PurpleSyncAutoCore.Event[]{PurpleSyncAutoCore.Event.Enable,PurpleSyncAutoCore.Event.LocalSaved,PurpleSyncAutoCore.Event.ChatUpdated})check(PurpleSyncFirstUse.due(event,1800000001,1800000000),"explicit action or delivered update does not consume lifecycle cooldown");
  System.out.println("SYNC_POLICY_COUNTERGATES_PASS");
 }
}
