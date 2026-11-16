window.PurpleSleepPlayback = {
    identity: null,
    bind: function (expected) {
        this.identity = PurpleYoutubeIdentity(expected, function (item, retired) {
            if (window.YoutubeProxy && YoutubeProxy.purpleSleepIdentity) YoutubeProxy.purpleSleepIdentity(item, retired);
        });
    },
    currentItem: function () { return this.identity && this.identity.check() ? this.identity.current() : ""; },
    retireIdentity: function () {
        if (this.identity) this.identity.retire();
        this.blocked = this.endFence = this.endLatch = false;
    },
    blocked: false,
    endFence: false,
    endLatch: false,
    allowPlay: function () { return this.currentItem() ? !this.blocked : true; },
    explicitPlay: function () { if (this.currentItem()) { this.blocked = false; this.endLatch = false; } },
    actualState: function () {
        if (!this.currentItem()) return -99;
        return this.endLatch ? 0 : window.player && player.getPlayerState ? player.getPlayerState() : -99;
    },
    armEnd: function () {
        const state = this.actualState();
        if (state > 0) this.endFence = true;
        return state;
    },
    fence: function (blocked, endFence) {
        if (!this.currentItem()) { this.blocked = this.endFence = this.endLatch = false; return; }
        if (typeof endFence === "boolean") this.endFence = endFence;
        this.blocked = blocked || this.endLatch && this.endFence;
        if (!this.blocked) this.endLatch = false;
        if (blocked && window.player && player.pauseVideo && player.getPlayerState() !== -1) player.pauseVideo();
    },
    beforeState: function (state) {
        if (!this.currentItem()) { this.blocked = this.endFence = this.endLatch = false; return false; }
        if (state === 0 && this.endFence && window.player && player.getPlayerState() === 0) {
            this.endLatch = true;
            this.blocked = true;
        }
        if (this.blocked && (state === 1 || state === 3)) {
            if (window.player && player.pauseVideo) player.pauseVideo();
            return true;
        }
        return false;
    }
};
