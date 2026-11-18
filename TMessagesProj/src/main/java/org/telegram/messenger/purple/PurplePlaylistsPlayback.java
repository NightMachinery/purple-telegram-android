package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class PurplePlaylistsPlayback implements AutoCloseable {
    enum Order { Normal, Reverse, Shuffle }
    enum Repeat { None, One, All }
    enum Status { Idle, Resolving, Playing, Paused, Retry, Wait, Exhausted, Closed }

    interface Gate { boolean start(BooleanSupplier action); }
    interface Port {
        boolean current();
        void defer(Runnable action);
        AutoCloseable resolve(PurplePlaylistsView.Song song, Consumer<PurplePlaylistsResolver.Result> done);
        Gate gate(boolean automatic);
        boolean start(PurplePlaylistsResolver.Result result, boolean restart);
        void exhausted(boolean automatic);
        void changed(State state);
    }

    static final class State {
        final Status status;
        final int selected, playing, waitSeconds;
        final PurplePlaylistsResolver.Status sourceStatus;
        final List<Integer> traversal;
        State(PurplePlaylistsPlayback queue) {
            status = queue.status; selected = queue.selected; playing = queue.playing;
            waitSeconds = queue.waitSeconds; sourceStatus = queue.sourceStatus;
            traversal = Collections.unmodifiableList(new ArrayList<>(queue.traversal));
        }
    }

    private final List<PurplePlaylistsView.Song> songs;
    private final ArrayList<Integer> traversal = new ArrayList<>();
    private final Port port;
    private final Random random;
    private Order order;
    private Repeat repeat;
    private Status status = Status.Idle;
    private PurplePlaylistsResolver.Status sourceStatus;
    private int selected = -1, playing = -1, waitSeconds, direction = 1;
    private long generation;
    private AutoCloseable pending;
    private Gate gate;
    private boolean automatic, restart, wrap;

    PurplePlaylistsPlayback(List<PurplePlaylistsView.Song> songs, Order order, Repeat repeat,
            Port port, Random random) {
        if (songs == null || order == null || repeat == null || port == null || random == null)
            throw new IllegalArgumentException("Missing playlist playback endpoint");
        HashSet<String> documents = new HashSet<>();
        for (PurplePlaylistsView.Song song : songs) {
            if (song == null || !documents.add(song.documentId))
                throw new IllegalArgumentException("Invalid playlist playback snapshot");
        }
        this.songs = Collections.unmodifiableList(new ArrayList<>(songs));
        this.order = order; this.repeat = repeat; this.port = port; this.random = random;
        rebuild(-1);
    }

    State state() { return new State(this); }

    private boolean current() {
        if (status == Status.Closed) return false;
        if (port.current()) return true;
        close();
        return false;
    }

    boolean play(int index) {
        if (!current() || index < -1 || index >= songs.size()) return false;
        if (songs.isEmpty()) { finish(false); return true; }
        if (index < 0) index = selected < 0 ? traversal.get(0) : selected;
        if (order == Order.Shuffle) rebuild(index);
        begin(index, false, false, 1, false);
        return true;
    }

    boolean retry() {
        return selected >= 0 && play(selected);
    }

    boolean next(boolean byEnd) { return step(1, byEnd); }
    boolean previous() { return step(-1, false); }

    private boolean step(int direction, boolean byEnd) {
        if (!current()) return false;
        if (byEnd && (status == Status.Retry || status == Status.Wait || status == Status.Paused
                || status == Status.Resolving || status == Status.Exhausted)) return true;
        if (selected < 0) return false;
        int position = traversal.indexOf(selected);
        int next = byEnd && repeat == Repeat.One ? position : position + direction;
        if (next < 0 || next >= traversal.size()) {
            if (byEnd && repeat != Repeat.All) {
                finish(true);
                return true;
            }
            next = Math.floorMod(next, traversal.size());
        }
        int target = traversal.get(next);
        begin(target, byEnd, byEnd ? repeat == Repeat.One : target == playing, direction, !byEnd);
        return true;
    }

    void modes(Order value, Repeat loop) {
        if (value == null || loop == null) throw new IllegalArgumentException("Missing playback mode");
        if (!current()) return;
        repeat = loop;
        if (order == value) return;
        boolean resolving = status == Status.Resolving;
        Gate admission = gate;
        boolean wasAutomatic = automatic, wasRestart = restart;
        int requested = selected;
        cancel();
        order = value;
        rebuild(playing >= 0 ? playing : selected);
        if (resolving) {
            selected = requested;
            gate = admission; automatic = wasAutomatic; restart = wasRestart;
            status = Status.Resolving;
            resolve(generation, direction, new HashSet<>());
        } else publish();
    }

    void paused() {
        if (!current()) return;
        cancel();
        if (playing >= 0) selected = playing;
        status = Status.Paused;
        publish();
    }

    void resumed() {
        if (!current() || playing < 0) return;
        cancel(); selected = playing; status = Status.Playing; publish();
    }

    void nativeFailed() {
        if (current()) failed(PurplePlaylistsResolver.Status.Failed, 0);
    }

    private void rebuild(int anchor) {
        traversal.clear();
        for (int i = 0; i < songs.size(); ++i) traversal.add(i);
        if (order == Order.Reverse) Collections.reverse(traversal);
        if (order == Order.Shuffle) {
            Collections.shuffle(traversal, random);
            if (anchor >= 0) {
                traversal.remove(Integer.valueOf(anchor));
                traversal.add(0, anchor);
            }
        }
    }

    private void begin(int index, boolean byEnd, boolean restart, int direction, boolean wrap) {
        cancel();
        selected = index; automatic = byEnd; this.restart = restart; this.direction = direction; this.wrap = wrap;
        sourceStatus = null; waitSeconds = 0;
        gate = port.gate(byEnd);
        if (gate == null) { status = Status.Retry; publish(); return; }
        status = Status.Resolving;
        long command = generation;
        publish();
        if (current() && command == generation) resolve(command, direction, new HashSet<>());
    }

    private void resolve(long command, int direction, HashSet<Integer> visited) {
        if (!current() || command != generation) return;
        int index = selected;
        if (!visited.add(index)) { finish(automatic); return; }
        final boolean[] received = { false };
        try {
            AutoCloseable request = port.resolve(songs.get(index), result -> port.defer(() -> {
                if (!current() || command != generation || received[0]) return;
                received[0] = true;
                cancelRequest();
                accept(command, index, direction, visited, result);
            }));
            if (command == generation && status == Status.Resolving && !received[0]) pending = request;
            else closeRequest(request);
        } catch (RuntimeException e) {
            if (current() && command == generation && !received[0]) {
                received[0] = true;
                failed(PurplePlaylistsResolver.Status.Failed, 0);
            }
        }
    }

    private void accept(long command, int index, int direction, HashSet<Integer> visited,
            PurplePlaylistsResolver.Result result) {
        if (result == null || result.song != songs.get(index)) {
            failed(PurplePlaylistsResolver.Status.Failed, 0); return;
        }
        sourceStatus = result.status;
        if (result.playable()) {
            if (result.message == null || (result.source != result.song.source
                    && !result.song.alternatives.contains(result.source))) {
                failed(PurplePlaylistsResolver.Status.Failed, 0); return;
            }
            Gate admission = gate;
            gate = null;
            final boolean[] consumed = { false };
            boolean started;
            try {
                started = admission != null && admission.start(() -> {
                    if (consumed[0] || !current() || command != generation) return false;
                    consumed[0] = true;
                    return port.start(result, restart);
                });
            } catch (RuntimeException e) { started = false; }
            if (!current() || command != generation) return;
            if (started && consumed[0]) {
                playing = selected = index; status = Status.Playing;
                publish();
            } else failed(PurplePlaylistsResolver.Status.Failed, 0);
            return;
        }
        if (result.status == PurplePlaylistsResolver.Status.Deleted
                || result.status == PurplePlaylistsResolver.Status.NoAccess
                || result.status == PurplePlaylistsResolver.Status.Changed
                || result.status == PurplePlaylistsResolver.Status.Restricted) {
            int position = traversal.indexOf(index) + direction;
            if (position < 0 || position >= traversal.size()) {
                if (!wrap && repeat != Repeat.All) { finish(automatic); return; }
                position = Math.floorMod(position, traversal.size());
            }
            selected = traversal.get(position);
            port.defer(() -> resolve(command, direction, visited));
        } else failed(result.status, result.waitSeconds);
    }

    private void failed(PurplePlaylistsResolver.Status reason, int wait) {
        cancel();
        sourceStatus = reason;
        status = reason == PurplePlaylistsResolver.Status.Wait ? Status.Wait : Status.Retry;
        waitSeconds = status == Status.Wait ? Math.max(1, wait) : 0;
        publish();
    }

    private void finish(boolean byEnd) {
        cancel(); status = Status.Exhausted;
        long command = generation;
        port.exhausted(byEnd);
        if (current() && command == generation) publish();
    }

    private void publish() { port.changed(state()); }
    private void cancel() { ++generation; cancelRequest(); gate = null; }
    private void cancelRequest() { AutoCloseable request = pending; pending = null; closeRequest(request); }
    private static void closeRequest(AutoCloseable request) {
        if (request != null) {
            try { request.close(); } catch (Exception ignored) {}
        }
    }

    @Override public void close() {
        if (status == Status.Closed) return;
        cancel(); status = Status.Closed;
    }
}
