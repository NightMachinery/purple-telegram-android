package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleModernSyncCases.*;
import java.io.*;
import java.nio.file.*;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

public final class PurpleSyncAutoOutboxCases {
    static MessageObject row(File path, int id, boolean unsent) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id=id; message.out=true; message.dialog_id=202; message.random_id=123;
        message.send_state=id>0?3:unsent?1:2; message.attachPath=path.getAbsolutePath();
        message.message=PurpleSyncPost.CAPTION;
        message.media=new TLRPC.TL_messageMediaDocument(); message.media.document=new TLRPC.TL_document();
        TLRPC.TL_documentAttributeFilename name=new TLRPC.TL_documentAttributeFilename();
        name.file_name=PurpleSyncPost.RECORD_FILE_NAME; message.media.document.attributes.add(name);
        MessageObject object=new MessageObject(); object.currentAccount=1; object.messageOwner=message; return object;
    }
    public static int run(File root) throws Exception {
        int before=checks;
        for (boolean edit:new boolean[]{false,true}) for(String change:new String[]{"disable","routing","new-server-body"}) {
            PurpleSyncAutoCases.Port p=PurpleSyncAutoCases.bound(root,"outbox-"+edit+"-"+change);
            if(edit) PurpleSyncAutoCases.initialPublish(p); else p.enable();
            p.stopOn=edit?PurpleSyncAutoCore.Action.EditInPlace:PurpleSyncAutoCore.Action.Post;
            p.saved(edit?REMOTE:OWN);
            check(p.disk().getBoolean("active"),"pending core checkpoint survived before old upstream effect");
            byte[] staged=PurpleSyncAutoCore.decode(p.disk().getString("record"));
            check(PurpleSyncPost.validRecord(staged),"actual canonical reserved record");
            File file=new File(p.client.filesDirectory(),PurpleSyncPost.STAGING_DIRECTORY+"/persisted/Purple settings sync.json");
            write(file,staged);
            File pathRecord=new File(p.frame.app,"old-outbox-path"); Files.write(pathRecord.toPath(),file.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            p.stopOn=null; p.restart();
            if(change.equals("disable")) p.engine.event(PurpleSyncAutoCore.Event.Disable);
            if(change.equals("routing")) p.frame.local("follow");
            if(change.equals("new-server-body")) {
                p.client.remote(REMOTE);
                if(edit) { int latest=p.client.next; p.client.messages.put(501,p.client.messages.remove(latest)); }
            }
            int posts=p.posts, edits=p.edits;
            File reloaded=new File(new String(Files.readAllBytes(pathRecord.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            for(boolean unsent:new boolean[]{true,false}) {
                MessageObject message=row(reloaded,edit?501:-17,unsent);
                check(!PurpleSync.registerRetry(1,message,unsent),"restart/user retry diverts "+edit+"/"+change+"/"+unsent);
                message.messageOwner.attachPath=new File(p.frame.app,"moved-cache.json").getAbsolutePath();
                check(PurpleSyncAutoOutbox.divert(1,message),"durable reserved filename/caption survives moved stage");
            }
            if(change.equals("disable")) check(!p.enabled&&!p.disk().getBoolean("enabled")&&!p.disk().getBoolean("active"),"disabled inactive core record refuses retry");
            p.engine.event(PurpleSyncAutoCore.Event.Foreground);
            check(p.posts==posts&&p.edits==edits,"fresh core reconciliation/hold never replays old outbox "+edit+"/"+change);
            p.frame.unchangedA(); p.engine.close();
        }
        PurpleSyncAutoCases.Port inactive=PurpleSyncAutoCases.bound(root,"outbox-enabled-inactive");
        PurpleSyncAutoCases.initialPublish(inactive);
        File file=new File(inactive.client.filesDirectory(),PurpleSyncPost.STAGING_DIRECTORY+"/inactive/Purple settings sync.json");
        write(file,inactive.client.messages.values().iterator().next());
        check(inactive.disk().getBoolean("enabled")&&!inactive.disk().getBoolean("active"),"independent active=false fixture");
        check(!PurpleSync.registerRetry(1,row(file,-17,true),true),"enabled but inactive canonical modern stage refuses retry");
        MessageObject ordinary=row(new File(root,"normal.json"),-18,false);
        ordinary.messageOwner.message="ordinary";
        ordinary.messageOwner.media.document.attributes.get(0).file_name="normal.json";
        check(PurpleSync.registerRetry(1,ordinary,false),"ordinary upstream user retry remains admitted");
        inactive.frame.unchangedA(); inactive.engine.close();
        return checks-before;
    }
}
