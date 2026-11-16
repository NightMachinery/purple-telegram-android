window.PurpleYoutubeIdentity = function (expected, notify) {
    "use strict";
    let verified = false, retired = false, current = "";
    function item(url) {
        try {
            if (typeof url !== "string" || !url) return "";
            const parsed = new URL(url);
            if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.port) return "";
            if (parsed.hostname !== "www.youtube.com" && parsed.hostname !== "youtube.com") return "";
            if (parsed.pathname !== "/watch" || parsed.searchParams.getAll("v").length !== 1) return "";
            const id = parsed.searchParams.get("v");
            return /^[A-Za-z0-9_-]{11}$/.test(id) ? id : "";
        } catch (_) { return ""; }
    }
    function retire() {
        if (retired) return;
        retired = true; verified = false; current = "";
        notify("", true);
    }
    return {
        check() {
            if (retired) return false;
            let url = "";
            try { url = window.player && typeof player.getVideoUrl === "function" ? player.getVideoUrl() : ""; } catch (_) {}
            const actual = item(url);
            if (!actual || actual !== expected) {
                if (verified || url) retire();
                return false;
            }
            current = actual;
            if (!verified) { verified = true; notify(current, false); }
            return true;
        },
        current() { return current; },
        retire: retire,
        retired() { return retired; }
    };
};
