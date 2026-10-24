package org.telegram.messenger.purple;

import java.io.File;

final class PurpleArchivePaths {
    final String account;
    final PurpleArchiveFiles.Directory root, metadataRoot, accountsRoot, metadata;
    PurpleArchivePaths(File applicationFiles, String account) throws Exception {
        if (!PurpleSyncRecoveryFiles.key(account)) throw new IllegalArgumentException("archive account key");
        this.account = account;
        PurpleArchiveFiles.Directory application = new PurpleArchiveFiles.Directory(applicationFiles.getCanonicalFile(), false);
        root = application.child("purple", false);
        metadataRoot = root.child("content-archive-state", true);
        accountsRoot = root.child("content-archive", true);
        metadata = metadataRoot.child(account, true);
    }
    PurpleArchiveFiles.Directory accountDirectory() { return accountsRoot.child(account, true); }
    PurpleArchiveFiles.Directory store(String id, boolean create) {
        if (!PurpleArchiveKeys.token(id)) throw new IllegalArgumentException("archive store identity");
        PurpleArchiveFiles.Directory account = create ? accountDirectory() : new PurpleArchiveFiles.Directory(accountsRoot.file(this.account), true);
        return create ? account.child(id, true) : new PurpleArchiveFiles.Directory(account.file(id), true);
    }
    void check() { root.check(); metadataRoot.check(); accountsRoot.check(); metadata.check(); }
}
