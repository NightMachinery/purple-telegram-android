package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleArchiveSlice implements AutoCloseable {
    final PurpleArchiveStore store;
    final PurpleArchiveStore.Reader reader;
    final JSONObject key;
    final JSONArray rows = new JSONArray();
    final HashSet<String> loaded = new HashSet<>();
    String token;
    JSONObject head, facts;
    PurpleArchiveSlice(PurpleArchiveStore store, JSONObject key, String replay) throws Exception {
        this.store = store; this.key = key; reader = store.reader();
        try {
            JSONObject plan = plan("header"); PurpleArchiveSqlite.Value[] header = read(plan).values[0];
            add(1, header[0].text(), header[1].integer, header[2].bytes);
            plan = plan("message"); token = plan.getString("id"); PurpleArchiveSqlite.Result message = read(plan);
            if (message.rows == 1) {
                PurpleArchiveSqlite.Value[] values = message.values[0];
                add(2, token, 0, values[2].bytes);
                String headId = values[1].text();
                if (!headId.isEmpty()) {
                    PurpleArchiveSqlite.Result result = read(plan("head"));
                    if (result.rows != 1) throw new PurpleArchiveStore.Failure("Archive head missing");
                    JSONObject decoded = add(3, token + "/" + headId, 0, result.values[0][0].bytes);
                    head = decoded.getJSONObject("value").getJSONObject("snapshot");
                    receipt(head.getString("id")); receipt(head.getString("revision")); dependencies(head);
                }
                if (replay != null && !replay.isEmpty()) receipt(replay);
            }
            plan = plan("epoch"); PurpleArchiveSqlite.Result epoch = read(plan);
            if (epoch.rows == 1) add(8, plan.getString("id"), 0, epoch.values[0][1].bytes);
            facts = PurpleArchiveCore.call("facts", store.arguments().put("rows", rows).put("key", key), store.crypto());
            if (!facts.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive slice refused");
        } catch (Exception | LinkageError e) { reader.close(); throw e; }
    }
    JSONObject plan(String name) throws Exception {
        return PurpleArchiveCore.call("query", store.arguments().put("key", key).put("query", name), store.crypto());
    }
    PurpleArchiveSqlite.Result read(JSONObject plan) throws Exception {
        if (!plan.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive query unavailable");
        return reader.read(new PurpleArchiveCore.Statement(plan.getJSONObject("statement")));
    }
    JSONObject add(int kind, String id, long position, byte[] frame) throws Exception {
        JSONObject row = new JSONObject().put("kind", kind).put("id", id).put("position", Long.toString(position))
            .put("frame", PurpleArchiveCore.base64(frame));
        JSONObject decoded = open(store, reader, row);
        if (loaded.add(kind + ":" + id)) rows.put(row);
        return decoded;
    }
    boolean receipt(String id) throws Exception {
        JSONObject query = PurpleArchiveCore.call("query", store.arguments().put("query", "receipt").put("key", key).put("event", id), store.crypto());
        PurpleArchiveSqlite.Result result = read(query);
        if (result.rows == 0) return false;
        add(4, query.getString("id"), 0, result.values[0][0].bytes); return true;
    }
    void media(JSONObject binding, String blob) throws Exception {
        JSONObject source = binding.getJSONObject("source");
        String version = source.getString("version");
        if (!facts.optString("head").equals(version)) {
            JSONObject query = PurpleArchiveCore.call("query", store.arguments().put("query", "version").put("key", key).put("version", version), store.crypto());
            PurpleArchiveSqlite.Result found = read(query);
            if (found.rows != 1) throw new PurpleArchiveStore.Failure("Archive historical media version missing");
            JSONObject historical = add(3, query.getString("id"), 0, found.values[0][0].bytes).getJSONObject("value").getJSONObject("snapshot");
            receipt(historical.getString("id")); receipt(historical.getString("revision")); dependencies(historical);
        }
        JSONObject query = PurpleArchiveCore.call("query", store.arguments().put("query", "jobs").put("key", key).put("version", version), store.crypto());
        PurpleArchiveSqlite.Result jobs = read(query);
        if (jobs.rows >= 128) throw new PurpleArchiveStore.Failure("Archive media job slice limit");
        for (PurpleArchiveSqlite.Value[] job : jobs.values) add(6, job[0].text(), 0, job[1].bytes);
        if (blob != null && !blob.isEmpty()) blob(blob);
    }
    void blob(String id) throws Exception {
        JSONObject query = PurpleArchiveCore.call("query", store.arguments().put("query", "blob").put("key", key).put("record", id), store.crypto());
        PurpleArchiveSqlite.Result found = read(query);
        if (found.rows == 1) add(5, found.values[0][0].text(), 0, found.values[0][1].bytes);
    }
    private void dependencies(JSONObject version) throws Exception {
        JSONArray media = version.getJSONArray("media"); boolean staged = false;
        for (int i = 0; i < media.length(); ++i) {
            JSONObject ref = media.getJSONObject(i); staged |= ref.getInt("state") == 1;
            if (ref.getInt("state") == 2) blob(ref.getString("blobId"));
        }
        if (!staged) return;
        JSONObject query = PurpleArchiveCore.call("query", store.arguments().put("query", "jobs").put("key", key).put("version", version.getString("id")), store.crypto());
        PurpleArchiveSqlite.Result jobs = read(query);
        if (jobs.rows >= 128) throw new PurpleArchiveStore.Failure("Archive media job slice limit");
        for (PurpleArchiveSqlite.Value[] job : jobs.values) {
            JSONObject value = add(6, job[0].text(), 0, job[1].bytes).getJSONObject("value");
            if (value.getInt("state") == 1 && !value.getString("blobId").isEmpty()) blob(value.getString("blobId"));
        }
    }
    static JSONObject open(PurpleArchiveStore store, PurpleArchiveStore.Reader reader, JSONObject row) throws Exception {
        if (row.getInt("kind") == 3) {
            JSONObject body = PurpleArchiveCore.call("bodyQuery", store.arguments().put("row", row), store.crypto());
            if (body.getBoolean("needed")) {
                PurpleArchiveCore.Statement query = new PurpleArchiveCore.Statement(body.getJSONObject("statement"));
                PurpleArchiveSqlite.Result result = reader.read(query); JSONArray chunks = new JSONArray();
                for (PurpleArchiveSqlite.Value[] values : result.values) chunks.put(PurpleArchiveCore.base64(values[values.length - 1].bytes));
                row.put("chunks", chunks);
            }
        }
        JSONObject decoded = PurpleArchiveCore.call("openRow", store.arguments().put("row", row), store.crypto());
        if (!decoded.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive row unavailable");
        return new JSONObject(new String(PurpleArchiveCore.unbase64(decoded.getString("row")), StandardCharsets.UTF_8));
    }
    @Override public void close() { reader.close(); }
}
