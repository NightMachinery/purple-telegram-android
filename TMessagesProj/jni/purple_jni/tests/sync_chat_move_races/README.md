# Seeded move crash and race cases

The `sync-chat-move-races` suite uses the production executor, AGP business
classes, real JNI/core and durable file reopen with three independent installs
and a shared synthetic cloud. See the [scope and native boundary](../../../../../docs/sync-chat-move-races.md).

The driver accepts repeatable `--seed` values and `--scenario all`, `matrix` or
`races`. Default seeds exercise every priority 1 crash window and the priority 2
race/control schedules. No production activation or Telegram backend is used.
