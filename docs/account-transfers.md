# Account-specific legacy settings transfers

Send to Saved Messages, Share and Import from file use the settings screen’s
account. A Saved Messages document imports into the message’s account, including
when another account is selected. External-file entry captures the selected
account once. The confirmation names that account and the role its file has on
this device. An unresolved owner keeps transfers held.

Whole files retain the document name `settings.toml`. Schema-2 layered files use
`account-settings.toml`, avoiding shipped filename-based offers; shipped schema-1
sync readers refuse their version. This build accepts both names. Missing version
still means schema 1. An absent account file cannot be sent. Launch offers stay
active-account-only through download and confirmation; freshness uses that
account’s own primary mtime, or the global primary mtime when it has no own file.

The shared core’s AnalyzeConfigChange inspects exact proposed bytes against the
opening primary, role, global file, device-local Work Mode and all loaded account
files. Every non-Ready result blocks replacement. Missing primaries with good
copies and invalid primaries require recovery rather than an inferred fallback.
Global list removal includes loaded accounts’ stranded own members. Mixed tables
are grouped by each key’s scope. Follow and Stock members are shown as inactive.
Their pin and unpin warnings describe stored account values without claiming a
local following change.
App-wide losses, removed lists/presets/schedule entries and defined-list emptying
are explicit risks, even when inactive on this device. Global targets lead with
Work Mode off or local list-emptying consequences. Cancel receives focus; no
replacement is preselected. The same analysis guards editor Save and the import
confirmation reached from a launch offer.

Imports reuse guarded editor publication, retaining account lifetime, roster,
role, local choices, primary/good/backup bytes and directory identity through the
confirmation. The account’s `.import.bak` and `.bak` hold the previous primary.
The exact incoming bytes are installed without serialization. A partial backup
or publication remains a reported failure rather than a claimed transaction.
No automatic last-good recovery is introduced. An account-file refresh reuses
the accepted global resolution while the global role and primary/good inputs
remain unchanged. It rebuilds account readers and lists without serializing
device state. The watcher uses the same path; a changed global source falls
back to the normal full reload.

Send fingerprints stay in root `state.toml` for its immutable owning account;
other accounts use their own `send-state.toml`. Fingerprint updates splice only
the named scalar, preserving root state markers and unknown bytes. Each account
has a separate five-second save debounce. A qualifying save carries the actual
file owner’s identity and exact bytes, independent of the selected account.
Only that file’s own `[sync] send_after_save_p` counts. Creation, import, History
restore and signed-out writes never start sends. An import records its own
fingerprint and cancels only its account’s pending save. Confirmed-send and retry
receipts retain their originating identity, including environment and lifetime.
A logout, role/local-choice change or changed file cancels a retained admission.
A confirmed upload advances its account’s offered-message watermark even when a
later save or marker I/O failure prevents publishing its sent fingerprint. Only
a still-current originating identity may advance either record.

Modern schema-2 Apply, History/Undo and binding recovery now use the same
replacement impact contract. See [account-modern-sync.md](account-modern-sync.md)
for their separate explicit-account pipeline and staged Finish rules.

Headless regressions in `purple_jni/tests/transfers` run the production models,
shared JNI and isolated files, along with routed/editor, offer, auto-send and
staging cases. They verify exact A-file isolation during B import, both document
names, raw newer-schema refusal, omitted version 1, per-key scope, recovery
holds, stale confirmations, fingerprint preservation and independent account
countdowns. Actual Android lifecycle, filesystem and network delivery still
need separate device checks; this batch uses no device, emulator or GUI.
