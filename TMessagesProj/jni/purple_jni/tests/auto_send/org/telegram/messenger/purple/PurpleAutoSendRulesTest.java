package org.telegram.messenger.purple;

public final class PurpleAutoSendRulesTest {
    private static final int NONE = PurpleAutoSendRules.NO_ACCOUNT;

    private static int checks;
    private static int failures;
    private static String section = "";

    private PurpleAutoSendRulesTest() {
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

    private static boolean[] slots(boolean... activated) {
        return activated;
    }

    private static void testOneAccountPosts() {
        begin("one account");
        final boolean[] activated = slots(true, false, false, false);
        check(PurpleAutoSendRules.firstAccount(activated) == 0);
        check(PurpleAutoSendRules.postsFrom(0, activated));
    }

    private static void testOnlyTheFirstAccountPosts() {
        begin("only the first account posts");
        final boolean[] activated = slots(true, false, false, true);
        check(PurpleAutoSendRules.firstAccount(activated) == 0);
        check(PurpleAutoSendRules.postsFrom(0, activated));
        check(!PurpleAutoSendRules.postsFrom(3, activated));
    }

    private static void testFirstAccountNeedNotBeSlotZero() {
        begin("first account in a later slot");
        final boolean[] activated = slots(false, true, false, true);
        check(PurpleAutoSendRules.firstAccount(activated) == 1);
        check(PurpleAutoSendRules.postsFrom(1, activated));
        check(!PurpleAutoSendRules.postsFrom(3, activated));
        check(!PurpleAutoSendRules.postsFrom(0, activated));
    }

    private static void testEveryOtherAccountIsSkipped() {
        begin("every other account is skipped");
        final boolean[] activated = slots(true, true, true, true);
        check(PurpleAutoSendRules.postsFrom(0, activated));
        check(!PurpleAutoSendRules.postsFrom(1, activated));
        check(!PurpleAutoSendRules.postsFrom(2, activated));
        check(!PurpleAutoSendRules.postsFrom(3, activated));
    }

    private static void testNoAccount() {
        begin("no account");
        final boolean[] none = slots(false, false, false, false);
        check(PurpleAutoSendRules.firstAccount(none) == NONE);
        check(!PurpleAutoSendRules.postsFrom(0, none));
        check(!PurpleAutoSendRules.postsFrom(NONE, none));
        check(!PurpleAutoSendRules.postsFrom(NONE, slots(true, false, false, false)));
    }

    public static void main(String[] args) {
        testOneAccountPosts();
        testOnlyTheFirstAccountPosts();
        testFirstAccountNeedNotBeSlotZero();
        testEveryOtherAccountIsSkipped();
        testNoAccount();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
