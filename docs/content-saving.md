# Local media saves and content screenshots

Purple permits user-requested local exports of available received media, even
when chat or message metadata forbids forwarding or the message has a timer.
There is no new preference. The client does not alter those metadata fields or
any outgoing request.

`PurpleMediaSave` owns chat menu additions, the disappearing-media viewer menu,
the view-once voice/round-video player menu, and protected audio downloads. On protected messages it replaces only the
ordinary local save rows, leaving forwarding and sharing decisions upstream.
Live photos without at-rest encryption keep the upstream motion-photo save
handlers, preserving both the image and video parts.
The helper resolves the attachment or normal/cache media location. For photos
it also checks the exact displayed-size cache path used by SecretMediaViewer,
which can differ from the larger size selected by getPathToMessage. For an
at-rest encrypted `.enc` file it uses Telegram's `EncryptedFileInputStream`
and matching cache key. Encryption is explicit source metadata, set only when
selecting an appended Telegram cache candidate. A user attachment named `.enc`
is copied without decryption. Alternative video qualities resolve from
`cachedQuality.uri` and `qualityToSave`, retaining the selected document's
filename and MIME. Synthetic animated/custom emoji documents are excluded. It writes directly into public storage, without a
plaintext temporary cache file. Android 10 and later use a pending MediaStore
entry published after the stream closes. The stream copy rejects truncated or
non-progressing reads and bounds every encrypted read to its remaining size.
Length comes from the opened file descriptor, so source unlinking at expiry
cannot turn an already-requested save into a successful empty export.
A short encrypted read fails rather than letting the upstream decryption stream
advance its cipher offset past the bytes it actually read. Older Android uses
external storage
permission and a fresh destination scanned by the media scanner. A failed copy
removes its own partial output. Missing media prompts the user to download it.
Internal storage is accepted only
for the exact derived media filename in Telegram's media-cache directory;
arbitrary internal attachments stay blocked.
A manual save creates the intended user-owned copy; source timers and deletion
continue normally and no expired media is recovered or retained automatically.

`PurpleContentPolicy` disables content-driven screenshot restrictions in chat,
profile, photo, disappearing photo/video/voice, story and translation windows.
Profile avatar menus expose the gallery action independently of forwarding.
Story gallery saving bypasses the client Premium upsell for all accounts.
Story saving still requires an available, unexpired (or pinned) story, not a
deleted or skipped placeholder.
Passcode, payment and other unrelated window policies keep their existing paths.
`FileLoader` also honors the existing automatic gallery-save preference in
protected regular chats. Its encrypted-dialog exclusion, encrypted cache route
and `SaveToGallerySettingsHelper` exclusion for secret media remain in place,
so automatic saving does not archive disappearing media.
The photo viewer's gallery visibility is independent of its forwarding/share
visibility. Secret-media save controls use a supplier of the current message,
so a viewer reused for another message does not save a previous one.

Validation for this change is headless: the upstream hook checker and actual
production AGP JavaCompile, plus `MediaSaveStreamTest` against the actual
production copy helper (16 byte-boundary, source-selection, short-read, truncation and no-progress
checks). Device screenshots, Android storage providers and
menu geometry still need native checks before release.

Run the stream checks with a JDK, using a scratch output directory:

```sh
classes="$(mktemp -d)"
javac -d "$classes" \
  TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleMediaSaveStream.java \
  TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleMediaSaveSource.java \
  purple/tests/MediaSaveStreamTest.java
java -cp "$classes" MediaSaveStreamTest
```
