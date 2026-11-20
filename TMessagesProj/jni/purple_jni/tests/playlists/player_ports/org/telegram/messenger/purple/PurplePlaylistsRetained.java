package org.telegram.messenger.purple;

import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

final class PurplePlaylistsRetained {
    static TLRPC.Message offlineMessage;
    static boolean fileAvailable;
    static int offlineResolutions;

    static AutoCloseable prepareOffline(PurplePlaylistsAccount.Captured account, String playlist,
            PurplePlaylistsView.Song song, BooleanSupplier scope,
            Consumer<PurplePlaylistsResolver.Result> done) {
        ++offlineResolutions;
        if (scope.getAsBoolean()) {
            if (offlineMessage == null) throw new AssertionError("Offline fixture message missing");
            done.accept(PurplePlaylistsResolver.Result.offline(song, song.source, offlineMessage, false));
        }
        return () -> {};
    }

    static File path(int account, TLObject attachment, boolean forceCache) {
        return !forceCache && fileAvailable && offlineMessage != null
                && offlineMessage.media.document == attachment ? new File("fixture-retained-audio") : null;
    }


    static AutoCloseable prepare(PurplePlaylistsAccount.Captured account, String playlist,
            PurplePlaylistsResolver.Result result, java.util.function.BooleanSupplier scope,
            java.util.function.Consumer<PurplePlaylistsResolver.Result> done) {
        if (scope.getAsBoolean()) done.accept(result);
        return () -> {};
    }
}
