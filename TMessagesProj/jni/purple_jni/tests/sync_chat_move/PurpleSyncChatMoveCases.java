package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

public final class PurpleSyncChatMoveCases {
    private static int checks;
    private static final Set<String> actions = new HashSet<>();
    private static final Set<String> statuses = new HashSet<>();
    private static final Set<String> persistedStatuses = new HashSet<>();
    private static JSONObject beginning, journal, planned;

    private static void check(boolean condition, String name) {
        ++checks;
        if (!condition) throw new AssertionError(name);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String encode(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static byte[] decode(String value) { return Base64.getDecoder().decode(value); }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private static PurpleSyncChatMoveCore.Result request(String op, JSONObject input) {
        final PurpleSyncChatMoveCore.Result result = PurpleSyncChatMoveCore.request(op, bytes(input.toString()));
        statuses.add(result.status);
        return result;
    }
    private static void invalid(String op, JSONObject input) {
        check(request(op, input).status.equals("Invalid"), "refused " + op);
    }

    public static void main(String[] args) throws Exception {
        try (java.util.stream.Stream<String> lines = Files.lines(Paths.get(args[0]))) {
            final java.util.Iterator<String> rows = lines.iterator();
            int row = 0;
            while (rows.hasNext()) {
                ++row;
                final JSONObject fixture = new JSONObject(rows.next());
                final String op = fixture.getString("op");
                final JSONObject input = fixture.getJSONObject("input");
                final JSONObject expected = fixture.getJSONObject("expected");
                final PurpleSyncChatMoveCore.Result result = request(op, input);
                for (String key : expected.keySet()) {
                    if (!expected.get(key).equals(result.fields.opt(key))) {
                        Files.write(Paths.get(args[0]).resolveSibling("mismatch.json"), bytes(new JSONObject()
                                .put("fixture", fixture).put("actual", result.fields).toString()));
                    }
                    check(expected.get(key).equals(result.fields.opt(key)), "fixture " + row + " " + op + " " + key);
                }
                statuses.add(result.status);
                if (!result.fields.optString("canonical").isEmpty()) persistedStatuses.add(result.status);
                if (result.fields.has("nextAction")) actions.add(result.fields.getString("nextAction"));
                if (op.equals("begin") && result.status.equals("Valid") && beginning == null) {
                    beginning = copy(input);
                    journal = new JSONObject().put("account", input.getString("account"))
                            .put("source", input.getString("source")).put("target", input.getString("target"))
                            .put("journal", result.fields.getString("canonical"));
                }
                if (op.equals("plan") && result.status.equals("Ready") && planned == null) {
                    planned = copy(input).put("plan", result.fields.getString("plan"));
                }
            }
            check(row > 100, "core fixture breadth");
        }
        check(beginning != null && journal != null && planned != null, "core fixtures available");
        final JSONObject parsed = request("parse", journal).fields;
        check(parsed.getString("canonical").equals(journal.getString("journal")), "core canonical round trip");
        for (String field : new String[]{"account", "source", "target"}) {
            final JSONObject wrong = copy(journal);
            wrong.put(field, field.equals("account") ? "1-777" : "channel:10");
            invalid("parse", wrong);
        }
        final JSONObject raw = new JSONObject(new String(decode(journal.getString("journal")), StandardCharsets.UTF_8));
        raw.put("extension", new JSONObject().put("counter", 9007199254740991L));
        final JSONObject unknown = copy(journal).put("journal", encode(bytes(raw.toString())));
        final JSONObject roundTrip = new JSONObject(new String(decode(request("serialize", unknown).fields.getString("canonical")), StandardCharsets.UTF_8));
        check(roundTrip.getJSONObject("extension").getLong("counter") == 9007199254740991L, "unknown members preserved exactly");
        raw.put("revision", 9007199254740991L);
        check(request("parse", copy(journal).put("journal", encode(bytes(raw.toString())))).status.equals("Valid"), "maximum safe revision");
        raw.put("revision", 9007199254740992L);
        invalid("parse", copy(journal).put("journal", encode(bytes(raw.toString()))));
        raw.put("revision", 0).put("own_request", "18446744073709551615");
        check(request("parse", copy(journal).put("journal", encode(bytes(raw.toString())))).status.equals("Valid"), "full uint64 request identity");
        raw.put("version", 2);
        check(request("parse", copy(journal).put("journal", encode(bytes(raw.toString())))).status.equals("NewerVersion"), "newer journal refused");
        statuses.add("NewerVersion");
        invalid("parse", copy(journal).put("journal", encode(bytes("{\"version\":1,\"version\":1}"))));
        check(PurpleSyncChatMoveCore.request("parse", bytes("{\"journal\":\"\",\"journal\":\"\"}")).status.equals("Invalid"), "duplicate wrapper refused");
        check(PurpleSyncChatMoveCore.request("hash", new byte[]{(byte) 0xff}).status.equals("Invalid"), "invalid UTF-8 refused");
        check(PurpleSyncChatMoveCore.request("hash", new byte[4 * 1024 * 1024 + 1]).status.equals("Invalid"), "oversize refused");
        invalid("hash", new JSONObject().put("bytes", "YQ"));
        invalid("unknown", journal);
        final JSONObject event = request("event", copy(journal).put("kind", "Resolution")).fields.getJSONObject("event");
        event.put("outcome", "Success").put("resolution", beginning.getJSONObject("resolution"));
        final JSONObject advance = copy(journal).put("event", event);
        check(request("advance", advance).status.equals("Valid"), "captured event scope");
        event.put("revision", "18446744073709551615");
        invalid("advance", advance);
        event.put("revision", 0);
        invalid("advance", advance);
        check(request("usesState", planned).fields.getBoolean("usesState"), "plan uses exact state");
        check(request("select", new JSONObject().put("inventory", planned.getJSONObject("oldInventory"))).status.equals("Ready"), "head selection");
        final JSONObject destination = copy(planned.getJSONObject("newInventory"));
        final JSONObject verify = copy(planned).put("destination", destination);
        check(request("verify", verify).status.equals("MissingRecords"), "destination missing required records");
        destination.put("complete", false);
        check(request("verify", verify).status.equals("ReadDestination"), "incomplete destination holds");
        final JSONObject foreign = copy(destination).put("account", "1-777").put("messages", new org.json.JSONArray()).put("complete", true);
        check(request("verify", copy(planned).put("destination", foreign)).status.equals("WrongScope"), "foreign destination scope");
        final JSONObject missingReaders = copy(planned);
        final JSONObject eligibility = copy(planned.getJSONObject("eligibility"));
        final JSONObject readers = copy(eligibility.getJSONObject("readers"));
        readers.remove("warningRequired");
        eligibility.put("readers", readers);
        missingReaders.put("eligibility", eligibility);
        invalid("plan", missingReaders);
        final JSONObject missingStageFact = copy(planned);
        missingStageFact.remove("otherStagesPresent");
        invalid("plan", missingStageFact);
        final JSONObject message = planned.getJSONObject("oldInventory").getJSONArray("messages").getJSONObject(0);
        check(request("message", message).status.equals("Ready"), "scoped message construction");
        check(request("hash", new JSONObject().put("bytes", message.getString("bytes"))).fields.getString("hash").length() == 64, "record hash");
        final JSONObject commands = request("commands", journal).fields;
        check(!commands.getBoolean("postsOwn") && commands.getJSONArray("forwards").length() == 0, "no delivery command before planning");
        final JSONObject state = new JSONObject(new String(decode(planned.getString("state")), StandardCharsets.UTF_8));
        state.put("retained_config_bases", new JSONObject().put("version", 1).put("records", new org.json.JSONArray())
                .put("extension", "retained"));
        final JSONObject retainedInput = copy(planned).put("state", encode(bytes(state.toString())));
        final PurpleSyncChatMoveCore.Result retainedPlan = request("plan", retainedInput);
        check(retainedPlan.status.equals("Ready"), "retained base state admitted");
        final JSONObject complete = copy(planned.getJSONObject("oldInventory")).put("chat", planned.getString("target"));
        final org.json.JSONArray carried = complete.getJSONArray("messages");
        for (int i = 0; i < carried.length(); ++i) carried.getJSONObject(i).put("chat", planned.getString("target")).put("id", Integer.toString(i + 1));
        final JSONObject commitInput = copy(retainedInput).put("plan", retainedPlan.fields.getString("plan")).put("destination", complete);
        final PurpleSyncChatMoveCore.Result committed = request("prepareCommit", commitInput);
        check(committed.status.equals("Ready"), "prepared carry commit");
        final JSONObject committedState = new JSONObject(new String(decode(committed.fields.getString("canonical")), StandardCharsets.UTF_8));
        check(committedState.getJSONObject("retained_config_bases").getString("extension").equals("retained"), "retained base extension survives prepared state");
        check(committedState.getString("chat").equals(planned.getString("target")), "prepared state has typed target");
        check(request("verify", commitInput).status.equals("Ready"), "complete destination verified");
        final JSONObject playlistInput = copy(retainedInput).put("playlistsInUse", true);
        final PurpleSyncChatMoveCore.Result playlistPlan = request("plan", playlistInput);
        check(playlistPlan.status.equals("Ready"), "playlist-aware plan");
        check(request("verify", copy(commitInput).put("plan", playlistPlan.fields.getString("plan"))).status.equals("PlaylistRequired"), "missing playlist receipt holds");

        check(request("cleanupCandidates", commitInput).fields.getJSONArray("references").length() == 0, "no unaccepted cleanup candidates");
        check(!request("sourceMatchesCleanup", copy(commitInput).put("message", message)).fields.getBoolean("allowed"), "no cleanup without authorization");
        check(request("prepareStage", retainedInput.put("plan", retainedPlan.fields.getString("plan"))).status.equals("Invalid"), "no invented own stage");

        final JSONObject planDocument = new JSONObject(new String(decode(retainedPlan.fields.getString("plan")), StandardCharsets.UTF_8));
        planDocument.put("extension", "preserved");
        final JSONObject planRound = copy(retainedInput).put("plan", encode(bytes(planDocument.toString())));
        check(new JSONObject(new String(decode(request("parsePlan", planRound).fields.getString("plan")), StandardCharsets.UTF_8))
                .getString("extension").equals("preserved"), "unknown plan member preserved");
        for (String name : new String[]{"None", "ResolvePending", "WritePendingState", "RetirePendingStage", "ResolveAgain", "Plan",
                "StageOwn", "Deliver", "VerifyDestination", "PostMark", "ReadMark", "PrepareCache", "CommitState", "RetireOwnStage", "CompleteCache", "Cleanup"}) {
            check(actions.contains(name), "core action covered " + name);
        }
        for (String name : new String[]{"Invalid", "Valid", "NewerVersion", "Wait", "Retry", "NeedsReview", "Superseded"}) {
            check(statuses.contains(name), "core status covered " + name);
        }
        for (String name : new String[]{"Invalid", "Ready", "ReadSource", "ReadDestination", "FinishPendingOld", "AbandonPending", "UnknownSource",
                "MissingLocalVersion", "NeedsReview", "ConsentRequired", "WrongScope", "MissingRecords", "PlaylistRequired"}) {
            check(statuses.contains(name), "carry status covered " + name);
        }
        for (String name : new String[]{"Wait", "Retry", "NeedsReview", "Superseded"}) {
            check(persistedStatuses.contains(name), "nonempty canonical status covered " + name);
        }
        System.out.println("SYNC_CHAT_MOVE_CHECKS=" + checks);
        System.out.println("SYNC_CHAT_MOVE_ACTIONS=" + actions.size());
    }
}
