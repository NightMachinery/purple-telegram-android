/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

/**
 * First use of the sync chat (Magic Purple Sync R4b): a device with no sync state in the chat's space. purple-core
 * decides ({@code SyncFirstUseInputOf} and {@code PlanSyncFirstUse}, bridged as {@link PurpleSyncCore#firstUse}); this
 * class only acts on the answer, through the runner's existing Check, apply and publish:
 * <ul>
 * <li>Wait: nothing is sent (an empty chat and the shipped defaults here);</li>
 * <li>Publish: the first record, or this device's settings as the successor of the chat's head after the user chose
 * "Use this device's settings", with no confirmation;</li>
 * <li>Join: the chat's head is applied with History, as the "use synced settings" choice does;</li>
 * <li>JoinSilently: the space is joined and the local file stays; when its bytes differ from the head although they
 * mean the same, it is published as the successor, so the chat's head is this file;</li>
 * <li>Ask: the one-time choice, answered with {@link PurpleSyncCore.FirstUseChoice}.</li>
 * </ul>
 * A device whose state is in another space ({@code space_mismatch}) has that state set aside first
 * ({@link PurpleSyncRunner#setAside}: the state, its stage, retained bases and automatic record go into an
 * {@code orphaned-<date>} folder; settings, History and the chat binding stay), and then acts as a device with no
 * state. It is set aside only when first use acts, never for Wait or Ask. Once a state exists in the chat's space the
 * review is bound and {@link #applies} is false, so the question can never come back.
 */
public final class PurpleSyncFirstUse {
    public enum Result {
        /** The device has a state in the chat's space (or the check is not one first use can read). */
        NotFirstUse,
        Wait,
        Ask,
        /** Joined the chat's space; nothing needed sending, or a send did not finish (see {@link Outcome#sent}). */
        Joined,
        Published,
        /** Nothing changed: see {@link Outcome#reason}. */
        Refused
    }

    public static final class Outcome {
        public final Result result;
        public final PurpleSyncCore.FirstUseAction action;
        /** A diag code: why first use refused, or the action it took. */
        public final String reason;
        public final PurpleSyncApply.ApplyResult applied;
        public final PurpleSyncPublisher.Result sent;
        public final boolean archived;
        /**
         * First use joined to publish this device's file (the first record or a successor) and the post did not go
         * out: the new state is the chat's, the file is not yet. Automatic sync then takes the file as a Save.
         */
        public final boolean unsent;

        Outcome(Result result, PurpleSyncCore.FirstUseAction action, String reason, PurpleSyncApply.ApplyResult applied,
                PurpleSyncPublisher.Result sent, boolean archived) {
            this.result = result;
            this.action = action;
            this.reason = reason;
            this.applied = applied;
            this.sent = sent;
            this.archived = archived;
            this.unsent = result == Result.Joined && "successor_unsent".equals(reason);
        }
    }

    /** The review code of a state in another sync space. */
    public static final String SPACE_MISMATCH = "space_mismatch";

    /**
     * Whether {@code check} shows a device with no state in the chat's space: an unbound review core could read, or
     * a bound one refused only because the chat's records are in another space.
     */
    public static boolean applies(PurpleSyncRunner.Check check) {
        if (check == null || check.review == null || check.inventory == null || check.local == null) return false;
        if (!check.review.bound) return check.review.status == PurpleSyncCore.ReviewStatus.Ready;
        return mismatch(check);
    }

    static boolean mismatch(PurpleSyncRunner.Check check) {
        return check.review.bound && check.review.status == PurpleSyncCore.ReviewStatus.NeedsReview
                && SPACE_MISMATCH.equals(check.review.reason);
    }

    /** Core's first-use plan for {@code check}, or null when {@link #applies} is false. */
    public static PurpleSyncCore.FirstUse plan(PurpleSyncRunner.Check check, PurpleSyncCore.FirstUseChoice choice) {
        return applies(check) ? PurpleSyncCore.firstUse(check.inventory, check.local, choice) : null;
    }

    /** Runs first use for {@code check}, the runner's current check; {@code done} gets the outcome on the UI poster. */
    public static void run(PurpleSyncRunner runner, PurpleSyncRunner.Check check, PurpleSyncCore.FirstUseChoice choice,
            PurpleSyncRunner.Listener<Outcome> done) {
        new PurpleSyncFirstUse(runner, choice, done).start(check);
    }

    /** Automatic first use reads the chat at most once per this many seconds, as automatic checks do (core). */
    static final long CHECK_SECONDS = 15 * 60;

    /**
     * Whether automatic sync of a device with no state may read the chat for first use on {@code event} at
     * {@code now}, {@code last} being when it last did (0 for never): turning automatic sync on and a real Save always
     * may; the lifecycle triggers (session ready, foreground, unlock, retry) once per {@link #CHECK_SECONDS}, so a
     * device that waits or asks does not scan the chat on every activation.
     */
    static boolean due(PurpleSyncAutoCore.Event event, long now, long last) {
        return event == PurpleSyncAutoCore.Event.Enable || event == PurpleSyncAutoCore.Event.LocalSaved
                || last <= 0 || now < last || now - last >= CHECK_SECONDS;
    }

    /**
     * The user's answer, kept per account until first use acted on it, so a crash or a failed step after the old
     * state was set aside does not ask again: the next first use of the same chat space takes it. It is kept with
     * the space it answered for, and dropped once the device joined or published, or has a state in the chat's space.
     */
    interface Answers {
        String get();
        void put(String value);
        void clear();
    }

    static Answers answers(int slot) {
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        String key = identity != null ? "purple_sync_first_answer_" + identity.key : null;
        return new Answers() {
            public String get() { return key != null ? org.telegram.messenger.MessagesController.getMainSettings(slot).getString(key, null) : null; }
            public void put(String value) { if (key != null) org.telegram.messenger.MessagesController.getMainSettings(slot).edit().putString(key, value).commit(); }
            public void clear() { if (key != null) org.telegram.messenger.MessagesController.getMainSettings(slot).edit().remove(key).commit(); }
        };
    }

    /** What an answer answered: the chat's space and its current heads as core names them. */
    static String seen(PurpleSyncCore.FirstUse plan) {
        java.util.ArrayList<String> parents = new java.util.ArrayList<>(plan.expectedParents);
        java.util.Collections.sort(parents);
        return plan.space + "|" + plan.headKey + "|" + String.join(",", parents);
    }

    static String answer(PurpleSyncCore.FirstUseChoice choice, PurpleSyncCore.FirstUse plan) { return choice.name() + "|" + seen(plan); }

    /**
     * The user tapped an answer to what {@code check} asked. It is kept now, before the Check the tap starts, so a
     * failed Check, leaving the screen or a process death does not ask again.
     */
    public static void remember(int slot, PurpleSyncRunner.Check check, PurpleSyncCore.FirstUseChoice choice) {
        PurpleSyncCore.FirstUse seen = plan(check, PurpleSyncCore.FirstUseChoice.None);
        if (seen != null && seen.isValid() && seen.available && !seen.space.isEmpty()) answers(slot).put(answer(choice, seen));
    }

    static String shownKey(String account) { return "purple_sync_first_result_" + account; }

    /** The result kept across a restart for the status: Ask or Wait, anything else is nothing kept. */
    static Result shown(String kept) {
        return Result.Ask.name().equals(kept) ? Result.Ask : Result.Wait.name().equals(kept) ? Result.Wait : null;
    }

    /**
     * The kept answer for what {@code plan} sees, or None. An answer kept for other heads (they changed since, for
     * example a split resolved) is dropped: it never saw these settings, so first use plans again and may ask again.
     */
    static PurpleSyncCore.FirstUseChoice kept(Answers answers, PurpleSyncCore.FirstUse plan) {
        String value = answers.get();
        if (value == null || plan.space.isEmpty()) return PurpleSyncCore.FirstUseChoice.None;
        for (PurpleSyncCore.FirstUseChoice choice : PurpleSyncCore.FirstUseChoice.values())
            if (choice != PurpleSyncCore.FirstUseChoice.None && value.equals(answer(choice, plan))) return choice;
        answers.clear();
        return PurpleSyncCore.FirstUseChoice.None;
    }

    private final PurpleSyncRunner runner;
    private PurpleSyncCore.FirstUseChoice choice;
    private final Answers answers;
    private final PurpleSyncRunner.Listener<Outcome> done;
    private PurpleSyncCore.FirstUseAction action;
    private boolean archived, rechecked, posting;

    private PurpleSyncFirstUse(PurpleSyncRunner runner, PurpleSyncCore.FirstUseChoice choice,
            PurpleSyncRunner.Listener<Outcome> done) {
        this.runner = runner;
        this.choice = choice != null ? choice : PurpleSyncCore.FirstUseChoice.None;
        this.done = done;
        answers = answers(runner.account());
    }

    private void start(PurpleSyncRunner.Check check) {
        if (!applies(check)) { finish(Result.NotFirstUse, "not_first_use", null, null); return; }
        PurpleSyncCore.FirstUse plan = plan(check, choice);
        if (plan == null || !plan.isValid() || !plan.available || plan.action == null) {
            finish(Result.Refused, "first_use_unavailable", null, null);
            return;
        }
        if (choice == PurpleSyncCore.FirstUseChoice.None && plan.action == PurpleSyncCore.FirstUseAction.Ask) {
            PurpleSyncCore.FirstUseChoice kept = kept(answers, plan);
            if (kept != PurpleSyncCore.FirstUseChoice.None) {
                choice = kept;
                plan = plan(check, choice);
                if (plan == null || !plan.isValid() || !plan.available || plan.action == null) {
                    finish(Result.Refused, "first_use_unavailable", null, null);
                    return;
                }
            }
        } else if (choice != PurpleSyncCore.FirstUseChoice.None && plan.action != PurpleSyncCore.FirstUseAction.Ask && !plan.space.isEmpty()) {
            PurpleSyncCore.FirstUse seen = plan(check, PurpleSyncCore.FirstUseChoice.None);
            if (seen != null && seen.isValid() && seen.available) answers.put(answer(choice, seen));
        }
        action = plan.action;
        if (action == PurpleSyncCore.FirstUseAction.Wait) { finish(Result.Wait, "wait", null, null); return; }
        if (action == PurpleSyncCore.FirstUseAction.Ask) { finish(Result.Ask, "ask", null, null); return; }
        if (action == PurpleSyncCore.FirstUseAction.Publish && plan.groupEmpty && !rechecked) {
            // A first record starts a new space. Two fresh devices that both saw the empty chat would start two, so
            // the chat is read again right before: if a record arrived meanwhile, first use plans from that check
            // (the strict successor of it, or the one-time choice) instead.
            rechecked = true;
            recheck(this::start);
            return;
        }
        if (!mismatch(check)) { act(check, plan); return; }
        // The state of another space goes aside first; the device then acts as one with no state.
        PurpleSyncRunner.Start started = runner.setAside(check, ok -> {
            if (!Boolean.TRUE.equals(ok)) { finish(Result.Refused, "archive_refused", null, null); return; }
            archived = true;
            recheck(next -> {
                PurpleSyncCore.FirstUse again = plan(next, choice);
                if (next.review.bound || again == null || !again.isValid() || !again.available || again.action != action) {
                    finish(Result.Refused, "check_changed", null, null);
                    return;
                }
                act(next, again);
            });
        });
        if (started != PurpleSyncRunner.Start.Started) finish(Result.Refused, refusal(started), null, null);
    }

    private void recheck(PurpleSyncRunner.Listener<PurpleSyncRunner.Check> next) {
        PurpleSyncRunner.Start started = runner.check(null, outcome -> {
            if (outcome.status != PurpleSyncRunner.CheckStatus.Finished || outcome.check == null) {
                finish(Result.Refused, "check_failed", null, null);
                return;
            }
            next.onResult(outcome.check);
        });
        if (started != PurpleSyncRunner.Start.Started) finish(Result.Refused, refusal(started), null, null);
    }

    /**
     * Acts through the runner. Core names the chat's current head ({@link PurpleSyncCore.FirstUse#headKey}); the client
     * never derives it. It is the chosen key of a join (Join and "Use the synced settings"; JoinSilently with the
     * head's exact bytes joins with the local choice, which is that head) and the one expected parent of a successor (JoinSilently with other bytes, and "Use
     * this device's settings"), which keeps the local file and publishes it on a fresh check. The first record of
     * an empty chat has no parent.
     */
    private void act(PurpleSyncRunner.Check check, PurpleSyncCore.FirstUse plan) {
        boolean successor = !plan.groupEmpty && (action == PurpleSyncCore.FirstUseAction.Publish
                || action == PurpleSyncCore.FirstUseAction.JoinSilently && !plan.sameBytes);
        boolean join = action == PurpleSyncCore.FirstUseAction.Join || action == PurpleSyncCore.FirstUseAction.JoinSilently && !successor;
        if (join && plan.headKey.isEmpty() || successor && plan.expectedParents.isEmpty()) { finish(Result.Refused, "heads_unclear", null, null); return; }
        // A file of the head's exact bytes is the head: core's review lists that head as the same, not as a choice,
        // and PlanSyncConfigApply refuses its key (InvalidChoice), so that join keeps the local choice.
        String key = join && !(action == PurpleSyncCore.FirstUseAction.JoinSilently && plan.sameBytes) ? plan.headKey : null;
        PurpleSyncRunner.Start started = runner.apply(check, key, outcome -> {
            PurpleSyncApply.ApplyResult result = outcome.result;
            if (result.status != PurpleSyncCore.ApplyStatus.Applied) {
                finish(Result.Refused, "apply_refused", result, null);
                return;
            }
            PurpleSyncRunner.PostTicket ticket = outcome.post;
            posting = successor || ticket != null;
            PurpleSyncRunner.Listener<PurpleSyncPublisher.Result> sent = published -> finish(
                    published != null && published.status == PurpleSyncCore.PublishStatus.Confirmed ? Result.Published : Result.Joined,
                    code(action), result, published);
            if (successor && ticket == null) {
                // This device's file as the successor of the chat's head, against a fresh check.
                if (result.fingerprint == null || result.fingerprint.isEmpty()) { finish(Result.Joined, code(action), result, null); return; }
                PurpleSyncCore.PostRequest request = PurpleSyncCore.PostRequest.newContent(result.fingerprint, plan.expectedParents);
                recheck(fresh -> {
                    PurpleSyncRunner.Start posting = runner.publish(fresh, request, sent);
                    if (posting != PurpleSyncRunner.Start.Started) finish(Result.Joined, refusal(posting), result, null);
                });
                return;
            }
            if (ticket == null) { finish(Result.Joined, code(action), result, null); return; }
            PurpleSyncRunner.Start posting = ticket.freshCheckFirst ? null : runner.publish(ticket, sent);
            if (posting == null) recheck(fresh -> {
                PurpleSyncRunner.Start again = runner.publish(fresh, ticket, sent);
                if (again != PurpleSyncRunner.Start.Started) finish(Result.Joined, refusal(again), result, null);
            });
            else if (posting != PurpleSyncRunner.Start.Started) finish(Result.Joined, refusal(posting), result, null);
        });
        if (started != PurpleSyncRunner.Start.Started) finish(Result.Refused, refusal(started), null, null);
    }

    static String code(PurpleSyncCore.FirstUseAction action) {
        if (action == null) return "none";
        switch (action) {
            case Wait: return "wait";
            case Publish: return "publish";
            case Join: return "join";
            case JoinSilently: return "join_silently";
            default: return "ask";
        }
    }

    private static String refusal(PurpleSyncRunner.Start start) {
        return start == PurpleSyncRunner.Start.Busy ? "busy" : start == PurpleSyncRunner.Start.Stale ? "check_changed" : "account_unavailable";
    }

    private void finish(Result result, String reason, PurpleSyncApply.ApplyResult applied, PurpleSyncPublisher.Result sent) {
        if (posting && result == Result.Joined) reason = "successor_unsent";
        if (result == Result.Joined || result == Result.Published || result == Result.NotFirstUse) answers.clear();
        PurpleDiagLog.event("sync", "first_use", PurpleDiagLogStore.slot(runner.account()),
                PurpleDiagLogStore.code("action", code(action)), PurpleDiagLogStore.code("outcome", result.name().toLowerCase(java.util.Locale.ROOT)),
                PurpleDiagLogStore.reason(reason), PurpleDiagLogStore.flag("archived", archived));
        done.onResult(new Outcome(result, action, reason, applied, sent, archived));
    }
}
