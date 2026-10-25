# Direct-share host regression

run_direct_share.py takes a real AGP JavaCompile receipt. PurpleDirectShare and PurpleDirectSharePublisher, including their nested classes, must load from its class snapshot. Only fixture cases and dependencies are compiled by the host runner.

The Android/AndroidX ShortcutManager, scheduling, account inventory and Work Mode predicates are synthetic ports. They expose calls, cached versus dynamic entries, limits and queued callbacks. The adapter tests execute the production collection, filtering, routing, reconciliation and generation code. They do not claim production Work Mode parsing, native account-key validation, Android binder semantics or device ranking. The pure publisher cases add deterministic delta, bounded-retention, quota and stale-generation checks. Fixture users and labels are synthetic.

Fix1 adds a synthetic retained-pin store, per-login nonce port and final receiver-helper regressions. The actual LaunchActivity and forwarding receiver hook locations are source-checked; Android Activity dispatch and OEM pin behavior remain device qualification. Raw reviewer RoutingProbe scenarios are adapted to require refusal and common multi-share recipient consumption.

Fix2 executes both actual AGP forwarding receiver classes in an independent classpath with synthetic Activity/Bundle ports and no receiver replacement. ColdStartProbe is adapted to require forwarding before inventory initialization and admission only after it; stale and unbound targets still refuse at the final helper. The reviewer NavigationProbe is retained as58 assertions.
