package org.telegram.messenger.purple;

public final class PurpleRecordingSendIdentity {
    public final int account;
    public final long user, dialog, peer;
    public PurpleRecordingSendIdentity(int account, long user, long dialog, long peer) {
        this.account = account; this.user = user; this.dialog = dialog; this.peer = peer;
    }
    public boolean matches(int account, long user, long dialog) {
        return this.account == account && this.user == user && this.dialog == dialog;
    }
}
