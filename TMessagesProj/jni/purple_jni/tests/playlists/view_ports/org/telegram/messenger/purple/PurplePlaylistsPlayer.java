package org.telegram.messenger.purple;
public final class PurplePlaylistsPlayer {
    public static final class State { public int account; }
    public boolean start(PurplePlaylistsAccount.Captured account, java.util.List<PurplePlaylistsView.Song> songs, int index) {
        throw new AssertionError("Audio admission is outside the storage fixture");
    }
    public boolean start(PurplePlaylistsAccount.Captured account, java.util.List<PurplePlaylistsView.Song> songs, int index, String playlist) {
        return start(account, songs, index);
    }
    public boolean retry(State state) { throw new AssertionError("Audio Retry is outside the storage fixture"); }
}
