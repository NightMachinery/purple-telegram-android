# Confirming a waited action

Every positive effective wait ends at **Ready**. Nothing applies at countdown
end. Tap **Start Peek**, **Extend Peek**, **Switch to …**, or **Apply change**
to apply the captured action. Peek duration starts at that confirmation. The
action is disabled for 500 ms after Ready appears and after it reappears on
unlock. Abort remains usable throughout and stays in the countdown's position;
the ready action sits above it, even in a short window.

Fresh hardware Enter or keypad Enter confirms after the guard. Auto-repeat is
ignored. Other keys abort outside the guard; any countdown key aborts. A phone
needs no keyboard. There is no notification or persistent pending request.
Process death aborts it. Zero effective delay, tightening, no-ops, End Peek,
settings sync and configured automation keep their existing exemptions.

The shared `[peek] ready_timeout_seconds` defaults to 600 seconds and has a
10 second floor. Expiry is measured from the countdown deadline, including
sleep, rather than from the first tick after waking. The Peek option editor
refuses values below 10. Lowering applies immediately, raising waits, and
saving the effective current value does nothing, including an absent 600.

Switching apps or another window taking focus cancels. Screen-off, device lock
and the Telegram passcode can preserve the request. The cover hides while
locked and returns with a new input guard. The controller samples
`PowerManager.isInteractive`, `KeyguardManager.isKeyguardLocked` and Telegram's
passcode state, and receives screen-off/on/user-present broadcasts. A pause
without lock proof is suspended for at most 300 ms. Lock proof must arrive
strictly before that boundary; a later lock cannot rescue an app switch. A
quick switch back also cancels. After established lock clears, the owner has
less than two seconds to return. At that limit, expiry wins over a first relock
sample. During return, an unlocked owner-Activity resume proves that Telegram
came back, even before its window gains focus. A later unlocked pause starts
a fresh 300 ms Away interval; duplicate or late stop callbacks do not extend
it. A relock during return is preserved only after that positive resume proof.
If Telegram stayed stopped, unlock then relock is indistinguishable from an
app switch and cancels.

Unlock proof comes from the current interactive/keyguard/passcode levels, not
from the arrival of USER_PRESENT. The exact owner's resume clears the stale
screen-off latch and resamples those levels. Resume before USER_PRESENT and
USER_PRESENT before resume therefore both work. A resume sampled while still
locked is not proof of an unlocked return. On a real unlock/relock, the wait
survives if the owner's unlocked resume was observed in between; it ends if
Telegram stayed stopped or the return expired. Missing or ambiguous signals
cancel safely.

After a locked cover is revealed, its cached focus can still describe the hidden
window. For at most 300 ms, the resumed owner remains foreground while waiting
for the dialog's first focus callback. Any callback ends that handoff; a focus
loss or unlocked owner pause still follows the ordinary cancellation rules. A
missing callback exhausts the handoff and cannot admit a write under the longer
500 ms input guard. A queued confirmation may reveal Ready but must wait for a
fresh guarded press.

All deadlines use `elapsedRealtime`, which includes deep sleep. Timers only
wake the controller. Every confirmation revalidates the opening account,
login, settings, state, local policy, fragment and guarded writer. A real
settings or state change still invalidates a request during lock, including
an existing active Peek ending under the independent focus-end policy.

Host tests run actual AGP controller/model/cover classes with Android platform
ports and real core JNI. They cover the guard boundaries, button and keys,
lock ordering, screen return, app and window switches, stale callbacks,
timeout, editor classification, core parsing/splicing, and ready placement.
They do not establish real device lifecycle ordering, rendering or accessibility
behavior. Phone qualification remains necessary. No upstream hook changed.
