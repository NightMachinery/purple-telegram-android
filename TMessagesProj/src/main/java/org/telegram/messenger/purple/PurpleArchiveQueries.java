package org.telegram.messenger.purple;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveQueries implements AutoCloseable {
    static final long IDLE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final BigInteger MAX_BYTES = new BigInteger("18446744073709551615");
    public static final class Scope {
        final PurpleAccountFiles.Identity identity;
        final String encoded;
        final long selection;
        final java.util.function.BooleanSupplier policy;
        public final String title;
        Scope(PurpleAccountFiles.Identity identity, JSONObject value, String title, long selection) throws Exception { this(identity,value,title,selection,() -> true); }
        Scope(PurpleAccountFiles.Identity identity, JSONObject value, String title, long selection, java.util.function.BooleanSupplier policy) throws Exception {
            if (identity == null || value.getJSONArray("aliases").length() > 31) throw new IllegalArgumentException("Archive scope unavailable");
            this.identity = identity; encoded = value.toString(); this.title = title; this.selection = selection; this.policy = policy;
        }
        JSONObject value() throws Exception { return new JSONObject(encoded); }
        public String account() { return Long.toString(identity.userId); }
        public String topic() { try { return value().has("topic") ? value().getJSONObject("topic").getString("id") : ""; } catch (Exception e) { return ""; } }
        public static Scope capture(int account, long dialog, TLRPC.User user, TLRPC.Chat chat, TLRPC.EncryptedChat secret, TLRPC.ChatFull info, long topic) throws Exception {
            PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(account);
            if (identity == null || account != UserConfig.selectedAccount || topic < 0) throw new IllegalStateException("Archive opening account changed");
            int gate = PurpleGate.generation(); long revision = PurpleAccountOwner.observedRevision(), role = PurpleAccountOwner.observedRoleEpoch();
            JSONObject parent; String title; JSONArray aliases = new JSONArray();
            if (secret != null && DialogObject.isEncryptedDialog(dialog) && DialogObject.getEncryptedChatId(dialog) == secret.id && secret.id > 0) {
                if (topic != 0) throw new IllegalArgumentException("Archive secret topic");
                parent = chat(3, secret.id); title = user == null ? Long.toString(secret.id) : name(user);
            } else if (chat != null && chat.id > 0 && dialog == -chat.id) {
                boolean channel = ChatObject.isChannel(chat); parent = chat(channel ? 2 : 1, chat.id); title = chat.title;
                if (channel && info != null && info.id == chat.id && info.migrated_from_chat_id > 0) aliases.put(chat(1, info.migrated_from_chat_id));
                if (!channel && chat.migrated_to != null && chat.migrated_to.channel_id > 0) aliases.put(chat(2, chat.migrated_to.channel_id));
                if (topic != 0 && !channel) throw new IllegalArgumentException("Archive topic parent");
            } else if (user != null && user.id > 0 && dialog == user.id && topic == 0) { parent = chat(0, user.id); title = name(user); }
            else throw new IllegalArgumentException("Archive typed parent unavailable");
            JSONObject value = new JSONObject().put("chat", parent).put("aliases", aliases).put("collection", 0).put("representation", "original");
            if (topic != 0) value.put("topic", new JSONObject().put("chat", parent).put("id", Long.toString(topic)));
            return new Scope(identity, value, title == null || title.isEmpty() ? parent.getString("id") : title, PurpleArchiveViewerModel.selectionGeneration(),
                () -> gate == PurpleGate.generation() && revision == PurpleAccountOwner.observedRevision() && role == PurpleAccountOwner.observedRoleEpoch());
        }
        static JSONObject chat(int kind, long id) throws Exception { return new JSONObject().put("kind", kind).put("id", Long.toString(id)); }
        private static String name(TLRPC.User user) { return ((user.first_name == null ? "" : user.first_name) + " " + (user.last_name == null ? "" : user.last_name)).trim(); }
    }
    public static final class Entry {
        final String key, version, versionDigest, query, accountGeneration, chatGeneration;
        final PurpleArchiveStore store;
        public final String text, caption, rich, observedAt, senderUser, senderGroup, senderChannel, reply, topic;
        public final boolean body, partial, missingPreimage, truncated;
        public final int status;
        public final List<Integer> media;
        Entry(JSONObject value, PurpleArchiveQueries query) throws Exception {
            this.query = query.token; store = query.store; status = value.getInt("deletedStatus");
            JSONObject shell = decoded(value.getString("shell")).getJSONObject("value"); key = shell.getJSONObject("key").toString();
            JSONObject chat = shell.getJSONObject("key").getJSONObject("chat"); accountGeneration = query.accountGeneration;
            chatGeneration = query.generations.get(chat.getInt("kind") + ":" + chat.getString("id"));
            if (chatGeneration == null) throw new PurpleArchiveStore.Failure("Archive opening generation unavailable");
            observedAt = shell.getString("deletedObservedAt");
            body = !value.isNull("head"); ArrayList<Integer> states = new ArrayList<>();
            if (body) {
                JSONObject head = decoded(value.getString("head")).getJSONObject("value").getJSONObject("snapshot");
                version = head.getString("id"); versionDigest = PurpleArchiveNormalize.digest(head.toString());
                JSONObject content = head.getJSONObject("content"), observation = head.getJSONObject("observation"), context = observation.getJSONObject("context");
                String originalText = content.getString("text"), originalCaption = content.getString("caption");
                text = clipped(originalText); caption = clipped(originalCaption);
                JSONObject object = content.optJSONObject("rich");
                String originalRich = object == null ? "" : object.optString("title") + "\n" + object.optString("description") + "\n" + object.optString("text");
                rich = clipped(originalRich).trim(); truncated = originalText.length() > 8192 || originalCaption.length() > 8192 || originalRich.length() > 8192;
                partial = head.getInt("completeness") != 1; missingPreimage = head.getBoolean("missingPreimage"); topic = observation.getString("topicId");
                senderUser = fact(context,"senderUser"); senderGroup = fact(context,"senderChat"); senderChannel = fact(context,"senderChannel"); reply = fact(context,"reply");
                JSONArray refs = head.getJSONArray("media"); if (refs.length() > 64) throw new PurpleArchiveStore.Failure("Archive media display limit");
                for (int i = 0; i < refs.length(); ++i) { int state = refs.getJSONObject(i).getInt("state"); if (state < 0 || state > 2) throw new IllegalStateException("Archive media state"); states.add(state); }
            } else {
                version = versionDigest = text = caption = rich = senderUser = senderGroup = senderChannel = reply = topic = "";
                partial = missingPreimage = truncated = false;
            }
            media = Collections.unmodifiableList(states);
        }
        private static String fact(JSONObject context, String name) {
            String value = context.optString(name, "");
            try { return !value.equals("0") && Long.toString(Long.parseLong(value)).equals(value) ? value : ""; } catch (NumberFormatException e) { return ""; }
        }
        private static String clipped(String value) { return value.substring(0, Math.min(8192, value.length())); }
    }
    public static final class Page {
        public final String status, logicalBytes, physicalBytes;
        public final List<Entry> entries;
        public final List<Long> counts;
        public final boolean more, logicalComplete, gap;
        Page(String status, List<Entry> entries, long[] counts, String logical, String physical, boolean more, boolean complete, boolean gap) {
            this.status = status; this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            ArrayList<Long> values = new ArrayList<>(); if (counts != null) for (long value : counts) values.add(value);
            this.counts = counts == null ? null : Collections.unmodifiableList(values); logicalBytes = logical; physicalBytes = physical;
            this.more = more; logicalComplete = complete; this.gap = gap;
        }
        static Page failure(String status) { return new Page(status, Collections.emptyList(), null, "", "", false, false, false); }
    }
    final Scope scope;
    final PurpleArchiveStore store;
    final PurpleArchiveStore.Reader reader;
    final String token = PurpleArchiveBinding.token(), login;
    final LongSupplier clock;
    final int order;
    final long[] counts = new long[7];
    final HashMap<String,String> generations = new HashMap<>();
    String accountGeneration;
    final String physical;
    volatile long lastUse;
    private JSONObject cursor;
    private String blobCursor = "";
    private BigInteger logical = BigInteger.ZERO;
    private boolean more = true, complete;
    volatile boolean refreshSuggested;
    PurpleArchiveQueries(PurpleArchiveStore store, Scope scope, String login, int order, String physical, LongSupplier clock) throws Exception {
        this.store = store; this.scope = scope; this.login = login; this.order = order; this.physical = physical; this.clock = clock; lastUse = clock.getAsLong();
        if (order < 0 || order > 1 || !store.binding.account.equals(scope.identity.key)) throw new IllegalArgumentException("Archive query authority");
        reader = store.retainedReader();
    }
    void open() throws Exception {
        plan("deleted");
        JSONObject headerPlan = PurpleArchiveCore.call("query", store.arguments().put("query", "header"), store.crypto());
        PurpleArchiveSqlite.Value[] header = read(headerPlan, 3).values[0];
        JSONObject opened = PurpleArchiveCore.call("openRow", store.arguments().put("row", row(1, header[0].text(), header[2])), store.crypto());
        accountGeneration = opened.getString("accountGeneration");
        PurpleArchiveSqlite.Result totals = read(plan("counts"), 7);
        if (totals.rows != 1) throw new PurpleArchiveStore.Failure("Archive counts unavailable");
        for (int i = 0; i < counts.length; ++i) { if (totals.values[0][i].type != PurpleArchiveSqlite.INTEGER || totals.values[0][i].integer < 0) throw new IllegalStateException("Archive count type"); counts[i] = totals.values[0][i].integer; }
        JSONObject value = scope.value(); ArrayList<JSONObject> chats = new ArrayList<>(); chats.add(value.getJSONObject("chat"));
        JSONArray aliases = value.getJSONArray("aliases"); for (int i = 0; i < aliases.length(); ++i) chats.add(aliases.getJSONObject(i));
        for (JSONObject chat : chats) {
            JSONObject query = PurpleArchiveCore.call("viewPlan", arguments().put("query", "epoch").put("chat", chat), store.crypto());
            PurpleArchiveSqlite.Result rows = read(query, 2); String generation = "0";
            if (rows.rows == 1) generation = PurpleArchiveCore.call("viewEpoch", arguments().put("chat", chat).put("generation", rows.values[0][0].text())
                .put("row", row(8, query.getString("id"), rows.values[0][1])), store.crypto()).getString("generation");
            generations.put(chat.getInt("kind") + ":" + chat.getString("id"), generation);
        }
    }
    JSONObject arguments() throws Exception { return store.arguments().put("scope", scope.value()); }
    JSONObject plan(String query) throws Exception { return PurpleArchiveCore.call("viewPlan", arguments().put("query", query).put("order", order).put("limit", 128).put("cursor", cursor == null ? JSONObject.NULL : cursor).put("after", blobCursor), store.crypto()); }
    PurpleArchiveSqlite.Result read(JSONObject plan, int columns) throws Exception {
        PurpleArchiveSqlite.Result result = reader.read(new PurpleArchiveCore.Statement(plan.getJSONObject("statement")));
        if (result.rows != result.values.length) throw new IllegalStateException("Archive row count");
        for (PurpleArchiveSqlite.Value[] values : result.values) if (values.length != columns) throw new IllegalStateException("Archive query shape");
        return result;
    }
    static JSONObject row(int kind, String id, PurpleArchiveSqlite.Value frame) throws Exception {
        if (frame.type != PurpleArchiveSqlite.BLOB) throw new IllegalStateException("Archive envelope type");
        return new JSONObject().put("kind", kind).put("id", id).put("position", "0").put("frame", PurpleArchiveCore.base64(frame.bytes));
    }
    static JSONObject decoded(String frame) throws Exception {
        byte[] bytes = PurpleArchiveCore.unbase64(frame); if (bytes == null) throw new IllegalStateException("Archive display row");
        try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); } finally { Arrays.fill(bytes, (byte) 0); }
    }
    boolean expired() { return clock.getAsLong() - lastUse >= IDLE_NANOS; }
    static boolean generationCurrent(PurpleArchiveStore store, JSONObject scope, JSONObject chat, String expected) throws Exception {
        JSONObject arguments = store.arguments().put("scope", scope).put("chat", chat);
        JSONObject query = PurpleArchiveCore.call("viewPlan", arguments.put("query", "epoch"), store.crypto());
        PurpleArchiveSqlite.Result result = store.readStatement(new PurpleArchiveCore.Statement(query.getJSONObject("statement")));
        if (result.rows == 0) return expected.equals("0");
        if (result.rows != 1 || result.values[0].length != 2) throw new PurpleArchiveStore.Failure("Archive scope epoch shape");
        String generation = PurpleArchiveCore.call("viewEpoch", store.arguments().put("scope", scope).put("chat", chat)
            .put("generation", result.values[0][0].text()).put("row", row(8,query.getString("id"),result.values[0][1])),store.crypto()).getString("generation");
        return generation.equals(expected);
    }
    boolean current() {
        if (expired() || !reader.current()) return false;
        try {
            JSONObject value = scope.value(); ArrayList<JSONObject> chats = new ArrayList<>(); chats.add(value.getJSONObject("chat"));
            JSONArray aliases = value.getJSONArray("aliases"); for (int i = 0; i < aliases.length(); ++i) chats.add(aliases.getJSONObject(i));
            for (JSONObject chat : chats) if (!generationCurrent(store,value,chat,generations.get(chat.getInt("kind") + ":" + chat.getString("id")))) return false;
            refreshSuggested |= reader.changed(); return true;
        } catch (Exception | LinkageError e) { return false; }
    }
    Page next(int limit) throws Exception {
        if (limit < 1 || limit > 128 || !current()) throw new PurpleArchiveStore.Failure("Archive query stale");
        JSONObject query = arguments().put("query", "deleted").put("limit", limit).put("order", order).put("cursor", cursor == null ? JSONObject.NULL : cursor);
        PurpleArchiveSqlite.Result rows = more ? read(PurpleArchiveCore.call("viewPlan", query, store.crypto()), 6) : null;
        ArrayList<Entry> entries = new ArrayList<>(); JSONObject previous = cursor;
        if (rows != null) for (PurpleArchiveSqlite.Value[] values : rows.values) {
            if (values[1].type != PurpleArchiveSqlite.INTEGER || values[1].integer < 0 || values[2].type != PurpleArchiveSqlite.INTEGER || values[2].integer == 0) throw new IllegalStateException("Archive transcript tuple");
            String token = values[0].text(), headId = values[4].text();
            JSONObject position = new JSONObject().put("date", Long.toString(values[1].integer)).put("id", Long.toString(values[2].integer)).put("token", token);
            if (previous != null && (order == 0 ? compare(previous,position) >= 0 : compare(previous,position) <= 0)) throw new IllegalStateException("Archive cursor order");
            JSONObject shell = row(2, token, values[3]), head = null;
            if (!headId.isEmpty()) {
                head = row(3, token + "/" + headId, values[5]); PurpleArchiveSlice.open(store, reader, head);
            } else if (values[5].type != PurpleArchiveSqlite.NULL) throw new IllegalStateException("Archive bodyless head projection");
            JSONObject decoded = PurpleArchiveCore.call("viewEntry", arguments().put("token", token).put("date", position.getString("date")).put("messageId", position.getString("id"))
                .put("headId", headId).put("shell", shell).put("head", head == null ? JSONObject.NULL : head), store.crypto());
            entries.add(new Entry(decoded,this)); previous = position;
        }
        if (!complete) usage();
        if (!current()) throw new PurpleArchiveStore.Failure("Archive query stale");
        cursor = previous; if (rows != null) more = rows.rows == limit; lastUse = clock.getAsLong();
        return new Page("Ready", entries, counts, logical.toString(), physical, more, complete, store.hasGaps());
    }
    private void usage() throws Exception {
        PurpleArchiveSqlite.Result rows = read(plan("blobs"), 3);
        for (PurpleArchiveSqlite.Value[] values : rows.values) {
            String token = values[0].text(); if (token.compareTo(blobCursor) <= 0) throw new IllegalStateException("Archive blob cursor");
            String bytes = PurpleArchiveCore.call("viewBlob", arguments().put("bytes", values[1].text()).put("row", row(5,token,values[2])),store.crypto()).getString("bytes");
            logical = totalBytes(logical,bytes); blobCursor = token;
        }
        complete = rows.rows < 128;
    }
    static BigInteger totalBytes(BigInteger current, String bytes) {
        if (current.signum() < 0 || current.compareTo(MAX_BYTES) > 0) throw new IllegalArgumentException("Archive logical bytes");
        BigInteger next = current.add(PurpleArchiveBinding.generation(bytes));
        if (next.compareTo(MAX_BYTES) > 0) throw new IllegalStateException("Archive logical bytes overflow");
        return next;
    }
    static int compare(JSONObject a, JSONObject b) throws Exception {
        int order = Long.compare(Long.parseLong(a.getString("date")),Long.parseLong(b.getString("date")));
        if (order == 0) order = Long.compare(Long.parseLong(a.getString("id")),Long.parseLong(b.getString("id")));
        return order != 0 ? order : a.getString("token").compareTo(b.getString("token"));
    }
    @Override public void close() { reader.close(); }
}
