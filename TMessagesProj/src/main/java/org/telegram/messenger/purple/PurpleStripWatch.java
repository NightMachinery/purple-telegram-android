package org.telegram.messenger.purple;

/**
 * Compares the strip membership already used by each DialogsActivity instance.
 * A generation changes on peek, schedule and list reloads even when tabs do not.
 * The exact preset, view names and folder flags remain the comparison inputs;
 * the first build has no prior identity and does not count as a change.
 */
public final class PurpleStripWatch {

    private String identity;

    public boolean changed(int account, PurpleCore.Loaded state) {
        final String next = stripIdentity(account, state);
        final boolean changed = identity != null && !identity.equals(next);
        identity = next;
        return changed;
    }

    public static String stripIdentity(int account, PurpleCore.Loaded state) {
        if (state == null || PurpleWorkMode.stock(account)) {
            return "";
        }
        final StringBuilder identity = new StringBuilder();
        identity.append(state.normal || state.preset == null ? "" : state.preset);
        for (int a = 0, n = state.views.size(); a < n; ++a) {
            identity.append('\n').append(state.views.get(a).name);
        }
        identity.append('\t');
        for (int a = 0, n = state.folders.size(); a < n; ++a) {
            final PurpleCore.FolderEntry folder = state.folders.get(a);
            identity.append(folder.all ? "*ALL" : folder.name)
                    .append(folder.enabled ? '+' : '-')
                    .append(folder.show ? '+' : '-')
                    .append('\n');
        }
        return identity.toString();
    }
}
