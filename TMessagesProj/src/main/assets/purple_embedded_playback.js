function (config) {
    "use strict";
    const bridge = window.PurpleEmbeddedMedia;
    if (!bridge || typeof bridge.postMessage !== "function") return;
    const frame = Array.from(crypto.getRandomValues(new Uint32Array(4)), n => n.toString(16)).join("-");
    let sequence = 0, nextMedia = 0, authorization = 0, retired = false;
    const records = new WeakMap(), active = new Map(), constructed = new WeakSet(), urls = new Map();
    const nativePause = HTMLMediaElement.prototype.pause, nativePlay = HTMLMediaElement.prototype.play;
    const wrapper = window === window.top && location.origin === "https://messenger.telegram.org" && !!config.youtube;
    let selected = wrapper ? {id: 0, incarnation: 1, provider: "YOUTUBE_WRAPPER", item: config.youtube,
        blocked: false, endFence: false, latch: false} : null;
    const youtubeIdentity = wrapper ? PurpleYoutubeIdentity(config.youtube, (item, lost) => {
        if (lost) {
            send("identityLost", {providerItem: config.youtube, provider: "YOUTUBE_WRAPPER"});
            selected = null;
            if (window.PurpleSleepPlayback) PurpleSleepPlayback.retireIdentity();
        }
    }) : null;
    let announcedIdentity = false;
    function wrapperCurrent() {
        if (!youtubeIdentity || !youtubeIdentity.check()) return false;
        if (!announcedIdentity && selected) {
            announcedIdentity = true;
            report("selected", selected);
        }
        return !!selected;
    }
    function send(type, extra) {
        if (!retired) bridge.postMessage(JSON.stringify(Object.assign({load: config.load, frame: frame,
            sequence: ++sequence, type: type}, extra)));
    }
    function identity(record) {
        return {media: record.id, incarnation: record.incarnation, provider: record.provider, providerItem: record.item};
    }
    function pause(record) {
        if (record.provider === "YOUTUBE_WRAPPER") {
            if (!wrapperCurrent()) return;
            if (window.player && typeof player.pauseVideo === "function" && player.getPlayerState() !== -1) player.pauseVideo();
        } else if (record.element.networkState !== HTMLMediaElement.NETWORK_EMPTY && !record.element.paused) {
            nativePause.call(record.element);
        }
    }
    function snapshot(record) {
        if (!record) return null;
        if (record.provider === "YOUTUBE_WRAPPER") {
            if (!youtubeIdentity.check()) return null;
            const state = window.player && typeof player.getPlayerState === "function" ? player.getPlayerState() : -99;
            return Object.assign(identity(record), {classification: "UNKNOWN", evidence: "NONE", loaded: state >= 0,
                paused: state !== 1 && state !== 3, ended: state === 0 || record.latch, looping: false,
                blocked: record.blocked, endFence: record.endFence, authorization: authorization, transport: record.latch ? 0 : state});
        }
        const media = record.element;
        const immutable = media.srcObject ? constructed.has(media.srcObject) : urls.has(media.currentSrc);
        return Object.assign(identity(record), {classification: immutable ? "FINITE" : "UNKNOWN",
            evidence: immutable ? "OBSERVED_IMMUTABLE_BLOB" : "NONE",
            loaded: media.networkState !== HTMLMediaElement.NETWORK_EMPTY, paused: media.paused,
            ended: media.ended || record.latch, looping: media.loop, blocked: record.blocked,
            endFence: record.endFence, authorization: authorization});
    }
    function report(type, record, extra) {
        const state = snapshot(record);
        if (state) send(type, Object.assign(state, extra));
    }
    function observe(media) {
        if (!(media instanceof HTMLMediaElement)) return null;
        let record = records.get(media);
        if (!record) {
            if (active.size >= 128) return null;
            record = {id: ++nextMedia, incarnation: 1, provider: "HTML_MEDIA", item: "",
                element: media, blocked: false, endFence: false, latch: false, source: media.currentSrc, object: media.srcObject};
            records.set(media, record); active.set(record.id, record); report("candidate", record);
        } else if (!active.has(record.id)) {
            if (active.size >= 128) return null;
            record.incarnation++; active.set(record.id, record); report("candidate", record);
        }
        return record;
    }
    function lifecycle(event) {
        const record = observe(event.target);
        if (!record) return;
        if (event.type === "emptied" || event.type === "loadstart" || record.source !== event.target.currentSrc
                || record.object !== event.target.srcObject) {
            record.incarnation++; record.source = event.target.currentSrc; record.object = event.target.srcObject;
            record.latch = false;
            if (record === selected) { selected = null; send("selectionLost", identity(record)); }
        }
        if (record === selected && event.type === "ended" && event.target.ended && record.endFence && !event.target.loop) {
            record.blocked = true; record.latch = true;
            event.stopImmediatePropagation(); pause(record); report("end", record); return;
        }
        if (record === selected && record.blocked && ["play", "playing", "canplay", "loadedmetadata"].includes(event.type)) {
            pause(record); event.stopImmediatePropagation();
        }
        report("state", record);
    }
    ["loadstart", "emptied", "loadedmetadata", "canplay", "play", "playing", "pause", "ended", "abort", "error", "durationchange"].forEach(
        name => window.addEventListener(name, lifecycle, true));
    HTMLMediaElement.prototype.play = function () {
        const record = records.get(this);
        if (record && record === selected && record.blocked) return Promise.reject(new DOMException("Playback paused", "AbortError"));
        return nativePlay.apply(this, arguments);
    };
    for (const name of ["Blob", "File"]) {
        const Original = window[name];
        if (typeof Original === "function") window[name] = new Proxy(Original, {construct(target, args, newTarget) {
            const object = Reflect.construct(target, args, newTarget); constructed.add(object); return object;
        }});
    }
    const createObjectURL = URL.createObjectURL, revokeObjectURL = URL.revokeObjectURL;
    URL.createObjectURL = function (object) {
        const url = createObjectURL.apply(this, arguments);
        if (constructed.has(object)) urls.set(url, object);
        return url;
    };
    URL.revokeObjectURL = function (url) { urls.delete(String(url)); return revokeObjectURL.apply(this, arguments); };
    function scan(root) {
        if (root instanceof HTMLMediaElement) observe(root);
        if (root.querySelectorAll) root.querySelectorAll("video,audio").forEach(observe);
    }
    new MutationObserver(changes => {
        changes.forEach(change => change.addedNodes.forEach(scan));
        active.forEach((record, id) => {
            if (!record.element.isConnected) { active.delete(id); report("removed", record); if (record === selected) selected = null; }
        });
    }).observe(document, {childList: true, subtree: true});
    scan(document);

    const youtubeFrame = (location.hostname === "www.youtube.com" || location.hostname === "www.youtube-nocookie.com")
        && (location.port === "" || location.port === "443") && location.protocol === "https:"
        && location.pathname === "/embed/" + config.youtube && !!config.youtube;
    function metadata(value) {
        const details = value && value.videoDetails;
        if (youtubeFrame && details && details.videoId === config.youtube && typeof details.isLiveContent === "boolean") {
            send("metadata", {providerItem: details.videoId, live: details.isLiveContent});
        }
    }
    function metadataURL(value) {
        try {
            const url = new URL(value, location.href);
            return youtubeFrame && url.origin === location.origin && url.pathname === "/youtubei/v1/player";
        } catch (_) { return false; }
    }
    if (youtubeFrame) {
        const descriptor = Object.getOwnPropertyDescriptor(window, "ytInitialPlayerResponse");
        if (!descriptor || (descriptor.configurable && "value" in descriptor)) {
            let value = descriptor && descriptor.value;
            metadata(value);
            Object.defineProperty(window, "ytInitialPlayerResponse", {configurable: true, enumerable: true,
                get() { return value; }, set(next) { value = next; metadata(next); }});
        }
        if (typeof window.fetch === "function") {
            const originalFetch = window.fetch;
            window.fetch = function (input) {
                const result = originalFetch.apply(this, arguments);
                if (metadataURL(typeof input === "string" || input instanceof URL ? String(input) : input instanceof Request ? input.url : null)) {
                    result.then(response => {
                        const length = Number(response.headers.get("content-length"));
                        if (length > 1048576) return;
                        const copy = response.clone();
                        if (!copy.body || !copy.body.getReader) return;
                        const reader = copy.body.getReader(), decoder = new TextDecoder();
                        let size = 0, text = "";
                        function read() {
                            return reader.read().then(part => {
                                if (part.done) { text += decoder.decode(); try { metadata(JSON.parse(text)); } catch (_) {} return; }
                                size += part.value.byteLength;
                                if (size > 1048576) { reader.cancel().catch(() => {}); return; }
                                text += decoder.decode(part.value, {stream: true}); return read();
                            });
                        }
                        read().catch(() => {});
                    }).catch(() => {});
                }
                return result;
            };
        }
        const opened = new WeakMap(), open = XMLHttpRequest.prototype.open, sendXHR = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function (method, url) {
            const result = open.apply(this, arguments); opened.set(this, (typeof url === "string" || url instanceof URL) && metadataURL(url)); return result;
        };
        XMLHttpRequest.prototype.send = function () {
            if (opened.get(this)) this.addEventListener("load", () => {
                try {
                    if (this.responseType === "json") metadata(this.response);
                    else if (!this.responseType || this.responseType === "text") {
                        const text = this.responseText;
                        if (text.length <= 1048576) metadata(JSON.parse(text));
                    }
                } catch (_) {}
            }, {once: true});
            return sendXHR.apply(this, arguments);
        };
    }
    bridge.onmessage = function (event) {
        let command;
        try { command = JSON.parse(event.data); } catch (_) { return; }
        if (wrapper && !wrapperCurrent()) return;
        if (retired || !selected || command.load !== config.load || command.frame !== frame
                || command.media !== selected.id || command.incarnation !== selected.incarnation
                || command.providerItem !== selected.item || command.authorization < authorization) return;
        authorization = command.authorization;
        if (command.operation === "fence") {
            selected.endFence = command.endFence;
            selected.blocked = command.blocked || selected.latch && selected.endFence;
            if (!selected.blocked && !selected.endFence) selected.latch = false;
            if (window.PurpleSleepPlayback && wrapper) PurpleSleepPlayback.fence(selected.blocked, selected.endFence);
            if (selected.blocked) pause(selected);
        } else if (command.operation === "explicitPlay") {
            selected.blocked = false; selected.latch = false;
            if (window.PurpleSleepPlayback && wrapper) {
                PurpleSleepPlayback.endLatch = false;
                PurpleSleepPlayback.fence(false, selected.endFence);
            }
        } else if (command.operation === "arm") {
            const state = snapshot(selected);
            if (state && state.loaded && !state.ended && !state.looping && selected) selected.endFence = true;
        } else if (command.operation === "pause") {
            selected.blocked = true; pause(selected);
        } else if (command.operation !== "query") return;
        report("ack", selected, {command: command.command});
    };
    if (wrapper) window.PurpleEmbeddedPlayback = {
        beforeState(state) {
            if (!wrapperCurrent()) return false;
            if (state === 0 && selected.endFence && window.player && player.getPlayerState() === 0) {
                selected.blocked = true; selected.latch = true;
                if (window.PurpleSleepPlayback) PurpleSleepPlayback.fence(true);
                pause(selected); report("end", selected); return true;
            }
            if (selected.blocked && (state === 1 || state === 3)) { pause(selected); return true; }
            report("state", selected); return false;
        }
    };
    window.addEventListener("pagehide", () => { send("retire", {}); retired = true; }, {once: true});
    send("hello", {});
    if (wrapper) wrapperCurrent();
}
