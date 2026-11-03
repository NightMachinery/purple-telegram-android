package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Host cases for flood waits of sync requests outside the lookup (record reads, posts, edits, deletions): a long
 * flood is handed to the port to keep, a kept wait holds the request after a restart, and the binding keeps it
 * per Telegram method.
 */
public final class PurpleSyncMagicWaitCases {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }

    static final class Port implements PurpleRpcRequest.Port {
        long clock = 1_000_000L, held;
        int sends;
        final Map<Integer, PurpleRpcRequest.Reply> replies = new LinkedHashMap<>();
        final Map<Runnable, Long> timers = new LinkedHashMap<>();
        final List<PurpleRpcRetry.Transient> waits = new ArrayList<>();
        final List<Long> flooded = new ArrayList<>();
        public boolean current() { return true; }
        public long now() { return clock; }
        public void dispatch(Runnable task) { task.run(); }
        public void delay(Runnable task, long millis) { timers.put(task, PurpleRpcRetry.add(clock, millis)); }
        public void cancelDelay(Runnable task) { timers.remove(task); }
        public int send(TLObject request, PurpleRpcRequest.Reply reply) { replies.put(++sends, reply); return sends; }
        public void cancel(int token) { }
        public void waiting(PurpleRpcRetry.Transient wait) { waits.add(wait); }
        public long heldUntil(TLObject request) { return held; }
        public void flooded(TLObject request, long retryAt) { flooded.add(retryAt); }
        void error(int token, String text) { TLRPC.TL_error error = new TLRPC.TL_error(); error.code = 420; error.text = text; replies.get(token).accept(null, error); }
        void through(long next) {
            clock = next;
            for (Map.Entry<Runnable, Long> timer : new ArrayList<>(timers.entrySet()))
                if (timer.getValue() <= clock && timers.remove(timer.getKey()) != null) timer.getKey().run();
        }
    }

    static PurpleRpcRequest start(Port port, int[] replies) {
        PurpleRpcRequest call = new PurpleRpcRequest(new PurpleRpcRequest.Stream(), port, new TLRPC.TL_messages_getHistory(), 30000,
            (response, error) -> ++replies[0], ended -> { });
        call.start();
        return call;
    }

    public static void main(String[] args) {
        PurpleCore.ensureLoaded();

        // A long flood is handed to the port to keep; a short one is retried in place only.
        Port port = new Port(); int[] replies = { 0 };
        start(port, replies);
        port.error(1, "FLOOD_WAIT_10");
        check(port.flooded.isEmpty() && port.waits.size() == 1, "a short flood is not kept");
        port.through(port.clock + 11_000);
        check(port.sends == 2, "it is retried in place");
        port.error(2, "FLOOD_WAIT_7200");
        check(port.flooded.size() == 1 && port.flooded.get(0) == port.clock + 7_200_000L, "a long flood is kept with its end: " + port.flooded);

        // After a restart, a kept wait holds the request until it ends, as a flood wait the caller can show.
        port = new Port(); replies[0] = 0;
        port.held = port.clock + 3_600_000L;
        start(port, replies);
        check(port.sends == 0 && port.waits.size() == 1 && port.waits.get(0).kind == PurpleRpcRetry.Kind.Flood
            && port.waits.get(0).retryAt == port.held, "a kept wait holds the request: sends=" + port.sends);
        port.through(port.held - 1);
        check(port.sends == 0, "nothing is sent before it ends");
        long end = port.held; port.held = 0;
        port.through(end);
        check(port.sends == 1, "it is sent once the wait ends");
        port.replies.get(1).accept(new TLRPC.TL_boolTrue(), null);
        check(replies[0] == 1, "and answers normally");
        port = new Port(); port.held = port.clock - 1; start(port, replies);
        check(port.sends == 1 && port.waits.isEmpty(), "a wait already over holds nothing");

        // Telegram method names, as the binding keys them.
        check(PurpleRpcRequests.method(new TLRPC.TL_messages_getHistory()).equals("messages.getHistory"), "messages.getHistory");
        check(PurpleRpcRequests.method(new TLRPC.TL_channels_deleteMessages()).equals("channels.deleteMessages"), "channels.deleteMessages");
        check(PurpleRpcRequests.method(new TLRPC.TL_upload_saveFilePart()).equals("upload.saveFilePart"), "upload.saveFilePart");
        check(PurpleRpcRequests.method(new org.telegram.tgnet.tl.TL_account.updateNotifySettings()).equals("account.updateNotifySettings"),
            "nested request classes");
        for (String method : PurpleRpcRequests.WRITE_METHODS) check(PurpleDiagLogStore.VOCABULARY.contains(method.toLowerCase(java.util.Locale.ROOT)), "logged by name: " + method);

        // The binding keeps the wait per method across a restart, and a later flood only extends it.
        PurpleSyncMagicChatCases.Fake fake = new PurpleSyncMagicChatCases.Fake();
        PurpleSyncMagicChat service = new PurpleSyncMagicChat(fake);
        service.recordWait("messages.getHistory", fake.now + 7200); fake.drain();
        check(fake.logged("wait_recorded method=messages.gethistory seconds=7200"), "the kept wait is logged: " + fake.diag);
        PurpleSyncMagicChatCases.Fake restarted = new PurpleSyncMagicChatCases.Fake();
        restarted.binding = fake.binding; restarted.now = fake.now + 60;
        PurpleSyncMagicChat again = new PurpleSyncMagicChat(restarted);
        check(again.waitUntil("messages.getHistory") == fake.now + 7200, "a restarted process reads it");
        check(again.waitUntil("messages.sendMedia") == 0, "other methods are free");
        check(again.waitUntil(PurpleRpcRequests.WRITE_METHODS) == 0 && again.waitUntil("messages.sendMedia", "messages.getHistory") == fake.now + 7200,
            "a group answers its latest wait");
        again.recordWait("messages.getHistory", fake.now + 60); restarted.drain();
        check(again.waitUntil("messages.getHistory") == fake.now + 7200, "a shorter flood never shortens it");
        restarted.now = fake.now + 7200;
        check(again.waitUntil("messages.getHistory") == 0, "it ends on time");
        // The settings screen no longer searches Saved Messages for a newer file: Purple sends no search requests.
        for (String path : new String[] { "TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleSyncOffer.java",
                "TMessagesProj/src/main/java/org/telegram/ui/PurpleSettingsActivity.java" }) {
            String text;
            try { text = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8); }
            catch (java.io.IOException error) { throw new AssertionError("readable: " + path); }
            check(!text.contains("messages_search") && !text.contains("PurpleCheckSaved"), "no Saved Messages search: " + path);
        }
        System.out.println("SYNC_MAGIC_WAIT_CHECKS=" + checks);
    }
}
