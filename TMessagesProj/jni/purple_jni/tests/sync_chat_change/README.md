# Change sync chat host cases

`run_sync_chat_change.py` loads actual AGP business classes, checks their code origins, and composes the provision-only setup engine with the real injected Carry executor and durable files. It uses synthetic Telegram replies; no network or device runs.

The cases cover eligibility/reader parity, opening state/cache/winner and foreground consent, cleanup defaults and disclosure, durable child intent, matched uncertain-create recovery, duplicate callbacks, purpose isolation, orphan retention, and safe Carry commit before new-channel decoration. Every suite has program and driver completion floors in `purple/host_suites.json`. Production activation remains a constant false.

Decoration callbacks retain their step while waiting or held. An unsuccessful pin, mute or archive callback remains held across later Carry Cleanup/Done progress. Only an explicit retry of the held step can continue; successful earlier steps are preserved. Closing the flow revokes retries. Process-restart decoration reconciliation remains unqualified, so an uncertain decoration is never automatically repeated after a restart.

`PurpleSyncChatConsecutiveChangeCases` adds one same-install O to N1 to N2 case. It reuses the race harness's synthetic cloud and wire receipts, opens the ordinary store after closing the first Change, and rebuilds the second screen from actual cache/state files and core resolution. Both choices go through `Control.offerChange` / `acceptChange` and the injected production Change controller. It checks a clean new request scope, state/cache commits, exact prior-wrapper archival, operation identities and own-record ledgers. The driver verifies its AGP production source cohort before compiling the cases.
