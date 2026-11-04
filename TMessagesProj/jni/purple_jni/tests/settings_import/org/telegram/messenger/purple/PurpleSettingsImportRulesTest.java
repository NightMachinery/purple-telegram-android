package org.telegram.messenger.purple;


public final class PurpleSettingsImportRulesTest {
    private static final int DAILY = 0;
    private static final int TEST = 1;
    private static final String WITH_USERNAME = "%1$s (@%2$s)";

    private static int checks;
    private static int failures;
    private static String section = "";

    private PurpleSettingsImportRulesTest() {
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

    private static void testImportNeedsTheSourceAccountActive() {
        begin("import needs the source account active");
        check(PurpleSettingsImportRules.mayImport(DAILY, DAILY));
        check(!PurpleSettingsImportRules.mayImport(TEST, DAILY));
        check(!PurpleSettingsImportRules.mayImport(DAILY, TEST));
        check(PurpleSettingsImportRules.mayImport(PurpleSettingsImportRules.NO_ACCOUNT, DAILY));
        check(PurpleSettingsImportRules.mayImport(PurpleSettingsImportRules.NO_ACCOUNT, TEST));
    }

    private static void testAccountLabel() {
        begin("account label");
        check("Evar (@evar)".equals(PurpleSettingsImportRules.accountLabel("Evar", "evar", WITH_USERNAME)));
        check("Evar (@evar)".equals(PurpleSettingsImportRules.accountLabel(" Evar ", "evar", WITH_USERNAME)));
        check("Evar".equals(PurpleSettingsImportRules.accountLabel("Evar", null, WITH_USERNAME)));
        check("Evar".equals(PurpleSettingsImportRules.accountLabel("Evar", "", WITH_USERNAME)));
        check("@evar".equals(PurpleSettingsImportRules.accountLabel("", "evar", WITH_USERNAME)));
        check("@evar".equals(PurpleSettingsImportRules.accountLabel(null, "evar", WITH_USERNAME)));
        check(PurpleSettingsImportRules.accountLabel("  ", null, WITH_USERNAME) == null);
        check(PurpleSettingsImportRules.accountLabel(null, "", WITH_USERNAME) == null);
    }

    public static void main(String[] args) {
        testImportNeedsTheSourceAccountActive();
        testAccountLabel();
        System.out.println("SETTINGS_IMPORT_CHECKS="+checks);
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
