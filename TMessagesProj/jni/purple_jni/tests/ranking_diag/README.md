# Frequent-contact diagnostics host checks

`run_ranking_diag.py --receipt <real AGP receipt.json> --output <unused directory>`
checks receipt source hashes and runs the production numeric formatter and memory
history, without shadowing either class. Cases cover anonymous fixtures containing
names, handles, phones, IDs and hashes, locale independence, raw order, hidden
slots, unavailable reads, clock/anchor signs, exact increment arithmetic (including
integer overflow and non-finite results), bounded concurrent events and request
tracking. The formatter model accepts only primitives and numeric slot lists.

The runner also replays the exact production SELECT in in-memory SQLite, including
account isolation, retained outgoing counts, empty aggregates, pending messages,
future dates and day boundaries. Future-dated rows satisfy the lower-bound count
windows, matching the query. Source guards check the export route and observational
hooks and reject writers or request calls in the collector. These are formatter,
history and SQL checks, not Android UI or native database integration runs.

Fix 1 adds the review skew and slot-reuse probes as collector regressions. They
run real AGP collector/formatter/history classes with deterministic platform,
queue, DB, Gate and export boundary ports. Cases use a two-day wall skew, hostile
identity fields/exceptions, reuse before enqueue and during/after reads, final
format revalidation, same-user re-login, storage replacement and Clear before
UI capture. The real quiet Gate implementation is checked through source guards;
its native-backed policy is not executed by these ports.
