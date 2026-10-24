package org.telegram.messenger.purple;

import android.content.SharedPreferences;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import java.security.SecureRandom;
import java.util.Objects;

final class PurpleAccountBinding {
    static final String KEY_PREFIX = "purple_sync_binding_";
    static final Object WRITE_LOCK = new Object();

    interface AccountAccess {
        boolean isActivated(int account);
        long userId(int account);
        String read(int account, String key);
        boolean commit(int account, String key, String value);
        default String identity(int account) { return "0-" + userId(account); }
        default long lifetime(int account) { return 0; }
    }
    static final class Capture {
        final int account;
        final AccountAccess access;
        final long userId, lifetime;
        final String identity, value, legacy;
        Capture(int account, AccountAccess access) {
            this.account = account; this.access = access;
            userId = activeUserId(account, access); identity = access.identity(account); lifetime = access.lifetime(account);
            value = access.read(account, KEY_PREFIX + userId);
            legacy = null;
        }
        boolean sameIdentity() {
            return userId > 0 && identity != null && (identity.equals("0-" + userId) || identity.equals("1-" + userId)) && sameActiveUser(account, userId, access)
                    && Objects.equals(identity, access.identity(account)) && lifetime == access.lifetime(account);
        }
        boolean current() {
            return sameIdentity() && Objects.equals(value, access.read(account, KEY_PREFIX + userId));
        }
    }
    static final class Binding {
        final long userId;
        final String token, error;
        final Capture captured;
        Binding(Capture captured, String token, String error) {
            this.captured = captured; userId = captured != null ? captured.userId : 0;
            this.token = token; this.error = error;
        }
        boolean isValid() { return error == null; }
        boolean current() { return isValid() && captured != null && captured.current(); }
    }
    static final class Initialization {
        final long userId;
        final byte[] entropy;
        final String error;
        final Capture captured;
        Initialization(Capture captured, byte[] entropy, String error) {
            this.captured = captured; userId = captured != null ? captured.userId : 0;
            this.entropy = entropy; this.error = error;
        }
        boolean isValid() { return error == null; }
    }
    private static final AccountAccess PRODUCTION_ACCESS = new AccountAccess() {
        public boolean isActivated(int account) { return UserConfig.getInstance(account).isClientActivated(); }
        public long userId(int account) { return UserConfig.getInstance(account).getClientUserId(); }
        public String identity(int account) {
            PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(account);
            return id != null ? id.key : null;
        }
        public long lifetime(int account) {
            PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(account);
            return id != null ? id.lifetime : -1;
        }
        public String read(int account, String key) {
            try { return MessagesController.getMainSettings(account).getString(key, null); }
            catch(RuntimeException e) { return "\u0000"; }
        }
        public boolean commit(int account, String key, String value) {
            SharedPreferences preferences = MessagesController.getMainSettings(account);
            return preferences.edit().putString(key, value).commit();
        }
    };
    static Binding startupCandidate(int account) {
        if (!isAccountIndex(account)) return failure("AccountUnavailable");
        Capture captured = new Capture(account, PRODUCTION_ACCESS);
        String token = captured.value != null ? captured.value : captured.legacy;
        return captured.current() && isToken(token) ? new Binding(captured, token, null) : failure("BindingUnavailable");
    }
    static Binding read(int account) { return isAccountIndex(account) ? read(account, PRODUCTION_ACCESS) : failure("AccountUnavailable"); }
    static Binding read(int account, AccountAccess access) {
        Binding value = readForCheck(account, access);
        return !value.isValid() || isToken(value.token) ? value : failure("BindingUnavailable");
    }
    static Binding readForCheck(int account) { return isAccountIndex(account) ? readForCheck(account, PRODUCTION_ACCESS) : failure("AccountUnavailable"); }
    static Binding readForCheck(int account, AccountAccess access) {
        Capture captured = new Capture(account, access);
        if (!captured.sameIdentity()) return failure("AccountUnavailable");
        if (!captured.current()) return failure("AccountChanged");
        return new Binding(captured, captured.value != null ? captured.value : "", null);
    }
    static Initialization prepareInitialization(int account, SecureRandom random) {
        return isAccountIndex(account) ? prepareInitialization(account, PRODUCTION_ACCESS, random) : new Initialization(null, null, "AccountUnavailable");
    }
    static Initialization prepareInitialization(int account, AccountAccess access, SecureRandom random) {
        Capture captured = new Capture(account, access);
        if (!captured.current()) return new Initialization(null, null, "AccountChanged");
        byte[] entropy = null;
        if (captured.value == null) { entropy = new byte[16]; random.nextBytes(entropy); }
        PurpleAccountSyncCore.Result plan = PurpleAccountSyncCore.planSetupBindingToken(captured.value, entropy);
        if (!plan.isValid() || !isToken(plan.bindingToken) || !captured.current()) return new Initialization(null, null, "InvalidBindingToken");
        byte[] token = new byte[16];
        for (int i = 0; i < token.length; ++i) token[i] = (byte) ((Character.digit(plan.bindingToken.charAt(2*i),16)<<4) | Character.digit(plan.bindingToken.charAt(2*i+1),16));
        return new Initialization(captured, token, null);
    }
    static Binding persist(int account, Initialization prepared, String token) {
        return prepared != null && prepared.captured != null && prepared.captured.account == account
                ? persist(prepared.captured, token) : failure("AccountChanged");
    }
    static Binding restore(Binding expected, String token) { return expected != null && expected.isValid() ? persist(expected.captured, token, true) : failure("AccountChanged"); }
    static Binding persist(int account, long expectedUserId, String token) {
        return isAccountIndex(account) ? persist(account, expectedUserId, token, PRODUCTION_ACCESS) : failure("AccountUnavailable");
    }
    static Binding persist(int account, long expectedUserId, String token, AccountAccess access) {
        Capture captured = new Capture(account, access);
        return captured.userId == expectedUserId ? persist(captured, token) : failure("AccountChanged");
    }
    private static Binding persist(Capture expected, String token) { return persist(expected, token, false); }
    private static Binding persist(Capture expected, String token, boolean replace) {
        if (expected == null || !isToken(token)) return failure("InvalidBindingToken");
        synchronized (WRITE_LOCK) {
            if (!expected.current()) return failure("AccountChanged");
            if (!replace && expected.value != null && !token.equals(expected.value)) return failure("BindingConflict");
            if (!token.equals(expected.value) && !expected.access.commit(expected.account, KEY_PREFIX + expected.userId, token)) return failure("BindingWriteFailed");
            if (!expected.sameIdentity()) return failure("AccountChanged");
            Binding stored = read(expected.account, expected.access);
            return stored.isValid() && token.equals(stored.token) ? stored : failure("BindingWriteFailed");
        }
    }
    static long activeUserId(int account) { return isAccountIndex(account) ? activeUserId(account, PRODUCTION_ACCESS) : 0; }
    static boolean isSameActiveUser(int account, long userId) { return isAccountIndex(account) && sameActiveUser(account, userId, PRODUCTION_ACCESS); }
    static boolean isToken(String token) {
        if (token == null || token.length() != 32) return false;
        for (int i=0;i<token.length();++i) { char value=token.charAt(i); if ((value<'0'||value>'9')&&(value<'a'||value>'f')) return false; }
        return true;
    }
    private static long activeUserId(int account, AccountAccess access) { return access.isActivated(account) ? Math.max(0, access.userId(account)) : 0; }
    private static boolean sameActiveUser(int account, long userId, AccountAccess access) { return userId>0 && access.isActivated(account) && access.userId(account)==userId; }
    private static boolean isAccountIndex(int account) { return account>=0 && account<UserConfig.MAX_ACCOUNT_COUNT; }
    private static Binding failure(String error) { return new Binding(null, null, error); }
    private PurpleAccountBinding() { }
}
