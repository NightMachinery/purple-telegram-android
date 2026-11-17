package org.telegram.messenger.purple;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class PurplePlaylistsSyncCommit {
    enum Status { Ready, Refused, WriteFailed, DurabilityUnknown, RetirementPending }
    static final class Result {
        final Status status;
        final boolean synced;
        final PurplePlaylistsStore.Write write;
        final PurplePlaylistsSyncCore.Result plan;
        private Result(Status status, boolean synced, PurplePlaylistsStore.Write write, PurplePlaylistsSyncCore.Result plan) {
            this.status = status; this.synced = synced; this.write = write; this.plan = plan;
        }
    }
    static final class Ack {
        private final PurplePlaylistsStore.Durable durable;
        private final PurpleSyncChatOrigin origin;
        private final PurpleSyncChatCore.Peer chat;
        private final PurplePlaylistsSyncDiscovery.Result discovery;
        private final PurplePlaylistsSyncPublication.Result published;
        private Ack(PurplePlaylistsStore.Durable durable, PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat,
                PurplePlaylistsSyncDiscovery.Result discovery, PurplePlaylistsSyncPublication.Result published) {
            this.durable = durable; this.origin = origin; this.chat = chat; this.discovery = discovery; this.published = published;
        }
        boolean acknowledges(PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat, byte[] payload) {
            if (!this.origin.equals(origin) || !this.chat.equals(chat) || !durable.current()
                    || !discovery.matches(origin, chat)) return false;
            boolean proven = published != null && published.current()
                    && published.status == PurplePlaylistsSyncPublication.Status.Confirmed
                    && origin.equals(published.attempt.origin) && chat.equals(published.attempt.chat)
                    && Arrays.equals(payload, published.payload());
            if (!proven) {
                PurplePlaylistsSyncDiscovery.Recovery proof = discovery.publication(origin, chat, payload);
                proven = proof != null && proof.current();
            }
            if (!proven) return false;
            PurplePlaylistsSyncCore.Result record = PurplePlaylistsSyncCore.classify(metadata(), payload, origin.userId);
            if (!"Valid".equals(record.detail().optString("status"))) return false;
            PurplePlaylistsCore.Result contained = PurplePlaylistsCore.contains(durable.library(), record.library(), origin.userId);
            return contained.valid() && Boolean.TRUE.equals(contained.contains) && durable.current()
                    && discovery.matches(origin, chat) && (published == null || published.current());
        }
    }
    static Result checkpoint(PurplePlaylistsStore store, byte[] request, byte[] body, long self, long now, BooleanSupplier scope) {
        PurplePlaylistsStore.Write write = null;
        PurplePlaylistsSyncCore.Result plan = null;
        try {
            if (!scope.getAsBoolean()) return result(Status.Refused, false, null, null);
            PurplePlaylistsStore.Read latest = store.read();
            if (!latest.ready()) return result(Status.Refused, false, null, null);
            plan = PurplePlaylistsSyncCore.partial(request, body, latest.library(), latest.state(), self);
            if (plan == null || !"Planned".equals(plan.detail().optString("status"))
                    || plan.detail().optBoolean("discoveryComplete") || !scope.getAsBoolean())
                return result(Status.Refused, false, null, plan);
            PurplePlaylistsLocalCore.Result state = PurplePlaylistsLocalCore.checkpoint(plan.state(),
                    PurplePlaylistsLocalCore.SyncResult.Problem, now, self);
            if (!state.valid()) return result(Status.Refused, false, null, plan);
            write = store.write(latest, plan.library(), state.canonicalBytes(), PurplePlaylistsStore.Kind.Merge, scope::getAsBoolean);
            if (!write.ready()) return result(Status.WriteFailed, false, write, plan);
            PurplePlaylistsStore.Read saved = store.read();
            if (!saved.ready() || !Arrays.equals(saved.library(), plan.library()) || !Arrays.equals(saved.state(), state.canonicalBytes())
                    || store.confirm(saved, scope::getAsBoolean) == null)
                return result(Status.DurabilityUnknown, false, write, plan);
            return result(Status.Ready, false, write, plan);
        } catch (Exception error) { return result(write == null ? Status.Refused : Status.DurabilityUnknown, false, write, plan); }
    }
    static Result apply(PurplePlaylistsStore store, PurplePlaylistsPublicationJournal journal,
            PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat, PurplePlaylistsSyncDiscovery.Result discovery,
            PurplePlaylistsSyncPublication.Result published, boolean manual, long now, BooleanSupplier scope) {
        if (store == null || journal == null || origin == null || chat == null || discovery == null || scope == null)
            return result(Status.Refused, false, null, null);
        PurplePlaylistsStore.Current guard = () -> scope.getAsBoolean() && discovery.matches(origin, chat)
                && (published == null || published.current() && published.attempt != null
                    && origin.equals(published.attempt.origin) && chat.equals(published.attempt.chat));
        PurplePlaylistsStore.Write write = null;
        PurplePlaylistsSyncCore.Result plan = null;
        try {
            if (!guard.current()) return result(Status.Refused, false, null, null);
            PurplePlaylistsStore.Read latest = store.read();
            if (!latest.ready()) return result(Status.Refused, false, null, null);
            plan = discovery.plan(latest.library(), latest.state(), manual, now, published);
            if (!"Planned".equals(plan.detail().optString("status")) || !guard.current())
                return result(Status.Refused, false, null, plan);
            PurplePlaylistsPublicationJournal.Read prior = journal.read();
            org.json.JSONObject local = new org.json.JSONObject(new String(latest.state(), java.nio.charset.StandardCharsets.UTF_8));
            if (plan.detail().optBoolean("steadyState") && !local.has("unsynced_since")
                    && local.optJSONObject("status") != null
                    && "synced".equals(local.optJSONObject("status").optString("result"))
                    && (prior.status == PurplePlaylistsPublicationJournal.Status.Empty
                        || prior.status == PurplePlaylistsPublicationJournal.Status.Retired)) {
                PurplePlaylistsStore.Durable existing = store.confirm(latest, guard);
                return result(existing != null && existing.current() ? Status.Ready : Status.DurabilityUnknown,
                        existing != null && existing.current(), null, plan);
            }
            boolean synced = plan.detail().optBoolean("discoveryComplete")
                    && ("InitialEmpty".equals(plan.detail().optString("publication"))
                        || "EqualRecord".equals(plan.detail().optString("publication"))
                            && discovery.contains(plan.library(), published));
            org.json.JSONObject records = plan.detail().optJSONObject("records");
            PurplePlaylistsLocalCore.SyncResult outcome = synced ? PurplePlaylistsLocalCore.SyncResult.Synced
                    : records != null && records.optLong("newer") > 0 ? PurplePlaylistsLocalCore.SyncResult.NewerFormat
                    : records != null && records.optLong("oversized") > 0
                        || "ExpandedLimit".equals(plan.detail().optString("publication"))
                        || "CompressedLimit".equals(plan.detail().optString("publication"))
                        ? PurplePlaylistsLocalCore.SyncResult.Oversized : PurplePlaylistsLocalCore.SyncResult.Problem;
            PurplePlaylistsLocalCore.Result state = PurplePlaylistsLocalCore.checkpoint(plan.state(), outcome, now, origin.userId);
            if (!state.valid() || !guard.current()) return result(Status.Refused, false, null, plan);
            write = store.write(latest, plan.library(), state.canonicalBytes(),
                    PurplePlaylistsStore.Kind.Merge, guard);
            if (!write.ready()) return result(Status.WriteFailed, false, write, plan);
            PurplePlaylistsStore.Read saved = store.read();
            if (!saved.ready() || !Arrays.equals(saved.library(), plan.library())
                    || !Arrays.equals(saved.state(), state.canonicalBytes()))
                return result(Status.DurabilityUnknown, false, write, plan);
            PurplePlaylistsStore.Durable durable = store.confirm(saved, guard);
            if (durable == null || !durable.current()) return result(Status.DurabilityUnknown, false, write, plan);
            PurplePlaylistsPublicationJournal.Read intent = journal.read();
            if (intent.unresolved()) {
                if (!plan.detail().optBoolean("discoveryComplete"))
                    return result(Status.RetirementPending, false, write, plan);
                if (published != null) intent = journal.confirm(intent, published);
                else intent = journal.recover(intent, discovery);
                if (intent.status != PurplePlaylistsPublicationJournal.Status.Confirmed)
                    return result(Status.RetirementPending, synced, write, plan);
                Ack ack = new Ack(durable, origin, chat, discovery, published);
                if (journal.retire(intent, ack).status != PurplePlaylistsPublicationJournal.Status.Retired)
                    return result(Status.RetirementPending, synced, write, plan);
            } else if (intent.status != PurplePlaylistsPublicationJournal.Status.Empty
                    && intent.status != PurplePlaylistsPublicationJournal.Status.Retired) {
                return result(Status.RetirementPending, synced, write, plan);
            }
            return result(Status.Ready, synced, write, plan);
        } catch (Exception error) { return result(write == null ? Status.Refused : Status.DurabilityUnknown, false, write, plan); }
    }
    static void onWorker(PurplePlaylistsWorker worker, PurpleSyncChatOrigin origin, PurpleSyncChatCore.Peer chat,
            PurplePlaylistsSyncDiscovery.Result discovery, PurplePlaylistsSyncPublication.Result published,
            boolean manual, long now, BooleanSupplier scope, Consumer<Result> done) {
        if (worker == null || origin == null || worker.accountSlot() != origin.slot || done == null || scope == null)
            throw new IllegalArgumentException("MissingPlaylistSyncCommitWorker");
        AtomicBoolean answered = new AtomicBoolean();
        Consumer<Result> answer = value -> { if (answered.compareAndSet(false, true)) done.accept(value); };
        Runnable refused = () -> answer.accept(result(Status.Refused, false, null, null));
        if (!worker.postStore(null, (store, self) -> {
            if (self != origin.userId || !worker.downloadCurrent() || !scope.getAsBoolean()) { refused.run(); return; }
            PurplePlaylistsPublicationJournal journal = new PurplePlaylistsPublicationJournal(store.media(), origin, chat,
                    () -> worker.downloadCurrent() && scope.getAsBoolean());
            Result result = apply(store, journal, origin, chat, discovery, published, manual, now,
                    () -> worker.downloadCurrent() && scope.getAsBoolean());
            worker.syncChanged(result.write);
            answer.accept(result);
        }, refused, false)) refused.run();
    }
    private static Result result(Status status, boolean synced, PurplePlaylistsStore.Write write, PurplePlaylistsSyncCore.Result plan) {
        return new Result(status, synced, write, plan);
    }
    private static byte[] metadata() {
        return ("{\"regular\":true,\"forwarded\":false,\"isDocument\":true,\"caption\":\""
                + PurplePlaylistsSyncPublication.CAPTION + "\",\"mime\":\"application/gzip\",\"names\":[\""
                + PurplePlaylistsSyncPublication.NAME + "\"],\"ownership\":\"Own\",\"message\":\"1\",\"document\":\"1\",\"read\":\"Succeeded\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private PurplePlaylistsSyncCommit() { }
}
