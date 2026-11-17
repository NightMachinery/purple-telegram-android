# Manual playlist sync and bounded admission

Manual checks use the captured account worker and the same sync actor as automatic
checks. They respect persisted cooldowns and reconcile unknown publication or delete
outcomes before issuing new effects. Resolution has a 30-second owned deadline.
Duplicate or late callbacks cannot launch a second actor. Closing the screen or
losing the captured account cancels its own request, while admitted writes settle
before the final immutable write/delete result is delivered.

Automatic scheduling keeps edit quiet time, earliest-unsynced maximum age, chat
updates and the foreground backstop. Manual checks can read with automatic sync off.
Android/provider execution and stock light/dark/long-text/RTL comparison remain
pending; host lifecycle and Android API compilation alone do not prove them.

The main playlist screen uses this version's UniversalFragment, UItem.asCheck,
UItem.asButton and UItem.asShadow, with the stock adapter and theme. Status and
incomplete/hidden counts use localized strings. Check sync status reconciles an
unknown outcome through the same actor; it does not reset a publication journal.
Automatic sync changes are committed on the account worker before the checkbox
receives its new value. Menu Refresh remains a local read.
