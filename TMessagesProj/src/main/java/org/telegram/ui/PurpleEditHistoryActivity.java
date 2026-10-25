package org.telegram.ui;

import android.content.Context;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleArchiveViewerModel;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

public final class PurpleEditHistoryActivity extends UniversalFragment {
    private static final int NEXT = 0x70750331, REFRESH = 0x70750332;
    private PurpleArchiveViewerModel model;
    private PurpleArchiveViewerModel.Page page;
    private boolean loading;
    public PurpleEditHistoryActivity(int account, TLRPC.Message message) {
        currentAccount = account;
        try { model = new PurpleArchiveViewerModel(account, message); } catch (Exception | LinkageError ignored) {}
    }
    @Override protected CharSequence getTitle() { return text(R.string.PurpleArchiveViewHistory); }
    @Override public View createView(Context context) {
        View result = super.createView(context); refresh(); return result;
    }
    private void refresh() {
        page = null;
        try {
            PurpleArchiveViewerModel previous = model;
            if (previous == null) throw new IllegalStateException("Archive opening identity unavailable");
            model = previous.restart(); previous.close(); next();
        }
        catch (Exception | LinkageError e) { refused(); }
    }
    private void next() {
        if (loading || model == null || isFinished) return;
        if (!model.available()) { page = null; loading = false; refused(); if (listView != null) listView.adapter.update(true); return; }
        loading = true; WeakReference<PurpleEditHistoryActivity> weak = new WeakReference<>(this);
        PurpleArchiveViewerModel request = model; View openingView = getFragmentView();
        boolean admitted = request.next(result -> {
            PurpleEditHistoryActivity view = weak.get();
            if (view == null || view.isFinished || view.model != request || view.getFragmentView() != openingView) return;
            view.loading = false; view.page = result;
            if (!result.error.isEmpty()) view.refused();
            if (view.listView != null) view.listView.adapter.update(true);
        });
        if (!admitted) { loading = false; refused(); }
        if (listView != null) listView.adapter.update(true);
    }
    private void refused() {
        loading = false;
        PurpleWriterUI.explain(this, text(R.string.PurpleArchiveViewHistory), text(R.string.PurpleArchiveHistoryUnavailable),
            "file_unreadable", this::refresh, null);
    }
    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (model != null && !model.available()) { page = null; loading = false; }
        items.add(UItem.asShadow(text(R.string.PurpleArchiveHistoryPolicy)));
        if (loading) items.add(UItem.asShadow(text(R.string.PurpleArchiveOpening)));
        else if (page == null || !page.error.isEmpty()) items.add(UItem.asShadow(text(R.string.PurpleArchiveHistoryUnavailable)));
        else if (page.versions.isEmpty()) items.add(UItem.asShadow(text(R.string.PurpleArchiveHistoryEmpty)));
        else for (PurpleArchiveViewerModel.Version version : page.versions) {
            String label = text(version.origin == 1 ? R.string.PurpleArchiveEditObserved : R.string.PurpleArchiveBodyObserved);
            String detail = label + " " + when(version.observedAt) + "\n" + text(R.string.PurpleArchiveServerDate) + " " + when(version.serverTime);
            if (version.missingPreimage) detail += "\n" + text(R.string.PurpleArchiveMissingPreimage);
            if (version.partial) detail += "\n" + text(R.string.PurpleArchivePartialBody);
            if (version.media != 0) detail += "\n" + text(R.string.PurpleArchiveMediaNotAvailable);
            items.add(UItem.asShadow(detail));
            String body = version.text + (version.caption.isEmpty() ? "" : "\n" + version.caption);
            items.add(UItem.asShadow(body.isEmpty() ? text(R.string.PurpleArchivePartialBody) : body));
        }
        if (!loading && page != null && page.more) items.add(UItem.asButton(NEXT, text(R.string.PurpleArchiveOlder)));
        items.add(UItem.asButton(REFRESH, text(R.string.PurpleArchiveRefresh)));
    }
    private String when(String seconds) {
        try { long value = Long.parseLong(seconds); return value == 0 ? text(R.string.PurpleArchiveUnknownDate)
            : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(Math.multiplyExact(value, 1000))); }
        catch (Exception e) { return text(R.string.PurpleArchiveUnknownDate); }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == NEXT) next(); else if (item.id == REFRESH) { loading = false; refresh(); }
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void onResume() {
        super.onResume();
        if (model != null && !model.available()) { page = null; loading = false; if (listView != null) listView.adapter.update(true); }
    }
    @Override public void onFragmentDestroy() { if (model != null) model.close(); super.onFragmentDestroy(); }
    private static String text(int id) { return LocaleController.getString(id); }
}
