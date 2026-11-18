package org.telegram.messenger.purple;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.LruCache;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.audioinfo.AudioInfo;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.function.Consumer;

public final class PurplePlaylistsArtwork implements AutoCloseable {
    private static final int SOURCE_TIMEOUT_MS = 8000;

    private final PurplePlaylistsAccount.Captured account;
    private final ArrayDeque<Request> waiting = new ArrayDeque<>();
    private final LruCache<String, MessageObject> cached = new LruCache<>(32);
    private Request active;
    private long retryAt;
    private boolean closed;

    private final class Request {
        final PurplePlaylistsView.Song song;
        final Consumer<MessageObject> listener;
        final Runnable timeout = () -> cancel(this);
        PurplePlaylistsResolver resolver;

        Request(PurplePlaylistsView.Song song, Consumer<MessageObject> listener) {
            this.song = song;
            this.listener = listener;
        }
    }

    private PurplePlaylistsArtwork(PurplePlaylistsAccount.Captured account) {
        this.account = account;
    }

    public static PurplePlaylistsArtwork open(int account) {
        PurplePlaylistsAccount.Captured captured = UserConfig.selectedAccount == account
                ? PurplePlaylistsAccount.capture(account) : null;
        return captured == null ? null : new PurplePlaylistsArtwork(captured);
    }

    private boolean current() {
        return !closed && UserConfig.selectedAccount == account.identity.slot && account.current();
    }

    public Bitmap playingCover(PurplePlaylistsView.Song song) {
        if (!current()) return null;
        MediaController media = MediaController.getInstance();
        MessageObject message = media.getPlayingMessageObject();
        if (message == null || message.currentAccount != account.identity.slot
                || message.getDocument() == null || !song.documentId.equals(Long.toUnsignedString(message.getDocument().id))) return null;
        AudioInfo audio = media.getAudioInfo();
        return audio == null ? null : audio.getSmallCover();
    }

    public Runnable load(PurplePlaylistsView.Song song, Consumer<MessageObject> listener) {
        if (!current()) return null;
        MessageObject hit = cached.get(song.documentId);
        if (hit != null) {
            listener.accept(hit);
            return null;
        }
        if (SystemClock.elapsedRealtime() < retryAt) return null;
        Request request = new Request(song, listener);
        waiting.addLast(request);
        if (active == null) next();
        return () -> cancel(request);
    }

    private void next() {
        if (!current() || SystemClock.elapsedRealtime() < retryAt) {
            waiting.clear();
            return;
        }
        while ((active = waiting.pollFirst()) != null) {
            Request request = active;
            MessageObject hit = cached.get(request.song.documentId);
            if (hit != null) {
                active = null;
                request.listener.accept(hit);
                continue;
            }
            request.resolver = new PurplePlaylistsResolver(new PurplePlaylistsTelegram(account, this::current),
                    account.identity.userId, PurpleCopyPolicy.unrestricted(), Collections.singletonList(request.song),
                    results -> done(request, results.get(0)));
            AndroidUtilities.runOnUIThread(request.timeout, SOURCE_TIMEOUT_MS);
            request.resolver.start();
            return;
        }
    }

    private void done(Request request, PurplePlaylistsResolver.Result result) {
        if (active != request) return;
        if (result.status == PurplePlaylistsResolver.Status.Wait) {
            retryAt = SystemClock.elapsedRealtime() + result.waitSeconds * 1000L;
        } else if (current() && result.playable() && result.message != null) {
            MessageObject message = new MessageObject(account.identity.slot, result.message, false, false);
            cached.put(request.song.documentId, message);
            request.listener.accept(message);
        }
        cancel(request);
    }

    private void cancel(Request request) {
        if (active != request) {
            waiting.remove(request);
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(request.timeout);
        request.resolver.close();
        active = null;
        next();
    }

    @Override public void close() {
        closed = true;
        if (active != null) cancel(active);
        waiting.clear();
        cached.evictAll();
    }
}
