# Carry JNI host suite

Run `run_sync_chat_move.py --receipt AGP_RECEIPT --json OFFLINE_JSON_JAR
--library HOST_PURPLECORE --output UNUSED_DIRECTORY` from the tests directory,
under the same compiler wrapper as the other host drivers. The host library
must include sync_chat_move_bridge.cpp from purple.cmake. The driver obtains
macOS Qt compiler paths from that library's actual LC_RPATH, or uses the local
Qt6Core pkg-config metadata on other hosts. It downloads nothing.

The fixture generator includes the pinned shared core's carry test harness and
runs its deterministic sections (excluding the seeded fleet simulation), and records Begin, Advance, Resume, NextAction, Plan, PrepareCommit and exact
record/old-mark deletion calls. Original core assertions still run. Each unique
request is replayed through the actual AGP Java facade and host JNI, comparing
status, exact canonical/prepared bytes, actions and deletion verdicts with the
direct core result. The test requires every journal action and status, including
nonempty canonical Wait/Retry/NeedsReview/Superseded results.

Additional Java cases check duplicate JSON in both wrapper and journal, wrong
account and typed peer scope, unsafe/numeric counters, maximum safe revision,
full-width request identities, newer journals, unknown plan/journal members,
retained-base state admission, invalid base64/UTF-8 and oversize refusal. The
business-class origin is checked against the real AGP output before replay.

These are pure host tests. They do not perform file-store recovery, Telegram
calls, device work or UI actions. Storage/executor contracts and native transport
qualification belong to later slices. See docs/sync-chat-move.md for the wire
contract and retention boundary.
