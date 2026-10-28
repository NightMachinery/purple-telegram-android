# Typed carry I/O host suite

`run_sync_chat_move_io.py` consumes real AGP, host JNI, core carry fixtures and
the modern/store controlled platform ports. It loads every carry business class
from AGP and encodes/decodes real generated Telegram TL. Run it through the
canonical host runner as `sync-chat-move-io`; prerequisites are registered there.

Cases cover ordinary/channel equal-number peers, flags, final metadata and C0,
owned complete scans, immutable bytes, durable reservation and restart, reader
and account/login fences, unknown reconciliation, exact mark readback, size and
durability refusal, upload renewal/cancel/throwing cleanup, stale timers,
combined flood waits, duplicate callbacks and late captured-account Updates.
The one-byte capacity boundary is red on the pre-fix AGP classes and requires the longer false boolean plus every maximum acknowledgement to fit before admission.
All account files and endpoints are synthetic. No device, UI or Telegram request
is used. See docs/sync-chat-move.md for the contract and native gaps.

Fix-round regressions retain overlapping absence tickets through a later uncertain
attempt, both within one wrapper and across wrappers sharing a store. Delayed
request and own-upload renewal callbacks reject a legitimate successor from a
different journal revision or opening epoch; same-revision epoch replacement is
a separate control. Existing same-epoch renewal remains the success control.
