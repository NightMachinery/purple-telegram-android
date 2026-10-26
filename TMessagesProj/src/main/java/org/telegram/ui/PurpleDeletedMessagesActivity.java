package org.telegram.ui;

import android.content.Context;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleArchiveDeletedModel;
import org.telegram.messenger.purple.PurpleArchiveQueries;
import org.telegram.messenger.purple.PurpleArchiveViewerModel;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

public final class PurpleDeletedMessagesActivity extends UniversalFragment {
    private static final int REFRESH = 0x70750401, NEXT = 0x70750402, USAGE = 0x70750403, HISTORY = 0x70750410;
    private final PurpleArchiveQueries.Scope scope;
    private final Runnable scopeClosed;
    private PurpleArchiveDeletedModel model;
    private PurpleArchiveQueries.Page page;
    private boolean loading, freshnessShown;
    private String state = "Opening";
    private static final class Action {
        final PurpleArchiveQueries.Entry entry;
        final PurpleArchiveQueries.Page page;
        final PurpleArchiveDeletedModel model;
        Action(PurpleArchiveQueries.Entry entry, PurpleArchiveQueries.Page page, PurpleArchiveDeletedModel model) { this.entry = entry; this.page = page; this.model = model; }
    }
    private final Runnable authority = new Runnable() {
        @Override public void run() {
            if (isFinished) return;
            if (model != null && !model.available()) { state = model.state(); page = null; loading = false; update(); }
            if (model != null && model.available() && model.refreshSuggested() && !freshnessShown) { freshnessShown = true; update(); }
            AndroidUtilities.runOnUIThread(this,1000);
        }
    };
    public PurpleDeletedMessagesActivity(int account, PurpleArchiveQueries.Scope scope) { this(account,scope,() -> {}); }
    PurpleDeletedMessagesActivity(int account, PurpleArchiveQueries.Scope scope, Runnable scopeClosed) {
        currentAccount = account; this.scope = scope; this.scopeClosed = scopeClosed;
        try { model = new PurpleArchiveDeletedModel(scope); } catch (Exception | LinkageError e) { state = PurpleArchiveDeletedModel.failure(e); }
    }
    @Override protected CharSequence getTitle() { return text(R.string.PurpleArchiveDeletedMessages); }
    @Override public View createView(Context context) {
        View result = super.createView(context); bind(); next(); return result;
    }
    private void bind() {
        if (model == null) return;
        WeakReference<PurpleDeletedMessagesActivity> weak = new WeakReference<>(this); PurpleArchiveDeletedModel opening = model;
        model.onRevoked(() -> {
            PurpleDeletedMessagesActivity view = weak.get(); if (view == null || view.isFinished || view.model != opening) return;
            view.state = opening.state(); view.page = null; view.loading = false; view.update();
        });
    }
    private void next() {
        if (loading || isFinished || model == null) return;
        loading = true; state = "Opening"; WeakReference<PurpleDeletedMessagesActivity> weak = new WeakReference<>(this);
        PurpleArchiveDeletedModel opening = model; View openingView = getFragmentView();
        if (!model.next(result -> {
            PurpleDeletedMessagesActivity view = weak.get();
            if (view == null || view.isFinished || view.model != opening || view.getFragmentView() != openingView) return;
            view.loading = false; view.state = result.status; view.page = result.status.equals("Ready") ? result : null; view.update();
        })) { loading = false; state = model.state(); page = null; }
        update();
    }
    private void refresh() {
        loading = false; page = null; freshnessShown = false;
        try { if (model == null) throw new IllegalStateException("Archive opening unavailable"); model = model.restart(); bind(); next(); }
        catch (Exception | LinkageError e) { state = "Stale"; update(); }
    }
    void scopeRevoked() {
        if (model != null) model.close(); page = null; loading = false; state = "Stale"; update();
    }
    private void update() { if (listView != null) listView.adapter.update(true); }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (model != null && !model.available()) { state = model.state(); page = null; loading = false; }
        items.add(UItem.asShadow(LocaleController.formatString(R.string.PurpleArchiveDeletedScope,scope.account(),scope.title)));
        if (!scope.topic().isEmpty()) items.add(UItem.asShadow(LocaleController.formatString(R.string.PurpleArchiveTopicScope,scope.topic())));
        items.add(UItem.asShadow(text(R.string.PurpleArchiveDeletedPolicy)));
        if (loading) items.add(UItem.asShadow(text(R.string.PurpleArchiveOpening)));
        if (page == null) items.add(UItem.asShadow(text(state.equals("Opening") ? R.string.PurpleArchiveOpening : state.equals("Stale") ? R.string.PurpleArchiveQueryStale
            : state.equals("Busy") ? R.string.PurpleArchiveQueryBusy : state.equals("SchemaMismatch") ? R.string.PurpleArchiveSchemaMismatch
            : state.equals("ReadOnly") ? R.string.PurpleArchiveReadOnly : R.string.PurpleArchiveQueryUnavailable)));
        else {
            if (model != null && model.refreshSuggested()) items.add(UItem.asShadow(text(R.string.PurpleArchiveQueryNewer)));
            items.add(UItem.asShadow(LocaleController.formatString(R.string.PurpleArchiveScopedCounts,page.counts.get(0).toString(),page.counts.get(1).toString(),page.counts.get(2).toString(),
                page.counts.get(3).toString(),page.counts.get(4).toString(),page.counts.get(5).toString(),page.counts.get(6).toString())));
            items.add(UItem.asShadow(LocaleController.formatString(page.logicalComplete ? R.string.PurpleArchiveLogicalBytes : R.string.PurpleArchiveLogicalProgress,page.logicalBytes)));
            items.add(UItem.asShadow(LocaleController.formatString(R.string.PurpleArchivePhysicalEstimate,page.physicalBytes) + "\n" + text(R.string.PurpleArchiveFreedUnknown)));
            if (page.gap) items.add(UItem.asShadow(text(R.string.PurpleArchiveGap)));
            if (page.entries.isEmpty() && !page.more) items.add(UItem.asShadow(text(page.counts.get(1) == 0 ? R.string.PurpleArchiveDeletedEmpty : R.string.PurpleArchiveDeletedEnd)));
            for (PurpleArchiveQueries.Entry entry : page.entries) {
                String badge = text(entry.status == 2 ? R.string.PurpleArchiveExpired : entry.status == 3 ? R.string.PurpleArchiveConsumed : R.string.PurpleArchiveDeleted);
                items.add(UItem.asShadow(LocaleController.formatString(R.string.PurpleArchiveDeletionObserved,badge,when(entry.observedAt))));
                if (!entry.body) { items.add(UItem.asShadow(text(R.string.PurpleArchiveNoBody))); continue; }
                items.add(UItem.asShadow(LocaleController.formatString(R.string.PurpleArchiveCapturedContext,sender(entry),entry.reply.isEmpty() ? text(R.string.PurpleArchiveNotCaptured) : entry.reply,
                    entry.topic.isEmpty() || entry.topic.equals("0") ? text(R.string.PurpleArchiveNotCaptured) : entry.topic)));
                if (entry.partial) items.add(UItem.asShadow(text(R.string.PurpleArchivePartialBody)));
                if (entry.missingPreimage) items.add(UItem.asShadow(text(R.string.PurpleArchiveMissingPreimage)));
                String body = entry.text + (entry.caption.isEmpty() ? "" : "\n" + entry.caption) + (entry.rich.isEmpty() ? "" : "\n" + entry.rich);
                items.add(UItem.asShadow(body.isEmpty() ? text(R.string.PurpleArchivePartialBody) : body));
                if (entry.truncated) items.add(UItem.asShadow(text(R.string.PurpleArchivePreviewClipped)));
                for (int media : entry.media) items.add(UItem.asShadow(text(media == 2 ? R.string.PurpleArchiveMediaComplete : media == 1 ? R.string.PurpleArchiveMediaPartial : R.string.PurpleArchiveMediaMissing)));
                UItem history = UItem.asButton(HISTORY + items.size(),text(R.string.PurpleArchiveViewHistory)); history.object = new Action(entry,page,model); items.add(history);
            }
            if (!loading && page.more) items.add(UItem.asButton(NEXT,text(R.string.PurpleArchiveQueryNext)));
            else if (!loading && !page.logicalComplete) items.add(UItem.asButton(USAGE,text(R.string.PurpleArchiveUsageContinue)));
        }
        items.add(UItem.asButton(REFRESH,text(R.string.PurpleArchiveQueryRefresh)));
        items.add(UItem.asButton(PurpleArchivePurgeUI.TRASH,text(R.string.PurpleArchiveEmptyTrash)));
        items.add(UItem.asButton(PurpleArchivePurgeUI.EDITS,text(R.string.PurpleArchiveClearEdits)));
    }
    private String sender(PurpleArchiveQueries.Entry entry) {
        if (!entry.senderUser.isEmpty()) return LocaleController.formatString(R.string.PurpleArchiveCapturedUser,entry.senderUser);
        if (!entry.senderGroup.isEmpty()) return LocaleController.formatString(R.string.PurpleArchiveCapturedGroup,entry.senderGroup);
        if (!entry.senderChannel.isEmpty()) return LocaleController.formatString(R.string.PurpleArchiveCapturedChannel,entry.senderChannel);
        return text(R.string.PurpleArchiveNotCaptured);
    }
    private String when(String value) {
        try { long seconds = Long.parseLong(value); return seconds == 0 ? text(R.string.PurpleArchiveUnknownDate)
            : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(Math.multiplyExact(seconds,1000))); }
        catch (Exception e) { return text(R.string.PurpleArchiveUnknownDate); }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof Action) {
            Action action = (Action)item.object;
            if (model == null || action.model != model || action.page != page || !model.available()) { page = null; state = "Stale"; update(); return; }
            PurpleArchiveViewerModel history = model.history(action.entry);
            if (history != null) presentFragment(new PurpleEditHistoryActivity(currentAccount,history));
            else { page = null; state = "Stale"; update(); }
        } else if (item.id == PurpleArchivePurgeUI.TRASH || item.id == PurpleArchivePurgeUI.EDITS) {
            try { PurpleArchivePurgeUI.open(this,org.telegram.messenger.purple.PurpleArchivePurgeModel.Scope.parent(scope),item.id == PurpleArchivePurgeUI.EDITS ? 1 : 0); }
            catch (Exception | LinkageError e) { page = null; state = "Stale"; update(); }
        } else if (item.id == REFRESH) refresh(); else if (item.id == NEXT || item.id == USAGE) next();
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void onResume() { super.onResume(); AndroidUtilities.cancelRunOnUIThread(authority); authority.run(); }
    @Override public void onPause() { AndroidUtilities.cancelRunOnUIThread(authority); super.onPause(); }
    @Override public void onFragmentDestroy() {
        scopeClosed.run(); AndroidUtilities.cancelRunOnUIThread(authority); if (model != null) model.close(); super.onFragmentDestroy(); }
    private static String text(int id) { return LocaleController.getString(id); }
}
