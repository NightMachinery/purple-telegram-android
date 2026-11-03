package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Host cases for PurpleSyncMagicCore against the host-built purplecore: the
 * JSON both ways, every bridge operation, the problem codes and the refusal
 * of malformed input. The policy itself is tested in purple-core.
 */
public final class PurpleSyncMagicCases {
    private static int checks;
    private static final long NOW = 1792886400L;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        ++checks;
    }

    private static PurpleSyncMagicCore.Candidate chat(long id, Long others, Long admins) {
        PurpleSyncMagicCore.Candidate result = new PurpleSyncMagicCore.Candidate();
        result.channelId = id;
        result.title = PurpleSyncMagicCore.TITLE;
        result.megagroup = true;
        result.facts.kind = PurpleSyncChatCore.Kind.Supergroup;
        result.facts.creator = true;
        result.facts.admin = true;
        result.facts.soleAdmin = true;
        result.facts.canSendFiles = true;
        result.facts.otherMembers = others;
        result.facts.otherAdmins = admins;
        return result;
    }

    private static PurpleSyncMagicCore.Binding parsed(String text) {
        check(text != null, "the edit succeeded");
        PurpleSyncMagicCore.Binding result = PurpleSyncMagicCore.parse(text);
        check(result != null, "the edit's binding parses");
        return result;
    }

    private static PurpleSyncChatCore.Peer channel(long id) {
        return PurpleSyncChatCore.Peer.of(PurpleSyncChatCore.PeerKind.Channel, id);
    }

    public static void main(String[] args) {
        PurpleCore.ensureLoaded();

        // Problem codes match core's table, both ways.
        for (PurpleSyncMagicCore.Problem problem : PurpleSyncMagicCore.Problem.values()) {
            String raised = PurpleSyncMagicCore.raise("", problem);
            PurpleSyncMagicCore.Binding parsed = parsed(raised);
            check(parsed != null && parsed.problem == problem, "problem code round trip: " + problem.code());
        }
        check(PurpleSyncMagicCore.Problem.ViaBotRecord.code().equals("via_bot_record"), "codes are snake case");

        // Title.
        check(PurpleSyncMagicCore.isTitle("Magic Purple Sync"), "the exact title is magic");
        check(!PurpleSyncMagicCore.isTitle("magic purple sync"), "the title is case sensitive");
        check(!PurpleSyncMagicCore.isTitle(" Magic Purple Sync"), "the title is not trimmed");
        check(!PurpleSyncMagicCore.isTitle(null), "no title is not magic");

        // Check.
        check(PurpleSyncMagicCore.check(chat(1000, 0L, 0L)).verdict == PurpleSyncMagicCore.Verdict.Eligible, "a sole-member megagroup is eligible");
        check(PurpleSyncMagicCore.check(chat(1000, null, null)).verdict == PurpleSyncMagicCore.Verdict.Unverified, "unknown counts are unverified");
        PurpleSyncMagicCore.Check extra = PurpleSyncMagicCore.check(chat(1000, 1L, 0L));
        check(extra.verdict == PurpleSyncMagicCore.Verdict.Ineligible && extra.problem == PurpleSyncMagicCore.Problem.ExtraMember, "another member is refused");
        PurpleSyncMagicCore.Candidate open = chat(1000, 0L, 0L);
        open.facts.publicUsername = true;
        check(PurpleSyncMagicCore.check(open).problem == PurpleSyncMagicCore.Problem.PublicUsername, "a public chat is refused");
        PurpleSyncMagicCore.Candidate left = chat(1000, 0L, 0L);
        left.member = false;
        check(PurpleSyncMagicCore.check(left).problem == PurpleSyncMagicCore.Problem.ChatLeft, "a left chat is refused");
        check(PurpleSyncMagicCore.check(null).verdict == PurpleSyncMagicCore.Verdict.Ineligible, "no chat is ineligible");

        // Plan: an unbound device with nothing visible waits for its lists, then creates once they are complete.
        PurpleSyncMagicCore.LookupInput input = new PurpleSyncMagicCore.LookupInput();
        input.now = NOW;
        PurpleSyncMagicCore.Plan plan = PurpleSyncMagicCore.plan(input);
        check(plan.action == PurpleSyncMagicCore.Action.Wait && plan.retryAt == 0 && !plan.telegram,
            "unbound, nothing visible, lists loading: a wait of Purple's own");
        input.listsComplete = true;
        check(PurpleSyncMagicCore.plan(input).action == PurpleSyncMagicCore.Action.Create, "complete lists without a magic chat: create");

        // A visible chat is verified, the oldest first, whether the lists are complete or not.
        input.listsComplete = false;
        input.visible = Arrays.asList(chat(2000, null, null), chat(1000, null, null));
        plan = PurpleSyncMagicCore.plan(input);
        check(plan.action == PurpleSyncMagicCore.Action.Verify && channel(1000).equals(plan.chat) && plan.rebind, "the oldest visible chat is verified");
        input.visible = new ArrayList<>(Collections.singletonList(chat(3000, 2L, 0L)));
        plan = PurpleSyncMagicCore.plan(input);
        check(plan.action == PurpleSyncMagicCore.Action.Wait && plan.retryAt == 0 && !plan.telegram,
            "only ineligible magic chats, lists loading: a wait for the lists, not a problem");
        input.listsComplete = true;
        plan = PurpleSyncMagicCore.plan(input);
        check(plan.action == PurpleSyncMagicCore.Action.Problem && plan.problem == PurpleSyncMagicCore.Problem.MagicIneligible && plan.show,
            "only ineligible magic chats, lists complete: a problem, shown, never a new chat");

        // Verify binds; the bound chat is then used until it is due again.
        PurpleSyncMagicCore.Verified verified = PurpleSyncMagicCore.verify("", chat(1000, 0L, 0L), NOW);
        check(verified != null && verified.problem == PurpleSyncMagicCore.Problem.None, "verify binds an eligible chat");
        PurpleSyncMagicCore.Binding bound = parsed(verified.binding);
        check(bound != null && bound.mode == PurpleSyncMagicCore.Mode.Magic && channel(1000).equals(bound.chat) && bound.verifiedAt == NOW, "the binding records the chat");
        PurpleSyncMagicCore.LookupInput again = new PurpleSyncMagicCore.LookupInput();
        again.binding = verified.binding;
        again.boundState = PurpleSyncMagicCore.BoundState.Present;
        again.bound = chat(1000, null, null);
        again.now = NOW + 60;
        plan = PurpleSyncMagicCore.plan(again);
        check(plan.action == PurpleSyncMagicCore.Action.Use && channel(1000).equals(plan.chat), "a recently verified chat is used");
        again.boundState = PurpleSyncMagicCore.BoundState.Gone;
        again.bound = null;
        plan = PurpleSyncMagicCore.plan(again);
        check(plan.action == PurpleSyncMagicCore.Action.Problem && plan.problem == PurpleSyncMagicCore.Problem.ChatGone, "a gone chat is a problem");
        // A bound chat missing from memory waits for the lists; complete lists without it mean it is gone.
        again.boundState = PurpleSyncMagicCore.BoundState.Unknown;
        plan = PurpleSyncMagicCore.plan(again);
        check(plan.action == PurpleSyncMagicCore.Action.Wait && plan.retryAt == 0 && !plan.telegram, "a missing bound chat waits for the lists");
        again.listsComplete = true;
        plan = PurpleSyncMagicCore.plan(again);
        check(plan.action == PurpleSyncMagicCore.Action.Problem && plan.problem == PurpleSyncMagicCore.Problem.ChatGone && plan.show
            && channel(1000).equals(plan.chat), "complete lists without it: the chat is gone");
        again.listsComplete = false;
        again.boundState = PurpleSyncMagicCore.BoundState.Present;
        check(PurpleSyncMagicCore.plan(again).action == PurpleSyncMagicCore.Action.Invalid, "Present without its facts is refused");
        PurpleSyncMagicCore.Verified refused = PurpleSyncMagicCore.verify(verified.binding, chat(1000, 1L, 0L), NOW);
        check(refused != null && refused.problem == PurpleSyncMagicCore.Problem.ExtraMember, "verify raises the chat's problem");
        PurpleSyncMagicCore.Binding raised = parsed(refused.binding);
        check(raised.problem == PurpleSyncMagicCore.Problem.ExtraMember && !raised.problemShown, "the raised problem waits to be shown");

        // Show once, clear, reset.
        String shown = PurpleSyncMagicCore.shown(refused.binding);
        check(parsed(shown).problemShown, "shown marks the problem");
        check(parsed(PurpleSyncMagicCore.raise(shown, PurpleSyncMagicCore.Problem.ExtraMember)).problemShown, "the same problem is not shown again");
        check(!parsed(PurpleSyncMagicCore.raise(shown, PurpleSyncMagicCore.Problem.ChatLeft)).problemShown, "a different problem is shown");
        PurpleSyncMagicCore.Binding cleared = parsed(PurpleSyncMagicCore.clear(shown));
        check(cleared.problem == PurpleSyncMagicCore.Problem.None && !cleared.problemShown && channel(1000).equals(cleared.chat), "clear keeps the chat");

        // Waits persist in the binding and only grow.
        String waited = PurpleSyncMagicCore.recordWait("", PurpleSyncMagicCore.CREATE, NOW + 600);
        check(PurpleSyncMagicCore.waitUntil(waited, PurpleSyncMagicCore.CREATE, NOW) == NOW + 600, "a wait is remembered");
        check(PurpleSyncMagicCore.waitUntil(waited, PurpleSyncMagicCore.CREATE, NOW + 600) == 0, "a wait ends at its deadline");
        check(PurpleSyncMagicCore.waitUntil(waited, PurpleSyncMagicCore.FULL_INFO, NOW) == 0, "a wait holds only its method");
        check(PurpleSyncMagicCore.waitUntil(PurpleSyncMagicCore.recordWait(waited, PurpleSyncMagicCore.CREATE, NOW + 60), PurpleSyncMagicCore.CREATE, NOW) == NOW + 600,
            "a shorter wait does not shorten it");
        input = new PurpleSyncMagicCore.LookupInput();
        input.binding = waited;
        input.listsComplete = true;
        input.now = NOW;
        plan = PurpleSyncMagicCore.plan(input);
        check(plan.action == PurpleSyncMagicCore.Action.Wait && plan.retryAt == NOW + 600 && plan.telegram, "the lookup honours the kept flood, as Telegram's wait");
        String reset = PurpleSyncMagicCore.reset(PurpleSyncMagicCore.recordWait(PurpleSyncMagicCore.raise(verified.binding, PurpleSyncMagicCore.Problem.ChatLeft),
            PurpleSyncMagicCore.CREATE, NOW + 600));
        PurpleSyncMagicCore.Binding forgotten = parsed(reset);
        check(reset != null && PurpleSyncMagicCore.waitUntil(reset, PurpleSyncMagicCore.CREATE, NOW) == NOW + 600, "reset keeps the waits");
        check(forgotten.chat == null && forgotten.mode == PurpleSyncMagicCore.Mode.None && forgotten.problem == PurpleSyncMagicCore.Problem.None, "reset forgets the chat");

        // A creation in progress blocks a second one.
        String creating = PurpleSyncMagicCore.createStep("", PurpleSyncMagicCore.CreateStep.Uncertain, NOW, null);
        input = new PurpleSyncMagicCore.LookupInput();
        input.binding = creating;
        input.listsComplete = true;
        input.now = NOW + 60;
        plan = PurpleSyncMagicCore.plan(input);
        check(plan.action == PurpleSyncMagicCore.Action.Wait && !plan.telegram, "an uncertain creation is not repeated, and that wait is Purple's own");

        // Manual, migration and login edits.
        String manual = PurpleSyncMagicCore.manual("", PurpleSyncChatCore.Peer.self());
        PurpleSyncMagicCore.Binding self = parsed(manual);
        check(self.mode == PurpleSyncMagicCore.Mode.Manual && PurpleSyncChatCore.Peer.self().equals(self.chat), "a manual Saved Messages override");
        input = new PurpleSyncMagicCore.LookupInput();
        input.binding = manual;
        input.now = NOW;
        input.boundState = PurpleSyncMagicCore.BoundState.Present;
        input.bound = chat(0, 0L, 0L);
        input.bound.facts.kind = PurpleSyncChatCore.Kind.Self;
        plan = PurpleSyncMagicCore.plan(input);
        check(plan.action == PurpleSyncMagicCore.Action.Use && PurpleSyncChatCore.Peer.self().equals(plan.chat), "Saved Messages is used as overridden");
        for (PurpleSyncMagicCore.Migration step : PurpleSyncMagicCore.Migration.values()) {
            check(parsed(PurpleSyncMagicCore.migration("", step)).migration == step, "migration step " + step);
        }
        check(parsed(PurpleSyncMagicCore.login("", "abc")).login.equals("abc"), "the login authority is kept");

        // Malformed input is refused, never guessed.
        check(PurpleSyncMagicCore.parse("{") == null, "a broken binding is refused");
        check(PurpleSyncMagicCore.parse("{\"version\":9}") == null, "a binding of an unknown version is refused");
        check(PurpleSyncMagicCore.waitUntil("{", PurpleSyncMagicCore.CREATE, NOW) == -1, "waitUntil reports an unreadable binding");
        check(PurpleSyncMagicCore.recordWait("{", PurpleSyncMagicCore.CREATE, NOW) == null, "edits refuse an unreadable binding");
        check(PurpleSyncMagicCore.manual("", null) == null, "a manual override needs a chat");
        check(PurpleSyncMagicCore.parse(null) == null && PurpleSyncMagicCore.clear(null) == null && PurpleSyncMagicCore.verify(null, chat(1000, 0L, 0L), NOW) == null
            && PurpleSyncMagicCore.waitUntil(null, PurpleSyncMagicCore.CREATE, NOW) == -1, "a failed edit (null) is never read as no binding");
        PurpleSyncMagicCore.LookupInput lost = new PurpleSyncMagicCore.LookupInput();
        lost.binding = null;
        check(PurpleSyncMagicCore.plan(lost).action == PurpleSyncMagicCore.Action.Invalid, "a lookup refuses a null binding");
        check(new org.json.JSONObject(PurpleSyncMagicCore.requestNative("nope", "{}")).optBoolean("ok", true) == false, "an unknown operation fails");
        check(new org.json.JSONObject(PurpleSyncMagicCore.requestNative("plan", "[]")).optBoolean("ok", true) == false, "a non-object input fails");
        check(!new org.json.JSONObject(PurpleSyncMagicCore.requestNative("plan",
                "{\"binding\":\"\",\"boundState\":\"Unknown\",\"visible\":[],\"fresh\":false,\"now\":\"1\"}")).optBoolean("ok", true)
            && new org.json.JSONObject(PurpleSyncMagicCore.requestNative("plan",
                "{\"binding\":\"\",\"boundState\":\"Unknown\",\"visible\":[],\"fresh\":false,\"listsComplete\":false,\"now\":\"1\"}")).optBoolean("ok"),
            "a plan without listsComplete is refused, never read as complete");
        PurpleSyncMagicCore.Candidate bad = chat(1000, 0L, 0L);
        bad.facts.kind = PurpleSyncChatCore.Kind.ChannelDirectMessages;
        check(PurpleSyncMagicCore.check(bad).verdict == PurpleSyncMagicCore.Verdict.Ineligible, "every Java kind reaches core");
        check(new org.json.JSONObject(PurpleSyncMagicCore.requestNative("check", "{\"chat\":{\"channelId\":\"-1\"}}")).optBoolean("ok", true) == false,
            "a negative channel id is refused");

        // The TL mapping: local facts leave the counts unknown, full info makes them exact.
        org.telegram.tgnet.TLRPC.TL_channel channel = new org.telegram.tgnet.TLRPC.TL_channel();
        channel.id = 1000; channel.title = PurpleSyncMagicCore.TITLE; channel.megagroup = true; channel.creator = true;
        PurpleSyncMagicCore.Candidate local = PurpleSyncChatFacts.magic(channel, null);
        check(local != null && local.channelId == 1000 && local.megagroup && local.facts.creator && local.facts.otherMembers == null, "a local channel maps without counts");
        check(PurpleSyncMagicCore.check(local).verdict == PurpleSyncMagicCore.Verdict.Unverified, "and is unverified");
        org.telegram.tgnet.TLRPC.TL_channelFull full = new org.telegram.tgnet.TLRPC.TL_channelFull();
        full.id = 1000; full.flags = 1 | (1 << 1); full.participants_count = 1; full.admins_count = 1;
        PurpleSyncMagicCore.Candidate exact = PurpleSyncChatFacts.magic(channel, full);
        check(exact != null && exact.facts.otherMembers == 0L && exact.facts.otherAdmins == 0L, "full info gives exact counts");
        check(PurpleSyncMagicCore.check(exact).verdict == PurpleSyncMagicCore.Verdict.Eligible, "a sole-member channel is eligible");
        full.participants_count = 2;
        check(PurpleSyncMagicCore.check(PurpleSyncChatFacts.magic(channel, full)).problem == PurpleSyncMagicCore.Problem.ExtraMember, "a second member is seen");
        full.id = 1001;
        check(PurpleSyncChatFacts.magic(channel, full) == null, "full info of another chat is refused");
        channel.left = true;
        check(PurpleSyncMagicCore.check(PurpleSyncChatFacts.magic(channel, null)).problem == PurpleSyncMagicCore.Problem.ChatLeft, "a left channel is not a member");
        org.telegram.tgnet.TLRPC.TL_channelForbidden forbidden = new org.telegram.tgnet.TLRPC.TL_channelForbidden();
        forbidden.id = 1000;
        check(PurpleSyncChatFacts.magic(forbidden, null) != null && !PurpleSyncChatFacts.magic(forbidden, null).member, "a forbidden channel is not a member");
        check(PurpleSyncChatFacts.magic(null, null) == null, "no chat, no candidate");

        // Classify: core's rules, through the bridge. Only floods are waits; transport trouble is transient; the rest refused.
        String[][] errors = {
            { "420", "FLOOD_WAIT_120", "false", "Wait", "120" }, { "420", "FLOOD_PREMIUM_WAIT_5", "false", "Wait", "5" },
            { "420", "SLOWMODE_WAIT_9", "false", "Wait", "9" }, { "420", "FLOOD_WAIT_", "false", "Refused", "0" },
            { "-504", "", "true", "Transient", "0" }, { "-1", "", "false", "Transient", "0" }, { "500", "INTERNAL", "false", "Transient", "0" },
            { "401", "AUTH_KEY_UNREGISTERED", "false", "Transient", "0" }, { "400", "INPUT_METHOD_INVALID", "false", "Refused", "0" },
            { "400", "PEER_FLOOD", "false", "Refused", "0" }, { "403", "CHAT_WRITE_FORBIDDEN", "false", "Refused", "0" },
            { "406", "CHANNEL_PRIVATE", "false", "Refused", "0" },
        };
        for (String[] row : errors) {
            PurpleSyncMagicCore.Classified error = PurpleSyncMagicCore.classify(Integer.parseInt(row[0]), row[1], Boolean.parseBoolean(row[2]));
            check(error.kind.name().equals(row[3]) && error.seconds == Long.parseLong(row[4]), "classify " + row[0] + " " + row[1] + ": " + error.kind + " " + error.seconds);
        }
        check(PurpleSyncMagicCore.classify(-504, null, true).kind == PurpleSyncMagicCore.ErrorKind.Transient, "a transport failure without text is transient");
        check(!new org.json.JSONObject(PurpleSyncMagicCore.requestNative("classify", "{\"code\":\"400\"}")).optBoolean("ok", true),
            "classify refuses malformed input");
        check(PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.CREATE, "CHANNELS_TOO_MUCH") == PurpleSyncMagicCore.Problem.CreateLimit
            && PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.CREATE, "USER_RESTRICTED") == PurpleSyncMagicCore.Problem.CreateLimit,
            "creation limits are create_limit");
        check(PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.FULL_INFO, "CHANNEL_PRIVATE") == PurpleSyncMagicCore.Problem.ChatGone
            && PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.FULL_INFO, "CHANNEL_INVALID") == PurpleSyncMagicCore.Problem.ChatGone,
            "a private or invalid channel's full info is chat_gone");
        check(PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.CREATE, "INPUT_METHOD_INVALID") == PurpleSyncMagicCore.Problem.RequestRefused
            && PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.FULL_INFO, "CHANNELS_TOO_MUCH") == PurpleSyncMagicCore.Problem.RequestRefused
            && PurpleSyncMagicCore.refusalProblem(PurpleSyncMagicCore.CREATE, null) == PurpleSyncMagicCore.Problem.RequestRefused,
            "anything else is request_refused");
        check(PurpleSyncMagicCore.Problem.RequestRefused.code().equals("request_refused"), "the new problem's code");

        // A refused verify: raised for the bound chat, only a rejection for another candidate.
        PurpleSyncMagicCore.Verified own = PurpleSyncMagicCore.verifyProblem(verified.binding, 1000, PurpleSyncMagicCore.Problem.RequestRefused, NOW);
        check(own != null && !own.rejected && parsed(own.binding).problem == PurpleSyncMagicCore.Problem.RequestRefused, "the bound chat's refusal is raised");
        PurpleSyncMagicCore.Verified other = PurpleSyncMagicCore.verifyProblem(verified.binding, 900, PurpleSyncMagicCore.Problem.RequestRefused, NOW);
        check(other != null && other.rejected && parsed(other.binding).problem == PurpleSyncMagicCore.Problem.None, "another candidate is only rejected");
        input = new PurpleSyncMagicCore.LookupInput();
        input.binding = other.binding; input.boundState = PurpleSyncMagicCore.BoundState.Present; input.bound = chat(1000, null, null);
        input.visible = Arrays.asList(chat(900, null, null), chat(1000, null, null)); input.now = NOW + 60;
        check(PurpleSyncMagicCore.plan(input).action == PurpleSyncMagicCore.Action.Use, "and is skipped by the next plan");
        check(PurpleSyncMagicCore.verifyProblem(verified.binding, 900, PurpleSyncMagicCore.Problem.None, NOW) == null
            && PurpleSyncMagicCore.verifyProblem("{", 900, PurpleSyncMagicCore.Problem.RequestRefused, NOW) == null, "verifyProblem refuses no problem or a broken binding");

        // No sync path may send a Telegram search.
        check(PurpleSyncChatTelegram.searches(new org.telegram.tgnet.TLRPC.TL_messages_search())
            && PurpleSyncChatTelegram.searches(new org.telegram.tgnet.TLRPC.TL_messages_searchGlobal()), "both searches are refused");
        check(!PurpleSyncChatTelegram.searches(new org.telegram.tgnet.TLRPC.TL_channels_getFullChannel()), "a full-info read is not a search");

        System.out.println("SYNC_MAGIC_CHECKS=" + checks);
    }
}
