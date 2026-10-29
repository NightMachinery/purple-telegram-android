package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatCache {
    interface IO {
        byte[] read() throws Exception;
        boolean write(byte[] expected, byte[] next);
        boolean remove(byte[] expected);
    }
    static final class ReadersSnapshot {
        final String memberHash;
        final Set<String> memberHashes;
        final Kind kind;
        final Long otherMembers, otherAdmins;
        final boolean creator, admin, canSendFiles, anonymousAdmin, postsAsOther, publicUsername, protectedContent, linkedDiscussion, autoDelete;
        ReadersSnapshot(String hash, Set<String> memberHashes, Long members, Long admins, ChatFacts facts) {
            this.memberHashes=memberHashes!=null?Collections.unmodifiableSet(new HashSet<>(memberHashes)):null;
            kind=facts.kind;memberHash = hash; otherMembers = members; otherAdmins = admins; creator = facts.creator; admin = facts.admin;
            canSendFiles = facts.canSendFiles; anonymousAdmin = facts.anonymousAdmin; postsAsOther = facts.postsAsOther;
            publicUsername = facts.publicUsername; protectedContent = facts.protectedContent; linkedDiscussion = facts.linkedDiscussion; autoDelete = facts.autoDelete;
        }
        static ReadersSnapshot from(PurpleSyncChatClient.Info info) {
            if (info == null || !info.readable() || !info.readersKnown) return null;
            ChatFacts f = info.peer.facts;
            Set<String> members=null;
            if(info.members!=null) {
                members=new HashSet<>();
                for(Long id:info.members)if(id!=info.origin.userId)members.add(hash(("purple-sync-reader-v1:"+info.origin.userId+":"+id).getBytes(StandardCharsets.UTF_8)));
            }
            return new ReadersSnapshot(memberHash(info.members,info.origin.userId),members,f.otherMembers,f.otherAdmins,f);
        }
        boolean newReaders(ReadersSnapshot newer) {
            if (newer == null) return false;
            if(creator&&!newer.creator)return true;
            if(memberHashes!=null&&newer.memberHashes!=null)return !memberHashes.containsAll(newer.memberHashes);
            if(otherMembers==null||newer.otherMembers==null)return false;
            return newer.otherMembers>otherMembers || memberHashes==null&&!memberHash.isEmpty()&&!newer.memberHash.isEmpty()
                    &&!memberHash.equals(newer.memberHash)&&newer.otherMembers.equals(otherMembers);
        }
        JSONObject json() {
            JSONObject out = PurpleSyncChatCore.json("kind",kind.name(),"memberHash",memberHash,"creator",creator,"admin",admin,"canSendFiles",canSendFiles,"anonymousAdmin",anonymousAdmin,
                "postsAsOther",postsAsOther,"publicUsername",publicUsername,"protectedContent",protectedContent,"linkedDiscussion",linkedDiscussion,"autoDelete",autoDelete);
            if (otherMembers != null) put(out,"otherMembers",otherMembers.toString());
            if (otherAdmins != null) put(out,"otherAdmins",otherAdmins.toString());
            if(memberHashes!=null) {
                ArrayList<String> sorted=new ArrayList<>(memberHashes);Collections.sort(sorted);JSONArray values=new JSONArray();
                for(String member:sorted)values.put(member);put(out,"memberHashes",values);
            }
            return out;
        }
        static ReadersSnapshot parse(JSONObject value) throws Exception {
            String hash = value.getString("memberHash"); if (!hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("readers hash");
            ChatFacts facts = new ChatFacts();facts.kind=Kind.valueOf(value.getString("kind"));
            facts.creator = bool(value,"creator"); facts.admin = bool(value,"admin"); facts.canSendFiles = bool(value,"canSendFiles"); facts.anonymousAdmin = bool(value,"anonymousAdmin");
            facts.postsAsOther = bool(value,"postsAsOther"); facts.publicUsername = bool(value,"publicUsername"); facts.protectedContent = bool(value,"protectedContent");
            facts.linkedDiscussion = bool(value,"linkedDiscussion"); facts.autoDelete = bool(value,"autoDelete");
            Set<String> members=null;
            if(value.has("memberHashes")) {
                JSONArray array=value.getJSONArray("memberHashes");if(array.length()>200)throw new IllegalArgumentException("readers size");
                members=new HashSet<>();String previous="";
                for(int i=0;i<array.length();++i) {
                    String member=array.getString(i);
                    if(!member.matches("[0-9a-f]{64}")||previous.compareTo(member)>=0)throw new IllegalArgumentException("readers member");
                    members.add(member);previous=member;
                }
            }
            return new ReadersSnapshot(hash,members,value.has("otherMembers") ? decimal(value,"otherMembers") : null,value.has("otherAdmins") ? decimal(value,"otherAdmins") : null,facts);
        }
    }
    static final class Data {
        final Cache location;
        final String title;
        final ReadersSnapshot readers;
        final Set<String> warned;
        final JSONObject journal;
        final boolean newReaders;
        Data(Cache location, String title, ReadersSnapshot readers, Set<String> warned, JSONObject journal, boolean newReaders) {
            this.location = location; this.title = title; this.readers = readers; this.warned = Collections.unmodifiableSet(new HashSet<>(warned));
            this.journal = copy(journal); this.newReaders = newReaders;
        }
        boolean pendingMove() { return journal.length() != 0; }
        boolean accepted(String stream) { return warned.contains(stream); }
        Data next(Cache location, PurpleSyncChatClient.Info info) {
            ReadersSnapshot current = ReadersSnapshot.from(info);
            if (this.location.chat.equals(location.chat)) return new Data(location,info.title,readers != null ? readers : current,warned,journal,newReaders || readers != null && readers.newReaders(current));
            return new Data(location,info.title,current,Collections.emptySet(),new JSONObject(),false);
        }
    }
    static final class Loaded {
        final boolean ok,paused;
        final byte[] bytes;
        final Data data;
        Loaded(boolean ok, byte[] bytes, Data data) { this(ok,bytes,data,false); }
        Loaded(boolean ok,byte[] bytes,Data data,boolean paused) { this.ok=ok;this.bytes=bytes;this.data=data;this.paused=paused; }
    }
    static Loaded load(IO io, PurpleSyncChatOrigin origin, String authority) {
        byte[] bytes = null;
        try {
            bytes = io.read(); if (bytes == null) return new Loaded(true,null,null);
            if (bytes.length > 65536) return new Loaded(false,bytes,null);
            JSONObject value = new JSONObject(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            if (!(value.get("version") instanceof Integer || value.get("version") instanceof Long) || ((Number)value.get("version")).longValue() != 1 || !origin.accountKey().equals(value.getString("account")) ) return new Loaded(false,bytes,null);
            boolean paused=value.has("paused")&&bool(value,"paused");
            boolean admitted=authority.equals(value.getString("authority"));
            if(value.has("pauseOnly")) {
                if(!bool(value,"pauseOnly")||!paused||value.has("chat"))return new Loaded(false,bytes,null);
                return new Loaded(true,bytes,null,true);
            }
            if(!admitted)return new Loaded(true,bytes,null,paused);
            Peer chat = Peer.parse(value.getString("chat"),origin.userId); if (chat == null || !chat.token().equals(value.getString("chat"))) return new Loaded(false,bytes,null);
            Object rawId = value.get("markId"); if (!(rawId instanceof Integer || rawId instanceof Long)) return new Loaded(false,bytes,null);
            long id = ((Number)rawId).longValue(), date = decimal(value,"markDate");
            if (id < 0 || id > Integer.MAX_VALUE || (id == 0) != (date == 0)) return new Loaded(false,bytes,null);
            String title = value.getString("title"); if (title.length() > 1024) return new Loaded(false,bytes,null);
            Set<String> warned = new HashSet<>(); JSONArray array = value.getJSONArray("warned");
            if (array.length() > 2) return new Loaded(false,bytes,null);
            for (int i=0;i<array.length();++i) { String stream = array.getString(i); if (!stream.equals("config") && !stream.equals("library") || !warned.add(stream)) return new Loaded(false,bytes,null); }
            ReadersSnapshot readers = value.has("readers") ? ReadersSnapshot.parse(value.getJSONObject("readers")) : null;
            return new Loaded(true,bytes,new Data(new Cache(chat,(int)id,date),title,readers,warned,value.getJSONObject("journal"),bool(value,"newReaders")),paused);
        } catch (Exception error) { return new Loaded(false,bytes,null); }
    }
    static boolean save(IO io, Loaded opening, PurpleSyncChatOrigin origin, String authority, Data data, BooleanSupplier current) {
        if (opening == null || !opening.ok || opening.paused || data == null || !current.getAsBoolean()) return false;
        try {
            byte[] encoded=encode(origin,authority,data);
            return encoded.length <= 65536 && current.getAsBoolean() && (java.util.Arrays.equals(opening.bytes,encoded) ? java.util.Arrays.equals(opening.bytes,io.read()) : io.write(opening.bytes,encoded)) && current.getAsBoolean();
        } catch (Exception error) { return false; }
    }
    static byte[] encode(PurpleSyncChatOrigin origin,String authority,Data data) {
        JSONArray warned = new JSONArray(); ArrayList<String> streams = new ArrayList<>(data.warned); Collections.sort(streams); for (String stream : streams) warned.put(stream);
        JSONObject value = json("version",1,"account",origin.accountKey(),"authority",authority,"chat",data.location.chat.token(),"markId",data.location.markId,
            "markDate",Long.toString(data.location.markDate),"title",data.title,"warned",warned,"journal",data.journal,"newReaders",data.newReaders);
        if (data.readers != null) put(value,"readers",data.readers.json());
        byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
        return encoded;
    }
    static boolean pause(IO io,Loaded opening,PurpleSyncChatOrigin origin,String authority,boolean paused,BooleanSupplier current) {
        try {
            PurpleAccountFileSnapshot.worker();
            if(opening==null||!opening.ok||!current.getAsBoolean()||opening.data!=null&&opening.data.pendingMove()||!java.util.Arrays.equals(opening.bytes,io.read()))return false;
            if(!paused&&opening.data==null)return current.getAsBoolean()&&(opening.bytes==null||io.remove(opening.bytes));
            JSONObject value=opening.data!=null?new JSONObject(new String(opening.bytes,StandardCharsets.UTF_8)):json("version",1,"account",origin.accountKey(),"authority",authority,"pauseOnly",true);
            if(paused)value.put("paused",true);else value.remove("paused");
            byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
            return bytes.length<=65536&&current.getAsBoolean()&&io.write(opening.bytes,bytes)&&current.getAsBoolean();
        }catch(Exception|LinkageError e){return false;}
    }
    static void forgetLogin(IO io,PurpleSyncChatOrigin origin,String authority) {
        try {
            PurpleAccountFileSnapshot.worker();
            Loaded loaded=load(io,origin,authority);
            if(!loaded.ok||loaded.data==null||loaded.bytes==null)return;
            if(loaded.paused){
                byte[] negative=json("version",1,"account",origin.accountKey(),"authority",authority,"pauseOnly",true,"paused",true).toString().getBytes(StandardCharsets.UTF_8);
                io.write(loaded.bytes,negative);
            }else io.remove(loaded.bytes);
        }catch(Exception|LinkageError error){}
    }
    static IO file(File path) {
        return new IO() {
            public byte[] read() throws Exception {
                PurpleAccountFileSnapshot snapshot = PurpleAccountFileSnapshot.read(path,65536);
                if (snapshot.status == PurpleAccountFileSnapshot.Status.ReadError) throw new java.io.IOException("sync chat cache read");
                return snapshot.bytes;
            }
            public boolean write(byte[] expected, byte[] next) {
                PurpleAccountFileSnapshot snapshot = PurpleAccountFileSnapshot.read(path,65536);
                return snapshot.status != PurpleAccountFileSnapshot.Status.ReadError && java.util.Arrays.equals(snapshot.bytes,expected) && PurpleAccountFileSnapshot.replace(path,snapshot,next);
            }
            public boolean remove(byte[] expected) {
                PurpleAccountFileSnapshot snapshot = PurpleAccountFileSnapshot.read(path,65536);
                return snapshot.status != PurpleAccountFileSnapshot.Status.ReadError && java.util.Arrays.equals(snapshot.bytes,expected)
                    && (snapshot.status == PurpleAccountFileSnapshot.Status.Missing || snapshot.matches(path) && path.delete());
            }
        };
    }
    static String authority(String loginAnchor) {
        if (loginAnchor == null || !loginAnchor.matches("[0-9a-f]{32}")) return null;
        return hash(("purple-sync-chat-login-v1:"+loginAnchor).getBytes(StandardCharsets.UTF_8));
    }
    private static String memberHash(Set<Long> members, long self) {
        if (members == null) return "";
        ArrayList<Long> ids = new ArrayList<>(members); ids.remove(self); Collections.sort(ids); StringBuilder text = new StringBuilder();
        for (Long id : ids) text.append(id).append('\n');
        return hash(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(byte[] bytes) {
        try { byte[] value = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder hex = new StringBuilder(); for(byte b:value)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return hex.toString(); }
        catch (Exception error) { throw new IllegalStateException("sync chat hash"); }
    }
    private static long decimal(JSONObject value, String key) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof String)) throw new IllegalArgumentException("sync chat number");
        long number = Long.parseLong((String)raw); if (number < 0 || !Long.toString(number).equals(raw)) throw new IllegalArgumentException("sync chat number"); return number;
    }
    private static boolean bool(JSONObject value, String key) throws Exception { Object raw = value.get(key); if (!(raw instanceof Boolean)) throw new IllegalArgumentException("sync chat boolean"); return (Boolean)raw; }
    private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch(Exception error) { throw new IllegalArgumentException("sync chat journal"); } }
    private PurpleSyncChatCache() { }
}
