package org.telegram.messenger.purple;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PurpleFolderProjection {
    private static final class Publication {
        final PurpleCore.Loaded state;
        final int revision;
        final PurpleWorkMode.View[] views;

        Publication(PurpleCore.Loaded state, int revision, PurpleWorkMode.View[] views) {
            this.state = state;
            this.revision = revision;
            this.views = views;
        }
    }

    public static final class Frame {
        public final PurpleFolderSelection.Snapshot<MessagesController.DialogFilter> selection;
        public final List<MessagesController.DialogFilter> filters;
        private final Publication publication;
        private final PurpleAccountFiles.Identity identity;

        private Frame(Publication publication, PurpleAccountFiles.Identity identity,
                long role, String definition, ArrayList<MessagesController.DialogFilter> filters) {
            this.publication = publication;
            this.identity = identity;
            this.filters = Collections.unmodifiableList(new ArrayList<>(filters));
            ArrayList<PurpleFolderSelection.Entry<MessagesController.DialogFilter>> entries = new ArrayList<>();
            for (MessagesController.DialogFilter filter : this.filters) {
                PurpleFolderSelection.Kind kind = filter.isDefault() ? PurpleFolderSelection.Kind.DEFAULT
                        : PurpleGate.isExtraView(filter) ? PurpleFolderSelection.Kind.VIEW
                        : PurpleFolderSelection.Kind.FOLDER;
                entries.add(new PurpleFolderSelection.Entry<>(kind, filter.id, filter.locked, filter));
            }
            selection = new PurpleFolderSelection.Snapshot<>(
                    new PurpleFolderSelection.Account(identity.key, identity.lifetime, role),
                    publication.revision, definition, entries);
        }

        public int account() {
            return identity.slot;
        }

        public boolean current() {
            return identity.current()
                    && selection.account.role == PurpleAccountOwner.observedRoleEpoch()
                    && valid(publication, identity.slot)
                    && identity.current()
                    && selection.account.role == PurpleAccountOwner.observedRoleEpoch();
        }
    }

    private static volatile Publication publication;

    public static void publish(PurpleCore.Loaded state, int revision) {
        PurpleWorkMode.View[] views = new PurpleWorkMode.View[UserConfig.MAX_ACCOUNT_COUNT];
        for (int account = 0; account < views.length; account++) {
            views[account] = PurpleWorkMode.view(account);
        }
        publication = new Publication(state, revision, views);
    }

    public static Frame capture(int account) {
        Publication observed = publication;
        if (!valid(observed, account)) return null;
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(account);
        if (identity == null) return null;
        long role = PurpleAccountOwner.observedRoleEpoch();
        ArrayList<MessagesController.DialogFilter> raw = MessagesController.getInstance(account).getDialogFiltersUnrestricted();
        ArrayList<MessagesController.DialogFilter> filters = PurpleGate.shownFiltersQuiet(account, raw);
        if (filters == null) return null;
        for (MessagesController.DialogFilter filter : filters) if (filter == null) return null;
        try {
            Frame result = new Frame(observed, identity, role,
                    PurpleStripWatch.stripIdentity(account, observed.state), filters);
            return result.current() ? result : null;
        } catch (IllegalArgumentException invalidProjection) {
            return null;
        }
    }

    private static boolean valid(Publication observed, int account) {
        if (observed == null || account < 0 || account >= observed.views.length) return false;
        if (publication != observed || PurpleGate.state() != observed.state
                || PurpleGate.generation() != observed.revision
                || PurpleWorkMode.view(account) != observed.views[account]) return false;
        return PurpleGate.state() == observed.state && PurpleGate.generation() == observed.revision
                && PurpleWorkMode.view(account) == observed.views[account] && publication == observed;
    }

    private PurpleFolderProjection() { }
}
