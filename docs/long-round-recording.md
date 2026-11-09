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
