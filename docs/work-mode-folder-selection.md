# Folder selection follows the rendered folder

The folder strip and the chat list must refer to the same logical folder.
Work Mode can publish a new folder projection before the queued strip refresh
runs. A tab's numeric position therefore cannot identify the folder the user
saw. Previously the strip committed its indicator first, then the screen looked
up that position in the newer projection. A reorder selected another folder;
a shorter projection could leave the old list under the newly selected tab.

`PurpleFolderProjection` publishes a completed Work Mode projection after its
synthetic filters have been rebuilt. Its capture checks the account lifetime,
role, loaded state, generation and per-account view before and after collecting
one ordered filter list. `PurpleFolderSelection` resolves the rendered logical
identity into that capture. Native folder IDs survive reordering. Synthetic
view IDs are accepted only under the same Work Mode definition, because the
existing synthetic builder reuses IDs by ordinal.

`PurpleFolderNavigation` belongs to one `DialogsActivity`. It remembers actual
tab objects and actual page objects, so recycling positions or exchanging the
two native pages cannot exchange their identities. A selection is admitted,
bound to its exact native filter object, validated, and consumed once. A new
intent, refresh, account change or fragment retirement invalidates old work.
If the rendered list changed, refresh completes the requested valid selection
without committing the old positional indicator. Removed or locked targets are
refused. A refresh preserves an unchanged native binding and its scroll position.

The strip retains its stock rows, drawables, lock feedback and animation.
Its optional preparation delegate runs before indicator publication. Other
consumers retain the legacy callback order. A transition serial prevents a
reentrant selection or strip rebuild from reviving an old animation runnable.
The native swipe listener captures its own selection ticket and checks the
actual animator before it swaps pages or clears state. A new tap cancels the
previous native swipe writer before rebinding.

The upstream edits are confined to `FilterTabsView` preparation and transition
seams, and `DialogsActivity` render, bind, gesture and lifecycle seams. Native
filter-slot selection stays in `switchToCurrentSelectedMode`. The screen keeps
the small callbacks that manipulate its private page, adapter and action-bar
state; the identity and admission policy stays in Purple-owned helpers.

Private qualification has compiled all six changed Java sources against real
Android application class inputs. Source-bound regressions retain the original
three incorrect-outcome witnesses and pass 60 candidate checks through actual
navigation method bodies with inert UI/account ports. These checks cover
reordering, shrink, reentrant native binding, synthetic definition changes,
both page slots, swipes, reversal, cancellation, old listeners and retirement.
They do not execute the Android event loop. Fresh AGP compilation, the complete
canonical host campaign and actual Android tap/swipe and visual checks remain
required before claiming that the installed application is repaired.
