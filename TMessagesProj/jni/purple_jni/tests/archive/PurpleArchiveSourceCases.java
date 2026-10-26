package org.telegram.messenger.purple;

import static org.telegram.messenger.purple.PurpleArchiveB3aCases.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.Provider;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.MacSpi;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveSourceCases {
    static volatile Runnable nativeBarrier;

    public static final class BarrierProvider extends Provider {
        public BarrierProvider() {
            super("ArchiveSourceBarrier", 1.0, "Real HMAC with a deterministic native-call barrier");
            put("Mac.HmacSHA256", BarrierMac.class.getName());
        }
    }

    public static final class BarrierMac extends MacSpi {
        private final Mac delegate;
        public BarrierMac() {
            try { delegate = Mac.getInstance("HmacSHA256", "SunJCE"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
        protected int engineGetMacLength() { return delegate.getMacLength(); }
        protected void engineInit(Key key, AlgorithmParameterSpec parameters) throws java.security.InvalidKeyException, java.security.InvalidAlgorithmParameterException { delegate.init(key, parameters); }
        protected void engineUpdate(byte value) { delegate.update(value); }
        protected void engineUpdate(byte[] bytes, int start, int length) { delegate.update(bytes, start, length); }
        protected byte[] engineDoFinal() {
            Runnable barrier = nativeBarrier;
            if (barrier != null) { nativeBarrier = null; barrier.run(); }
            return delegate.doFinal();
        }
        protected void engineReset() { delegate.reset(); }
    }

    static JSONObject clone(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static JSONObject source(int kind, String text) throws Exception {
        return new JSONObject().put("kind", kind).put("bytes", PurpleArchiveCore.base64(text.getBytes(StandardCharsets.UTF_8)));
    }
    static PurpleArchiveSource.Prepared prepare(TLRPC.Message message, String version) throws Exception {
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(message), null)) {
            return new PurpleArchiveSource.Prepared(slice, version == null ? slice.head.getString("id") : version);
        }
    }
    static JSONObject call(String operation, PurpleArchiveSource.Prepared prepared, JSONObject changes) throws Exception {
        JSONObject captured = new JSONObject(prepared.request);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(prepared.store, captured.getJSONObject("key"), null)) {
            slice.version(captured.getString("version"));
            JSONObject args = prepared.arguments(slice).put("extent", 0).put("target", 1);
            for (String field : changes.keySet()) args.put(field, changes.get(field));
            return PurpleArchiveCore.call(operation, args, prepared.store.crypto());
        }
    }
    static void invalid(String operation, PurpleArchiveSource.Prepared prepared, JSONObject changes, String label) throws Exception {
        JSONObject result;
        try { result = call(operation, prepared, changes); }
        catch (Exception e) { check(true, label + " fails closed at decode"); return; }
        check(!result.getString("status").equals("Ready"), label + " fails closed");
        check(result.optJSONArray("statements") == null || result.getJSONArray("statements").length() == 0, label + " has no write program");
    }
    static void commit(JSONObject plan) throws Exception {
        check(plan.getString("status").equals("Ready"), "real source adapter produces a valid SQL program");
        check(worker.store.execute(new PurpleArchiveCore.Program(plan)).committed, "real Store commits the adapter program");
    }
    static void basic() throws Exception {
        on(() -> {
            TLRPC.Message message = message(9300, "normalized", -1);
            JSONObject before = capture(message);
            PurpleArchiveSource.Prepared prepared = prepare(message, null);
            JSONObject facts = call("sourceFacts", prepared, new JSONObject());
            check(facts.getString("status").equals("Ready"), "exact sourceFacts reaches the compiled adapter");
            check(facts.getString("version").equals(before.getString("head")) && facts.getString("revision").equals(before.getString("revisionId")), "source facts preserve exact version and revision");
            PurpleArchiveSource.Choice absent = PurpleArchiveSource.choose(prepared, 0, true);
            check(!absent.genuine && !absent.partial, "legacy source absence chooses complete structural output");
            String[] fields = {"version", "revision", "hash", "representation", "accountGeneration", "chatGeneration"};
            for (String field : fields) {
                Object wrong = field.endsWith("Generation") ? "18446744073709551614" : field.equals("hash") ? hash(new byte[]{9}) : "wrong";
                JSONObject changes = new JSONObject().put(field, wrong);
                if (!field.equals("version")) check(call("sourceFacts", prepared, changes).getString("status").equals("Ready"), "facts derive identity instead of trusting supplied " + field);
                else invalid("sourceFacts", prepared, changes, "facts wrong version");
                invalid("sourceChoose", prepared, changes, "choice wrong " + field);
                invalid("sourceBind", prepared, clone(changes).put("source", source(0, "**normalized**")), "bind wrong " + field);
            }
            for (String field : new String[]{"account", "messageId", "representation"}) {
                JSONObject wrongKey = new JSONObject(prepared.request).getJSONObject("key");
                wrongKey.put(field, field.equals("account") ? "1-22" : field.equals("messageId") ? "9301" : "translated-en");
                JSONObject changes = new JSONObject().put("key", wrongKey);
                invalid("sourceFacts", prepared, changes, "facts wrong key " + field);
                invalid("sourceChoose", prepared, changes, "choice wrong key " + field);
                invalid("sourceBind", prepared, clone(changes).put("source", source(0, "**normalized**")), "bind wrong key " + field);
            }
            try (PurpleMarkdownSource.Candidate candidate = PurpleMarkdownSource.compose("**normalized**", "normalized", PurpleArchiveSource.composeBody("normalized", new JSONArray()), false)) {
                check(candidate != null, "actual compose capture creates the source candidate");
                PurpleArchiveSource.bind(prepared, candidate, -77L, 808L, () -> true);
            }
            PurpleArchiveSource.Choice chosen = PurpleArchiveSource.choose(prepare(message, null), 0, false);
            check(chosen.genuine && chosen.text().equals("**normalized**"), "acknowledged compose source survives encrypted Store roundtrip byte exactly");
            JSONObject after = facts(message);
            for (String field : new String[]{"head", "revisionId", "order"}) check(before.getString(field).equals(after.getString(field)), "source acknowledgement adds no new " + field);
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(message), null)) {
                JSONObject binding = slice.head.getJSONObject("source").getJSONObject("binding");
                check(binding.getString("localMessageId").equals("-77") && binding.getString("randomId").equals("808"), "source retains exact local and random acknowledgement identities");
                check(slice.head.getString("revision").equals(before.getString("revisionId")), "source acknowledgement leaves retained revision unchanged");
            }
            check(!PurpleArchiveSource.choose(prepare(message, null), 1, false).genuine, "selection never borrows whole genuine source");
            check(!PurpleArchiveSource.choose(prepare(message, null), 2, false).genuine, "fragment never borrows whole genuine source");
            invalid("sourceBind", prepare(message, null), new JSONObject().put("source", source(0, "different")), "conflicting compose source");
            TLRPC.Message file = message(9302, "", 13);
            capture(file); PurpleArchiveSource.Prepared historical = prepare(file, null);
            JSONObject old = new JSONObject(historical.request);
            file.message = "new caption"; ++file.edit_date; JSONObject newer = capture(file);
            try (PurpleMarkdownSource.Candidate candidate = PurpleMarkdownSource.file("observed.md", "text/markdown", "document_" + (9000 + file.id), 13, "# historical\n".getBytes(StandardCharsets.UTF_8))) {
                check(candidate != null, "actual Markdown file bytes create a source candidate");
                PurpleArchiveSource.bind(historical, candidate, null, null, () -> true);
            }
            PurpleArchiveSource.Choice history = PurpleArchiveSource.choose(historical, 0, true);
            check(history.genuine && history.text().equals("# historical\n"), "Hydration attaches exact historical file after a newer head");
            invalid("sourceChoose", historical, new JSONObject().put("target", 0), "historical file cannot become current body");
            check(!PurpleArchiveSource.choose(prepare(file, null), 0, true).genuine, "new head never inherits historical source bytes");
            JSONObject finalFacts = facts(file);
            for (String field : new String[]{"head", "revisionId", "order"}) check(newer.getString(field).equals(finalFacts.getString(field)), "historical Hydration preserves newer " + field);
            try (PurpleArchiveSlice slice = new PurpleArchiveSlice(worker.store, key(file), null)) {
                check(slice.version(old.getString("version")).getString("revision").equals(old.getString("revision")), "historical Hydration preserves original revision");
                check(slice.head.getJSONObject("content").getString("caption").equals("new caption"), "historical attachment preserves new caption");
            }
            invalid("sourceBind", historical, new JSONObject().put("source", source(1, "conflict")), "conflicting historical file");
            TLRPC.Message cancelled = message(9303, "cancelled attach", -1);
            capture(cancelled); PurpleArchiveSource.Prepared pending = prepare(cancelled, null);
            JSONObject pendingPlan = call("sourceBind", pending, new JSONObject().put("source", source(0, "**cancelled attach**")));
            check(pendingPlan.getString("status").equals("Ready"), "unbound source has a valid attach before revocation");
            AtomicInteger authorityChecks = new AtomicInteger();
            check(!worker.store.execute(new PurpleArchiveCore.Program(pendingPlan), () -> authorityChecks.incrementAndGet() < 5).committed, "scope revocation during a real source transaction rolls back");
            check(authorityChecks.get() == 5, "source transaction entered before the scope was revoked");
            check(!PurpleArchiveSource.choose(prepare(cancelled, null), 0, true).genuine, "rolled-back source never becomes readable");
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(worker.store.directory.file.toPath())) {
                byte[] sentinel = "**normalized**".getBytes(StandardCharsets.UTF_8);
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) files.filter(java.nio.file.Files::isRegularFile)::iterator) {
                    byte[] ciphertext = java.nio.file.Files.readAllBytes(path);
                    boolean clear = false;
                    for (int offset = 0; offset <= ciphertext.length - sentinel.length; ++offset) {
                        if (Arrays.equals(sentinel, Arrays.copyOfRange(ciphertext, offset, offset + sentinel.length))) { clear = true; break; }
                    }
                    check(!clear, "genuine source remains encrypted in every Store artifact");
                }
            }
            return null;
        });
    }

    static void purge(String mode) throws Exception {
        TLRPC.Message message = message(9310, "purge target", -1);
        PurpleArchiveSource.Prepared prepared = on(() -> { capture(message); return prepare(message, null); });
        JSONObject plan = on(() -> call("sourceBind", prepared, new JSONObject().put("source", source(0, "**purge target**"))));
        if (mode.equals("attach-purge")) on(() -> { commit(plan); check(PurpleArchiveSource.choose(prepare(message, null), 0, true).genuine, "attached source is available before purge"); return null; });
        on(() -> { PurpleArchiveQueryCases.apply(key(message), null, 2, 0); return null; });
        try (PurpleArchivePurgeModel model = new PurpleArchivePurgeModel(PurpleArchiveMaintenanceCases.scope(PurpleArchiveMaintenanceCases.parent()), 0)) {
            PurpleArchivePurgeModel.Preview preview = PurpleArchiveMaintenanceCases.review(model);
            on(() -> {
                PurpleArchiveMaintenanceCases.beginOnly(PurpleArchiveMaintenanceCases.parent(), 0, preview);
                check(!worker.store.execute(new PurpleArchiveCore.Program(plan)).committed, "purge admission refuses a previously planned source attach");
                refuses(() -> PurpleArchiveSource.choose(prepared, 0, true), "purge admission refuses old source choice");
                refuses(() -> call("sourceBind", prepared, new JSONObject().put("source", source(0, "late"))), "purge admission refuses source adapter resnapshot");
                return null;
            });
            PurpleArchiveMaintenanceCases.complete();
        }
        on(() -> { refuses(() -> PurpleArchiveSource.choose(prepared, 0, true), "purged source never returns after Finish"); return null; });
    }

    static void race(String mode) throws Exception {
        TLRPC.Message message = message(9320, "racing source", -1);
        PurpleArchiveSource.Prepared prepared = on(() -> { capture(message); return prepare(message, null); });
        JSONObject choicePlan = on(() -> call("sourceBind", prepared, new JSONObject().put("source", source(0, "**racing source**"))));
        on(() -> { commit(choicePlan); return null; });
        TLRPC.Message unbound = message(9321, "pending source", -1);
        PurpleArchiveSource.Prepared pending = on(() -> { capture(unbound); return prepare(unbound, null); });
        JSONObject plan = on(() -> call("sourceBind", pending, new JSONObject().put("source", source(0, "**pending source**"))));
        check(plan.getString("status").equals("Ready") && plan.getJSONArray("statements").length() > 2, "separate unbound source has a real pending write program");
        PurpleArchiveMarkdown.Guard guard = PurpleArchiveMarkdown.guard(PurpleAccountFiles.capture(0));
        AtomicBoolean scope = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Security.insertProviderAt(new BarrierProvider(), 1);
        Future<Boolean> inFlight = worker.queue.submit(() -> {
            try {
                JSONObject captured = new JSONObject(prepared.request);
                try (PurpleArchiveSlice slice = new PurpleArchiveSlice(prepared.store, captured.getJSONObject("key"), null)) {
                    slice.version(captured.getString("version"));
                    JSONObject args = prepared.arguments(slice).put("extent", 0).put("target", 1);
                    nativeBarrier = () -> {
                        entered.countDown();
                        try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("native race release"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    };
                    JSONObject result = PurpleArchiveCore.call("sourceChoose", args, prepared.store.crypto());
                    return result.getString("status").equals("Ready");
                }
            } catch (Exception e) { return false; }
        });
        try {
            check(entered.await(10, TimeUnit.SECONDS), "choice is paused inside real JNI HMAC callback");
            if (mode.equals("logout")) { PurpleArchive.beforeLogout(0); UserConfig.getInstance(0).active = false; }
            else if (mode.equals("discard")) {
                PurpleArchive.RemovalRequest request = PurpleArchive.prepareRemoval(0);
                check(request != null && PurpleArchive.remove(request, () -> {}), "discard is accepted while source choice is inside JNI");
            } else if (mode.equals("scope")) scope.set(false);
            else if (mode.equals("selection")) PurpleArchiveViewerModel.selectionChanged(1);
            else throw new AssertionError("unknown source race");
        } finally { release.countDown(); }
        check(inFlight.get(20, TimeUnit.SECONDS), "real adapter completes a valid pre-revocation source snapshot");
        AtomicInteger published = new AtomicInteger();
        check(!guard.publish(scope::get, published::incrementAndGet) && published.get() == 0, "revocation blocks genuine-source clipboard publication after native completion");
        if (mode.equals("scope") || mode.equals("selection")) on(() -> {
            AtomicInteger calls = new AtomicInteger();
            check(!worker.store.execute(new PurpleArchiveCore.Program(plan), () -> scope.get() && guard.current() && calls.incrementAndGet() < 2).committed, "revoked scope or selection rejects a fresh source attach");
            check(!PurpleArchiveSource.choose(prepare(unbound, null), 0, true).genuine, "revoked pending attachment remains absent");
            return null;
        });
        else {
            try { check(!on(() -> prepared.store.execute(new PurpleArchiveCore.Program(plan)).committed), "logout or discard rejects queued source attach"); }
            catch (Exception e) { check(true, "logout or discard closes source attach authority"); }
            await(() -> worker.retired, "removal completes after native callback returns");
        }
        Security.removeProvider("ArchiveSourceBarrier");
    }

    public static void main(String[] args) throws Exception {
        String mode = args[2];
        System.load(args[0]); PurpleCore.ensureLoaded(); Security.addProvider(new PurpleArchivePlatformProvider());
        File root = new File(args[1]); check(root.mkdir(), "exclusive source fixture");
        ApplicationLoader.files = new File(root, "files"); check(ApplicationLoader.files.mkdir(), "exclusive source files");
        ApplicationLoader.applicationContext = new android.content.Context(); UserConfig.selectedAccount = 0;
        UserConfig.getInstance(0).active = true; UserConfig.getInstance(0).user = 11;
        PurpleArchiveLogin.source = slot -> "11111111111111111111111111111111";
        PurpleArchive.refresh(0, () -> {}); await(() -> PurpleArchive.status(0).state.equals("Ready"), "encrypted Store ready");
        worker = PurpleArchive.existing(PurpleAccountFiles.capture(0));
        try {
            if (mode.equals("basic")) basic();
            else if (mode.equals("attach-purge") || mode.equals("purge-attach")) purge(mode);
            else race(mode);
            System.out.println("SOURCE_ADAPTER mode=" + mode + " checks=" + checks);
        } finally {
            nativeBarrier = null;
            if (!worker.retired) on(() -> { if (worker.store != null) { worker.store.drainReaders(); worker.store.close(); worker.store = null; } return null; });
            worker.queue.shutdown();
        }
    }
}
