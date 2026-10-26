package org.telegram.messenger.purple;

import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatDiscovery {
    enum Phase { Unset, Searching, Use, Paused, Lost }
    enum Step { Search, PeerInfo, ExactMark, SettingsHint, Inventory, Idle }
    static final class Status {
        final Phase phase;
        final Resolution resolution;
        final String reason;
        final boolean complete, postingHeld, newReaders;
        final long retryAt;
        Status(Phase phase, Resolution resolution, String reason, boolean complete, boolean held, long retryAt) {
            this(phase,resolution,reason,complete,held,retryAt,false);
        }
        Status(Phase phase,Resolution resolution,String reason,boolean complete,boolean held,long retryAt,boolean newReaders){
            this.newReaders=newReaders;
            this.phase = phase; this.resolution = resolution; this.reason = reason; this.complete = complete; postingHeld = held; this.retryAt = retryAt;
        }
    }
    static final class Command {
        final PurpleSyncChatOrigin origin;
        final long run, ticket;
        final Step step;
        final PurpleSyncChatRequests.Search search;
        final PurpleSyncChatRequests.Cursor cursor;
        final Peer chat;
        final Cache mark;
        Command(PurpleSyncChatOrigin origin, long run, long ticket, Step step, PurpleSyncChatRequests.Search search,
                PurpleSyncChatRequests.Cursor cursor, Peer chat, Cache mark) {
            this.origin = origin; this.run = run; this.ticket = ticket; this.step = step; this.search = search; this.cursor = cursor; this.chat = chat; this.mark = mark;
        }
    }
    static final class Reply {
        final PurpleSyncChatOrigin origin;
        final long run, ticket;
        boolean success;
        PurpleSyncChatRequests.Page page;
        PurpleSyncChatClient.Info info;
        PurpleSyncChatClient.Exact exact;
        SettingsHint hint = SettingsHint.Unknown;
        RecordPresence records = RecordPresence.Unknown;
        Reply(Command command) { origin = command.origin; run = command.run; ticket = command.ticket; }
        Reply(PurpleSyncChatOrigin origin, long run, long ticket) { this.origin = origin; this.run = run; this.ticket = ticket; }
    }

    private final PurpleSyncChatOrigin origin;
    private final ResolveInput input;
    private final PurpleSyncChatCache.Data original;
    private final String stream;
    private final List<Peer> dialogs;
    private final List<TLRPC.Message> hits = new ArrayList<>();
    private final Map<Peer,PurpleSyncChatClient.Info> facts = new HashMap<>();
    private final Set<String> verified = new HashSet<>(), gone = new HashSet<>();
    private final Map<Peer,RecordPresence> inventories = new HashMap<>();
    private PurpleSyncChatRequests.Pages pages;
    private PurpleSyncChatCache.Data cache;
    private Command command;
    private Status status;
    private List<Peer> peers = Collections.emptyList();
    private int peerIndex, dialogIndex, retries;
    private long run, ticket;
    private boolean complete, uncertain, globalCapped, fallbackUsed, cacheRead, cancelled, fallbackTruncated, directOnly, hintInventory;

    PurpleSyncChatDiscovery(PurpleSyncChatOrigin origin, PurpleSyncChatCache.Data cache, boolean bound, Peer boundChat, List<Peer> dialogs, String stream) {
        this.origin = origin; this.original = cache; this.cache = cache; this.stream = stream;
        input = new ResolveInput(origin.userId); input.cache = cache != null ? cache.location : null; input.settingsBound = bound; input.settingsChat = boundChat;
        List<Peer> unique = new ArrayList<>(); Set<Peer> seen = new HashSet<>();
        if (dialogs != null) for (Peer peer : dialogs) if (peer != null && (peer.kind == PeerKind.Chat || peer.kind == PeerKind.Channel) && seen.add(peer)) unique.add(peer);
        fallbackTruncated = unique.size() > 50; this.dialogs = new ArrayList<>(unique.subList(0,Math.min(50,unique.size())));
        status = new Status(Phase.Paused,safeResolution(),"NotStarted",false,true,0);
        command = new Command(origin,0,0,Step.Idle,null,null,null,null);
    }
    void start(long now) { if (!cancelled) begin(now,false); }
    void startBeforePost(long now) {
        if (cancelled) return;
        begin(now,false);
        if(command.step==Step.Idle)return;
        Cache effective = effective();
        if (effective != null && effective.markId > 0 && (original == null || !original.pendingMove())) {
            directOnly = true; issue(Step.ExactMark,effective.chat,effective,null);
        }
    }
    void startRetry(long now,int completed) { if (!cancelled && completed >= 0 && completed <= 3) { retries=completed; begin(now,true); } }
    int retriesCompleted() { return retries; }
    void tick(long now) {
        if (!cancelled && command.step == Step.Idle && status.phase == Phase.Searching && status.retryAt > 0 && now >= status.retryAt) { ++retries; begin(now,true); }
    }
    void cancel() { cancelled = true; ++run; issue(Step.Idle,null,null,null); }
    Command command() { return command; }
    Status status() { return status; }
    PurpleSyncChatCache.Data cache() { return cache; }
    ResolveInput input() { return input; }
    private void begin(long now, boolean retry) {
        ++run; hits.clear(); facts.clear(); verified.clear(); gone.clear(); inventories.clear();
        complete = true; uncertain = false; directOnly = hintInventory = false; globalCapped = false; fallbackUsed = false; cacheRead = false; dialogIndex = peerIndex = 0;
        input.marks = Collections.emptyList(); input.cacheRead = DirectRead.Unknown; input.settingsHint = SettingsHint.NotRun;
        input.cachedEligibility = unknownEligibility(); input.searchRetriesCompleted = retries;
        if (!retry) retries = input.searchRetriesCompleted = 0;
        if(original!=null&&input.settingsBound&&!original.location.chat.equals(input.settingsChat)){pause("BoundChatMismatch");return;}
        if (original != null && original.pendingMove()) { pause("PendingMove"); return; }
        status = new Status(Phase.Searching,safeResolution(),"Resolving",false,true,0);
        search(PurpleSyncChatRequests.Search.Global,null);
    }
    boolean complete(Reply reply, long now) {
        if (cancelled || reply == null || command.step == Step.Idle || !origin.equals(reply.origin) || reply.run != run || reply.ticket != command.ticket) return false;
        try {
            switch (command.step) {
                case Search: searched(reply,now); break;
                case PeerInfo:
                    if (reply.success && valid(reply.info,command.chat)) facts.put(command.chat,reply.info);
                    else { facts.remove(command.chat); uncertain = true; }
                    if (effective() != null && effective().markId == 0 && command.chat.equals(effective().chat)) {
                        cacheRead = true; input.cacheRead = reply.success && valid(reply.info,command.chat) && reply.info.readable() ? DirectRead.Valid : DirectRead.Unknown;
                    }
                    peerNext(now); break;
                case ExactMark: exact(reply,now); break;
                case SettingsHint:
                    input.settingsHint = reply.success ? reply.hint : SettingsHint.Unknown;
                    if(input.settingsHint==SettingsHint.Found||input.settingsHint==SettingsHint.Absent){hintInventory=true;issue(Step.Inventory,Peer.self(),null,null);}
                    else { uncertain = true; finish(now); }
                    break;
                case Inventory:
                    if (!reply.success || reply.records == RecordPresence.Unknown) { pause("InventoryUnknown"); break; }
                    inventories.put(command.chat,reply.records);
                    if(hintInventory){hintInventory=false;input.settingsHint=reply.records==RecordPresence.Present?SettingsHint.Found:SettingsHint.Absent;}
                    resolve(now); break;
                default: return false;
            }
        } catch (RuntimeException error) { pause("InvalidReply"); }
        return true;
    }
    private boolean valid(PurpleSyncChatClient.Info info, Peer chat) { return info != null && origin.equals(info.origin) && chat.equals(info.chat); }
    private Cache effective() { return original != null ? original.location : input.settingsBound ? new Cache(input.settingsChat,0,0) : null; }
    private void issue(Step step, Peer chat, Cache mark, PurpleSyncChatRequests.Search search) {
        command = new Command(origin,run,++ticket,step,search,pages != null ? pages.cursor() : PurpleSyncChatRequests.Cursor.first(),chat,mark);
    }
    private void search(PurpleSyncChatRequests.Search kind, Peer target) {
        pages = new PurpleSyncChatRequests.Pages(origin,kind); issue(Step.Search,target,null,kind);
    }
    private void searched(Reply reply, long now) {
        PurpleSyncChatRequests.Search kind = command.search; Peer target = command.chat;
        PurpleSyncChatRequests.PageStatus state = reply.success ? pages.add(reply.page) : PurpleSyncChatRequests.PageStatus.Incomplete;
        if (reply.page != null) for (TLRPC.Message message : reply.page.messages) {
            if (hits.size() >= 4096) { complete = false; break; }
            hits.add(message);
        }
        if (state == PurpleSyncChatRequests.PageStatus.Next && kind != PurpleSyncChatRequests.Search.Dialog) { issue(Step.Search,target,null,kind); return; }
        if (state != PurpleSyncChatRequests.PageStatus.Complete) complete = false;
        if (kind == PurpleSyncChatRequests.Search.Global) {
            globalCapped = state == PurpleSyncChatRequests.PageStatus.Capped;
            search(PurpleSyncChatRequests.Search.Self,Peer.self());
        } else if (kind == PurpleSyncChatRequests.Search.Broadcasts) {
            fallbackNext(now);
        } else if (kind == PurpleSyncChatRequests.Search.Dialog) {
            fallbackNext(now);
        } else {
            searchDone(now);
        }
    }
    private void fallbackNext(long now) {
        if (dialogIndex < dialogs.size()) { search(PurpleSyncChatRequests.Search.Dialog,dialogs.get(dialogIndex++)); return; }
        if (fallbackTruncated) complete = false;
        searchDone(now);
    }
    private void searchDone(long now) {
        Cache effective = effective();
        if (!cacheRead && effective != null && effective.markId > 0) { issue(Step.ExactMark,effective.chat,effective,null); return; }
        Set<Peer> unique = new java.util.LinkedHashSet<>();
        if (!cacheRead && effective != null) unique.add(effective.chat);
        for (TLRPC.Message message : hits) {
            if (potential(message)) { Peer peer = PurpleSyncChatFacts.token(message.peer_id,origin.userId); if (peer != null) unique.add(peer); else uncertain = true; }
        }
        peers = new ArrayList<>(unique); peerIndex = 0; peerNext(now);
    }
    private static boolean media(TLRPC.Message message) { return message.media != null && message.media.getClass() != TLRPC.TL_messageMediaEmpty.class; }
    private static boolean potential(TLRPC.Message message) {
        return message != null && parseMark(message.message,media(message),Ownership.Own).status != MarkStatus.NotMark;
    }
    private void peerNext(long now) {
        while (peerIndex < peers.size()) { Peer peer = peers.get(peerIndex++); if (!facts.containsKey(peer)) { issue(Step.PeerInfo,peer,null,null); return; } }
        resolve(now);
    }
    private void exact(Reply reply, long now) {
        Cache location = command.mark; PurpleSyncChatClient.Exact exact = reply.success ? reply.exact : null;
        boolean cached = effective() != null && effective().equals(location);
        DirectRead read = exact != null && valid(exact.info,location.chat) ? exact.state : DirectRead.Unknown;
        if (cached) { cacheRead = true; input.cacheRead = read; }
        if (exact != null && valid(exact.info,location.chat)) facts.put(location.chat,exact.info);
        if (read == DirectRead.Unknown) { uncertain = true; pause("UnknownMark"); return; }
        if (read == DirectRead.Valid) {
            if (exact.message == null || exact.message.id != location.markId || exact.message.date != location.markDate
                || !location.chat.equals(PurpleSyncChatFacts.token(exact.message.peer_id,origin.userId))) { pause("UnknownMark"); return; }
            remove(location); hits.add(exact.message); verified.add(key(location));
        } else {
            remove(location); gone.add(key(location));
            if (read == DirectRead.ChatGone || read == DirectRead.Migrated) hits.removeIf(message -> location.chat.equals(PurpleSyncChatFacts.token(message.peer_id,origin.userId)));
        }
        if (cached && directOnly && read != DirectRead.Valid) { directOnly = false; search(PurpleSyncChatRequests.Search.Global,null); return; }
        searchDone(now);
    }
    private void remove(Cache location) { hits.removeIf(message -> message.id == location.markId && location.chat.equals(PurpleSyncChatFacts.token(message.peer_id,origin.userId))); }
    private static String key(Cache location) { return location.chat.token()+"/"+location.markId; }
    private List<Mark> mapped() {
        List<Mark> marks = new ArrayList<>();
        for (TLRPC.Message message : hits) {
            if (!potential(message)) continue;
            Peer peer = PurpleSyncChatFacts.token(message.peer_id,origin.userId);
            if (peer == null) { uncertain = true; continue; }
            PurpleSyncChatClient.Info info = facts.get(peer);
            if (!valid(info,peer) || info.peer == null || !info.eligibility().known) { uncertain = true; continue; }
            Ownership own = ownership(PurpleSyncChatFacts.message(origin,info.peer,message),Options.restricted());
            if (own == Ownership.Unknown) { uncertain = true; continue; }
            ParsedMark parsed = parseMark(message.message,media(message),own);
            if (parsed.status == MarkStatus.NotMark) continue;
            Cache location = new Cache(peer,message.id,message.date); if (gone.contains(key(location))) continue;
            marks.add(new Mark(location,parsed.status,info.eligibility(),inventories.getOrDefault(peer,RecordPresence.Unknown),info.readable(),verified.contains(key(location))));
        }
        return marks;
    }
    private void resolve(long now) {
        input.marks = mapped(); Cache effective = effective();
        PurpleSyncChatClient.Info cached = effective != null ? facts.get(effective.chat) : null;
        input.cachedEligibility = cached != null ? cached.eligibility() : unknownEligibility();
        input.readerWarningAccepted = original != null && original.accepted(stream);
        boolean eligible = false; for (Mark mark : input.marks) eligible |= mark.eligibility.allowed();
        if (!fallbackUsed && (globalCapped || !eligible && (effective == null || effective.markId == 0 || input.cacheRead != DirectRead.Valid))) {
            fallbackUsed = true; search(PurpleSyncChatRequests.Search.Broadcasts,null); return;
        }
        if (!complete || uncertain) { finish(now); return; }
        if (effective == null && !eligible && input.settingsHint == SettingsHint.NotRun) { issue(Step.SettingsHint,Peer.self(),null,null); return; }
        Resolution result = PurpleSyncChatCore.resolve(input);
        if (result.action == Action.VerifyMark) { issue(Step.ExactMark,result.winner.chat,result.winner,null); return; }
        if (result.action == Action.ReadWinnerInventory) { issue(Step.Inventory,result.winner.chat,result.winner,null); return; }
        if (result.action == Action.Use && result.readChat != null && !facts.containsKey(result.readChat)) {
            issue(Step.PeerInfo,result.readChat,null,null); return;
        }
        finish(result,now);
    }
    private void finish(long now) { finish(PurpleSyncChatCore.resolve(input),now); }
    private void finish(Resolution result, long now) {
        issue(Step.Idle,null,null,null);
        if (!complete || uncertain) { status = new Status(Phase.Paused,safeResolution(),uncertain?"UnknownFacts":"IncompleteSearch",false,true,0,cache!=null&&cache.newReaders); return; }
        Phase phase = result.action == Action.Unset ? Phase.Unset : result.action == Action.Use ? Phase.Use : result.action == Action.Searching ? Phase.Searching : result.action == Action.Lost ? Phase.Lost : Phase.Paused;
        String reason = result.action == Action.Invalid ? "InvalidResolution" : result.action == Action.Follow ? "FollowRequired" : result.action == Action.Fill ? "FillRequired"
            : result.action == Action.NewerMark ? "NewerMark" : result.needsReaderWarning ? "ReaderConsent" : "";
        boolean held = result.postingHeld;
        PurpleSyncChatClient.Info info = result.readChat != null ? facts.get(result.readChat) : null;
        if (result.action == Action.Use) {
            if (info == null || !info.readable()) { held = true; reason = "UnknownFacts"; phase = Phase.Paused; }
            else if (!info.eligibility().allowed()) { held = true; reason = "ChatIneligible"; phase = Phase.Paused; }
            else if (!info.readersKnown) { held = true; reason = "UnknownReaders"; }
            if (result.cache != null && info != null && info.readable()) {
                cache = original != null ? original.next(result.cache,info) : new PurpleSyncChatCache.Data(result.cache,info.title,PurpleSyncChatCache.ReadersSnapshot.from(info),Collections.emptySet(),new org.json.JSONObject(),false);
            }
        }
        status = new Status(phase,result,reason,true,held,result.retryAfterSeconds > 0 ? now+result.retryAfterSeconds*1000L : 0,cache!=null&&cache.newReaders);
    }
    private Resolution safeResolution() {
        ResolveInput safe = new ResolveInput(origin.userId); safe.cache = original != null ? original.location : null; safe.settingsBound = input.settingsBound;
        safe.settingsChat = input.settingsChat; safe.cacheRead = DirectRead.Unknown; return PurpleSyncChatCore.resolve(safe);
    }
    private void pause(String reason) { issue(Step.Idle,null,null,null); status = new Status(Phase.Paused,safeResolution(),reason,false,true,0,cache!=null&&cache.newReaders); }
}
