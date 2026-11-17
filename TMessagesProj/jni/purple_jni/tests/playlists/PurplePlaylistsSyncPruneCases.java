package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

public final class PurplePlaylistsSyncPruneCases {
    private static int checks;
    private static final byte[] EMPTY = bytes("{\"format\":1,\"playlists\":{},\"purged\":{}}");
    private static final byte[] EDIT = bytes("{\"format\":1,\"playlists\":{\"pl-aaaaaaaaaaaaaaab\":{\"title\":[\"prune ✓\",300],\"deleted\":[false,300]}},\"purged\":{}}");
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String name) { ++checks; if (!value) throw new AssertionError(name); }
    private static final class Timer implements PurplePlaylistsSyncActor.Scheduler {
        Runnable deadline; int canceled;
        public long now() { return 1000; }
        public void post(Runnable task, long delay) { check(delay == 120000, "bounded prune deadline"); deadline = task; }
        public void cancel(Runnable task) { check(task == deadline, "only owned prune timer canceled"); ++canceled; }
    }
    private static final class Client extends PurplePlaylistsSyncPublicationCases.Client {
        final TLRPC.TL_message[] remote = new TLRPC.TL_message[2];
        final File[] files = new File[2];
        final List<Integer> lookups = new ArrayList<>(), deletions = new ArrayList<>();
        int downloads; boolean holdLookup, holdDownload, holdDelete, duplicate;
        Reply<Lookup> lookupReply; Reply<File> downloadReply; Reply<PurpleSyncAutoCore.Outcome> deleteReply;
        java.util.function.BooleanSupplier deleteGuard;
        Runnable onLookup, onDownload, beforeDelete;
        PurpleSyncAutoCore.Outcome outcome = PurpleSyncAutoCore.Outcome.Deleted;
        Client(Path root, byte[] target, byte[] witness) throws Exception {
            Files.createDirectories(root);
            byte[][] values = {PurplePlaylistsSyncCore.encode(target, 101).publication(), PurplePlaylistsSyncCore.encode(witness, 101).publication()};
            for (int i = 0; i < 2; ++i) {
                files[i] = root.resolve(i + ".gz").toFile(); Files.write(files[i].toPath(), values[i]);
                remote[i] = PurplePlaylistsSyncPublicationCases.message(41 + i, values[i].length);
                remote[i].media.document.id += i;
            }
        }
        private Message wrap(int i) {
            TLRPC.TL_message value = remote[i];
            return new Message(value.id, true, false, true, value.message, PurplePlaylistsSyncPublication.NAME,
                    chat, value.media.document.id, value.media.document.size, value.edit_date, value);
        }
        @Override public int requestMessage(int id, Reply<Lookup> reply) {
            lookups.add(id); lookupReply = reply; if (onLookup != null) onLookup.run();
            if (!holdLookup) {
                Lookup value = id < 41 || id > 42 || remote[id - 41] == null ? Lookup.missing() : Lookup.found(wrap(id - 41));
                reply.onReply(value); if (duplicate) reply.onReply(value);
            }
            return ++tokens;
        }
        @Override public Object download(Message value, Reply<File> reply) {
            ++downloads; downloadReply = reply; if (onDownload != null) onDownload.run();
            if (!holdDownload) { reply.onReply(files[value.id - 41]); if (duplicate) reply.onReply(null); }
            return "download-" + value.id;
        }
        @Override public void deleteOwn(int id, java.util.function.BooleanSupplier current, Reply<PurpleSyncAutoCore.Outcome> reply) {
            if (beforeDelete != null) beforeDelete.run();
            deleteGuard = current; deleteReply = reply;
            if (!current.getAsBoolean()) { reply.onReply(PurpleSyncAutoCore.Outcome.OutcomeUnknown); return; }
            deletions.add(id);
            if (!holdDelete) { reply.onReply(outcome); if (duplicate) reply.onReply(outcome); }
        }
        JSONObject plan() throws Exception {
            JSONObject target = new JSONObject().put("message", "41").put("document", Long.toUnsignedString(remote[0].media.document.id));
            JSONObject witness = new JSONObject().put("message", "42").put("document", Long.toUnsignedString(remote[1].media.document.id));
            String hash = PurplePlaylistsSyncCore.classify(playlistMetadata(wrap(1)), Files.readAllBytes(files[1].toPath()), 101).detail().getString("hash");
            return new JSONObject().put("prune", new JSONObject().put("status", "Planned").put("targets",
                    new JSONArray().put(new JSONObject().put("target", target).put("witness", witness).put("hash", hash))));
        }
    }
    private static final class Run {
        final Client client; final Timer timer = new Timer(); final PurplePlaylistsSyncPublicationCases.Authority authority;
        final PurplePlaylistsSyncPrune operation; final List<PurplePlaylistsSyncPrune.Result> replies = new ArrayList<>();
        Run(Client client) { this.client = client; authority = new PurplePlaylistsSyncPublicationCases.Authority(client);
            operation = new PurplePlaylistsSyncPrune(client, authority, () -> true, timer); }
        void start(JSONObject plan) { operation.start(plan, replies::add); client.drain(); }
        void terminal(PurplePlaylistsSyncPrune.Status status, int deleted) {
            check(replies.size() == 1 && replies.get(0).status == status && replies.get(0).deleted == deleted,
                    "exact prune result " + status + ":" + (replies.isEmpty() ? "none" : replies.get(0).status));
            check(timer.canceled == 1, "one timer cancellation");
            operation.close(); client.drain(); check(replies.size() == 1, "one completion even after close");
        }
    }
    private static void basic(Path root) throws Exception {
        Client c = new Client(root.resolve("equal"), EDIT, EDIT); Run r = new Run(c); JSONObject plan = c.plan();
        c.duplicate = true; r.start(plan); r.terminal(PurplePlaylistsSyncPrune.Status.Ready, 1);
        check(c.lookups.equals(Arrays.asList(41,42,41,42)), "fresh target and witness read twice");
        check(c.downloads == 2 && c.deletions.equals(Arrays.asList(41)) && r.authority.checks == 2,
                "both bodies and renewed rights precede only target delete");
        check(r.replies.get(0).deleteIssued && !c.deleteGuard.getAsBoolean(), "finished deletion guard revoked");
        c = new Client(root.resolve("contained"), EMPTY, EDIT); r = new Run(c); r.start(c.plan()); r.terminal(PurplePlaylistsSyncPrune.Status.Ready, 1);
        c = new Client(root.resolve("not-contained"), EDIT, EMPTY); r = new Run(c); r.start(c.plan()); r.terminal(PurplePlaylistsSyncPrune.Status.Refused, 0);
        check(c.deletions.isEmpty(), "fresh non-containing witness never deletes");
        c = new Client(root.resolve("wrong-hash"), EDIT, EDIT); r = new Run(c); plan = c.plan();
        plan.getJSONObject("prune").getJSONArray("targets").getJSONObject(0).put("hash", "0".repeat(64));
        r.start(plan); r.terminal(PurplePlaylistsSyncPrune.Status.Refused, 0); check(c.deletions.isEmpty(), "changed canonical witness hash refuses");
    }
    private static void changes(Path root) throws Exception {
        for (int mode = 0; mode < 12; ++mode) {
            Client c = new Client(root.resolve("change-" + mode), EDIT, EDIT); Run r = new Run(c); JSONObject plan = c.plan(); final int variant = mode;
            c.onLookup = () -> {
                if (c.lookups.size() != (variant < 6 ? 3 : 4)) return;
                TLRPC.TL_message value = c.remote[variant < 6 ? 0 : 1];
                switch (variant % 6) {
                    case 0: ++value.edit_date; break;
                    case 1: ++value.media.document.id; break;
                    case 2: ++value.media.document.size; break;
                    case 3: value.message += " changed"; break;
                    case 4: c.ownership = PurpleSyncChatCore.Ownership.Other; break;
                    case 5: c.remote[variant < 6 ? 0 : 1] = null; break;
                }
            };
            r.start(plan); r.terminal(PurplePlaylistsSyncPrune.Status.Refused, 0);
            check(c.deletions.isEmpty(), "changed target/witness never deletes " + mode);
        }
        for (int mode = 0; mode < 5; ++mode) {
            Client c = new Client(root.resolve("route-" + mode), EDIT, EDIT); Run r = new Run(c); JSONObject plan = c.plan(); final int variant = mode;
            r.authority.onIssued = () -> {
                if (variant == 0) c.chat = PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, 22);
                else if (variant == 1) c.origin = new PurpleSyncChatOrigin(0, 0, 101, "replaced-login");
                else if (variant == 2) c.active = false;
                else if (variant == 3) r.authority.live = false;
                else r.operation.close();
            };
            r.start(plan); r.terminal(mode == 4 ? PurplePlaylistsSyncPrune.Status.Canceled : PurplePlaylistsSyncPrune.Status.Refused, 0);
            check(c.deletions.isEmpty() && !r.replies.get(0).deleteIssued, "last authority handoff cannot delete after route loss " + mode);
        }
    }
    private static void finalHandoff(Path root) throws Exception {
        for (int variant = 0; variant < 6; ++variant) {
            Client c = new Client(root.resolve("dispatch-" + variant), EDIT, EDIT); Run r = new Run(c); final int mode = variant;
            c.beforeDelete = () -> {
                TLRPC.TL_message value = c.remote[mode < 3 ? 0 : 1];
                if (mode % 3 == 0) ++value.edit_date;
                else if (mode % 3 == 1) ++value.media.document.id;
                else c.ownership = PurpleSyncChatCore.Ownership.Other;
            };
            r.start(c.plan()); r.terminal(PurplePlaylistsSyncPrune.Status.OutcomeUnknown, 0);
            check(c.deletions.isEmpty(), "actual queued deletion supplier rejects edited target/witness " + variant);
        }
        for (int mode = 0; mode < 4; ++mode) {
            Client c = new Client(root.resolve("initial-" + mode), EDIT, EDIT); Run r = new Run(c); JSONObject plan = c.plan();
            if (mode == 0) c.ownership = PurpleSyncChatCore.Ownership.Unknown;
            else if (mode == 1) ++c.remote[0].media.document.id;
            else if (mode == 2) Files.write(c.files[1].toPath(), new byte[(int) c.files[1].length()]);
            else c.mode = 4;
            r.start(plan); r.terminal(PurplePlaylistsSyncPrune.Status.Refused, 0);
            check(c.deletions.isEmpty(), "unknown ownership/document/body/renewed rights rejects " + mode);
        }
    }
    private static void lifecycle(Path root) throws Exception {
        for (int phase = 0; phase < 3; ++phase) {
            Client c = new Client(root.resolve("close-" + phase), EDIT, EDIT); Run r = new Run(c);
            c.holdLookup = phase == 0; c.holdDownload = phase == 1; c.holdDelete = phase == 2;
            r.start(c.plan()); check(r.replies.isEmpty(), "pending prune remains bounded until owner event");
            r.operation.close(); c.drain();
            r.terminal(phase == 2 ? PurplePlaylistsSyncPrune.Status.OutcomeUnknown : PurplePlaylistsSyncPrune.Status.Canceled, 0);
            if (phase == 0) c.lookupReply.onReply(PurpleSyncClient.Lookup.found(c.wrap(0)));
            else if (phase == 1) c.downloadReply.onReply(c.files[0]);
            else c.deleteReply.onReply(PurpleSyncAutoCore.Outcome.Deleted);
            c.drain(); check(r.replies.size() == 1 && c.deletions.size() == (phase == 2 ? 1 : 0), "late callbacks never restart or retry delete");
            check(phase == 2 || c.canceled.size() == 1, "only exact owned read handle canceled");
        }
        Client c = new Client(root.resolve("timeout"), EDIT, EDIT); Run r = new Run(c); c.holdDelete = true;
        r.start(c.plan()); r.timer.deadline.run(); c.drain(); r.terminal(PurplePlaylistsSyncPrune.Status.OutcomeUnknown, 0);
        check(c.deletions.size() == 1, "unknown deletion never auto-retries");
        for (PurpleSyncAutoCore.Outcome outcome : new PurpleSyncAutoCore.Outcome[]{PurpleSyncAutoCore.Outcome.DeletionRefused, PurpleSyncAutoCore.Outcome.NetworkFailure, PurpleSyncAutoCore.Outcome.OutcomeUnknown}) {
            c = new Client(root.resolve(outcome.name()), EDIT, EDIT); r = new Run(c); c.outcome = outcome; r.start(c.plan());
            r.terminal(outcome == PurpleSyncAutoCore.Outcome.DeletionRefused ? PurplePlaylistsSyncPrune.Status.Refused : PurplePlaylistsSyncPrune.Status.OutcomeUnknown, 0);
            check(c.deletions.size() == 1, "one attempt for " + outcome);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); basic(root); changes(root); finalHandoff(root); lifecycle(root);
        System.out.println("PLAYLIST_SYNC_PRUNE_CHECKS=" + checks); System.out.println("PLAYLIST_SYNC_PRUNE_OK");
    }
}
