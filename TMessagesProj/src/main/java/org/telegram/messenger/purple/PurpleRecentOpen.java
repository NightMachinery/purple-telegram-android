package org.telegram.messenger.purple;

final class PurpleRecentOpen {
    volatile int account = -1;
    volatile long dialogId;
    volatile boolean eligible;

    boolean matches(int account, long dialogId) {
        return this.account == account && this.dialogId == dialogId && dialogId != 0;
    }

    void set(int account, long dialogId, boolean eligible) {
        this.account = account;
        this.dialogId = dialogId;
        this.eligible = eligible;
    }

    boolean close(int account, long dialogId) {
        if (!matches(account, dialogId)) return false;
        boolean grantGrace = eligible;
        this.account = -1;
        this.dialogId = 0;
        eligible = false;
        return grantGrace;
    }

    void suppress(int account, long dialogId) {
        if (matches(account, dialogId)) eligible = false;
    }
}
