# Sleep timer

The proposed timer uses one device-local process-wide deadline or an end-of-item
binding. Only choice/custom duration are remembered. Active timers and media
identities are never persisted or synchronized.

The player and viewer reuse stock menus, moon, palettes and duration wheels.
The compact strip shows the active countdown. Neither PiP form gets a timer
control. Gallery selections use typed process-local source identities, retained
across quality reconstruction and PiP, rather than invented Telegram IDs or
media paths. Timed enforcement includes registered gallery native playback.

Saved music is retained as a paused selected model without preparation. Later
blocked cleanup retires the actual voice/round native ownership before adoption;
explicit Play then prepares the selected song. Expiry itself remains pause-only.

Registered finite controllable web playback uses pause-only controls and captured
load/item/intent/generation receipts. The frame port requires both WebKit
DOCUMENT_START_SCRIPT and WEB_MESSAGE_LISTENER. Listener and document-start
installation precede init navigation; commands use the originating frame's
JavaScriptReplyProxy. The existing YouTube wrapper remains the selected adapter
and the documented wrapper path remains available without those features.

The frame observer tracks actual media elements and source incarnations. Only
Blob/File objects whose construction was observed can establish immutable
resource evidence; a blob URL, MSE, duration or seek range cannot. Generic element
discovery does not identify the intended player, including ads. These candidates
remain SELECTION_UNRESOLVED and disabled until a source-backed provider adapter
exists. This includes an observed finite Blob with unresolved selection.

YouTube classification requires an explicit boolean videoDetails.isLiveContent
and a matching videoId for the current native load. Existing preview responses
and already-occurring frame bootstrap/fetch/XHR responses may supply that
information; the timer does not request or replay metadata. Response availability,
schema and selected provider item continuity are not guaranteed. Missing metadata,
known live playback and unsupported generic selection keep disabled menu states.
Full finite embed support remains required and OPEN for these missing contracts.

Embedded end arming waits for fresh state and a locally installed end fence,
including requests through the shared picker. Local fences retain a reached end
across native queues and stop wrapper readiness/resize continuation. Explicit
native Play can release the fence; refresh and PiP transfer do not grant Play.
The same WebView and port survive its existing floating PiP surface transfer.
Native-player PiP handoff keeps its prior gates.

The sleep-timer host suite exercises actual production rules, retirement
ordering, saved selection and identity. A source-bound fixture also executes
the timer facade, Audio request classification and Saved Music list refresh
against inert player endpoints. Its 18 checks cover healthy fallback, expiry,
missing or restored selections, repeated refresh and deliberate Play. The
unfixed refresh fails the same silence assertion. All 135 prior rule checks
remain. A rules fixture covers PlayRequest on both slots (55 checks). A second
source-bound fixture executes the actual media session callbacks, the session
helper and the chat timestamp block over inert storage, player, viewer and UI
queue endpoints (59 session and 52 timestamp checks). These counts are measured against the production members. The fixture renames the one fully
qualified timer call in the timestamp block to its own facade copy. App/Media3/WebView behavior,
no-network/Cast isolation, UI and native ownership need separate qualification.
elapsedRealtime counts sleep, but Handler scheduling does not establish Doze
wake delivery. No new service, permission or exact alarm is proposed.

Automatic viewer collection refreshes and SavedState restoration retain their
pause/expiry fence. Verified actual opening uses USER_OPEN and can authorize
stock playback for the same retained item. Thumbnail/navigation USER_SELECTION
requires a changed logical item. Numeric reindex and surface restoration remain
REFRESH and do not authorize Play. Deliberate transport Play remains ordinary.

Deferred explicit Play uses a one-shot PlayRequest taken when the command
arrives. It needs no selected item and changes no playback state: the stock
explicit Play that runs on completion lifts the fence as usual. Pause, expiry,
a new selection, player replacement, a direct Play or a newer request on the
same slot revokes it. Starting, extending or cancelling the timer does not.
A deadline that passed unchecked expires before the request is judged.

Media session prepare selects the dialog, queue and title without starting
playback, so it never lifts the expiry fence. A later plain Play plays the
prepared item; a Play that arrives while the prepare is still loading waits for
it. A lazily loaded Play keeps its request through the load instead of entering
stock Play with no receipt. The newest session command, an account switch or
session release revokes older ones. A prepare overtaken by another audio
selection falls back to stock Play. Chat timestamp navigation takes its request
when it schedules the 40 ms open (VIEWER for video, AUDIO for voice and round)
and opens or plays only if the request is still current. A stale timestamp
callback opens nothing and leaves forceSeekTo for the next deliberate Play.

Upstream hooks and why they remain:

- `MediaController` delegates request classification, deferred resumes,
  end handling and Saved Music restoration to `PurpleSleepTimerAudio`.
  Its small facade methods expose existing private player/model state without
  copying stock playback into Purple code. Saved Music fallback is automatic
  and cannot grant Play after expiry. Proximity-lower recreation still needs
  separate pause-intent follow-up.
- `VideoPlayer` delegates native preparation, start and callback admission.
  This fences actual native starts after a receipt becomes stale.
- `AudioPlayerAlert` and `FragmentContextView` delegate menus and countdown
  strips to Purple UI helpers while retaining stock layout ownership.
- `PhotoViewer` delegates viewer identity, navigation intent, native player
  replacement and the timer surface. Its facade methods expose the private
  viewer state used by `PurpleSleepTimerViewer` and `PurpleSleepTimerWeb`.
- `PhotoViewerWebView` delegates construction, initialization and state events
  to `PurpleEmbeddedPlaybackPort` and `PurpleEmbeddedPlaybackBridge`.
  The existing `youtube_embed.html` wrapper reports load-scoped identity and
  end state to the selected adapter.
- `MusicPlayerReceiver`, `MusicPlayerService` and `ScreenReceiver` delegate
  transport commands and lifecycle transitions so automatic callbacks cannot
  acquire explicit playback intent.
- `PipVideoOverlay`, `PipActivityController` and `PipSourceHandlerState2`
  transfer the existing playback authority between surfaces. PiP gains no
  timer control or new ownership grant.
- `LaunchActivity` delegates foreground enforcement and user feedback.
- `TelegramMediaSession` delegates prepare, the lazy-load continuation, the
  playlist start and plain Play to `PurpleSleepTimerSession`, and revokes its
  commands on account switch and release. Stock selection, queue and title
  code stays in place; only the setPlaylist call is guarded.
- `ChatActivity` wraps its timestamp callback with `PurpleSleepTimer#deferPlay`.
  The stock open and play body is unchanged.

Frame bookkeeping, classification and native receipts live in Purple files.
No dependency, service, permission, Cast command or global WebView pauseTimers
operation is added. Real AGP compilation, frame/JavaScript execution,
request-count equality, ownership/audio behavior and Doze delivery remain
separate required qualification.

The selected YouTube adapter verifies the current getVideoUrl identity before
state, acknowledgments, commands and end handling. Matching native configuration
alone does not establish current identity. A mismatch or loss after verification
permanently retires that load's selection, finite classification and end authority.
An A-to-B-to-A provider transition cannot revive the old handle. A fresh native
init/binding is required. The legacy wrapper uses the same identity guard and a
load-scoped identity notification. Missing identity before any verification stays
ineligible; no discovery request or duration inference supplies it.

The embedded-port identity and finite-evidence fixture runs with the host suite
(18 checks). A separate headless Chromium check executes the unchanged wrapper
and timer scripts against a real HTML media element with a synthetic provider
port. Its 22 checks cover local pause, readiness/resize fencing, native end,
explicit resume, stale authorization and irreversible provider identity retirement.
The timer and control pages each issue the same two locally fulfilled requests.
This does not establish Android WebView, a real provider, audible output or Cast
isolation. The four deliberately removed deferred-command hooks are rejected by
the same permanent assertions.

The custom-duration Start button reuses the stock rounded ButtonWithCounterView
from Stories.recorder, alongside the stock NumberPicker duration wheels.

The Wait/Peek host fixture keeps its real AGP business classes and uses an
explicit inert timer screen-pulse port. It checks that the actual ScreenReceiver
calls that port once on screen-off. Timer behavior is covered separately by the
sleep-timer suite. The nested Peek driver stays in the same interpreter so the
canonical compiler wrapper also surrounds its compiler calls.

The media host fixture preserves its original strip and seek hook pins and
checks the new timer transport hooks separately. Its extracted queue and media
receiver use explicit timer ports with the timer inactive, and assert that the
automatic, restore and deliberate Play hooks are exercised. These checks prove
host integration, not Android player or speaker behavior.
