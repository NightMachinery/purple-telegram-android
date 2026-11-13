package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

final class PurpleMediaSettingsRow extends TextDetailSettingsCell {
    PurpleMediaSettingsRow(Context context, Theme.ResourcesProvider resources) {
        super(context);
        setMultilineDetail(true);
        getTextView().setSingleLine(false);
        getTextView().setMaxLines(Integer.MAX_VALUE);
        getTextView().setEllipsize(null);
        getTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resources));
        getValueTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resources));
        setMinimumHeight(AndroidUtilities.dp(64));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = Math.max(0, MeasureSpec.getSize(widthSpec) - AndroidUtilities.dp(42));
        getTextView().measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        FrameLayout.LayoutParams title = (FrameLayout.LayoutParams) getTextView().getLayoutParams();
        FrameLayout.LayoutParams value = (FrameLayout.LayoutParams) getValueTextView().getLayoutParams();
        title.gravity = value.gravity = Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        title.width = value.width = width;
        getTextView().setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        getValueTextView().setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        value.topMargin = AndroidUtilities.dp(14) + getTextView().getMeasuredHeight();
        super.onMeasure(widthSpec, heightSpec);
    }

    static UItem button(int id, CharSequence title, CharSequence value) {
        return Factory.of(id, title, value);
    }

    public static final class Factory extends UItem.UItemFactory<PurpleMediaSettingsRow> {
        static { setup(new Factory()); }

        static UItem of(int id, CharSequence title, CharSequence value) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.text = title;
            item.subtext = value;
            return item;
        }

        @Override
        public PurpleMediaSettingsRow createView(Context context, RecyclerListView listView,
                int currentAccount, int classGuid, Theme.ResourcesProvider resources) {
            return new PurpleMediaSettingsRow(context, resources);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter,
                UniversalRecyclerView listView) {
            ((PurpleMediaSettingsRow) view).setTextAndValue(item.text, item.subtext, divider);
        }
    }
}
