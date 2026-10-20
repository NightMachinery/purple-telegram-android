# Peek controls and selection Markdown qualification

These cases are prepared for the next qualified application. Source inspection
and the carried formatter results do not establish production Java, JNI, R8 or
native behavior. The repaired source passes the real standalone library Java
task and 52 focused production-helper host checks. The unchanged JNI proof carries
against the integrated shared core. The original full hook audit passes after the
two narrow copy-hook changes. These checks establish API compilation, helper
behavior and hook accounting only; independent source
review, full application and native qualification remain required.

## Peek

- Omit `start_delay_seconds`: Android displays ten seconds and desktop uses zero.
  Configure zero explicitly and verify immediate activation on both devices.
  Verify strict shared parsing of negative, fractional, string and overflow values.
- Start from the checkbox, every chip and the dial release. A full opaque app
  screen must show large remaining seconds and Abort Peek; no hidden chat may
  appear before completion. The duration must begin at activation, not the tap.
- Abort by the button and Back, and issue a second start/toggle. No pending
  operation or duration write should survive. Reopen the picker and start once.
- Pause, destroy/recreate the activity or originating fragment, switch accounts,
  replace the selected account's user, change preset, lock the app, and reload
  settings during the countdown. No stale completion may activate Peek.
  Restart the process during a countdown: no pending countdown is restored.
- Change delay from ten to zero, zero to ten and ten to twenty. Wait the maximum
  of old and new values. Abort preserves the old setting; successful Save changes
  only the setting and does not activate Peek. A concurrent settings edit must
  not be overwritten by the delayed write.
- Check End Peek beside the lock in narrow portrait, landscape and tablet layouts.
  It is visible only during active Peek and absent during both countdown kinds.
  It ends timed and indefinite Peek immediately. Lock, profile, search and daily
  controls must retain their original behavior.
- With the exit checkbox on, manually end Peek, expire it and end it through the
  configured app-lock policy. Close a conversation hidden by the restored preset
  or an everywhere override, but preserve chats visible through unread rules,
  temporary show overrides, reachable folder tabs or a visible Archive.
  Folder reachability must not defeat an everywhere hide. Normal stays open.
- Repeat those end routes with the checkbox off: the conversation stays open.
  Check active duration replacement, active setting-change countdown plus Abort,
  and the existing long-press extension cap without starting an extra Peek.

## Copy as Markdown

- Select ordinary text, captions, an original full article, a partial article and
  a displayed translated article together. The MD action must preserve selected
  order and perform one clipboard write only after all full article bodies exist.
- Include primary and migrated messages with identical numeric IDs. Preserve both
  distinct messages, migrated before primary, and use each message's own peer for
  full-body requests. Check reverse ID ordering in encrypted history.
- While a full-page request is pending, edit/delete/replace one selected message,
  change translation/language, navigate away, pause/destroy the chat, switch
  account, or start another Markdown copy. The old operation must not write.
  Errors, empty replies and replies that remain partial must preserve clipboard.
- Repeat in protected chats, with an album and with only ordinary selected text.
  Ordinary Copy, context/album Markdown, text/code selection and preview copying
  retain their existing policy and behavior. Check MD accessibility and toolbar
  layout with all other available actions.

The existing 25 ordinary and 70 structural formatter checks are unchanged inputs
for this delta. They are not replayed for the five source-review repairs.

## Five source-review repairs

Peek reads a fresh bounded settings snapshot before choosing friction, loads
those same bytes, and guards completion and a delay splice against that snapshot.
Missing, unreadable or malformed input cancels the operation. A changed snapshot
cancels even an immediate start. Completion rechecks exact pending ownership after
reload callbacks. The guarded replacement checks again before rename; it is not
an atomic compare-and-swap against an uncooperative external editor.

The deliberate exit decision excludes open/recent grace, while ordinary list
visibility keeps its existing grace. A forced close suppresses only the matching
account/dialog eligibility and drops its grace entry before normal pause handling.
Real unread/mention/reaction visibility, show/hide decisions and reachable folders
or Archive still apply, with an everywhere hide taking precedence over reachability.

Ordinary Markdown canonicalizes numeric and username mentions, preserves actual
absolute URLs including mail and telephone targets, and leaves interaction-only
spans as visible text. Browser URL spans contribute their merged bold, italic,
strike and underline flags as well as the link; duplicate supported style spans
remain deduplicated by the existing writer. Code remains literal code.

The focused host regression exercises the actual snapshot reader/guarded writer,
open eligibility model and link target helper with the actual Markdown writer.
It does not execute Android spans, native settings parsing, UI ownership callbacks,
real dialog visibility or clipboard actions. Those routes require the following
native checks on the qualified successor application:

- With cached zero, write delay ten and tap before the watcher fires. All entry
  points wait ten. Reduce a newly written twenty to zero and wait twenty.
  Delete the file, make it unreadable, or write malformed TOML during either
  countdown; no activation or stale write occurs. Preserve concurrent edits.
- Abort a pending setting change and preserve its bytes. Cause a synchronous
  settings reload to cancel it; an old completion must not clear a replacement
  pending operation or activate Peek.
- Open a hidden chat during Peek with positive already-in-view grace. Expire or
  stop Peek, including an everywhere hide: the chat closes and is absent after
  onPause. Repeat for another account with the same dialog ID. With the checkbox
  off, Normal, real unread/mention/reaction visibility, a show override or a
  reachable folder/Archive, preserve the expected open chat.
- Copy ordinary numeric/username mentions and URLs carrying each supported style,
  combined styles and spoiler overlap. Preserve the target and style once;
  bot commands, hashtags, timestamps, bank cards and clipboard-only spans remain
  visible text. Repeat with inline code and literal Markdown punctuation.
- In both peer-protected and message-protected chats, tap inline code and use the
  fallback long-press link Copy row. Verify the exact clipboard result. Ordinary
  unprotected copying remains available; forwarding and saving retain their
  separate behavior.
