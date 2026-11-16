package org.telegram.messenger.purple;

import java.util.EnumMap;
import java.util.Objects;

/** Device-local rules. The caller serializes events and supplies elapsed realtime. */
public final class PurpleSleepTimerRules {
    public static final long MINUTE = 60_000L;
    public static final long MAX_DURATION = 12 * 60 * MINUTE;
    public enum Mode { OFF, TIMED, END }
    public enum Slot { AUDIO, VIEWER }
    public enum Kind { SONG, VOICE, ROUND, VIDEO }
    public enum Source { MESSAGE, LOCAL_VIEWER, EMBED }

    public static final class Item {
        public final int account, message;
        public final long dialog, document;
        public final Kind kind;
        public final Source source;
        public final long localId;

        public Item(int account, long dialog, int message, long document, Kind kind) {
            source = Source.MESSAGE;
            localId = 0;
            this.account = account;
            this.dialog = dialog;
            this.message = message;
            this.document = document;
            this.kind = Objects.requireNonNull(kind);
        }

        public Item(Source source, long localId) {
            if (source == Source.MESSAGE || localId <= 0) throw new IllegalArgumentException("Local media identity required");
            this.source = Objects.requireNonNull(source);
            this.localId = localId;
            account = message = 0;
            dialog = document = 0;
            kind = Kind.VIDEO;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Item)) return false;
            final Item item = (Item) other;
            return source == item.source && kind == item.kind && (source != Source.MESSAGE
                    ? localId == item.localId
                    : account == item.account && dialog == item.dialog && message == item.message && document == item.document);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, localId, account, dialog, message, document, kind);
        }
    }

    public static final class Ticket {
        public final Slot slot;
        public final Item item;
        public final long incarnation, intent;
        private Ticket(Slot slot, State state) {
            this.slot = slot;
            item = state.item;
            incarnation = state.incarnation;
            intent = state.intent;
        }
    }

    public static final class EndEvent {
        public final Ticket ticket;
        public final long generation;
        private EndEvent(Ticket ticket, long generation) {
            this.ticket = ticket;
            this.generation = generation;
        }
    }

    /**
     * One-shot receipt for an explicit Play whose stock work finishes later (a lazy load,
     * a delayed navigation). It needs no selected item. Pause, expiry, a new selection,
     * player replacement or a newer request revokes it; timer start, extension or
     * cancellation does not. Requesting changes no playback state.
     */
    public static final class PlayRequest {
        private final PurpleSleepTimerRules owner;
        private final Slot slot;
        private final long incarnation, intent, request;
        private boolean consumed;
        private PlayRequest(PurpleSleepTimerRules owner, Slot slot, State state) {
            this.owner = owner;
            this.slot = slot;
            incarnation = state.incarnation;
            intent = state.intent;
            request = state.requests;
        }
    }

    public EndEvent captureEnd(Slot slot) { return new EndEvent(ticket(slot), generation); }

    public boolean matchesEnd(EndEvent event, boolean actualNativeEnd) {
        return event != null && matchesEnd(event.ticket.slot, event.ticket, actualNativeEnd, event.generation);
    }

    private static final class State {
        Item item;
        boolean eligibleEnd, blocked;
        long incarnation, intent, requests;
    }

    private final EnumMap<Slot, State> players = new EnumMap<>(Slot.class);
    private Mode mode = Mode.OFF;
    private long generation, deadline;
    private Slot boundSlot;
    private Item boundItem, pendingAdvance;

    public PurpleSleepTimerRules() {
        for (Slot slot : Slot.values()) players.put(slot, new State());
    }

    public Mode mode() { return mode; }
    public Item boundItem() { return boundItem; }
    public long generation() { return generation; }
    public long remaining(long now) { return mode == Mode.TIMED ? Math.max(0, deadline - now) : 0; }
    public Item current(Slot slot) { return players.get(slot).item; }
    public boolean eligible(Slot slot) {
        final State state = players.get(slot);
        return state.item != null && state.eligibleEnd;
    }
    public boolean loaded() {
        for (State state : players.values()) if (state.item != null) return true;
        return false;
    }

    public boolean timed(long now, long duration) {
        if (!loaded() || duration < MINUTE || duration > MAX_DURATION) return false;
        mode = Mode.TIMED;
        deadline = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
        boundSlot = null;
        boundItem = null;
        generation++;
        return true;
    }

    public boolean end(Slot slot) {
        if (!eligible(slot)) return false;
        mode = Mode.END;
        boundSlot = slot;
        boundItem = current(slot);
        deadline = 0;
        generation++;
        return true;
    }

    public boolean add(long now, long duration) {
        if (mode != Mode.TIMED || due(now) || duration <= 0) return false;
        final long left = remaining(now);
        final long extra = Math.min(duration, MAX_DURATION - Math.min(left, MAX_DURATION));
        deadline += extra;
        generation++;
        return true;
    }

    public void cancel() {
        mode = Mode.OFF;
        deadline = 0;
        boundSlot = null;
        boundItem = null;
        generation++;
    }

    public boolean due(long now) { return mode == Mode.TIMED && now >= deadline; }

    public boolean matchesEnd(Slot slot, Ticket event, boolean realEnd, long eventGeneration) {
        return realEnd && eventGeneration == generation && mode == Mode.END && slot == boundSlot && valid(event)
                && Objects.equals(boundItem, event.item);
    }

    public void expire(Item endedSong) {
        for (State state : players.values()) {
            state.blocked = true;
            state.intent++;
        }
        pendingAdvance = endedSong != null && endedSong.kind == Kind.SONG ? endedSong : null;
        cancel();
    }

    public void loaded(Slot slot, Item item, boolean eligibleEnd) {
        final State state = players.get(slot);
        final boolean changed = !Objects.equals(state.item, item);
        if (changed) {
            state.item = item;
            state.incarnation++;
            state.intent++;
            if (slot == Slot.AUDIO) pendingAdvance = null;
        }
        state.eligibleEnd = item != null && eligibleEnd;
        if (mode == Mode.END && boundSlot == slot) {
            final boolean samePlayerKind = item != null && boundItem != null
                    && (item.kind == boundItem.kind || (item.kind == Kind.VOICE || item.kind == Kind.ROUND)
                        && (boundItem.kind == Kind.VOICE || boundItem.kind == Kind.ROUND));
            if (state.eligibleEnd && samePlayerKind) {
                boundItem = item;
            } else {
                cancel();
            }
        }
        if (!loaded() && mode != Mode.OFF) cancel();
    }

    public boolean unload(Ticket ticket) {
        if (!sameIncarnation(ticket)) return false;
        loaded(ticket.slot, null, false);
        return true;
    }

    public boolean handoff(Ticket from, Slot to) {
        if (!valid(from) || from.slot == to) return false;
        final State source = players.get(from.slot);
        final State target = players.get(to);
        if (target.item != null && !target.item.equals(source.item)) return false;
        target.item = source.item;
        target.eligibleEnd = source.eligibleEnd;
        target.blocked = source.blocked;
        target.incarnation++;
        target.intent++;
        if (mode == Mode.END && boundSlot == from.slot) boundSlot = to;
        source.item = null;
        source.eligibleEnd = false;
        source.incarnation++;
        source.intent++;
        return true;
    }

    public void replaceIncarnation(Slot slot) {
        final State state = players.get(slot);
        state.incarnation++;
        state.intent++;
    }

    public Ticket ticket(Slot slot) { return new Ticket(slot, players.get(slot)); }

    public PlayRequest requestPlay(Slot slot) {
        final State state = players.get(slot);
        state.requests++;
        return new PlayRequest(this, slot, state);
    }

    public boolean acceptPlay(PlayRequest request) {
        if (request == null || request.owner != this || request.consumed) return false;
        request.consumed = true;
        final State state = players.get(request.slot);
        return state.incarnation == request.incarnation && state.intent == request.intent
                && state.requests == request.request;
    }

    public boolean valid(Ticket ticket) {
        return sameIncarnation(ticket) && players.get(ticket.slot).intent == ticket.intent;
    }

    private boolean sameIncarnation(Ticket ticket) {
        if (ticket == null || ticket.item == null) return false;
        final State state = players.get(ticket.slot);
        return state.incarnation == ticket.incarnation && Objects.equals(state.item, ticket.item);
    }

    public boolean autoStart(Ticket ticket) {
        return valid(ticket) && !players.get(ticket.slot).blocked;
    }

    public boolean transport(Slot slot, boolean play, boolean explicit, Ticket request) {
        if (explicit) {
            if (play) manualPlay(slot); else manualPause(slot);
            return true;
        }
        return !play || autoStart(request);
    }

    public void manualPlay(Slot slot) {
        final State state = players.get(slot);
        state.blocked = false;
        state.intent++;
    }

    public void manualPause(Slot slot) {
        final State state = players.get(slot);
        state.blocked = true;
        state.intent++;
    }

    public void seek(Slot slot) {
        if (slot == Slot.AUDIO) pendingAdvance = null;
    }

    public Item takePendingAdvance(Item current, boolean explicitItemPlay) {
        final Item pending = pendingAdvance;
        pendingAdvance = null;
        return !explicitItemPlay && Objects.equals(pending, current) ? pending : null;
    }

    public boolean blocked(Slot slot) { return players.get(slot).blocked; }
}
