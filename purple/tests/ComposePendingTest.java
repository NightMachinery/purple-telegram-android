package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ComposePendingTest {
    private static int checks;
    private static final byte[] BODY = "normalized A".getBytes(StandardCharsets.UTF_8);

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static PurpleMarkdownSource.Candidate source() {
        return PurpleMarkdownSource.compose("**A**", "A", BODY, false);
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong();
        final AtomicBoolean authority = new AtomicBoolean(true);
        final PurpleComposePending registry = new PurpleComposePending(clock::get);
        final PurpleComposePending.Scope scope = new PurpleComposePending.Scope(0, 42, authority::get);
        final Object params = new Object(), owner = new Object();
        final PurpleMarkdownSource.Candidate candidate = source();

        Fixture() {
            check(registry.prepare(params, scope, candidate), "prepare exact source");
            check(registry.bind(0, params, owner, -1, 123, 42), "bind exact local owner and random id");
        }

        PurpleComposePending.Transfer confirm() { return registry.confirm(0, owner, -1, 7, 123, 42, BODY); }
    }

    public static void main(String[] args) throws Exception {
        Fixture f = new Fixture();
        PurpleComposePending.Transfer transfer = f.confirm();
        check(transfer != null && transfer.current() && transfer.source.text().equals("**A**"), "confirmed original source survives transfer");
        check(transfer.localId == -1 && transfer.randomId == 123 && transfer.serverId == 7, "local and random aliases reach exact acknowledgement");
        check(f.registry.retainedBytes() > 0, "queued archive attachment keeps its memory budget");
        check(f.confirm() == null, "confirmation is one shot");
        transfer.close();
        check(f.registry.retainedBytes() == 0 && !transfer.current(), "finished attachment releases source and guard");
        boolean cleared = false;
        try { f.candidate.bytes(); } catch (IllegalStateException expected) { cleared = true; }
        check(cleared, "source buffer cannot be read after release");
        f = new Fixture();
        check(f.registry.confirm(1, f.owner, -1, 7, 123, 42, BODY) == null, "other account refuses without consuming owner");
        check(f.confirm() != null, "other account left correct owner intact");
        f = new Fixture();
        check(f.registry.confirm(0, new Object(), -1, 7, 123, 42, BODY) == null, "same local id with another owner refuses");
        check(f.confirm() != null, "unmatched owner cannot consume original source");
        f = new Fixture();
        check(f.registry.confirm(0, f.owner, -1, 7, 123, 43, BODY) == null, "other dialog refuses");
        check(f.confirm() != null, "other dialog left original owner intact");
        f = new Fixture();
        check(f.registry.confirm(0, f.owner, -2, 7, 123, 42, BODY) == null && f.registry.retainedBytes() == 0, "wrong local id retires ambiguous source");
        f = new Fixture();
        check(f.registry.confirm(0, f.owner, -1, 7, 124, 42, BODY) == null, "wrong random id refuses");
        f = new Fixture();
        check(f.registry.confirm(0, f.owner, -1, 7, 123, 42, "B".getBytes(StandardCharsets.UTF_8)) == null, "server normalization mismatch refuses source");
        f = new Fixture(); f.authority.set(false);
        check(f.confirm() == null && f.registry.retainedBytes() == 0, "logout or purge authority loss revokes pending source");
        f = new Fixture(); transfer = f.confirm(); f.authority.set(false);
        check(!transfer.current(), "queued attachment keeps opening authority");
        f.registry.prune(); check(f.registry.retainedBytes() == 0, "lost authority clears queued buffers");
        f = new Fixture(); transfer = f.confirm(); f.registry.cleanup(1);
        check(transfer.current(), "other account cleanup does not cancel attachment");
        f.registry.cleanup(0); check(!transfer.current() && f.registry.retainedBytes() == 0, "own logout erases queued source");
        f = new Fixture(); f.registry.cancel(1, f.owner); check(f.confirm() != null, "other account cancellation is inert");
        f = new Fixture(); f.registry.cancel(0, f.owner); check(f.confirm() == null, "cancelled send has no source attachment");
        for (int i = 0; i < 3; ++i) {
            f = new Fixture(); f.clock.set(PurpleComposePending.TIMEOUT); f.registry.prune();
            check(f.registry.retainedBytes() == 0, "timeout releases heavy state immediately");
            check(f.confirm() == null, "late receipt after timeout cannot revive source");
        }
        AtomicLong clock = new AtomicLong(); PurpleComposePending registry = new PurpleComposePending(clock::get);
        PurpleComposePending.Scope scope = new PurpleComposePending.Scope(0, 42, () -> true);
        ArrayList<Object> retainedParams = new ArrayList<>();
        for (int i = 0; i < PurpleComposePending.MAX_ENTRIES; ++i) {
            Object params = new Object(); retainedParams.add(params);
            check(registry.prepare(params, scope, source()), "bounded live candidate registry");
        }
        PurpleMarkdownSource.Candidate excess = source();
        check(!registry.prepare(new Object(), scope, excess), "entry budget refuses extra source without evicting old authority");
        excess.close(); registry.cleanup(0); check(registry.retainedBytes() == 0, "bounded registry cleanup releases all buffers");
        System.out.println("COMPOSE_PENDING_CHECKS=" + checks);
    }
}
