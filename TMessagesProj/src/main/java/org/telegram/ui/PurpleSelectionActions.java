package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.Components.AnimatedTextView;

public final class PurpleSelectionActions {
    private static final int STATE_TAG = 0x70750531;
    private static final int MORE = 0x70750532;
    private final ActionBarMenu menu;
    private final AnimatedTextView count;
    private final ActionBarMenuItem more;
    private boolean applying;
    private final ArrayList<Entry> entries = new ArrayList<>();

    private static final class Entry {
        final ActionBarMenuItem item;
        final ActionBarMenuSubItem popup;
        int visibility;
        boolean overflow;

        Entry(ActionBarMenuItem item, ActionBarMenuSubItem popup) {
            this.item = item;
            this.popup = popup;
            visibility = item.getVisibility();
        }
    }

    private PurpleSelectionActions(ActionBarMenu menu, AnimatedTextView count) {
        this.menu = menu;
        this.count = count;
        ArrayList<ActionBarMenuItem> items = new ArrayList<>();
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (menu.getChildAt(i) instanceof ActionBarMenuItem) items.add((ActionBarMenuItem) menu.getChildAt(i));
        }
        more = menu.addItemWithWidth(MORE, R.drawable.msg_actions, dp(48), LocaleController.getString(R.string.AccDescrMoreOptions));
        more.setVisibility(View.GONE);
        for (ActionBarMenuItem item : items) {
            Drawable icon = item.getIconView().getDrawable();
            if (icon != null && icon.getConstantState() != null) icon = icon.getConstantState().newDrawable().mutate();
            ActionBarMenuSubItem popup = more.addSubItem((Integer) item.getTag(), 0, icon, item.getContentDescription(), true, false, null);
            Entry entry = new Entry(item, popup);
            popup.setOnClickListener(v -> {
                if (!more.isSubMenuShowing()) return;
                more.closeSubMenu();
                if (entry.visibility != View.VISIBLE || !entry.overflow || !item.isEnabled() || menu.getVisibility() != View.VISIBLE) return;
                item.layout(more.getLeft(), more.getTop(), more.getRight(), more.getBottom());
                item.performClick();
            });
            entries.add(entry);
        }
    }

    public static void install(ActionBarMenu menu, AnimatedTextView count) {
        menu.setTag(STATE_TAG, new PurpleSelectionActions(menu, count));
    }

    public static boolean visibility(ActionBarMenu menu, ActionBarMenuItem item, int visibility) {
        if (menu == null) return false;
        PurpleSelectionActions state = (PurpleSelectionActions) menu.getTag(STATE_TAG);
        if (state == null || state.applying) return false;
        for (Entry entry : state.entries) {
            if (entry.item != item) continue;
            entry.visibility = visibility;
            state.applying = true;
            try {
                entry.item.setVisibility(entry.overflow ? View.GONE : visibility);
                entry.popup.setVisibility(entry.overflow ? visibility : View.GONE);
            } finally {
                state.applying = false;
            }
            menu.requestLayout();
            return true;
        }
        return false;
    }

    public static boolean visible(ActionBarMenu menu, ActionBarMenuItem item) {
        PurpleSelectionActions state = (PurpleSelectionActions) menu.getTag(STATE_TAG);
        if (state != null) {
            for (Entry entry : state.entries) if (entry.item == item) return entry.visibility == View.VISIBLE;
        }
        return item != null && item.getVisibility() == View.VISIBLE;
    }

    public static void measure(ActionBarMenu menu, int width) {
        PurpleSelectionActions state = (PurpleSelectionActions) menu.getTag(STATE_TAG);
        if (state != null) state.layout(width);
    }

    private void layout(int width) {
        applying = true;
        try {
            arrange(width);
        } finally {
            applying = false;
        }
    }

    private void arrange(int width) {
        LinearLayout.LayoutParams label = (LinearLayout.LayoutParams) count.getLayoutParams();
        int text = Math.max(dp(48), Math.min(dp(160), (int) Math.ceil(count.getDrawable().getWidth()) + dp(8)));
        int reserved = label.leftMargin + label.rightMargin + text + dp(8);
        int visible = 0;
        for (Entry entry : entries) if (entry.visibility == View.VISIBLE) visible++;
        int slots = Math.max(1, (width - reserved) / dp(48));
        boolean overflow = visible > slots;
        int direct = overflow ? slots - 1 : slots;
        for (Entry entry : entries) {
            boolean shown = entry.visibility == View.VISIBLE;
            entry.overflow = shown && direct <= 0;
            if (shown) direct--;
            entry.item.setVisibility(entry.overflow ? View.GONE : entry.visibility);
            entry.popup.setVisibility(entry.overflow ? View.VISIBLE : View.GONE);
            if (entry.overflow) {
                Drawable icon = entry.item.getIconView().getDrawable();
                if (icon != null && icon.getConstantState() != null) icon = icon.getConstantState().newDrawable().mutate();
                entry.popup.setTextAndIcon(entry.item.getContentDescription(), 0, icon);
                entry.popup.setContentDescription(entry.item.getContentDescription());
            }
            entry.popup.setEnabled(entry.item.isEnabled());
            bounds(entry.item);
        }
        more.setVisibility(overflow ? View.VISIBLE : View.GONE);
        bounds(more);
    }

    private static void bounds(ActionBarMenuItem item) {
        LinearLayout.LayoutParams bounds = (LinearLayout.LayoutParams) item.getLayoutParams();
        bounds.width = dp(48);
        bounds.leftMargin = bounds.rightMargin = 0;
    }
}
