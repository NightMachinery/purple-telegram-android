package org.telegram.messenger;
public final class MediaController {
    private static final MediaController instance = new MediaController();
    private final org.telegram.messenger.purple.PurplePlaylistsPlayer player = new org.telegram.messenger.purple.PurplePlaylistsPlayer();
    public static MediaController getInstance() { return instance; }
    public org.telegram.messenger.purple.PurplePlaylistsPlayer purplePlaylists() { return player; }
}
