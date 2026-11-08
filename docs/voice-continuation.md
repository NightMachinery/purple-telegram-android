# Voice and round continuation

Purple Settings > Media playback has two app-wide rows next to Seek step:

- **After a voice message**: Resume music stops after the tapped message;
  Play unheard, then resume music (default) skips outgoing messages and plays
  incoming voice and round messages until the first already-heard one;
  Play all following, then resume music keeps upstream's eligible mixed queue,
  applying the existing private-chat ownership switch.
- **Resume music after voice messages** (default on): interrupted music returns
  playing. Off restores the same track and position paused, as upstream does.

Without interrupted music the selected queue runs to the same boundary and
stops. The settings are captured on the next manual play, including a replay of
the same message. Unheard uses MessageObject.isContentUnread(), not a guessed
playback percentage. Telegram synchronizes that listened flag: another device
can clear it, and a played file need not have reached its last sample. The
queue observes loaded messages and eligible live appends; it does not fetch
history solely to extend playback. View-once media remains excluded.

The settings live in purple-core's Playback and travel in settings.toml:
`after_voice = "resume_music" | "unheard" | "all_following"` and
`resume_music_after_voice_p = true | false`. Defaults are unheard and true.
The existing `private_chat_queue_p` remains independent: All following confines
private-chat queues started on incoming messages when that switch is on;
Unheard always means incoming in private chats, groups and channels. A heard
incoming item closes the Unheard queue, including future appends. Skipped own
messages do not form a heard boundary.

PurpleVoicePlayback owns the policy and settings snapshot; purple-core's
ContinuationDecision defines Skip/Play/Stop. Upstream hooks in ChatActivity
route queue construction through PurpleChatHooks. MediaController delegates
live append filtering, gives its real mixed queue priority at natural audio
and round completion, and retains the interrupted music playlist when cleanup
has already cleared the playing object. Finally a guard controls upstream's
pause-after-restore. The previous unconditional pause explained silent music
restoration; clearing the music list on an automatic next voice could also
leave its saved track with no list to restore from. No media encoding or
recording behavior changes.

Host cases run the actual builder entry, Purple helper, completion branches,
playlist cleanup and restoration with inert Android and player ports, with
all mode/switch/music/ownership combinations and both media kinds. Core cases
exercise parsing, warnings, defaults, app-wide scope and the real JNI rule.
They do not qualify Android audio focus, downloads, Bluetooth, or rendering.
