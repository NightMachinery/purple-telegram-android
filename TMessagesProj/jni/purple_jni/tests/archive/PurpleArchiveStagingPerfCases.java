package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;

public final class PurpleArchiveStagingPerfCases {
    static void counts() throws Exception {
        byte[] data = bytes(2 * 1048576 + 19);
        PurpleArchiveStaging staging = stage(message(7100, "operation bounds", data.length), new AtomicBoolean(true));
        long start = System.nanoTime(), maximumReads = 0, maximumPublications = 0, maximumSyncs = 0;
        for (int offset = 0; offset < data.length; offset += 65536) {
            PurpleArchiveFiles.operationsNative(true); long reads = worker.store.authorityReads;
            staging.piece(offset, Arrays.copyOfRange(data, offset, Math.min(data.length, offset + 65536)));
            long[] operations = PurpleArchiveFiles.operationsNative(false);
            long authority = worker.store.authorityReads - reads;
            check(operations.length == 5, "real native operation counters have the expected shape");
            check(operations[1] == 0, "no per-node file publication");
            check(operations[0] <= 5, "one image, page, consolidated cache, delta and head publication per piece");
            check(operations[2] <= 10, "at most ten actual filesystem fsync calls per piece");
            check(authority <= 6, "one initial authority read and at most five committed-effect reads");
            maximumReads = Math.max(maximumReads, authority); maximumPublications = Math.max(maximumPublications, operations[0]); maximumSyncs = Math.max(maximumSyncs, operations[2]);
        }
        java.lang.reflect.Field field = PurpleArchiveStaging.class.getDeclaredField("bundles"); field.setAccessible(true);
        check(((java.util.Map<?, ?>) field.get(staging)).size() <= 16, "proof cache has a fixed bundle bound");
        try (PurpleArchiveFiles.Listing listing = staging.directory.listing()) {
            for (;;) { String[] names = listing.next(); if (names.length == 0) break; for (String name : names) check(!name.startsWith("n-"), "new jobs create no node files"); }
        }
        staging.admitConsistency(hash(data)); check(staging.finish(), "bounded operation path finishes exact bytes"); canonical(staging, data);
        System.out.println("STAGING_PERF_MAX_AUTHORITY_READS=" + maximumReads);
        System.out.println("STAGING_PERF_MAX_PUBLICATIONS=" + maximumPublications);
        System.out.println("STAGING_PERF_MAX_FSYNC=" + maximumSyncs);
        System.out.println("STAGING_PERF_2MIB_MS=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
    static void repair() throws Exception {
        byte[] data = bytes(17 * 65536 + 3); AtomicBoolean active = new AtomicBoolean(true);
        PurpleArchiveStaging staging = stage(message(7101, "rebuild cache", data.length), active); add(staging, data);
        String checkpoint = staging.call("stagingInspect", new JSONObject().put("header", staging.head)).getString("checkpoint");
        Files.write(staging.directory.file("cache-" + checkpoint).toPath(), new byte[]{'{','}'});
        PurpleArchiveStaging reopened = PurpleArchiveStaging.reopen(worker.store, staging.id, active::get);
        PurpleArchiveStaging.Page repaired; int turns = 0;
        for (;;) { try { repaired = reopened.page(0); break; } catch (PurpleArchiveStaging.CacheRebuilding pending) { ++turns; check(turns <= 18, "repair has at most one authenticated checkpoint per turn"); } }
        check(repaired.value != null && turns > 0, "tampered consolidated cache rebuilds from authenticated durable deltas over bounded turns");
        check(reopened.page(4).value != null, "rebuilt proof preserves the last partial page");
        check(reopened.finish(), "repaired cache cannot change authenticated source bytes"); canonical(reopened, data);
    }
    static void cuts() throws Exception {
        String[] prefixes = {"image", "page-", "cache-", "delta-", "head"};
        for (int i = 0; i < prefixes.length; ++i) {
            byte[] data = bytes(93); AtomicBoolean active = new AtomicBoolean(true), cut = new AtomicBoolean();
            TLRPC.Message message = message(7110 + i, "durable cut " + prefixes[i], data.length);
            PurpleArchiveStaging staging = stage(message, active); String prefix = prefixes[i];
            PurpleArchiveFiles.publicationObserver.set(name -> {
                if ((prefix.equals("image") ? name.matches("[0-9a-f]{64}") : name.startsWith(prefix)) && cut.compareAndSet(false, true)) throw new IllegalStateException("fixture process-death cut");
            });
            try { refuses(() -> staging.piece(0, data), "durable-step cut interrupts the caller"); }
            finally { PurpleArchiveFiles.publicationObserver.remove(); }
            check(cut.get(), "requested durable publication was reached");
            PurpleArchiveStaging reopened = PurpleArchiveStaging.reopen(worker.store, staging.id, active::get);
            boolean complete = false;
            try { complete = reopened.finish(); } catch (Exception expected) { }
            check(!complete, "a process death before consistency never publishes Complete");
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(message), null)) {
                check(slice.head.getJSONArray("media").getJSONObject(0).getInt("state") != 2, "cut leaves no falsely Complete attachment");
            }
        }
    }
    static void authority() throws Exception {
        byte[] data = bytes(101); AtomicBoolean active = new AtomicBoolean(true), changed = new AtomicBoolean();
        PurpleArchiveStaging staging = stage(message(7120, "authority before effect", data.length), active);
        PurpleArchiveFiles.Snapshot original = worker.store.paths.metadata.read("fence.json", 65536);
        PurpleArchiveFiles.publicationObserver.set(name -> {
            if (name.matches("[0-9a-f]{64}") && changed.compareAndSet(false, true)) {
                try { Files.write(worker.store.paths.metadata.file("fence.json").toPath(), new byte[]{'{','}'}); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        PurpleArchiveFiles.operationsNative(true);
        try { refuses(() -> staging.piece(0, data), "changed durable fence is refused at the next committed effect"); }
        finally {
            PurpleArchiveFiles.publicationObserver.remove();
            PurpleArchiveFiles.Snapshot now = worker.store.paths.metadata.read("fence.json", 65536);
            worker.store.paths.metadata.publish("fence.json", now, original.bytes);
        }
        check(changed.get(), "authority changed after the first durable image");
        check(worker.store.current(), "fixture restores the exact original authority for later cases");
        AtomicBoolean permit = new AtomicBoolean(true), revoked = new AtomicBoolean();
        PurpleArchiveStaging revokedJob = stage(message(7121, "memory revoke before effect", data.length), permit);
        PurpleArchiveFiles.publicationObserver.set(name -> { if (name.matches("[0-9a-f]{64}")) { revoked.set(true); permit.set(false); } });
        try { refuses(() -> revokedJob.piece(0, data), "instant source revocation stops the next effect in the same step"); }
        finally { PurpleArchiveFiles.publicationObserver.remove(); }
        check(revoked.get(), "memory revocation cut was exercised");
    }
    static void cachedWindows(File root) throws Exception {
        byte[] data = bytes(PurpleArchiveStaging.WINDOW + 19); TLRPC.Message message = message(7130,"cached complete windows",data.length);
        File file = new File(root,"cached-full-window"); Files.write(file.toPath(),data);
        PurpleArchiveAcquiredSource.Entry entry = cachedEntry(message,file); java.util.concurrent.atomic.AtomicInteger images = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong imageBytes = new java.util.concurrent.atomic.AtomicLong();
        PurpleArchiveFiles.publicationObserver.set(name -> {
            if (name.matches("[0-9a-f]{64}")) {
                try { images.incrementAndGet(); imageBytes.addAndGet(Files.size(entry.staging.directory.file(name).toPath())); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        try {
            entry.fileStep(); check(entry.position == PurpleArchiveStaging.WINDOW,"completed-file step stages one full bounded core window");
            entry.fileStep(); check(entry.position == data.length,"last partial completed-file window is staged once");
            int steps=0; while(!entry.complete){entry.fileStep();check(++steps<=6,"two-pass completed-file validation stays bounded");}
            check(images.get()==2&&imageBytes.get()<=data.length+2L*4608,"completed files avoid the growing 64/128/192/256 KiB image rewrite");
            check(entry.staging.finish(),"two coherent cached-file passes admit exact Complete bytes");canonical(entry.staging,data);entry.published=true;
        } finally { PurpleArchiveFiles.publicationObserver.remove();entry.close(); }
    }
    static void legacy() throws Exception {
        byte[] data=bytes(PurpleArchiveStaging.WINDOW+19);AtomicBoolean active=new AtomicBoolean(true);
        PurpleArchiveStaging staging=stage(message(7131,"legacy cache layout",data.length),active);
        staging.piece(0,Arrays.copyOfRange(data,0,65536));staging.piece(65536,Arrays.copyOfRange(data,65536,131072));
        for(int checkpoint=1;checkpoint<=2;++checkpoint){
            String name="cache-"+checkpoint;JSONObject bundle=PurpleArchiveBinding.json(staging.directory.read(name,32768).bytes);
            for(int level=1;level<=46;++level){JSONObject node=bundle.getJSONArray("nodes").getJSONObject(level-1);String file="n-"+level+"-"+node.getString("hash");
                byte[] bytes=new JSONObject().put("left",node.getString("left")).put("right",node.getString("right")).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                staging.directory.publish(file,staging.directory.read(file,32768),bytes);
            }
            staging.directory.remove(name,false);
        }
        PurpleArchiveStaging resumed=PurpleArchiveStaging.reopen(worker.store,staging.id,active::get);PurpleArchiveFiles.operationsNative(true);
        for(int offset=131072;offset<data.length;offset+=65536)resumed.piece(offset,Arrays.copyOfRange(data,offset,Math.min(data.length,offset+65536)));
        check(PurpleArchiveFiles.operationsNative(false)[1]==0,"legacy node caches are read but never republished by production staging");
        resumed.admitConsistency(hash(data));check(resumed.finish(),"legacy jobs resume using original authenticated checkpoint and source binding");canonical(resumed,data);
    }
    static void replacement() throws Exception {
        byte[] data = bytes(83); PurpleArchiveStaging staging = stage(message(7122, "directory replacement", data.length), new AtomicBoolean(true));
        File original = staging.directory.file, parked = new File(original.getParentFile(), "parked-" + staging.id); AtomicBoolean moved = new AtomicBoolean();
        PurpleArchiveFiles.publicationObserver.set(name -> {
            if (name.matches("[0-9a-f]{64}") && moved.compareAndSet(false, true)) {
                try { Files.move(original.toPath(), parked.toPath()); Files.createDirectory(original.toPath()); Files.setPosixFilePermissions(original.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        try { refuses(() -> staging.piece(0,data), "a replaced directory never receives the next metadata effect"); }
        finally { PurpleArchiveFiles.publicationObserver.remove(); }
        check(moved.get() && original.list() != null && original.list().length == 0, "replacement directory remains byte-empty");
        Files.delete(original.toPath()); Files.move(parked.toPath(),original.toPath());
        check(worker.store.current(), "replacement fixture restores the original account directory incarnation");
    }
    static void commitFence() throws Exception {
        PurpleArchiveFiles.Snapshot fence = worker.store.paths.metadata.read("fence.json",65536);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        org.json.JSONArray statements = new org.json.JSONArray().put(PurpleArchiveDeletedBoundaryCases.statement("BEGIN IMMEDIATE",false))
            .put(PurpleArchiveDeletedBoundaryCases.statement("UPDATE archive_meta SET maintenance_id='fixture' WHERE singleton=1 AND maintenance_id=''",true))
            .put(PurpleArchiveDeletedBoundaryCases.statement("COMMIT",false));
        JSONObject plan = new JSONObject().put("status","Ready").put("statements",statements);
        boolean committed;
        try (PurpleArchiveStore.StagingStep step = worker.store.stagingStep()) {
            committed = worker.store.execute(new PurpleArchiveCore.Program(plan), () -> {
                if (calls.incrementAndGet() == 4) {
                    try { Files.write(worker.store.paths.metadata.file("fence.json").toPath(),new byte[]{'{','}'}); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
                return true;
            }).committed;
        } finally {
            PurpleArchiveFiles.Snapshot now = worker.store.paths.metadata.read("fence.json",65536);
            worker.store.paths.metadata.publish("fence.json",now,fence.bytes);
        }
        check(calls.get() == 4 && !committed,"SQL COMMIT revalidates full durable authority after all cached checks");
        PurpleArchiveSqlite.Result metadata = PurpleArchiveQueryCases.database().run("SELECT maintenance_id FROM archive_meta WHERE singleton=1");
        check(metadata.rows == 1 && metadata.values[0][0].text().isEmpty(),"failed commit authority rolls back the uncommitted mutation");
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]); check(root.mkdir(), "exclusive staging performance fixture");
        ApplicationLoader.files = new File(root, "files"); check(ApplicationLoader.files.mkdir(), "fixture files"); ApplicationLoader.applicationContext = new android.content.Context();
        UserConfig.selectedAccount = 0; UserConfig.getInstance(0).active = true; UserConfig.getInstance(0).user = 11; PurpleArchiveLogin.source = slot -> "11111111111111111111111111111111";
        PurpleArchive.refresh(0, () -> {}); await(() -> PurpleArchive.status(0).state.equals("Ready"), "archive opens offline"); worker = PurpleArchive.existing(PurpleAccountFiles.capture(0));
        on(() -> { counts(); repair(); cuts(); authority(); cachedWindows(root); legacy(); replacement(); commitFence(); worker.store.drainReaders(); worker.store.close(); worker.store = null; return null; }); worker.queue.shutdown();
        System.out.println("ARCHIVE_STAGING_PERF_CHECKS=" + checks);
    }
}
