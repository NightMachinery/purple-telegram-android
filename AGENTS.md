# Agent Guide for Purple Telegram Android

## External test tooling

Private build-machine notes live in the private purple-build repo; when it is cloned at ~/.purple-build, see ~/.purple-build/readme.md.

## Build scheduling

Full Purple Telegram desktop and Android application builds may run whenever
the user explicitly authorizes the build and grants access to the machine.
Night scheduling is an option when the user requests it, not a standing time
restriction. Do not leave an active agent waiting for a requested window to
open. Non-building static checks may run at any time.

## Keeping upstream merges easy

Upstream Telegram Android is merged into this fork again and again, and every
line Purple changes in an upstream file is a conflict waiting in some future
merge. So Purple code lives in Purple's own files: the
`org.telegram.messenger.purple` package, `TMessagesProj/jni/purple_jni/`, or
the purple-core submodule at `TMessagesProj/jni/purple` when desktop needs the
same logic.

- Touch an upstream file only to add a hook: one import, one guarded call into
  a Purple class, one early return, or one menu row that delegates to Purple
  code. Put the decision and the work behind that call, in a Purple file.
- Do not copy, move or restructure upstream code to make room for Purple
  logic, and do not reformat upstream lines you pass through. If there is no
  place for a hook, add the smallest extension point that makes one.
- When a feature seems to need a large edit inside an upstream file (for
  example `ChatActivity`, `MediaController` or `DialogsActivity`), look for a
  smaller hook first. If the edit is still needed, say in the README section
  for that feature which upstream files it touches and why.
- Review your own diff of upstream files before committing; each hunk should
  read as a hook, not as feature code.

Where Purple code goes:

- Logic goes in `org.telegram.messenger.purple`, in new small classes named
  after their feature, not in `PurpleGate`. The `PurpleGate` methods that
  hooks already call stay where they are.
- A helper that needs package-private members of an upstream UI class goes in
  `org.telegram.ui` with the `Purple` prefix, for example one helper instance
  per upstream screen.
- Build logic goes under the repository's `purple/` directory, new Gradle
  scripts in `purple/gradle/`, each applied by one `apply from:` line. Native
  build logic goes in `TMessagesProj/jni/purple_jni/`.
- New Purple strings go in `TMessagesProj/src/main/res/values/purple_strings.xml`.
  Default app names belong to the standalone app resource overlay.

Keep explanations for retained upstream hooks in the feature README section
or `docs/upstream-merge.md`, so the upstream edit stays limited to its hook.

How a hook looks:

- It carries at most one comment line, `// Purple: see PurpleX#method`. The
  rationale goes in that method's Javadoc, or in the feature's README section
  when it is about which upstream files the feature touches and why.
- Every Purple menu, row or button id lives in the Purple class that handles
  it and takes a value from the reserved range `0x70750000 + n`, clear of
  upstream's sequential ids.
- A UI file that has a per-instance Purple helper routes every hook through
  that instance and imports nothing. Elsewhere, a file with one or two Purple
  call sites uses fully qualified names, and a file that calls a Purple class
  many times imports it once.
- A Work Mode hook (list membership, folder filtering, notification and
  unread filtering, chat-list hiding, mute, temporary rows) takes the account
  it acts for, never a global. If the call site has no account in hand, say
  so in the commit instead of inventing one.

Before committing a change to an upstream file, run
`purple/upstream-hooks.sh check` (mark new files with `git add -N` first). If
the change shrinks or grows an edit, run `purple/upstream-hooks.sh baseline`
and commit `docs/upstream-hooks.baseline` with it. `docs/upstream-merge.md`
explains the check and is the checklist for merging an upstream release.

New work follows this from the start. Older Purple code that still sits inside
upstream files is being moved out.

## Running every host suite

Before delivering a patch, run the host suites on the exact base you deliver
against, and again after any rebase:

```bash
python3 purple/run_host_suites.py --output-dir <dir outside the tree> \
    --java-home <JDK 17> --qt-frameworks <host Qt frameworks> \
    --compile-wrapper '<hold wrapper>' --build-wrapper '<heavy hold wrapper>' \
    --core-repo <purple-core clone> \
    --submodule-repo TMessagesProj/lib/jlatexmath=<clone or bare mirror>
```

`purple/host_suites.json` is the canonical list. `agp-compile` and
`agp-compile-standalone` run the real `:TMessagesProj:compileDebugJavaWithJavac`
and `:TMessagesProj:compileStandaloneJavaWithJavac` offline through
`--init-script purple/gradle/host-receipt.gradle`, which records the javac,
class output and class paths and refuses any native, assemble, sign or install
task. `host-library` builds `libpurplecore` for the host from the sources
`purple_jni/purple.cmake` lists. A suite names what it tests by placeholder:
`{receipt}` or `{standalone-receipt}` for the variant its driver expects,
`{library}`, and `{out:<name>}` for an earlier suite's output. Shell drivers run
under `bash`; one that compiles runs whole inside one `--compile-wrapper` call.
A patch that adds or removes a driver anywhere under
`TMessagesProj/jni/purple_jni/tests/` or `purple/tests/`, or a `--suite` value
of one, updates the list in the same commit. A Python driver declares its
`--suite` choices as a literal list, which the runner reads from its syntax
tree; computed choices are refused, as are empty `phases` and `--help`
arguments. A shell script that only execs one `--suite` of a listed driver is
recorded under `aliases`, and the runner checks that it calls nothing else; a
driver the runner does not drive yet is recorded under `undriven` with the
reason. The runner refuses to start while the list and the drivers disagree,
and `--list` runs only that check.

`./gradlew` never runs, because its bootstrap could download. The runner
starts `--gradle`, or the launcher the wrapper already unpacked for its exact
`distributionUrl` (the folder the wrapper derives from that URL, with its `.ok`
marker), and refuses unless `--version` reports the wrapper's version. Nothing
is downloaded, and no daemon is stopped or reused (`--no-daemon`). The compile
runs with `--no-build-cache` and `--rerun`, and its receipt must show the task
executed and did work (not skipped, up to date or restored), so the classes the
suites load were compiled in this run. A tree without `local.properties` needs
`--qt-android`: the runner creates the file exclusively with that one key, never
through a link, and afterwards removes only the file it created. It refuses a
`local.properties` symbolic link and never reads an existing file.

Every populated submodule is built from, so each must match its gitlink. The
runner hashes its working files like a git tree and compares them with the tree
the gitlink names, found in the submodule's own `.git` or in the repository
`--core-repo` or `--submodule-repo PATH=REPO` gives (read offline; a bare
mirror will do). It does this before and after the run.

`--compile-wrapper` and `--build-wrapper` are commands the runner puts in front
of each compile and each Gradle build. A hold wrapper takes the hold with its
own holder id and its own pid as the liveness pid, runs the command, returns its
exit status, and releases the hold on every exit; if the wrapper is killed, the
dead pid frees the hold. A step that times out, or a run that is interrupted, is
sent SIGTERM as a process group, and after a short grace whatever is left of the
group is killed.

`results.json` lists every configuration's commands, exit code, duration, log
and the check counts its logs report, with the tree and submodule hashes before
and after. A driven suite lists under `expects` every completion record its
programs and phases print (`<log>:<KEY>`, read from each program's log in the
suite's output folder and from the driver's own output), each with a floor: a
count it must reach, or `"PASS"`. A suite passes only when this run's fresh logs
hold every expected record at its floor and report no failure (`N checks, M
failures` with M above zero, or `KEY=FAIL`). A program that prints no count is
held to its own closing output: `<log>:LINE <text>` needs a line starting with
that text in this run's log of that program, so a skipped or silent program
fails the suite; a Python driver given a receipt
must also have made a compiler call of its own. A patch that adds a program, a
phase or a counter updates the suite's `expects`, and the floors sit a little
below the counts of a clean run, so that a suite that silently loses most of a
program's checks fails. The self-check refuses a driven suite without one. The summary is `N/N PASS`, marked
`PARTIAL` for a selection, `NOT CLEAN` when the tree or a submodule changed
during the run, and `UNVERIFIED` when a populated submodule does not match its
gitlink or cannot be checked. The exit status is 0 for a clean full pass, 3 for
a clean partial one, and 1 otherwise.

## Release handoff

Private build-machine notes live in the private purple-build repo; when it is cloned at ~/.purple-build, see ~/.purple-build/readme.md.
