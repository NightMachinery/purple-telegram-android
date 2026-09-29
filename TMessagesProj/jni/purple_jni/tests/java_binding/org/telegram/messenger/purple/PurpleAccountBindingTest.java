package org.telegram.messenger.purple;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

public final class PurpleAccountBindingTest {
    private static final String TOKEN_A = "00112233445566778899aabbccddeeff";
    private static final String TOKEN_B = "ffeeddccbbaa99887766554433221100";

    private static final class Access
            implements PurpleAccountBinding.AccountAccess {
        boolean active = true;
        boolean commitSucceeds = true;
        boolean changeOnRead;
        boolean changeOnCommit;
        long userId = 100;
        int commits;
        final Map<String, String> values = new HashMap<>();

        @Override
        public boolean isActivated(int account) {
            return active;
        }

        @Override
        public long userId(int account) {
            return userId;
        }

        @Override
        public String read(int account, String key) {
            final String result = values.get(key);
            if (changeOnRead) {
                changeOnRead = false;
                ++userId;
            }
            return result;
        }

        @Override
        public boolean commit(int account, String key, String value) {
            ++commits;
            if (changeOnCommit) {
                ++userId;
            }
            if (commitSucceeds) {
                values.put(key, value);
            }
            return commitSucceeds;
        }
    }

    private PurpleAccountBindingTest() {
    }

    private static final class RandomBytes extends SecureRandom {
        final byte value;
        int calls;

        private RandomBytes(byte value) {
            this.value = value;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, value);
            ++calls;
        }
    }

    public static void main(String[] args) {
        testInactiveAndNonpositiveAccounts();
        testInvalidAccountIndexes();
        testStalePriorUserBinding();
        testDifferingTokenRefusal();
        testFailedCommit();
        testIdentityChangeAroundRead();
        testIdentityChangeAroundWrite();
        testCheckPreservesMissingAndMalformedTokens();
        testInitializeRetryReusesPersistedToken();
        testInitializeRejectsMalformedExistingToken();
        System.out.println("PurpleAccountBinding host test passed");
    }

    private static void testInactiveAndNonpositiveAccounts() {
        final Access inactive = new Access();
        inactive.active = false;
        expectError(PurpleAccountBinding.read(0, inactive),
                "AccountUnavailable");
        final Access nonpositive = new Access();
        nonpositive.userId = 0;
        expectError(PurpleAccountBinding.persist(0, 0, TOKEN_A, nonpositive),
                "InvalidBindingToken");
    }

    private static void testInvalidAccountIndexes() {
        expectError(PurpleAccountBinding.read(-1), "AccountUnavailable");
        expectError(PurpleAccountBinding.read(4), "AccountUnavailable");
        expectError(PurpleAccountBinding.readForCheck(-1),
                "AccountUnavailable");
        expectError(PurpleAccountBinding.persist(4, 100, TOKEN_A),
                "AccountUnavailable");
        expect(PurpleAccountBinding.activeUserId(-1) == 0);
        expect(!PurpleAccountBinding.isSameActiveUser(4, 100));
    }

    private static void testStalePriorUserBinding() {
        final Access access = new Access();
        access.values.put("purple_sync_binding_99", TOKEN_A);
        expectError(PurpleAccountBinding.read(0, access),
                "BindingUnavailable");
        expectValid(PurpleAccountBinding.persist(0, 100, TOKEN_B, access));
        expect(TOKEN_A.equals(access.values.get("purple_sync_binding_99")));
        expect(TOKEN_B.equals(access.values.get("purple_sync_binding_100")));
    }

    private static void testDifferingTokenRefusal() {
        final Access access = new Access();
        access.values.put("purple_sync_binding_100", TOKEN_A);
        expectError(PurpleAccountBinding.persist(0, 100, TOKEN_B, access),
                "BindingConflict");
        expect(access.commits == 0);
        expect(TOKEN_A.equals(access.values.get("purple_sync_binding_100")));
    }

    private static void testFailedCommit() {
        final Access access = new Access();
        access.commitSucceeds = false;
        expectError(PurpleAccountBinding.persist(0, 100, TOKEN_A, access),
                "BindingWriteFailed");
    }

    private static void testIdentityChangeAroundRead() {
        final Access access = new Access();
        access.values.put("purple_sync_binding_100", TOKEN_A);
        access.changeOnRead = true;
        expectError(PurpleAccountBinding.read(0, access), "AccountChanged");
    }

    private static void testIdentityChangeAroundWrite() {
        final Access access = new Access();
        access.changeOnCommit = true;
        expectError(PurpleAccountBinding.persist(0, 100, TOKEN_A, access),
                "AccountChanged");
    }

    private static void testCheckPreservesMissingAndMalformedTokens() {
        final Access missing = new Access();
        final PurpleAccountBinding.Binding missingBinding =
                PurpleAccountBinding.readForCheck(0, missing);
        expectValid(missingBinding);
        expect("".equals(missingBinding.token));
        final Access malformed = new Access();
        malformed.values.put("purple_sync_binding_100", "bad-token");
        final PurpleAccountBinding.Binding malformedBinding =
                PurpleAccountBinding.readForCheck(0, malformed);
        expectValid(malformedBinding);
        expect("bad-token".equals(malformedBinding.token));
    }

    private static void testInitializeRetryReusesPersistedToken() {
        final Access access = new Access();
        final RandomBytes firstRandom = new RandomBytes((byte) 0x11);
        final PurpleAccountBinding.Initialization first =
                PurpleAccountBinding.prepareInitialization(
                        0, access, firstRandom);
        expect(first.isValid());
        expect(firstRandom.calls == 1);
        final String token = HexFormat.of().formatHex(first.entropy);
        expectValid(PurpleAccountBinding.persist(
                0, first.userId, token, access));
        expect(access.commits == 1);

        final RandomBytes retryRandom = new RandomBytes((byte) 0x22);
        final PurpleAccountBinding.Initialization retry =
                PurpleAccountBinding.prepareInitialization(
                        0, access, retryRandom);
        expect(retry.isValid());
        expect(retry.userId == first.userId);
        expect(Arrays.equals(retry.entropy, first.entropy));
        expect(retryRandom.calls == 0);
        expectValid(PurpleAccountBinding.persist(
                0, retry.userId, HexFormat.of().formatHex(retry.entropy),
                access));
        expect(access.commits == 1);
    }

    private static void testInitializeRejectsMalformedExistingToken() {
        final Access access = new Access();
        access.values.put("purple_sync_binding_100", "malformed");
        final RandomBytes random = new RandomBytes((byte) 0x11);
        final PurpleAccountBinding.Initialization preparation =
                PurpleAccountBinding.prepareInitialization(0, access, random);
        expect(!preparation.isValid());
        expect("InvalidBindingToken".equals(preparation.error));
        expect(random.calls == 0);
        expect(access.commits == 0);
    }

    private static void expectValid(PurpleAccountBinding.Binding binding) {
        expect(binding.isValid());
    }

    private static void expectError(PurpleAccountBinding.Binding binding,
            String error) {
        expect(!binding.isValid());
        expect(error.equals(binding.error));
    }

    private static void expect(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }
}
