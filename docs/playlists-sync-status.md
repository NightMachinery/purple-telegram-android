# Playlist sync outcome and retry state

Sync actor results retain immutable aggregate file-write flags, the final failure
checkpoint, publication issuance and pruning/deletion results. Cancellation closes
network work immediately, then waits for already admitted store actions to settle
before reporting final write truth. A deleted witness is never inferred from an
issued request. Unknown publication or deletion remains unknown and is reconciled
by fresh discovery, never by blind retry.

Transient transport and timeout failures persist Offline. Flood and server waits
persist Wait. The existing shared native local-state checkpoint preserves unknown
members and earliest unsynced age. Status retry_at_ms is a decimal string in the
shared status preserved members, retaining the exact signed-long deadline without
JSON floating-point rounding, including a saturated indefinite server wait. Both
manual and automatic attempts respect a future deadline. A fresh successful step
clears it. No library or message count limit is introduced.

These are private host changes pending application integration and real provider,
lifecycle and stock UI validation. Manual Retry and notices remain pending.

Managed non-short RPC waits are checkpointed before a final RPC reply exists.
The actor cancels its owned transport and settles once; a short managed retry may
recover within its existing budget and does not leave a stale Wait after success.
