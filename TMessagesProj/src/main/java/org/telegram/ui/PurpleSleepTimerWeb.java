package org.telegram.ui;

import android.content.Context;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.purple.PurpleSleepTimer;
import org.telegram.messenger.purple.PurpleSleepTimerRules;
import org.telegram.ui.Components.PhotoViewerWebView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

public final class PurpleSleepTimerWeb {
    private static final AtomicLong NEXT_LOAD = new AtomicLong();
    private final PhotoViewerWebView view;
    private final PhotoViewer viewer;
    private final PurpleSleepTimer timer = PurpleSleepTimer.get();
    private volatile long load;
    private volatile PurpleSleepTimer.BackendReceipt receipt;
    private boolean finite, ended, knownLive, identityVerified, identityRetired;
    private PurpleEmbeddedPlaybackBridge embedded;
    private long authorization;
    private boolean endArmPending;

    public void install(android.webkit.WebView web) {
        embedded = new PurpleEmbeddedPlaybackBridge(web, new PurpleEmbeddedPlaybackPort.Observer() {
            @Override public void identity(String item, boolean retired) { PurpleSleepTimerWeb.this.identity(load, item, retired); }
            @Override public void changed() { viewer.purpleSleepRefresh(); }
            @Override public void ended(PurpleEmbeddedPlaybackPort.State state) {
                final PurpleSleepTimer.BackendReceipt captured = receipt;
                if (state.authorization != authorization || captured == null || !timer.validBackend(captured)) return;
                ended = state.ended;
                if (state.ended) timer.realBackendEnd(captured, true);
            }
            @Override public void metadata(long observedLoad, String item, PurpleEmbeddedPlaybackPort.Classification classification,
                    PurpleEmbeddedPlaybackPort.Evidence evidence) {
                if (observedLoad != load || !view.purpleSleepYoutubeId().equals(item)
                        || evidence != PurpleEmbeddedPlaybackPort.Evidence.YOUTUBE_PLAYER_RESPONSE) return;
                applyClassification(classification);
            }
        });
    }
    public void navigate(String youtube) {
        if (embedded != null) embedded.begin(load, youtube, asset(view.getContext(), "purple_embedded_playback.js"), asset(view.getContext(), "purple_youtube_identity.js"));
    }
    public PurpleEmbeddedPlaybackPort.Capability embeddedCapability() {
        return embedded == null ? PurpleEmbeddedPlaybackPort.Capability.FEATURES_UNAVAILABLE : embedded.capability();
    }
    public void identity(long observedLoad, String item, boolean retired) {
        AndroidUtilities.runOnUIThread(() -> {
            if (observedLoad != load || identityRetired) return;
            if (retired || !view.purpleSleepYoutubeId().equals(item) || item.isEmpty()) {
                identityRetired = true; identityVerified = false;
                finite = knownLive = ended = endArmPending = false;
                receipt = null; authorization++;
                if (embedded != null) embedded.retireYoutubeIdentity();
            } else identityVerified = true;
            viewer.purpleSleepRefresh();
        });
    }
    private void applyClassification(PurpleEmbeddedPlaybackPort.Classification classification) {
        if (identityRetired) return;
        finite = classification == PurpleEmbeddedPlaybackPort.Classification.FINITE;
        knownLive = classification == PurpleEmbeddedPlaybackPort.Classification.LIVE;
        if (embedded != null) embedded.youtubeMetadata(classification);
        viewer.purpleSleepRefresh();
    }

    public PurpleSleepTimerWeb(PhotoViewerWebView view, PhotoViewer viewer) {
        this.view = view;
        this.viewer = viewer;
    }
    public void begin() {
        load = NEXT_LOAD.incrementAndGet();
        receipt = null;
        finite = ended = knownLive = identityVerified = identityRetired = false;
        endArmPending = false;
        authorization++;
    }
    public long load() { return load; }
    public boolean finite() { return finite && identityVerified && !identityRetired && view.isControllable(); }
    public boolean ended() { return ended; }
    public PurpleSleepTimer.BackendReceipt receipt() { return receipt; }

    public void observeResponse(long observedLoad, JSONObject response) {
        final JSONObject details = response.optJSONObject("videoDetails");
        if (details == null || !(details.opt("isLiveContent") instanceof Boolean)) return;
        final String providerItem = details.optString("videoId");
        final boolean live = details.optBoolean("isLiveContent", true);
        AndroidUtilities.runOnUIThread(() -> {
            if (observedLoad != load || providerItem.isEmpty() || !providerItem.equals(view.purpleSleepYoutubeId())) return;
            applyClassification(live ? PurpleEmbeddedPlaybackPort.Classification.LIVE : PurpleEmbeddedPlaybackPort.Classification.FINITE);
        });
    }

    public int unavailableText() {
        return !view.isControllable() ? org.telegram.messenger.R.string.PurpleSleepEmbedUnavailable
                : knownLive ? org.telegram.messenger.R.string.PurpleSleepLiveUnavailable
                : org.telegram.messenger.R.string.PurpleSleepEmbedPending;
    }
    public void unregister() {
        receipt = null;
        authorization++;
        endArmPending = false;
        if (embedded != null && embedded.selected() != null)
            embedded.setExpiryFence(embedded.selected(), authorization, false, false);
        else view.getWebView().evaluateJavascript("window.PurpleSleepPlayback && PurpleSleepPlayback.fence(false, false);", null);
    }
    public void explicitPlay() {
        if (receipt == null || !timer.startBackend(receipt)) return;
        if (embedded != null && embedded.selected() != null) embedded.explicitPlay(embedded.selected(), authorization);
        else view.getWebView().evaluateJavascript("window.PurpleSleepPlayback && PurpleSleepPlayback.explicitPlay();", null);
    }

    public void refresh() {
        receipt = timer.captureBackend(view);
        authorization++;
        endArmPending = false;
        if (receipt != null) {
            setFence(timer.blocked(PurpleSleepTimerRules.Slot.VIEWER));
            if (embedded != null && embedded.selected() != null && timer.endBound(PurpleSleepTimerRules.Slot.VIEWER)) {
                final long capturedAuthorization = authorization;
                final PurpleSleepTimer.BackendReceipt captured = receipt;
                embedded.queryState(embedded.selected(), capturedAuthorization, state -> {
                    if (capturedAuthorization != authorization || !timer.validBackend(captured)) return;
                    ended = state.ended;
                    if (state.ended && state.endFence) timer.realBackendEnd(captured, true);
                });
            } else if (timer.endBound(PurpleSleepTimerRules.Slot.VIEWER)) {
                final long capturedAuthorization = authorization;
                final PurpleSleepTimer.BackendReceipt captured = receipt;
                view.getWebView().evaluateJavascript("window.PurpleSleepPlayback ? PurpleSleepPlayback.actualState() : -99", value -> {
                    if (capturedAuthorization != authorization || !timer.validBackend(captured)) return;
                    if ("0".equals(value)) { ended = true; timer.realBackendEnd(captured, true); }
                });
            }
        }
    }
    public boolean allow(PurpleSleepTimer.BackendReceipt captured) {
        return captured == null || timer.startBackend(captured);
    }
    public boolean beforePlay() { return allow(receipt); }
    public boolean pauseLocal() {
        if (receipt != null) {
            setFence(true);
            if (embedded != null && embedded.selected() != null) {
                embedded.pauseLocal(embedded.selected(), authorization);
                return true;
            }
        }
        return view.isYouTube();
    }
    private void setFence(boolean blocked) {
        if (embedded != null && embedded.selected() != null) {
            embedded.setExpiryFence(embedded.selected(), authorization, blocked,
                    endArmPending || timer.endBound(PurpleSleepTimerRules.Slot.VIEWER));
            return;
        }
        view.getWebView().evaluateJavascript("window.PurpleSleepPlayback && PurpleSleepPlayback.fence(" + blocked + ","
                + (endArmPending || timer.endBound(PurpleSleepTimerRules.Slot.VIEWER)) + ");", null);
    }
    public void release() {
        receipt = null;
        authorization++;
        endArmPending = false;
        if (embedded != null) embedded.destroy();
        load = NEXT_LOAD.incrementAndGet();
        viewer.purpleSleepWebClosed(view);
    }

    public void requestEnd() { timer.startEnd(PurpleSleepTimerRules.Slot.VIEWER); }
    public void prepareEnd(Runnable accept) {
        final PurpleSleepTimer.BackendReceipt captured = receipt;
        if (captured == null || !timer.validBackend(captured)) return;
        endArmPending = true;
        if (embedded != null && embedded.selected() != null) {
            final long capturedAuthorization = authorization;
            endArmPending = true;
            embedded.installEndFence(embedded.selected(), capturedAuthorization, state -> {
                if (capturedAuthorization != authorization || !timer.validBackend(captured)) return;
                ended = state.ended;
                if (!state.endFence || !state.canEnd()) {
                    endArmPending = false;
                    setFence(timer.blocked(PurpleSleepTimerRules.Slot.VIEWER));
                    return;
                }
                accept.run();
                endArmPending = false;
                setFence(timer.blocked(PurpleSleepTimerRules.Slot.VIEWER));
            });
            return;
        }
        view.getWebView().evaluateJavascript("window.PurpleSleepPlayback ? PurpleSleepPlayback.armEnd() : -99", value -> {
            if (!timer.validBackend(captured)) return;
            final int actual;
            try { actual = Integer.parseInt(value); } catch (RuntimeException ignored) { return; }
            if (actual <= 0) {
                endArmPending = false;
                setFence(timer.blocked(PurpleSleepTimerRules.Slot.VIEWER));
                return;
            }
            ended = false;
            accept.run();
            endArmPending = false;
            setFence(timer.blocked(PurpleSleepTimerRules.Slot.VIEWER));
        });
    }

    private void applyState(PurpleSleepTimer.BackendReceipt captured, String rawState, int actual, Runnable stockApply) {
        if (!timer.validBackend(captured) || !Integer.toString(actual).equals(rawState)) return;
        ended = actual == 0;
        if (ended && endArmPending && !timer.endBound(PurpleSleepTimerRules.Slot.VIEWER)) return;
        if (timer.blocked(PurpleSleepTimerRules.Slot.VIEWER)) {
            if (actual == 1 || actual == 3) view.purpleSleepPauseLocal();
            else if (actual == 2) stockApply.run();
            return;
        }
        if (ended && timer.realBackendEnd(captured, true)) return;
        stockApply.run();
    }
    public void state(long observedLoad, String rawState, Runnable stockApply) {
        if (observedLoad != load) return;
        final PurpleSleepTimer.BackendReceipt captured = receipt;
        if (captured == null) { stockApply.run(); return; }
        AndroidUtilities.runOnUIThread(() -> {
            if (observedLoad != load || !timer.validBackend(captured)) return;
            if (embedded != null && embedded.selected() != null) {
                final long capturedAuthorization = authorization;
                embedded.queryState(embedded.selected(), capturedAuthorization, state -> {
                    if (observedLoad == load && capturedAuthorization == authorization)
                        applyState(captured, rawState, state.transport, stockApply);
                });
                return;
            }
            view.getWebView().evaluateJavascript("window.PurpleSleepPlayback ? PurpleSleepPlayback.actualState() : -99", value -> {
                if (observedLoad != load || !timer.validBackend(captured)) return;
                final int actual;
                try { actual = Integer.parseInt(value); } catch (RuntimeException ignored) { return; }
                applyState(captured, rawState, actual, stockApply);
            });
        });
    }

    private static String asset(Context context, String name) {
        try (InputStream input = context.getAssets().open(name)) {
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    public static String html(Context context, String source) {
        return source.replace("<head>", "<head><script>" + asset(context, "purple_youtube_identity.js") + ";" + asset(context, "purple_sleep_timer.js") + "</script>");
    }
}
