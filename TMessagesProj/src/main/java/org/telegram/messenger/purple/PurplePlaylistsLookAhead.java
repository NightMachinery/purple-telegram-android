package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

final class PurplePlaylistsLookAhead implements AutoCloseable {
    interface Port {
        boolean current();
        void defer(Runnable action);
        AutoCloseable resolve(PurplePlaylistsView.Song song, Consumer<PurplePlaylistsResolver.Result> done);
    }

    private final Port port;
    private final List<PurplePlaylistsView.Song> songs;
    private final HashMap<PurplePlaylistsView.Song, PurplePlaylistsResolver.Result> cache = new HashMap<>();
    private final ArrayList<PurplePlaylistsView.Song> window = new ArrayList<>();
    private AutoCloseable pending;
    private long generation;
    private boolean closed;

    PurplePlaylistsLookAhead(List<PurplePlaylistsView.Song> songs, Port port) {
        this.songs = songs; this.port = port;
    }

    PurplePlaylistsResolver.Result take(PurplePlaylistsView.Song song) {
        stop();
        return cache.remove(song);
    }

    void forget(PurplePlaylistsView.Song song) { cache.remove(song); }

    void playing(PurplePlaylistsResolver.Result current, List<Integer> traversal, int selected,
            PurplePlaylistsPlayback.Repeat repeat) {
        stop();
        if (closed || !port.current() || current == null || !current.playable()) return;
        window.clear(); window.add(current.song);
        int position = traversal.indexOf(selected);
        if (position < 0) return;
        if (repeat != PurplePlaylistsPlayback.Repeat.One) {
            for (int offset = 1; offset <= 3 && offset < traversal.size(); ++offset) {
                int next = position + offset;
                if (next >= traversal.size() && repeat != PurplePlaylistsPlayback.Repeat.All) break;
                PurplePlaylistsView.Song song = songs.get(traversal.get(next % traversal.size()));
                if (!window.contains(song)) window.add(song);
            }
        }
        cache.keySet().retainAll(window);
        cache.put(current.song, current);
        long expected = generation;
        port.defer(() -> next(expected, 1));
    }

    private boolean current(long expected) { return !closed && expected == generation && port.current(); }

    private void next(long expected, int position) {
        if (!current(expected) || position >= window.size()) return;
        PurplePlaylistsView.Song song = window.get(position);
        PurplePlaylistsResolver.Result cached = cache.get(song);
        if (cached != null) {
            if (cached.status != PurplePlaylistsResolver.Status.Wait) port.defer(() -> next(expected, position + 1));
            return;
        }
        final boolean[] received = { false };
        try {
            AutoCloseable request = port.resolve(song, result -> port.defer(() -> {
                if (!current(expected) || received[0]) return;
                received[0] = true; closePending();
                if (result == null || result.song != song) return;
                cache.put(song, result);
                if (result.status != PurplePlaylistsResolver.Status.Wait) port.defer(() -> next(expected, position + 1));
            }));
            if (current(expected) && !received[0]) pending = request;
            else close(request);
        } catch (RuntimeException ignored) {
            if (current(expected)) closePending();
        }
    }

    void stop() { ++generation; closePending(); }
    private void closePending() { AutoCloseable request = pending; pending = null; close(request); }
    private static void close(AutoCloseable request) {
        if (request != null) {
            try { request.close(); } catch (Exception ignored) {}
        }
    }
    @Override public void close() { stop(); closed = true; cache.clear(); window.clear(); }
}
