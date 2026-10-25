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
