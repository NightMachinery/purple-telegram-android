package org.telegram.messenger.purple;
final class PurplePlaylistsRetained {
    static AutoCloseable prepare(PurplePlaylistsAccount.Captured account, String playlist,
            PurplePlaylistsResolver.Result result, java.util.function.BooleanSupplier scope,
            java.util.function.Consumer<PurplePlaylistsResolver.Result> done) {
        if (scope.getAsBoolean()) done.accept(result);
        return () -> {};
    }
}
