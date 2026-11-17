# Incremental playlist discovery

Discovery walks strictly descending history pages without a fixed page-count cap.
Unrelated messages are discarded after classification. Candidate metadata and
validated hashes occupy space proportional to playlist records, while only one
compressed body is admitted to the merge path at a time. The shared record size
limits still apply to each body; no aggregate compressed-body budget defines a
history limit.

Each owned body is checked against an exact second message observation, passed
through the actual shared step, then merged with the latest account-worker store.
Library and local memo are confirmed through file and parent durability barriers
before discovery advances. Partial checkpoints carry a Problem status and never
ACK, publish or prune. Cancel waits for admitted store work and preserves its
actual write flags. Restart reads the durable memo and resumes by rescanning
metadata, without treating a saved scan offset as server authority.

Final results retain body digests and canonical hashes, not compressed body
arrays. Exact unresolved publication bytes are reclassified before recovery.
Prune proposals use a canonical-equal survivor; every deletion still requires
the existing two fresh bodies, actual shared containment plan, refreshed account
and chat authority, and unchanged message snapshots.

The merged library and compact metadata still scale with library/record count.
This change bounds retained record bodies, not the size of the user's library.
The host regression covers interrupted native checkpoints, exact-byte journal
recovery, more than a thousand history pages and cumulative body budgets. Memo
reuse follows the classifier version exported by shared core: valid hashes remain
reusable across classifier upgrades, while other states are read again after a
version change. Full application and provider verification remain separate gates.
