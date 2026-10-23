# Account-routed settings writes

Generic app-wide controls retain the initiating account lifetime, the app-wide
account key, role revision, Java/native generations, gate generation, roster,
file bytes and ancestor identities. Local Premium, schedule, screen time,
Peek delay, suggestions, focus sync, device labels and new-list structure edit
that captured app-wide file through AccountFiles. A signed-out app-wide account
keeps its recorded file as the destination. A role, account, view, dialog or
input change refuses a retained action. Rule Delete confirmations keep the
rule editor’s opening writer. Peek-delay input captures its writer and loaded
settings when opened and carries them through Save and the full countdown.

Publication reuses the guarded account editor model: exact backup, native
exclusive temporary file, private mode, fsync, rename and readback. A first
non-root file uses the shared version-2 template and copies no inherited
settings. Missing root files or missing primaries with good files remain
recovery. Opening controls creates no account directory. First explicit writes
use the existing native directory ownership receipt. This is bounded guarded
I/O, not a filesystem compare-and-swap or atomic account/filesystem transaction.

The list menu reads effective member arrays with the shared local Work Mode
choice. Own and stock edit the initiating account file, forking just the edited
list when inherited. Follow edits the app-wide file. Global accounts edit their
own app-wide file. The shared SetListMembers splice keeps explicit empty
arrays. Unticking keeps an explicit empty array. Existing comments
remain in place; inherited forks copy member comments through ListMemberComments
and resolve only the edited chat title in the initiating account. Every tick
rebuilds checkbox callbacks with a fresh captured route.

The first successful offline app-wide file edit in a process shows one bulletin
with Change, opening the existing app-wide account chooser. Modern sync and History restrictions remain. Legacy whole-file imports and
exports now use each account’s captured file, as described in
[account transfers](account-transfers.md). Eligible existing-file saves arm only
the file owner’s independent countdown. First file creation never sends.

Headless cases live in `TMessagesProj/jni/purple_jni/tests/routed_writes/`.
They use the production routed/editor models, actual core JNI and real isolated
files through the same host route as the editor cases. Android UI, actual
roster/logout dispatch and Android Os behavior require separate device checks;
these host cases do not claim that coverage. Existing story and channel suggestion hooks now pass their account.

## Work Mode choices and list ownership

Settings offers a device-local choice for each account when at least two
accounts are known, counting a signed-out app-wide account: own members with
app-wide fallback, follow app-wide members, or stock Telegram. A global account
has On and Stock choices. The shared SetAccountWorkMode splice changes only
local.toml. The opening writer guards the whole dialog and publication.

Each reload captures native effective list settings for each signed-in identity.
The global input is the same accepted snapshot used for the shared resolution.
Own member arrays replace inherited arrays; follow retains the fork on disk.
Each slot has separate visibility/story caches and synthetic folder objects.
An identity, backend, lifetime or global-role mismatch answers stock until the next reload.
A rejected reload under the same global role retains the previous snapshot.
Malformed or unreadable local choices retain the last accepted choice for that
global role; deleting local.toml restores Own. Before ownership is established,
readers keep the existing app-wide behavior and writes remain held.
Stock bypasses preset visibility, mute, archive/story rules, extra tabs,
Until/close-buffer marks, badges and suggestions, while leaving Last Seen,
Local Premium and app-wide schedule/Peek/Screen Time settings alone. Refresh
publishes the new mute readers, recounts unread state on each storage queue,
then rebuilds lists and badges after the queued counter publication.

List rows identify own versus inherited members and explain the fork or follow
write. Stock retains list edits for other devices and hides Until actions.
Long press offers Delete own members only for a non-global version-2 table
containing exactly the members key. Version 1, unversioned, defining tables,
and unknown keys refuse Reset. Confirmation retains the opening route and
uses the core RemoveTableKey splice with a quoted literal list name. Comments
and sibling keys survive. New list structure and pinned order remain app-wide.

Reload diagnostics count changed app-wide keys using SummarizeConfigChange;
list members are excluded. Undefined list overrides remain on disk and appear
with chat counts in account settings, editor preview and reload bulletins.
The bulletin opens the affected account editor through identity/role guards.
Removing members from a table that defines a list warns about devices using
that account as their app-wide source. Loading and warning generation create
no account files. Modern non-root sync/History holds remain; legacy account-file transfers have
their own guarded path.

Native account generations hold both effective settings and their resolved
preset. Reload builds and validates a replacement while the published handles
remain usable. Publication swaps the generation under a write lease, then
retires old handles after existing read leases finish. JNI readers keep a read
lease through the query. Account identity locks precede publication locks.
Abandoned candidates are retired without replacing the active generation.
Missing positive handles answer hidden, muted and story-hidden; explicit Stock
and the legacy app-wide route have separate sentinel handles.

Editor scope captures the opening local Work Mode choice with the other file
inputs. Follow-mode member edits, including removed keys, are listed under
keys not used on this device and explain that saved settings still change on
other devices. Last Seen and other per-account fields keep their own scope.
Delete-own table paths escape quotes, backslashes and every TOML control
character, including newline, tab and DEL, matching the core splice helper.
