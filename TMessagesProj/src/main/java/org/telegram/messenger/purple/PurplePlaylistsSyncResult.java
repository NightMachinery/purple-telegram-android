package org.telegram.messenger.purple;

public final class PurplePlaylistsSyncResult {
    public enum Status { Ready, Disabled, Refused, Offline, Wait, Failed, OutcomeUnknown, Canceled, TimedOut, Busy, Unavailable }
    public final Status status;
    public final String detail, checkpointStatus;
    public final long retryAt;
    public final boolean sendIssued, deleteIssued, libraryCommitted, stateCommitted, libraryMayHaveChanged, stateMayHaveChanged;
    public final int deletedRecords;

    PurplePlaylistsSyncResult(Status status, String detail) {
        this.status = status; this.detail = detail; checkpointStatus = ""; retryAt = 0;
        sendIssued = deleteIssued = libraryCommitted = stateCommitted = libraryMayHaveChanged = stateMayHaveChanged = false;
        deletedRecords = 0;
    }
    PurplePlaylistsSyncResult(PurplePlaylistsSyncActor.Result result) {
        status = Status.valueOf(result.status.name()); detail = result.detail; retryAt = result.retryAt;
        sendIssued = result.sendIssued; deleteIssued = result.deleteIssued;
        libraryCommitted = result.libraryCommitted; stateCommitted = result.stateCommitted;
        libraryMayHaveChanged = result.libraryMayHaveChanged; stateMayHaveChanged = result.stateMayHaveChanged;
        checkpointStatus = result.checkpoint == null || result.checkpoint.status == null ? "" : result.checkpoint.status.name();
        deletedRecords = result.prune == null ? 0 : result.prune.deleted;
    }
    public boolean changed() {
        return libraryCommitted || stateCommitted || libraryMayHaveChanged || stateMayHaveChanged;
    }
}
