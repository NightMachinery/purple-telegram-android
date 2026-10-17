package org.telegram.messenger.purple;

import android.view.View;
import android.view.ViewGroup;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ContactsEmptyView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Predicate;

public final class PurpleContactsFilter {
    private final int currentAccount;
    private final int onlyUsers;
    private final Predicate<TLRPC.User> userFilter;
    private HashMap<String, ArrayList<TLRPC.TL_contact>> filteredUsersSectionsDict;
    private ArrayList<String> filteredSortedUsersSectionsArray;
    private ArrayList<TLRPC.TL_contact> filteredOnlineContacts;

    public PurpleContactsFilter(int currentAccount, int onlyUsers, Predicate<TLRPC.User> userFilter) {
        this.currentAccount = currentAccount;
        this.onlyUsers = onlyUsers;
        this.userFilter = userFilter;
    }

    public HashMap<String, ArrayList<TLRPC.TL_contact>> usersSectionsDict() {
        return filteredUsersSectionsDict;
    }

    public ArrayList<String> sortedUsersSectionsArray() {
        return filteredSortedUsersSectionsArray;
    }

    public ArrayList<TLRPC.TL_contact> onlineContacts() {
        return filteredOnlineContacts;
    }

    private ArrayList<TLRPC.TL_contact> filterContacts(ArrayList<TLRPC.TL_contact> contacts) {
        final ArrayList<TLRPC.TL_contact> result = new ArrayList<>();
        if (contacts == null) {
            return result;
        }
        for (TLRPC.TL_contact contact : contacts) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(contact.user_id);
            if (userFilter.test(user)) {
                result.add(contact);
            }
        }
        return result;
    }

    public void rebuild(ArrayList<TLRPC.TL_contact> onlineContacts) {
        final HashMap<String, ArrayList<TLRPC.TL_contact>> sourceDict = onlyUsers == 2
                ? ContactsController.getInstance(currentAccount).usersMutualSectionsDict
                : ContactsController.getInstance(currentAccount).usersSectionsDict;
        final ArrayList<String> sourceSections = onlyUsers == 2
                ? ContactsController.getInstance(currentAccount).sortedUsersMutualSectionsArray
                : ContactsController.getInstance(currentAccount).sortedUsersSectionsArray;
        filteredUsersSectionsDict = new HashMap<>();
        filteredSortedUsersSectionsArray = new ArrayList<>();
        for (String section : sourceSections) {
            final ArrayList<TLRPC.TL_contact> contacts = filterContacts(sourceDict.get(section));
            if (!contacts.isEmpty()) {
                filteredUsersSectionsDict.put(section, contacts);
                filteredSortedUsersSectionsArray.add(section);
            }
        }
        filteredOnlineContacts = filterContacts(onlineContacts);
    }

    public static boolean canSeeOrPeek(int currentAccount, TLRPC.User user) {
        return PurpleLastSeen.canSeeOrPeek(user);
    }

    public static int acceptedCount(ArrayList<?> results, Predicate<Object> accept) {
        int count = 0;
        for (int i = 0; i < results.size(); i++) {
            if (accept.test(results.get(i))) {
                count++;
            }
        }
        return count;
    }

    public static Object acceptedItem(ArrayList<?> results, int index, Predicate<Object> accept) {
        for (int i = 0; i < results.size(); i++) {
            Object result = results.get(i);
            if (accept.test(result) && index-- == 0) {
                return result;
            }
        }
        return null;
    }

    public static void dropRejected(ArrayList<Object> users, ArrayList<CharSequence> names, Predicate<TLRPC.User> acceptUser) {
        for (int i = users.size() - 1; i >= 0; i--) {
            Object user = users.get(i);
            if (user instanceof TLRPC.User && !acceptUser.test((TLRPC.User) user)) {
                users.remove(i);
                names.remove(i);
            }
        }
    }

    public static void bindEmpty(View view, boolean filtered) {
        if (view instanceof ViewGroup
                && ((ViewGroup) view).getChildAt(0) instanceof ContactsEmptyView) {
            ((ContactsEmptyView) ((ViewGroup) view).getChildAt(0))
                    .setFilteredEmptyState(filtered);
        }
    }
}
