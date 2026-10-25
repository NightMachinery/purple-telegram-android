package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class PurpleArchiveDiscard {
    static String seal(PurpleArchivePaths paths) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(paths.metadata.identity.getBytes(StandardCharsets.UTF_8));
        for (String name : new String[]{"binding.json", "creating.json", "fence.json", "removing.json", "recovery.json", "cleared.json", "login.json"}) {
            paths.metadata.check();
            PurpleArchiveFiles.Mark mark = PurpleArchiveFiles.markAny(paths.metadata.file(name));
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            if (mark != null) digest.update(mark.stamp.getBytes(StandardCharsets.UTF_8));
        }
        PurpleArchiveFiles.Mark account = PurpleArchiveFiles.mark(paths.accountsRoot.file(paths.account), true);
        if (account != null) digest.update(account.stamp.getBytes(StandardCharsets.UTF_8));
        paths.metadata.check();
        return PurpleArchiveCore.base64(digest.digest());
    }
    private PurpleArchiveDiscard() {}
}
