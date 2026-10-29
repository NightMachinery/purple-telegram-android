# Older Purple marks

The Sync chat screen lists supported Own marks from this account when a complete
discovery found a newer winner. A row captures the account/login, normalized chat,
message id, date, full text and author identity. Its separate **Remove mark**
confirmation describes only that selected message. Search results identify rows;
they do not authorize deletion.

Removal is independent of moving records, but its production constant remains
OFF pending qualification, as requested for this slice. The public action holds
before constructing an executor or transport.

## Authority

The injected controller serializes with the account's other sync operations. It
resolves the winner afresh, requires complete Use without a posting hold, checks
durable move references on the worker, then rereads the selected message exactly.
Any changed chat, id, date, full text, author, ownership or mark format holds. A
current winner, Unknown or inexact read, foreign or unsupported/newer mark holds.
`CheckStaleMarkRemoval` in the shared core is the sole authority immediately before
the single-id delete. The shared request controller has a per-attempt admission
hook, defaulting to its existing behavior for other requests. Mark deletion uses
it to resolve again, worker-check references and reread the exact row before
every retry, under the same captured account capability. Admission is generation
and one-shot fenced, remains inside the original request deadline, and uses a
separate read client so it cannot wait behind its own write stream. No Switch journal is synthesized and the Carry old-mark
cleanup helper is not used.

An active or unreadable Carry journal blocks removal conservatively. Validated
terminal journals and retained history still protect exact mark references and
attempted mark text belonging to skipped cleanup or interrupted abandonment. A
completed, fully validated Done journal does not create a permanent write hold.
Account serialization prevents a new move from starting during admission. No
record deletion, broad history cleanup or channel deletion is granted.

## Absence and uncertainty

D-M2B5-12 applies only to an exact single-id read of the captured stale Own mark
in its captured chat, using a channel-scoped request for channels. The recognized
exact response must contain the same id, and independent current chat information
must establish readability. Under those conditions a peerless MessageEmpty may
establish absence. If the optional peer flag is present, its peer must match the
captured chat. Contradicting or dangling peers, other ids, zero rows, inexact,
flooded, ambiguous or other uncertain reply shapes are Unknown. The consumer
requires the captured location and matching account/chat readability receipt; a
generic MarkGone state is insufficient.

This exception is used only for AlreadyAbsent or removal confirmation after
applied Updates. It never grants deletion authority: that requires a positive
exact Own readback through CheckStaleMarkRemoval. Record lookups and
history/inventory pages remain conservative; peerless record MessageEmpty is
Unknown. This mark-only exception does not broaden cleanup absence rules.

A zero-row reply, search omission, transport error, contradictory peer, edited
ordinary message or inexact page does not prove absence. An already absent mark
sends no deletion. Returned Updates are applied, or affected-message counters are
processed with account/login fencing, before the confirmation reread. An
acknowledgement alone is insufficient. A final unqualified reply holds for an
explicit new action and reread. Shared transient retries keep their existing
schedule and require fresh admission before every actual send.

All callbacks check the action generation and expected phase. Close, cancellation,
login changes or owner departure invalidate callbacks before canceling requests.
Every exit releases its exact account capability, including throwing or reentrant
cleanup. The controller adds no timer or monitor. File/reference validation stays
on the worker; the screen uses captured RAM rows. The native helper and protected
Telegram difference-counter methods are reached through Purple-owned files only.

## Host qualification

`run_sync_chat_marks.py` uses the actual AGP classes and real JNI core with
deterministic synthetic ports, registering both program and driver completion
floors in the canonical host list. It exercises changed winner, equal ids in
different chats, text/date/author edits, Unknown/inexact reads, explicit absence,
active-operation references, cancellation, stale callbacks and throwing/reentrant
cleanup. Native and host tests do not qualify Telegram server behavior, real
Android lifecycle or physical power-loss durability.
