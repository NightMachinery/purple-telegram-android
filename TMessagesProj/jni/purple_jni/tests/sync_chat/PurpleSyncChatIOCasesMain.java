package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;

public final class PurpleSyncChatIOCasesMain {
    public static void main(String[] args)throws Exception {
        PurpleSyncChatCases.main(args);
        for(String name:new String[]{"PurpleSyncChatCore","PurpleSyncChatIO","PurpleSyncChatRequests","PurpleSyncChatUpload","PurpleSyncChatPostEffect","PurpleSyncReader","PurpleSyncRecordCore","PurpleSyncRecordSnapshot"})
            check(new File(Class.forName("org.telegram.messenger.purple."+name).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new File(args[0]).getCanonicalFile()),"production overlay origin "+name);
        File root=Files.createTempDirectory(Paths.get(args[2]),"cases-").toFile();
        PurpleRpcRetryCases.run();
        PurpleSyncChatIOCases.run(root);
        PurpleSyncChatUploadCases.run();
        PurpleSyncChatPostEffectCases.run();
        PurpleSyncRecordCases.run();
        System.out.println("SYNC_CHAT_IO_CHECKS="+checks);
    }
}
