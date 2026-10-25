package org.telegram.messenger.purple;

import org.telegram.ui.Adapters.DialogsSearchAdapter;
import org.telegram.ui.Components.RecyclerListView;

public final class PurpleSearchRefresh {
    private PurpleSearchRefresh() {
    }

    public static void refresh(int account, int ownerAccount, String query,
            DialogsSearchAdapter adapter, RecyclerListView hints) {
        if (account != ownerAccount) {
            return;
        }
        adapter.filterRecent(query == null ? null : query.trim());
        adapter.notifyDataSetChanged();
        if (hints != null && hints.getAdapter() != null) {
            hints.getAdapter().notifyDataSetChanged();
        }
    }
}
