# Modern sync host fixtures

Run after the real AGP `:TMessagesProj:compileStandaloneJavaWithJavac` task and
an affected host JNI link against the repository's pinned core. Capture that
JavaCompile task's destination, compiler, classpath and bootstrapClasspath as
`production-javac-entry.json`; entries in the two classpath arrays have a `path`
field. Use a real Maven org.json jar ahead of Android's API stubs.

```sh
python3 TMessagesProj/jni/purple_jni/tests/run_modern_sync.py \
  --receipt "$receipt" --json "$json_jar" \
  --library "$native_directory/libpurplecore.dylib" --output "$unused_output"
```

The output directory must not exist. It retains compiler/runtime arguments,
input hashes, logs, classes and all synthetic file fixtures. The harness compiles
only fixtures and Android platform ports against actual AGP output. It checks
business-class code origins before every suite. Production parser, routing,
impact, editor, sync store, runner, History and recovery models are not shadowed.
The generated compiler receipt and build output belong outside the repository;
never copy a profile, credential or actual account store into these fixtures.

The default command runs modern flow/recovery, routed/editor/transfer, the
existing injected store state-machine suite, core bridge cases, and History
rules/native fingerprints. `--suite modern`, `routed`, `existing`, `store`,
`core` or `history` selects one group. The injected Core/Clock ports in the
existing store/History unit cases remain fault injection. Their native cases
and the modern executor suite use genuine core replies. Settings parsing and
replacement impact always use the real JNI library.

Modern cases use the same account editor publication model, physical file ports
and native owned-directory/move primitives as runtime. They cover B's first
Join, Apply/import marker/no echo, Publish, uncertain receipts and Finish with
missing or broken primary/good fallback; Restore and Undo; per-account History
limits and operation leases; stale roles, local choices, other loaded files,
parents, login lifetimes and backend switches; divergent-head conflict Share
and the local-over-remote whole-file loss warning. A's settings, good, backups,
markers, store, History, preferences and directory names are byte-checked while
B acts. Simultaneous network waits do not share a busy flag.

The fixture now uses the production preference scanner and actual account
identity-change entry point for logout/slot-reuse tests. No other-account
identities are supplied by the fixture. AndroidUtilities queues the subsequent
UI-only owner reload; native account-cache invalidation still runs. The UI
uses the tested Check.action/requiresChoice presentation decisions for the
disclosed empty Join; no Android widget is driven. Runner cases repair both
state and pending AtomicFile .new/.bak layouts before classification and refuse
unknown or unsafe artifacts.

Recovery cases cover automatic keyed Relink, explicit root cloud proof,
incomplete/wrong-account/negative evidence, both found and unconfirmed pending
posts, duplicate token claims, stale lifetime and preference capture, missing
and reusable setup tokens, invalid binary state, unknown pending bytes, a
failed pending-first archive, exact same-journal resume, modified archive
refusal, an arriving queue entry and account-only Undo clearing. No settings,
good, backup, History or local choice is an archive target.

The `run_sync_executors.sh`, `run_config_sync_bridge.sh`, `run_sync_history.sh`
and `run_account_sync_store.sh` entry points select the matching suite here and
accept the same required flags. Their former accountless compile recipes are
removed. The platform ports here do not qualify Android UI, Looper,
actual Os/AtomicFile, FileObserver, network, devices, emulator, APK or shrinker.
