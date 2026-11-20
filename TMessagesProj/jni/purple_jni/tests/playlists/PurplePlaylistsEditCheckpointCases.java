package org.telegram.messenger.purple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.json.JSONObject;

public final class PurplePlaylistsEditCheckpointCases {
    private static final String ID = "pl-aaaaaaaaaaaaaaab";
    private static final long SELF = 101;
    private static final long EDIT_TIME = 300;
    private static final byte[] INITIAL = library("Before");
    private static final byte[] LOCAL = bytes(
            "{\"format\":1,\"sync\":true,\"status\":{\"result\":\"synced\"},\"unknown_field\":{\"kept\":true}}");
    private static final byte[] WAITING = bytes(
            "{\"format\":1,\"sync\":true,\"unsynced_since\":50,\"status\":{\"result\":\"wait\","
            + "\"retry_at_ms\":\"12000\",\"keep\":6},\"android_playlist_downloads\":{\"format\":99,\"opaque\":[1,2]},"
            + "\"unknown_field\":{\"kept\":true}}");

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] library(String title) {
        return bytes("{\"format\":1,\"playlists\":{\"" + ID + "\":{\"title\":[\"" + title
                + "\",100],\"deleted\":[false,100]}},\"purged\":{}}");
    }
    private static JSONObject object(byte[] value) {
        return new JSONObject(new String(value, StandardCharsets.UTF_8));
    }
    private static void check(boolean value, String assertion) {
        if (!value) throw new AssertionError(assertion);
    }
    private static boolean hasTitle(byte[] data, String title) {
        JSONObject lists = object(data).getJSONObject("playlists");
        for (String id : lists.keySet()) {
            if (title.equals(lists.getJSONObject(id).getJSONArray("title").getString(0))) return true;
        }
        return false;
    }

    private static final class Faults implements PurplePlaylistsStore.Physical {
        final PurplePlaylistsPhysical actual;
        String phase = "";
        int hits;
        Faults(PurplePlaylistsPhysical actual) { this.actual = actual; }
        public boolean current() { return actual.current(); }
        public PurplePlaylistsStore.File read(PurplePlaylistsStore.Name name) throws Exception {
            return actual.read(name);
        }
        public boolean matches(PurplePlaylistsStore.Name name, PurplePlaylistsStore.File expected) throws Exception {
            return actual.matches(name, expected);
        }
        public PurplePlaylistsStore.Publication confirm(PurplePlaylistsStore.Name name,
                PurplePlaylistsStore.File expected, PurplePlaylistsStore.Current current) throws Exception {
            return actual.confirm(name, expected, current);
        }
        public PurplePlaylistsStore.Publication replace(PurplePlaylistsStore.Name name,
                PurplePlaylistsStore.File expected, byte[] value, PurplePlaylistsStore.Current current) throws Exception {
            String step = name == PurplePlaylistsStore.Name.EditCheckpoint
                    ? (value.length == 0 ? "retire" : "prepare") : name.name().toLowerCase(java.util.Locale.ROOT);
            if (phase.equals(step)) {
                ++hits;
                throw new IOException("Injected physical write fault");
            }
            PurplePlaylistsStore.Publication written = actual.replace(name, expected, value, current);
            if (phase.equals(step + "-unknown")) {
                ++hits;
                return new PurplePlaylistsStore.Publication(false, written.file);
            }
            return written;
        }
        public void close() throws Exception { actual.close(); }
    }

    private static PurplePlaylistsPhysical open(Path path) throws Exception {
        return new PurplePlaylistsPhysical(path.toString(), "account/playlists", () -> true, true);
    }

    private static void abrupt(String result) {
        System.out.println(result);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static void seed(Path path, String phase, String mode) throws Exception {
        try (Faults files = new Faults(open(path)); PurplePlaylistsEdits editor = new PurplePlaylistsEdits(SELF)) {
            PurplePlaylistsStore store = new PurplePlaylistsStore(SELF, files);
            boolean pristine = mode.equals("pristine");
            if (!pristine) check(store.write(store.read(), INITIAL, mode.equals("wait") ? WAITING : LOCAL,
                    PurplePlaylistsStore.Kind.LocalEdit).ready(), "seed stable files");
            PurplePlaylistsStore.Read prior = store.read();
            check(prior.ready(), "ready before edit");
            PurplePlaylistsEdits.Command command = pristine || mode.equals("wait")
                    ? PurplePlaylistsEdits.Command.create("After", new byte[10], EDIT_TIME)
                    : PurplePlaylistsEdits.Command.rename(ID, "After", EDIT_TIME);
            byte[] priorLocal = files.read(PurplePlaylistsStore.Name.Local).bytes();
            PurplePlaylistsEdits.Result edit = editor.prepare(prior.library(), prior.state(), command);
            check(edit.valid() && edit.changed, "actual native edit prepared");
            check(object(edit.state()).getLong("unsynced_since") == (mode.equals("wait") ? 50 : EDIT_TIME),
                    "original earliest age");
            files.phase = phase;
            PurplePlaylistsStore.Write result = store.writeEdit(prior, edit);
            check(files.hits == (phase.equals("none") ? 0 : 1), "one expected fault at " + phase);
            if (phase.equals("prepare")) {
                check(result.status == PurplePlaylistsStore.Status.EditCheckpointFailed && !result.libraryMayHaveChanged,
                        "record failure prevents library attempt");
            } else if (phase.startsWith("library")) {
                check(result.status == PurplePlaylistsStore.Status.LibraryWriteFailed && !result.libraryCommitted
                        && !result.stateMayHaveChanged, "library failure keeps flags truthful");
            } else {
                check(result.libraryCommitted, "library was confirmed");
                check(result.status == (phase.equals("retire") ? PurplePlaylistsStore.Status.EditCheckpointFailed
                        : phase.equals("none") ? PurplePlaylistsStore.Status.Ready
                        : PurplePlaylistsStore.Status.StateWriteFailed), "exact completion status");
                check(result.stateCommitted == (phase.equals("retire") || phase.equals("none")), "state commit truth");
            }
            boolean beforeLibrary = phase.equals("prepare") || phase.equals("library");
            check(hasTitle(files.read(PurplePlaylistsStore.Name.Library).bytes(), beforeLibrary ? "Before" : "After"),
                    "durable library matches publication boundary");
            if (phase.equals("local") || phase.startsWith("library") || phase.equals("prepare")) {
                check(Arrays.equals(priorLocal, files.read(PurplePlaylistsStore.Name.Local).bytes()),
                        "local raw bytes still match the prior file");
            }
            byte[] record = files.read(PurplePlaylistsStore.Name.EditCheckpoint).bytes();
            check(phase.equals("prepare") ? record == null : record != null
                    && record.length == (phase.equals("none") ? 0 : 212), "expected record");
            abrupt("SEED_" + phase);
        }
    }

    private static void read(Path path, String outcome, String mode) throws Exception {
        try (Faults files = new Faults(open(path)); PurplePlaylistsEdits editor = new PurplePlaylistsEdits(SELF)) {
            PurplePlaylistsStore store = new PurplePlaylistsStore(SELF, files);
            byte[] initial = files.read(PurplePlaylistsStore.Name.Local).bytes();
            boolean interruptAgain = outcome.equals("retry-retire");
            if (interruptAgain) files.phase = "retire";
            PurplePlaylistsStore.Read found = store.read();
            if (interruptAgain) {
                check(files.hits == 1 && found.status == PurplePlaylistsStore.Status.EditCheckpointFailed,
                        "interrupted recovery leaves a pending record");
                check(object(files.read(PurplePlaylistsStore.Name.Local).bytes()).getLong("unsynced_since") == EDIT_TIME,
                        "local repair survives a second abrupt process exit");
                abrupt("RECOVERY_INTERRUPTED_AFTER_STATE");
            }
            check(found.ready(), "new process finishes recovery: " + found.detail);
            check("NothingToUndo".equals(editor.prepare(found.library(), found.state(),
                    PurplePlaylistsEdits.Command.undo(999)).error), "Undo stays local to the old process");
            JSONObject state = object(found.state());
            if (!mode.equals("pristine"))
                check(state.getJSONObject("unknown_field").getBoolean("kept"), "unknown metadata preserved");
            if (outcome.equals("prior")) {
                check(hasTitle(found.library(), "Before") && !state.has("unsynced_since"),
                        "unpublished edit reopens clean");
                check(Arrays.equals(initial, files.read(PurplePlaylistsStore.Name.Local).bytes()),
                        "unpublished edit cannot rewrite local metadata");
            } else {
                check(hasTitle(found.library(), "After"), "new library survived");
                check(state.getLong("unsynced_since") == (mode.equals("wait") ? 50 : EDIT_TIME), "exact earliest age");
                if (mode.equals("wait")) {
                    check(PurplePlaylistsLocalCore.retryAt(found.state()) == 12000, "existing wait preserved");
                    check(state.getJSONObject("status").getInt("keep") == 6
                            && state.getJSONObject("android_playlist_downloads").getInt("format") == 99,
                            "cooldown and opaque download metadata preserved");
                } else if (!outcome.equals("settled")) {
                    JSONObject status = state.optJSONObject("status");
                    check(status == null || !"synced".equals(status.getString("result")),
                            "recovered edit cannot inherit a stale success");
                }
                if (outcome.equals("settled") || outcome.equals("after-recovery")) {
                    check(Arrays.equals(initial, files.read(PurplePlaylistsStore.Name.Local).bytes()),
                            "already durable local state stays byte-identical");
                }
            }
            PurplePlaylistsStore.File retired = files.read(PurplePlaylistsStore.Name.EditCheckpoint);
            check(retired.absent() || retired.bytes().length == 0, "record retired after durable recovery");
            check(Arrays.equals(found.state(), store.read().state()), "second read stable");
            System.out.println("RECOVERED_" + outcome);
        }
    }

    private static void refuse(Path path, String reason) throws Exception {
        try (PurplePlaylistsPhysical files = open(path)) {
            PurplePlaylistsStore.Name name = PurplePlaylistsStore.Name.EditCheckpoint;
            if (reason.equals("library")) name = PurplePlaylistsStore.Name.Library;
            if (reason.equals("state")) name = PurplePlaylistsStore.Name.Local;
            if (!reason.equals("account")) {
                PurplePlaylistsStore.File old = files.read(name);
                byte[] replacement;
                if (reason.equals("library")) replacement = library("Later");
                else if (reason.equals("state")) replacement = bytes("{\"format\":1,\"future\":[71]}");
                else {
                    replacement = old.bytes();
                    replacement[reason.equals("format") ? 3 : 24] ^= 1;
                }
                check(files.replace(name, old, replacement, () -> true).durable, "synthetic replacement durable");
            }
            byte[] library = files.read(PurplePlaylistsStore.Name.Library).bytes();
            byte[] state = files.read(PurplePlaylistsStore.Name.Local).bytes();
            byte[] marker = files.read(PurplePlaylistsStore.Name.EditCheckpoint).bytes();
            PurplePlaylistsStore failed = new PurplePlaylistsStore(reason.equals("account") ? SELF + 1 : SELF, files);
            PurplePlaylistsStore.Read refused = failed.read();
            check(refused.status == PurplePlaylistsStore.Status.EditCheckpointFailed && refused.state() == null,
                    "unexpected checkpoint identity must be refused");
            check(Arrays.equals(library, files.read(PurplePlaylistsStore.Name.Library).bytes())
                    && Arrays.equals(state, files.read(PurplePlaylistsStore.Name.Local).bytes())
                    && Arrays.equals(marker, files.read(PurplePlaylistsStore.Name.EditCheckpoint).bytes()),
                    "refusal preserves all files");
            System.out.println("REFUSED_" + reason);
        }
    }

    public static void main(String[] args) throws Exception {
        Path path = Paths.get(args[1]);
        switch (args[0]) {
            case "seed": seed(path, args[2], args[3]); break;
            case "read": read(path, args[2], args[3]); break;
            case "refuse": refuse(path, args[2]); break;
            default: throw new IllegalArgumentException("Unknown fixture stage");
        }
    }
}
