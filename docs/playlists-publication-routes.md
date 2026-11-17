# Publication journal routes

Changing the selected sync chat or replacing a login preserves the previous
publication intent in the same private native journal. Each route is scoped to
the login hash and peer, within the captured account, slot and environment.
Returning to a route reads its exact original compressed bytes and random id;
an unresolved send is reconciled by fresh owned readback and is never resent.

The version 2 envelope holds up to four original version 1 records with individual
checksums and an envelope checksum. A single native compare-and-replace, file
barrier, directory barrier and exact readback publishes the entire envelope.
There is no interval in which an old intent has moved but the new one is absent.
Unknown, malformed or other-account data refuses writes. A full journal refuses
another route without dropping an unresolved intent. It does not delete remote
records or confer any network authority on archived routes. Single-route files
remain readable and use the original encoding.

Host qualification and application integration are tracked separately. The limit
is a bounded local recovery backlog, not a playlist or server-history limit.
