# Waiting before Work Mode changes

The same full-screen countdown and `[peek] start_delay_seconds` cover Peek
starts, restarts and long-press extensions, preset changes, account Work Mode
choices, app-wide source choices that would enter Normal, and delay edits.
The menu and Settings both use the preset picker; account choices use the
Work Mode row under account settings. Android has no preset or Peek hotkey.

The rule is deliberately coarse. Leaving Normal for a restrictive preset,
leaving Stock for Own or Follow, ending Peek, and no-ops are instant. Entering
Normal or Stock, switching presets, and switching Own/Follow wait. **Switching
between two restrictive presets also waits, even when the new one is stricter.**
There is no comparison of lists, folders, stories or notification policies.
The shared delay can be zero. Delay edits wait for the longer of old and new.

`PurplePeekControls.request` is the one UI request path. It captures a write
session before classification, explains a refusal immediately, then starts the
countdown if needed. Completion validates the opening identity, route, file
snapshots, state bytes and gate generation, captures a new write session for
the opening account, and writes once. No permit is added to low-level writers.
Ending Peek also routes through the request, validating that a Peek is active
and stopping it immediately without needing a settings-write session.
`purple/check-wait-routes.py` inventories method spellings across materialized
Java/Kotlin files, including upstream UI: setPreset/startPeek/extendPeek/stopPeek
with any receiver or no receiver, setMode in units mentioning PurpleWriter,
and apply in units mentioning PurpleGlobalAccountChoice. Declarations count
too; unrelated methods in these scopes require inventory review. Comments and
strings are excluded. This syntactic check does not resolve types, aliases,
wrappers or reflection and is not proof that every possible bypass is absent.
`purple/tests/test_wait_routes.py` covers static imports and renamed Choice
receivers. Selecting Normal while the saved preset is missing still writes
Normal; a real-core test invokes the compiled Pending.noOp method.

Back, Abort, closing the screen, backgrounding the owning activity, a screen
lock or app lock, and changes to the captured settings or state cancel the
operation without writing. A Peek already running is untouched by an aborted
restart or extension. A completed restart replaces its deadline then; a
completed extension adds to its current deadline with the existing hour cap.
The existing capped/unlimited long-press behavior (end Peek with feedback) now
happens after the wait. A pending countdown is never persisted.

A Work Mode request stays bound to its opening account even if the selected
account changes. Switching accounts alone does not retarget or cancel it.
Changing the opening account's identity, membership files, role, or settings
context cancels it. Peek starts, extensions and delay edits retain their
existing foreground-fragment and selected-account guards.

Incoming settings sync is exempt: it applies without a countdown or arrival
hold. The initiating device gates its own Work Mode UI. Per-account mode and
the active preset/Peek state are device-local, not synced. Manual sync Apply,
Import, and History Undo/Restore are deliberate settings operations and are
also exempt in this batch. Schedule and Focus transitions remain immediate:
they are automation configured in advance, and editing their configuration is
a settings operation. An exempt apply cancels any pending request whose
captured context changes. No automatic transition opens a wait screen.

Fixed-vocabulary diagnostic events record requested, waiting, instant,
aborted, held, applied and failed outcomes by action and account slot. They
carry no preset names, list names, account keys or settings text.

Host tests run the AGP-compiled `PurpleWaitGate`, account identity capture and
routed mode writer against the host JNI library. They cover the coarse rule,
wait completion, exactly-once consumption, abort paths, an account switch,
context changes and held writes. Device lifecycle delivery and visual layout
are not exercised by host tests. No upstream hook changes are needed.

The device-local [Peek visibility option](peek-focus.md) uses the same request/countdown. Enabling is restrictive and immediate; disabling waits for the resolved delay. Its route captures local policy revision, selection generation and the delay source rather than a synced-file write session. Unlike legacy Work Mode source/preset requests, its fragment and selected-account guard remains strict. Screen-off and both passcode paths cancel pending waits directly and quietly. The route inventory includes the sole local preference writer and rejects direct or moved writes.
