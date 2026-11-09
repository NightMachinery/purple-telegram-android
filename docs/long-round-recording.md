# Longer local round recording

The Media recording settings screen offers a per-account local recording length
of 1, 3 or 5 minutes. The default is one minute. This changes recording length,
not Telegram's circle format: circles remain at most one minute. Local does not
mean offline: the delivered native recorder already uploads temporary file parts
while recording, before message-send approval. This feature does not redesign
that transport behavior.

The local preference uses the account's existing main SharedPreferences. It does
not change the shared core settings schema. UniversalFragment UItem buttons,
AlertDialog item choosers and the stock Minutes plural supply settings UI. New
explanatory text lives in purple_strings.xml.

Legacy captures the selected limit when a new take starts, retains it on resume,
and snapshots it for its encoder's audio drain. Its progress ring uses the same
limit. The composer timer keeps the existing half-second stop margin and reads
the setting only for a new take. V2 passes the selected limit to the existing
RoundVideoSession builder; native duration timers, trim/resume admission and
finalization already use that immutable session limit. V2 progress reads the
session's actual maximum. One-minute behavior retains the existing59.5s composer
stop and60s native/audio bounds.

Native encoders stream to disk rather than retaining a take in Java memory.
V2 remuxing uses its existing bounded2MiB sample buffer. Increasing local length
increases disk usage and processing time; it changes no capture codec, source
resolution or global camera policy. Hardware startup, real audio/video duration
and release still require a device pass.

The host regression executes the production preference and extracted native
composer, legacy ring/audio-drain and V2 duration/trim-resume expressions. A
negative control compiles the delivered60s expressions with a3minute preference
and fails its cutoff contract. These inert-port checks do not exercise codecs or
physical capture. Source and device evidence have separate acceptance scopes.


For recordings longer than one minute, normal Send prepares circle parts by
default and opens a single review with the part count and retained-original
notice. The primary button sends those parts. A secondary button prepares one
ordinary full-length video instead and returns to review. Neither path sends
until the final review and any stock paid-message confirmation are approved.
The Recording bin marks these entries as long local recordings and offers the
same default. Routing uses the retained file's measured duration, even if editable preview
metadata describes a shorter trim. The full retained source is used, rather than replacing it with a
one-minute trim or deleting it after a successful queue acknowledgement.

Circle preparation balances consecutive source intervals with a 59.5-second
planning ceiling, avoiding a tiny final tail. All parts are prepared and checked
before enqueue. Each has independently encoded H.264 video and AAC audio,
measured duration at most 60 seconds, square dimensions, an opening video sync
sample, and bounded timestamps. A scoped MediaCodecVideoConvertor extension
accepts an owned AudioInput and its microsecond duration. PurpleLongRoundAudioInput
excludes fully preceding decoded blocks and uses the native decoder's partial
PCM trim, so prior-sync AAC packets are not blindly copied across a boundary.
Ordinary video preserves all original bytes and carries roundVideo=false.
Prepared files bypass another conversion; native send code reads their actual
media attributes. No longer-than-one-minute circle metadata is emitted.

Preparation runs on one worker, checks available disk space with a reserve,
limits duration and source size, honors cancellation and a deadline, and hashes
the source before and after. Only individually owned derived files are removed
on cancellation or failure. The bin original remains under the existing retention
policy, including after successful send. User deletion and normal retention
expiry still apply. A failed preparation can be retried from that original. If circle encoding
fails, its error dialog also offers the full ordinary-video alternative without
requiring a successful circle encode first.

Each request captures account, user, target, topic, reply and send-as arguments
before asynchronous work. Each output gets a separate copy of the same frozen
send identity. Delivery queues one part at a time and advances only after local
storage acknowledgement. This proves local queueing, not server delivery. A
timeout or identity change stops the sequence; a partial or uncertain queue
result tells the user to check the chat before retrying, rather than silently
resending potentially queued parts. No durable outbox or automatic retry is
introduced. Existing short recordings retain their prior send path.

Host tests execute the production interval plan, preparation, output attributes,
queue state and owned audio adapter over inert Android ports. A synthetic long
H.264/AAC source is split through a FFmpeg converter port, then every part is
probed and decoded. This checks the preparation contract and independently
playable synthetic outputs; it does not execute Android MediaCodec, UI rendering,
physical capture or real message sending, and does not establish sample-perfect
continuity on a device. Production delivery and preview tests cover captured
identity, delayed lookup, queue acknowledgement and original retention. Device
qualification must check both recorder adapters, boundary audio and video,
cancellation, storage pressure, partial sends and the stock AlertDialog review
beside a stock confirmation in light, dark, long text and RTL layouts.

The actual owned review/confirmation methods also run against inert dialog ports:
circles are the default, ordinary video is secondary, review cancellation sends
nothing, paid approval covers the prepared count, and a duplicate approval cannot
restart or abort an already accepted sequence.
