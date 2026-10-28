# Android Peek wait

Android increases the wait after each successful Peek or extension. With the
default base of 10 seconds, waits are 10, 20, 30 seconds, up to 100 seconds:
`base + min(step × successful activations, maximum extra)`. The defaults are
10 seconds per activation, 90 seconds maximum extra and a 60 minute reset.
A zero base still gets the extra. Aborted or refused countdowns do not count.
An extension counts only when it actually extends the running Peek.

Settings has a Peek wait group with a local enable switch, step (1 to 300
seconds), maximum extra (0 to 3600 seconds), and reset interval (1 to 1440
minutes). It is enabled by default. The counter continues to track successes
while disabled, so enabling it cannot erase a streak. These options and the
streak live only in `purple_peek_local`, excluded from cloud backup and device
transfer. They are absent from settings.toml and sync. Desktop is unchanged.

The reset starts at the END of the last Peek. A running Peek prevents reset.
Within one boot, elapsedRealtime measures inactivity, including sleep. Across
a reboot, the wall clock is used; a backwards wall clock does not count as
time elapsed. Android boot_count identifies a boot, with the kernel boot ID
as a fallback on older Android. If neither is readable, the streak stays in
place rather than resetting early. A missed natural expiry uses its recorded
deadline. An unreadable streak or failed streak write refuses further waited
operations for that process rather than treating it as an empty streak.

Every gated wait uses this delay, including Work Mode loosening and in-app
Peek option changes. Tightening applies immediately; loosening waits with the
current options. The numeric editor captures its policy and opening account
identity before Save. Selection, login, route, settings, state, policy or
streak changes invalidate a pending request. Aborting keeps the old option.

## Android editor inventory

The core `[peek]` fields are `hotkey`, `auto_off`, `tap`, `hotkey_length`,
`tap_mobile`, `end_on_screen_lock_p`, `end_on_app_lock_p`,
`end_on_app_lock_mobile_p`, `exit_hidden_chats_p`, `start_delay_seconds`, and `ready_timeout_seconds`.
Android exposes persistent in-app editors for start delay, ready timeout and exit-hidden.
All enter `PurplePeekControls.request`; their writes occur only in pending
completion through guarded PurpleWriter methods. The duration row opens the
Peek start picker, it does not edit a stored duration. Starting and extending
through it use the same escalated wait. There are no in-app writers for the
other core fields or a mobile hotkey. Future duration/lock editors must enter
this controller and classify shorter lengths and lock-on as tightening.

Local focus-end and the four escalation options also enter that request. Their
sole policy writers are admitted only at pending completion. The streak owner
has a separate bounded local writer; it cannot edit policy. The source guard
and negative fixtures cover all these writer spellings and direct Peek table
edits. Manual whole-file import, sync Apply, History Undo/Restore, external
edits and incoming sync remain exempt, and cancel any now-stale pending wait.

Host tests execute AGP controller, local policy and streak classes with explicit
Android, clock, account and I/O ports. The pure clock cases test exact reset
boundaries and reboot/backwards-clock behavior. The controller cases test
success/abort/extension counting, Work Mode parity and option classification.
The existing real-JNI visibility withdrawal, focus, wait and share regressions
remain part of qualification. The ready timeout is exposed by the existing native bridge; no upstream hook changes are needed.

## Crash and clock boundaries

Before writing a Peek start or successful extension, Android durably reserves
its increment. A failed reservation refuses the Peek before it can reveal or
extend anything. A confirmed refused state write can refund that reservation;
a crash between reservation and state write can over-count, and cannot
under-count. A blank streak is durably initialized before the first request.
Out-of-bounds stored options are malformed, are reported, and read as the
protective defaults (10 seconds, 90 seconds, 60 minutes), never permissive clamps.

An expiry missed while the device was off keeps its original wall-time END and
cross-boot age. Once that age fits the new boot's uptime, it becomes a monotonic
anchor. A backwards wall clock remains protective. Across a reboot E2 trusts
positive wall-time age: advancing the wall clock and rebooting can shorten the
reset interval. This is an accepted clock limit, not trusted-clock protection.

After a reboot, an early End uses its observed end time, bounded above by the scheduled deadline. The reset begins at that end, including focus endings and abandoned reservations. Expiry while powered off keeps the scheduled end and its cross-boot wall age.

Every positive effective delay ends at [Ready](peek-ready.md), requiring a fresh
confirmation before the action applies or a successful activation is counted.
