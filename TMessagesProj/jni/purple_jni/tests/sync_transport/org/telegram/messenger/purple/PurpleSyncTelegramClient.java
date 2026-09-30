package org.telegram.messenger.purple;

final class PurpleSyncTelegramClient {
    private PurpleSyncTelegramClient() {
    }

    static PurpleSyncClient create(int account, long userId) {
        throw new UnsupportedOperationException("no Telegram on this host");
    }
}
