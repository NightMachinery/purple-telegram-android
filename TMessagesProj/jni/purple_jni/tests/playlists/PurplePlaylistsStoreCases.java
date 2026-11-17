package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

public final class PurplePlaylistsStoreCases {
    private static int checks;
    private static final String EMPTY = "{\"format\":1,\"playlists\":{},\"purged\":{}}";
    private static final String LOCAL = "{\"format\":1,\"sync\":true}";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private static void check(boolean value, String reason) {
        ++checks;
        if (!value) throw new AssertionError(reason);
    }

    private static final class Memory implements PurplePlaylistsStore.Physical {
        final EnumMap<PurplePlaylistsStore.Name, PurplePlaylistsStore.File> files =
                new EnumMap<>(PurplePlaylistsStore.Name.class);
        final List<PurplePlaylistsStore.Name> writes = new ArrayList<>();
        PurplePlaylistsStore.Name failBefore, failAfter, failRead;
        PurplePlaylistsStore.Name hookName;
        Runnable hook;
        boolean route = true;
        int closes, confirmations;
        boolean failConfirm;
        Memory() {
            for (PurplePlaylistsStore.Name name : PurplePlaylistsStore.Name.values()) put(name, null);
        }
        void put(PurplePlaylistsStore.Name name, byte[] value) {
            files.put(name, new PurplePlaylistsStore.File(new Object(), value));
        }
        public boolean current() { return route; }
        public PurplePlaylistsStore.File read(PurplePlaylistsStore.Name name) throws Exception {
            if (name == failRead) throw new Exception("synthetic read failure");
            PurplePlaylistsStore.File value = files.get(name);
            return new PurplePlaylistsStore.File(value.token, value.bytes());
        }
        public boolean matches(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File expected) {
            PurplePlaylistsStore.File value = files.get(name);
            return expected != null && value.token == expected.token && value.content(expected.bytes());
        }
        public PurplePlaylistsStore.Publication confirm(PurplePlaylistsStore.Name name,
                PurplePlaylistsStore.File expected, PurplePlaylistsStore.Current current) {
            ++confirmations;
            if (failConfirm || !current.current() || !matches(name, expected))
                return new PurplePlaylistsStore.Publication(false, null);
            return new PurplePlaylistsStore.Publication(true, files.get(name));
        }
        public PurplePlaylistsStore.Publication replace(PurplePlaylistsStore.Name name,
                PurplePlaylistsStore.File expected, byte[] value, PurplePlaylistsStore.Current current) {
            if (name == hookName && hook != null) { Runnable action = hook; hook = null; action.run(); }
            if (name == failBefore || !current.current() || !matches(name, expected))
                return new PurplePlaylistsStore.Publication(false, null);
            writes.add(name);
            put(name, value);
            if (name == failAfter || !current.current()) return new PurplePlaylistsStore.Publication(false, files.get(name));
            return new PurplePlaylistsStore.Publication(true, files.get(name));
        }
        public void close() { ++closes; route = false; }
    }

    private static void missingAndOrdering() throws Exception {
        Memory memory = new Memory();
        try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
            PurplePlaylistsStore.Read initial = store.read();
            check(initial.ready(), "proved missing files use explicit core defaults");
            check(text(initial.library()).equals(EMPTY), "default library");
            check(text(initial.state()).equals(LOCAL), "default local state");
            check(memory.writes.isEmpty(), "read never materializes defaults");
            byte[] exposed = initial.library(); exposed[0] = 0;
            check(initial.library()[0] == '{', "read buffers are immutable to caller");
            PurplePlaylistsStore.Write saved = store.write(initial, bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit);
            check(saved.ready(), "first write succeeds");
            check(saved.libraryCommitted && saved.stateCommitted, "both first files confirmed");
            check(saved.libraryMayHaveChanged && saved.stateMayHaveChanged, "attempted writes visible");
            check(memory.writes.equals(Arrays.asList(PurplePlaylistsStore.Name.Library, PurplePlaylistsStore.Name.Local)),
                    "library precedes local state even for empty library");
            check(store.write(initial, bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit).status
                    == PurplePlaylistsStore.Status.Changed, "attempt invalidates old read capability");
            PurplePlaylistsStore.Read current = store.read();
            int priorWrites = memory.writes.size();
            PurplePlaylistsStore.Write same = store.write(current, bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.Merge);
            check(same.ready() && !same.libraryCommitted && !same.stateCommitted, "no-op is not a commit");
            check(memory.writes.size() == priorWrites, "unchanged merge performs no writes or backup");
        }
        check(memory.closes == 1, "physical lock ownership released once");
    }

    private static void staleAndRefused() throws Exception {
        Memory memory = new Memory();
        try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
            PurplePlaylistsStore.Read old = store.read(), current = store.read();
            check(store.setSync(old, false).status == PurplePlaylistsStore.Status.Changed, "later read revokes prior capability");
            Memory otherMemory = new Memory();
            try (PurplePlaylistsStore other = new PurplePlaylistsStore(11, otherMemory)) {
                check(store.setSync(other.read(), false).status == PurplePlaylistsStore.Status.Changed,
                        "another store cannot supply read authority");
            }
            memory.put(PurplePlaylistsStore.Name.Library, bytes(EMPTY));
            check(store.setSync(current, false).status == PurplePlaylistsStore.Status.Changed, "file identity change refuses write");
            check(memory.writes.isEmpty(), "stale writes have no side effects");
            current = store.read();
            check(store.write(current, null, bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit).status
                    == PurplePlaylistsStore.Status.InvalidWrite, "invalid requested library refused");
            check(store.write(current, bytes(EMPTY), bytes("{\"format\":2}"), PurplePlaylistsStore.Kind.Merge).status
                    == PurplePlaylistsStore.Status.InvalidWrite, "newer requested state refused");
            check(memory.writes.isEmpty(), "invalid candidates never touch files");
            memory.put(PurplePlaylistsStore.Name.Local, bytes("{\"format\":2}"));
            check(store.read().status == PurplePlaylistsStore.Status.NewerState, "newer local file held");
            memory.put(PurplePlaylistsStore.Name.Local, bytes("{"));
            check(store.read().status == PurplePlaylistsStore.Status.InvalidState, "invalid local file held");
            memory.put(PurplePlaylistsStore.Name.Local, null);
            memory.put(PurplePlaylistsStore.Name.Library, bytes("{\"format\":2}"));
            check(store.read().status == PurplePlaylistsStore.Status.NewerLibrary, "newer library held");
            memory.put(PurplePlaylistsStore.Name.Library, bytes(""));
            check(store.read().status == PurplePlaylistsStore.Status.InvalidLibrary, "empty existing library is not absence");
            memory.failRead = PurplePlaylistsStore.Name.Library;
            check(store.read().status == PurplePlaylistsStore.Status.IoError, "unreadable file never becomes defaults");
        }
    }

    private static void backupAndPartialDurability() throws Exception {
        Memory memory = new Memory();
        byte[] raw = bytes(" {\"purged\":{},\"playlists\":{},\"format\":1} \n");
        memory.put(PurplePlaylistsStore.Name.Library, raw);
        try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
            PurplePlaylistsStore.Write saved = store.write(store.read(), bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.Merge);
            check(saved.ready(), "changing merge succeeds");
            check(memory.files.get(PurplePlaylistsStore.Name.Backup).content(raw), "backup uses exact prior bytes");
            check(memory.writes.equals(Arrays.asList(PurplePlaylistsStore.Name.Backup,
                    PurplePlaylistsStore.Name.Library, PurplePlaylistsStore.Name.Local)), "backup precedes changing merge and checkpoint");
        }
        for (PurplePlaylistsStore.Name failed : new PurplePlaylistsStore.Name[] {
                PurplePlaylistsStore.Name.Library, PurplePlaylistsStore.Name.Local}) {
            memory = new Memory();
            memory.failAfter = failed;
            try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
                PurplePlaylistsStore.Read expected = store.read();
                PurplePlaylistsStore.Write saved = store.write(expected, bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit);
                check(!saved.ready(), "post-publication barrier failure remains failure");
                check(saved.libraryMayHaveChanged, "library attempt retained on failure");
                if (failed == PurplePlaylistsStore.Name.Library) {
                    check(saved.status == PurplePlaylistsStore.Status.LibraryWriteFailed && !saved.libraryCommitted,
                            "uncertain library durability is not confirmed");
                    check(!saved.stateMayHaveChanged && memory.files.get(PurplePlaylistsStore.Name.Local).absent(),
                            "unconfirmed library never authorizes a local checkpoint");
                } else {
                    check(saved.status == PurplePlaylistsStore.Status.StateWriteFailed && saved.libraryCommitted,
                            "confirmed library survives local durability failure");
                    check(saved.stateMayHaveChanged && !saved.stateCommitted, "uncertain local durability exposed");
                }
                check(store.write(expected, bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit).status
                        == PurplePlaylistsStore.Status.Changed, "failure requires reload");
                check(store.read().ready(), "read after partial publication can recover latest files");
            }
        }
        memory = new Memory();
        memory.put(PurplePlaylistsStore.Name.Library, raw);
        memory.failBefore = PurplePlaylistsStore.Name.Backup;
        try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
            PurplePlaylistsStore.Write saved = store.write(store.read(), bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.Merge);
            check(saved.status == PurplePlaylistsStore.Status.BackupFailed && !saved.libraryMayHaveChanged,
                    "failed backup cannot change primary files");
            check(memory.files.get(PurplePlaylistsStore.Name.Library).content(raw) && memory.writes.isEmpty(),
                    "failed backup preserves exact library");
        }
    }

    private static void preferencesAndGuards() throws Exception {
        Memory memory = new Memory();
        byte[] raw = bytes(" {\"purged\":{},\"playlists\":{},\"format\":1} \n");
        byte[] local = bytes("{\"sync\":true,\"unsynced_since\":2,\"future\":{\"kept\":true}}");
        memory.put(PurplePlaylistsStore.Name.Library, raw);
        memory.put(PurplePlaylistsStore.Name.Local, local);
        try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
            PurplePlaylistsStore.Write saved = store.setSync(store.read(), false);
            check(saved.ready() && saved.stateCommitted && !saved.libraryMayHaveChanged, "sync edit is local-only");
            check(memory.files.get(PurplePlaylistsStore.Name.Library).content(raw), "sync edit preserves noncanonical library bytes");
            String actual = text(memory.files.get(PurplePlaylistsStore.Name.Local).bytes());
            check(actual.contains("\"unsynced_since\":2") && actual.contains("\"future\":{\"kept\":true}")
                    && actual.contains("\"sync\":false"), "sync edit retains age and unknown state");
            check(memory.files.get(PurplePlaylistsStore.Name.Backup).absent(), "local-only edit preserves backup absence");
        }
        memory = new Memory();
        final Memory selected = memory;
        try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
            memory.hookName = PurplePlaylistsStore.Name.Local;
            memory.hook = () -> selected.put(PurplePlaylistsStore.Name.Library, bytes(EMPTY));
            PurplePlaylistsStore.Write saved = store.write(store.read(), bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit);
            check(saved.status == PurplePlaylistsStore.Status.StateWriteFailed && saved.libraryCommitted,
                    "checkpoint refuses library replacement during state publication");
            check(memory.files.get(PurplePlaylistsStore.Name.Local).absent(), "changed checkpoint cannot publish memo");
        }
        memory = new Memory();
        final Memory revoked = memory;
        try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
            memory.hookName = PurplePlaylistsStore.Name.Library;
            memory.hook = () -> revoked.route = false;
            PurplePlaylistsStore.Write saved = store.write(store.read(), bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit);
            check(!saved.ready() && memory.writes.isEmpty(), "revoked route refuses actual publication");
        }
    }

    private static void unchangedLibraryDurability() throws Exception {
        Memory memory = new Memory();
        memory.put(PurplePlaylistsStore.Name.Library, bytes(EMPTY));
        memory.failConfirm = true;
        try (PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory)) {
            PurplePlaylistsStore.Read expected = store.read();
            check(expected.ready(), "existing equal library can be read without durability authority");
            Object identity = memory.files.get(PurplePlaylistsStore.Name.Library).token;
            PurplePlaylistsStore.Write failed = store.write(expected, bytes(EMPTY), bytes(LOCAL),
                    PurplePlaylistsStore.Kind.LocalEdit);
            check(failed.status == PurplePlaylistsStore.Status.LibraryWriteFailed && !failed.stateMayHaveChanged,
                    "unchanged library barrier failure refuses local checkpoint");
            check(memory.confirmations == 1 && memory.writes.isEmpty(), "equal bytes require explicit durability confirmation");
            check(memory.files.get(PurplePlaylistsStore.Name.Local).absent(), "unconfirmed existing library cannot publish memo");
            check(!failed.libraryCommitted && !failed.libraryMayHaveChanged,
                    "confirmation does not claim a library replacement");
            check(memory.files.get(PurplePlaylistsStore.Name.Library).token == identity,
                    "failed confirmation preserves library identity");
            memory.failConfirm = false;
            check(store.write(expected, bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit).status
                    == PurplePlaylistsStore.Status.Changed, "failed confirmation revokes prior read capability");
            PurplePlaylistsStore.Write saved = store.write(store.read(), bytes(EMPTY), bytes(LOCAL),
                    PurplePlaylistsStore.Kind.LocalEdit);
            check(saved.ready() && saved.stateCommitted && !saved.libraryCommitted,
                    "fresh read and successful barrier permit local checkpoint");
            check(memory.writes.equals(Arrays.asList(PurplePlaylistsStore.Name.Local)),
                    "confirmation does not rewrite equal library");
        }
    }

    private static void lifetimeAndReentry() throws Exception {
        Memory memory = new Memory();
        PurplePlaylistsStore store = new PurplePlaylistsStore(11, memory);
        memory.hookName = PurplePlaylistsStore.Name.Library;
        memory.hook = () -> check(store.read().status == PurplePlaylistsStore.Status.Busy, "reentrant read cannot replace authority");
        check(store.write(store.read(), bytes(EMPTY), bytes(LOCAL), PurplePlaylistsStore.Kind.LocalEdit).ready(),
                "guard reentry leaves serialized write valid");
        PurplePlaylistsStore.Read[] result = new PurplePlaylistsStore.Read[1];
        Thread wrong = new Thread(() -> result[0] = store.read());
        wrong.start(); wrong.join();
        check(result[0].status == PurplePlaylistsStore.Status.WrongThread, "store never migrates between worker threads");
        store.close(); store.close();
        check(store.read().status == PurplePlaylistsStore.Status.Closed && memory.closes == 1, "close revokes and releases once");
    }

    public static void main(String[] args) throws Exception {
        missingAndOrdering();
        staleAndRefused();
        backupAndPartialDurability();
        preferencesAndGuards();
        unchangedLibraryDurability();
        lifetimeAndReentry();
        System.out.println("PLAYLIST_STORE_CHECKS=" + checks);
        System.out.println("PLAYLIST_STORE_OK");
    }
}
