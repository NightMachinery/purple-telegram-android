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
