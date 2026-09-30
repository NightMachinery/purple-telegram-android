package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

public final class PurpleSyncTransportTest {
    private static final long USER = 777;
    private static final int MAX = PurpleSyncReader.MAX_RECORD_BYTES;
    private static final String NAME = PurpleSyncPost.RECORD_FILE_NAME;

    private static int checks;
    private static int failures;
    private static String section = "";
    private static File scratch;
    private static String space;
    private static String install;

    private PurpleSyncTransportTest() {
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

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fill(int size, char value) {
        final byte[] result = new byte[size];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static byte[] record(String text, long seq, byte[]... parents) {
        final PurpleAccountSyncCore.Result built =
                PurpleAccountSyncCore.buildConfigRecord(utf8(text), parents,
                        space, install, "desktop:" + install, "Desktop",
                        "Harness", seq, 1700000000L + seq);
        check(built.isValid());
        return built.record;
    }

    private static final class Task {
        final String thread;
        final Runnable run;

        Task(String thread, Runnable run) {
            this.thread = thread;
            this.run = run;
        }
    }

    private static final class Sent {
        final File file;
        final byte[] bytes;
        final String caption;
        final String mime;
        final String thread;
        final PurpleSyncClient.Receipt receipt;

        Sent(File file, byte[] bytes, String caption, String mime,
                String thread, PurpleSyncClient.Receipt receipt) {
            this.file = file;
            this.bytes = bytes;
            this.caption = caption;
            this.mime = mime;
            this.thread = thread;
            this.receipt = receipt;
        }
    }

    private static final class Fake implements PurpleSyncClient {
        final ArrayDeque<Task> tasks = new ArrayDeque<>();
        String thread = "test";
        boolean active = true;
        final TreeMap<Integer, Message> cloud = new TreeMap<>();
        final Map<Long, byte[]> documents = new HashMap<>();
        final Map<Integer, Lookup> lookups = new HashMap<>();
        final Map<Long, byte[]> served = new HashMap<>();
        final Set<Long> failDownloads = new HashSet<>();
        final List<LocalCopy> copies = new ArrayList<>();
        boolean copiesUnreadable;
        int historyFailAt = -1;
        IntFunction<History> historyTamper;
        boolean throwOnLookup;
        boolean moveOnReceipt;
        boolean throwOnSend;
        int nextToken = 1;
        int nextId = 1000;
        long nextDocument = 5000;
        final List<Integer> historyOffsets = new ArrayList<>();
        final List<Integer> historyTokens = new ArrayList<>();
        int historyReplies;
        final List<Integer> lookedUp = new ArrayList<>();
        final List<Integer> lookupTokens = new ArrayList<>();
        final List<Integer> downloaded = new ArrayList<>();
        final List<Object> downloadHandles = new ArrayList<>();
        final List<Integer> cancelledTokens = new ArrayList<>();
        final List<Object> cancelledDownloads = new ArrayList<>();
        int queueQueries;
        final List<Sent> sent = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        final File root;
        final File files;
        long now = 1_800_000_000_000L;

        Fake() throws IOException {
            final File base = Files.createTempDirectory(scratch.toPath(), "fake").toFile();
            root = new File(base, PurpleSyncPost.STAGING_DIRECTORY);
            files = new File(base, "files");
            check(files.mkdirs());
        }

        void post(String name, Runnable run) {
            tasks.add(new Task(name, run));
        }

        boolean step() {
            final Task task = tasks.poll();
            if (task == null) {
                return false;
            }
            final String saved = thread;
            thread = task.thread;
            try {
                task.run.run();
            } finally {
                thread = saved;
            }
            return true;
        }

        void pump() {
            int guard = 0;
            while (step()) {
                if (++guard > 1_000_000) {
                    throw new IllegalStateException("runaway scheduler");
                }
            }
        }

        void runUntil(BooleanSupplier condition) {
            while (!condition.getAsBoolean()) {
                if (!step()) {
                    throw new IllegalStateException("idle before condition in " + section);
                }
            }
        }

        int add(Message message) {
            cloud.put(message.id, message);
            return message.id;
        }

        int addRecord(byte[] bytes) {
            final int id = nextId++;
            final long document = nextDocument++;
            documents.put(document, bytes);
            return add(new Message(id, true, false, true, "#purplesync", NAME,
                    true, document, bytes.length, 0, document));
        }

        int addText(String text) {
            final int id = nextId++;
            return add(new Message(id, true, false, false, text, null, true,
                    0, 0, 0, null));
        }

        Message message(int id) {
            return cloud.get(id);
        }

        @Override
        public long userId() {
            return USER;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void runOnMain(Runnable task) {
            post("main", task);
        }

        @Override
        public void runOnWorker(Runnable task) {
            post("worker", task);
        }

        @Override
        public void log(String line) {
            logs.add(line);
        }

        @Override
        public int requestHistory(int offsetId, int limit, Reply<History> reply) {
            check("worker".equals(thread));
            final int token = nextToken++;
            historyOffsets.add(offsetId);
            historyTokens.add(token);
            post("network", () -> reply.onReply(history(offsetId, limit)));
            return token;
        }

        private History history(int offsetId, int limit) {
            final int index = historyReplies++;
            if (index == historyFailAt) {
                return History.failed("TIMEOUT");
            }
            if (historyTamper != null) {
                final History tampered = historyTamper.apply(offsetId);
                if (tampered != null) {
                    return tampered;
                }
            }
            final List<Message> page = new ArrayList<>();
            for (Message message : cloud.descendingMap().values()) {
                if (offsetId != 0 && message.id >= offsetId) {
                    continue;
                }
                if (page.size() == limit) {
                    break;
                }
                page.add(message);
            }
            return History.of(page, cloud.size());
        }

        @Override
        public int requestMessage(int id, Reply<Lookup> reply) {
            check("worker".equals(thread));
            if (throwOnLookup) {
                throw new IllegalStateException("lookup exploded");
            }
            final int token = nextToken++;
            lookedUp.add(id);
            lookupTokens.add(token);
            post("network", () -> {
                final Lookup forced = lookups.get(id);
                if (forced != null) {
                    reply.onReply(forced);
                    return;
                }
                final Message message = cloud.get(id);
                reply.onReply(message != null ? Lookup.found(message) : Lookup.missing());
            });
            return token;
        }

        @Override
        public void cancelRequest(int token) {
            cancelledTokens.add(token);
        }

        @Override
        public Object download(Message message, Reply<File> reply) {
            check("worker".equals(thread));
            final Object handle = new Object();
            downloaded.add(message.id);
            downloadHandles.add(handle);
            post("network", () -> {
                final long document = (Long) message.remote;
                final byte[] bytes = served.containsKey(document)
                        ? served.get(document) : documents.get(document);
                if (failDownloads.contains(document) || bytes == null) {
                    reply.onReply(null);
                    return;
                }
                try {
                    final File file = new File(files, document + ".json");
                    Files.write(file.toPath(), bytes);
                    reply.onReply(file);
                } catch (IOException e) {
                    reply.onReply(null);
                }
            });
            return handle;
        }

        @Override
        public void cancelDownload(Object download) {
            cancelledDownloads.add(download);
        }

        @Override
        public void localCopies(Reply<List<LocalCopy>> reply) {
            ++queueQueries;
            post("storage", () -> reply.onReply(
                    copiesUnreadable ? null : new ArrayList<>(copies)));
        }

        @Override
        public File stagingRoot() {
            return root;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void send(File file, String caption, String mime,
                PurpleSyncClient.Receipt receipt) {
            if (throwOnSend) {
                throw new IllegalStateException("send exploded");
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file.toPath());
            } catch (IOException e) {
                bytes = null;
            }
            sent.add(new Sent(file, bytes, caption, mime, thread, receipt));
        }

        void receipt(Sent which, int serverId, boolean preparationFailed) {
            post("main", () -> which.receipt.onReceipt(serverId, preparationFailed));
        }

        int accept(Sent which) {
            final int id = addRecord(which.bytes);
            if (moveOnReceipt) {
                check(which.file.delete());
            }
            receipt(which, id, false);
            return id;
        }
    }

    private static final class Recorder implements PurpleSyncTransport.Progress,
            PurpleSyncTransport.CheckDone, PurpleSyncTransport.PostDone {
        final Fake fake;
        final List<PurpleSyncTransport.CheckResult> checks = new ArrayList<>();
        final List<PurpleSyncTransport.PostResult> posts = new ArrayList<>();
        final List<String> progress = new ArrayList<>();
        boolean offMain;
        boolean progressAfterDone;

        Recorder(Fake fake) {
            this.fake = fake;
        }

        @Override
        public void onProgress(PurpleSyncTransport.Phase phase, int done, int total) {
            offMain |= !"main".equals(fake.thread);
            progressAfterDone |= dones() != 0;
            progress.add(phase + " " + done + "/" + total);
        }

        @Override
        public void onCheckDone(PurpleSyncTransport.CheckResult result) {
            offMain |= !"main".equals(fake.thread);
            checks.add(result);
        }

        @Override
        public void onPostDone(PurpleSyncTransport.PostResult result) {
            offMain |= !"main".equals(fake.thread);
            posts.add(result);
        }

        int dones() {
            return checks.size() + posts.size();
        }

        PurpleSyncTransport.CheckResult check() {
            return checks.isEmpty() ? null : checks.get(0);
        }

        PurpleSyncTransport.PostResult post() {
            return posts.isEmpty() ? null : posts.get(0);
        }
    }

    private static PurpleSyncTransport.CheckResult runCheck(Fake fake,
            Recorder recorder) {
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        transport.check(recorder, recorder);
        check(recorder.dones() == 0);
        fake.pump();
        check(recorder.checks.size() == 1 && recorder.posts.isEmpty());
        check(!recorder.offMain && !recorder.progressAfterDone);
        return recorder.check();
    }

    private static int rowOf(PurpleSyncInventory inventory, int id) {
        for (int i = 0; i != inventory.count(); ++i) {
            if (inventory.id(i) == id) {
                return i;
            }
        }
        return -1;
    }

    private static int outcome(PurpleSyncInventory inventory, int id) {
        final int row = rowOf(inventory, id);
        return row < 0 ? -1 : inventory.transport(row);
    }

    private static long documentId(PurpleSyncInventory inventory, int id) {
        final int row = rowOf(inventory, id);
        return row < 0 ? -1 : inventory.documentIds[row];
    }

    private static long editDate(PurpleSyncInventory inventory, int id) {
        final int row = rowOf(inventory, id);
        return row < 0 ? -1 : inventory.editDates[row];
    }

    private static byte[] bytes(PurpleSyncInventory inventory, int id) {
        final int row = rowOf(inventory, id);
        return row < 0 ? new byte[] { -1 } : inventory.bytes[row];
    }

    private static PurpleSyncSettingsFile.Contents absent() {
        return new PurpleSyncSettingsFile.Contents(
                PurpleSyncSettingsFile.Status.Absent, new byte[0]);
    }

    private static PurpleSyncClient.Message meta(int id, boolean isMessage,
            boolean forwarded, boolean isDocument, String caption,
            String fileName) {
        return new PurpleSyncClient.Message(id, isMessage, forwarded,
                isDocument, caption, fileName, true, 1, 10, 0, 1L);
    }

    private static boolean candidate(PurpleSyncClient.Message message) {
        final PurpleSyncCore.Page page = PurpleSyncCore.classifyHistoryPage(0,
                new int[] { message.id }, new boolean[] { message.isMessage },
                new boolean[] { message.forwarded },
                new boolean[] { message.isDocument },
                new String[] { message.caption },
                new String[] { message.fileName });
        check(page.isValid());
        return page.candidates.length == 1;
    }

    private static void testConstantsMatchCore() {
        begin("constants");
        check(candidate(meta(5, true, false, true, "", NAME)));
        check(candidate(meta(5, true, false, true, PurpleSyncPost.CAPTION,
                "notes.txt")));
        check(candidate(meta(5, true, false, true,
                "posted " + PurpleSyncPost.CAPTION + " from a phone", null)));
        check(!candidate(meta(5, true, false, true, "", NAME + ".bak")));
        check(!candidate(meta(5, true, true, true, PurpleSyncPost.CAPTION, NAME)));
        check(!candidate(meta(5, true, false, false, PurpleSyncPost.CAPTION, null)));
        check(!candidate(meta(5, false, false, true, PurpleSyncPost.CAPTION, NAME)));
        check(PurpleSyncScanner.PAGE_SIZE == 100);
        check(MAX == 4 * 1024 * 1024);
        check("application/json".equals(PurpleSyncPost.MIME));
        check(!LEGACY_FILE_NAME.equals(NAME));
    }

    private static final String LEGACY_FILE_NAME = "settings.toml";

    private static void testHappyCheck() throws IOException {
        begin("happy check");
        final Fake fake = new Fake();
        final byte[] first = record("version = 1\n# first\n", 1);
        final byte[] second = record("version = 1\n# second\n", 2, first);
        for (int i = 0; i != 120; ++i) {
            fake.addText("note " + i);
        }
        final int a = fake.addRecord(first);
        for (int i = 0; i != 90; ++i) {
            fake.addText("more " + i);
        }
        fake.add(new PurpleSyncClient.Message(fake.nextId++, true, true, true,
                "#purplesync", NAME, true, 9, 10, 0, 9L));
        fake.add(new PurpleSyncClient.Message(fake.nextId++, false, false,
                false, "", null, true, 0, 0, 0, null));
        fake.add(new PurpleSyncClient.Message(fake.nextId++, true, false, true,
                "", "photo.jpg", true, 8, 10, 0, 8L));
        final int b = fake.addRecord(second);
        final long secondDocument = (Long) fake.message(b).remote;
        fake.cloud.put(b, new PurpleSyncClient.Message(b, true, false, true,
                "", NAME, true, secondDocument, second.length, 1700000123L,
                secondDocument));
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTransport.CheckResult result = runCheck(fake, recorder);
        check(result.status == PurpleSyncTransport.CheckStatus.Finished);
        final PurpleSyncInventory inventory = result.inventory;
        check(inventory.accountUserId == USER);
        check(inventory.scanComplete);
        check(inventory.count() == 2);
        check(inventory.id(0) == b && inventory.id(1) == a);
        check(outcome(inventory, a) == PurpleSyncInventory.FETCHED);
        check(outcome(inventory, b) == PurpleSyncInventory.FETCHED);
        check(Arrays.equals(bytes(inventory, a), first));
        check(Arrays.equals(bytes(inventory, b), second));
        check(documentId(inventory, a) == (Long) fake.message(a).remote);
        check(documentId(inventory, b) == secondDocument);
        check(editDate(inventory, a) == 0);
        check(editDate(inventory, b) == 1700000123L);
        check(inventory.totalBytes() == first.length + second.length);
        check(!result.sendQueued);
        check(fake.historyOffsets.size() == 4);
        check(fake.historyOffsets.get(0) == 0);
        check(fake.historyOffsets.get(1).equals(fake.cloud.descendingKeySet()
                .stream().skip(99).findFirst().orElse(-1)));
        check(fake.historyOffsets.get(2).equals(fake.cloud.descendingKeySet()
                .stream().skip(199).findFirst().orElse(-1)));
        check(fake.historyOffsets.get(3).equals(fake.cloud.firstKey()));
        check(fake.lookedUp.equals(Arrays.asList(b, a)));
        check(fake.downloaded.equals(Arrays.asList(b, a)));
        check(fake.queueQueries == 1);
        check(fake.cancelledTokens.isEmpty());
        check(recorder.progress.contains("Scanning 100/215"));
        check(recorder.progress.contains("Scanning 215/215"));
        check(recorder.progress.contains("Reading 0/2"));
        check(recorder.progress.get(recorder.progress.size() - 1).equals("Reading 2/2"));
        check(fake.logs.stream().anyMatch(line -> line.contains(inventory.summary())
                && line.contains("sendQueued=false")));
        final PurpleSyncCore.Review review = PurpleSyncCore.review(inventory,
                null, null, absent(), "android-00000001");
        check(review.isValid());
        check(review.inventory == PurpleSyncCore.InventoryStatus.Complete);
        check(review.heads.size() == 1);
    }

    private static void testEmptyHistory() throws IOException {
        begin("empty history");
        final Fake fake = new Fake();
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTransport.CheckResult result = runCheck(fake, recorder);
        check(result.status == PurpleSyncTransport.CheckStatus.Finished);
        check(result.inventory.scanComplete && result.inventory.count() == 0);
        check(fake.historyOffsets.size() == 1);
        final PurpleSyncCore.Review review = PurpleSyncCore.review(
                result.inventory, null, null, absent(), "android-00000001");
        check(review.isValid()
                && review.inventory == PurpleSyncCore.InventoryStatus.Complete);
    }

    private static void testStall() throws IOException {
        begin("stall");
        final Fake fake = new Fake();
        final byte[] first = record("version = 1\n# first\n", 1);
        for (int i = 0; i != 150; ++i) {
            fake.addText("note " + i);
        }
        fake.addRecord(first);
        fake.historyTamper = offset -> offset == 0 ? null : singleMessagePage(fake, offset);
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTransport.CheckResult result = runCheck(fake, recorder);
        check(result.status == PurpleSyncTransport.CheckStatus.Finished);
        check(!result.inventory.scanComplete);
        check(result.inventory.count() == 0);
        check(fake.lookedUp.isEmpty());
        check(fake.historyOffsets.size() == 2);
        check(fake.logs.stream().anyMatch(line -> line.contains("stalled")));
        final PurpleSyncCore.Review review = PurpleSyncCore.review(
                result.inventory, null, null, absent(), "android-00000001");
        check(review.isValid()
                && review.inventory == PurpleSyncCore.InventoryStatus.Incomplete);
    }

    private static PurpleSyncClient.History singleMessagePage(Fake fake, int offset) {
        final List<PurpleSyncClient.Message> page = new ArrayList<>();
        page.add(fake.cloud.get(offset));
        return PurpleSyncClient.History.of(page, fake.cloud.size());
    }

    private static void testRequestFailure() throws IOException {
        begin("request failure");
        for (int failAt = 0; failAt != 2; ++failAt) {
            final Fake fake = new Fake();
            for (int i = 0; i != 150; ++i) {
                fake.addText("note " + i);
            }
            fake.addRecord(record("version = 1\n# first\n", 1));
            fake.historyFailAt = failAt;
            final Recorder recorder = new Recorder(fake);
            final PurpleSyncTransport.CheckResult result = runCheck(fake, recorder);
            check(result.status == PurpleSyncTransport.CheckStatus.Finished);
            check(!result.inventory.scanComplete);
            check(result.inventory.count() == 0);
            check(fake.historyOffsets.size() == failAt + 1);
            check(fake.lookedUp.isEmpty());
            final PurpleSyncCore.Review review = PurpleSyncCore.review(
                    result.inventory, null, null, absent(), "android-00000001");
            check(review.isValid()
                    && review.inventory == PurpleSyncCore.InventoryStatus.Incomplete);
        }
    }

    private static void testReaderOutcomes() throws IOException {
        begin("reader outcomes");
        final Fake fake = new Fake();
        final byte[] good = record("version = 1\n# good\n", 1);
        final int vanished = fake.addRecord(good);
        final int deleted = fake.addRecord(good);
        final int uncaptioned = fake.addRecord(good);
        final int elsewhere = fake.addRecord(good);
        final int renumbered = fake.addRecord(good);
        final int serviced = fake.addRecord(good);
        final int forwarded = fake.addRecord(good);
        final int oversizedBefore = fake.addRecord(good);
        final int oversizedAfter = fake.addRecord(good);
        final int exact = fake.addRecord(fill(MAX, 'x'));
        final int shorter = fake.addRecord(good);
        final int inaccessible = fake.addRecord(good);
        final int invalid = fake.addRecord(good);
        final int failed = fake.addRecord(good);
        final int kept = fake.addRecord(good);
        final Map<Integer, PurpleSyncClient.Message> scanned = new HashMap<>(fake.cloud);
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        transport.check(recorder, recorder);
        fake.runUntil(() -> fake.historyReplies == 2);
        fake.cloud.remove(vanished);
        fake.lookups.put(deleted, PurpleSyncClient.Lookup.missing());
        final PurpleSyncClient.Message base = scanned.get(uncaptioned);
        fake.cloud.put(uncaptioned, new PurpleSyncClient.Message(uncaptioned,
                true, false, true, "", "other.json", true, base.documentId,
                base.size, 0, base.remote));
        fake.cloud.put(elsewhere, new PurpleSyncClient.Message(elsewhere, true,
                false, true, "#purplesync", NAME, false, 1, good.length, 0, 1L));
        fake.lookups.put(renumbered, PurpleSyncClient.Lookup.found(
                new PurpleSyncClient.Message(renumbered + 1, true, false, true,
                        "#purplesync", NAME, true, 1, good.length, 0, 1L)));
        fake.cloud.put(serviced, new PurpleSyncClient.Message(serviced, false,
                false, false, "", null, true, 0, 0, 0, null));
        fake.cloud.put(forwarded, new PurpleSyncClient.Message(forwarded, true,
                true, true, "#purplesync", NAME, true, 1, good.length, 0, 1L));
        fake.cloud.put(oversizedBefore, new PurpleSyncClient.Message(
                oversizedBefore, true, false, true, "#purplesync", NAME, true,
                71, MAX + 1L, 1700000200L, 71L));
        fake.documents.put(71L, fill(MAX + 1, 'o'));
        fake.cloud.put(oversizedAfter, new PurpleSyncClient.Message(
                oversizedAfter, true, false, true, "#purplesync", NAME, true,
                72, good.length, 0, 72L));
        fake.documents.put(72L, fill(MAX + 1, 'o'));
        final long shorterDocument = (Long) scanned.get(shorter).remote;
        fake.served.put(shorterDocument, Arrays.copyOf(good, good.length - 1));
        fake.failDownloads.add((Long) scanned.get(inaccessible).remote);
        fake.cloud.put(invalid, new PurpleSyncClient.Message(invalid, true,
                false, true, "#purplesync", NAME, true, 73, 0, 1700000300L, 73L));
        fake.lookups.put(failed, PurpleSyncClient.Lookup.failed("FLOOD_WAIT_3"));
        final PurpleSyncClient.Message keptBase = scanned.get(kept);
        fake.cloud.put(kept, new PurpleSyncClient.Message(kept, true, false,
                true, "#purplesync", NAME, true, keptBase.documentId,
                keptBase.size, -5, keptBase.remote));
        fake.pump();
        check(recorder.checks.size() == 1 && !recorder.offMain);
        final PurpleSyncTransport.CheckResult result = recorder.check();
        check(result.status == PurpleSyncTransport.CheckStatus.Finished);
        final PurpleSyncInventory inventory = result.inventory;
        check(inventory.scanComplete);
        check(inventory.count() == 15);
        check(outcome(inventory, vanished) == PurpleSyncInventory.VANISHED);
        check(outcome(inventory, deleted) == PurpleSyncInventory.VANISHED);
        check(outcome(inventory, uncaptioned) == PurpleSyncInventory.CHANGED);
        check(outcome(inventory, elsewhere) == PurpleSyncInventory.CHANGED);
        check(outcome(inventory, renumbered) == PurpleSyncInventory.CHANGED);
        check(outcome(inventory, serviced) == PurpleSyncInventory.CHANGED);
        check(outcome(inventory, forwarded) == PurpleSyncInventory.CHANGED);
        check(outcome(inventory, oversizedBefore) == PurpleSyncInventory.OVERSIZED);
        check(!fake.downloaded.contains(oversizedBefore));
        check(documentId(inventory, oversizedBefore) == 71);
        check(editDate(inventory, oversizedBefore) == 1700000200L);
        check(outcome(inventory, oversizedAfter) == PurpleSyncInventory.OVERSIZED);
        check(fake.downloaded.contains(oversizedAfter));
        check(documentId(inventory, oversizedAfter) == 72);
        check(outcome(inventory, exact) == PurpleSyncInventory.FETCHED);
        check(bytes(inventory, exact).length == MAX);
        check(outcome(inventory, shorter) == PurpleSyncInventory.INACCESSIBLE);
        check(outcome(inventory, inaccessible) == PurpleSyncInventory.INACCESSIBLE);
        check(bytes(inventory, inaccessible) == null);
        check(documentId(inventory, inaccessible)
                == (Long) scanned.get(inaccessible).remote);
        check(outcome(inventory, invalid) == PurpleSyncInventory.INVALID);
        check(!fake.downloaded.contains(invalid));
        check(documentId(inventory, invalid) == 73);
        check(outcome(inventory, failed) == PurpleSyncInventory.REQUEST_FAILED);
        check(outcome(inventory, kept) == PurpleSyncInventory.FETCHED);
        check(editDate(inventory, kept) == 0);
        for (int changed : new int[] { vanished, uncaptioned, elsewhere }) {
            check(documentId(inventory, changed) == 0);
        }
        final PurpleSyncCore.Review review = PurpleSyncCore.review(inventory,
                null, null, absent(), "android-00000001");
        check(review.isValid()
                && review.inventory == PurpleSyncCore.InventoryStatus.Incomplete);
    }

    private static void testNeedsReviewOutcomes() throws IOException {
        begin("needs review outcomes");
        final Fake fake = new Fake();
        final byte[] good = record("version = 1\n# good\n", 1);
        final int ok = fake.addRecord(good);
        final int gone = fake.addRecord(good);
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        transport.check(recorder, recorder);
        fake.runUntil(() -> fake.historyReplies == 2);
        fake.cloud.remove(gone);
        fake.pump();
        final PurpleSyncInventory inventory = recorder.check().inventory;
        check(outcome(inventory, ok) == PurpleSyncInventory.FETCHED);
        check(outcome(inventory, gone) == PurpleSyncInventory.VANISHED);
        final PurpleSyncCore.Review review = PurpleSyncCore.review(inventory,
                null, null, absent(), "android-00000001");
        check(review.isValid()
                && review.inventory == PurpleSyncCore.InventoryStatus.NeedsReview);
    }

    private static Fake cloudWith(int texts, int records) throws IOException {
        final Fake fake = new Fake();
        for (int i = 0; i != texts; ++i) {
            fake.addText("note " + i);
        }
        for (int i = 0; i != records; ++i) {
            fake.addRecord(record("version = 1\n# r" + i + "\n", i + 1));
        }
        return fake;
    }

    private static void testCancelMidScan() throws IOException {
        begin("cancel mid-scan");
        final Fake fake = cloudWith(250, 2);
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        transport.check(recorder, recorder);
        fake.runUntil(() -> fake.historyOffsets.size() == 2);
        transport.cancel();
        check(recorder.dones() == 0);
        fake.pump();
        check(recorder.checks.size() == 1);
        check(recorder.check().status == PurpleSyncTransport.CheckStatus.Cancelled);
        check(recorder.check().inventory == null);
        check(fake.cancelledTokens.equals(Arrays.asList(fake.historyTokens.get(1))));
        check(fake.historyOffsets.size() == 2);
        check(fake.lookedUp.isEmpty() && fake.queueQueries == 0);
        check(!recorder.offMain && !recorder.progressAfterDone);
        transport.cancel();
        fake.pump();
        check(recorder.dones() == 1);
    }

    private static void testCancelMidRead() throws IOException {
        begin("cancel mid-read");
        final Fake fake = cloudWith(10, 3);
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        transport.check(recorder, recorder);
        fake.runUntil(() -> fake.lookedUp.size() == 2);
        transport.cancel();
        fake.pump();
        check(recorder.checks.size() == 1);
        check(recorder.check().status == PurpleSyncTransport.CheckStatus.Cancelled);
        check(fake.cancelledTokens.equals(Arrays.asList(fake.lookupTokens.get(1))));
        check(fake.lookedUp.size() == 2 && fake.downloaded.size() == 1);
        check(fake.queueQueries == 0);

        begin("cancel mid-download");
        final Fake second = cloudWith(10, 3);
        final Recorder other = new Recorder(second);
        final PurpleSyncTelegramTransport again = new PurpleSyncTelegramTransport(second);
        again.check(other, other);
        second.runUntil(() -> second.downloaded.size() == 2);
        again.cancel();
        second.pump();
        check(other.checks.size() == 1);
        check(other.check().status == PurpleSyncTransport.CheckStatus.Cancelled);
        check(second.cancelledDownloads.equals(
                Arrays.asList(second.downloadHandles.get(1))));
        check(second.cancelledTokens.isEmpty());
        check(second.lookedUp.size() == 2);
    }

    private static void testAccountChanged() throws IOException {
        begin("account changed");
        final Fake fake = cloudWith(150, 2);
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        transport.check(recorder, recorder);
        fake.runUntil(() -> fake.historyReplies == 1);
        fake.active = false;
        fake.pump();
        check(recorder.checks.size() == 1);
        check(recorder.check().status == PurpleSyncTransport.CheckStatus.AccountChanged);
        check(recorder.check().inventory == null);
        check(fake.historyOffsets.size() == 1 && fake.lookedUp.isEmpty());

        begin("account changed mid-read");
        final Fake reading = cloudWith(10, 3);
        final Recorder readingRecorder = new Recorder(reading);
        final PurpleSyncTelegramTransport readingTransport =
                new PurpleSyncTelegramTransport(reading);
        readingTransport.check(readingRecorder, readingRecorder);
        reading.runUntil(() -> reading.lookedUp.size() == 1);
        reading.active = false;
        reading.pump();
        check(readingRecorder.checks.size() == 1);
        check(readingRecorder.check().status
                == PurpleSyncTransport.CheckStatus.AccountChanged);
        check(reading.downloaded.isEmpty() && reading.lookedUp.size() == 1);

        begin("account changed before start");
        final Fake idle = cloudWith(1, 0);
        idle.active = false;
        final Recorder idleRecorder = new Recorder(idle);
        new PurpleSyncTelegramTransport(idle).check(idleRecorder, idleRecorder);
        idle.pump();
        check(idleRecorder.check().status
                == PurpleSyncTransport.CheckStatus.AccountChanged);
        check(idle.historyOffsets.isEmpty());

        begin("account changed before the queue answer");
        final Fake queue = cloudWith(1, 1);
        final Recorder queueRecorder = new Recorder(queue);
        new PurpleSyncTelegramTransport(queue).check(queueRecorder, queueRecorder);
        queue.runUntil(() -> queue.queueQueries == 1);
        queue.active = false;
        queue.pump();
        check(queueRecorder.check().status
                == PurpleSyncTransport.CheckStatus.AccountChanged);
    }

    private static void testStaleGeneration() throws IOException {
        begin("stale generation");
        final Fake fake = cloudWith(250, 2);
        final Recorder first = new Recorder(fake);
        final Recorder second = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        transport.check(first, first);
        fake.runUntil(() -> fake.historyOffsets.size() == 1);
        fake.runUntil(() -> fake.historyReplies == 1);
        transport.check(second, second);
        fake.pump();
        check(first.checks.size() == 1);
        check(first.check().status == PurpleSyncTransport.CheckStatus.Cancelled);
        check(second.checks.size() == 1);
        check(second.check().status == PurpleSyncTransport.CheckStatus.Finished);
        check(second.check().inventory.count() == 2);
        check(second.check().inventory.scanComplete);
        check(fake.historyOffsets.size() == 1 + 4);
        check(first.progress.isEmpty());
        check(!first.offMain && !second.offMain && !second.progressAfterDone);

        begin("stale receipt after replacement");
        final Fake posting = new Fake();
        final Recorder poster = new Recorder(posting);
        final Recorder checker = new Recorder(posting);
        final PurpleSyncTelegramTransport both = new PurpleSyncTelegramTransport(posting);
        final byte[] staged = record("version = 1\n# staged\n", 1);
        both.post(staged, poster);
        posting.runUntil(() -> posting.sent.size() == 1);
        both.check(checker, checker);
        posting.pump();
        check(poster.posts.size() == 1);
        check(poster.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        posting.accept(posting.sent.get(0));
        posting.pump();
        check(poster.posts.size() == 1);
        check(checker.checks.size() == 1);
        check(checker.check().status == PurpleSyncTransport.CheckStatus.Finished);
        check(posting.lookedUp.isEmpty());
    }

    private static void testSendQueueRule() {
        begin("send queue rule");
        final List<PurpleSyncClient.LocalCopy> copies = new ArrayList<>();
        check(!PurpleSyncPost.holdsSyncRecord(USER, copies));
        copies.add(new PurpleSyncClient.LocalCopy(-5, USER, 0, Arrays.asList(NAME)));
        check(!PurpleSyncPost.holdsSyncRecord(USER, copies));
        copies.add(new PurpleSyncClient.LocalCopy(-6, USER, 1, Arrays.asList("settings.toml")));
        copies.add(new PurpleSyncClient.LocalCopy(-7, USER + 1, 1, Arrays.asList(NAME)));
        copies.add(new PurpleSyncClient.LocalCopy(8, USER, 2, Arrays.asList(NAME)));
        copies.add(new PurpleSyncClient.LocalCopy(-9, USER, 3, Arrays.asList(NAME)));
        copies.add(new PurpleSyncClient.LocalCopy(-10, USER, 2, Arrays.asList()));
        check(!PurpleSyncPost.holdsSyncRecord(USER, copies));
        final List<PurpleSyncClient.LocalCopy> sending = new ArrayList<>(copies);
        sending.add(new PurpleSyncClient.LocalCopy(-11, USER, 1, Arrays.asList(NAME)));
        check(PurpleSyncPost.holdsSyncRecord(USER, sending));
        final List<PurpleSyncClient.LocalCopy> failed = new ArrayList<>(copies);
        failed.add(new PurpleSyncClient.LocalCopy(-12, USER, 2, Arrays.asList(NAME)));
        check(PurpleSyncPost.holdsSyncRecord(USER, failed));
        final List<PurpleSyncClient.LocalCopy> named = new ArrayList<>(copies);
        named.add(new PurpleSyncClient.LocalCopy(-13, USER, 2,
                Arrays.asList("cover.jpg", NAME)));
        check(PurpleSyncPost.holdsSyncRecord(USER, named));
    }

    private static void testSendQueueThroughCheck() throws IOException {
        final String[] names = { "sending", "unsent at startup", "failed",
                "sent", "other name", "unreadable" };
        final int[] states = { 1, 1, 2, 0, 1, -1 };
        final boolean[] expected = { true, true, true, false, false, true };
        for (int i = 0; i != names.length; ++i) {
            begin("send queue through check: " + names[i]);
            final Fake fake = cloudWith(3, 1);
            if (states[i] < 0) {
                fake.copiesUnreadable = true;
            } else {
                fake.copies.add(new PurpleSyncClient.LocalCopy(-100 - i, USER,
                        states[i], Arrays.asList(i == 4 ? "settings.toml" : NAME)));
            }
            final Recorder recorder = new Recorder(fake);
            final PurpleSyncTransport.CheckResult result = runCheck(fake, recorder);
            check(result.status == PurpleSyncTransport.CheckStatus.Finished);
            check(result.sendQueued == expected[i]);
            check(result.inventory.count() == 1);
            check(fake.queueQueries == 1);
        }
    }

    private static void testCheckFailure() throws IOException {
        begin("check failure");
        final Fake fake = cloudWith(3, 2);
        fake.throwOnLookup = true;
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTransport.CheckResult result = runCheck(fake, recorder);
        check(result.status == PurpleSyncTransport.CheckStatus.Finished);
        check(!result.inventory.scanComplete && result.inventory.count() == 0);
        check(result.sendQueued);
    }

    private static Sent startPost(Fake fake, Recorder recorder,
            PurpleSyncTelegramTransport transport, byte[] staged) {
        transport.post(staged, recorder);
        check(recorder.dones() == 0);
        fake.runUntil(() -> fake.sent.size() == 1);
        return fake.sent.get(0);
    }

    private static void testPostConfirmed() throws IOException {
        begin("post confirmed");
        final Fake fake = new Fake();
        fake.moveOnReceipt = true;
        final byte[] staged = record("version = 1\n# staged\n", 1);
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        final Sent sent = startPost(fake, recorder, transport, staged);
        check("main".equals(sent.thread));
        check(PurpleSyncPost.CAPTION.equals(sent.caption));
        check("application/json".equals(sent.mime));
        check(NAME.equals(sent.file.getName()));
        final File directory = sent.file.getParentFile();
        check(directory.getParentFile().equals(fake.root));
        check(UUID.fromString(directory.getName()).toString().equals(directory.getName()));
        check(Arrays.equals(sent.bytes, staged));
        fake.pump();
        check(recorder.dones() == 0);
        final int id = fake.accept(sent);
        fake.pump();
        check(recorder.posts.size() == 1 && !recorder.offMain);
        final PurpleSyncTransport.PostResult result = recorder.post();
        check(result.status == PurpleSyncTransport.PostStatus.Confirmed);
        check(result.messageId == id);
        check(Arrays.equals(result.readBack, staged));
        check(fake.lookedUp.equals(Arrays.asList(id)));
        check(fake.downloaded.equals(Arrays.asList(id)));
        check(!directory.exists());

        begin("post confirmed with the copy kept");
        final Fake keeping = new Fake();
        final Recorder keeper = new Recorder(keeping);
        final Sent kept = startPost(keeping, keeper,
                new PurpleSyncTelegramTransport(keeping), staged);
        keeping.accept(kept);
        keeping.pump();
        check(keeper.post().status == PurpleSyncTransport.PostStatus.Confirmed);
        check(kept.file.isFile());
    }

    private static void testPostInvalid() throws IOException {
        begin("post invalid record");
        final byte[] staged = record("version = 1\n# staged\n", 1);
        final byte[] padded = Arrays.copyOf(staged, staged.length + 1);
        padded[staged.length] = '\n';
        final byte[][] bad = { null, new byte[0], utf8("{\"not\":\"a record\"}"),
                padded, fill(PurpleSyncPost.MAX_STAGED_BYTES + 1, ' ') };
        for (byte[] record : bad) {
            final Fake fake = new Fake();
            final Recorder recorder = new Recorder(fake);
            new PurpleSyncTelegramTransport(fake).post(record, recorder);
            check(recorder.dones() == 0);
            fake.pump();
            check(recorder.posts.size() == 1 && !recorder.offMain);
            check(recorder.post().status == PurpleSyncTransport.PostStatus.InvalidRecord);
            check(recorder.post().messageId == 0 && recorder.post().readBack == null);
            check(fake.sent.isEmpty());
            check(!fake.root.exists());
        }
        check(PurpleSyncPost.validRecord(staged));
    }

    private static void testPostReceiptLost() throws IOException {
        begin("post receipt lost");
        final Fake fake = new Fake();
        final byte[] staged = record("version = 1\n# staged\n", 1);
        final Recorder recorder = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        final Sent sent = startPost(fake, recorder, transport, staged);
        fake.pump();
        check(recorder.dones() == 0);
        transport.cancel();
        fake.pump();
        check(recorder.posts.size() == 1);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        check(sent.file.isFile());
        fake.accept(sent);
        fake.pump();
        check(recorder.posts.size() == 1);
        check(fake.lookedUp.isEmpty());
    }

    private static void testPostReceiptOutcomes() throws IOException {
        final byte[] staged = record("version = 1\n# staged\n", 1);

        begin("post send failed");
        Fake fake = new Fake();
        Recorder recorder = new Recorder(fake);
        Sent sent = startPost(fake, recorder, new PurpleSyncTelegramTransport(fake), staged);
        fake.receipt(sent, 0, false);
        fake.pump();
        check(recorder.posts.size() == 1);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        check(sent.file.isFile());
        check(fake.lookedUp.isEmpty());

        begin("post preparation failed");
        fake = new Fake();
        recorder = new Recorder(fake);
        sent = startPost(fake, recorder, new PurpleSyncTelegramTransport(fake), staged);
        fake.receipt(sent, 0, true);
        fake.pump();
        check(recorder.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        check(!sent.file.exists() && !sent.file.getParentFile().exists());

        begin("post hand-off threw");
        final Fake throwing = new Fake();
        throwing.throwOnSend = true;
        recorder = new Recorder(throwing);
        new PurpleSyncTelegramTransport(throwing).post(staged, recorder);
        throwing.pump();
        check(recorder.posts.size() == 1 && !recorder.offMain);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        final File[] throwingLeft = throwing.root.listFiles();
        check(throwingLeft != null && throwingLeft.length == 0);

        begin("post read-back mismatch");
        fake = new Fake();
        recorder = new Recorder(fake);
        sent = startPost(fake, recorder, new PurpleSyncTelegramTransport(fake), staged);
        int id = fake.accept(sent);
        fake.served.put((Long) fake.message(id).remote,
                record("version = 1\n# someone else\n", 1));
        fake.cloud.put(id, new PurpleSyncClient.Message(id, true, false, true,
                "#purplesync", NAME, true, (Long) fake.message(id).remote,
                fake.served.get((Long) fake.message(id).remote).length, 0,
                fake.message(id).remote));
        fake.pump();
        check(recorder.posts.size() == 1);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.NeedsReview);
        check(recorder.post().messageId == id && recorder.post().readBack == null);

        begin("post read-back vanished");
        fake = new Fake();
        recorder = new Recorder(fake);
        sent = startPost(fake, recorder, new PurpleSyncTelegramTransport(fake), staged);
        id = fake.accept(sent);
        fake.cloud.remove(id);
        fake.pump();
        check(recorder.post().status == PurpleSyncTransport.PostStatus.NeedsReview);

        begin("post read-back request failed");
        fake = new Fake();
        recorder = new Recorder(fake);
        sent = startPost(fake, recorder, new PurpleSyncTelegramTransport(fake), staged);
        id = fake.accept(sent);
        fake.lookups.put(id, PurpleSyncClient.Lookup.failed("TIMEOUT"));
        fake.pump();
        check(recorder.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        check(recorder.post().messageId == id);

        begin("post read-back download failed");
        fake = new Fake();
        recorder = new Recorder(fake);
        sent = startPost(fake, recorder, new PurpleSyncTelegramTransport(fake), staged);
        id = fake.accept(sent);
        fake.failDownloads.add((Long) fake.message(id).remote);
        fake.pump();
        check(recorder.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        check(recorder.posts.size() == 1);
    }

    private static void testPostCancelAndAccount() throws IOException {
        final byte[] staged = record("version = 1\n# staged\n", 1);

        begin("post cancelled before hand-off");
        final Fake early = new Fake();
        Recorder recorder = new Recorder(early);
        PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(early);
        transport.post(staged, recorder);
        early.runUntil(() -> early.tasks.stream().anyMatch(task -> "main".equals(task.thread)));
        transport.cancel();
        early.pump();
        check(recorder.posts.size() == 1);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.Cancelled);
        check(early.sent.isEmpty());
        final File[] left = early.root.listFiles();
        check(left != null && left.length == 0);

        begin("post cancelled before start");
        Fake fake = new Fake();
        recorder = new Recorder(fake);
        transport = new PurpleSyncTelegramTransport(fake);
        transport.post(staged, recorder);
        transport.cancel();
        fake.pump();
        check(recorder.posts.size() == 1);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.Cancelled);
        check(fake.sent.isEmpty() && !fake.root.exists());

        begin("post account lost before hand-off");
        final Fake lost = new Fake();
        recorder = new Recorder(lost);
        new PurpleSyncTelegramTransport(lost).post(staged, recorder);
        lost.runUntil(() -> lost.tasks.stream().anyMatch(task -> "main".equals(task.thread)));
        lost.active = false;
        lost.pump();
        check(recorder.posts.size() == 1);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.Cancelled);
        check(lost.sent.isEmpty());

        begin("post account lost after hand-off");
        fake = new Fake();
        recorder = new Recorder(fake);
        final Sent sent = startPost(fake, recorder, new PurpleSyncTelegramTransport(fake), staged);
        fake.active = false;
        fake.accept(sent);
        fake.pump();
        check(recorder.posts.size() == 1);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        check(fake.lookedUp.isEmpty());

        begin("post cancelled during read-back");
        final Fake reading = new Fake();
        recorder = new Recorder(reading);
        transport = new PurpleSyncTelegramTransport(reading);
        final Sent readSent = startPost(reading, recorder, transport, staged);
        reading.accept(readSent);
        reading.runUntil(() -> reading.lookedUp.size() == 1);
        transport.cancel();
        reading.pump();
        check(recorder.posts.size() == 1);
        check(recorder.post().status == PurpleSyncTransport.PostStatus.OutcomeUnknown);
        check(reading.cancelledTokens.equals(Arrays.asList(reading.lookupTokens.get(0))));
    }

    private static void testPrune() throws IOException {
        begin("prune");
        final Fake fake = new Fake();
        final long old = fake.now - PurpleSyncPost.STAGING_MAX_AGE_MS - 1000;
        final long recent = fake.now - 1000;
        check(fake.root.mkdirs());
        final File stale = new File(fake.root, UUID.randomUUID().toString());
        final File empty = new File(fake.root, UUID.randomUUID().toString());
        final File fresh = new File(fake.root, UUID.randomUUID().toString());
        final File touched = new File(fake.root, UUID.randomUUID().toString());
        for (File directory : new File[] { stale, empty, fresh, touched }) {
            check(directory.mkdirs());
        }
        final File staleFile = new File(stale, NAME);
        final File freshFile = new File(fresh, NAME);
        final File touchedFile = new File(touched, NAME);
        for (File file : new File[] { staleFile, freshFile, touchedFile }) {
            Files.write(file.toPath(), utf8("{}"));
        }
        check(staleFile.setLastModified(old) && stale.setLastModified(old));
        check(empty.setLastModified(old));
        check(freshFile.setLastModified(recent) && fresh.setLastModified(recent));
        check(touchedFile.setLastModified(recent) && touched.setLastModified(old));
        final Recorder recorder = new Recorder(fake);
        final byte[] staged = record("version = 1\n# staged\n", 1);
        startPost(fake, recorder, new PurpleSyncTelegramTransport(fake), staged);
        check(!stale.exists() && !empty.exists());
        check(freshFile.isFile() && touchedFile.isFile());
        check(PurpleSyncReader.readAtMost(fake.root, 10) == null);
    }

    private static void testReplacement() throws IOException {
        begin("post replaces a check");
        final Fake fake = cloudWith(250, 1);
        final Recorder checker = new Recorder(fake);
        final Recorder poster = new Recorder(fake);
        final PurpleSyncTelegramTransport transport = new PurpleSyncTelegramTransport(fake);
        transport.check(checker, checker);
        fake.runUntil(() -> fake.historyOffsets.size() == 1);
        transport.post(record("version = 1\n# staged\n", 1), poster);
        check(checker.dones() == 0 && poster.dones() == 0);
        fake.runUntil(() -> fake.sent.size() == 1);
        fake.pump();
        check(checker.checks.size() == 1);
        check(checker.check().status == PurpleSyncTransport.CheckStatus.Cancelled);
        check(fake.historyOffsets.size() == 1);
        check(fake.cancelledTokens.equals(Arrays.asList(fake.historyTokens.get(0))));
        fake.accept(fake.sent.get(0));
        fake.pump();
        check(poster.posts.size() == 1);
        check(poster.post().status == PurpleSyncTransport.PostStatus.Confirmed);
        check(checker.checks.size() == 1);
    }

    public static void main(String[] args) throws Exception {
        scratch = Files.createTempDirectory("purple-sync-transport").toFile();
        space = PurpleAccountSyncCore.formatSpaceId(fill(16, 's')).id;
        install = PurpleAccountSyncCore.formatInstallId(fill(16, 'd')).id;
        check(!space.isEmpty() && !install.isEmpty());
        testConstantsMatchCore();
        testHappyCheck();
        testEmptyHistory();
        testStall();
        testRequestFailure();
        testReaderOutcomes();
        testNeedsReviewOutcomes();
        testCancelMidScan();
        testCancelMidRead();
        testAccountChanged();
        testStaleGeneration();
        testSendQueueRule();
        testSendQueueThroughCheck();
        testCheckFailure();
        testPostConfirmed();
        testPostInvalid();
        testPostReceiptLost();
        testPostReceiptOutcomes();
        testPostCancelAndAccount();
        testPrune();
        testReplacement();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
