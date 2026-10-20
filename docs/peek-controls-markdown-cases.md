# Copy as Markdown qualification

The formatter and real production Java API checks passed for the combined candidate.
Application and native behavior remain unmeasured.

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

## Source repairs and remaining native cases

Ordinary Markdown canonicalizes numeric and username mentions, preserves actual
absolute URLs including mail and telephone targets, and leaves interaction-only
spans as visible text. Browser URL spans contribute their merged bold, italic,
strike and underline flags as well as the link; duplicate supported style spans
remain deduplicated by the existing writer. Code remains literal code.

- Copy ordinary numeric/username mentions and URLs carrying each supported style,
  combined styles and spoiler overlap. Preserve the target and style once;
  bot commands, hashtags, timestamps, bank cards and clipboard-only spans remain
  visible text. Repeat with inline code and literal Markdown punctuation.
- In both peer-protected and message-protected chats, tap inline code and use the
  fallback long-press link Copy row. Verify the exact clipboard result. Ordinary
  unprotected copying remains available; forwarding and saving retain their
  separate behavior.
