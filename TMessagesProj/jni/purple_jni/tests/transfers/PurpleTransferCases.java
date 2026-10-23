package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PurpleTransferCases {
    private static int checks;
    private static final String GLOBAL = "# A exact 🌻\nversion=1\n[sync]\nsend_after_save_p=true\n[premium]\nenabled_p=true\n[lists.work]\ntitle=\"Work\"\nmembers=[11,22]\n[presets.work]\nchats=[\"work\"]\n";
    private static final String OWN = "# B exact 日本語\nversion=2\n[last_seen]\nreasons_p=true\n[sync]\nsend_after_save_p=true\n";
    private static byte[] bytes(String text) { return text == null ? null : text.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); ++checks; }
    private static void write(File file, String text) throws Exception { Files.createDirectories(file.toPath().getParent()); Files.write(file.toPath(), bytes(text)); }
    private static PurpleConfigImpact.Input input(String before, boolean global, int mode, String base) {
        return new PurpleConfigImpact.Input(bytes(before), global, mode, bytes(base), new String[0], new byte[0][], new int[0]);
    }
    private static JSONObject finding(JSONObject impact, int kind, String table, String key) {
        JSONArray findings = impact.optJSONArray("findings");
        if (findings != null) for (int i=0;i<findings.length();++i) {
            JSONObject item = findings.optJSONObject(i);
            if (item.optInt("kind") == kind && (table == null || table.equals(item.optString("table")))
                    && (key == null || key.equals(item.optString("key")))) return item;
        }
        return null;
    }
    private static void impactCases() throws Exception {
        String mixed = "version=2\n[last_seen]\nreasons_p=false\ntrade_remember=\"7d\"\n[playlists]\nmenu_item_p=false\n";
        JSONObject result = input("version=2\n",false,0,GLOBAL).analyze(bytes(mixed));
        check(PurpleConfigImpact.ready(result),"core impact ready for layered mixed tables");
        check(finding(result,0,"last_seen","reasons_p").optInt("afterUse")==1,"per-account key used here");
        check(finding(result,0,"last_seen","trade_remember").optInt("afterUse")==2,"same-table app-wide key used elsewhere");
        check(finding(result,0,"playlists","menu_item_p").optInt("afterUse")==2,"app-wide exception in per-account table");
        check(finding(result,1,"last_seen","reasons_p")!=null,"new own key is a pin");
        check(!result.optBoolean("dangerous"),"mixed harmless additions remain harmless");
        String members="version=2\n[lists.work]\nmembers=[55]\n";
        for(int mode=0;mode<3;++mode) {
            result=input("version=2\n",false,mode,GLOBAL).analyze(bytes(members));
            check(PurpleConfigImpact.ready(result),"member impact ready");
            check(finding(result,0,"lists.work","members").optInt("afterUse")== (mode==0?1:mode==1?3:4),"captured Own Follow Stock scope");
        }
        result=input("version=2\n",false,0,"version=1\n").analyze(bytes(members));
        check(finding(result,0,"lists.work","members").optInt("afterUse")==5,"undefined list is inactive");
        result=new PurpleConfigImpact.Input(bytes(GLOBAL),true,0,null,new String[]{"0-202"},new byte[][]{bytes(members)},new int[]{1}).analyze(bytes("version=2\n"));
        check(PurpleConfigImpact.ready(result)&&result.optBoolean("dangerous"),"global app-wide removal is dangerous");
        check(result.optBoolean("turnsWorkModeOff"),"global loss of all presets leads Work Mode off wording");
        check(finding(result,4,null,null)!=null&&finding(result,5,null,null)!=null,"list and preset losses named");
        check(finding(result,8,null,null)!=null,"global removal reports stranded own members even in Follow");
        result=input(GLOBAL,true,0,null).analyze(bytes(GLOBAL.replace("members=[11,22]","members=[]")));
        check(result.optBoolean("dangerous")&&finding(result,7,"lists.work",null)!=null,"empty defined list is dangerous");
        result=input("version=2\n[lists.work]\nmembers=[55]\n",false,0,GLOBAL).analyze(bytes("version=2\n[lists.work]\nmembers=[]\n"));
        check(!result.optBoolean("dangerous"),"members-only fork does not define an emptied list");
        check(input("[",true,0,null).analyze(bytes(OWN)).optInt("status")==1,"invalid current refuses");
        check(input(OWN,false,0,"[").analyze(bytes(OWN)).optInt("status")==3,"invalid global refuses");
        check(input(OWN,false,0,GLOBAL).analyze(bytes("[")).optInt("status")==2,"invalid proposed refuses");
        check(input(OWN,false,0,GLOBAL).analyze(bytes("version=4294967298\n")).optInt("status")==5,"large raw schema does not wrap to v1");
        check(new PurpleConfigImpact.Input(bytes(GLOBAL),true,0,null,new String[]{"B"},new byte[][]{bytes("[")},new int[]{0}).analyze(bytes(GLOBAL)).optInt("status")==4,"invalid loaded account blocks global impact");
        check(!PurpleConfigImpact.ready(new PurpleConfigImpact.Input(bytes(GLOBAL),true,0,null,new String[]{"B","B"},new byte[][]{null,null},new int[]{0,0}).analyze(bytes(GLOBAL))),"duplicate context refuses");
        check(PurpleConfigImpact.ready(input(null,false,0,GLOBAL).analyze(bytes(OWN))),"absent primary is explicit, not empty/fallback");
        check(PurpleCore.parse(bytes("[last_seen]\nreasons_p=false\n")).version==1,"omitted schema remains version 1");
        check(!"settings.toml".equals(PurpleTransferRules.exportName(2)),"shipped filename predicate cannot discover layered export");
        check("settings.toml".equals(PurpleTransferRules.exportName(1)),"whole exports keep shipped filename");
        check(PurpleTransferRules.fileName("ACCOUNT-SETTINGS.TOML")&&PurpleTransferRules.fileName("settings.toml")&&!PurpleTransferRules.fileName("other.toml"),"new reader accepts exactly both names");
        java.lang.reflect.Method legacy=PurpleSyncSettingsFile.class.getDeclaredMethod("legacy",byte[].class);
        legacy.setAccessible(true);
        check(!((Boolean)legacy.invoke(null,(Object)bytes(OWN))),"retained production schema-1 sync guard refuses layered file");
        check((Boolean)legacy.invoke(null,(Object)bytes("[last_seen]\nreasons_p=false\n")),"retained schema-1 guard accepts omitted version");
        check(PurpleTransferRules.receiptAllowed("A","B",true,true),"B legacy staged receipt passes asynchronous preparation");
        check(!PurpleTransferRules.receiptAllowed("A","B",false,true),"nonlegacy B receipt keeps modern guard");
        check(!PurpleTransferRules.receiptAllowed("A","B",true,false),"unready B receipt stays held");
        check(PurpleTransferRules.receiptAllowed("A","A",false,true),"existing root receipt unchanged");
        boolean[] receiptLifetime={true};
        PurpleSendReceipt guarded=PurpleSendReceipt.guarded(()->receiptLifetime[0],(id,failed)->{});
        check(guarded.current(),"original send receipt begins current");receiptLifetime[0]=false;
        check(!guarded.current(),"logout before preparation cannot adopt the replacement slot identity");
        String state="# root marker exact 日本語\nunknown=\"keep\"\nselected_preset=\"work\"\n[another]\nmarker=17\n";
        String imported=PurpleLegacySendState.noteNative(bytes(state),bytes(OWN),true);
        check(imported.endsWith(state)&&imported.startsWith("last_imported_fingerprint"),"fingerprint splice preserves every root marker and unknown byte");
        String sent=PurpleLegacySendState.noteNative(bytes(imported),bytes(OWN),false);
        check(sent.endsWith(imported),"sent bookkeeping preserves import marker and root contents");
        String multiline="# exact 🌻\nlast_sent_fingerprint = \"\"\"old\nvalue\"\"\" # marker trail\n[other]\nx=17\n";
        String replaced=PurpleLegacySendState.noteNative(bytes(multiline),bytes(OWN),false);
        check(replaced.startsWith("# exact 🌻\nlast_sent_fingerprint = \"")&&replaced.endsWith(" # marker trail\n[other]\nx=17\n"),"multiline fingerprint replacement preserves surrounding root bytes");
        check(PurpleLegacySendState.noteNative(new byte[]{'u','=', '\"',(byte)0xff,'\"'},bytes(OWN),false)==null,"fingerprint state rejects invalid UTF8 without replacement");
        check(!PurpleCore.shouldAutoSend(bytes(OWN),bytes(imported),bytes(OWN),false),"import fingerprint suppresses echo on later identical save");
        check(!PurpleCore.shouldAutoSend(bytes(OWN),new byte[0],bytes(OWN),true),"import reason never sends");
        check(PurpleCore.shouldAutoSend(bytes(OWN),new byte[0],bytes(OWN),false),"own switch qualifies existing later save");
        check(!PurpleCore.shouldAutoSend(bytes("version=2\n"),new byte[0],bytes("version=2\n"),false),"global switch is not inherited by a file-local missing switch");
        check(PurpleLegacySendState.noteNative(bytes("last_sent_fingerprint=7\n"),bytes(OWN),false)==null,"bad marker refuses instead of resetting root state");
        check(PurpleLegacySendState.noteNative(bytes("last_imported_fingerprint=7\n"),bytes(OWN),false)==null,"other malformed marker also blocks bookkeeping");
    }
    private static final class Fixture implements PurpleAccountEditorModel.Context {
        final File root, own, global;
        final Map<File,byte[]> untouched = new LinkedHashMap<>();
        PurpleAccountEditorModel model;
        boolean live=true;
        int refresh;
        Fixture(File parent,String name) throws Exception {
            root=new File(parent,name);check(root.mkdir(),"exclusive transfer fixture");
            own=new File(root,"accounts/0-202/settings.toml");global=new File(root,"settings.toml");
            write(global,GLOBAL);write(own,OWN);
            write(new File(root,"local.toml"),"root_file_account=\"0-101\"\nglobal_account=\"0-101\"\n");
            write(new File(root,"accounts/root.toml"),"root_file_account=\"0-101\"\n");
            for(String suffix:new String[]{".good",".bak",".import.bak"})write(new File(global.getPath()+suffix),"A sentinel "+suffix+"\n");
            for(String path:new String[]{"state.toml","sync/state.json","sync-history/keep.toml"})write(new File(root,path),"A untouched "+path+"\n");
            for(File file:new File[]{global,new File(global.getPath()+".good"),new File(global.getPath()+".bak"),new File(global.getPath()+".import.bak"),new File(root,"state.toml"),new File(root,"sync/state.json"),new File(root,"sync-history/keep.toml")})untouched.put(file,Files.readAllBytes(file.toPath()));
        }
        void open() throws Exception {
            model=new PurpleAccountEditorModel(root,own,new File(own.getPath()+".bak"),"0-101","0-101","0-202",
                    Arrays.asList(global,new File(global.getPath()+".good"),new File(own.getPath()+".good")),PurpleAccountEditorCases.PHYSICAL,this,PurpleEditorDirectories.NATIVE);
            model.impact(new PurpleConfigImpact.Input(model.capturedBytes(own),false,0,model.capturedBytes(global),new String[0],new byte[0][],new int[0]));
        }
        void unchanged() throws Exception { for(Map.Entry<File,byte[]> entry:untouched.entrySet())check(Arrays.equals(entry.getValue(),Files.readAllBytes(entry.getKey().toPath())),"A exact bytes retained: "+entry.getKey().getName()); }
        public boolean current(boolean refreshed) { return live&&refresh==(refreshed?1:0); }
        public void refresh() { ++refresh; }
        public void notifyChanged() { }
    }
    private static void fileCases(File parent) throws Exception {
        for(String name:new String[]{"settings.toml","account-settings.toml"}) {
            Fixture f=new Fixture(parent,name);f.open();
            String draft=name.equals("settings.toml")?"# whole incoming\n[last_seen]\nreasons_p=false\n":OWN.replace("true","false");
            PurpleAccountEditorModel.Preview preview=f.model.preview(draft,true);
            check(preview!=null,"genuine imported preview ready");
            PurpleAccountEditorModel.Result result=f.model.save(preview);
            check(result.ok&&result.changed,"genuine account import publishes");
            check(Files.readString(f.own.toPath()).equals(draft),"exact incoming bytes retained");
            check(Files.readString(new File(f.own.getPath()+".bak").toPath()).equals(OWN),"B regular backup exact");
            check(Files.readString(new File(f.own.getPath()+".import.bak").toPath()).equals(OWN),"B import backup exact");
            f.unchanged();
        }
        Fixture markers=new Fixture(parent,"fingerprints");
        java.lang.reflect.Method resolve=PurpleAccountFiles.class.getDeclaredMethod("resolveNative",String.class,String.class);
        resolve.setAccessible(true);
        JSONObject aPaths=new JSONObject((String)resolve.invoke(null,"0-101","0-101"));
        JSONObject bPaths=new JSONObject((String)resolve.invoke(null,"0-202","0-101"));
        check(aPaths.getString("fingerprint").equals("state.toml"),"root account uses legacy device state markers");
        check(bPaths.getString("fingerprint").equals("accounts/0-202/send-state.toml"),"B uses own keyed send-state");
        File bState=new File(markers.root,bPaths.getString("fingerprint"));
        write(bState,"# B marker sentinel\nunknown=17\n");
        PurpleGlobalChoiceFiles.Snapshot beforeState=new PurpleGlobalChoiceFiles.Snapshot(bState,false,PurpleGlobalChoiceFiles.LIMIT,PurpleAccountEditorCases.PHYSICAL);
        String noted=PurpleLegacySendState.noteNative(beforeState.bytes,bytes(OWN),true);
        PurpleGlobalChoiceFiles.Publication published=PurpleGlobalChoiceFiles.replace(bState,bytes(noted),PurpleAccountEditorCases.PHYSICAL,
                ()->beforeState.current(PurpleAccountEditorCases.PHYSICAL),PurpleAppWideRemovalModel::ownedTemporary);
        check(published.changed&&published.local!=null,"B import fingerprint publishes via genuine guarded native temporary");
        check(!PurpleCore.shouldAutoSend(bytes(OWN),Files.readAllBytes(bState.toPath()),bytes(OWN),false),"persisted B fingerprint suppresses import echo");
        markers.unchanged();
        Fixture missing=new Fixture(parent,"missing");Files.delete(missing.own.toPath());missing.open();
        check(missing.model.save(missing.model.preview("# omitted version whole\n[last_seen]\nreasons_p=false\n",true)).ok,"explicit import may create whole account file with omitted version");missing.unchanged();
        Fixture good=new Fixture(parent,"good");Files.delete(good.own.toPath());write(new File(good.own.getPath()+".good"),OWN);
        boolean refused=false;try{good.open();}catch(Exception e){refused=true;}check(refused,"absent primary with good is recovery, not import creation");good.unchanged();
        Fixture bad=new Fixture(parent,"bad");write(bad.own,"[");write(new File(bad.own.getPath()+".good"),OWN);bad.open();
        check(bad.model.preview(OWN,true)==null,"bad primary with good never guessed as clean current");bad.unchanged();
        Fixture badGood=new Fixture(parent,"bad-good");Files.delete(badGood.own.toPath());write(new File(badGood.own.getPath()+".good"),"[");
        refused=false;try{badGood.open();}catch(Exception e){refused=true;}check(refused,"absent primary with invalid good is held, never a blank import");badGood.unchanged();
        Fixture noGood=new Fixture(parent,"bad-no-good");write(noGood.own,"[");noGood.open();
        check(noGood.model.preview(OWN,true)==null,"invalid primary without good also refuses");noGood.unchanged();
        Fixture stale=new Fixture(parent,"role-switch");stale.open();PurpleAccountEditorModel.Preview preview=stale.model.preview(OWN,true);
        write(new File(stale.root,"local.toml"),"root_file_account=\"0-101\"\nglobal_account=\"0-202\"\n");
        check(!stale.model.save(preview).changed&&!new File(stale.own.getPath()+".import.bak").exists(),"role switch before confirm refuses before any backup");stale.unchanged();
        Fixture logout=new Fixture(parent,"logout");logout.open();preview=logout.model.preview(OWN,true);logout.live=false;
        check(!logout.model.save(preview).changed,"logout/slot lifetime retirement refuses");logout.unchanged();
    }
    private static void debounceCases() {
        class Scheduler implements PurpleSaveDebounce.Scheduler {
            final ArrayList<Runnable> pending=new ArrayList<>();
            public boolean currentThread(){return true;}public void post(Runnable r,long delay){pending.add(r);}public void cancel(Runnable r){pending.remove(r);}
            void fire(){for(Runnable r:new ArrayList<>(pending))r.run();pending.clear();}
        }
        Scheduler scheduler=new Scheduler();PurpleSaveDebounces saves=new PurpleSaveDebounces(scheduler);ArrayList<String> sent=new ArrayList<>();
        saves.of("A").admit(()->true,()->sent.add("A1"),true);
        saves.of("B").admit(()->true,()->sent.add("B1"),true);
        saves.of("B").admit(()->true,()->sent.add("B2"),true);scheduler.fire();
        check(sent.equals(Arrays.asList("A1","B2")),"rapid B save replaces only B, A countdown survives");
        sent.clear();saves.of("A").admit(()->true,()->sent.add("A"),true);saves.of("B").admit(()->true,()->sent.add("B"),true);
        saves.of("B").admit(()->true,()->sent.add("creation/import"),false);scheduler.fire();
        check(sent.equals(Arrays.asList("A")),"B creation/import/History admission cancels only B and never invents a send");
        sent.clear();boolean[] lifetime={true};saves.of("B").admit(()->lifetime[0],()->sent.add("old"),true);lifetime[0]=false;scheduler.fire();
        check(sent.isEmpty(),"logout invalidates original countdown");
        saves.of("B").admit(()->true,()->sent.add("new"),true);int[] reload={0};saves.refresh(()->++reload[0]);scheduler.fire();
        check(reload[0]==1&&sent.equals(Arrays.asList("new")),"watcher reload once preserves still-current per-origin receipts");
    }
    private static void confirmedSendCases(File parent) throws Exception {
        Fixture f = new Fixture(parent, "delayed-confirmation");
        byte[] b1 = Files.readAllBytes(f.own.toPath());
        String b2 = OWN.replace("send_after_save_p=true", "send_after_save_p=false");
        write(f.own, b2);
        check(f.own.setLastModified(200000L), "B2 save timestamp captured before B1 confirmation");
        File marker = new File(f.own.getParentFile(), "send-state.toml");
        write(marker, "# existing marker\nunknown=17\n");
        byte[] markerBefore = Files.readAllBytes(marker.toPath());
        int[] watermark = {0}, attempts = {0};
        boolean[] live = {true};
        PurpleConfirmedSend.note(() -> live[0], () -> {
            ++attempts[0];
            try {
                if (!Arrays.equals(b1, Files.readAllBytes(f.own.toPath()))) return false;
                throw new AssertionError("B2 must refuse the obsolete B1 fingerprint");
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        }, () -> watermark[0] = 101);
        check(attempts[0] == 1 && watermark[0] == 101, "delayed B1 confirmation records ID despite B2 fingerprint refusal");
        check(PurpleSyncOfferRules.judge(1, 1, false, 101, 300, watermark[0], f.own.lastModified() / 1000)
                == PurpleSyncOfferRules.Verdict.ALREADY_OFFERED, "next launch refuses this install's obsolete B1 even with newer server date");
        check(Arrays.equals(markerBefore, Files.readAllBytes(marker.toPath())), "B1 confirmation does not mark B2 as sent");
        check(Files.readString(f.own.toPath()).equals(b2), "B2 exact unsent bytes survive delayed confirmation");
        PurpleConfirmedSend.note(() -> live[0], () -> false, () -> watermark[0] = 102);
        check(watermark[0] == 102, "marker I/O refusal cannot suppress confirmed watermark");
        boolean failed = false;
        try {
            PurpleConfirmedSend.note(() -> live[0], () -> { throw new IllegalStateException("marker failure"); }, () -> watermark[0] = 103);
        } catch (IllegalStateException e) { failed = true; }
        check(failed && watermark[0] == 103, "unexpected marker failure still records confirmation before propagating");
        PurpleConfirmedSend.note(() -> live[0], () -> { live[0] = false; return true; }, () -> watermark[0] = 104);
        check(watermark[0] == 103, "retired identity cannot record a replacement account watermark");
        PurpleConfirmedSend.note(() -> live[0], () -> { throw new AssertionError("retired fingerprint"); }, () -> watermark[0] = 105);
        check(watermark[0] == 103, "already retired confirmation performs no bookkeeping");
        f.unchanged();
    }
    private static void warningTextCases() throws Exception {
        Map<Integer, String> strings = new LinkedHashMap<>();
        org.w3c.dom.NodeList nodes = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File(System.getProperty("purple.test.strings"))).getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); ++i) {
            org.w3c.dom.Element node = (org.w3c.dom.Element) nodes.item(i);
            if (node.getAttribute("name").startsWith("PurpleImpact")) {
                java.lang.reflect.Field field = org.telegram.messenger.R.string.class.getField(node.getAttribute("name"));
                int id = 0x7f100000 + strings.size();
                field.setInt(null, id);
                strings.put(field.getInt(null), node.getTextContent());
            }
        }
        PurpleConfigImpactText.Text words = new PurpleConfigImpactText.Text() {
            public String get(int id) { String value = strings.get(id); if (value == null) throw new AssertionError("missing resource " + id); return value; }
            public String format(int id, Object... args) { return String.format(java.util.Locale.ROOT, get(id), args); }
            public String label(String key) { return key; }
        };
        String empty = "version=2\n", members = "version=2\n[lists.work]\nmembers=[55]\n";
        for (int mode = 0; mode < 3; ++mode) for (boolean pin : new boolean[]{true, false}) {
            JSONObject impact = input(pin ? empty : members, false, mode, GLOBAL).analyze(bytes(pin ? members : empty));
            JSONObject fact = finding(impact, pin ? 1 : 2, "lists.work", "members");
            check(fact != null && fact.optInt(pin ? "afterUse" : "beforeUse") == (mode == 0 ? 1 : mode == 1 ? 3 : 4), "native pin use reason for Own/Follow/Stock");
            String text = PurpleConfigImpactText.describe(impact, false, "Own account", "Base account", words);
            if (mode == 0) {
                check(text.contains(pin ? "stops following Base account" : "follows Base account again"), "Own pin retains active inheritance warning: pin=" + pin + " text=" + text);
                check(!text.contains("pin is inactive") && !text.contains("pin was inactive"), "active Own pin is not called dormant");
            } else {
                check(text.contains(pin ? "stores an own value" : "removes its stored own value"), "Follow/Stock pin describes stored account change");
                check(text.contains(pin ? "pin is inactive on this device" : "pin was inactive on this device"), "Follow/Stock warning explicitly preserves inactive local scope");
                check(!text.contains("stops following") && !text.contains("follows Base account again"), "Follow/Stock pin makes no local following claim");
                check(text.contains("changes nothing here"), "dormant pin wording agrees with inactive summary");
            }
        }
    }
    public static int run(File parent) throws Exception {checks=0;impactCases();fileCases(parent);debounceCases();confirmedSendCases(parent);warningTextCases();return checks;}
}
