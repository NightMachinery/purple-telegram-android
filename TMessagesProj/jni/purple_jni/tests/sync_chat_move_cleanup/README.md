# Carry cleanup host cases

The canonical driver loads the actual AGP business classes and real JNI. Synthetic
ports substitute only transport, foreground and UI scheduling. Local files exercise
the real wrapper, barriers, intents and restart logic. Program and driver closing
counts are registered with floors in `purple/host_suites.json`.

Cases cover frozen all-install settings, unrelated/legacy/later sentinels, Follow
own late posts, independent old marks, exact date/hash/author/chat changes, missing
N coverage, permission loss, crash after deletion, scoped versus peerless/zero
absence, per-attempt renewal, duplicate and stale admission, RAM-only GUI checks,
explicit Retry/Skip, ChatGone and the uncopied-record install-moved note. Inherited
Change cases pin Saved Messages defaults and the older-device warning.

These are host qualifications, with no device, GUI or Telegram requests. Production
constants remain OFF. See `docs/sync-chat-cleanup.md` for scope and partial outcomes.

CLEANUP_REVIEW_CHECKS adds combined Pin/Mute/Archive failure and cleanup holds,
executor intent/result durability faults, and timeout plus backoff during renewal.
It retains the reviewer's conservative absence/generation/restart controls and
checks actionable progress, no implicit decoration retry, and zero extra deletes.

Resume cases run through Change for existing and created chats, require real
CleanupSkipped and released holds, preserve Waiting/Held decorations, and verify
first-start opening checks plus changed journal/state/cache refusal. The original
32-case reviewer schedule remains permanent alongside the expanded controls.
