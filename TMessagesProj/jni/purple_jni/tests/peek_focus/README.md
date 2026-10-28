# Peek focus and ready-step host tests

`run_wait_gate.py` also runs `run_peek_focus.py` on real AGP classes. Production
controller, wait/presence/layout models, cover, foreground owner, focus model,
local policy, ForegroundDetector, ScreenReceiver and core origins are asserted.
Android/UI, clock, preferences, IO and account/settings routing are explicit
synthetic ports. The original wait suite retains real routed-writer/account/JNI
coverage. Fixtures are synthetic.

Dialog hide/show enqueue focus callbacks through the synthetic UI queue. The
queued-focus cases deliver the returning callback before and after a queued
confirmation, reproduce the reviewer's genuine-unlock schedule, and check owner
pause, another window and a missing callback during the bounded focus handoff.

The ready cases cover late initial presentation at countdown expiry and after
ready timeout, both reviewer return/departure and overdue-relock regressions,
unlock/relock with and without a positive owner resume, both resume/user-present
orders, duplicate stop callbacks during a fresh Away interval, countdown without apply, fresh button and Enter/keypad
Enter, repeat and 499/500 ms input guard, Abort, stale timer/action/Abort,
throwing/reentrant cleanup, End Peek, locks and missed screen-on, app/window switches, quick return,
300 ms late-lock rejection, timeout, settings revocation and timeout-editor
classification. Pure cases additionally cover lock before pause, hours of
sleep, the two second unlock-return boundary, unknown signals, geometry and
real core timeout parsing/splicing. Existing escalation and persistence cases
explicitly confirm after their wait and guard rather than relying on automatic
completion. Notification ports deliver only the subscribed event ID.

These tests establish neither real OS delivery ordering nor rendered layout
or accessibility. The owner checks those on the phone; see
[Ready behavior](../../../../../docs/peek-ready.md).
