package org.telegram.messenger.purple;
final class PurplePlaylistsAccount {
 static final class Identity { int slot; long userId = 101; }
 static final class Captured { final Identity identity = new Identity(); boolean live = true; boolean current() { return live; } }
 static Captured[] slots = { new Captured(), new Captured() };
 static { slots[1].identity.slot = 1; }
 static Captured capture(int slot) { return slots[slot].current() ? slots[slot] : null; }
}
