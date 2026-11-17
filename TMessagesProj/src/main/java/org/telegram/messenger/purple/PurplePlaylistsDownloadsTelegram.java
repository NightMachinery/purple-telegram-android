package org.telegram.messenger.purple;

import org.telegram.messenger.AndroidUtilities;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class PurplePlaylistsDownloadsTelegram implements PurplePlaylistsDownloads.Factory {
    private final PurplePlaylistsAccount.Captured account;
    private final ArrayList<PurplePlaylistsTelegram> refreshers = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.function.BooleanSupplier unrestricted;
    PurplePlaylistsDownloadsTelegram(PurplePlaylistsAccount.Captured account, boolean unrestricted) {
        this(account, () -> unrestricted);
    }
    PurplePlaylistsDownloadsTelegram(PurplePlaylistsAccount.Captured account, java.util.function.BooleanSupplier unrestricted) {
        if (account == null || unrestricted == null || !account.current()) throw new IllegalArgumentException("MissingDownloadAccount");
        this.account = account; this.unrestricted = unrestricted;
    }
    @Override public PurplePlaylistsDownloads.Port open(PurplePlaylistsStore.Current current,
            PurplePlaylistsDownloadTransfer.Dispatcher dispatcher) {
        return new PurplePlaylistsDownloadTransfer(account, () -> !closed.get() && current.current(), dispatcher);
    }
    @Override public void refresh(PurplePlaylistsDownloads.Item item, PurplePlaylistsStore.Current current,
            Consumer<PurplePlaylistsDownloadTransfer.Document> done) {
        AndroidUtilities.runOnUIThread(() -> {
            if (closed.get() || !account.current() || !current.current()) return;
            PurplePlaylistsTelegram port = new PurplePlaylistsTelegram(account,
                    () -> !closed.get() && current.current());
            refreshers.add(port);
            try {
                port.read(item.source, reply -> {
                    refreshers.remove(port); port.close();
                    if (closed.get() || !account.current() || !current.current()) return;
                    PurplePlaylistsDownloadTransfer.Document document = null;
                    if (reply != null && reply.failure == null && PurplePlaylistsResolver.qualify(item.song,
                            item.source, reply, account.identity.userId, unrestricted.getAsBoolean()) == PurplePlaylistsResolver.Status.Ready) {
                        try { document = new PurplePlaylistsDownloadTransfer.Document(reply.message.media.document); }
                        catch (IllegalArgumentException ignored) { }
                    }
                    done.accept(document);
                });
            } catch (RuntimeException e) {
                refreshers.remove(port); port.close(); done.accept(null);
            }
        });
    }
    @Override public void cancel() {
        AndroidUtilities.runOnUIThread(() -> {
            for (PurplePlaylistsTelegram port : refreshers) port.close();
            refreshers.clear();
        });
    }
    @Override public void close() {
        if (closed.getAndSet(true)) return;
        cancel();
    }
}
