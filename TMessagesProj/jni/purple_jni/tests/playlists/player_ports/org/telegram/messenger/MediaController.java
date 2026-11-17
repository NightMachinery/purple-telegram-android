package org.telegram.messenger;
import org.telegram.messenger.purple.*;
import java.util.*;
public final class MediaController {
 public static MediaController instance; public static MediaController getInstance() { return instance; }
 public final PurpleSleepTimerAudio audio = new PurpleSleepTimerAudio();
 private final PurplePlaylistsPlayer purplePlaylists = new PurplePlaylistsPlayer(this, audio);
 public PurplePlaylistsPlayer purplePlaylists() { return purplePlaylists; }
 public final ArrayList<MessageObject> playlist = new ArrayList<>(), shuffledPlaylist = new ArrayList<>();
 public final Map<Integer,MessageObject> playlistMap = new HashMap<>();
 Object currentSavedMusicList, playlistGlobalSearchParams, savedMusicPlaylistState;
 int playlistClassGuid, currentPlaylistNum; int[] playlistMaxId = new int[2]; long playlistMergeDialogId;
 boolean[] playlistEndReached = new boolean[2]; boolean loadingPlaylist, forceLoopCurrentPlaylist, playMusicAgain;
 public MessageObject playing; public boolean paused, accept = true; public int starts, cleanups, seeks, history, prefetch, pages;
 public MediaController() { instance = this; }
 public MessageObject getPlayingMessageObject() { return playing; }
 public boolean isMessagePaused() { return paused; }
 public boolean seekToProgress(MessageObject message, float progress) { seeks++; message.audioProgressSec = 0; return true; }
 private final PurpleSleepTimerAudio purpleSleep = audio;
 public boolean playMessage(MessageObject message) { return playMessage(message, false); }
 /* EXTRACT:play */
 /* END:play */
 private boolean purpleSleepPlayMessage(MessageObject message, boolean silent) {
   if (!accept) return false; starts++; playing = message; paused = false; return true;
 }
 public void cleanupPlayer(boolean notify, boolean stop) { purplePlaylists.stopped(stop, false); cleanups++; playing = null; paused = true; }
 public void pauseMessage() { purplePlaylists.pause(playing); PurpleSleepTimer.get().rules.manualPause(PurpleSleepTimerRules.Slot.AUDIO); paused = true; }
 public void loadMoreMusic() { /* EXTRACT:history */
/* END:history */ history++; }
 public void prefetch() { /* EXTRACT:prefetch */
/* END:prefetch */ prefetch++; }
 public void page(int event) { switch (event) {
case 0: /* EXTRACT:page0 */
/* END:page0 */ pages++; break;
case 1: /* EXTRACT:page1 */
/* END:page1 */ pages++; break;
case 2: /* EXTRACT:page2 */
/* END:page2 */ pages++; break;
} }
/* EXTRACT:bridge */
/* END:bridge */
/* EXTRACT:release */
/* END:release */
/* EXTRACT:clear */
/* END:clear */
}
