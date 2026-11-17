package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.glass.GlassTabView;

public final class PurplePlaylistsUI {
    public static GlassTabView tab(Context context, Theme.ResourcesProvider provider) {
        GlassTabView tab = GlassTabView.createMainTab(context, provider, GlassTabView.TabAnimation.MUSIC, R.string.MainTabsContacts);
        tab.setText(PurplePlaylistsActivity.text("Title")); return tab;
    }
    public static BaseFragment fragment(int account) { return new PurplePlaylistsActivity(account); }
    public static BaseFragment mainTab(int account) { return PurplePlaylistsActivity.mainTab(account); }
    public static boolean ownsContactTab() { return true; }
    public static void contacts(BaseFragment host, int account) {
        Bundle args = new Bundle(); args.putBoolean("needPhonebook", true); args.putBoolean("needFinishFragment", false);
        ContactsActivity contacts = new ContactsActivity(args); contacts.setCurrentAccount(account); host.presentFragment(contacts);
    }
    public static void menu(BaseFragment host, ItemOptions options, int account) {
        options.add(R.drawable.msg_contacts, LocaleController.getString(R.string.Contacts), () -> contacts(host, account));
        options.add(R.drawable.msg_filled_data_music, PurplePlaylistsActivity.text("Title"), () -> host.presentFragment(fragment(account)));
    }
    public static boolean tabMenu(BaseFragment host, View anchor, int account) {
        if (host.getParentActivity() == null) return false;
        ItemOptions options = ItemOptions.makeOptions(host, anchor); menu(host, options, account); options.show(); return true;
    }
    private PurplePlaylistsUI() { }
}
