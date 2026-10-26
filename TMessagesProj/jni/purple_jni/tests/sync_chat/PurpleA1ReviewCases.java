package org.telegram.messenger.purple;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.telegram.tgnet.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;
public final class PurpleA1ReviewCases {
 static int checks,failures;
 static void expect(boolean good,String name){++checks;if(!good){++failures;System.out.println("FAIL "+name);}}
 static File payload(File root)throws Exception {
  PurpleAccountSyncCore.Result r=PurpleAccountSyncCore.buildConfigRecord(PurpleSyncChatServiceCases.bytes("version=2\n"),new byte[0][],PurpleAccountSyncCore.formatSpaceId(new byte[16]).id,PurpleAccountSyncCore.formatInstallId(new byte[16]).id,"synthetic-device","Android","synthetic",1,1700000000);
  expect(r.isValid()&&r.record!=null,"genuine owned config payload");File p=new File(root,"record.json");Files.write(p.toPath(),r.record);return p;
 }
 static void qAbsent(File payload) {
  PurpleSyncChatRecordCases.Client inventory=new PurpleSyncChatRecordCases.Client(payload);
  TLRPC.TL_message older=PurpleSyncChatRecordCases.document(7,false);older.message="";
  inventory.pages.add(messages(older));inventory.fresh.put(7,older);
  RecordPresence actual=PurpleSyncChatRecordCases.presence(inventory);
  expect(actual==RecordPresence.Present,"filename-only legacy record is present in full owned inventory");
  PurpleSyncChatRequestCases.Native nativeQ=new PurpleSyncChatRequestCases.Native();PurpleSyncChatTelegram q=new PurpleSyncChatTelegram(ORIGIN,nativeQ);SettingsHint[] hinted={null};q.hint(value->hinted[0]=value);nativeQ.reply(1,messages());q.cancel();
  expect(hinted[0]==SettingsHint.Absent,"production exact empty Q response control");
  PurpleSyncChatModelCases.World world=new PurpleSyncChatModelCases.World();world.hint=hinted[0];world.records.put(SELF,actual);
  PurpleSyncChatDiscovery model=PurpleSyncChatModelCases.model(null);model.start(1000);world.drain(model,1000);
  System.out.println("Q_EMPTY inventoryPresent="+(actual==RecordPresence.Present)+" phase="+model.status().phase+" complete="+model.status().complete+" inventories="+world.inventories+" command="+model.command().step);
  expect(model.status().phase!=PurpleSyncChatDiscovery.Phase.Unset,"empty caption Q must not establish absence before complete inventory");
 }
 static void unstableCounts(File payload) {
  for(int mode=0;mode<3;++mode) {
   PurpleSyncChatRecordCases.Client client=new PurpleSyncChatRecordCases.Client(payload);
   TLRPC.TL_message ordinary=mark(SELF,8,50);ordinary.message="ordinary";
   TLRPC.TL_messages_messagesSlice first=new TLRPC.TL_messages_messagesSlice();first.count=mode==2?1:2;first.messages.add(ordinary);client.pages.add(first);
   if(mode==1)client.pages.add(messages());else {TLRPC.TL_messages_messagesSlice last=new TLRPC.TL_messages_messagesSlice();last.count=1;client.pages.add(last);}
   PurpleSyncTransport.CheckResult full=PurpleSyncChatRecordCases.inventory(client);
   RecordPresence result=full.status==PurpleSyncTransport.CheckStatus.Finished?PurpleSyncChat.recordPresence(full.inventory,11):RecordPresence.Unknown;
   PurpleSyncChatDiscovery.Status status=PurpleSyncChatRecordCases.hintResult(result);
   System.out.println("HISTORY_COUNT mode="+mode+" scanned="+(full.inventory!=null&&full.inventory.scanComplete)+" presence="+result+" phase="+status.phase+" complete="+status.complete+" held="+status.postingHeld);
   expect(mode==2?result==RecordPresence.Empty:result==RecordPresence.Unknown,"history count continuity mode"+mode);
   expect(mode==2?status.phase==PurpleSyncChatDiscovery.Phase.Unset:!status.complete&&status.postingHeld,"incomplete history cannot authorize absence mode"+mode);
  }
 }
 static void exactIdentity() {
  Cache cached=new Cache(SELF,7,50);PurpleSyncChatClient.Info info=info(SELF);
  TLRPC.TL_message changed=mark(SELF,7,999);
  PurpleSyncChatClient.Exact dated=PurpleSyncChatTelegram.classify(ORIGIN,cached,messages(changed),null,info);
  System.out.println("R3_DATE expected=50 actual=999 state="+dated.state);
  expect(dated.state==DirectRead.Unknown,"R3 contradictory immutable date remains Unknown");
  PurpleSyncChatDiscovery before=PurpleSyncChatModelCases.model(PurpleSyncChatModelCases.cache(SELF,7,50));before.startBeforePost(1000);
  PurpleSyncChatDiscovery.Reply response=new PurpleSyncChatDiscovery.Reply(before.command());response.success=true;response.exact=dated;before.complete(response,1000);
  System.out.println("R3_DATE_MODEL complete="+before.status().complete+" held="+before.status().postingHeld+" cacheDate="+before.cache().location.markDate);
  expect(before.status().postingHeld&&before.cache().location.markDate==50,"contradictory exact date cannot refresh cache or release before-post gate");
  TLRPC.TL_messageEmpty wrong=new TLRPC.TL_messageEmpty();wrong.id=7;wrong.flags=1;wrong.peer_id=peer(USER);
  PurpleSyncChatClient.Exact empty=PurpleSyncChatTelegram.classify(ORIGIN,cached,messages(wrong),null,info);
  System.out.println("R3_EMPTY wrongPeer=true state="+empty.state);
  expect(empty.state==DirectRead.Unknown,"R3 wrong-peer messageEmpty remains Unknown");
  expect(PurpleSyncChatTelegram.classify(ORIGIN,cached,messages(mark(SELF,7,50)),null,info).state==DirectRead.Valid,"R3 exact valid control");
  TLRPC.TL_messageEmpty gone=new TLRPC.TL_messageEmpty();gone.id=7;
  expect(PurpleSyncChatTelegram.classify(ORIGIN,cached,messages(gone),null,info).state==DirectRead.MarkGone,"R3 true missing control");
 }
 static void nativeSender() {
  TLRPC.TL_message raw=message(GROUP);raw.out=true;
  SerializedData out=new SerializedData();raw.serializeToStream(out);SerializedData input=new SerializedData(out.toByteArray());
  TLRPC.Message nativeMessage=TLRPC.Message.TLdeserialize(input,input.readInt32(true),true);
  expect((nativeMessage.flags&(1<<8))==0&&nativeMessage.from_id!=null,"native TL retains missing-from flag and fills peer fallback");
  Ownership result=own(input(GROUP),nativeMessage);
  System.out.println("GROUP_MISSING_FROM ownership="+result);
  expect(result==Ownership.Unknown,"synthesized dialog peer is not affirmative other-author evidence");
  nativeMessage.id=9;nativeMessage.date=70;
  PurpleSyncChatModelCases.World world=new PurpleSyncChatModelCases.World();world.add(mark(SELF,7,50));world.add(nativeMessage);
  PurpleSyncChatDiscovery model=PurpleSyncChatModelCases.model(PurpleSyncChatModelCases.cache(SELF,7,50));model.start(1000);world.drain(model,1000);
  System.out.println("GROUP_UNKNOWN_MODEL complete="+model.status().complete+" held="+model.status().postingHeld+" phase="+model.status().phase);
  expect(!model.status().complete&&model.status().postingHeld,"missing group sender must hold global resolution rather than silently disappear");
  expect(own(input(SELF),message(SELF))==Ownership.Own,"Self out=false absent sender remains Own");
 }
 public static void main(String[] args)throws Exception {
  PurpleCore.ensureLoaded();File root=new File(args[0]);root.mkdirs();File p=payload(root);
  qAbsent(p);unstableCounts(p);exactIdentity();nativeSender();
  System.out.println("INDEPENDENT checks="+checks+" failures="+failures);
  if(failures!=0)System.exit(1);
 }
}
