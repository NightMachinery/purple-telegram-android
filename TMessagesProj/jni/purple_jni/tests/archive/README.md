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
