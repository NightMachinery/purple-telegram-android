package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleModernSyncCases.*;
import java.io.File;
import java.util.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;

public final class PurpleSyncAutoReviewCases {
    static final class UploadPort implements PurpleSyncAutoUpload.Port {
        PurpleSyncClient.Reply<Boolean> uploaded;
        PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> sent;
        byte[] source, body;
        int uploads, sends, reads, cancels;
        Runnable duringUpload, duringSend;
        public void worker(Runnable task) { task.run(); }
        public int upload(long id, byte[] bytes, PurpleSyncClient.Reply<Boolean> reply) {
            ++uploads; source = bytes.clone(); uploaded = reply;
            if (duringUpload != null) duringUpload.run();
            return 41;
        }
        public void exact(int id, PurpleSyncClient.Reply<byte[]> reply) { ++reads; reply.onReply(body); }
        public int send(int id, TLRPC.TL_inputFile file, PurpleSyncClient.Reply<PurpleSyncAutoUpload.Result> reply) {
            check(file.parts == 1 && file.name.equals(PurpleSyncPost.RECORD_FILE_NAME) && file.id != 0,
                "ephemeral upload uses the canonical one-part document");
            check(file.md5_checksum.length() == 32, "upload checksum supplied");
            ++sends; sent = reply;
            if (duringSend != null) duringSend.run();
            return 42;
        }
        public void cancel(int request) { ++cancels; }
    }
    static Runnable timer(Set<Runnable> before) {
        for (Runnable task : new ArrayList<>(AndroidUtilities.delayed.keySet()))
            if (!before.contains(task)) return task;
        throw new AssertionError("new timeout");
    }
    static void uploads(File root) throws Exception {
        PurpleSyncAutoCases.Port p = PurpleSyncAutoCases.bound(root, "owned-rpc");
        PurpleSyncAutoCases.initialPublish(p);
        byte[] canonical = p.client.messages.values().iterator().next();
        for (boolean edit : new boolean[] {false, true}) {
            UploadPort port = new UploadPort(); port.body = canonical;
            int[] replies = {0}; PurpleSyncAutoUpload.Result[] outcome = {null};
            PurpleSyncAutoUpload operation = new PurpleSyncAutoUpload(1, edit ? 501 : 0, canonical.clone(), canonical.clone(),
                () -> true, port, value -> { ++replies[0]; outcome[0] = value; });
            operation.start(); check(port.uploads == 1 && port.sends == 0, "no final RPC before upload confirmation");
            port.uploaded.onReply(true);
            check(port.sends == 1 && port.reads == (edit ? 1 : 0), "edit repeats exact-body read after upload");
            port.uploaded.onReply(true); check(port.sends == 1, "duplicate upload callback cannot resend");
            port.sent.onReply(new PurpleSyncAutoUpload.Result(501, null));
            check(replies[0] == 1 && outcome[0].id == 501 && !PurpleSyncAutoEdits.busy(1), "one confirmed callback releases guard");
            port.sent.onReply(new PurpleSyncAutoUpload.Result(501, null));
            check(replies[0] == 1, "late duplicate success cannot apply twice");
        }
        for (String reason : new String[] {"disabled", "routing", "new-server-body"}) {
            UploadPort port = new UploadPort(); port.body = reason.equals("new-server-body") ? new byte[]{1} : canonical;
            boolean[] live = {true}; int[] replies = {0};
            PurpleSyncAutoUpload operation = new PurpleSyncAutoUpload(1, 501, canonical.clone(), canonical.clone(),
                () -> live[0], port, value -> { check(value.id == 0, "held edit cannot report success"); ++replies[0]; });
            operation.start(); if (!reason.equals("new-server-body")) live[0] = false;
            port.uploaded.onReply(true);
            check(port.sends == 0 && replies[0] == 1, "no stale edit after " + reason);
        }
        for (boolean edit : new boolean[] {false, true}) {
            UploadPort port = new UploadPort(); port.body = canonical;
            Set<Runnable> before = new HashSet<>(AndroidUtilities.delayed.keySet());
            int[] replies = {0};
            PurpleSyncAutoUpload operation = new PurpleSyncAutoUpload(1, edit ? 501 : 0, canonical.clone(), canonical.clone(),
                () -> true, port, value -> { ++replies[0]; check(value.id == 0, "timeout is unknown"); });
            operation.start(); port.uploaded.onReply(true); timer(before).run();
            check(replies[0] == 1 && port.cancels == 1 && !PurpleSyncAutoEdits.busy(1), "timeout cancels pending RPC and releases guard");
            for (String name : new String[]{"bytes", "previous", "current", "reply", "port"}) {
                java.lang.reflect.Field f = PurpleSyncAutoUpload.class.getDeclaredField(name); f.setAccessible(true);
                check(f.get(operation) == null, "timeout releases " + name);
            }
            if (edit) {
                List<Object> retained = new ArrayList<>();
                for (int i = 0; i < 130; ++i) {
                    Set<Runnable> timers = new HashSet<>(AndroidUtilities.delayed.keySet());
                    Object stale = new Object(); retained.add(stale);
                    PurpleSyncAutoEdits.register(1, stale, () -> true, value -> {});
                    timer(timers).run();
                }
                java.lang.reflect.Field tracker = PurpleSyncAutoEdits.class.getDeclaredField("tombstones"); tracker.setAccessible(true);
                java.lang.reflect.Field token = PurpleSyncAutoUpload.class.getDeclaredField("token"); token.setAccessible(true);
                check(!((Map<?, ?>) tracker.get(null)).containsKey(token.get(operation)), "old operation tombstone was evicted at the bound");
            }
            int queued = AndroidUtilities.pending.size();
            port.sent.onReply(new PurpleSyncAutoUpload.Result(501, null));
            check(replies[0] == 1, "late callback after timeout never applies success");
            check(AndroidUtilities.pending.size() == queued + 1, "late callback requests captured-account reconciliation");
        }
        for (boolean upload : new boolean[]{false, true}) {
            UploadPort port = new UploadPort(); port.body = canonical;
            Runnable disable = () -> PurpleSyncAutoEdits.cleanup(1);
            if (upload) port.duringUpload = disable; else port.duringSend = disable;
            int[] replies = {0};
            PurpleSyncAutoUpload operation = new PurpleSyncAutoUpload(1, 501, canonical.clone(), canonical.clone(),
                () -> true, port, value -> { ++replies[0]; check(value.id == 0, "Disable racing request registration stays unknown"); });
            operation.start(); if (!upload) port.uploaded.onReply(true);
            check(replies[0] == 1 && port.cancels == 1, "request token returned after Disable is still cancelled");
        }
        p.frame.unchangedA(); p.engine.close();
    }
    static void timeouts() throws Exception {
        java.lang.reflect.Field field = PurpleSyncAutoEdits.class.getDeclaredField("entries"); field.setAccessible(true);
        Map<?, ?> entries = (Map<?, ?>) field.get(null);
        int count = entries.size(); int[] replies = {0}; List<Object> tokens = new ArrayList<>();
        for (int i = 0; i < 200; ++i) {
            Object token = new Object(); tokens.add(token);
            Set<Runnable> before = new HashSet<>(AndroidUtilities.delayed.keySet());
            PurpleSyncAutoEdits.register(1, token, () -> true, result -> { ++replies[0]; check(result == PurpleSyncAutoCore.Outcome.OutcomeUnknown, "timeout reports unknown"); });
            timer(before).run();
            check(entries.size() == count, "timeout releases message/guard entry immediately");
            if (i == 2) check(replies[0] == 3 && !PurpleSyncAutoEdits.busy(1), "reviewer's three-timeout regression");
        }
        field = PurpleSyncAutoEdits.class.getDeclaredField("tombstones"); field.setAccessible(true);
        Map<?, ?> tombstones = (Map<?, ?>) field.get(null);
        check(tombstones.size() <= 128, "weak scalar tombstones bounded");
        Object latest = tokens.get(tokens.size()-1);
        check(PurpleSyncAutoEdits.finished(1, latest, false, null) && replies[0] == 200,
            "late timeout callback is recognized without success delivery");
    }
    static void unscopedCheckpoint(File root) throws Exception {
        PurpleSyncAutoCases.Port p = PurpleSyncAutoCases.bound(root, "old-unscoped-checkpoint");
        p.enable(); p.stopOn = PurpleSyncAutoCore.Action.Post; p.saved(OWN);
        org.json.JSONObject old = p.disk(); old.remove("androidAuthority");
        write(new File(p.frame.sync(1), "auto.json"), bytes(old.toString()));
        p.stopOn = null; p.restart(); p.engine.event(PurpleSyncAutoCore.Event.Disable);
        check(!p.disk().has("androidAuthority"), "Disable cannot bless an unknown old routing authority");
        p.engine.event(PurpleSyncAutoCore.Event.Enable);
        check(p.posts == 0 && p.edits == 0 && p.reason == PurpleSyncAutoCore.Reason.StateChanged,
            "old unscoped pending bytes remain held after re-enable");
        p.frame.unchangedA(); p.engine.close();
    }
    static void run(File root) throws Exception { uploads(root); timeouts(); unscopedCheckpoint(root); }
}
