package org.telegram.messenger.purple;

import java.io.IOException;
import java.util.Arrays;

public final class PurplePlaylistsRemoval {
    public enum Status { Ready, Pending, Busy, Changed, Unavailable, Failed }
    public static final class Result {
        public final Status status;
        public final int removed, protectedFiles;
        public final boolean filesMayHaveChanged, stateMayHaveChanged, prune;
        private Result(Status status, int removed, int protectedFiles, boolean files, boolean state, String playlist) {
            this.status = status; this.removed = removed; this.protectedFiles = protectedFiles;
            filesMayHaveChanged = files; stateMayHaveChanged = state; prune = playlist == null;
        }
        public boolean ready() { return status == Status.Ready; }
    }
    static Result pending(byte[] library, byte[] state, long self, String playlist) {
        PurplePlaylistsDownloadsCore.RemovalPlan plan = PurplePlaylistsDownloadsCore.removal(library, state, self,
                playlist == null || playlist.isEmpty() ? null : playlist);
        return plan.cleanupSelected ? new Result(Status.Pending, 0, plan.protectedFiles, false, false, playlist)
                : plan.cleanupPrune ? new Result(Status.Pending, 0, plan.protectedFiles, false, false, null) : null;
    }
    static Result run(PurplePlaylistsStore store, long self, PurplePlaylistsStore.Read expected,
            String playlist, boolean active, PurplePlaylistsStore.Current current) {
        int removed = 0, protectedFiles = 0;
        boolean filesChanged = false, stateChanged = false;
        try {
            if (!current.current() || !store.stillCurrent(expected)) return new Result(Status.Changed, 0, 0, false, false, playlist);
            PurplePlaylistsDownloadsCore.RemovalPlan plan = PurplePlaylistsDownloadsCore.removal(
                    expected.library(), expected.state(), self, playlist);
            protectedFiles = plan.protectedFiles;
            if (plan.status != PurplePlaylistsDownloadsCore.Status.Ready && plan.status != PurplePlaylistsDownloadsCore.Status.Busy)
                return new Result(plan.status == PurplePlaylistsDownloadsCore.Status.Busy ? Status.Busy : Status.Unavailable, 0, protectedFiles, false, false, playlist);
            if (!plan.present) return new Result(Status.Ready, 0, 0, false, false, playlist);
            if (!active && plan.status == PurplePlaylistsDownloadsCore.Status.Ready && plan.candidates().length == 0
                    && plan.intents().length == 0 && !(playlist == null ? plan.cleanupPrune : plan.cleanupSelected))
                return new Result(Status.Ready, 0, protectedFiles, false, false, playlist);
            PurplePlaylistsDownloadsCore.Result marker = PurplePlaylistsDownloadsCore.cleanup(expected.state(), self, playlist, true);
            if (!marker.ready()) return new Result(Status.Unavailable, 0, protectedFiles, false, false, playlist);
            if (!Arrays.equals(marker.local(), expected.state())) {
                PurplePlaylistsStore.Write written = store.write(expected, expected.library(), marker.local(), PurplePlaylistsStore.Kind.LocalEdit);
                stateChanged |= written.stateMayHaveChanged || written.stateCommitted;
                if (!written.ready()) return new Result(Status.Failed, 0, protectedFiles, false, stateChanged, playlist);
                PurplePlaylistsStore.Read actual = store.read();
                if (!actual.ready() || !current.current() || !Arrays.equals(actual.library(), expected.library())
                        || !Arrays.equals(actual.state(), marker.local()))
                    return new Result(Status.Changed, 0, protectedFiles, false, stateChanged, playlist);
                expected = actual;
            }
            if (active || plan.status == PurplePlaylistsDownloadsCore.Status.Busy) return new Result(Status.Busy, 0, protectedFiles, false, stateChanged, playlist);
            for (String token : plan.intents()) {
                PurplePlaylistsStore.Read read = store.read();
                if (!read.ready() || !current.current()) return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
                PurplePlaylistsDownloadsCore.RemovalPlan fresh = PurplePlaylistsDownloadsCore.removal(read.library(), read.state(), self, playlist);
                if (fresh.status != PurplePlaylistsDownloadsCore.Status.Ready
                        || Arrays.stream(fresh.intents()).noneMatch(token::equals))
                    return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
                PurplePlaylistsDownloadsCore.Record record = null;
                for (PurplePlaylistsDownloadsCore.Record item : PurplePlaylistsDownloadsCore.records(read.state(), self)) {
                    if (!item.complete && token.equals(item.token)) {
                        if (record != null) throw new IOException("AmbiguousIntentProof");
                        record = item;
                    }
                }
                if (record == null) throw new IOException("MissingIntentProof");
                PurplePlaylistsStore.Current guard = () -> current.current() && store.stillCurrent(read);
                PurplePlaylistsPhysical media = store.media();
                try { media.confirmMediaPartAbsent(token, guard); }
                catch (IOException error) {
                    if (!"Changed".equals(error.getMessage()) || record.identity.isEmpty()) throw error;
                    media.discardMedia(token, record.identity, guard);
                    filesChanged = true;
                }
                boolean published = false;
                if (!record.sha256.isEmpty()) {
                    try { media.confirmMediaAbsent(record.document, record.extension, guard); }
                    catch (IOException error) {
                        if (!"Changed".equals(error.getMessage())) throw error;
                        media.inspectMedia(record.document, record.extension,
                                new PurplePlaylistsPhysical.MediaFile(record.identity, record.size, record.sha256), guard);
                        published = true;
                    }
                }
                PurplePlaylistsDownloadsCore.Result update = published
                        ? PurplePlaylistsDownloadsCore.complete(read.state(), self, record.playlist, token,
                                record.document, record.extension, record.size, record.identity, record.sha256)
                        : PurplePlaylistsDownloadsCore.forgetIntent(read.state(), self, record.playlist, token,
                                record.document, record.extension, record.size, record.identity, record.sha256);
                if (!update.ready() || !guard.current()) throw new IOException("IntentAuthorityChanged");
                PurplePlaylistsStore.Write written = store.write(read, read.library(), update.local(), PurplePlaylistsStore.Kind.LocalEdit);
                stateChanged |= written.stateMayHaveChanged || written.stateCommitted;
                if (!written.ready()) return new Result(Status.Failed, removed, protectedFiles, filesChanged, stateChanged, playlist);
                PurplePlaylistsStore.Read actual = store.read();
                if (!actual.ready() || !current.current() || !Arrays.equals(actual.library(), read.library())
                        || !Arrays.equals(actual.state(), update.local()))
                    return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
                if (!published) ++removed;
            }
            PurplePlaylistsStore.Read afterIntents = store.read();
            if (!afterIntents.ready() || !current.current()) return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
            plan = PurplePlaylistsDownloadsCore.removal(afterIntents.library(), afterIntents.state(), self, playlist);
            if (plan.status != PurplePlaylistsDownloadsCore.Status.Ready)
                return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
            protectedFiles = plan.protectedFiles;
            for (long document : plan.candidates()) {
                PurplePlaylistsStore.Read read = store.read();
                if (!read.ready() || !current.current()) return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
                PurplePlaylistsDownloadsCore.RemovalPlan fresh = PurplePlaylistsDownloadsCore.removal(read.library(), read.state(), self, playlist);
                if (fresh.status != PurplePlaylistsDownloadsCore.Status.Ready
                        || Arrays.stream(fresh.candidates()).noneMatch(value -> value == document))
                    return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
                PurplePlaylistsDownloadsCore.Record record = null;
                for (PurplePlaylistsDownloadsCore.Record item : PurplePlaylistsDownloadsCore.records(read.state(), self)) {
                    if (item.complete && item.document == document) {
                        if (record != null) throw new IOException("AmbiguousRemovalProof");
                        record = item;
                    }
                }
                if (record == null) throw new IOException("MissingRemovalProof");
                PurplePlaylistsStore.Current guard = () -> current.current() && store.stillCurrent(read);
                PurplePlaylistsPhysical media = store.media();
                try { media.confirmMediaAbsent(document, record.extension, guard); }
                catch (IOException error) {
                    if (!"Changed".equals(error.getMessage())) throw error;
                    PurplePlaylistsPhysical.MediaFile proof = new PurplePlaylistsPhysical.MediaFile(record.identity, record.size, record.sha256);
                    media.removeMedia(document, record.extension, proof, guard);
                    filesChanged = true;
                }
                PurplePlaylistsDownloadsCore.Result update = PurplePlaylistsDownloadsCore.forgetFile(read.state(), self,
                        document, record.extension, record.size, record.identity, record.sha256);
                if (!update.ready() || !guard.current()) throw new IOException("RemovalAuthorityChanged");
                PurplePlaylistsStore.Write written = store.write(read, read.library(), update.local(), PurplePlaylistsStore.Kind.LocalEdit);
                stateChanged |= written.stateMayHaveChanged || written.stateCommitted;
                if (!written.ready()) return new Result(Status.Failed, removed, protectedFiles, filesChanged, stateChanged, playlist);
                PurplePlaylistsStore.Read actual = store.read();
                if (!actual.ready() || !current.current() || !Arrays.equals(actual.library(), read.library())
                        || !Arrays.equals(actual.state(), update.local()))
                    return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
                ++removed;
            }
            PurplePlaylistsStore.Read finalRead = store.read();
            if (!finalRead.ready() || !current.current()) return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
            PurplePlaylistsDownloadsCore.Result cleared = playlist == null
                    ? PurplePlaylistsDownloadsCore.normalizeCleanup(finalRead.library(), finalRead.state(), self)
                    : PurplePlaylistsDownloadsCore.cleanup(finalRead.state(), self, playlist, false);
            if (!cleared.ready()) return new Result(Status.Failed, removed, protectedFiles, filesChanged, stateChanged, playlist);
            PurplePlaylistsStore.Write written = store.write(finalRead, finalRead.library(), cleared.local(), PurplePlaylistsStore.Kind.LocalEdit);
            stateChanged |= written.stateMayHaveChanged || written.stateCommitted;
            if (!written.ready()) return new Result(Status.Failed, removed, protectedFiles, filesChanged, stateChanged, playlist);
            PurplePlaylistsStore.Read confirmed = store.read();
            if (!confirmed.ready() || !current.current() || !Arrays.equals(confirmed.library(), finalRead.library())
                    || !Arrays.equals(confirmed.state(), cleared.local()))
                return new Result(Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
            return new Result(Status.Ready, removed, protectedFiles, filesChanged, stateChanged, playlist);
        } catch (Exception | LinkageError error) {
            filesChanged |= error instanceof PurplePlaylistsPhysical.Failure && ((PurplePlaylistsPhysical.Failure)error).mayHaveChanged;
            return new Result(current.current() ? Status.Failed : Status.Changed, removed, protectedFiles, filesChanged, stateChanged, playlist);
        }
    }
    private PurplePlaylistsRemoval() { throw new AssertionError(); }
}
