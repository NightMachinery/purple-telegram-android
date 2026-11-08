# Bluetooth play/pause investigation

Against the exact merged Telegram 13.1.1 source, MusicPlayerService differs
only by F9's seek import, rewind/fast-forward callbacks, custom-action guard,
and two additive seek-action masks. Both existing masks retain PLAY_PAUSE,
PLAY and PAUSE. The custom guard consumes only Purple's two seek names.
There is no new onMediaButtonEvent override; the normal media-session callback
still provides onPlay and onPause, and setActive(true) is unchanged.

MusicPlayerReceiver, MusicBrowserService and TelegramMediaSession match that
upstream release. The legacy RemoteControlClient still advertises play, pause
and play/pause. Both stock and Purple can construct a player session tagged
telegramAudioPlayer and a separate browser session tagged TelegramMediaSession.
Only the former is published by MusicPlayerService; seeing the latter in code
alone does not establish which Android selected for a Bluetooth event.

Host coverage runs the real receiver on HEADSETHOOK and MEDIA_PLAY_PAUSE down,
up and next down: one pause, no double toggle, then play. The F9 seek suite
pins the additive masks and action guard. This establishes no code-proven F9
play/pause regression, so no speculative session override is added.

Device evidence needed: capture `adb shell dumpsys media_session` while Purple
music is playing, immediately after a Bluetooth button press, while paused,
and after another press; repeat for a voice. Record the addressed media-button
session, active session ordering/tags, package, state and supported actions,
plus any delivery or audio-focus logs. Compare a stock Telegram run on the
same device. If Android selects the browser session with stale STATE_NONE,
that observation would justify unifying session publication or deactivating
the idle competing session. Keep dumps private: they may include track titles,
chat details and other applications' sessions.
