package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

public final class PurpleDirectShareCases {
    private static int checks;
    private static void check(boolean value, String reason) { ++checks; if (!value) throw new AssertionError(reason); }
    private static PurpleDirectSharePublisher.Target<String> target(String id, String version) {
        return new PurpleDirectSharePublisher.Target<>(id, version, id, 0, false, true, true);
    }
    private static PurpleDirectSharePublisher.Target<String> conversation(String id, boolean visible, boolean current, boolean observed) {
        return new PurpleDirectSharePublisher.Target<>(id, "account-A:1", id, 0, true, visible, current).observed(observed);
    }
    private static final class ShortcutManagerPort implements PurpleDirectSharePublisher.Port<String> {
        final LinkedHashMap<String, PurpleDirectSharePublisher.Target<String>> dynamic = new LinkedHashMap<>();
        final ArrayList<String> removedLongLived = new ArrayList<>();
        int adds, updates, removes;
        Runnable duringRead;
        public List<PurpleDirectSharePublisher.Target<String>> current() {
            if (duringRead != null) { Runnable action = duringRead; duringRead = null; action.run(); }
            return new ArrayList<>(dynamic.values());
        }
        public void remove(List<String> ids, List<String> longLived) {
            ++removes; for (String id : ids) dynamic.remove(id); removedLongLived.addAll(longLived);
        }
        public void add(List<PurpleDirectSharePublisher.Target<String>> targets) {
            ++adds; for (PurpleDirectSharePublisher.Target<String> target : targets) {
                check(!dynamic.containsKey(target.id) || !dynamic.get(target.id).dynamic, "add is only for an absent dynamic shortcut"); dynamic.put(target.id, target);
            }
        }
        public void update(List<PurpleDirectSharePublisher.Target<String>> targets) {
            ++updates; for (PurpleDirectSharePublisher.Target<String> target : targets) {
                check(dynamic.containsKey(target.id), "update retains an existing shortcut identity"); dynamic.put(target.id, target);
            }
        }
        int calls() { return adds + updates + removes; }
    }
    private static List<List<PurpleDirectSharePublisher.Target<String>>> accounts(String... ids) {
        ArrayList<List<PurpleDirectSharePublisher.Target<String>>> result = new ArrayList<>();
        for (String group : ids) {
            ArrayList<PurpleDirectSharePublisher.Target<String>> account = new ArrayList<>();
            if (!group.isEmpty()) for (String id : group.split(",")) account.add(target(id, id));
            result.add(account);
        }
        return result;
    }
    private static boolean publish(PurpleDirectSharePublisher<String> owner, ShortcutManagerPort os,
            List<List<PurpleDirectSharePublisher.Target<String>>> accounts, List<PurpleDirectSharePublisher.Target<String>> conversations, int limit) {
        return owner.publish(owner.invalidate(), accounts, target("compose", "selected-A"), conversations, limit, os, () -> true);
    }
    private static void ranks() {
        PurpleDirectSharePublisher<String> owner = new PurpleDirectSharePublisher<>(); ShortcutManagerPort os = new ShortcutManagerPort();
        List<List<PurpleDirectSharePublisher.Target<String>>> accounts = accounts("pdid_0-11_7,pdid_0-11_8", "pdid_0-22_7,pdid_0-22_9");
        check(publish(owner, os, accounts, Collections.emptyList(), 5), "two-account reload accepted");
        check(new ArrayList<>(os.dynamic.keySet()).equals(Arrays.asList("compose", "pdid_0-11_7", "pdid_0-22_7", "pdid_0-11_8", "pdid_0-22_9")), "selected account first in each hint rank round");
        check(os.dynamic.get("pdid_0-11_7").rank == 1 && os.dynamic.get("pdid_0-22_7").rank == 2, "identical peers in two accounts have different IDs and ranks");
        int calls = os.calls(); check(publish(owner, os, accounts, Collections.emptyList(), 5), "repeat reload accepted");
        check(os.calls() == calls, "repeat reload has no OS mutations");
        List<List<PurpleDirectSharePublisher.Target<String>>> reordered = accounts("pdid_0-11_8,pdid_0-11_7", "pdid_0-22_7,pdid_0-22_9");
        check(publish(owner, os, reordered, Collections.emptyList(), 5), "rank-only update accepted");
        check(os.dynamic.get("pdid_0-11_8").rank == 1 && os.dynamic.get("pdid_0-11_7").rank == 3, "hint changes update ranks even with unchanged ID set");
        check(os.adds == 1 && os.removes == 0 && os.updates == 1, "rank change does not remove and re-add existing targets");
        check(publish(owner, os, accounts("pdid_0-22_7,pdid_0-22_9", "pdid_0-11_8,pdid_0-11_7"), Collections.emptyList(), 3), "small OS budget accepted");
        check(os.dynamic.size() == 3 && os.dynamic.get("pdid_0-22_7").rank == 1 && os.dynamic.get("pdid_0-11_8").rank == 2, "small budget retains highest hint from both accounts");
    }
    private static void hidden() {
        PurpleDirectSharePublisher<String> owner = new PurpleDirectSharePublisher<>(); ShortcutManagerPort os = new ShortcutManagerPort();
        os.dynamic.put("pndid_0-11_99", conversation("pndid_0-11_99", true, true, true));
        check(publish(owner, os, accounts("pdid_0-22_7"), Arrays.asList(conversation("pndid_0-11_99", false, true, true)), 5), "hide accepted");
        check(!os.dynamic.containsKey("pndid_0-11_99") && os.removedLongLived.contains("pndid_0-11_99"), "hidden conversation removed from dynamic and long-lived surfaces");
        check(owner.retained().size() == 1 && os.dynamic.containsKey("pdid_0-22_7"), "restorable A payload retained without removing B");
        int calls = os.calls(); publish(owner, os, accounts("pdid_0-22_7"), Arrays.asList(conversation("pndid_0-11_99", false, true, false)), 5);
        check(os.calls() == calls, "repeated hidden reload is idempotent");
        publish(owner, os, accounts("pdid_0-22_7"), Arrays.asList(conversation("pndid_0-11_99", true, true, false)), 5);
        check(os.dynamic.containsKey("pndid_0-11_99") && owner.retained().isEmpty(), "unhide restores existing conversation without another notification");
        int restoredCalls = os.calls(); publish(owner, os, accounts("pdid_0-22_7"), Arrays.asList(conversation("pndid_0-11_99", true, true, true)), 5);
        check(os.calls() == restoredCalls, "reload after restoration is fully idempotent");
        publish(owner, os, accounts("pdid_0-22_7"), Arrays.asList(conversation("pndid_0-11_99", false, true, true)), 5);
        publish(owner, os, accounts("pdid_0-22_7"), Arrays.asList(conversation("pndid_0-11_99", true, false, false)), 5);
        check(!os.dynamic.containsKey("pndid_0-11_99") && owner.retained().isEmpty(), "logout or lifetime change prevents retained restoration");
        publish(owner, os, accounts("pdid_0-11_7,pdid_0-11_8", "pdid_0-22_7"), Collections.emptyList(), 5);
        publish(owner, os, accounts("pdid_0-11_8", "pdid_0-22_7"), Collections.emptyList(), 5);
        check(!os.dynamic.containsKey("pdid_0-11_7") && os.dynamic.containsKey("pdid_0-22_7"), "hiding a direct peer removes only that account-qualified target");
        publish(owner, os, accounts("pdid_0-11_7,pdid_0-11_8", "pdid_0-22_7"), Collections.emptyList(), 5);
        check(os.dynamic.containsKey("pdid_0-11_7"), "direct hint restored on unhide");
    }
    private static void stale() {
        PurpleDirectSharePublisher<String> owner = new PurpleDirectSharePublisher<>(); ShortcutManagerPort os = new ShortcutManagerPort();
        long stale = owner.invalidate(); owner.invalidate();
        check(!owner.publish(stale, accounts("pdid_0-11_7"), target("compose", "a"), Collections.emptyList(), 4, os, () -> true) && os.calls() == 0, "stale queued generation publishes nothing");
        long fresh = owner.invalidate();
        check(!owner.publish(fresh, accounts("pdid_0-11_7"), target("compose", "a"), Collections.emptyList(), 4, os, () -> false) && os.calls() == 0, "lost routing or policy authority publishes nothing");
        os.duringRead = owner::invalidate; fresh = owner.invalidate();
        check(!owner.publish(fresh, accounts("pdid_0-11_7"), target("compose", "a"), Collections.emptyList(), 4, os, () -> true) && os.calls() == 0, "generation lost while reading OS state publishes nothing");
    }
    private static void ownership() {
        PurpleDirectSharePublisher<String> owner = new PurpleDirectSharePublisher<>(); ShortcutManagerPort os = new ShortcutManagerPort();
        os.dynamic.put("foreign-bot", target("foreign-bot", "kept")); os.dynamic.put("did3_7", target("did3_7", "legacy"));
        publish(owner, os, accounts("pdid_0-11_7", "pdid_0-22_7"), Collections.emptyList(), 4);
        check(os.dynamic.containsKey("foreign-bot") && !os.dynamic.containsKey("did3_7") && os.dynamic.size() == 4, "legacy direct IDs retired incrementally and foreign targets consume budget");
        check(os.removedLongLived.contains("did3_7"), "legacy direct target cannot remain cached");
        publish(owner, os, accounts("pdid_0-11_7", "pdid_0-22_7"), Collections.emptyList(), 0);
        check(os.dynamic.size() == 1 && os.dynamic.containsKey("foreign-bot"), "zero OS capacity adds nothing and preserves unrelated targets");
        os.dynamic.put("pdid_0-11_123", target("pdid_0-11_123", "cached-hidden").dynamic(false));
        publish(owner, os, accounts(), Collections.emptyList(), 4);
        check(!os.dynamic.containsKey("pdid_0-11_123") && os.removedLongLived.contains("pdid_0-11_123"), "cached-only direct target is removed when absent from visible hints");
        os.dynamic.put("pdid_0-22_7", target("pdid_0-22_7", "cached").dynamic(false));
        publish(owner, os, accounts("pdid_0-22_7"), Collections.emptyList(), 3);
        check(os.dynamic.get("pdid_0-22_7").dynamic && os.dynamic.size() == 3, "visible cached hint is promoted within dynamic quota");
        ArrayList<PurpleDirectSharePublisher.Target<String>> hidden = new ArrayList<>();
        for (int i = 0; i < 100; ++i) hidden.add(conversation("pndid_0-11_" + i, false, true, true));
        publish(owner, os, accounts(), hidden, 4); check(owner.retained().size() == 64, "conversation retention has a hard bound");
        os.dynamic.put("ndid_777", conversation("ndid_777", true, true, true));
        publish(owner, os, accounts(), Arrays.asList(conversation("ndid_777", false, false, true), conversation("pndid_0-11_777", true, true, false)), 4);
        check(!os.dynamic.containsKey("ndid_777") && os.dynamic.containsKey("pndid_0-11_777"), "known legacy conversation migrates incrementally to its qualified owner");
        os.dynamic.put("ndid_999", conversation("ndid_999", false, false, true));
        publish(owner, os, accounts(), Arrays.asList(conversation("ndid_999", false, false, true)), 4);
        os.dynamic.put("pndid_0-11_1234", conversation("pndid_0-11_1234", false, true, true));
        publish(owner, os, accounts("pdid_0-11_7,pdid_0-11_8"), Arrays.asList(conversation("pndid_0-11_1234", false, true, true), conversation("pndid_0-11_1234", false, true, true)), 2);
        check(os.dynamic.size() == 2, "duplicate conversation facts cannot inflate the OS budget");
        check(!os.dynamic.containsKey("ndid_999") && owner.retained().stream().noneMatch(x -> x.id.equals("ndid_999")), "ambiguous legacy owner is removed without guessed restoration");
    }
    public static void main(String[] args) {
        ranks(); hidden(); stale(); ownership();
        System.out.println("DIRECT_SHARE_CHECKS=" + checks);
    }
}
