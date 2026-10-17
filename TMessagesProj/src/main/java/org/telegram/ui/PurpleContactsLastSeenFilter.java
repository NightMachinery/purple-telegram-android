package org.telegram.ui;

import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleContactsFilter;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.Adapters.ContactsAdapter;
import org.telegram.ui.Adapters.SearchAdapter;
import org.telegram.ui.Components.RecyclerListView;

import java.util.function.Supplier;

import static org.telegram.messenger.LocaleController.getString;

public final class PurpleContactsLastSeenFilter implements NotificationCenter.NotificationCenterDelegate {
    private static final int FILTER = 0x70750e01;
    private static final int MORE = 0x70750e02;

    private int currentAccount;
    private boolean attached;
    private boolean lastSeenFilter;
    private ActionBarMenuSubItem lastSeenFilterItem;
    private RecyclerListView listView;
    private ContactsAdapter listViewAdapter;
    private SearchAdapter searchListViewAdapter;
    private Supplier<String> searchQuery;

    public static boolean isDragSelectable(View view) {
        return view instanceof ActionBarMenuSubItem
                && Integer.valueOf(FILTER).equals(view.getTag());
    }

    public void attach(int currentAccount) {
        if (attached) {
            return;
        }
        this.currentAccount = currentAccount;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogFiltersUpdated);
        attached = true;
    }

    public void detach() {
        if (!attached) {
            return;
        }
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogFiltersUpdated);
        attached = false;
    }

    public void attach(RecyclerListView listView, ContactsAdapter listViewAdapter, SearchAdapter searchListViewAdapter, Supplier<String> searchQuery) {
        this.listView = listView;
        this.listViewAdapter = listViewAdapter;
        this.searchListViewAdapter = searchListViewAdapter;
        this.searchQuery = searchQuery;
    }

    public void addMenu(ActionBarMenu menu, boolean destroyAfterSelect, boolean createSecretChat, boolean returnAsResult) {
        if (!destroyAfterSelect && !createSecretChat && !returnAsResult) {
            ActionBarMenuItem moreItem = menu.addItem(MORE, R.drawable.ic_ab_other);
            moreItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
            lastSeenFilterItem = moreItem.addSubItem(FILTER, R.drawable.msg_view_file,
                    getString(R.string.PurpleContactsVisibleLastSeen), true);
            lastSeenFilterItem.setChecked(lastSeenFilter);
        }
    }

    public boolean onMenuItem(int id) {
        if (id != FILTER) {
            return false;
        }
        setLastSeenFilter(!lastSeenFilter);
        return true;
    }

    public boolean enabled() {
        return lastSeenFilter;
    }

    public boolean acceptUser(TLRPC.User user) {
        return !lastSeenFilter || PurpleContactsFilter.canSeeOrPeek(currentAccount, user);
    }

    public boolean allowUnregisteredContacts() {
        return !lastSeenFilter;
    }

    public boolean acceptSearchResult(Object object) {
        return !lastSeenFilter || (object instanceof TLRPC.User && acceptUser((TLRPC.User) object));
    }

    private void setLastSeenFilter(boolean value) {
        lastSeenFilter = value;
        lastSeenFilterItem.setChecked(value);
        listViewAdapter.setUserFilter(value ? user -> PurpleContactsFilter.canSeeOrPeek(currentAccount, user) : null);
        refresh();
    }

    public void refresh() {
        if (listView != null
                && listView.getAdapter() == searchListViewAdapter
                && !TextUtils.isEmpty(searchQuery.get())) {
            searchListViewAdapter.searchDialogs(searchQuery.get());
        }
    }

    public void refresh(int mask) {
        if ((mask & MessagesController.UPDATE_MASK_STATUS) != 0 && lastSeenFilter) {
            listViewAdapter.notifyDataSetChanged();
            refresh();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogFiltersUpdated) {
            if (lastSeenFilter && listViewAdapter != null) {
                listViewAdapter.notifyDataSetChanged();
                refresh();
            }
        }
    }
}
