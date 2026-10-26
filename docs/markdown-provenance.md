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

Full-body copy uses the shared ChooseCopySource authority only after matching the
current displayed original to the retained complete body and revision. Selection
actions never substitute whole stored source. Current-message routing pins login
lifetime, both history routes, owner/body, grouped membership/order, translation
and request generation. A publication lock and a store commit counter revoke
queued source results after archive changes, purge or discard. A source-less
message still uses the existing structural writer.

D-M3B5-A1: complete displayed translations copy their own blocks. A partial
translated display refuses because the endpoint supplies no source revision.
An original full-body response must preserve its observed prefix blocks and
opening message metadata. Unsupported article blocks, lists, cells or inline
constructors refuse the entire action.

Archive history and deleted-message viewers offer Copy as Markdown over the exact
retained version. Copy checks the opening version digest, core source binding,
account/login/selection, store/purge generations and the view through final
clipboard publication. Changing pages, pausing or destroying the view cancels it.
Older/deleted snapshots never request a current Telegram body.

The archive renderer reuses the existing text/entity and rich-block writers. It
marks retained partial or opaque bodies explicitly. Unsupported/opaque structures
and media use a labelled JSON code block preserving every original content field,
including original TL bytes encoded as base64. This is a lossless retained
structure, not an unsupported-block placeholder presented as a complete article.
No media download or local-path URL is introduced.

Host verification compiles changed production Java against an existing AGP class
snapshot, with separate headless fixtures. The reproducible runner is
`purple/tests/run_markdown_host.py`. Supply an existing `--receipt`, real `--json`
JAR, compiled `--core` JNI library, a new scratch `--output` directory and
`--hold-helper` pointing at night_hold. It acquires compiler:local separately for
each javac invocation, checks the 6 GiB floor and never invokes Gradle or cleans
output. The receipt must match the app source base. Runtime ports replace
Android/request/source-lookup boundaries in the route suite; the archive suite
runs the real renderer and publication Guard.

Deleted-view Copy does not open a hidden pagination reader. Its internal pinned
model reads the exact version in a scoped Slice and releases itself in the
worker's finally path, before the result reaches the UI queue. Admission failure
uses a separate finally path; atomic close makes release idempotent. Only the
immutable result and the existing publication guard reach UI delivery. An
ordinary capture, cancellation, parent closure or purge can therefore suppress
that delivery without retaining an internal model or Store/WAL reader.

The host runner also accepts `--archive-host`, `--sqlite` and `--format1` together
to run real encrypted Store cases through the supplied JNI library. That library
must include `archive_source_bridge.inc` and the pinned core; compile the changed
bridge translation unit with `-Wall -Wextra -Werror`. All Java fixture processes
use `-Xcheck:jni`.

The seven reader controls cover delivered copy, intervening committed capture,
cancellation, parent close and committed purge admission, including cancellation
or purge before worker execution. They check reader/model release before UI
delivery and reader counts after suppression. The valid control must publish the
retained Markdown body. Every revoked control must suppress publication.

The dedicated source cases cover exact and wrong account/key, version, revision,
content hash, representation and account/chat purge generations; legacy source
absence; acknowledgement without a new edit/revision/order; and historical-file
Hydration after a newer head. They also check encrypted storage, rollback after
scope revocation during source attachment, both attach/purge orderings, and
logout/discard/selection/scope revocation while source choice is paused inside a
real JNI HMAC callback. The callback delegates real cryptography to the platform
provider and introduces no source or Store substitute. A separate unbound
message supplies each pending attach so duplicate-write refusal cannot mask a
revocation failure. Runtime Store lifetime is enforced by Store authority and
publication guards; persistent purge generations are independently validated by
core. Android resource compilation, Android ABI builds and device clipboard/UI
behavior still require separate qualification.
