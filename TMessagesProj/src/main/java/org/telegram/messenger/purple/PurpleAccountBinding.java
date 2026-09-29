/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 (GPL v2),
 * or (at your option) any later version.
 */
package org.telegram.messenger.purple;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.security.SecureRandom;

final class PurpleAccountBinding {
    private static final String KEY_PREFIX = "purple_sync_binding_";
    private static final Object WRITE_LOCK = new Object();

    interface AccountAccess {
        boolean isActivated(int account);
        long userId(int account);
        String read(int account, String key);
        boolean commit(int account, String key, String value);
    }

    static final class Binding {
        final long userId;
        final String token;
        final String error;

        private Binding(long userId, String token, String error) {
            this.userId = userId;
            this.token = token;
            this.error = error;
        }

        boolean isValid() {
            return error == null;
        }
    }

    static final class Initialization {
        final long userId;
        final byte[] entropy;
        final String error;

        private Initialization(long userId, byte[] entropy, String error) {
            this.userId = userId;
            this.entropy = entropy;
            this.error = error;
        }

        boolean isValid() {
            return error == null;
        }
    }

    private static final AccountAccess PRODUCTION_ACCESS = new AccountAccess() {
        @Override
        public boolean isActivated(int account) {
            return UserConfig.getInstance(account).isClientActivated();
        }

        @Override
        public long userId(int account) {
            return UserConfig.getInstance(account).getClientUserId();
        }

        @Override
        public String read(int account, String key) {
            return MessagesController.getMainSettings(account)
                    .getString(key, null);
        }

        @Override
        public boolean commit(int account, String key, String value) {
            final SharedPreferences preferences =
                    MessagesController.getMainSettings(account);
            return preferences.edit().putString(key, value).commit();
        }
    };

    private PurpleAccountBinding() {
    }

    static Binding read(int account) {
        if (!isAccountIndex(account)) {
            return failure("AccountUnavailable");
        }
        return read(account, PRODUCTION_ACCESS);
    }

    static Initialization prepareInitialization(int account,
            SecureRandom random) {
        if (!isAccountIndex(account)) {
            return initializationFailure("AccountUnavailable");
        }
        return prepareInitialization(account, PRODUCTION_ACCESS, random);
    }

    static Initialization prepareInitialization(int account,
            AccountAccess access, SecureRandom random) {
        final long userId = activeUserId(account, access);
        if (userId <= 0) {
            return initializationFailure("AccountUnavailable");
        }
        final String existing = access.read(account, key(userId));
        if (!sameActiveUser(account, userId, access)) {
            return initializationFailure("AccountChanged");
        }
        if (existing != null && !isToken(existing)) {
            return initializationFailure("InvalidBindingToken");
        }
        final byte[] entropy = new byte[16];
        if (existing == null) {
            random.nextBytes(entropy);
        } else {
            for (int i = 0; i != entropy.length; ++i) {
                entropy[i] = (byte) ((Character.digit(
                        existing.charAt(2 * i), 16) << 4)
                        | Character.digit(existing.charAt(2 * i + 1), 16));
            }
        }
        return new Initialization(userId, entropy, null);
    }

    static Binding read(int account, AccountAccess access) {
        final long userId = activeUserId(account, access);
        if (userId <= 0) {
            return failure("AccountUnavailable");
        }
        final String token = access.read(account, key(userId));
        if (!isToken(token)) {
            return failure("BindingUnavailable");
        }
        if (!sameActiveUser(account, userId, access)) {
            return failure("AccountChanged");
        }
        return new Binding(userId, token, null);
    }

    static Binding readForCheck(int account) {
        if (!isAccountIndex(account)) {
            return failure("AccountUnavailable");
        }
        return readForCheck(account, PRODUCTION_ACCESS);
    }

    static Binding readForCheck(int account, AccountAccess access) {
        final long userId = activeUserId(account, access);
        if (userId <= 0) {
            return failure("AccountUnavailable");
        }
        final String token = access.read(account, key(userId));
        if (!sameActiveUser(account, userId, access)) {
            return failure("AccountChanged");
        }
        return new Binding(userId, token != null ? token : "", null);
    }

    static Binding persist(int account, long expectedUserId, String token) {
        if (!isAccountIndex(account)) {
            return failure("AccountUnavailable");
        }
        return persist(account, expectedUserId, token, PRODUCTION_ACCESS);
    }

    static Binding persist(int account, long expectedUserId, String token,
            AccountAccess access) {
        if (expectedUserId <= 0 || !isToken(token)) {
            return failure("InvalidBindingToken");
        }
        synchronized (WRITE_LOCK) {
            if (!sameActiveUser(account, expectedUserId, access)) {
                return failure("AccountChanged");
            }
            final String key = key(expectedUserId);
            final String existing = access.read(account, key);
            if (existing != null && !existing.equals(token)) {
                return failure("BindingConflict");
            }
            if (existing == null && !access.commit(account, key, token)) {
                return failure("BindingWriteFailed");
            }
            if (!sameActiveUser(account, expectedUserId, access)) {
                return failure("AccountChanged");
            }
            final String stored = access.read(account, key);
            if (!token.equals(stored)) {
                return failure("BindingWriteFailed");
            }
            return new Binding(expectedUserId, token, null);
        }
    }

    static long activeUserId(int account) {
        if (!isAccountIndex(account)) {
            return 0;
        }
        return activeUserId(account, PRODUCTION_ACCESS);
    }

    static boolean isSameActiveUser(int account, long userId) {
        return isAccountIndex(account)
                && sameActiveUser(account, userId, PRODUCTION_ACCESS);
    }

    static boolean isToken(String token) {
        if (token == null || token.length() != 32) {
            return false;
        }
        for (int i = 0; i != token.length(); ++i) {
            final char value = token.charAt(i);
            if ((value < '0' || value > '9')
                    && (value < 'a' || value > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static long activeUserId(int account, AccountAccess access) {
        if (!access.isActivated(account)) {
            return 0;
        }
        final long userId = access.userId(account);
        return userId > 0 ? userId : 0;
    }

    private static boolean sameActiveUser(int account, long userId,
            AccountAccess access) {
        return userId > 0
                && access.isActivated(account)
                && access.userId(account) == userId;
    }

    private static String key(long userId) {
        return KEY_PREFIX + userId;
    }

    private static boolean isAccountIndex(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
    }

    private static Binding failure(String error) {
        return new Binding(0, null, error);
    }

    private static Initialization initializationFailure(String error) {
        return new Initialization(0, null, error);
    }
}
