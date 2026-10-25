# Archive storage host checks

Run `../run_archive.py` with `--receipt` naming the real
`:TMessagesProj:compileStandaloneJavaWithJavac` entry receipt, `--json` naming a
host org.json jar, `--core` naming the actual Purple core JNI library, `--sqlite`
naming the host build of Telegram's bundled SQLite plus the archive shims and
test fault interposer, and `--output` naming a fresh scratch directory.

The runner compiles platform ports and cases only. Every business archive class
must resolve to the AGP output directory. SQLite is the bundled 3.39.3 engine,
not a platform/system substitute. JNI runs with `-Xcheck:jni`.

The JCA test provider records the real AndroidProvider's requested key policy,
then supplies host AES keys. It does not qualify AndroidKeyStore hardware,
nonexportability, OS backup/transfer execution, or a device filesystem. Platform
ports live in `archive_ports` and the existing `modern_sync` tree.

`format1.sql` is a synthetic, genuine base 9a7fa48 initialization program with
valid authenticated header/key contexts. It proves unchanged old-shape refusal
before key access and explicit removal/recreation. It is never a migration.

Faults exercise actual SQLite state with one-shot prepare/bind/step/reset/
finalize/COMMIT/close failures. The interposer is host-test-only. Cases cover
thread ownership, search-only platform ancestors, filesystem substitution,
transaction acknowledgement, eight
reader snapshots, uncertain close, revision and identity revocation, forced
logout drain, exact account isolation and encrypted media integrity. Restart
seed/recover cases run in separate JVMs and use the production cleanup scanner,
including an unsafe retained account beside another account's pending removal.

Login admission cases exercise the production nonce hooks with an atomic preference fault port. Separate JVMs seed and reopen the real worker/store, retain all files after failed logout writes, then perform a fresh same-user login. They check authenticated orphan erasure, same-login restart, DC/key identity close, malformed/tampered refusal, explicit discard and account B isolation. Android SharedPreferences persistence is verified against its API contract and AOSP backup-rename protocol.

Capture fixtures use the real TL serializer, core planner/envelopes and bundled SQLite. They cover accepted versus observed content, semantic media-reference noise, same-second A-B-A, durable replay/crash cuts, hydration fences, cached and evicted cloud/secret deletion, opaque preimages, queue cancellation, same-ID account isolation, paged snapshots, reader-close faults and saturated-queue logout. No GUI or device behavior is claimed.

Review regressions use separate JVMs for a logout crash before removal intent, then run the production startup scanner. They also check a newer login before queued recovery, B isolation, confirmed discard of malformed/oversized/unreadable login metadata and missing/invalid current nonces, plus queued viewer delivery and Refresh/Older after selection changes.

B3a fixtures use actual AGP staging, lifecycle, acquired-source and inline helpers with the real JNI planner and SQLite. They cover sparse/out-of-order pieces, contradictory overlap, encrypted original bindings, pre-header crash replay, corrupt-cache reconstruction, historical-version completion, canonical ciphertext/deduplication, zero length, original-cause view-once replay, changed head/revision/hash/generation, removal refusal, independent photo qualities, local encrypted-cache keys, producer-buffer preservation, queue pressure and selection revocation. Separate JVMs recover fully covered staging and resume a partial original job without initiating a download.

The FileLoadOperation, MessageObject, FileLoader, queue and storage ports supply immutable producer metadata, scheduled callbacks and cached TL rows. They do not replace archive business logic. The AES-CTR port models Telegram's local decrypt boundary; the filesystem port uses the real host descriptor metadata. JNI and all archive business classes resolve to immutable AGP outputs. No network execution, device callbacks, Keystore hardware or UI behavior is claimed.

B3a review regressions run the unchanged B3aSeamProbe (discard, semantic-origin replacement, non-blocking logout) and TornRecoveryProbe in separate seed/recovery JVMs for both between-pass and during-pass source changes. A verified immutable staging admission still recovers, while partial staging resumes from observed bytes. Logout crash fixtures now retain nested lifecycle and staging artifacts and assert that signed-out startup erases the complete old store while preserving B.

Fix2 adds the unchanged RefusedRetryAdmissionProbe plus mixed-session coverage, terminal reopen refusal, full-coverage-without-validator refusal and a positive fresh coherent stream. Direct staging fixtures now explicitly supply the hash of their known immutable full input.

B3b adds production AGP-class reader/export/provider tests with real core leases and canonical chunk verification. Cases cover historical versions after a newer head, shared blobs, random chunk boundaries and EOF, main/Binder refusal, opaque URI/UID admission, API 26 callback and API 23 sequential-pipe logic, tamper and missing-chunk refusal, zero-byte Save, open/write/close and partial-cleanup failures, late success after a switch, bounded capabilities/streams, app passcode states, discard and a queued-read logout while B stays unchanged. Separate JVMs leave two durable leases, then prove restart releases them without reviving old URI tokens. Platform descriptor, picker, codec and hardware behavior still require device qualification; the pipe port is an in-memory transport, not an OS pipe claim.

Release-pressure coverage blocks the account owner and closes one capability 1000 times. Only one release (plus an already queued validation) may be waiting; the durable lease releases after the owner resumes.

OwnedMediaInvariantCases ports the B3b review probes. It checks truthful closed Save delivery, nine-version history continuation after own lease acquire/release, stale payload/interleaved/other-view transitions, the SQL purge maintenance barrier, failed release followed by committed retry, cached-version mutation and login/slot replacement. The purge case installs the production admission barrier in a fixture transaction; it does not exercise a purge UI.


PurpleArchiveQueryCases uses actual AGP Queries/DeletedModel classes, the production native bridge, bundled SQLite and the existing Android platform ports. It covers140-row pages, both tuple directions and complete date/id ties, negative64-bit secret IDs, parent/alias/topic/collection/representation filters, bodyless and corrupt/missing-head/body-chunk refusal, count status,130 unique blobs over two usage pages, shared-blob deduplication and unsigned total boundaries, same-snapshot schema admission, reader cap/idle release, queued A-to-B-to-A/logout/discard and login replacement, scoped history identity and account B database/WAL isolation. Optional `--suites query-pages query-model` focuses a run without changing business classes. The complete runner still executes every suite by default.

The B3b full-media case prints initial staging elapsed milliseconds, the unchanged40000ms fixture deadline and completion status. This measures the initial retain call including queue wait, validation, encrypted publication and SQL commit, so disk-pressure timeout margins remain visible.


The `boundary-*` cases exercise the actual Store executor boundary for queued and already-shown linked history, including a disposed parent, and verify that ordinary unrelated/same-chat captures and lease transactions keep the original Deleted snapshot. They port the B4a review probes. History's exact own-lease revision tests remain in the owned-media suite.


`staging-perf` uses actual native operation counters and production staging to bound node publications, total publications, fsyncs and full authority reads per piece. It also covers consolidated-cache repair across bounded turns, legacy node-cache resume, each durable-publication cut, source/fence revocation, directory replacement and the final SQL COMMIT fence. The completed-file case counts full-window image writes and retains two-pass validation. Existing torn-source, mixed-provenance, terminal-refusal, stale-generation, logout and discard suites remain in the full runner. Neither the operation assertions nor the original 40 s deadline depend on elapsed-time thresholds added for this optimization.
