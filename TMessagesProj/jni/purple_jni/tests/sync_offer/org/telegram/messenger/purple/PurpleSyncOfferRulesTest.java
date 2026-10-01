package org.telegram.messenger.purple;

import org.telegram.messenger.purple.PurpleSyncOfferRules.Verdict;

public final class PurpleSyncOfferRulesTest {
    private static final int DAILY = 0;
    private static final int TEST = 1;
    private static final String WITH_USERNAME = "%1$s (@%2$s)";

    private static int checks;
    private static int failures;
    private static String section = "";

    private PurpleSyncOfferRulesTest() {
    }

    private static void check(boolean ok) {
        ++checks;
        if (!ok) {
            ++failures;
            final StackTraceElement where = new Throwable().getStackTrace()[1];
            System.out.println("  FAIL  " + section + ":" + where.getLineNumber());
        }
    }

    private static void begin(String name) {
        section = name;
    }

    private static Verdict launch(int account, int active, int id, long date, int lastOffered, long stamp) {
        return PurpleSyncOfferRules.judge(account, active, false, id, date, lastOffered, stamp);
    }

    private static Verdict manual(int account, int active, int id, long date, long stamp) {
        return PurpleSyncOfferRules.judge(account, active, true, id, date, 0, stamp);
    }

    private static void testActiveAccountNewerFileIsOffered() {
        begin("active account newer file");
        check(launch(DAILY, DAILY, 10, 2000, 5, 1000) == Verdict.OFFER);
        check(manual(DAILY, DAILY, 10, 2000, 1000) == Verdict.OFFER);
        check(launch(TEST, TEST, 10, 2000, 0, 0) == Verdict.OFFER);
    }

    private static void testInactiveAccountIsNeverOffered() {
        begin("inactive account");
        check(launch(TEST, DAILY, 10, 2000, 0, 1000) == Verdict.INACTIVE_ACCOUNT);
        check(manual(TEST, DAILY, 10, 2000, 1000) == Verdict.INACTIVE_ACCOUNT);
        check(launch(DAILY, TEST, 10, 2000, 0, 1000) == Verdict.INACTIVE_ACCOUNT);
    }

    private static void testInactiveAccountRecordsNothing() {
        begin("inactive account before the other rules");
        check(launch(TEST, DAILY, 5, 2000, 5, 1000) == Verdict.INACTIVE_ACCOUNT);
        check(launch(TEST, DAILY, 10, 1000, 0, 1000) == Verdict.INACTIVE_ACCOUNT);
        check(manual(TEST, DAILY, 10, 500, 1000) == Verdict.INACTIVE_ACCOUNT);
    }

    private static void testLaunchOffersAMessageOnce() {
        begin("launch offers once");
        check(launch(DAILY, DAILY, 5, 2000, 5, 1000) == Verdict.ALREADY_OFFERED);
        check(launch(DAILY, DAILY, 4, 2000, 5, 1000) == Verdict.ALREADY_OFFERED);
        check(manual(DAILY, DAILY, 5, 2000, 1000) == Verdict.OFFER);
    }

    private static void testOlderFileIsNotOffered() {
        begin("not newer than settings.toml");
        check(launch(DAILY, DAILY, 10, 1000, 5, 1000) == Verdict.NOT_NEWER);
        check(launch(DAILY, DAILY, 10, 999, 5, 1000) == Verdict.NOT_NEWER);
        check(manual(DAILY, DAILY, 10, 1000, 1000) == Verdict.NOT_NEWER);
    }

    private static void testImportNeedsTheSourceAccountActive() {
        begin("import needs the source account active");
        check(PurpleSyncOfferRules.mayImport(DAILY, DAILY));
        check(!PurpleSyncOfferRules.mayImport(TEST, DAILY));
        check(!PurpleSyncOfferRules.mayImport(DAILY, TEST));
        check(PurpleSyncOfferRules.mayImport(PurpleSyncOfferRules.NO_ACCOUNT, DAILY));
        check(PurpleSyncOfferRules.mayImport(PurpleSyncOfferRules.NO_ACCOUNT, TEST));
    }

    private static void testAccountLabel() {
        begin("account label");
        check("Evar (@evar)".equals(PurpleSyncOfferRules.accountLabel("Evar", "evar", WITH_USERNAME)));
        check("Evar (@evar)".equals(PurpleSyncOfferRules.accountLabel(" Evar ", "evar", WITH_USERNAME)));
        check("Evar".equals(PurpleSyncOfferRules.accountLabel("Evar", null, WITH_USERNAME)));
        check("Evar".equals(PurpleSyncOfferRules.accountLabel("Evar", "", WITH_USERNAME)));
        check("@evar".equals(PurpleSyncOfferRules.accountLabel("", "evar", WITH_USERNAME)));
        check("@evar".equals(PurpleSyncOfferRules.accountLabel(null, "evar", WITH_USERNAME)));
        check(PurpleSyncOfferRules.accountLabel("  ", null, WITH_USERNAME) == null);
        check(PurpleSyncOfferRules.accountLabel(null, "", WITH_USERNAME) == null);
    }

    public static void main(String[] args) {
        testActiveAccountNewerFileIsOffered();
        testInactiveAccountIsNeverOffered();
        testInactiveAccountRecordsNothing();
        testLaunchOffersAMessageOnce();
        testOlderFileIsNotOffered();
        testImportNeedsTheSourceAccountActive();
        testAccountLabel();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
