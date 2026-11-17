package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.purple.PurplePlaylistsPlayer;
import org.telegram.messenger.purple.PurplePlaylistsView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PurplePlaylistsAudioQueue implements PurplePlaylistsPlayer.Listener {
    private final PurplePlaylistsPlayer player;
    private final PurplePlaylistsPlayer.Queue queue;
    private PurplePlaylistsPlayer.Observation observation;
    private Runnable refresh;
    private Runnable dismiss;
    private List<Integer> filtered;
    private String playing = "";
    private Runnable pendingSearch;
    private int searchGeneration;
    private boolean closed;

    private PurplePlaylistsAudioQueue(PurplePlaylistsPlayer player, PurplePlaylistsPlayer.Queue queue) {
        this.player = player;
        this.queue = queue;
    }

    static PurplePlaylistsAudioQueue open(int account) {
        PurplePlaylistsPlayer player = MediaController.getInstance().purplePlaylists();
        PurplePlaylistsPlayer.Queue queue = player.displayedQueue(account);
        return queue == null ? null : new PurplePlaylistsAudioQueue(player, queue);
    }

    void observe(Runnable refresh, Runnable dismiss) {
        this.refresh = refresh;
        this.dismiss = dismiss;
        observation = player.observe(queue.account, this);
    }

    @Override public void playbackChanged(PurplePlaylistsPlayer.State state) {
        if (closed) return;
        if (!player.ownsQueue(queue)) {
            dismiss.run();
            return;
        }
        if (!playing.equals(state.playingDocumentId)) {
            playing = state.playingDocumentId;
            refresh.run();
        }
    }

    int size() {
        return filtered == null ? queue.songs.size() : filtered.size();
    }

    private int index(int position) {
        return filtered == null ? position : filtered.get(position);
    }

    TextDetailCell createRow(Context context, Theme.ResourcesProvider provider) {
        TextDetailCell cell = new TextDetailCell(context, provider);
        cell.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, provider));
        return cell;
    }

    void bind(View view, int position) {
        TextDetailCell cell = (TextDetailCell) view;
        int sourceIndex = index(position);
        PurplePlaylistsView.Song song = queue.songs.get(sourceIndex);
        String title = song.title.isEmpty() ? LocaleController.getString(R.string.AudioUnknownTitle) : song.title;
        String detail = song.performer.isEmpty() ? LocaleController.getString(R.string.AudioUnknownArtist) : song.performer;
        if (song.duration > 0) detail += " · " + AndroidUtilities.formatDuration(song.duration, false);
        if (song.documentId.equals(playing)) detail += " · " + LocaleController.getString("PurplePlaylistsNowPlaying");
        cell.setTextAndValue(title, detail, position + 1 < size());
        cell.setTag(sourceIndex);
    }

    void select(View view) {
        if (!closed && view.getTag() instanceof Integer) player.selectQueueSong(queue, (Integer) view.getTag());
    }

    boolean scrollToPlaying(LinearLayoutManager layout) {
        String document = player.snapshot().playingDocumentId;
        for (int position = 0; position < size(); position++) {
            if (queue.songs.get(index(position)).documentId.equals(document)) {
                layout.scrollToPosition(position);
                return true;
            }
        }
        return false;
    }

    void search(String query, Runnable changed) {
        cancelSearch();
        int generation = searchGeneration;
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (term.isEmpty()) {
            filtered = null;
            changed.run();
            return;
        }
        pendingSearch = () -> {
            List<Integer> matches = new ArrayList<>();
            for (int index = 0; index < queue.songs.size(); index++) {
                PurplePlaylistsView.Song song = queue.songs.get(index);
                if (song.title.toLowerCase(Locale.ROOT).contains(term)
                        || song.performer.toLowerCase(Locale.ROOT).contains(term)) matches.add(index);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (closed || generation != searchGeneration) return;
                pendingSearch = null;
                filtered = matches;
                changed.run();
            });
        };
        Utilities.searchQueue.postRunnable(pendingSearch, 300);
    }

    private void cancelSearch() {
        ++searchGeneration;
        if (pendingSearch != null) Utilities.searchQueue.cancelRunnable(pendingSearch);
        pendingSearch = null;
    }

    void close() {
        closed = true;
        cancelSearch();
        if (observation != null) observation.close();
        observation = null;
        refresh = dismiss = null;
    }
}
