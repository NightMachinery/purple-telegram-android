package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.Locale;

final class PurpleSyncRecoveryModel {
    interface Current { boolean current(); }
    interface Persist { boolean write(String token); }
    static final class Preferences {
        final String token;
        final JSONArray others, retained, active;
        final boolean complete;
        final Current current;
        final Persist persist;
        Preferences(String token, JSONArray others, boolean complete, Current current, Persist persist) {
            this(token,others,new JSONArray(),null,complete,current,persist);
        }
        Preferences(String token,JSONArray others,JSONArray retained,JSONArray active,boolean complete,Current current,Persist persist) {
            this.token=token;this.others=others;this.retained=retained;this.active=active;this.complete=complete;this.current=current;this.persist=persist;
        }
    }
    static final class Offer {
        final PurpleSyncRecoveryModel model;
        final JSONObject request, journal;
        final PurpleSyncRecoveryCore.Plan plan;
        final PurpleSyncInventory inventory;
        final PurpleSyncSettingsFile.Contents local;
        /** Carry entries in this account's own sync folder, or null when there are none. */
        final Carry carry;
        /** Another signed-in account's sync folder holds carry entries; only that account may set them aside. */
        final boolean otherAccountCarry;
        Offer(PurpleSyncRecoveryModel model,JSONObject request,JSONObject journal,PurpleSyncRecoveryCore.Plan plan,
                PurpleSyncInventory inventory,PurpleSyncSettingsFile.Contents local,Carry carry,boolean otherAccountCarry) {
            this.model=model;this.request=request;this.journal=journal;this.plan=plan;this.inventory=inventory;this.local=local;
            this.carry=carry;this.otherAccountCarry=otherAccountCarry;
        }
        boolean resuming() { return journal!=null; }
    }
    /** A top-level entry observed by lstat ({@link PurpleGlobalChoiceFiles.Physical#mark}); its bytes are never read. */
    static final class Entry {
        final PurpleGlobalChoiceFiles.Mark mark;
        final boolean directory;
        Entry(PurpleGlobalChoiceFiles.Mark mark,boolean directory) { this.mark=mark;this.directory=directory; }
        boolean same(Entry other) { return other!=null&&directory==other.directory&&mark.same(other.mark); }
    }
    /**
     * The retired carry's entries ({@link PurpleAccountSyncStore#legacyArtifactName}) at the top of this account's own
     * sync folder, plus the identity of every other top-level entry, taken after the owner route was validated.
     * Unsafe when any carry entry is not a plain single-link file or a directory, when there are more than
     * {@link #LIMIT}, when another entry cannot be observed safely, or when a Start again journal exists.
     */
    static final class Carry {
        static final int LIMIT=64;
        final String rootIdentity;
        final int rootMode;
        final java.util.TreeMap<String,Entry> entries,others;
        final boolean safe;
        Carry(PurpleGlobalChoiceFiles.Mark root,java.util.TreeMap<String,Entry> entries,java.util.TreeMap<String,Entry> others,boolean safe) {
            rootIdentity=root.identity;rootMode=root.mode;this.entries=entries;this.others=others;this.safe=safe;
        }
        /** The same folder, the same carry entries and the same other entries, compared by lstat marks. */
        boolean sameAs(Carry other) {
            return other!=null&&safe&&other.safe&&rootIdentity.equals(other.rootIdentity)&&rootMode==other.rootMode
                    &&same(entries,other.entries)&&same(others,other.others);
        }
        private static boolean same(java.util.TreeMap<String,Entry> a,java.util.TreeMap<String,Entry> b) {
            if(!a.keySet().equals(b.keySet()))return false;
            for(Map.Entry<String,Entry> item:a.entrySet())if(!item.getValue().same(b.get(item.getKey())))return false;
            return true;
        }
    }
    final File root,sync,journalFile;
    final String account,owner,global;
    final Preferences preferences;
    final PurpleSyncRecoveryFiles.Tree tree;
    final Current current;
    final PurpleEditorDirectories.Creator directories;
    final PurpleSyncRecoveryFiles.Moves moves;
    private final PurpleSyncRecoveryFiles.InactiveTokens inactive=new PurpleSyncRecoveryFiles.InactiveTokens();
    private boolean consumed;
    private boolean otherAccountCarry;
    static final int ARCHIVE_ATTEMPTS=60;
    private Current archiveIdle;
    private String[] rootEntries;
    private final java.util.Set<String> targetTokens=new java.util.HashSet<>(), inertTokens=new java.util.HashSet<>();

    PurpleSyncRecoveryModel(File root,String account,String owner,String global,Preferences preferences,
            Current current,PurpleGlobalChoiceFiles.Physical physical,PurpleEditorDirectories.Creator directories,
            PurpleSyncRecoveryFiles.Moves moves) {
        this.root=root;this.account=account;this.owner=owner;this.global=global;this.preferences=preferences;
        this.current=current;this.directories=directories;this.moves=moves;
        sync=new File(root,account.equals(owner)?"sync":"accounts/"+account+"/sync");journalFile=new File(sync,"start-again.json");
        tree=new PurpleSyncRecoveryFiles.Tree(root,physical);
    }
    boolean retained() { return live() && tree.current(); }
    private boolean live() { return !consumed&&current.current()&&preferences.current.current()&&(archiveIdle==null||archiveIdle.current()); }
    Offer check(PurpleSyncInventory inventory,PurpleSyncSettingsFile.Contents local) {
        try {
            Carry carry=null;
            JSONObject request=new JSONObject().put("account",account).put("owner",owner).put("signedIn",current.current())
                    .put("preferenceToken",preferences.token).put("preferences",preferences.others).put("discoveryComplete",false)
                    .put("stores",new JSONArray()).put("cloud",inventory!=null).put("storeSpaceComplete",completeCloud(inventory));
            boolean complete=preferences.complete;
            JSONObject journal=null;
            try {
                if(!PurpleSyncRecoveryFiles.key(account)||!PurpleSyncRecoveryFiles.key(owner)||!PurpleSyncRecoveryFiles.key(global))throw new IllegalStateException("invalid recovery identity");
                PurpleSyncRecoveryFiles.Snapshot localChoice=tree.capture(new File(root,"local.toml"),false);
                PurpleSyncRecoveryFiles.Snapshot ownerRecord=tree.capture(new File(root,"accounts/root.toml"),false);
                JSONObject route=new JSONObject(PurpleGlobalChoiceModel.planNative(localChoice.bytes,ownerRecord.bytes,owner,global,account));
                if(!route.optBoolean("ok"))throw new IllegalStateException("recovery owner changed");
                // WHY: only after the keys and the owner route are proven, so the inventory names this account's folder.
                carry=carryInventory();
                if(!PurpleAccountSyncStore.recoverInterrupted(root,sync,account,tree.physical,()->live()&&tree.current()))throw new IllegalStateException("interrupted sync recovery refused");
                if(PurpleAccountBinding.isToken(preferences.token))targetTokens.add(preferences.token);
                PurpleSyncRecoveryFiles.Snapshot activeJournal=tree.capture(journalFile,false,PurpleSyncRecoveryFiles.JOURNAL_LIMIT);
                JSONArray stores=new JSONArray();stores.put(store(new File(root,"sync"),null));
                PurpleSyncRecoveryFiles.Snapshot accounts=tree.capture(new File(root,"accounts"),true);
                if(accounts.entries!=null)for(String key:accounts.entries) {
                    if(key.equals("root.toml"))continue;
                    if(!PurpleSyncRecoveryFiles.key(key))throw new IllegalStateException("unknown account directory");
                    if(key.equals(account)||signedIn(key))tree.capture(new File(root,"accounts/"+key),true);
                    stores.put(store(new File(root,"accounts/"+key+"/sync"),key));
                    if(stores.length()>100)throw new IllegalStateException("too many stores");
                }
                if(activeJournal.bytes!=null) {
                    journal=new JSONObject(new String(activeJournal.bytes,StandardCharsets.UTF_8));
                    JSONObject plan=journal.getJSONObject("plan");
                    if(!account.equals(plan.getString("account"))||!owner.equals(plan.getString("owner"))
                            ||!preferences.token.equals(plan.getString("preferenceToken")))return null;
                    byte[] expected=PurpleSyncRecoveryCore.bytes(plan.getJSONObject("expected").getString("state"));
                    noteTargetToken(expected);
                    request=new JSONObject().put("journalPlan",plan);
                } else request.put("stores",stores);
            } catch(Exception e) { complete=false; }
            request.put("discoveryComplete",complete);
            PurpleSyncRecoveryCore.Plan plan=retainedConflict()
                    ?new PurpleSyncRecoveryCore.Plan(new JSONObject().put("recovery",PurpleSyncRecoveryCore.Status.Ambiguous.ordinal()).toString())
                    :journal!=null&&!complete
                    ?new PurpleSyncRecoveryCore.Plan(new JSONObject().put("recovery",PurpleSyncRecoveryCore.Status.Incomplete.ordinal()).toString())
                    :PurpleSyncRecoveryCore.plan(request,inventory,local);
            if(plan==null||!live())return null;
            return new Offer(this,request,journal,plan,inventory,local,carry,otherAccountCarry);
        } catch(Exception|LinkageError e) { return null; }
    }
    private static boolean completeCloud(PurpleSyncInventory inventory) {
        if(inventory==null||!inventory.scanComplete)return false;
        for(int transport:inventory.transport)if(transport!=0)return false;
        return true;
    }
    private boolean signedIn(String key) {
        if(preferences.active==null)return true;
        for(int i=0;i<preferences.active.length();++i)if(key.equals(preferences.active.optString(i)))return true;
        return false;
    }
    private void noteTargetToken(byte[] state) {
        if(state==null)return;
        PurpleAccountSyncCore.Result parsed=PurpleAccountSyncCore.inspectState(state);
        if(parsed.isValid()&&PurpleAccountBinding.isToken(parsed.bindingToken))targetTokens.add(parsed.bindingToken);
    }
    private boolean retainedConflict() {
        for(String token:inertTokens)if(targetTokens.contains(token))return true;
        for(int i=0;i<preferences.others.length();++i) {
            JSONObject claim=preferences.others.optJSONObject(i);
            if(claim!=null&&(account.equals(claim.optString("account"))||targetTokens.contains(claim.optString("token"))))return true;
        }
        for(int i=0;i<preferences.retained.length();++i) {
            JSONObject claim=preferences.retained.optJSONObject(i);
            if(claim!=null&&(account.equals(claim.optString("account"))
                    ||account.substring(2).equals(claim.optString("user"))
                    ||targetTokens.contains(claim.optString("token"))))return true;
        }
        return false;
    }
    private JSONObject store(File directory,String key) throws Exception {
        boolean candidate=key!=null?key.equals(account):account.equals(owner);
        boolean inert=!candidate&&!signedIn(key!=null?key:owner);
        if(inert) {
            for(String name:new String[]{"state.json","state.json.bak","state.json.new"}) {
                String token=inactive.inspect(new File(directory,name),tree.physical);
                if(token!=null)inertTokens.add(token);
            }
            return new JSONObject().put("directoryAccount",key!=null?key:JSONObject.NULL)
                    .put("statePresent",false).put("state","")
                    .put("pendingPresent",false).put("pendingComplete",true).put("pending",new JSONObject());
        }
        PurpleSyncRecoveryFiles.Snapshot folder=tree.capture(directory,true);
        if(folder.entries!=null)for(String name:folder.entries) {
            File entry=new File(directory,name);
            if(name.equals("state.json")||name.equals("lock")||name.equals("auto.json"))tree.capture(entry,false);
            else if(name.equals("pending")||PurpleSyncRecoveryFiles.archiveName(name))tree.capture(entry,true);
            else if(name.equals("retained-bases")) {
                PurpleSyncRetainedFiles.records(directory);
                PurpleSyncRecoveryFiles.Snapshot retained=tree.capture(entry,true);
                if(retained.entries!=null)for(String item:retained.entries)tree.capture(new File(entry,item),false);
            }
            else if(name.equals("start-again.json")&&directory.equals(sync))tree.capture(entry,false,PurpleSyncRecoveryFiles.JOURNAL_LIMIT);
            // WHY: the magic chat binding is the account's: Start again neither reads nor moves it, so it stays.
            else if(PurpleAccountSyncStore.bindingFile(name)){if(!entry.isFile()||!PurpleAccountFileSnapshot.notLink(entry))throw new IllegalStateException("unknown sync entry");}
            else {
                if(!directory.equals(sync)&&PurpleAccountSyncStore.legacyArtifactName(name))otherAccountCarry=true;
                throw new IllegalStateException("unknown sync entry");
            }
        }
        PurpleSyncRecoveryFiles.Snapshot state=tree.capture(new File(directory,"state.json"),false);
        if(candidate)noteTargetToken(state.bytes);
        PurpleSyncRecoveryFiles.Snapshot pending=tree.capture(new File(directory,"pending"),true);
        JSONObject files=new JSONObject();
        if(pending.entries!=null)for(String name:pending.entries) {
            PurpleSyncRecoveryFiles.Snapshot item=tree.capture(new File(pending.file,name),false);
            files.put(name,PurpleSyncRecoveryCore.hex(item.bytes));
        }
        if(directory.equals(sync))rootEntries=folder.entries;
        return new JSONObject().put("directoryAccount",key!=null?key:JSONObject.NULL)
                .put("statePresent",state.bytes!=null).put("state",state.bytes!=null?PurpleSyncRecoveryCore.hex(state.bytes):"")
                .put("pendingPresent",!pending.mark.missing()).put("pendingComplete",true).put("pending",files);
    }
    private Entry entry(File file) {
        try { PurpleGlobalChoiceFiles.Mark mark=tree.physical.mark(file,false); if(!mark.missing())return new Entry(mark,false); } catch(Exception e) { }
        try { PurpleGlobalChoiceFiles.Mark mark=tree.physical.mark(file,true); if(!mark.missing())return new Entry(mark,true); } catch(Exception e) { }
        return null;
    }
    /** Lists this account's own sync folder and lstats its top-level entries; reads no entry. Null without carry. */
    private Carry carryInventory() throws Exception {
        PurpleGlobalChoiceFiles.Mark root=tree.physical.mark(sync,true);
        if(root.missing())return null;
        String[] names=PurpleSyncRecoveryFiles.names(sync);
        boolean any=false;
        for(String name:names)if(PurpleAccountSyncStore.legacyArtifactName(name))any=true;
        if(!any)return null;
        // WHY: names-only snapshots of the folder's parent and ancestry, so the action can prove the same
        // directories. The sync folder itself and every carry entry get lstat marks only, never a Snapshot.
        tree.capture(sync.getParentFile(),true);
        java.util.TreeMap<String,Entry> entries=new java.util.TreeMap<>(),others=new java.util.TreeMap<>();
        boolean safe=!root.missing();
        int found=0;
        for(String name:names) {
            if(name.equals("lock")) { Entry lock=entry(new File(sync,name));if(lock==null||lock.directory)safe=false;continue; }
            if(name.equals(journalFile.getName()))safe=false;
            Entry item=entry(new File(sync,name));
            if(item==null) { safe=false;continue; }
            if(PurpleAccountSyncStore.legacyArtifactName(name)) { ++found;entries.put(name,item); }
            else others.put(name,item);
        }
        if(found==0||found>Carry.LIMIT)safe=false;
        return new Carry(root,entries,others,safe);
    }
    /**
     * Whether the folder still holds exactly what was confirmed: the same folder, owner route and ancestry, every
     * other entry unchanged, the carry entries not yet moved unchanged, and the moved ones, by identity, in
     * {@code archive}, which holds nothing else.
     */
    private boolean carryUnchanged(Carry carry,File archive,PurpleGlobalChoiceFiles.Mark archiveMark,java.util.Set<String> moved,boolean locked) {
        try {
            if(!live()||!tree.currentExcept(sync))return false;
            PurpleGlobalChoiceFiles.Mark root=tree.physical.mark(sync,true);
            if(!carry.rootIdentity.equals(root.identity)||carry.rootMode!=root.mode)return false;
            TreeSet<String> expected=new TreeSet<>(carry.others.keySet());
            for(String name:carry.entries.keySet())if(!moved.contains(name))expected.add(name);
            if(archive!=null)expected.add(archive.getName());
            TreeSet<String> actual=new TreeSet<>(Arrays.asList(PurpleSyncRecoveryFiles.names(sync)));
            if(!locked)actual.remove("lock");
            else if(!actual.remove("lock"))return false;
            if(!expected.equals(actual))return false;
            for(Map.Entry<String,Entry> item:carry.others.entrySet())if(!item.getValue().same(entry(new File(sync,item.getKey()))))return false;
            for(Map.Entry<String,Entry> item:carry.entries.entrySet()) {
                if(moved.contains(item.getKey()))continue;
                if(!item.getValue().same(entry(new File(sync,item.getKey()))))return false;
            }
            if(archive==null)return moved.isEmpty();
            PurpleGlobalChoiceFiles.Mark now=tree.physical.mark(archive,true);
            if(!archiveMark.identity.equals(now.identity)||archiveMark.mode!=now.mode)return false;
            if(!new TreeSet<>(Arrays.asList(PurpleSyncRecoveryFiles.names(archive))).equals(new TreeSet<>(moved)))return false;
            for(String name:moved) {
                // WHY: identity and kind only; a rename may change the moved entry's ctime and so its revision.
                Entry was=carry.entries.get(name),is=entry(new File(archive,name));
                if(is==null||is.directory!=was.directory||!is.mark.identity.equals(was.mark.identity))return false;
            }
            return true;
        } catch(Exception|LinkageError e) { return false; }
    }
    /**
     * After the user confirmed {@code shown}, moves exactly its carry entries, unread, into a new private
     * {@code orphaned-<date>} folder inside this account's own sync folder. Nothing is read, rewritten, restored or
     * deleted, and no other entry moves. Every step is one no-follow, no-replace, identity-checked rename, so each
     * entry stays either in place or in that folder; a stop part-way leaves the rest in place, still refused by the
     * store, for a fresh Check and a fresh confirmation. The offer is consumed by the attempt.
     */
    boolean setAsideCarry(Offer shown) {
        try {
            Carry carry=shown!=null?shown.carry:null;
            if(shown==null||shown.model!=this||carry==null||!carry.safe||shown.resuming())return false;
            java.util.Set<String> moved=new TreeSet<>();
            if(!carryUnchanged(carry,null,null,moved,false))return false;
            try(PurpleSyncRecoveryFiles.Lock lock=new PurpleSyncRecoveryFiles.Lock(sync,carry.rootIdentity)) {
                if(!carryUnchanged(carry,null,null,moved,true))return false;
                PurpleGlobalChoiceFiles.Mark parent=tree.physical.mark(sync,true);
                File archive=null;PurpleGlobalChoiceFiles.Mark archiveMark=null;
                long now=System.currentTimeMillis();
                for(int i=0;i<ARCHIVE_ATTEMPTS&&archive==null;++i) {
                    File candidate=new File(sync,"orphaned-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date(now+i*1000L)));
                    // WHY: an earlier set-aside in the same second keeps its folder; take the next free second.
                    if(java.nio.file.Files.exists(candidate.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))continue;
                    if(!carryUnchanged(carry,null,null,moved,true))return false;
                    PurpleEditorDirectories.Receipt created=directories.create(candidate,parent);
                    if(created==null||!created.created) {
                        if(!carryUnchanged(carry,null,null,moved,true))return false;
                        continue;
                    }
                    PurpleGlobalChoiceFiles.Mark mark=tree.physical.mark(candidate,true);
                    if(!created.owned||!created.parentIdentity.equals(parent.identity)||!created.mark.same(mark)||(mark.mode&0777)!=0700)return false;
                    archive=candidate;archiveMark=mark;
                }
                if(archive==null||!carryUnchanged(carry,archive,archiveMark,moved,true))return false;
                for(Map.Entry<String,Entry> item:carry.entries.entrySet()) {
                    if(!carryUnchanged(carry,archive,archiveMark,moved,true))return false;
                    // WHY: a false result may follow a rename whose fsync failed; stop either way, never undo.
                    if(!moves.move(new File(sync,item.getKey()),new File(archive,item.getKey()),item.getValue().mark.identity,
                            carry.rootIdentity,archiveMark.identity))return false;
                    moved.add(item.getKey());
                }
                return moved.equals(carry.entries.keySet())&&carryUnchanged(carry,archive,archiveMark,moved,true);
            }
        } catch(Exception|LinkageError e) { return false; }
        finally { consumed=true; }
    }
    boolean relink(Offer shown) {
        synchronized(PurpleAccountBinding.WRITE_LOCK) {
            try {
                if(shown==null||shown.model!=this||shown.resuming()||!live()||!tree.current())return false;
                if(shown.plan.status!=PurpleSyncRecoveryCore.Status.AutoRelink&&shown.plan.status!=PurpleSyncRecoveryCore.Status.RootRelink)return false;
                PurpleSyncRecoveryFiles.Snapshot folder=tree.capture(sync,true);
                try(PurpleSyncRecoveryFiles.Lock lock=new PurpleSyncRecoveryFiles.Lock(sync,folder.mark.identity)) {
                    if(!live()||!tree.currentExcept(sync)||!ownUnchangedWithLock())return false;
                    PurpleSyncRecoveryCore.Plan fresh=PurpleSyncRecoveryCore.plan(shown.request,shown.inventory,shown.local);
                    if(fresh==null||fresh.status!=shown.plan.status||!fresh.token.equals(shown.plan.token)||!PurpleAccountBinding.isToken(fresh.token))return false;
                    boolean stored=preferences.persist.write(fresh.token);
                    consumed=true;
                    return stored&&current.current()&&tree.currentExcept(sync)&&ownUnchangedWithLock();
                }
            } catch(Exception|LinkageError e) { return false; }
        }
    }
    private boolean ownUnchangedWithLock() {
        try {
            if(!tree.files.get(sync).mark.same(tree.physical.mark(sync,true)))return false;
            for(PurpleSyncRecoveryFiles.Snapshot snapshot:tree.files.values()) {
                if(snapshot.file.getPath().startsWith(sync.getPath()+File.separator)&&!snapshot.file.getName().equals("lock")&&!snapshot.current(tree.physical))return false;
            }
            TreeSet<String> expected=new TreeSet<>();if(rootEntries!=null)expected.addAll(Arrays.asList(rootEntries));expected.add("lock");
            return expected.equals(new TreeSet<>(Arrays.asList(PurpleSyncRecoveryFiles.names(sync))));
        } catch(Exception e) { return false; }
    }
    private JSONObject identities(JSONObject plan) throws Exception {
        JSONObject out=new JSONObject();JSONObject expected=plan.getJSONObject("expected");
        if(expected.getBoolean("statePresent"))out.put("state.json",tree.capture(new File(sync,"state.json"),false).mark.identity);
        if(expected.getBoolean("pendingPresent")) {
            out.put("pending",tree.capture(new File(sync,"pending"),true).mark.identity);
            java.util.Iterator<String> names=expected.getJSONObject("pending").keys();
            while(names.hasNext()) { String name=names.next();out.put("pending/"+name,tree.capture(new File(sync,"pending/"+name),false).mark.identity); }
        }
        return out;
    }
    boolean startAgain(Offer shown,boolean sendQueued) { return startAgain(shown,sendQueued,()->true); }
    boolean startAgain(Offer shown,boolean sendQueued,Current idle) {
        archiveIdle=idle;
        try {
            if(shown==null||shown.model!=this||!live()||sendQueued||!tree.current()
                    ||shown.resuming()&&shown.plan.start!=PurpleSyncRecoveryCore.Start.Ready||!shown.resuming()&&!shown.plan.offerStartAgain)return false;
            PurpleSyncRecoveryFiles.Snapshot folder=tree.capture(sync,true);
            if(folder.mark.missing())return false;
            try(PurpleSyncRecoveryFiles.Lock lock=new PurpleSyncRecoveryFiles.Lock(sync,folder.mark.identity)) {
                if(!live()||!tree.currentExcept(sync))return false;
                JSONObject journal=shown.journal;
                if(journal==null) {
                    if(!ownUnchangedWithLock()||!shown.plan.offerStartAgain)return false;
                    String date=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date());
                    File archive=new File(sync,"orphaned-"+date);
                    if(!tree.physical.mark(archive,true).missing())return false;
                    JSONObject request=new JSONObject(shown.request.toString()).put("start",true).put("date",date).put("confirmed",true)
                            .put("archiveAvailable",true).put("busy",false).put("sendQueued",sendQueued);
                    PurpleSyncRecoveryCore.Plan fresh=PurpleSyncRecoveryCore.plan(request,shown.inventory,shown.local);
                    if(fresh==null||fresh.start!=PurpleSyncRecoveryCore.Start.Ready||!live()||!tree.currentExcept(sync)||!ownUnchangedWithLock())return false;
                    JSONObject plan=fresh.value.getJSONObject("journalPlan");
                    JSONObject ids=identities(plan);
                    PurpleEditorDirectories.Receipt created=directories.create(archive,folder.mark);
                    if(created==null||!created.created||!created.owned||!created.parentIdentity.equals(folder.mark.identity)
                            ||!created.mark.same(tree.physical.mark(archive,true)))return false;
                    journal=new JSONObject().put("plan",plan).put("identities",ids).put("rootIdentity",folder.mark.identity)
                            .put("archiveIdentity",created.mark.identity).put("rootEntries",new JSONArray(rootEntries!=null?Arrays.asList(rootEntries):java.util.Collections.emptyList()));
                    PurpleSyncRecoveryFiles.Snapshot absent=new PurpleSyncRecoveryFiles.Snapshot(journalFile,false,PurpleSyncRecoveryFiles.JOURNAL_LIMIT,tree.physical);
                    if(!absent.mark.missing()||PurpleSyncRecoveryFiles.publish(journalFile,absent,journal.toString().getBytes(StandardCharsets.UTF_8),tree.physical,
                            ()->live()&&tree.currentExcept(sync))==null)return false;
                }
                return finishJournal(journal,shown.local);
            }
        } catch(Exception|LinkageError e) { return false; }
    }
    private boolean finishJournal(JSONObject journal,PurpleSyncSettingsFile.Contents local) throws Exception {
        JSONObject request=new JSONObject().put("journalPlan",journal.getJSONObject("plan"));
        JSONObject plan=journal.getJSONObject("plan"), expected=plan.getJSONObject("expected"), ids=journal.getJSONObject("identities");
        if(!account.equals(plan.getString("account"))||!owner.equals(plan.getString("owner"))||!preferences.token.equals(plan.getString("preferenceToken")))return false;
        PurpleSyncRecoveryCore.Plan nativePlan=PurpleSyncRecoveryCore.plan(request,null,local);
        if(nativePlan==null||nativePlan.start!=PurpleSyncRecoveryCore.Start.Ready)return false;
        File archive=new File(root,nativePlan.archive);
        if(!archive.getParentFile().equals(sync)||!journal.getString("rootIdentity").equals(tree.physical.mark(sync,true).identity)
                ||!journal.getString("archiveIdentity").equals(tree.physical.mark(archive,true).identity)
                ||(tree.physical.mark(archive,true).mode&0777)!=0700)return false;
        PurpleSyncRecoveryFiles.Snapshot journalExpected=new PurpleSyncRecoveryFiles.Snapshot(journalFile,false,PurpleSyncRecoveryFiles.JOURNAL_LIMIT,tree.physical);
        for(int i=0;i<nativePlan.moves.length();++i) {
            if(!live()||!tree.currentExcept(sync)||!journalExpected.current(tree.physical)||!journalDirectoriesSafe(journal,archive,expected))return false;
            JSONObject move=nativePlan.moves.getJSONObject(i);File source=new File(root,move.getString("source")),destination=new File(root,move.getString("destination"));
            boolean pending=move.getInt("kind")==0;
            if(!source.getParentFile().equals(sync)||!destination.getParentFile().equals(archive))return false;
            boolean from=matches(source,pending,expected,ids),to=matches(destination,pending,expected,ids);
            if(from&&!to&&tree.physical.mark(destination,pending).missing()) {
                if(!moves.move(source,destination,ids.getString(pending?"pending":"state.json"),journal.getString("rootIdentity"),journal.getString("archiveIdentity")))return false;
                if(!tree.physical.mark(source,pending).missing()||!matches(destination,pending,expected,ids))return false;
            } else if(!tree.physical.mark(source,pending).missing()||!to)return false;
        }
        JSONObject observation=observation(archive);
        PurpleSyncRecoveryCore.Plan complete=PurpleSyncRecoveryCore.plan(request.put("observation",observation),null,local);
        if(complete==null||complete.completion!=PurpleSyncRecoveryCore.Completion.SetupReady||!complete.clearUndo
                ||!live()||!tree.currentExcept(sync)||!journalExpected.current(tree.physical)||!journalDirectoriesSafe(journal,archive,expected))return false;
        if(!journalFile.delete()||!PurpleSyncRecoveryFiles.syncNative(sync.getPath(),journal.getString("rootIdentity")))return false;
        consumed=true;return true;
    }
    private boolean journalDirectoriesSafe(JSONObject journal,File archive,JSONObject expected) throws Exception {
        TreeSet<String> allowed=new TreeSet<>();JSONArray entries=journal.getJSONArray("rootEntries");
        for(int i=0;i<entries.length();++i)allowed.add(entries.getString(i));
        allowed.add("lock");allowed.add("start-again.json");allowed.add(archive.getName());
        for(String name:PurpleSyncRecoveryFiles.names(sync))if(!allowed.contains(name))return false;
        for(String name:PurpleSyncRecoveryFiles.names(archive))if(!name.equals("state.json")&&!name.equals("pending"))return false;
        return true;
    }
    private boolean matches(File path,boolean directory,JSONObject expected,JSONObject ids) throws Exception {
        PurpleSyncRecoveryFiles.Snapshot snapshot=new PurpleSyncRecoveryFiles.Snapshot(path,directory,PurpleSyncRecoveryFiles.FILE_LIMIT,tree.physical);
        if(snapshot.mark.missing()||!ids.getString(directory?"pending":"state.json").equals(snapshot.mark.identity))return false;
        if(!directory)return Arrays.equals(snapshot.bytes,PurpleSyncRecoveryCore.bytes(expected.getString("state")));
        JSONObject files=expected.getJSONObject("pending");
        if(snapshot.entries.length!=files.length())return false;
        for(String name:snapshot.entries) {
            if(!files.has(name))return false;
            PurpleSyncRecoveryFiles.Snapshot file=new PurpleSyncRecoveryFiles.Snapshot(new File(path,name),false,PurpleSyncRecoveryFiles.FILE_LIMIT,tree.physical);
            if(!ids.getString("pending/"+name).equals(file.mark.identity)||!Arrays.equals(file.bytes,PurpleSyncRecoveryCore.bytes(files.getString(name))))return false;
        }
        return true;
    }
    private JSONObject observation(File archive) throws Exception {
        PurpleSyncRecoveryFiles.Snapshot state=new PurpleSyncRecoveryFiles.Snapshot(new File(archive,"state.json"),false,PurpleSyncRecoveryFiles.FILE_LIMIT,tree.physical);
        PurpleSyncRecoveryFiles.Snapshot pending=new PurpleSyncRecoveryFiles.Snapshot(new File(archive,"pending"),true,0,tree.physical);
        JSONObject files=new JSONObject();
        if(pending.entries!=null)for(String name:pending.entries)files.put(name,PurpleSyncRecoveryCore.hex(new PurpleSyncRecoveryFiles.Snapshot(new File(pending.file,name),false,PurpleSyncRecoveryFiles.FILE_LIMIT,tree.physical).bytes));
        return new JSONObject().put("account",account).put("complete",true).put("preferenceToken",preferences.token)
                .put("originalStatePresent",!tree.physical.mark(new File(sync,"state.json"),false).missing())
                .put("originalPendingPresent",!tree.physical.mark(new File(sync,"pending"),true).missing())
                .put("archivedState",state.bytes!=null?PurpleSyncRecoveryCore.hex(state.bytes):JSONObject.NULL)
                .put("archivedPending",!pending.mark.missing()?files:JSONObject.NULL);
    }
}
