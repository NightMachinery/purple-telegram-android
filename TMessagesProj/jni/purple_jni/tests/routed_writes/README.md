# Routed writer cases

Compile `PurpleRoutedWriteCases` with actual production Java classes, real JSON
and the changed JNI bridge/core splice unit, then call `run(File)` with a fresh
isolated directory. Use the account-editor host toolchain route. No Android
runtime, account policy or parser stubs are supplied here.

Cases exercise production AccountFiles route validation and the routed/editor
models with real native publication: app-wide writes from a different account,
root and non-root/offline app-wide paths, Premium/schedule/screen-time splices,
first explicit version-2 creation, own/inherited/follow/stock member arrays,
copied comments, second ticks, explicit empties, exact backups, changed roles,
retired contexts and foreign symlinks. Missing root files and missing primaries
with good files remain recovery. UI lifetime and actual offline roster behavior
need Android observations; a host pass does not claim those checks.

Review regressions retain the opening production model across an A-to-B switch.
Matching native rule fingerprints in B do not authorize a stale Delete, and
Peek input Save/countdown completion leave both account files and backups
unchanged. Positive controls still permit Delete and delay edits in A. These
are genuine file/JNI continuation checks; Android dialog dispatch remains
unobserved in the headless run.
