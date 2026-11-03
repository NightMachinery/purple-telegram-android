# Review: F11, crash capture into the diagnostic log (Android)

Scope: `d111547a..crash-capture` (one commit, `2f7217a6 Capture crashes into the diagnostic log (F11)`), against
`docs/media-recording-design.md` section F11 and the owner's answer 3 = A (relative pc for the app's own libraries
plus the build id once per crash). Review only: nothing on `crash-capture` was changed.

What was run here (this VM cannot build the app):

- `purple/upstream-hooks.sh check`: OK. `purple/run_host_suites.py --list`: the list and the drivers agree.
- The pure classes (`PurpleDiagLogStore`, `PurpleDiagRepeats`, `PurpleCrashRecord`, `PurpleCrashTombstone`) and
  `PurpleCrashCaptureCases` compiled with plain javac 21 (not the AGP receipt): `CRASH_CAPTURE_CHECKS=21199`, pass.
- Small probes against the same classes for the findings below (outputs quoted where used).

Verdict: **CHANGES**. P1 = 2, P2 = 3, P3 = 7.

The design is followed closely and most of the scrubbing is sound: exception and abort messages never reach a line,
thread names are never read, paths are cut to an app library's base name or `system`, the pending file only accepts
printable `crash ` bodies, the tombstone reader is bounds-checked and survives every truncation, and the hook is one
fully qualified line after `super.onCreate()` with the baseline updated. The two P1s are a start-up crash in debug
builds caused by touching `FileLog` too early from the collector thread, and native function names that carry class
names outside the kept packages.

## P1

### P1-1. The collector thread calls `FileLog` before upstream has initialised `AndroidUtilities`; debug builds can crash at launch

`PurpleCrashCapture.start` (`PurpleCrashCapture.java:77-80`) starts the `purple-crash` thread right after
`super.onCreate()`, before `ApplicationLoader.onCreate` reaches `AndroidUtilities.getHelloWorld()`, whose upstream
comment reads "AndroidUtilities must be initialized before FileLog". The collector ends in
`PurpleDiagLog.appendNow` (`PurpleDiagLog.java:149-158`), which calls `FileLog.e("Purple: " + line)` for every line.
On API 30 and later there is almost always at least one new exit reason (every previous process ended somehow), so
this runs on nearly every cold start.

`FileLog.init()` is not thread-safe and, when `BuildConfig.DEBUG_PRIVATE_VERSION` is true (the `debug` build type
and two others in `TMessagesProj/build.gradle:155-210`), throws on a second entry:

Trace (a), main thread already inside `AndroidUtilities.<clinit>`:

1. Main: `getHelloWorld()` starts `AndroidUtilities.<clinit>` (big `Pattern.compile`s, then `checkDisplaySize`).
2. Collector: `appendNow` → `FileLog.e` → `LOGS_ENABLED` (true: `DEBUG_VERSION`) → `ensureInitied` →
   `getInstance()` assigns `Instance`, calls `init()`, sets `initiing = true`, then `AndroidUtilities.getLogsDir()`
   blocks on the class initialisation main holds.
3. Main, in `checkDisplaySize`: `FileLog.e("density = ...")` → `getInstance()` returns the instance (already
   assigned) → `init()` sees `initiing` and throws `IllegalStateException("double init call")`.
4. `checkDisplaySize`'s `catch (Exception e) { FileLog.e(e); }` calls `init()` again, which throws again out of the
   catch, out of the static block: `ExceptionInInitializerError` on the main thread in `Application.onCreate`. The
   app dies at launch.

Trace (b), collector first: the collector itself triggers `AndroidUtilities.<clinit>` from `getLogsDir()`; its
`checkDisplaySize` re-enters `FileLog.init()` on the same thread and throws the same way; `appendNow` swallows the
`ExceptionInInitializerError`, but `AndroidUtilities` is now permanently failed, and main's `getHelloWorld()` throws
`NoClassDefFoundError`.

Both need the collector to reach `FileLog` while main is before or inside `AndroidUtilities.<clinit>`, so it is a
race, but the window is real (the collector does one binder call, a prefs load and a small file read; main compiles
large regexes and queries the display). Because the cursor is only advanced after the write, the next start retries
the same lines into the same race. In builds with `DEBUG_PRIVATE_VERSION` false but logs on, the same race does not
crash but runs `FileLog.init()` twice: both open the same second-stamped log file with a truncating
`FileOutputStream`, a second `logQueue` thread is leaked, and the start of upstream's log is lost.

Fix: do not mirror crash lines to `FileLog` from `appendNow` (the diag log is the destination; upstream's own
handler already writes Java crashes to `FileLog`), or start the collector only after `ApplicationLoader.onCreate`
has initialised `AndroidUtilities` (for example post it to the main looper from `start`, which keeps the handler
install early). Either way, add a sentence on the ordering to `start`'s Javadoc.

### P1-2. Native function names bypass the package filter, so class names outside the kept packages reach the log

The design and the README promise "a class outside `org.telegram`, `android`, `androidx`, `java`, `javax`,
`kotlin` and `com.google` is `other`". That holds for Java frames (`PurpleDiagLogStore.frame` → `codeClass`), but
`nativeFrame` keeps any function name that passes `codeFunction` (`PurpleDiagLogStore.java`, `codeFunction`), which
allows dots. In a tombstone (and in an ANR's `native:` lines) frames in ART's compiled or JIT code carry the Java
method as the function name, so any third-party class name is written verbatim:

```
nativeFrame("/data/app/~~x/com.foo/oat/arm64/base.odex", "com.vendor.secret.Tracker.upload+40", 0x10)
  -> crash frame lib=system fn=com.vendor.secret.Tracker.upload
nativeFrame("[anon:dalvik-jit-code-cache]", "org.chromium.SomeClass.method", 0x10)
  -> crash frame lib=system fn=org.chromium.SomeClass.method
```

These are code names, not user data, so the real-world sensitivity is low, but the brief lists class names outside
the kept packages as a leak, and the exported log contradicts the header's and the README's privacy statement.

Fix: in `codeFunction`, when the name contains a `.` (a Java method), run the part before the last dot through
`codeClass` and the last part through `codeMethod`, giving `other` for a foreign class exactly as Java frames do;
keep `::` C++ names as they are. Add tombstone and ANR fixtures with an `base.odex` frame and a
`[anon:dalvik-jit-code-cache]` frame naming a foreign class; `clean()` in the cases cannot catch this today because
no fixture has such a frame.

## P2

### P2-1. A crash during the collector's run can delete its own pending record

`collect` reads the pending file (`readPending`), writes the lines, then `pending.delete()`
(`PurpleCrashCapture.java:107-114`). If any thread throws while the collector is between the read and the delete,
the handler appends the new record to the same file with an fsync, then runs the previous handlers (upstream's
`FileLog.fatal`, then the system's `KillApplicationHandler`, whose binder call to `ActivityManager` takes a while)
before the process dies. The collector keeps running during that time and deletes the file, new record included.
The next start then has only an unmatched `crash exit reason=crash` line, without frames. This is most likely in a
start-up crash loop, which is the case the feature exists for.

Fix: take the file before reading it: `renameTo(pending-crash.taken)`, read and log the taken file, delete it after
the write. The handler keeps appending to a fresh `pending-crash.log`. On start, also pick up a leftover `.taken`
from a run that died mid-collect.

### P2-2. A failed write still deletes the pending file and advances the cursor (storage full)

`PurpleDiagLogStore.append` returns false on any failure (full storage, unwritable directory, failed rotation), and
`appendNow` ignores the result. `collect` then deletes the pending file and commits `LAST_EXIT`, so with storage
full every crash record of that start is lost for good, though the pending file and the exit history still had it.

Fix: have `appendNow` return whether every line was appended (or the store returns the count), and only delete the
pending file and advance the cursor when it did. The pending file's 64 KB cap already bounds the retry.

### P2-3. Up to 16 traces of up to 4 MB are held in memory at once at start-up

`exit()` reads each ANR or native trace whole, up to `TRACE_LIMIT` (4 MB, plus one more 16 KB buffer), into a
`ByteArrayOutputStream` (whose growth and `toByteArray()` copy double the peak), and `collect` keeps every `Exit`,
bytes included, in a list until `plan` runs. On the first start with this feature (`LAST_EXIT` is 0) all 16
historical exits are read; on a device with a run of ANRs that is up to about 64 MB retained plus the copies, on a
background thread but in the same heap as the app's own start-up. An `OutOfMemoryError` there is caught on the
collector, but it can just as well land on the main thread's allocation instead. ANR text is also decoded and
`split` whole although only the main thread's block (printed first in ART's dump) is used.

Fix: build each record inside `exit()` and keep only its lines (drop the bytes before the next exit is read); for
ANRs, stream lines and stop at the end of the `"main"` block; for tombstones, a lower cap (for example 1 MB) is
enough since `threads` (field 16) precedes memory maps, logs and fds. Optionally read traces only for the newest few
exits on the first start.

## P3

### P3-1. Host suite floor does not guard the meaningful cases

`crash:CRASH_CAPTURE_CHECKS` has a floor of 20000 against 21199 on a clean run. About 6300 checks come from the
signal-word sweep (42 x 150) and roughly 13000 from the truncation loop over the 2446-byte synthetic tombstone. The
cases that test the actual scrubbing and merge (`frames`, `javaCrash`, `anrs`, `pending`, `merge`, together a few
hundred checks) could all disappear and the suite would still pass. AGENTS.md wants floors that fail when a program
silently loses most of its checks. Print one counter per section (for example `CRASH_FRAME_CHECKS`,
`CRASH_ANR_CHECKS`, `CRASH_MERGE_CHECKS`) and give each its own floor in `expects`.

`PurpleCrashCapture` itself (the handler chain, the cursor, the read-log-delete order) has no host coverage. Moving
the cursor and file handling into an Android-free helper would let the cases cover P2-1 and P2-2.

### P3-2. `collect` is not atomic across a crash

The lines are appended, then the pending file is deleted, then the cursor is committed. A process death between
those steps (again most likely in a start-up crash loop) logs the same records again on the next start. Committing
the cursor and taking the pending file (P2-1) before writing, and accepting a lost record over a duplicate, or the
reverse, should be a stated choice.

### P3-3. Both caps keep the oldest records

`plan` sorts oldest first and the limiter writes the first five records; `writePending` refuses new records once the
file is at 64 KB. In a crash loop the owner sees the first crashes and a count, not the latest ones, which are
usually the more relevant after a fix attempt. Consider keeping the newest five, and letting the pending file drop
its oldest records.

### P3-4. The 10 s match between a Java crash and its `REASON_CRASH` exit can miss

The pending record is stamped when the handler starts; the exit's timestamp is when the system recorded the death,
after the previous handlers and the system's crash processing (and, when the crash dialog is shown, possibly after
it). A miss gives a separate `crash exit` line plus the frames record, which is harmless but splits one crash in two.
Matching the nearest pending record before the exit (no upper bound beyond the previous exit) would be more robust.

### P3-5. Parsing details

- An ANR `native:` line for JIT code, `... /memfd:jit-cache (deleted) (offset 0x2000000) (com.vendor.Tracker.track+164)`,
  gives `fn=deleted` and loses the real function (probe output: `crash frame n=1 lib=system fn=deleted`). Skip a
  `(deleted)` group.
- When the crashing thread's id is missing or its thread entry was cut off, `PurpleCrashTombstone.parse` falls back
  to the first thread, which is then presented as the crashing thread. Mark such a record (for example
  `thread=fallback`) or log no frames.
- A native crash without a tombstone (API 30, or a failed read) prints `abort=none`, which reads as "no abort
  message" when it is really unknown. Leave the field out when there is no tombstone.

### P3-6. Release Java frames need the build's R8 mapping

The standalone and release builds are minified (`TMessagesProj_AppStandalone/build.gradle:90`), the keep rules keep
only `org.telegram.messenger.*` one level deep, and no `-keepattributes LineNumberTable` is set. Most Java frames in
an owner's real crash will therefore name R8-renamed classes and methods (still under `org.telegram`, so kept), whose
lines only make sense with that build's `mapping.txt`. The native side was designed for this (build id plus
unstripped libraries); the Java side needs the same: archive `mapping.txt` with each release next to the unstripped
`.so` files, and note it in the README section.

### P3-7. The handler loads its classes lazily, in the dying process

`PurpleCrashRecord` and `PurpleDiagLogStore` (with its large vocabulary set) are first loaded inside the uncaught
handler when nothing else touched them yet. Under an `OutOfMemoryError` that static initialisation can fail; the
`catch (Throwable)` keeps the chain intact, so nothing worse than a lost record happens. Touching both classes in
`start` (or from the collector) would make the handler's path allocation-light.

## Checked and fine

- Handler: never throws (everything inside `try/catch Throwable`), always calls the previous handler, falls back to
  `killProcess` plus `System.exit` when there is none. `BuildVars`' handler is installed later (first touch of
  `BuildVars` right after the hook) and chains to Purple's, so both run. No locks are taken on the crash path; the
  fsync is the only blocking call.
- API gating: `getHistoricalProcessExitReasons` only on 30+, trace reading for native crashes only on 31+, ANR traces
  on 30+. Reason codes 0 to 16 match the platform constants.
- Tombstone field numbers match AOSP `tombstone.proto` (tid 6, signal_info 10 with number 1 and code 3,
  abort_message 14, causes 15 with memory_error 2, threads 16 as a map with key 1 and value 2, BacktraceFrame rel_pc
  1, function_name 4, file_name 6, build_id 8). Negative signal codes (10-byte varints) parse.
- Privacy otherwise: exception and abort messages, thread names, command lines, registers, memory, log buffers and
  open files are never copied; ANR lock lines and other threads are skipped; app library paths are cut after the last
  `/` or `!`; build ids must be hex; every line passes the vocabulary or identifier checks and the line limit.
- Main-thread cost of `start`: one `getFilesDir`, one thread start.
- Upstream hook: one line, fully qualified, no comment, pinned by `run_recording.py`; `docs/upstream-hooks.baseline`
  updated; `upstream-hooks.sh check` passes.

REVIEW F11 android: CHANGES P1=2 P2=3 P3=7
