package org.telegram.messenger.purple;
public final class PurplePlaylistsDownloadControls {
 public enum Status { Idle, Canceling }
 public static final class State { public boolean canRetry, canCancel; public Status status = Status.Idle; }
 static final PurplePlaylistsDownloadControls INSTANCE = new PurplePlaylistsDownloadControls();
 static int starts; static boolean accept;
 static PurplePlaylistsDownloadControls acquire(PurplePlaylistsAccount.Captured account, PurplePlaylistsWorker worker) { return INSTANCE; }
 public State snapshot() { return new State(); }
 boolean start(PurplePlaylistsWorker.Snapshot snapshot, State expected) { ++starts; return accept; }
}
