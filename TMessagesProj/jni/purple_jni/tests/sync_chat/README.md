# Sync-chat host cases

## Transient request coverage

The discovery suite includes `PurpleRpcRetryCases`, using the production retry owner and real TL request/response classes with injected clocks and transports. It covers short and long floods, premium floods, all 5xx classes, transport and own-timeout backoff, permanent-error controls, deadline boundaries, one stream timer, cancellation and login replacement, duplicate/late replies, observer failure and request resource ownership. Fresh Self full-info and the actual discovery service recover from flood without final UnknownFacts or a cache write.

The modern suite includes automatic-write cooldown controls that preserve the exact active checkpoint and staged bytes and confirm an uncertain send by inventory without reposting. A delayed callback is still transient even after its retryAt has elapsed.

The flood FIX1 regressions separate wall and elapsed clock epochs, continue through a stalled non-RPC cache phase, and keep the original owned/borrowed before-post and retirement gate completion across local-open retries. Forced callers queue. `PurpleSyncManagedReadCases` in the modern suite drives the actual RPC owner and automatic aggregate watchdog through repeated own timeouts, wait/attempt boundaries, cancellation, permanent failure, and a separately bounded uncovered download after metadata retires.

The flood FIX2 discovery regressions port the independent `FloodRecheck` scenarios into `PurpleFloodLifecycleCases`: retired borrowed capabilities cannot strand a forced caller, with the watchdog delivered or overdue, while valid capabilities remain retained. The modern suite covers cancellation of both exact-read phases for readMany, edit, retirement and standalone verification, disable/re-enable on the same client, upload cleanup during verification, once-only retirement, synchronous callbacks, late token registration and unrelated-read watchdog isolation. All use injected endpoints and clocks, with no Telegram calls.
