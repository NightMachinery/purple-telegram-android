package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;

final class PurpleSyncRecordCases {
    static PurpleSyncRecordCore.Result evaluate(JSONObject value) {
        return PurpleSyncRecordCore.request("message", value.toString().getBytes(StandardCharsets.UTF_8));
    }
    static JSONObject message(String chat, String family) {
        return PurpleSyncChatCore.json("account", "0-11", "chat", chat, "id", "7", "date", "50",
                "ownership", "Own", "family", family, "bytes", PurpleSyncRetainedBases.encode(new byte[]{0,1,-1}));
    }
    static void run() throws Exception {
        int before=checks;
        String hash=PurpleSyncRetainedFiles.hash(new byte[]{0,1,-1});
        check(hash.equals("26a66b061e8f48f39927c312f25293959729eee95978e2892d49d3512a5cc092"),"retained hash unchanged");
        for(String chat:new String[]{"self","user:22","chat:33","channel:44"})for(String family:new String[]{"Settings","Playlists"}) {
            PurpleSyncRecordCore.Result result=evaluate(message(chat,family));
            check(result.status.equals("Ready"),"scoped record receipt accepted");
            JSONObject reference=result.fields.getJSONObject("reference");
            check(reference.getString("hash").equals(hash)&&reference.getString("chat").equals(chat),"exact receipt scope/hash");
        }
        String[][] bad={{"account","0-0"},{"account","00-11"},{"account","2-11"},{"chat","user:11"},{"chat","channel:0"},
                {"chat","channel:044"},{"id","0"},{"id","-1"},{"id","01"},{"id","2147483648"},{"date","0"},
                {"date","-1"},{"date","01"},{"date","9007199254740992"},{"family","Unknown"},{"ownership","Forwarded"},{"bytes","a="}};
        for(String[] edit:bad) {
            JSONObject value=message("self","Settings");value.put(edit[0],edit[1]);
            check(evaluate(value).status.equals("Invalid"),"invalid record receipt "+edit[0]);
        }
        JSONObject oversized=message("self","Settings");oversized.put("bytes",PurpleSyncRetainedBases.encode(new byte[4*1024*1024]));
        check(evaluate(oversized).status.equals("Invalid"),"original bridge input cap retained");
        check(PurpleSyncRecordCore.request("hash", "{\"bytes\":\"\"}".getBytes(StandardCharsets.UTF_8)).fields.getString("hash")
                .equals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),"empty byte hash unchanged");
        System.out.println("SYNC_RECORD_CHECKS="+(checks-before));
    }
    private PurpleSyncRecordCases() { }
}
