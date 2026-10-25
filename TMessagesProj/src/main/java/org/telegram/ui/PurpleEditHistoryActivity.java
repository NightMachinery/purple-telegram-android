package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleArchiveViewerModel;
import org.telegram.messenger.purple.PurpleArchiveMediaAccess;
import org.telegram.messenger.purple.PurpleArchiveMediaProvider;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

public final class PurpleEditHistoryActivity extends UniversalFragment {
    private static final int NEXT = 0x70750331, REFRESH = 0x70750332, CANCEL_MEDIA = 0x70750334, MEDIA = 0x70750340;
    private PurpleArchiveMediaAccess.Capability pendingMedia;
    private boolean preparing;
    private long mediaEpoch;
    private static final class MediaAction {
        final PurpleArchiveViewerModel.Attachment attachment;
        final PurpleArchiveViewerModel.Page page;
        final boolean save;
        MediaAction(PurpleArchiveViewerModel.Attachment attachment, PurpleArchiveViewerModel.Page page, boolean save) { this.attachment = attachment; this.page = page; this.save = save; }
    }
    private PurpleArchiveViewerModel model;
    private PurpleArchiveViewerModel.Page page;
    private boolean loading;
    private final Runnable authority = new Runnable() {
        @Override public void run() {
            if (isFinished) return;
            if (model != null && !model.available()) { cancelMedia(); page = null; loading = false; update(); }
            org.telegram.messenger.AndroidUtilities.runOnUIThread(this,1000);
        }
    };
    public PurpleEditHistoryActivity(int account, TLRPC.Message message) {
        currentAccount = account;
        try { model = new PurpleArchiveViewerModel(account, message); } catch (Exception | LinkageError ignored) {}
    }
    public PurpleEditHistoryActivity(int account, PurpleArchiveViewerModel captured) {
        currentAccount = account; model = captured;
    }
    @Override protected CharSequence getTitle() { return text(R.string.PurpleArchiveViewHistory); }
    @Override public View createView(Context context) {
        View result = super.createView(context); refresh(); return result;
    }
    private void refresh() {
        cancelMedia(); page = null;
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

            items.add(UItem.asShadow(detail));
            String body = version.text + (version.caption.isEmpty() ? "" : "\n" + version.caption);
            items.add(UItem.asShadow(body.isEmpty() ? text(R.string.PurpleArchivePartialBody) : body));
            for (PurpleArchiveViewerModel.Attachment attachment : version.attachments) {
                items.add(UItem.asShadow(attachment.name + "\n" + text(attachment.state == 2 ? R.string.PurpleArchiveMediaComplete : attachment.state == 1 ? R.string.PurpleArchiveMediaPartial : R.string.PurpleArchiveMediaMissing)));
                if (attachment.state != 2) continue;
                if (PurpleArchiveMediaActivity.supported(attachment.mime)) mediaButton(items, attachment, false);
                else items.add(UItem.asShadow(text(R.string.PurpleArchiveUnsupportedMedia)));
                mediaButton(items, attachment, true);
            }
        }
        if (preparing) { items.add(UItem.asShadow(text(R.string.PurpleArchiveMediaPreparing))); items.add(UItem.asButton(CANCEL_MEDIA, text(R.string.Cancel))); }
        if (!loading && page != null && page.more) items.add(UItem.asButton(NEXT, text(R.string.PurpleArchiveOlder)));
        items.add(UItem.asButton(REFRESH, text(R.string.PurpleArchiveRefresh)));
    }
    private String when(String seconds) {
        try { long value = Long.parseLong(seconds); return value == 0 ? text(R.string.PurpleArchiveUnknownDate)
            : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(Math.multiplyExact(value, 1000))); }
        catch (Exception e) { return text(R.string.PurpleArchiveUnknownDate); }
    }
    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof MediaAction) {
            MediaAction action = (MediaAction) item.object;
            if (!valid(action)) { mediaRefused(); return; }
            if (action.save) {
                AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
                dialog.setTitle(text(R.string.PurpleArchiveSaveMedia)); dialog.setMessage(text(R.string.PurpleArchiveSaveDisclosure));
                dialog.setPositiveButton(text(R.string.Save), (ignored, which) -> prepare(action));
                dialog.setNegativeButton(text(R.string.Cancel), null); showDialog(dialog.create());
            } else prepare(action);
        } else if (item.id == CANCEL_MEDIA) { cancelMedia(); update(); }
        else if (item.id == NEXT) { cancelMedia(); next(); }
        else if (item.id == REFRESH) { loading = false; refresh(); }
    }
    private void mediaButton(ArrayList<UItem> items, PurpleArchiveViewerModel.Attachment attachment, boolean save) {
        UItem item = UItem.asButton(MEDIA + items.size(), text(save ? R.string.PurpleArchiveSaveMedia : R.string.PurpleArchiveOpenMedia));
        item.object = new MediaAction(attachment, page, save); items.add(item);
    }
    private boolean valid(MediaAction action) { return !isFinished && model != null && model.available() && page == action.page && getParentActivity() != null; }
    private void prepare(MediaAction action) {
        if (!valid(action)) { mediaRefused(); return; }
        cancelMedia(); preparing = true; long epoch = mediaEpoch;
        PurpleArchiveViewerModel request = model; View opening = getFragmentView(); WeakReference<PurpleEditHistoryActivity> weak = new WeakReference<>(this);
        pendingMedia = request.media(action.attachment, (capability, error) -> {
            PurpleEditHistoryActivity view = weak.get();
            if (view == null || !view.valid(action) || view.model != request || view.getFragmentView() != opening || view.mediaEpoch != epoch) { if (capability != null) capability.close(); return; }
            view.pendingMedia = null; view.preparing = false;
            if (capability == null || !capability.available()) { view.mediaRefused(); view.update(); return; }
            try {
                Intent intent = new Intent(view.getParentActivity(), action.save ? PurpleArchiveSaveActivity.class : PurpleArchiveMediaActivity.class)
                    .setData(PurpleArchiveMediaProvider.uri(capability.token));
                view.getParentActivity().startActivity(intent);
            } catch (RuntimeException e) { capability.close(); view.mediaRefused(); }
            view.update();
        });
        if (pendingMedia == null) { preparing = false; mediaRefused(); } update();
    }
    private void cancelMedia() {
        ++mediaEpoch; preparing = false;
        if (pendingMedia != null) pendingMedia.close(); pendingMedia = null;
    }
    private void update() { if (listView != null) listView.adapter.update(true); }
    private void mediaRefused() {
        if (!isFinished && getParentActivity() != null) {
            AlertDialog.Builder dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            dialog.setTitle(text(R.string.PurpleArchiveViewHistory)); dialog.setMessage(text(R.string.PurpleArchiveMediaUnavailable));
            dialog.setPositiveButton(text(R.string.OK), null); showDialog(dialog.create());
        }
    }
    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
    @Override public void onResume() {
        super.onResume();
        org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(authority); authority.run();
        if (model != null && !model.available()) { cancelMedia(); page = null; loading = false; if (listView != null) listView.adapter.update(true); }
    }
    @Override public void onPause() { org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(authority); super.onPause(); }
    @Override public void onFragmentDestroy() { org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(authority); cancelMedia(); if (model != null) model.close(); super.onFragmentDestroy(); }
    private static String text(int id) { return LocaleController.getString(id); }
}
