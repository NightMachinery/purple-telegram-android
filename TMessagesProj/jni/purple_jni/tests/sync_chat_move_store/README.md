# Durable carry store host cases

The canonical `sync-chat-move-store` entry uses actual AGP business classes and
fresh host JNI. Existing modern-sync adapters provide Android, account routing
and Looper ports; shared core carry fixtures provide verified records and journal
schedules. Temporary files and FileChannel locks are real. Production origins
are asserted so no store, retention or carry algorithm is shadowed.

The cases pin file CAS, exact 4 MiB snapshots, pre/post-rename failures, file and
directory barriers, strict links/permissions, wrong-thread refusal, immutable base
admission, durable send identities, journal write faults, restart authority,
exact reserved/pending/committed images, consent-preserving cache witnesses,
Superseded/NeedsReview persistence, OS-process and two-thread lock contention,
corrupt/orphan/foreign sidecars, mapping bounds and ordinary writer/recovery fences.
No device, Telegram call or physical power-loss claim is involved. See
[storage behavior](../../../../../docs/sync-chat-move.md).

The modern-sync Looper port models the worker. The list-capture and wait-gate
drivers exclude it when compiling their existing main-thread UI Looper port,
so UI tests keep their actual production thread model.
