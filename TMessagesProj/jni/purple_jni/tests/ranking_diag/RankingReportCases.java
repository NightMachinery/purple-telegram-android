package org.telegram.messenger.purple;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class RankingReportCases {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        ++checks;
    }

    private static final class PrivateFixture {
        final String name = "UNIQUE_PRIVATE_PERSON";
        final String username = "@PRIVATE_HANDLE";
        final String phone = "+49 151 23456789";
        final long id = 9876543210123L;
        final String hash = "abcd9876fe001122334455";
        final double rating;

        PrivateFixture(double rating) {
            this.rating = rating;
        }

        @Override
        public String toString() {
            throw new AssertionError("fixture identity must not be formatted");
        }
    }

    private static PurpleRankingReport.Slot slot(PrivateFixture fixture, int rank, boolean visible) {
        PurpleRankingReport.Slot slot = new PurpleRankingReport.Slot();
        slot.rank = rank;
        slot.rating = fixture.rating;
        slot.visible = visible;
        slot.userKnown = slot.contact = slot.mutual = slot.eligible = true;
        slot.databaseRead = true;
        slot.day = 40;
        slot.week = 200;
        slot.month = 700;
        slot.hasOutgoing = true;
        slot.lastOutgoing = 9990;
        slot.dt = PurpleRankingReport.incrementDt(4, 9990, 10001);
        slot.next = PurpleRankingReport.increment(slot.dt, 2419200);
        return slot;
    }

    public static void main(String[] args) throws Exception {
        File expected = new File(args[0]).getCanonicalFile();
        for (Class<?> type : new Class<?>[]{PurpleRankingReport.class, PurpleHintHistory.class}) {
            File actual = new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();
            check(actual.equals(expected), "production class origin");
        }
        for (Class<?> type : new Class<?>[]{PurpleRankingReport.Account.class, PurpleRankingReport.Slot.class}) {
            for (Field field : type.getDeclaredFields()) {
                check(field.getType().isPrimitive() || field.getType() == List.class,
                        "numeric model accepts no identifier-bearing object");
            }
        }
        PrivateFixture first = new PrivateFixture(1.25);
        PrivateFixture last = new PrivateFixture(900.5);
        PurpleRankingReport.Account account = new PurpleRankingReport.Account();
        account.index = 2;
        account.selected = account.suggestContacts = account.controllerReady = true;
        account.loading = true;
        account.wall = 10000;
        account.server = 9980;
        account.timeDifference = -20;
        account.sync = 9000;
        account.anchor = 10001;
        account.decay = 2419200;
        account.slots.add(slot(first, 1, true));
        account.slots.add(slot(last, 2, false));
        PurpleRankingReport.Account empty = new PurpleRankingReport.Account();
        empty.index = 3;
        empty.wall = 10000;
        PurpleHintHistory.clear();
        PurpleHintHistory.record(2, 9995, PurpleHintHistory.Source.LOCAL_INCREMENT, 2, 2, 1, 0.75);
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        String report;
        try {
            report = PurpleRankingReport.format(Arrays.asList(account, empty), PurpleHintHistory.snapshot());
        } finally {
            Locale.setDefault(previous);
        }
        check(report.startsWith("Frequent contacts\n") && report.endsWith("----\n"), "section framing");
        check(report.contains("account=2 selected=true suggestContacts=true hints=2"), "account metadata");
        check(report.contains("loading=true serverInFlight=false"), "loading separate from request");
        check(report.contains("wallMinusServer=20 timeDifference=-20"), "clock offset signs");
        check(report.contains("lastHintsSyncTime=9000 syncAgeSeconds=1000"), "sync raw and age");
        check(report.contains("ratingLoadTime=10001 anchorAgeSeconds=-1"), "future anchor age preserved");
        check(report.contains("ratingLoadTime=0 anchorAgeSeconds=unset"), "zero anchor distinguished");
        check(report.contains("hints=unavailable controllerReady=false") && report.contains("server=unavailable"),
                "missing controller is explicit without creating it");
        check(report.indexOf("rank=1 rating=1.25") < report.indexOf("rank=2 rating=900.5"), "raw order is not sorted");
        check(report.contains("rank=2 rating=900.5 visibleNow=false"), "hidden slot retained");
        check(report.contains("userKnown=true contact=true mutual=true bot=false deleted=false eligible=true"), "user flags");
        check(report.contains("outgoing1d=40 outgoing7d=200 outgoing30d=700 lastOutgoingAgeSeconds=-10 dt=-11.0"), "server-based outgoing snapshot");
        check(report.contains("source=local_increment slots=2 rankBefore=2 rankAfter=1 added=0.75"), "numeric increment event");
        for (String secret : new String[]{first.name, first.username, first.phone, Long.toString(first.id), first.hash}) {
            check(!report.contains(secret), "fixture identifier absent");
        }
        check(account.slots.get(0).rating == 1.25 && account.slots.get(1).rating == 900.5,
                "format does not mutate ratings/order");
        PurpleRankingReport.Slot unavailable = new PurpleRankingReport.Slot();
        account.slots.add(unavailable);
        String failure = PurpleRankingReport.format(Collections.singletonList(account), Collections.emptyList());
        check(failure.contains("databaseRead=false") && failure.contains("dt=unavailable nextIncrement=unavailable"), "failed read explicit");
        check(PurpleRankingReport.incrementDt(1, 80, 100) == -20, "negative dt retained");
        check(PurpleRankingReport.incrementDt(1, 80, 0) == 0, "unset anchor");
        check(PurpleRankingReport.incrementDt(0, 80, 100) == 0, "no positive mid");
        check(PurpleRankingReport.incrementDt(-1, 80, 100) == 0, "pending outgoing only");
        check(PurpleRankingReport.incrementDt(1, Integer.MAX_VALUE, -1) == Integer.MIN_VALUE, "matches int subtraction overflow");
        check(PurpleRankingReport.increment(0, 2419200) == 1, "default increment one");
        check(PurpleRankingReport.increment(-2419200, 2419200) == Math.exp(-1), "negative exponent");
        check(Math.abs(PurpleRankingReport.increment(2419200, 2419200) - Math.E) <= Math.ulp(Math.E), "positive exponent");
        check(Double.isInfinite(PurpleRankingReport.increment(999999, 1)), "overflow preserved");
        check(Double.isNaN(PurpleRankingReport.increment(0, 0)), "invalid decay preserved");
        List<PurpleHintHistory.Event> snapshot = PurpleHintHistory.snapshot();
        snapshot.clear();
        check(PurpleHintHistory.snapshot().size() == 1, "snapshot detached");
        PurpleHintHistory.clear();
        for (int i = 0; i < 70; ++i) {
            PurpleHintHistory.record(i % 2, i, PurpleHintHistory.Source.CACHE_LOAD, i, 0, 0, 0);
        }
        snapshot = PurpleHintHistory.snapshot();
        check(snapshot.size() == 50 && snapshot.get(0).time == 20 && snapshot.get(49).time == 69, "bounded chronological ring");
        PurpleHintHistory.record(2, 71, PurpleHintHistory.Source.SERVER_START, 2, 0, 0, 0);
        check(PurpleHintHistory.inFlight(2) && !PurpleHintHistory.inFlight(3), "account-scoped request");
        long generation = PurpleHintHistory.generation();
        PurpleHintHistory.clear();
        check(PurpleHintHistory.snapshot().isEmpty() && PurpleHintHistory.inFlight(2), "clear history does not cancel request");
        check(PurpleHintHistory.generation() != generation, "clear invalidates a pending export snapshot");
        for (PurpleHintHistory.Source result : new PurpleHintHistory.Source[]{PurpleHintHistory.Source.SERVER_REPLACE,
                PurpleHintHistory.Source.SERVER_NOT_MODIFIED_OR_ERROR, PurpleHintHistory.Source.SERVER_DISABLED}) {
            PurpleHintHistory.record(2, 72, PurpleHintHistory.Source.SERVER_START, 2, 0, 0, 0);
            PurpleHintHistory.record(2, 73, result, 2, 0, 0, 0);
            check(!PurpleHintHistory.inFlight(2), "each server outcome completes request");
        }
        PurpleHintHistory.record(2, 74, PurpleHintHistory.Source.SERVER_START, 2, 0, 0, 0);
        long loginGeneration = PurpleHintHistory.loginGeneration(2);
        PurpleHintHistory.reset(2, 75);
        check(!PurpleHintHistory.inFlight(2), "logout cleanup resets request tracker");
        check(PurpleHintHistory.loginGeneration(2) != loginGeneration, "logout changes login generation");
        loginGeneration = PurpleHintHistory.loginGeneration(2);
        PurpleHintHistory.clear();
        check(PurpleHintHistory.loginGeneration(2) == loginGeneration, "clear preserves login generation");
        account.accountChanged = true;
        String changed = PurpleRankingReport.format(Collections.singletonList(account), Collections.emptyList());
        check(changed.contains("account=2 account changed during export") && !changed.contains("rating="), "changed account data omitted");
        ArrayList<Thread> workers = new ArrayList<>();
        for (int n = 0; n < 4; ++n) {
            final int index = n;
            Thread worker = new Thread(() -> {
                for (int i = 0; i < 100; ++i) PurpleHintHistory.record(index, i,
                        PurpleHintHistory.Source.REMOVE, 0, 0, 0, 0);
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) worker.join();
        check(PurpleHintHistory.snapshot().size() == 50, "concurrent bounded history");
        System.out.println("RANKING_DIAG_CHECKS=" + checks);
    }
}
