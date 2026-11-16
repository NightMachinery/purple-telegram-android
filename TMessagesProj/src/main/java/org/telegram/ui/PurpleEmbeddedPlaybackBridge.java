package org.telegram.ui;

import android.net.Uri;
import android.webkit.WebView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebMessageCompat;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class PurpleEmbeddedPlaybackBridge implements PurpleEmbeddedPlaybackPort {
    private static final String OBJECT = "PurpleEmbeddedMedia";
    private final WebView web;
    private final Observer observer;
    private final boolean supported;
    private final Map<String, Frame> frames = new HashMap<>();
    private final Map<Long, Pending> pending = new HashMap<>();
    private final Map<String, State> candidates = new HashMap<>();
    private Classification youtubeClassification = Classification.UNKNOWN;
    private boolean youtubeRetired;
    private ScriptHandler script;
    private long load, nextCommand;
    private String youtube = "";
    private Handle selected;
    private State latest;
    private boolean retired = true;

    private static final class Frame {
        final JavaScriptReplyProxy proxy;
        final String origin;
        final boolean main;
        long sequence;
        Frame(JavaScriptReplyProxy proxy, String origin, boolean main) {
            this.proxy = proxy; this.origin = origin; this.main = main;
        }
    }
    private static final class Pending {
        final Handle handle;
        final long authorization;
        final Reply reply;
        Pending(Handle handle, long authorization, Reply reply) {
            this.handle = handle; this.authorization = authorization; this.reply = reply;
        }
    }

    public PurpleEmbeddedPlaybackBridge(WebView web, Observer observer) {
        this.web = web;
        this.observer = observer;
        supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if (supported) WebViewCompat.addWebMessageListener(web, OBJECT, Collections.singleton("*"),
                (view, message, origin, main, proxy) -> {
                    if (message.getType() == WebMessageCompat.TYPE_STRING) receive(message.getData(), origin, main, proxy);
                });
    }

    public void begin(long load, String youtube, String source, String identitySource) {
        retire();
        this.load = load;
        this.youtube = youtube == null ? "" : youtube;
        retired = false;
        if (!supported) return;
        final String config = "{\"load\":" + load + ",\"youtube\":" + JSONObject.quote(this.youtube) + "}";
        script = WebViewCompat.addDocumentStartJavaScript(web, identitySource + ";(" + source + ")(" + config + ");", Collections.singleton("*"));
    }

    @Override public Capability capability() {
        if (retired || youtubeRetired) return Capability.RETIRED;
        if (!supported) return Capability.FEATURES_UNAVAILABLE;
        if (selected == null) return Capability.SELECTION_UNRESOLVED;
        if (youtubeClassification == Classification.UNKNOWN) return Capability.METADATA_PENDING;
        return youtubeClassification == Classification.LIVE ? Capability.LIVE : Capability.READY;
    }
    @Override public Handle selected() { return selected; }
    @Override public java.util.List<State> candidates() { return new java.util.ArrayList<>(candidates.values()); }
    public void youtubeMetadata(Classification classification) { if (!youtubeRetired) youtubeClassification = classification; }
    public void retireYoutubeIdentity() {
        youtubeRetired = true; youtubeClassification = Classification.UNKNOWN;
        selected = null; latest = null; pending.clear();
    }

    private void receive(String raw, Uri origin, boolean main, JavaScriptReplyProxy proxy) {
        if (retired || raw == null || raw.length() > 8192) return;
        try {
            final JSONObject message = new JSONObject(raw);
            if (message.optLong("load", -1) != load) return;
            final String frameId = message.optString("frame");
            if (frameId.length() < 8 || frameId.length() > 128) return;
            Frame frame = frames.get(frameId);
            if (frame == null) {
                if (!"hello".equals(message.optString("type")) || frames.size() >= 64) return;
                frame = new Frame(proxy, origin.toString(), main);
                frames.put(frameId, frame);
            }
            if (frame.proxy != proxy || !frame.origin.equals(origin.toString()) || frame.main != main) return;
            final long sequence = message.optLong("sequence", -1);
            if (sequence <= frame.sequence) return;
            frame.sequence = sequence;
            final String type = message.optString("type");
            if ("retire".equals(type)) {
                frames.remove(frameId);
                candidates.entrySet().removeIf(entry -> entry.getValue().handle.frame.equals(frameId));
                if (selected != null && selected.frame.equals(frameId)) {
                    retireYoutubeIdentity(); observer.identity("", true); observer.changed();
                }
                return;
            }
            if ("identityLost".equals(type) && main && "https".equals(origin.getScheme())
                    && "messenger.telegram.org".equals(origin.getHost()) && (origin.getPort() == -1 || origin.getPort() == 443)) {
                retireYoutubeIdentity();
                observer.identity("", true);
                return;
            }
            if (youtubeRetired) return;
            if ("metadata".equals(type)) {
                final String host = origin.getHost();
                if (!"https".equals(origin.getScheme()) || (origin.getPort() != -1 && origin.getPort() != 443)
                        || !("www.youtube.com".equals(host)
                        || "www.youtube-nocookie.com".equals(host)) || !youtube.equals(message.optString("providerItem"))) return;
                if (!(message.opt("live") instanceof Boolean)) return;
                observer.metadata(load, youtube, message.getBoolean("live") ? Classification.LIVE : Classification.FINITE,
                        Evidence.YOUTUBE_PLAYER_RESPONSE);
                return;
            }
            final boolean wrapper = "YOUTUBE_WRAPPER".equals(message.optString("provider"));
            if (wrapper && (!main || !"https".equals(origin.getScheme())
                    || !"messenger.telegram.org".equals(origin.getHost()) || (origin.getPort() != -1 && origin.getPort() != 443)
                    || youtube.isEmpty() || !youtube.equals(message.optString("providerItem")))) return;
            final Handle handle = new Handle(load, frameId, message.optLong("media", -1),
                    message.optLong("incarnation", -1), wrapper ? Provider.YOUTUBE_WRAPPER : Provider.HTML_MEDIA,
                    message.optString("providerItem"));
            if (handle.media < 0 || handle.incarnation < 1) return;
            final Classification classification = wrapper ? youtubeClassification
                    : Classification.valueOf(message.optString("classification", "UNKNOWN"));
            final Evidence evidence = wrapper && youtubeClassification != Classification.UNKNOWN
                    ? Evidence.YOUTUBE_PLAYER_RESPONSE : Evidence.valueOf(message.optString("evidence", "NONE"));
            if (classification == Classification.FINITE && evidence != Evidence.OBSERVED_IMMUTABLE_BLOB && !wrapper) return;
            final State state = new State(handle, classification, evidence, message.optBoolean("loaded"),
                    message.optBoolean("paused"), message.optBoolean("ended"), message.optBoolean("looping"),
                    message.optBoolean("blocked"), message.optBoolean("endFence"), message.optLong("authorization", -1), message.optInt("transport", -99));
            final String candidateKey = frameId + ":" + handle.media;
            if ("removed".equals(type)) candidates.remove(candidateKey);
            else if (candidates.size() < 128 || candidates.containsKey(candidateKey)) candidates.put(candidateKey, state);
            if (wrapper && "selected".equals(type)) {
                selected = handle; latest = state; observer.identity(handle.providerItem, false); observer.changed();
            }
            if (!handle.same(selected)) return;
            latest = state;
            if ("end".equals(type)) observer.ended(state);
            final Pending request = pending.remove(message.optLong("command", -1));
            if (request != null && request.handle.same(handle) && request.authorization == state.authorization)
                request.reply.accept(state);
        } catch (JSONException | IllegalArgumentException ignored) { }
    }

    private void command(Handle handle, long authorization, String operation, boolean blocked, boolean endFence, Reply reply) {
        if (retired || handle == null || !handle.same(selected)) return;
        final Frame frame = frames.get(handle.frame);
        if (frame == null) return;
        if (("pause".equals(operation) || "arm".equals(operation) || blocked)
                && youtubeClassification != Classification.FINITE) return;
        final long id = ++nextCommand;
        if (reply != null) {
            if (pending.size() >= 16) pending.clear();
            pending.put(id, new Pending(handle, authorization, reply));
        }
        try {
            final JSONObject message = new JSONObject().put("load", load).put("frame", handle.frame)
                    .put("media", handle.media).put("incarnation", handle.incarnation)
                    .put("providerItem", handle.providerItem).put("command", id).put("authorization", authorization)
                    .put("operation", operation).put("blocked", blocked).put("endFence", endFence);
            frame.proxy.postMessage(message.toString());
        } catch (JSONException | RuntimeException ignored) {
            pending.remove(id);
            frames.remove(handle.frame);
            selected = null; latest = null;
            observer.changed();
        }
    }
    @Override public void queryState(Handle handle, long authorization, Reply reply) {
        command(handle, authorization, "query", false, false, reply);
    }
    @Override public void installEndFence(Handle handle, long authorization, Reply reply) {
        command(handle, authorization, "arm", false, true, reply);
    }
    @Override public void setExpiryFence(Handle handle, long authorization, boolean blocked, boolean endFence) {
        command(handle, authorization, "fence", blocked, endFence, null);
    }
    @Override public void pauseLocal(Handle handle, long authorization) {
        command(handle, authorization, "pause", true, false, null);
    }
    @Override public void explicitPlay(Handle handle, long authorization) {
        command(handle, authorization, "explicitPlay", false, false, null);
    }
    @Override public void retire() {
        if (script != null) { script.remove(); script = null; }
        frames.clear(); pending.clear(); candidates.clear(); selected = null; latest = null; retired = true;
        youtubeClassification = Classification.UNKNOWN;
        youtubeRetired = false;
    }
    public void destroy() {
        retire();
        if (supported) WebViewCompat.removeWebMessageListener(web, OBJECT);
    }
}
