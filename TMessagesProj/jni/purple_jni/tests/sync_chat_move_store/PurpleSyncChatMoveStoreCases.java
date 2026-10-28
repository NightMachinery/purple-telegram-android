package org.telegram.messenger.purple;

import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class PurpleSyncChatMoveStoreCases {
    static int checks;
    static final String AUTH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static String BINDING;
    static JSONObject beginning;
    static byte[] initial, baseRecord;
    static final List<JSONObject> rows = new ArrayList<>();
    interface Attempt { void run() throws Exception; }
    static void check(boolean value, String reason) { ++checks; if (!value) throw new AssertionError(reason); }
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static byte[] decode(String value) { return PurpleSyncRetainedBases.decode(value); }
    static JSONObject json(byte[] value) throws Exception { return new JSONObject(new String(value, StandardCharsets.UTF_8)); }
    static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    static void expect(String status, Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + status); }
        catch (IOException error) { check(status.equals(error.getMessage()), "typed refusal " + status + ": " + error); }
    }
    static PurpleAccountFileSnapshot.Durable read(File file, int limit) throws Exception {
        return PurpleAccountFileSnapshot.durable("read", file, null, null, limit, 0);
    }
    static final class Fixture {
        final File root, cache;
        final AtomicBoolean current = new AtomicBoolean(true);
        final String binding, device;
        Fixture(File parent, String name, boolean state) throws Exception { this(parent, name, state ? initial : null); }
        Fixture(File parent, String name, byte[] state) throws Exception {
            root = new File(parent, name); PurpleModernSyncCases.directory(root); cache = new File(parent, name + "-cache.json");
            binding = state == null ? "" : json(state).getString("binding_token");
            device = state == null ? "test-a" : json(state).getString("created_device");
            if (state != null) PurpleModernSyncCases.write(new File(root, "state.json"), state);
        }
        PurpleAccountSyncStore owner() { return new PurpleAccountSyncStore(root, PurpleAccountSyncStore.NATIVE); }
        PurpleSyncChatMoveStore open() throws Exception { return open(AUTH); }
        PurpleSyncChatMoveStore open(String authority) throws Exception {
            PurpleSyncChatMoveStore value = new PurpleSyncChatMoveStore(root, owner(), 0, "0-777", binding, authority, device, cache, current::get);
            value.open(); return value;
        }
        void begin(PurpleSyncChatMoveStore store) throws Exception {
            store.retainBase(baseRecord);
            PurpleSyncChatMoveCore.Result result = store.begin(copy(beginning));
            check(result.status.equals("Valid") && store.journalBytes() != null, "begin persisted canonical journal");
        }
    }
    static void fileCases(File parent) throws Exception {
        File root = new File(parent, "file-cases"); PurpleModernSyncCases.directory(root);
        File file = new File(root, "state.json");
        PurpleAccountFileSnapshot.Durable absent = read(file, 4194304);
        check(absent.bytes == null, "descriptor absence snapshot");
        PurpleAccountFileSnapshot.durable("barrier", file, absent, null, 4194304, 0);
        PurpleAccountFileSnapshot.Durable original = PurpleAccountFileSnapshot.durable("write", file, absent, bytes("old"), 4194304, 0);
        expect("Changed", () -> PurpleAccountFileSnapshot.durable("write", file, absent, bytes("wrong"), 4194304, 0));
        for (int point = 1; point <= 7; ++point) {
            PurpleAccountFileSnapshot.Durable opening = read(file, 4194304); final int fault = point;
            expect("DurabilityFailed", () -> PurpleAccountFileSnapshot.durable("write", file, opening, bytes("new-" + fault), 4194304, fault));
            byte[] observed = read(file, 4194304).bytes;
            check(Arrays.equals(observed, point == 5 || point == 6 || point == 7 ? bytes("new-" + point) : opening.bytes), "fault boundary " + point);
            PurpleAccountFileSnapshot.durable("barrier", file, read(file, 4194304), null, 4194304, 0);
            check(Arrays.stream(root.list()).noneMatch(n -> n.startsWith(".carry-tmp-")), "only own unrenamed temporary cleaned");
        }
        PurpleAccountFileSnapshot.Durable sameBytes = read(file, 4194304);
        PurpleAccountFileSnapshot.durable("write", file, sameBytes, sameBytes.bytes, 4194304, 0);
        expect("Changed", () -> PurpleAccountFileSnapshot.durable("write", file, sameBytes, bytes("stale inode"), 4194304, 0));
        byte[] maximum = new byte[4194304]; Arrays.fill(maximum, (byte)'x');
        PurpleAccountFileSnapshot.Durable large = PurpleAccountFileSnapshot.durable("write", file, read(file, 4194304), maximum, 4194304, 0);
        check(Arrays.equals(maximum, large.bytes), "4MiB file token does not hit core JSON base64 limit");
        PurpleAccountFileSnapshot.durable("barrier", file, large, null, 4194304, 0);
        expect("RetentionFull", () -> read(file, 262144));
        File link = new File(root, "link.json"); Files.createSymbolicLink(link.toPath(), file.toPath());
        expect("InsecurePermissions", () -> read(link, 4194304));
        File hard = new File(root, "hard.json"); Files.createLink(hard.toPath(), file.toPath());
        expect("InsecurePermissions", () -> read(file, 4194304)); Files.delete(hard.toPath());
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
        expect("InsecurePermissions", () -> read(file, 4194304)); Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
        File parentLink = new File(parent, "linked-parent"); Files.createSymbolicLink(parentLink.toPath(), root.toPath());
        expect("AmbiguousFiles", () -> read(new File(parentLink, "state.json"), 4194304));
        Looper.mainThread = Thread.currentThread();
        try { expect("WrongThread", () -> read(file, 4194304)); } finally { Looper.mainThread = null; }
    }
    static void storeCases(File parent) throws Exception {
        Fixture fixture = new Fixture(parent, "operation", true); long identity;
        try (PurpleSyncChatMoveStore store = fixture.open()) {
            fixture.begin(store);
            try (PurpleAccountSyncStore other = fixture.owner()) { check(other.open(true, 0, "test-a").status == PurpleAccountSyncStore.Status.LockBusy, "existing account lock excludes ordinary writer"); }
            identity = store.sendIdentity("forward:chat:10:2", new SecureRandom());
            check(identity != 0 && identity == store.sendIdentity("forward:chat:10:2", new SecureRandom()), "stable send identity persisted before use");
            for (int index : new int[]{42, 44, 46, 47}) {
                JSONObject row = rows.get(index);
                check(Arrays.equals(store.journalBytes(), decode(row.getJSONObject("input").getString("journal"))), "fixture journal prerequisite " + index);
                PurpleSyncChatMoveCore.Result result = store.advance(row.getJSONObject("input").getJSONObject("event"));
                check(result.status.equals(row.getJSONObject("expected").getString("status")), "core transition status " + index + ": " + result.status);
                check(Arrays.equals(store.journalBytes(), decode(row.getJSONObject("expected").getString("canonical"))), "nonempty canonical persisted " + index);
            }
        }
        try (PurpleSyncChatMoveStore store = fixture.open()) {
            byte[] before = Files.readAllBytes(new File(fixture.root,"carry.json").toPath());
            PurpleSyncChatMoveCore.Result stale = store.advance(rows.get(42).getJSONObject("input").getJSONObject("event"));
            check(stale.status.equals("Invalid") && Arrays.equals(before, Files.readAllBytes(new File(fixture.root,"carry.json").toPath())),"stale revision cannot clear or replace current journal");
        }
        File journal = new File(fixture.root, "carry.json"); byte[] frozen = Files.readAllBytes(journal.toPath());
        try (PurpleSyncChatMoveStore reopened = fixture.open()) {
            check(identity == reopened.sendIdentity("forward:chat:10:2", new SecureRandom()), "restart keeps immutable send identity");
            check(reopened.ownBase().length > 0, "dirty text does not displace exact retained base");
            check(reopened.resume().fields.optString("canonical").length() != 0, "resume rebarriers actual reopened state");
        }
        try (PurpleAccountSyncStore ordinary = fixture.owner()) { check(ordinary.open(true, 0, "wrong-device").status == PurpleAccountSyncStore.Status.CarryPending, "carry preflight precedes changed-chat/device/stage recovery"); }
        expect("AccountUnbound", () -> fixture.open("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        byte[] before = Files.readAllBytes(journal.toPath()); JSONObject wrapper = json(before); wrapper.put("version", 2); PurpleModernSyncCases.write(journal, bytes(wrapper.toString()));
        expect("NewerState", fixture::open); check(Arrays.equals(Files.readAllBytes(journal.toPath()), bytes(wrapper.toString())), "newer journal never repaired or removed");
        PurpleModernSyncCases.write(journal, before); wrapper = json(before); wrapper.put("objects", new JSONObject()); PurpleModernSyncCases.write(journal, bytes(wrapper.toString()));
        expect("InvalidState", fixture::open); PurpleModernSyncCases.write(journal, before);
        wrapper = json(before); wrapper.put("extension", new JSONObject().put("future", "preserved")); PurpleModernSyncCases.write(journal, bytes(wrapper.toString()));
        try (PurpleSyncChatMoveStore store = fixture.open()) { store.sendIdentity("second", new SecureRandom()); }
        check(json(Files.readAllBytes(journal.toPath())).getJSONObject("extension").getString("future").equals("preserved"), "unknown wrapper member survives store write");
        try (PurpleSyncChatMoveStore store = fixture.open()) { fixture.current.set(false); expect("AccountUnbound", () -> store.sendIdentity("revoked", new SecureRandom())); }
        fixture.current.set(true);
        try (PurpleAccountSyncStore ordinary = fixture.owner()) { check(ordinary.open(true, 0, "test-a").status == PurpleAccountSyncStore.Status.CarryPending, "revocation releases exact account lock"); }
        Fixture orphan = new Fixture(parent, "orphan", true); PurpleModernSyncCases.directory(new File(orphan.root, "carry-objects"));
        expect("OrphanOperation", orphan::open);
        try (PurpleAccountSyncStore ordinary = orphan.owner()) { check(ordinary.open(true, 0, "test-a").status == PurpleAccountSyncStore.Status.CarryPending, "orphan artifacts fence ordinary restart"); }
        Fixture absent = new Fixture(parent, "absent", false);
        JSONObject fill = null; for (JSONObject row : rows) if (row.getString("op").equals("begin") && row.getJSONObject("input").isNull("state") && row.getJSONObject("expected").getString("status").equals("Valid")) { fill = row.getJSONObject("input"); break; }
        check(fill != null, "shared absent-state Fill fixture");
        try (PurpleSyncChatMoveStore store = absent.open()) { check(store.begin(copy(fill)).status.equals("Valid"), "actual absence fsync admits Fill"); check(store.stateBytes() == null && !new File(absent.root, "state.json").exists(), "Fill does not invent state"); }
    }
    static void transition(PurpleSyncChatMoveStore store, int index) throws Exception {
        JSONObject row = rows.get(index);
        check(Arrays.equals(store.journalBytes(), decode(row.getJSONObject("input").getString("journal"))), "exact prior journal " + index);
        PurpleSyncChatMoveCore.Result result = store.advance(row.getJSONObject("input").getJSONObject("event"));
        check(result.status.equals(row.getJSONObject("expected").getString("status")), "transition status " + index + ": " + result.status);
        check(Arrays.equals(store.journalBytes(), decode(row.getJSONObject("expected").getString("canonical"))), "exact persisted transition " + index);
    }
    static void cache(Fixture fixture, PurpleSyncChatMoveStore store, boolean complete) throws Exception {
        JSONObject journal = json(store.journalBytes()), mark = journal.getJSONObject("target_mark");
        PurpleSyncChatOrigin origin = new PurpleSyncChatOrigin(0, 0, 777, AUTH);
        PurpleSyncChatCache.Data data = new PurpleSyncChatCache.Data(new PurpleSyncChatCore.Cache(
                PurpleSyncChatCore.Peer.parse(mark.getString("chat"), 777), mark.getInt("id"), mark.getLong("date")),
                "retained title", null, new java.util.HashSet<>(Arrays.asList("config", "library")),
                complete ? new JSONObject() : new JSONObject().put("operation", journal.getString("operation")), true);
        PurpleModernSyncCases.write(complete ? fixture.cache : new File(fixture.root, "carry-cache.json"), PurpleSyncChatCache.encode(origin, AUTH, data));
    }
    static void imageCases(File parent) throws Exception {
        Fixture fixture = new Fixture(parent, "reserved-committed", decode(rows.get(83).getJSONObject("input").getString("state")));
        byte[] old = Files.readAllBytes(new File(fixture.root, "state.json").toPath());
        byte[] own = decode(rows.get(85).getJSONObject("input").getJSONObject("oldInventory").getJSONArray("messages").getJSONObject(0).getString("bytes"));
        try (PurpleSyncChatMoveStore store = fixture.open()) {
            store.retainBase(own);
            check(store.begin(copy(rows.get(83).getJSONObject("input"))).status.equals("Valid"), "own-reservation begin");
            transition(store, 84); transition(store, 86);
            check(Arrays.equals(old, store.stateBytes()) && !new File(fixture.root, "pending/config.json").exists(), "reserved image durable before touching ordinary state/stage");
            byte[] reserved = decode(rows.get(87).getJSONObject("input").getJSONObject("event").getString("state"));
            check(store.commitPreparedState().status.equals("Valid"), "core action admits exact own stage reservation");
            check(Arrays.equals(reserved, store.stateBytes()) && Arrays.equals(reserved, Files.readAllBytes(new File(fixture.root,"state.json").toPath())), "reserved image is exact core bytes without post-hash decoration");
            check(Arrays.equals(store.journalBytes(), decode(rows.get(87).getJSONObject("expected").getString("canonical"))), "own-stage durability acknowledgement follows both writes");
            for (int index : new int[]{88,89,91,93,95,97}) transition(store,index);
            cache(fixture,store,false); byte[] consent = Files.readAllBytes(new File(fixture.root,"carry-cache.json").toPath());
            transition(store,98);
            check(Arrays.equals(consent,Files.readAllBytes(new File(fixture.root,"carry-cache.json").toPath())), "cache witness preserves consent and warning bytes");
            byte[] committed = decode(rows.get(90).getJSONObject("expected").getString("canonical"));
            check(store.commitPreparedState().status.equals("Valid") && Arrays.equals(committed,store.stateBytes()), "committed image is exact prepared core bytes");
            check(new File(fixture.root,"pending/config.json").exists(), "committed state precedes stage retirement");
            check(store.retireStage().status.equals("Valid") && !new File(fixture.root,"pending/config.json").exists(), "only exact staged hash retired after durable state");
            cache(fixture,store,true); byte[] admitted = Files.readAllBytes(fixture.cache.toPath()); transition(store,101); transition(store,102);
            check(Arrays.equals(admitted,Files.readAllBytes(fixture.cache.toPath())), "completed-cache witness never overwrites baseline");
        }
        try (PurpleSyncChatMoveStore store = fixture.open()) { check(store.ownBase().length != 0, "committed base survives reopen independently of dirty settings"); }
        Fixture pending = new Fixture(parent,"pending-state",decode(rows.get(120).getJSONObject("input").getString("state")));
        PurpleModernSyncCases.write(new File(pending.root,"pending/config.json"),decode(rows.get(121).getJSONObject("input").getJSONObject("event").getString("stagedRecord")));
        try(PurpleSyncChatMoveStore store=pending.open()) {
            store.retainBase(baseRecord); check(store.begin(copy(rows.get(120).getJSONObject("input"))).status.equals("Valid"),"pending begin before ordinary cleanup");
            transition(store,121);
            byte[] image=decode(rows.get(125).getJSONObject("input").getString("state"));
            check(store.commitPreparedState().status.equals("Valid") && Arrays.equals(image,store.stateBytes()),"pending prepared image committed with truthful barriers");
        }
    }
    static void journalFailures(File parent) throws Exception {
        for(int fault=1;fault<=7;++fault) {
            Fixture fixture=new Fixture(parent,"journal-fault-"+fault,true);
            try(PurpleSyncChatMoveStore store=fixture.open()) { fixture.begin(store);store.fault(fault);expect("DurabilityFailed",()->store.sendIdentity("not-returned",new SecureRandom())); }
            JSONObject wrapper=json(Files.readAllBytes(new File(fixture.root,"carry.json").toPath()));
            boolean renamed=fault==5||fault==6||fault==7;
            check(wrapper.getJSONObject("send_identities").has("not-returned")==renamed,"journal failure keeps exact rename boundary "+fault);
            try(PurpleSyncChatMoveStore store=fixture.open()) {
                long id=store.sendIdentity("not-returned",new SecureRandom());
                check(id!=0 && (!renamed||Long.toString(id).equals(wrapper.getJSONObject("send_identities").getString("not-returned"))),"reopen rebarriers visible journal and retains mapping "+fault);
            }
        }
        Fixture superseded=new Fixture(parent,"superseded",true);
        try(PurpleSyncChatMoveStore store=superseded.open()) { superseded.begin(store);transition(store,41); }
        try(PurpleSyncChatMoveStore store=superseded.open()) { check(Arrays.equals(store.journalBytes(),decode(rows.get(41).getJSONObject("expected").getString("canonical"))),"non-Valid Superseded canonical survives restart"); }
        Fixture review=new Fixture(parent,"needs-review",true);
        try(PurpleSyncChatMoveStore store=review.open()) { review.begin(store);for(int index:new int[]{42,240,241,242,244,246,248,250})transition(store,index); }
        try(PurpleSyncChatMoveStore store=review.open()) { check(Arrays.equals(store.journalBytes(),decode(rows.get(250).getJSONObject("expected").getString("canonical"))),"non-Valid NeedsReview canonical survives restart"); }
        Fixture throwing=new Fixture(parent,"throwing-admission",true);
        AtomicBoolean failAdmission=new AtomicBoolean();
        try(PurpleSyncChatMoveStore store=new PurpleSyncChatMoveStore(throwing.root,throwing.owner(),0,"0-777",throwing.binding,AUTH,throwing.device,throwing.cache,()->{if(failAdmission.get())throw new IllegalStateException("fixture admission failed");return true;})) {
            store.open();throwing.begin(store);failAdmission.set(true);expect("InvalidState",()->store.sendIdentity("throw",new SecureRandom()));
        }
        try(PurpleAccountSyncStore other=throwing.owner()){check(other.open(true,0,"test-a").status==PurpleAccountSyncStore.Status.CarryPending,"throwing admission releases account lock");}
    }
    static void lockCases(File parent) throws Exception {
        Fixture fixture=new Fixture(parent,"locks",true);
        try(PurpleSyncChatMoveStore store=fixture.open()) {
            fixture.begin(store);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            Thread contender=new Thread(()->{try(PurpleAccountSyncStore other=fixture.owner()) { if(other.open(true,0,"test-a").status!=PurpleAccountSyncStore.Status.LockBusy)throw new AssertionError("contender admitted"); }catch(Throwable e){failure.set(e);}});
            contender.start();contender.join(3000);
            check(!contender.isAlive()&&failure.get()==null&&java.lang.management.ManagementFactory.getThreadMXBean().findDeadlockedThreads()==null,"two-thread account/store lock probe completes without deadlock");
            Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),"-cp",System.getProperty("java.class.path"),PurpleSyncChatMoveStoreCases.class.getName(),"--lock",new File(fixture.root,"lock").getPath()).inheritIO().start();
            check(child.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)&&child.exitValue()==0,"another OS process cannot acquire held FileChannel lock");
            File lock = new File(fixture.root,"lock");Files.move(lock.toPath(),new File(parent,"displaced-lock").toPath());PurpleModernSyncCases.write(lock,new byte[0]);
            expect("Changed",()->store.sendIdentity("replaced-lock",new SecureRandom()));
        }
        try(PurpleAccountSyncStore other=fixture.owner()) { check(other.open(true,0,"test-a").status==PurpleAccountSyncStore.Status.CarryPending,"close releases OS/RAM lock but durable operation still fences writer"); }
    }
    static void corruptCases(File parent) throws Exception {
        Fixture fixture=new Fixture(parent,"corrupt",true);try(PurpleSyncChatMoveStore store=fixture.open()){fixture.begin(store);}
        File journal=new File(fixture.root,"carry.json");byte[] original=Files.readAllBytes(journal.toPath());JSONObject wrapper=json(original);
        wrapper.put("account","0-778");PurpleModernSyncCases.write(journal,bytes(wrapper.toString()));expect("AccountUnbound",fixture::open);
        PurpleModernSyncCases.write(journal,bytes("{\"version\":1,\"version\":1}"));expect("InvalidState",fixture::open);
        PurpleModernSyncCases.write(journal,original);File[] objects=new File(fixture.root,"carry-objects").listFiles();File object=objects[0];byte[] saved=Files.readAllBytes(object.toPath());
        PurpleModernSyncCases.write(object,bytes("corrupt"));expect("InvalidState",fixture::open);PurpleModernSyncCases.write(object,saved);
        File foreign=new File(fixture.root,"carry-objects/foreign.json");PurpleModernSyncCases.write(foreign,bytes("foreign"));expect("AmbiguousFiles",fixture::open);Files.delete(foreign.toPath());
        JSONObject ids=new JSONObject();for(int i=1;i<=4096;++i)ids.put("request-"+i,Integer.toString(i));wrapper=json(original).put("send_identities",ids);PurpleModernSyncCases.write(journal,bytes(wrapper.toString()));
        try(PurpleSyncChatMoveStore store=fixture.open()){check(store.sendIdentity("request-1",new SecureRandom())==1,"full map retains existing id");expect("RetentionFull",()->store.sendIdentity("overflow",new SecureRandom()));}
        ids.put("duplicate","1");wrapper.put("send_identities",ids);PurpleModernSyncCases.write(journal,bytes(wrapper.toString()));expect("RetentionFull",fixture::open);
        PurpleModernSyncCases.write(journal,original);try(PurpleSyncChatMoveStore store=fixture.open()){expect("InvalidTransition",()->store.sendIdentity("all-zero",new SecureRandom(){@Override public long nextLong(){return 0;}}));}
    }
    static void writerCases(File parent) throws Exception {
        PurpleModernSyncCases.Frame frame=new PurpleModernSyncCases.Frame(parent,"normal-writer-fences",true);frame.initialize(1);
        File carry=new File(frame.sync(1),"carry.json");
        try(PurpleAccountSyncStore owner=frame.env(1).store()) {
            check(owner.open(true,1,PurpleModernSyncCases.device(1)).status==PurpleAccountSyncStore.Status.Ready,"ordinary owner open before artifact");
            PurpleModernSyncCases.write(carry,bytes("malformed but authoritative hold"));
            check(owner.commitConfigState(frame.state(1)).status!=PurpleAccountSyncStore.Status.Ready,"already-open commit rechecks carry hold");
            check(!owner.compareAuto(null,bytes("{}"),()->true),"automatic checkpoint cannot escape carry hold");
        }
        java.util.Map<File,byte[]> before=PurpleSyncRecoveryCases.files(frame.sync(1));
        for(int i=0;i<2;++i) {
            try(PurpleAccountSyncStore owner=frame.env(1).store()) {
                check(owner.open(true,1,"changed-device").status==PurpleAccountSyncStore.Status.CarryPending,"ordinary reopen holds before cleanup "+i);
                check(owner.initialize(frame.state(1)).status==PurpleAccountSyncStore.Status.CarryPending,"initialization cannot escape carry hold");
                check(owner.stageConfig(baseRecord,frame.state(1)).status!=PurpleAccountSyncStore.Status.Ready,"stage writer held across restart");
                check(owner.confirmConfigReadBack(baseRecord,1).status!=PurpleAccountSyncStore.Status.Ready,"confirmation held across restart");
                check(owner.commitConfigState(frame.state(1)).status!=PurpleAccountSyncStore.Status.Ready,"state commit held across restart");
                check(!owner.commitAutomatic(frame.state(1),frame.state(1),bytes("{}"),true,()->true),"automatic state commit held across restart");
                check(!owner.compareAuto(null,bytes("{}"),()->true),"automatic checkpoint held across restart");
                check(owner.checkpointRetainedBases().status!=PurpleAccountSyncStore.Status.Ready,"retention checkpoint held across restart");
            }
            PurpleSyncRecoveryModel.Offer offer=PurpleSyncRecoveryCases.offer(frame,1,frame.clouds[1].inventory(),PurpleSyncRecoveryFiles.NATIVE_MOVES);
            check(offer==null||!offer.model.relink(offer)&&!offer.model.startAgain(offer,false),"relink/start-again cannot erase carry across restart "+i);
            check(!PurpleAccountSyncStore.recoverInterrupted(frame.root,frame.sync(1),PurpleModernSyncCases.B,PurpleModernSyncCases.PHYSICAL,()->true),"interrupted ordinary recovery refuses carry "+i);
        }
        PurpleSyncRecoveryCases.retained(before);frame.unchangedA();
    }
    static void retentionCases(File parent) throws Exception {
        check(PurpleSyncRetainedBases.request("base", initial, null).status.equals("MissingLocalVersion"), "legacy missing base is visible");
        PurpleSyncRetainedBases.Result found = PurpleSyncRetainedBases.request("find", initial, null, baseRecord);
        check(found.ready() && Arrays.equals(found.record, baseRecord), "exact matching verified version and lineage bootstrap");
        check(PurpleSyncRetainedBases.request("find", initial, null, bytes("version=1\n# dirty text")).status.equals("MissingLocalVersion"), "dirty text cannot impersonate old record");
        JSONObject extension = json(found.state); extension.getJSONObject("retained_config_bases").put("future", "keep");
        byte[] extended = PurpleAccountSyncCore.inspectState(bytes(extension.toString())).state;
        check(PurpleSyncRetainedBases.request("compact", extended, null).ready(), "unknown retention fields admitted");
        byte[] compact = PurpleSyncRetainedBases.request("compact", extended, null).state;
        check(json(compact).getJSONObject("retained_config_bases").getString("future").equals("keep"), "compaction preserves unknown retention metadata");
        JSONObject newer = json(found.state); newer.getJSONObject("retained_config_bases").put("version", 2);
        check(PurpleSyncRetainedBases.request("inspect", PurpleAccountSyncCore.inspectState(bytes(newer.toString())).state, null).status.equals("NewerRetention"), "newer retention refuses without truncation");
        JSONObject invalid = json(found.state); invalid.getJSONObject("retained_config_bases").getJSONArray("records").getJSONObject(0).put("sha256", AUTH);
        check(PurpleSyncRetainedBases.request("inspect", PurpleAccountSyncCore.inspectState(bytes(invalid.toString())).state, null).status.equals("InvalidRetention"), "corrupt retained hash refuses");
        byte[] bounded = found.state;
        PurpleAccountSyncCore.Result state = PurpleAccountSyncCore.inspectState(initial);
        for (int i = 0; i < 15; ++i) {
            byte[] text = bytes("version=1\n# retained base " + i + "\n");
            byte[] record = PurpleAccountSyncCore.buildConfigRecord(text, new byte[0][], state.space, state.install, "test-a", "Android", "storage fixture", i+2, 1700000000L+i).record;
            PurpleSyncRetainedBases.Result added = PurpleSyncRetainedBases.request("retain", bounded, null, record);
            check(added.ready(), "retained record admitted within bound " + i); bounded = added.state;
        }
        byte[] overflow = PurpleAccountSyncCore.buildConfigRecord(bytes("version=1\n# seventeenth\n"), new byte[0][], state.space, state.install, "test-a", "Android", "storage fixture", 18, 1700000018L).record;
        check(PurpleSyncRetainedBases.request("retain", bounded, null, overflow).status.equals("RetentionFull"), "retained record count never truncates");
        PurpleSyncRetainedBases.Result compacted = PurpleSyncRetainedBases.request("compact", bounded, null);
        check(compacted.ready() && json(compacted.state).getJSONObject("retained_config_bases").getJSONArray("records").length()==1, "idle compaction keeps exact current base");
        File root = new File(parent, "retained-files"); PurpleModernSyncCases.directory(root); PurpleSyncRetainedFiles.materialize(root, found.state);
        check(PurpleSyncRetainedFiles.records(root).length == 1 && Arrays.equals(PurpleSyncRetainedFiles.records(root)[0], baseRecord), "worker checkpoint materializes exact immutable base and barriers");
    }
    static void checkpointCases(File parent) throws Exception {
        PurpleModernSyncCases.Frame frame=new PurpleModernSyncCases.Frame(parent,"idle-retention-checkpoint",true);frame.initialize(1);
        byte[] record=PurpleAccountSyncCore.buildConfigRecord(bytes("version=1\n# idle retained\n"),new byte[0][],frame.space(1),PurpleModernSyncCases.install(1),PurpleModernSyncCases.device(1),"Android","checkpoint fixture",1,1700000001L).record;
        byte[] retained=PurpleSyncRetainedBases.request("retain",frame.state(1),null,record).state;
        File state=new File(frame.sync(1),"state.json"),automatic=new File(frame.sync(1),"auto.json");PurpleModernSyncCases.write(state,retained);
        PurpleModernSyncCases.write(automatic,bytes("{\"version\":2,\"active\":false,\"expectedState\":\"\",\"record\":\"\"}"));
        try(PurpleAccountSyncStore store=frame.env(1).store()) {check(store.open(true,1,PurpleModernSyncCases.device(1)).status==PurpleAccountSyncStore.Status.Ready && Arrays.equals(retained,store.stateBytes()),"newer automatic record defers retention compaction without reclassification");}
        Files.delete(automatic.toPath());
        try(PurpleAccountSyncStore store=frame.env(1).store()) {check(store.open(true,1,PurpleModernSyncCases.device(1)).status==PurpleAccountSyncStore.Status.Ready,"idle worker checkpoint opens");check(json(store.stateBytes()).getJSONObject("retained_config_bases").getJSONArray("records").length()==0,"idle checkpoint compacts only after immutable files materialize");}
        check(PurpleSyncRetainedFiles.records(frame.sync(1)).length==1 && Arrays.equals(record,PurpleSyncRetainedFiles.records(frame.sync(1))[0]),"compaction keeps exact historical bytes on disk");
        Looper.mainThread=Thread.currentThread();try{expect("WrongThread",()->PurpleSyncRetainedFiles.records(frame.sync(1)));}finally{Looper.mainThread=null;}
        frame.unchangedA();
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--lock")) {
            try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(args[1],"rw")) {
                java.nio.channels.FileLock lock=file.getChannel().tryLock();if(lock!=null){lock.release();throw new AssertionError("OS lock not held");}
            }
            return;
        }
        for (String line : Files.readAllLines(new File(args[0]).toPath(), StandardCharsets.UTF_8)) rows.add(new JSONObject(line));
        for (JSONObject row : rows) if (row.getString("op").equals("begin") && row.getJSONObject("expected").getString("status").equals("Valid")) { beginning = row.getJSONObject("input"); break; }
        initial = decode(beginning.getString("state")); BINDING = new String(decode(beginning.getString("preferenceToken")), StandardCharsets.UTF_8); baseRecord = decode(rows.get(0).getJSONObject("input").getString("stagedRecord"));
        File parent = new File(args[1]); PurpleModernSyncCases.directory(parent);
        PurpleModernSyncCases.Frame frame = new PurpleModernSyncCases.Frame(parent, "routing", true);
        fileCases(frame.root); retentionCases(frame.root); storeCases(frame.root); imageCases(frame.root); journalFailures(frame.root); lockCases(frame.root); corruptCases(frame.root); writerCases(frame.root); checkpointCases(frame.root);
        System.out.println("SYNC_CHAT_MOVE_STORE_CHECKS=" + checks);
    }
}
