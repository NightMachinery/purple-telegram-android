package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;

public final class PurpleSyncChatA3IntegrationCases {
    public static void main(String[] args)throws Exception {
        File overlay=new File(args[0]).getCanonicalFile();
        for(String name:new String[]{"PurpleSyncRunner","PurpleSyncChat","PurpleSyncChatService","PurpleSyncChatSetup","PurpleSyncChatSetupRequests","PurpleSyncChatSetupNative"})
            if(!new File(Class.forName("org.telegram.messenger.purple."+name).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(overlay))throw new AssertionError("A3 production source origin "+name);
        File root=Files.createTempDirectory(new File(args[1]).toPath(),"a3-affected-").toFile();
        int before=PurpleModernSyncCases.checks;
        PurpleSyncChatIntegrationCases.ledger(root);
        System.out.println("TYPED_LEDGER_CHECKS="+(PurpleModernSyncCases.checks-before));
        System.out.println("RUNNER_GATE_CHECKS="+PurpleSyncChatIntegrationCases.run(root));
        System.out.println("TYPED_UPLOAD_LIFECYCLE_CHECKS="+PurpleSyncChatUploadIntegrationCases.run(root));
        PurpleAccountSyncStoreTest.main(new String[0]);
        PurpleSyncCoreTest.main(new String[0]);
    }
}
