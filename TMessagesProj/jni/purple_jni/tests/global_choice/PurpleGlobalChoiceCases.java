package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PurpleGlobalChoiceCases {
    private static int checks;
    private static final String A = "0-101", B = "0-202";
    private static final String LOCAL = "# 🌻 retained\nroot_file_account = \"0-101\" # owner\nglobal_account = \"0-101\" # source\n# That account's file on this device: settings.toml\nunknown = \"日本語\"\n[account_work_mode]\n\"0-202\" = \"follow\" # retain\n\"1-303\" = \"stock\"\n";
    private static final String OLD = "version=1\n[presets.work]\nlists=[]\n[premium]\nenabled=false\n";
    private static final String EMPTY = "version=2\n[last_seen]\nreasons_p=false\n";
    private static final String STATE = "active_preset=\"work\"\nactive_preset_source=\"manual\"\nlast_sent_fingerprint=\"inert-sent\"\nlast_imported_fingerprint=\"inert-imported\"\n";
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); ++checks; }
    private static void write(File f, String text) throws Exception { Files.createDirectories(f.toPath().getParent()); Files.write(f.toPath(), bytes(text)); }
    private static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    private static final PurpleGlobalChoiceFiles.Physical PHYSICAL = new PurpleGlobalChoiceFiles.Physical() {
        public PurpleGlobalChoiceFiles.Mark mark(File f, boolean directory) throws Exception {
            if (!Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) return new PurpleGlobalChoiceFiles.Mark("missing", "", 0);
            BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || (directory ? !a.isDirectory() : !a.isRegularFile())) throw new IllegalStateException("unsafe fixture path");
            Map<String,Object> unix = Files.readAttributes(f.toPath(), "unix:dev,ino,mode,nlink,ctime", LinkOption.NOFOLLOW_LINKS);
            if (!directory && ((Number)unix.get("nlink")).intValue() != 1) throw new IllegalStateException("linked fixture file");
            return new PurpleGlobalChoiceFiles.Mark(unix.get("dev")+":"+unix.get("ino"),
                    directory ? "" : a.size()+":"+a.lastModifiedTime()+":"+unix.get("ctime"), ((Number)unix.get("mode")).intValue());
        }
        public boolean write(File f, PurpleGlobalChoiceFiles.Mark owned, byte[] bytes) {
            return PurpleGlobalChoiceFiles.writeNative(f.getPath(), owned.identity, bytes);
        }
    };
    private static final class Fixture implements PurpleGlobalChoiceModel.Context {
        final File root, local, state, selected;
        PurpleGlobalChoiceModel model;
        boolean alive = true, foreignAfterPublish, retireDuringNormal, retireDuringNotify;
        boolean injectAtFinalWrite, replaceTemporary;
        boolean injected;
        File foreignTemporary;
        int normal, activate, notify;
        long revision, role, generation, epoch;
        int gate;
        String global = A;
        final PurpleGlobalChoiceModel.Route route = new PurpleGlobalChoiceModel.Route(A,A,B,0,0,0,0,0);
        Fixture(File parent, String name, String target) throws Exception {
            root = new File(parent, name); check(root.mkdir(), "exclusive fixture root");
            local = new File(root, "local.toml"); state = new File(root, "state.toml");
            selected = new File(root, "accounts/"+B+"/settings.toml");
            write(local, LOCAL); write(new File(root,"accounts/root.toml"), "root_file_account=\""+A+"\"\n");
            write(new File(root,"settings.toml"), OLD); write(state, STATE);
            if (target != null) write(selected, target);
            write(new File(root,"sync-history/retained"), "inert history");
            write(new File(root,"sync/state.json"), "inert store");
            write(new File(root,"accounts/1-303/send-state.toml"), "inert fingerprint");
        }
        void preview() throws Exception { model = new PurpleGlobalChoiceModel(root,A,A,B,PHYSICAL,this); }
        public boolean current(boolean activated) {
            try {
                if (foreignAfterPublish && !injected && read(local).contains("global_account = \""+B+"\"")) {
                    injected = true; write(selected, "version=2\n# foreign writer\n");
                }
                if ((injectAtFinalWrite || replaceTemporary) && !injected) {
                    for (File f : root.listFiles()) if (f.getName().startsWith("choice-") && f.length() > 0) {
                        injected = true;
                        if (replaceTemporary) {
                            File foreign = new File(root,"foreign-replacement"); write(foreign,"foreign creator");
                            Files.move(foreign.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);
                            foreignTemporary = f;
                        }
                        alive = false;
                    }
                }
                return alive && route.current(activated, normal + activate, A, global, revision, role, generation, epoch, gate);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        public boolean normal() {
            ++normal;
            try {
                check(activate==0 && read(new File(root,"settings.toml")).equals(OLD), "Normal occurs before selected settings installation against retained old source");
                check(PurpleGlobalChoiceModel.stateMatchesNative(Files.readAllBytes(state.toPath()),"work"), "old real State remains work before Normal");
                write(state,model.after.getString("state"));
                check(PurpleGlobalChoiceModel.stateMatchesNative(Files.readAllBytes(state.toPath()),"normal"), "real parsed State reached Normal");
                ++generation; ++epoch; ++gate;
                if (retireDuringNormal) alive = false;
                return true;
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        public boolean activate(PurpleGlobalChoiceModel.Result result) {
            ++activate;
            try {
                if (model.enterNormal) check(normal==1 && PurpleGlobalChoiceModel.stateMatchesNative(Files.readAllBytes(state.toPath()),"normal"), "activation observes established Normal first");
                result.activated = true;
                global = B; ++revision; ++role; ++generation; ++epoch; ++gate;
                return true;
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        public void notifyChanged() { ++notify; if (retireDuringNotify) alive = false; }
    }
    private static Map<String,byte[]> files(File root) throws Exception {
        Map<String,byte[]> values = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(root.toPath())) {
            paths.filter(Files::isRegularFile).sorted().forEach(p -> {
                try { values.put(root.toPath().relativize(p).toString(),Files.readAllBytes(p)); }
                catch (Exception e) { throw new IllegalStateException(e); }
            });
        }
        return values;
    }
    private static void unchangedOthers(Map<String,byte[]> before, File root) throws Exception {
        Map<String,byte[]> after = files(root);
        check(before.keySet().equals(after.keySet()), "no settings/store/history/fingerprint files created or moved");
        for (String key : before.keySet()) if (!key.equals("local.toml") && !key.equals("state.toml")) {
            check(Arrays.equals(before.get(key),after.get(key)),"unrelated actual file retained "+key);
        }
    }
    private static void beforeOpen(File parent, boolean symlink) throws Exception {
        File root = new File(parent, symlink ? "before-open-symlink" : "before-open-regular");
        check(root.mkdir(), "exclusive pre-open fixture");
        File target = new File(root,"local.toml"), sentinel = new File(root,"sentinel");
        write(target,"retained local"); write(sentinel,"inert foreign sentinel");
        Files.setPosixFilePermissions(sentinel.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        int sentinelMode = PHYSICAL.mark(sentinel,false).mode;
        File[] replacement = new File[1];
        int[] replacementMode = new int[1];
        PurpleGlobalChoiceFiles.Physical boundary = new PurpleGlobalChoiceFiles.Physical() {
            public PurpleGlobalChoiceFiles.Mark mark(File f, boolean directory) throws Exception { return PHYSICAL.mark(f,directory); }
            public boolean write(File f, PurpleGlobalChoiceFiles.Mark owned, byte[] bytes) throws Exception {
                replacement[0] = f;
                if (symlink) {
                    Files.delete(f.toPath());
                    Files.createSymbolicLink(f.toPath(),sentinel.toPath());
                } else {
                    File foreign = new File(root,"foreign-before-open");PurpleGlobalChoiceCases.write(foreign,"foreign regular sentinel");
                    Files.setPosixFilePermissions(foreign.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
                    Files.move(foreign.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);
                    replacementMode[0] = PHYSICAL.mark(f,false).mode;
                }
                return PHYSICAL.write(f,owned,bytes);
            }
        };
        PurpleGlobalChoiceFiles.Publication result = PurpleGlobalChoiceFiles.replace(target,bytes("new choice"),boundary,()->true);
        check(replacement[0] != null && !result.changed && result.local == null && read(target).equals("retained local"),"actual pre-open replacement refuses without destination mutation");
        check(read(sentinel).equals("inert foreign sentinel") && PHYSICAL.mark(sentinel,false).mode==sentinelMode,"actual native no-follow descriptor writer preserves unrelated sentinel bytes and mode");
        if (symlink) check(Files.isSymbolicLink(replacement[0].toPath()),"foreign pre-open symlink remains on refusal");
        else check(read(replacement[0]).equals("foreign regular sentinel") && PHYSICAL.mark(replacement[0],false).mode==replacementMode[0],"foreign opened regular inode is neither truncated nor chmodded and remains on refusal");
    }
    private static void dialogCases() {
        PurpleGlobalChoiceDialog flow = new PurpleGlobalChoiceDialog();
        PurpleGlobalChoiceDialog.Token picker=flow.picker();Object pickerDialog=new Object();boolean[] pickerShowing={true};
        flow.bind(picker,pickerDialog,()->pickerShowing[0]);
        check(!flow.current(picker) && flow.shown(picker,pickerDialog) && flow.current(picker),"production dialog seam authorizes exact shown picker only");
        check(flow.handoff(picker),"live picker enters intentional confirmation handoff");
        PurpleGlobalChoiceDialog.Token confirmation=flow.confirmation();Object confirmationDialog=new Object();boolean[] confirming={true};
        flow.bind(confirmation,confirmationDialog,()->confirming[0]);flow.dismissed(picker);
        check(flow.shown(confirmation,confirmationDialog) && flow.current(confirmation) && !flow.current(picker),"old picker pre/delegated dismissal preserves newly shown confirmation and fences late picker callback");
        confirming[0]=false;flow.dismissed(confirmation);
        check(!flow.current(confirmation) && !flow.current(),"production pre/delegated confirmation dismissal retires late positive callback");
        PurpleGlobalChoiceDialog cancel = new PurpleGlobalChoiceDialog();PurpleGlobalChoiceDialog.Token canceled=cancel.picker();Object canceledDialog=new Object();
        cancel.bind(canceled,canceledDialog,()->true);cancel.shown(canceled,canceledDialog);cancel.dismissed(canceled);
        check(!cancel.current(canceled) && !cancel.handoff(canceled),"dismissed picker cannot reopen confirmation from retained item callback");
        PurpleGlobalChoiceDialog replacement = new PurpleGlobalChoiceDialog();PurpleGlobalChoiceDialog.Token replaced=replacement.picker();Object replacedDialog=new Object();boolean[] exactLive={true};
        replacement.bind(replaced,replacedDialog,()->exactLive[0]);replacement.shown(replaced,replacedDialog);exactLive[0]=false;
        check(!replacement.current(replaced),"actual live-dialog identity/showing seam rejects replacement before delegated dismissal");exactLive[0]=true;
        check(!replacement.current(replaced),"retired replaced dialog cannot regain authorization after A-B-A visibility");
        PurpleGlobalChoiceDialog failed = new PurpleGlobalChoiceDialog();PurpleGlobalChoiceDialog.Token notShown=failed.picker();Object failedDialog=new Object();failed.bind(notShown,failedDialog,()->true);
        check(!failed.shown(notShown,null) && !failed.current(notShown),"failed show retires token despite otherwise live originating context");
        PurpleGlobalChoiceDialog wrong = new PurpleGlobalChoiceDialog();PurpleGlobalChoiceDialog.Token wrongToken=wrong.picker();wrong.bind(wrongToken,new Object(),()->true);
        check(!wrong.shown(wrongToken,new Object()) && !wrong.current(wrongToken),"wrong returned dialog cannot authorize a click");
    }
    private static void repairCases(File parent) throws Exception {
        String future="version=999\n[premium]\nenabled_p=false\n", supported="version=2\n[premium]\nenabled_p=true\n";
        Fixture recovered=new Fixture(parent,"future-primary-supported-good",future);write(new File(recovered.selected.getPath()+".good"),supported);write(recovered.state,STATE.replace("work","normal"));recovered.preview();
        PurpleGlobalChoiceModel.ActivationSource source=recovered.model.activationSource();
        check(recovered.model.after.getBoolean("good") && recovered.model.after.getBoolean("premium") && source.good && !source.defaults,"actual supported good preview and production Gate activation policy agree on fallback");
        check(Arrays.equals(source.primary,bytes(future)) && Arrays.equals(source.settings,bytes(supported)),"actual Gate activation input preserves observed future primary separately and passes supported recovery bytes");
        JSONObject loaded=new JSONObject(PurpleGlobalChoiceModel.describeNative(source.settings,recovered.selected.getPath(),null,Files.readAllBytes(recovered.state.toPath())));
        check(loaded.getBoolean("ok") && loaded.getBoolean("premium"),"real native parser consumes selected activation bytes with the previewed distinct value");
        check(recovered.model.apply().ok,"supported-good selection completes production coordinator with Normal retained");
        Fixture primary=new Fixture(parent,"supported-primary-control","version=2\n[premium]\nenabled_p=false\n");write(new File(primary.selected.getPath()+".good"),supported);write(primary.state,STATE.replace("work","normal"));primary.preview();source=primary.model.activationSource();
        check(!source.good && !source.defaults && Arrays.equals(source.settings,Files.readAllBytes(primary.selected.toPath())) && !primary.model.after.getBoolean("premium"),"supported primary remains the actual activation source despite distinct valid good");
        Fixture missing=new Fixture(parent,"activation-default-control",null);missing.preview();source=missing.model.activationSource();check(source.defaults && !source.good && source.settings==null && source.primary==null,"actual default activation policy has no invented recovery or primary bytes");
        beforeOpen(parent,false);beforeOpen(parent,true);dialogCases();
    }
    private static boolean reloadPremium(PurpleGlobalChoiceReload policy, File file) throws Exception {
        return new JSONObject(PurpleGlobalChoiceModel.describeNative(policy.source.settings,file.getPath(),null,null)).getBoolean("premium");
    }
    private static void reloadCases(File parent) throws Exception {
        String future="version=999\n[premium]\nenabled_p=false\n", good="version=2\n[premium]\nenabled_p=true\n", off="version=2\n[premium]\nenabled_p=false\n";
        Fixture picked=new Fixture(parent,"chooser-then-queued-reload",future);File recovery=new File(picked.selected.getPath()+".good");write(recovery,good);write(picked.state,STATE.replace("work","normal"));picked.preview();
        check(picked.model.apply().ok,"genuine successful chooser precedes ordinary reload policy");
        PurpleGlobalChoiceReload queued=PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->true);
        check(queued!=null && queued.current() && queued.source.good && !queued.source.defaults && Arrays.equals(queued.source.settings,bytes(good)) && reloadPremium(queued,picked.selected),"actual production ordinary reload policy retains confirmed supported good and distinct value after own local write");
        PurpleGlobalChoiceReload ordinary=PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->true);
        check(ordinary!=null && ordinary.source.good && reloadPremium(ordinary,picked.selected),"another ordinary reload recomputes same supported recovery without relying on canceled callback or cached bytes");
        write(recovery,off);check(!queued.current() && !ordinary.current(),"changed relevant good bytes invalidate retained reload read");
        PurpleGlobalChoiceReload refreshed=PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->true);
        check(refreshed!=null && refreshed.source.good && !reloadPremium(refreshed,picked.selected),"fresh ordinary read installs current relevant good rather than stale confirmed bytes");
        write(picked.selected,off);check(!refreshed.current(),"changed primary invalidates recovery read");
        PurpleGlobalChoiceReload primary=PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->true);
        check(primary!=null && !primary.source.good && !primary.source.defaults && !reloadPremium(primary,picked.selected),"supported changed primary refreshes ordinary policy with truthful nonfallback value");
        write(recovery,good);check(primary.current(),"supported primary ignores irrelevant recovery maintenance just as chooser preview");
        Path replacement=new File(picked.root,"same-primary-replacement").toPath();Files.copy(picked.selected.toPath(),replacement);Files.move(replacement,picked.selected.toPath(),StandardCopyOption.REPLACE_EXISTING);
        check(!primary.current(),"same-byte physical primary replacement invalidates retained policy");
        primary=PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->true);check(primary!=null && primary.current() && !primary.source.good,"fresh primary physical identity is captured on ordinary reload");
        write(picked.selected,future);PurpleGlobalChoiceReload recovered=PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->true);check(recovered!=null && recovered.source.good,"future primary resolves only same-account supported good");
        replacement=new File(picked.root,"same-good-replacement").toPath();Files.copy(recovery.toPath(),replacement);Files.move(replacement,recovery.toPath(),StandardCopyOption.REPLACE_EXISTING);check(!recovered.current(),"same-byte relevant good physical replacement invalidates retained policy");
        recovered=PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->true);check(recovered!=null && recovered.current() && recovered.source.good,"fresh recovery identity is revalidated");
        long[] role={7};long captured=role[0];PurpleGlobalChoiceReload roleBound=PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->role[0]==captured);check(roleBound!=null,"role-bound ordinary reload captures current context");role[0]+=2;check(!roleBound.current(),"observed role A-B-A invalidates original ordinary read");
        Fixture account=new Fixture(parent,"changed-account-source",off);File alternate=new File(account.root,"accounts/1-303/settings.toml");write(alternate,off);File accountGood=new File(alternate.getPath()+".good");write(accountGood,good);long freshRole=role[0];PurpleGlobalChoiceReload next=PurpleGlobalChoiceReload.read(account.root,alternate,PHYSICAL,()->role[0]==freshRole);
        check(next!=null && !next.source.good && !reloadPremium(next,alternate) && Arrays.equals(next.source.settings,bytes(off)),"fresh account/root source never reuses prior account recovery bytes");
        Fixture missing=new Fixture(parent,"ordinary-defaults",null);PurpleGlobalChoiceReload defaults=PurpleGlobalChoiceReload.read(missing.root,missing.selected,PHYSICAL,()->true);check(defaults!=null && defaults.source.defaults && defaults.source.settings==null && !defaults.source.good && !missing.selected.getParentFile().exists(),"ordinary defaults create no account directory and report no invented recovery");
        Fixture unsupported=new Fixture(parent,"ordinary-unsupported-no-good",future);check(PurpleGlobalChoiceReload.read(unsupported.root,unsupported.selected,PHYSICAL,()->true)==null,"ordinary unsupported primary without supported same-account good refuses before installation");
        Files.delete(recovery.toPath());check(PurpleGlobalChoiceReload.read(picked.root,picked.selected,PHYSICAL,()->true)==null,"removed relevant recovery cannot reuse retained old good");
        Fixture malformed=new Fixture(parent,"ordinary-malformed",off);Files.write(malformed.selected.toPath(),new byte[]{(byte)0xc0});check(PurpleGlobalChoiceReload.read(malformed.root,malformed.selected,PHYSICAL,()->true)==null,"ordinary readerror/malformed UTF8 is refused rather than treated as defaults");
        int[] samples={0};check(PurpleGlobalChoiceReload.read(account.root,account.selected,PHYSICAL,()->++samples[0]<2)==null,"context changed during source capture refuses actual production read");
    }
    private static PurpleCore.Loaded realStateLoad(byte[] settings, byte[] state) {
        try {
            java.lang.reflect.Method method=PurpleCore.class.getDeclaredMethod("loadNative",byte[].class,byte[].class,String.class,String.class,String.class);
            method.setAccessible(true);
            return PurpleCore.Loaded.fromJson((String)method.invoke(null,settings,state,"inert-state-test","android","mobile"));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void progressCases(File parent) throws Exception {
        String supported="version=2\n[presets.work]\nlists=[]\n[premium]\nenabled_p=true\n";
        long deadline=System.currentTimeMillis()/1000+3600;
        byte[] live=bytes("active_preset=\"work\"\nactive_preset_source=\"manual\"\npeek_active=true\npeek_deadline_unix="+deadline+"\n");
        Fixture fixture=new Fixture(parent,"live-peek-state-progress",supported);
        PurpleGlobalChoiceReload selected=PurpleGlobalChoiceReload.read(fixture.root,fixture.selected,PHYSICAL,()->true);check(selected!=null,"supported primary source selected for genuine state resolution");
        long[] role={1};long captured=role[0];PurpleGlobalChoiceProgress accepted=PurpleGlobalChoiceProgress.accepted(fixture.root,fixture.selected,selected.source,PHYSICAL,()->role[0]==captured);
        check(accepted!=null && accepted.current(),"accepted supported primary binds long-lived state authority");
        PurpleCore.Loaded published=PurpleGlobalChoiceProgress.resolve(selected.source,live,PurpleGlobalChoiceCases::realStateLoad);
        check(published.ok && !published.normal && published.clock.peeking,"real original JNI TheGate and production Java Loaded begin non-Normal live Peek");
        write(fixture.selected,"[unfinished");check(PurpleGlobalChoiceReload.read(fixture.root,fixture.selected,PHYSICAL,()->true)==null,"fresh malformed source installation is refused");
        PurpleGlobalChoiceModel.ActivationSource stateSource=accepted.forState(bytes("[unfinished"));check(stateSource!=null && Arrays.equals(stateSource.settings,bytes(supported)) && Arrays.equals(stateSource.primary,bytes("[unfinished")),"same owned inode permits state progress with accepted supported settings and truthful observed fingerprint");
        byte[] expired=bytes("active_preset=\"work\"\nactive_preset_source=\"manual\"\npeek_active=true\npeek_deadline_unix=1\n");
        published=PurpleGlobalChoiceProgress.resolve(stateSource,expired,PurpleGlobalChoiceCases::realStateLoad);
        check(published.ok && !published.normal && !published.clock.peeking && published.premium,"real native expiry updates production Loaded publication without installing malformed/Premium-off defaults");
        published=PurpleGlobalChoiceProgress.resolve(stateSource,live,PurpleGlobalChoiceCases::realStateLoad);check(published.clock.peeking,"real JNI live Peek restored for explicit End control");
        PurpleCore.PeekChange stopped=PurpleCore.stopPeek(live);check(!stopped.refused && !stopped.peeking && stopped.text!=null,"actual original stopPeekNative produces stopped State against real TheGate");
        published=PurpleGlobalChoiceProgress.resolve(accepted.forState(bytes("[unfinished")),bytes(stopped.text),PurpleGlobalChoiceCases::realStateLoad);
        check(published.ok && !published.clock.peeking && !published.normal && published.premium,"actual stopped State resolves native gate and production Java Loaded through shared Gate publication seam");
        role[0]+=2;check(accepted.forState(bytes("[unfinished"))==null,"role A-B-A permanently retires retained state authority");role[0]=captured;check(!accepted.current(),"retired role authority cannot revive after context values return");
        write(fixture.selected,supported);selected=PurpleGlobalChoiceReload.read(fixture.root,fixture.selected,PHYSICAL,()->true);PurpleGlobalChoiceProgress physical=PurpleGlobalChoiceProgress.accepted(fixture.root,fixture.selected,selected.source,PHYSICAL,()->true);
        Path replaced=new File(fixture.root,"ownership-replacement").toPath();Files.write(replaced,bytes(supported));Files.move(replaced,fixture.selected.toPath(),StandardCopyOption.REPLACE_EXISTING);check(physical.forState(bytes("[unfinished"))==null,"source inode ownership replacement refuses old state authority");
        boolean[] sameAccount={true};PurpleGlobalChoiceProgress account=PurpleGlobalChoiceProgress.accepted(fixture.root,fixture.selected,selected.source,PHYSICAL,()->sameAccount[0]);sameAccount[0]=false;check(account.forState(null)==null,"account/root/file context loss refuses retained source rather than crossing ownership");
        Fixture fallback=new Fixture(parent,"supported-recovery-state", "version=999\n");write(new File(fallback.selected.getPath()+".good"),supported);selected=PurpleGlobalChoiceReload.read(fallback.root,fallback.selected,PHYSICAL,()->true);PurpleGlobalChoiceProgress recovery=PurpleGlobalChoiceProgress.accepted(fallback.root,fallback.selected,selected.source,PHYSICAL,()->true);
        check(recovery!=null && selected.source.good && PurpleGlobalChoiceProgress.resolve(selected.source,live,PurpleGlobalChoiceCases::realStateLoad).clock.peeking,"supported same-account recovery remains truthful genuine live-Peek source");
        write(new File(fallback.selected.getPath()+".good"),"[unfinished");stateSource=recovery.forState(bytes("version=999\n"));published=PurpleGlobalChoiceProgress.resolve(stateSource,expired,PurpleGlobalChoiceCases::realStateLoad);check(stateSource.good && !published.clock.peeking && published.premium,"same owned recovery content invalidity permits expiry without installing rejected primary or recovery bytes");
        replaced=new File(fallback.root,"recovery-replacement").toPath();Files.write(replaced,bytes(supported));Files.move(replaced,new File(fallback.selected.getPath()+".good").toPath(),StandardCopyOption.REPLACE_EXISTING);check(recovery.forState(null)==null,"recovery inode replacement retires authority");
        Fixture defaults=new Fixture(parent,"no-invented-default-authority",null);selected=PurpleGlobalChoiceReload.read(defaults.root,defaults.selected,PHYSICAL,()->true);PurpleGlobalChoiceProgress absent=PurpleGlobalChoiceProgress.accepted(defaults.root,defaults.selected,selected.source,PHYSICAL,()->true);write(defaults.selected,"[unfinished");check(absent.forState(bytes("[unfinished"))==null,"new source creator cannot manufacture default-state fallback authority");
    }
    private static void replaceHandoff(File file) throws Exception {
        Path temporary=new File(file.getParentFile(),file.getName()+".handoff-replacement").toPath();
        Files.write(temporary,Files.readAllBytes(file.toPath()));
        Files.move(temporary,file.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
    private static void handoffCases(File parent) throws Exception {
        String supported="version=2\n[presets.work]\nlists=[]\n[premium]\nenabled_p=true\n";
        byte[] expired=bytes("active_preset=\"work\"\nactive_preset_source=\"manual\"\npeek_active=true\npeek_deadline_unix=1\n");
        byte[] live=bytes("active_preset=\"work\"\nactive_preset_source=\"manual\"\npeek_active=true\npeek_deadline_unix="+(System.currentTimeMillis()/1000+3600)+"\n");
        Fixture fixture=new Fixture(parent,"bound-handoff-primary",supported);
        PurpleGlobalChoiceReload selected=PurpleGlobalChoiceReload.read(fixture.root,fixture.selected,PHYSICAL,()->true);
        check(selected!=null,"fresh supported source selected through production policy");
        PurpleGlobalChoiceProgress authority=PurpleGlobalChoiceProgress.accepted(fixture.root,fixture.selected,selected.source,PHYSICAL,()->true);
        check(authority!=null,"fresh accepted selection may establish initial authority");
        write(fixture.selected,"[unfinished");
        check(PurpleGlobalChoiceReload.read(fixture.root,fixture.selected,PHYSICAL,()->true)==null,"malformed same inode refuses fresh installation");
        PurpleGlobalChoiceProgress.Use use=authority.forStateUse(bytes("[unfinished"),()->true);
        check(use!=null && use.authority()==authority && Arrays.equals(use.source.primary,bytes("[unfinished")),"actual Gate use seam carries same authority and truthful fingerprint");
        PurpleCore.Loaded result=use.resolve(expired,PurpleGlobalChoiceCases::realStateLoad);
        check(result!=null && result.ok && !result.normal && !result.clock.peeking && result.premium,"bound handoff permits actual native expiry under same inode malformed content");
        check(use.authority()==authority,"successful publication retains original authority without new ownership capture");
        PurpleCore.PeekChange stopped=PurpleCore.stopPeek(live);
        check(!stopped.refused && !stopped.peeking && stopped.text!=null,"actual original native End produces stopped State");
        result=authority.forStateUse(bytes("[unfinished"),()->true).resolve(bytes(stopped.text),PurpleGlobalChoiceCases::realStateLoad);
        check(result!=null && result.ok && !result.clock.peeking && !result.normal && result.premium,"bound handoff resolves explicit End with supported settings");
        use=authority.forStateUse(bytes("[unfinished"),()->true);
        check(use!=null && use.current(),"original primary authority current at successful derivation boundary");
        replaceHandoff(fixture.selected);int[] calls={0};
        result=use.resolve(expired,(settings,state)->{calls[0]++;return realStateLoad(settings,state);});
        check(result==null && calls[0]==0 && use.authority()==null,"replacement after derivation refuses before native load and adoption");
        check(!authority.current() && authority.forStateUse(null,()->true)==null,"observed primary replacement permanently retires original authority");
        write(fixture.selected,supported);selected=PurpleGlobalChoiceReload.read(fixture.root,fixture.selected,PHYSICAL,()->true);
        check(selected!=null && selected.current(),"supported replacement has a genuinely fresh accepted selection");
        PurpleGlobalChoiceProgress fresh=PurpleGlobalChoiceProgress.accepted(fixture.root,fixture.selected,selected.source,PHYSICAL,()->true);
        check(fresh!=null && fresh!=authority && fresh.current(),"only fresh accepted supported selection creates replacement authority");
        result=fresh.forStateUse(bytes(supported),()->true).resolve(live,PurpleGlobalChoiceCases::realStateLoad);
        check(result!=null && result.clock.peeking && result.premium,"fresh authority adoption resolves genuine native live Peek");
        long[] role={1};long captured=role[0];PurpleGlobalChoiceProgress roleAuthority=PurpleGlobalChoiceProgress.accepted(fixture.root,fixture.selected,selected.source,PHYSICAL,()->role[0]==captured);
        use=roleAuthority.forStateUse(bytes(supported),()->true);check(use!=null,"role authority derived before role boundary");role[0]+=2;calls[0]=0;
        result=use.resolve(expired,(settings,state)->{calls[0]++;return realStateLoad(settings,state);});
        check(result==null && calls[0]==0 && use.authority()==null,"role change after derivation refuses native load and adoption");role[0]=captured;
        check(!roleAuthority.current(),"observed role ABA cannot revive retained authority");
        Fixture recovery=new Fixture(parent,"bound-handoff-recovery","version=999\n");File good=new File(recovery.selected.getPath()+".good");write(good,supported);
        selected=PurpleGlobalChoiceReload.read(recovery.root,recovery.selected,PHYSICAL,()->true);
        check(selected!=null && selected.source.good,"real policy selects supported own recovery");
        PurpleGlobalChoiceProgress goodAuthority=PurpleGlobalChoiceProgress.accepted(recovery.root,recovery.selected,selected.source,PHYSICAL,()->true);
        write(good,"[unfinished");use=goodAuthority.forStateUse(bytes("version=999\n"),()->true);
        check(use!=null && use.source.good,"same recovery inode permits bound derivation");
        replaceHandoff(good);calls[0]=0;result=use.resolve(expired,(settings,state)->{calls[0]++;return realStateLoad(settings,state);});
        check(result==null && calls[0]==0 && use.authority()==null && !goodAuthority.current(),"recovery replacement after derivation refuses and retires original ownership");
        PurpleGlobalChoiceProgress post=PurpleGlobalChoiceProgress.accepted(fixture.root,fixture.selected,fresh.source,PHYSICAL,()->true);
        use=post.forStateUse(bytes(supported),()->true);check(use!=null,"post-native boundary starts with current original token");
        result=use.resolve(expired,(settings,state)->{PurpleCore.Loaded loaded=realStateLoad(settings,state);try{replaceHandoff(fixture.selected);}catch(Exception e){throw new IllegalStateException(e);}return loaded;});
        check(result==null && use.authority()==null && !post.current(),"replacement during real native resolution refuses result and final retention");
        selected=PurpleGlobalChoiceReload.read(fixture.root,fixture.selected,PHYSICAL,()->true);PurpleGlobalChoiceProgress context=PurpleGlobalChoiceProgress.accepted(fixture.root,fixture.selected,selected.source,PHYSICAL,()->true);
        boolean[] revision={true};use=context.forStateUse(bytes(supported),()->revision[0]);check(use!=null,"per-load revision context begins current");revision[0]=false;calls[0]=0;
        result=use.resolve(expired,(settings,state)->{calls[0]++;return realStateLoad(settings,state);});
        check(result==null && calls[0]==0 && use.authority()==null,"short per-load revision loss refuses current use");
        check(context.current() && context.forStateUse(bytes(supported),()->true)!=null,"ordinary later load uses new short context without permanently pinning revision");
        use=context.forStateUse(bytes(supported),()->true);result=use.resolve(expired,PurpleGlobalChoiceCases::realStateLoad);check(result!=null,"publication boundary obtains genuine resolved result");replaceHandoff(fixture.selected);
        check(use.authority()==null && !context.current(),"replacement after native resolution prevents final authority publication and retention");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 2 && args[2].equals("handoff")) {
            System.load(args[0]);File parent=new File(args[1]);check(!parent.exists() && parent.mkdir(),"exclusive handoff fixture root");
            handoffCases(parent);
            System.out.println("REAL_GLOBAL_CHOICE_AUTHORITY_HANDOFF_CASES_COMPLETED checks="+checks);
            for(Class<?> type:new Class<?>[]{PurpleGlobalChoiceProgress.class,PurpleCore.class,PurpleCore.Loaded.class,PurpleGlobalChoiceCases.class,JSONObject.class})System.out.println("CLASS_ORIGIN "+type.getName()+" "+type.getProtectionDomain().getCodeSource().getLocation());
            return;
        }

        if (args.length > 2 && args[2].equals("progress")) {
            System.load(args[0]);File parent=new File(args[1]);check(!parent.exists() && parent.mkdir(),"exclusive state-progress fixture root");
            progressCases(parent);
            System.out.println("REAL_GLOBAL_CHOICE_STATE_PROGRESS_CASES_COMPLETED checks="+checks);
            for(Class<?> type:new Class<?>[]{PurpleGlobalChoiceProgress.class,PurpleCore.class,PurpleCore.Loaded.class,PurpleGlobalChoiceCases.class,JSONObject.class})System.out.println("CLASS_ORIGIN "+type.getName()+" "+type.getProtectionDomain().getCodeSource().getLocation());
            return;
        }
        if (args.length > 2 && args[2].equals("reload")) {
            System.load(args[0]);File parent=new File(args[1]);check(!parent.exists() && parent.mkdir(),"exclusive reload fixture root");
            reloadCases(parent);
            System.out.println("REAL_GLOBAL_CHOICE_RELOAD_POLICY_CASES_COMPLETED checks="+checks);
            for(Class<?> type:new Class<?>[]{PurpleGlobalChoiceReload.class,PurpleGlobalChoiceModel.class,PurpleGlobalChoiceFiles.class,PurpleGlobalChoiceCases.class,JSONObject.class})System.out.println("CLASS_ORIGIN "+type.getName()+" "+type.getProtectionDomain().getCodeSource().getLocation());
            return;
        }
        System.load(args[0]); File parent=new File(args[1]);check(!parent.exists() && parent.mkdir(),"exclusive owned fixture directory");
        Fixture valid=new Fixture(parent,"normal-success",EMPTY);Map<String,byte[]> before=files(valid.root);valid.preview();
        check(valid.model.after.getBoolean("noAppWide") && valid.model.enterNormal,"actual selected layered settings have no app-wide preset");
        check(valid.model.before.getInt("presets")==1 && valid.model.after.getInt("presets")==0,"actual native preview counts");
        PurpleGlobalChoiceModel.Result result=valid.model.apply();
        check(result.ok && result.changed && result.activated && valid.normal==1 && valid.activate==1 && valid.notify==1,"successful production commit/Normal/activation/notification order");
        String saved=read(valid.local);check(saved.contains("unknown = \"日本語\"") && saved.contains("# 🌻 retained") && saved.contains("\"0-202\" = \"follow\" # retain") && saved.contains("root_file_account = \"0-101\" # owner"),"Unicode/unknown/owner/own-follow preserved by native scalar splice");
        check(saved.contains("accounts/0-202/settings.toml") && read(valid.state).contains("inert-sent"),"managed path and existing fingerprints retained");unchangedOthers(before,valid.root);
        Fixture conflict=new Fixture(parent,"post-publication-refusal",EMPTY);conflict.preview();byte[] oldState=Files.readAllBytes(conflict.state.toPath());conflict.foreignAfterPublish=true;result=conflict.model.apply();
        check(conflict.injected && result.changed && !result.ok && !result.activated,"actual foreign selected-file write after local publication produces saved/refused result");
        check(conflict.normal==0 && conflict.activate==0 && conflict.notify==0 && Arrays.equals(oldState,Files.readAllBytes(conflict.state.toPath())),"detected failed commit has no State/Normal/Gate callback effects");
        check(read(conflict.local).contains(B) && read(conflict.selected).contains("foreign writer"),"saved local choice and foreign bytes are not rolled back");
        Fixture defaults=new Fixture(parent,"missing-defaults",null);defaults.preview();check(defaults.model.after.getBoolean("defaults") && !defaults.selected.exists(),"absent selected source previews defaults without creation");result=defaults.model.apply();check(result.ok && !defaults.selected.getParentFile().exists(),"defaults activation creates no account directory");
        Fixture fallback=new Fixture(parent,"own-good","[broken");write(new File(fallback.selected.getPath()+".good"),OLD);fallback.preview();check(fallback.model.after.getBoolean("good") && !fallback.model.enterNormal,"same-account valid good supplies actual preview");write(new File(fallback.selected.getPath()+".good"),EMPTY);check(!fallback.model.apply().changed,"changed relevant fallback refuses publication");
        Fixture irrelevant=new Fixture(parent,"irrelevant-good",OLD);irrelevant.preview();write(new File(irrelevant.selected.getPath()+".good"),"unrelated recovery maintenance");check(irrelevant.model.apply().ok,"usable primary does not bind irrelevant recovery maintenance");
        Fixture stale=new Fixture(parent,"retired-ui",EMPTY);stale.preview();String oldLocal=read(stale.local);stale.alive=false;result=stale.model.apply();check(!result.changed && stale.normal==0 && read(stale.local).equals(oldLocal),"retired originating UI refuses before local publication");
        Fixture normalRetire=new Fixture(parent,"normal-retirement",EMPTY);normalRetire.preview();normalRetire.retireDuringNormal=true;result=normalRetire.model.apply();check(result.changed && !result.ok && normalRetire.normal==1 && normalRetire.activate==0,"retirement during real Normal callback prevents activation");
        Fixture notified=new Fixture(parent,"notification-retirement",OLD);notified.preview();notified.retireDuringNotify=true;result=notified.model.apply();check(result.changed && result.activated && !result.ok && notified.notify==1,"postactivation notification retirement retains precise activated-but-incomplete result");
        Fixture replacement=new Fixture(parent,"same-bytes-replacement",EMPTY);replacement.preview();Path other=new File(replacement.root,"replacement").toPath();Files.copy(replacement.selected.toPath(),other);Files.move(other,replacement.selected.toPath(),StandardCopyOption.REPLACE_EXISTING);check(!replacement.model.apply().changed,"same-byte selected-file atomic replacement invalidates captured physical identity");
        Fixture finalGuard=new Fixture(parent,"after-fsync-retirement",EMPTY);finalGuard.preview();String initial=read(finalGuard.local);finalGuard.injectAtFinalWrite=true;result=finalGuard.model.apply();check(finalGuard.injected && !result.changed && read(finalGuard.local).equals(initial),"actual guarded writer refuses lifetime loss after write/fsync before rename");check(files(finalGuard.root).keySet().stream().noneMatch(k->k.startsWith("choice-")),"only owned unpublished temporary removed");
        Fixture foreignTemp=new Fixture(parent,"foreign-temp",EMPTY);foreignTemp.preview();foreignTemp.replaceTemporary=true;result=foreignTemp.model.apply();check(foreignTemp.injected && !result.changed && read(foreignTemp.foreignTemporary).equals("foreign creator"),"foreign creator replacing unpublished temporary is retained");
        Fixture malformed=new Fixture(parent,"invalid-utf8",EMPTY);Files.write(malformed.local.toPath(),new byte[]{(byte)0xc0,(byte)0xaf});boolean refused=false;try{malformed.preview();}catch(Exception e){refused=true;}check(refused,"strict production reader refuses malformed UTF8, not missing");
        Fixture unsafe=new Fixture(parent,"symlink",EMPTY);Files.delete(unsafe.selected.toPath());Files.createSymbolicLink(unsafe.selected.toPath(),new File(unsafe.root,"settings.toml").toPath());refused=false;try{unsafe.preview();}catch(Exception e){refused=true;}check(refused,"real physical adapter refuses linked selected input");
        Fixture directory=new Fixture(parent,"read-error",EMPTY);Files.delete(directory.selected.toPath());Files.createDirectory(directory.selected.toPath());refused=false;try{directory.preview();}catch(Exception e){refused=true;}check(refused,"nonregular/read-error is not missing/defaults");
        Fixture unsupported=new Fixture(parent,"unsupported","version=999\n");refused=false;try{unsupported.preview();}catch(Exception e){refused=true;}check(refused,"unsupported selected version without valid good refused");
        Fixture same=new Fixture(parent,"current-noop",OLD);PurpleGlobalChoiceModel noop=new PurpleGlobalChoiceModel(same.root,A,A,A,PHYSICAL,same);Map<String,byte[]> original=files(same.root);result=noop.apply();check(result.ok && !result.changed && same.normal==0 && same.activate==0,"current global account is a true no-op");check(Arrays.equals(original.get("local.toml"),Files.readAllBytes(same.local.toPath())),"no-op preserves exact local bytes");
        JSONObject testPlan=new JSONObject(PurpleGlobalChoiceModel.planNative(bytes(LOCAL),bytes("root_file_account=\"0-101\"\n"),A,A,"1-303"));check(testPlan.getBoolean("ok") && testPlan.getString("selectedPath").equals("accounts/1-303/settings.toml"),"actual shared account-path model distinguishes test environment");
        check(!new JSONObject(PurpleGlobalChoiceModel.planNative(new byte[]{(byte)0xc0},bytes("root_file_account=\"0-101\"\n"),A,A,B)).getBoolean("ok"),"native scalar planner itself rejects invalid UTF8");
        Object config=new Object(),connection=new Object(),manager=new Object();List<PurpleGlobalChoiceModel.Account> roster=Arrays.asList(new PurpleGlobalChoiceModel.Account(0,A,1,config,connection,manager));
        check(PurpleGlobalChoiceModel.sameRoster(roster,Arrays.asList(new PurpleGlobalChoiceModel.Account(0,A,1,config,connection,manager))),"production roster accepts same identity");
        check(!PurpleGlobalChoiceModel.sameRoster(roster,Arrays.asList(new PurpleGlobalChoiceModel.Account(0,A,3,config,connection,manager))),"production roster rejects same-key slot lifetime ABA");
        check(!PurpleGlobalChoiceModel.sameRoster(roster,Arrays.asList(new PurpleGlobalChoiceModel.Account(0,A,1,config,connection,new Object()))),"production roster rejects manager/session replacement");
        check(!PurpleGlobalChoiceModel.sameRoster(roster,Arrays.asList(new PurpleGlobalChoiceModel.Account(0,"1-101",1,config,connection,manager))),"production roster rejects environment replacement");
        Fixture aba=new Fixture(parent,"role-aba",EMPTY);aba.preview();String abaLocal=read(aba.local);aba.role+=2;check(!aba.model.apply().changed && read(aba.local).equals(abaLocal),"actual production route guard refuses observed A-B-A role epoch before publication");
        Fixture localForeign=new Fixture(parent,"local-foreign",EMPTY);localForeign.preview();write(localForeign.local,LOCAL+"# foreign local writer\n");check(!localForeign.model.apply().changed && read(localForeign.local).endsWith("# foreign local writer\n"),"captured local input refuses foreign pre-publication writer without rollback");
        Fixture ownerForeign=new Fixture(parent,"owner-foreign",EMPTY);ownerForeign.preview();write(new File(ownerForeign.root,"accounts/root.toml"),"root_file_account=\"0-999\"\n");check(!ownerForeign.model.apply().changed && ownerForeign.normal==0,"captured owner input rejects change before State/Gate effects");
        int originalChecks=checks;
        repairCases(parent);
        System.out.println("REAL_GLOBAL_CHOICE_THREE_REPAIR_CASES_COMPLETED originalChecks="+originalChecks+" totalChecks="+checks);
        System.out.println("REAL_GLOBAL_CHOICE_MODEL_WRITER_NATIVE_CASES_COMPLETED checks="+checks);
        for(Class<?> type:new Class<?>[]{PurpleGlobalChoiceModel.class,PurpleGlobalChoiceFiles.class,PurpleGlobalChoiceDialog.class,PurpleGlobalChoiceCases.class,JSONObject.class})System.out.println("CLASS_ORIGIN "+type.getName()+" "+type.getProtectionDomain().getCodeSource().getLocation());
    }
}
