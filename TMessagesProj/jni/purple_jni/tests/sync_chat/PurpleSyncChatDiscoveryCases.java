package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;

public final class PurpleSyncChatDiscoveryCases {
    public static void main(String[] args)throws Exception {
        PurpleSyncChatCases.main(args);
        for(String name:new String[]{"PurpleSyncChatRequests","PurpleSyncChatTelegram","PurpleSyncChatCache","PurpleSyncChatDiscovery","PurpleSyncChatService","PurpleSyncChatRecordClient","PurpleSyncOperations","PurpleSyncRunner","PurpleSyncTelegramClient","PurpleSyncChat"})
            check(new File(Class.forName("org.telegram.messenger.purple."+name).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new File(args[0]).getCanonicalFile()),"production overlay origin "+name);
        File root=Files.createTempDirectory(Paths.get(args[2]),"cases-").toFile();
        PurpleSyncChatRequestCases.run();System.out.println("REQUEST_CLIENT_CHECKS="+checks);
        PurpleSyncChatModelCases.run();System.out.println("MODEL_CHECKS="+checks);
        PurpleSyncChatServiceCases.run(root);System.out.println("CACHE_SERVICE_CHECKS="+checks);
        PurpleA1ReviewCases.main(new String[]{new File(root,"review-control").getPath()});
        PurpleSyncChatRecordCases.run(root);System.out.println("SYNC_CHAT_TOTAL_CHECKS="+checks);
    }
}
