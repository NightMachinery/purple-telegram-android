package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

final class PurplePinnedMusicList implements PurplePinnedMusic.Listener {
    private final int account;
    private final long dialogId;
    private final long topicId;
    private final Adapter adapter;
    private ArrayList<PurplePinnedMusic.Item> items = new ArrayList<>();

    static void show(Context context, Theme.ResourcesProvider resourcesProvider,
            int account, long dialogId, long topicId) {
        new PurplePinnedMusicList(context, resourcesProvider, account, dialogId, topicId).show();
    }

    private PurplePinnedMusicList(Context context, Theme.ResourcesProvider resourcesProvider,
            int account, long dialogId, long topicId) {
        this.account = account;
        this.dialogId = dialogId;
        this.topicId = topicId;
        adapter = new Adapter(context, resourcesProvider);
    }

    private void show() {
        Context context = adapter.context;
        RecyclerListView list = new RecyclerListView(context, adapter.resourcesProvider);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);
        list.setVerticalScrollBarEnabled(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(context, adapter.resourcesProvider);
        builder.setTitle(context.getString(R.string.PurplePinnedMusicFiles));
        builder.setView(list, AndroidUtilities.dp(480));
        builder.setNegativeButton(context.getString(R.string.Close), null);
        builder.setOnDismissListener(ignored ->
                PurplePinnedMusic.removeListener(account, dialogId, topicId, this));
        PurplePinnedMusic.addListener(account, dialogId, topicId, this);
        builder.show();
    }

    @Override
    public void onPinnedMusicChanged(PurplePinnedMusic.Snapshot snapshot) {
        items = snapshot == null ? new ArrayList<>()
                : PurplePinnedMusic.getItems(account, dialogId, topicId);
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        private final Theme.ResourcesProvider resourcesProvider;

        Adapter(Context context, Theme.ResourcesProvider resourcesProvider) {
            this.context = context;
            this.resourcesProvider = resourcesProvider;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            Row row = new Row(context, resourcesProvider);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(68)));
            return new RecyclerListView.Holder(row);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((Row) holder.itemView).bind(items.get(position));
        }
    }

    private final class Row extends LinearLayout {
        private final TextView title;
        private final TextView detail;
        private final TextView retry;

        Row(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(16), 0);
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            detail = new TextView(context);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            detail.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
            detail.setSingleLine(true);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(detail);
            addView(texts, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            retry = new TextView(context);
            retry.setText(R.string.Retry);
            retry.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            retry.setTextColor(Theme.getColor(Theme.key_dialogButton, resourcesProvider));
            retry.setGravity(Gravity.CENTER);
            retry.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
            addView(retry, new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(48)));
        }

        void bind(PurplePinnedMusic.Item item) {
            title.setText(item.title);
            StringBuilder line = new StringBuilder();
            if (!TextUtils.isEmpty(item.artist)) {
                line.append(item.artist).append(" · ");
            }
            switch (item.state) {
                case 0:
                    line.append(getContext().getString(item.attempts > 0
                            ? R.string.PurplePinnedMusicFileRetryQueued
                            : R.string.PurplePinnedMusicFileQueued));
                    if (item.attempts > 0) {
                        line.append(": ").append(reason(item.failureReason));
                    }
                    break;
                case 1:
                    line.append(getContext().getString(item.waiting
                            ? R.string.PurplePinnedMusicFileWaiting
                            : item.promoting ? R.string.PurplePinnedMusicFilePromoting
                            : R.string.PurplePinnedMusicFileDownloading));
                    if (item.total > 0 && !item.promoting) {
                        line.append(' ').append(item.downloaded * 100 / item.total).append('%');
                    }
                    break;
                case 2:
                    line.append(getContext().getString(R.string.PurplePinnedMusicFileComplete));
                    break;
                case 4:
                    line.append(getContext().getString(R.string.PurplePinnedMusicFileCacheOnly));
                    break;
                default:
                    line.append(reason(item.failureReason));
                    break;
            }
            detail.setText(line);
            retry.setVisibility(item.retryable ? VISIBLE : GONE);
            retry.setOnClickListener(item.retryable ? view ->
                    PurplePinnedMusic.retryItem(account, dialogId, topicId, item.fileName) : null);
        }

        private String reason(int reason) {
            int string = reason == -1 ? R.string.PurplePinnedMusicFileNoSpace
                    : reason == 1 ? R.string.PurplePinnedMusicFileCancelled
                    : reason == 2 ? R.string.PurplePinnedMusicFileRateLimited
                    : reason == 3 ? R.string.PurplePinnedMusicFileRemoved
                    : reason == 4 ? R.string.PurplePinnedMusicFileInvalid
                    : reason == 5 ? R.string.PurplePinnedMusicFileStopped
                    : reason == 6 ? R.string.PurplePinnedMusicFilePromotionFailed
                    : R.string.PurplePinnedMusicFileFailed;
            return LocaleController.getString(string);
        }
    }
}
