# Change sync chat host cases

`run_sync_chat_change.py` loads actual AGP business classes, checks their code origins, and composes the provision-only setup engine with the real injected Carry executor and durable files. It uses synthetic Telegram replies; no network or device runs.

The cases cover eligibility/reader parity, opening state/cache/winner and foreground consent, cleanup defaults and disclosure, durable child intent, matched uncertain-create recovery, duplicate callbacks, purpose isolation, orphan retention, and safe Carry commit before new-channel decoration. Every suite has program and driver completion floors in `purple/host_suites.json`. Production activation remains a constant false.

Decoration callbacks retain their step while waiting or held. An unsuccessful pin, mute or archive callback remains held across later Carry Cleanup/Done progress. Only an explicit retry of the held step can continue; successful earlier steps are preserved. Closing the flow revokes retries. Process-restart decoration reconciliation remains unqualified, so an uncertain decoration is never automatically repeated after a restart.
