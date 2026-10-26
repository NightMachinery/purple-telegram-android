package org.telegram.messenger.purple;

import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleArchiveSource {
    static final class Prepared {
        final PurpleArchiveStore store;
        final String request;
        final boolean partial;

        Prepared(PurpleArchiveSlice slice, String version) throws Exception {
            store = slice.store;
            slice.version(version);
            JSONObject facts = PurpleArchiveCore.call("sourceFacts", store.arguments().put("rows", slice.rows)
                    .put("key", slice.key).put("version", version), store.crypto());
            if (!facts.getString("status").equals("Ready") || !slice.reader.current())
                throw new PurpleArchiveStore.Failure("Archive source target unavailable");
            partial = facts.getBoolean("partial");
            request = new JSONObject().put("key", slice.key).put("version", version).put("revision", facts.getString("revision"))
                    .put("hash", facts.getString("hash")).put("representation", slice.key.getString("representation"))
                    .put("accountGeneration", facts.getString("accountGeneration")).put("chatGeneration", facts.getString("chatGeneration")).toString();
        }

        JSONObject arguments(PurpleArchiveSlice slice) throws Exception {
            if (slice.store != store) throw new PurpleArchiveStore.Failure("Archive source store changed");
            JSONObject captured = new JSONObject(request);
            if (!captured.getString("accountGeneration").equals(slice.facts.getString("accountGeneration"))
                    || !captured.getString("chatGeneration").equals(slice.facts.getString("chatGeneration")))
                throw new PurpleArchiveStore.Failure("Archive source purge generation changed");
            JSONObject result = store.arguments().put("rows", slice.rows);
            for (String name : new String[]{"key", "version", "revision", "hash", "representation", "accountGeneration", "chatGeneration"})
                result.put(name, captured.get(name));
            return result;
        }
    }

    static final class Choice {
        final boolean genuine, partial;
        private final byte[] bytes;

        Choice(JSONObject value) throws Exception {
            if (!value.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive copy target changed");
            int kind = value.getInt("kind");
            if (kind < 0 || kind > 1) throw new PurpleArchiveStore.Failure("Archive copy source kind");
            genuine = kind == 1;
            partial = value.getBoolean("partial");
            bytes = PurpleArchiveCore.unbase64(value.getString("bytes"));
            if (bytes.length > PurpleMarkdownSource.LIMIT || !genuine && bytes.length != 0 || genuine && partial)
                throw new PurpleArchiveStore.Failure("Archive copy source refused");
        }

        String text() throws Exception {
            if (!genuine) throw new PurpleArchiveStore.Failure("Archive copy needs structural writer");
            return PurpleMarkdownSource.decode(bytes);
        }
    }

    static Choice choose(Prepared prepared, int extent, boolean archived) throws Exception {
        JSONObject captured = new JSONObject(prepared.request);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(prepared.store, captured.getJSONObject("key"), null)) {
            slice.version(captured.getString("version"));
            JSONObject value = PurpleArchiveCore.call("sourceChoose", prepared.arguments(slice).put("extent", extent)
                    .put("target", archived ? 1 : 0), prepared.store.crypto());
            if (!slice.reader.current()) throw new PurpleArchiveStore.Failure("Archive copy snapshot changed");
            return new Choice(value);
        }
    }

    static void bind(Prepared prepared, PurpleMarkdownSource.Candidate candidate, Long local, Long random, BooleanSupplier active) throws Exception {
        if (prepared.partial || candidate == null || !active.getAsBoolean()) throw new PurpleArchiveStore.Failure("Archive source binding refused");
        JSONObject captured = new JSONObject(prepared.request);
        try (PurpleArchiveSlice slice = new PurpleArchiveSlice(prepared.store, captured.getJSONObject("key"), null)) {
            JSONObject version = slice.version(captured.getString("version"));
            JSONObject content = version.getJSONObject("content");
            if (candidate.kind == 0) {
                if (!content.getString("caption").isEmpty() || !content.getString("opaque").isEmpty()
                        || !content.isNull("rich") || content.getJSONArray("media").length() != 0
                        || !candidate.matchesCompose(composeBody(content.getString("text"), content.getJSONArray("entities"))))
                    throw new PurpleArchiveStore.Failure("Archive compose source body differs");
            } else {
                if (!content.getString("text").isEmpty() || !content.getString("caption").isEmpty()
                        || content.getJSONArray("entities").length() != 0 || !content.isNull("rich")
                        || !content.getString("opaque").isEmpty()) throw new PurpleArchiveStore.Failure("Archive file has another displayed body");
                boolean matched = false;
                int documents = 0;
                JSONArray media = content.getJSONArray("media");
                for (int i = 0; i < media.length(); ++i) {
                    JSONObject descriptor = media.getJSONObject(i);
                    if (descriptor.getString("kind").equals("document")) {
                        documents++;
                        if (candidate.matchesFile(descriptor.getString("identity"),
                                PurpleArchiveCore.number(descriptor.getJSONObject("attributes").get("bytes")))) matched = true;
                    } else if (!descriptor.getString("kind").equals("thumbnail"))
                        throw new PurpleArchiveStore.Failure("Archive file has another primary medium");
                }
                if (!matched || documents != 1) throw new PurpleArchiveStore.Failure("Archive Markdown file identity differs");
            }
            JSONObject source = new JSONObject().put("kind", candidate.kind).put("bytes", PurpleArchiveCore.base64(candidate.bytes()));
            if (local != null) source.put("localMessageId", Long.toString(local));
            if (random != null) source.put("randomId", Long.toString(random));
            JSONObject plan = PurpleArchiveCore.call("sourceBind", prepared.arguments(slice).put("source", source), prepared.store.crypto());
            if (!active.getAsBoolean() || !slice.reader.current() || !prepared.store.execute(new PurpleArchiveCore.Program(plan), active).committed)
                throw new PurpleArchiveStore.Failure("Archive source binding not committed");
        }
    }

    static byte[] composeBody(String text, JSONArray entities) throws Exception {
        JSONObject body = new JSONObject().put("text", text).put("entities", entities);
        return PurpleArchiveBinding.canonical(body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private PurpleArchiveSource() {}
}
