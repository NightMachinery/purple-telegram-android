# Markdown source provenance

Source is optional metadata inside the encrypted archive version payload. Existing
archives remain readable and use the structural writer. No old source is inferred.

The ordinary composer freezes each outgoing part before Telegram parses Markdown.
Only unstyled input that changes during parsing and yields entities is eligible.
Pending source is bounded, memory-only, account/lifetime/ingress-bound and expires.
An exact local owner/random identity hands it to the acknowledged server body.
Changed bodies, scheduled and secret sends, captions, web previews and rich-editor
blocks remain source-less. The core SendMetadata operation attaches source without
adding an edit version, causal revision or body order.

Already acquired Markdown document bytes use the existing verified stream or
stable two-pass file path, at most 1 MiB. Coverage, digest, document identity,
opening version and lifetime must agree. A caption or another primary attachment
prevents file bytes from replacing a full message body. Invalid UTF-8 bytes may
be retained exactly but cannot silently become replacement characters on copy.

Source attachment and copy selection use existing core contracts. Purge, discard,
logout and account lifetime changes revoke pending work; version purges own source
removal. There is no separate source database or download for copying.
