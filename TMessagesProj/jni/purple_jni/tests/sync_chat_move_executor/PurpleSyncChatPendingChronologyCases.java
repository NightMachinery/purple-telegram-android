package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatMoveIOCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatMoveExecutorCases.Frame;

public final class PurpleSyncChatPendingChronologyCases {
    static int checks;
    static void must(boolean value,String message){++checks;if(!value)throw new AssertionError(message);}
    static void stalePendingAbsence(boolean restart,boolean freshControl)throws Exception {
        Frame first=new Frame(false,true);first.stop="Pending";first.dropKind="pending";first.start();
        must(first.deferredCommand.action.equals("Pending"),"initial Pending requested");
        PurpleSyncInventory oldAbsence=first.inventory(true);
        byte[] staged=Files.readAllBytes(new File(first.files.root,"pending/config.json").toPath());
        must(first.oldBodies.values().stream().noneMatch(v->Arrays.equals(v,staged)),"old scan predates pending send");
        PurpleSyncChatMove.Reply initial=first.reply();initial.source=oldAbsence;initial.sendIntent="NotIssued";
        Consumer<PurpleSyncChatMove.Reply> initialDone=first.deferred;first.stop="";initialDone.accept(initial);first.drain();
        must(first.effects==1&&first.deferredCommand.permit.kind.equals("pending"),"exact pending effect dispatched before lost ack");
        must(first.oldBodies.values().stream().anyMatch(v->Arrays.equals(v,staged)),"record really exists in O");
        Frame next;
        if(restart){first.close();next=new Frame(first);next.access="Protected";next.stop="Pending";next.resume();}
        else {next=first;next.access="Protected";next.stop="Pending";PurpleSyncChatMove.Reply unknown=next.reply();unknown.response=null;next.deferred.accept(unknown);next.drain();}
        must(next.deferredCommand.action.equals("Pending"),"new Pending reconciliation requested");
        PurpleSyncChatMove.Reply reply=next.reply();reply.source=freshControl?next.inventory(true):oldAbsence;reply.sendIntent="NotIssued";
        Consumer<PurpleSyncChatMove.Reply> done=next.deferred;next.stop="Source";done.accept(reply);next.drain();
        JSONObject journal=next.diskJournal();
        if(freshControl){
            must(journal.getInt("phase")>=3&&journal.getString("deferred_record").isEmpty(),"fresh post-outcome O scan reaches Plan without deferred record");
            must(next.effects==(restart?0:1),"fresh reconciliation sends no duplicate pending record");
        }else{
            must(journal.getInt("phase")==0&&journal.getString("deferred_record").isEmpty(),"stale O absence remains at ResolvePending, restart="+restart);
            must(next.reason().equals("StaleRead"),"stale scan identified, restart="+restart);
            must(Arrays.equals(staged,Files.readAllBytes(new File(next.files.root,"pending/config.json").toPath())),"stale scan cannot retire exact stage");
            JSONObject wrapper=json(Files.readAllBytes(new File(next.files.root,"carry.json").toPath()));
            must(wrapper.getJSONObject("send_identities").has("pending-issued:"+hash(staged)),"durable Issued floor survives stale proof");
        }
        next.close();
    }
    static void run()throws Exception{
        stalePendingAbsence(false,false);stalePendingAbsence(true,false);stalePendingAbsence(false,true);stalePendingAbsence(true,true);
        System.out.println("PENDING_CHRONOLOGY_CHECKS="+checks);
    }
    public static void main(String[] args)throws Exception{
        PurpleCore.ensureLoaded();rows=new ArrayList<>();for(String line:Files.readAllLines(new File(args[0]).toPath()))rows.add(new JSONObject(line));
        for(JSONObject r:rows)if(r.getString("op").equals("begin")&&r.getJSONObject("expected").getString("status").equals("Valid")){beginning=r.getJSONObject("input");break;}
        base=unblob(rows.get(0).getJSONObject("input").getString("stagedRecord"));PurpleSyncChatMoveStoreCases.initial=unblob(beginning.getString("state"));PurpleSyncChatMoveStoreCases.baseRecord=base;
        File parent=new File(args[1]);PurpleModernSyncCases.directory(parent);PurpleModernSyncCases.Frame setup=new PurpleModernSyncCases.Frame(parent,"routing",true);root=setup.root;run();
    }
}
