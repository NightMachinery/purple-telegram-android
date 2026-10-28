package org.telegram.messenger.purple;

import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.json.JSONObject;

public final class PurpleSyncConfirmedStageCases {
    private static int checks;
    private static void check(boolean value,String reason) { ++checks; if(!value)throw new AssertionError(reason); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static JSONObject json(byte[] value) throws Exception { return new JSONObject(new String(value,StandardCharsets.UTF_8)); }
    private static byte[] canonical(JSONObject value) { return PurpleAccountSyncCore.inspectState(bytes(value.toString())).state; }
    private static void startup(File parent,String name,int mode) throws Exception {
        PurpleModernSyncCases.Frame legacy=new PurpleModernSyncCases.Frame(parent,name,true);legacy.initialize(1);
        byte[] old=legacy.state(1);
        PurpleAccountSyncCore.Result built=PurpleAccountSyncCore.buildConfigRecord(bytes("version=1\n# exact legacy confirmed stage\n"),new byte[0][],
                legacy.space(1),PurpleModernSyncCases.install(1),PurpleModernSyncCases.device(1),"Android","legacy recovery",1,1700000001L);
        check(built.isValid()&&built.record!=null,"genuine old stage");
        PurpleAccountSyncStore.Reply reserved=PurpleAccountSyncStore.NATIVE.reserve(1,old,built.record);
        check(reserved.valid(),"genuine reservation");
        PurpleAccountSyncStore.Reply confirmed=PurpleAccountSyncStore.NATIVE.confirm(reserved.state,built.record,PurpleModernSyncCases.device(1),built.record,99);
        check(confirmed.valid()&&confirmed.changed,"raw pre-retention confirmation");
        check("Confirmed".equals(PurpleAccountSyncStore.NATIVE.checkStage(confirmed.state,built.record).verdict),"confirmed startup verdict");
        check("MissingLocalVersion".equals(PurpleSyncRetainedBases.request("base",confirmed.state,null).status),"old state has no embedded base");
        byte[] state=confirmed.state;
        if(mode==1||mode==2||mode==3||mode==6) {
            PurpleSyncRetainedBases.Result retained=PurpleSyncRetainedBases.request("retain",state,null,built.record);
            check(retained.ready(),"retained control prepared");state=retained.state;
            if(mode==2||mode==3) {
                JSONObject mutated=json(state);JSONObject metadata=mutated.getJSONObject("retained_config_bases");
                if(mode==2)metadata.put("version",2);
                else metadata.getJSONArray("records").getJSONObject(0).put("sha256",new String(new char[64]).replace('\0','0'));
                state=canonical(mutated);
            }
        }
        byte[] stageBytes=built.record;
        if(mode==5||mode==6) {
            PurpleAccountSyncCore.Result next=PurpleAccountSyncCore.buildConfigRecord(bytes("version=1\n# unreserved next stage\n"),new byte[0][],
                    legacy.space(1),PurpleModernSyncCases.install(1),PurpleModernSyncCases.device(1),"Android","unreserved recovery",2,1700000002L);
            check(next.isValid(),"genuine unreserved next stage");stageBytes=next.record;
            check(!PurpleAccountSyncStore.NATIVE.checkStage(state,stageBytes).valid()&&PurpleAccountSyncStore.NATIVE.reserve(1,state,stageBytes).valid(),"startup unreserved deletion branch");
        }
        File stage=new File(legacy.sync(1),"pending/config.json"),stateFile=new File(legacy.sync(1),"state.json");
        PurpleModernSyncCases.write(stateFile,state);PurpleModernSyncCases.write(stage,stageBytes);
        boolean main=mode==4;if(main)Looper.mainThread=Thread.currentThread();
        try(PurpleAccountSyncStore store=legacy.env(1).store()) {
            PurpleAccountSyncStore.Result result=store.open(true,1,PurpleModernSyncCases.device(1));
            if(mode==2||mode==3||main||mode==5) {
                check(result.status==(mode==2?PurpleAccountSyncStore.Status.NewerRetention:mode==5?PurpleAccountSyncStore.Status.MissingLocalVersion:PurpleAccountSyncStore.Status.InvalidRetention),"retention refusal "+mode+": "+result.status);
                check(stage.isFile()&&Arrays.equals(Files.readAllBytes(stage.toPath()),stageBytes),"refusal preserves only exact stage "+mode);
                check(Arrays.equals(Files.readAllBytes(stateFile.toPath()),state),"refusal preserves original state "+mode);
            } else {
                check(result.status==PurpleAccountSyncStore.Status.Ready,"legacy ordinary startup Ready");
                PurpleSyncRetainedBases.Result found=PurpleSyncRetainedBases.request("find",store.stateBytes(),null,PurpleSyncRetainedFiles.records(legacy.sync(1)));
                System.out.println("CONFIRMED_STARTUP mode="+mode+" status="+result.status+" stageExists="+stage.exists()+" base="+found.status);
                check(found.ready()&&Arrays.equals(found.record,built.record),"startup must retain exact confirmed base before deleting the only stage copy");
                check(!stage.exists(),"confirmed stage retired after durable retention");
            }
        } finally { if(main)Looper.mainThread=null; }
        if(main)try(PurpleAccountSyncStore store=legacy.env(1).store()) {
            check(store.open(true,1,PurpleModernSyncCases.device(1)).status==PurpleAccountSyncStore.Status.Ready,"worker recovers preserved main-thread stage");
            check(PurpleSyncRetainedBases.request("find",store.stateBytes(),null,PurpleSyncRetainedFiles.records(legacy.sync(1))).ready(),"worker retained exact stage");
        }
    }
    public static void main(String[] args) throws Exception {
        File root=new File(PurpleAccountFiles.root(),"confirmed-startup-regressions");PurpleModernSyncCases.directory(root);
        for(int mode=0;mode<7;++mode)startup(root,"case-"+mode,mode);
        System.out.println(checks+" confirmed-stage startup checks");
    }
}
