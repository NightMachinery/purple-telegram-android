package org.telegram.ui;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.purple.PurpleSleepTimer;
import org.telegram.messenger.purple.PurpleSleepTimerRules;
import org.telegram.messenger.purple.PurpleSleepTimerViewerSelection;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import static org.telegram.messenger.purple.PurpleSleepTimerRules.*;

/** One viewer owner survives its floating and native PiP surface transfers. */
public final class PurpleSleepTimerViewer implements PurpleSleepTimer.Port, PurpleSleepTimer.Listener {
    private final PhotoViewer viewer;
    private final PurpleSleepTimer timer = PurpleSleepTimer.get();
    private VideoPlayer player;
    private org.telegram.ui.Components.PhotoViewerWebView web;
    private final org.telegram.messenger.purple.PurpleSleepTimerViewerIdentity identities = new org.telegram.messenger.purple.PurpleSleepTimerViewerIdentity();
    private int preparing, openingDepth;
    private final org.telegram.messenger.purple.PurpleSleepTimerViewerSelection selection = new org.telegram.messenger.purple.PurpleSleepTimerViewerSelection();
    private ActionBarMenuItem menu;
    private ActionBarMenuSubItem timerRow, endRow;
    private boolean listening, endEligible;

    public PurpleSleepTimerViewer(PhotoViewer viewer) { this.viewer = viewer; }

    public void open(Runnable stock) {
        openingDepth++;
        try { prepare(stock); } finally { openingDepth--; }
    }

    public PurpleSleepTimerViewerSelection.Origin openingOrigin() {
        return openingDepth != 0 ? PurpleSleepTimerViewerSelection.Origin.USER_OPEN
                : PurpleSleepTimerViewerSelection.Origin.REFRESH;
    }

    private static Object at(java.util.List<?> values, int index) {
        return values != null && index >= 0 && index < values.size() ? values.get(index) : null;
    }

    public void select(java.util.List<MessageObject> messages, java.util.List<?> locals,
            java.util.List<?> pages, int index, boolean embed, PurpleSleepTimerViewerSelection.Origin origin, Runnable stock) {
        final Object candidate = at(messages, index);
        final MessageObject message = candidate instanceof MessageObject ? (MessageObject) candidate : null;
        Item next = PurpleSleepTimer.item(message);
        if (embed && message != null && message.messageOwner != null
                && MessageObject.getMedia(message.messageOwner) != null
                && MessageObject.getMedia(message.messageOwner).webpage != null) {
            next = identities.item(Source.EMBED, MessageObject.getMedia(message.messageOwner).webpage);
        } else if (next == null) {
            final Object local = at(locals, index);
            final Object page = at(pages, index);
            next = identities.item(Source.LOCAL_VIEWER, local != null ? local : page != null ? page : message);
        }
        if (selection.select(next, origin)) timer.explicitPlay(Slot.VIEWER);
        prepare(stock);
    }

    public void prepare(Runnable stock) {
        preparing++;
        try { stock.run(); } finally {
            preparing--;
            viewer.purpleSleepRefresh();
        }
    }

    public void bind(MessageObject message, java.util.List<?> locals, int index, Object page,
            VideoPlayer player, org.telegram.ui.Components.PhotoViewerWebView web, boolean eligible, boolean live) {
        this.web = web;
        this.player = player;
        final Object selected = locals != null && index >= 0 && index < locals.size() ? locals.get(index) : page;
        final Item item = web != null ? identities.item(Source.EMBED, web.purpleSleepSource())
                : PurpleSleepTimer.item(message) != null ? PurpleSleepTimer.item(message)
                : identities.item(Source.LOCAL_VIEWER, selected);
        if (live || item == null || (web == null && player == null)
                || web != null && !web.purpleSleepFinite()) {
            if (web != null) web.purpleSleepUnregister();
            release();
            return;
        }
        if (player != null && timer.owns(Slot.AUDIO, player) && item.equals(timer.current(Slot.AUDIO))) {
            timer.handoff(timer.ticket(Slot.AUDIO), Slot.VIEWER, this);
        } else {
            if (web != null) timer.attachBackend(item, true, web, this);
            else timer.attach(Slot.VIEWER, item, eligible, player, this);
        }
        endEligible = web != null || eligible;
        if (web != null && timer.blocked(Slot.VIEWER)) timer.locally(() -> { viewer.purpleSleepPauseLocal(); return true; });
        if (!listening) { timer.listen(this); listening = true; }
        changed();
    }

    public void release() {
        if (preparing != 0) return;
        timer.detach(timer.ticket(Slot.VIEWER));
        if (listening) { timer.unlisten(this); listening = false; }
        player = null;
        changed();
    }

    public boolean end() {
        if (player == null) return false;
        final boolean consumed = timer.realEnd(player);
        return consumed || timer.blocked(Slot.VIEWER);
    }

    public void toggle(boolean wasPlaying) {
        if (player == null && (web == null || !web.purpleSleepFinite())) return;
        if (wasPlaying) timer.explicitPause(Slot.VIEWER);
        else {
            timer.explicitPlay(Slot.VIEWER);
            if (web != null) web.purpleSleepExplicitPlay();
        }
    }

    public void menu(ActionBarMenuItem menu) {
        this.menu = menu;
        timerRow = menu.addSubItem(PurpleSleepTimerUI.VIEWER_TIMER, R.drawable.menu_night_mode_24,
                LocaleController.getString(R.string.PurpleSleepViewer));
        endRow = menu.addSubItem(PurpleSleepTimerUI.VIEWER_END, 0,
                LocaleController.getString(R.string.PurpleSleepViewerEnd));
        changed();
    }

    public void webClosed(org.telegram.ui.Components.PhotoViewerWebView closed) {
        if (web != closed) return;
        web = null;
        release();
    }

    public boolean action(int id) {
        if (id != PurpleSleepTimerUI.VIEWER_END || web == null || !web.purpleSleepFinite()
                || timer.mode() != Mode.OFF) return false;
        PurpleSleepTimerUI.installFeedback();
        web.purpleSleepStartEnd();
        return true;
    }

    public boolean canOpenMenu() { return timer.current(Slot.VIEWER) != null || web != null; }

    @Override public void changed() {
        if (menu == null) return;
        final boolean loaded = timer.current(Slot.VIEWER) != null;
        if (web != null || loaded && timer.current(Slot.VIEWER).source != Source.MESSAGE) menu.setVisibility(android.view.View.VISIBLE);
        menu.setSubItemShown(PurpleSleepTimerUI.VIEWER_TIMER, loaded || web != null);
        if (timerRow != null) timerRow.setEnabled(loaded);
        menu.setSubItemShown(PurpleSleepTimerUI.VIEWER_END, loaded && (timer.mode() != Mode.OFF || timer.eligible(Slot.VIEWER)));
        if (timerRow != null) timerRow.setText(!loaded && web != null ? LocaleController.getString(web.purpleSleepUnavailable())
                : timer.mode() == Mode.TIMED
                ? LocaleController.formatString(R.string.PurpleSleepViewerLeft, PurpleSleepTimerUI.compact())
                : LocaleController.getString(R.string.PurpleSleepViewer));
        if (endRow != null) endRow.setText(LocaleController.getString(timer.mode() == Mode.OFF
                ? R.string.PurpleSleepViewerEnd : R.string.PurpleSleepViewerCancel));
    }
    @Override public boolean prepareEnd(Runnable accept) {
        if (web == null || !web.purpleSleepFinite()) return false;
        web.purpleSleepPrepareEnd(accept);
        return true;
    }
    @Override public boolean eligibleEnd() {
        return endEligible && (web != null ? web.purpleSleepFinite() && !web.purpleSleepEnded()
                : player != null && !player.isLooping() && player.getPlaybackState() != androidx.media3.common.Player.STATE_ENDED);
    }
    @Override public boolean playing() { return web != null ? web.isPlaying() : player != null && player.isPlaying(); }
    @Override public void refreshReceipt() { if (web != null) web.purpleSleepRefreshReceipt(); }
    @Override public void fenceAutomaticResumes() { viewer.purpleSleepCancelResumes(); }
    @Override public void pauseLocal() { viewer.purpleSleepPauseLocal(); }
    @Override public void finishVoiceLocally() {}
}
