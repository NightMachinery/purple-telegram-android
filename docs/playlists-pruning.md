# Fresh playlist record pruning

Pruning rereads both the target and its surviving witness from the captured sync
chat, downloads their exact bounded bodies, and asks the shared inventory and
record coverage planner to verify the exact target, witness and canonical hash.
No local containment substitute decides deletion. Rights are renewed, both
metadata snapshots are reread, and rights are renewed again before deletion.
The deletion callback carries the same account, login and chat guard through
the actual Telegram adapter. Only the target message id is deleted.

Each operation has a two minute deadline, cancels only its own read handles,
ignores duplicate or late replies, and never retries a deletion. A timeout,
scope change or close after issuing deletion reports an unknown outcome.
An independent future sync must rediscover and reprove the pair from scratch.
This cannot make the two Telegram messages transactional: an external edit after
the last server read remains a server race. The witness is never deleted by
this operation, and moving the binding grants no authority over the old chat.

The current implementation is a private candidate pending host qualification
and coherent application integration.
