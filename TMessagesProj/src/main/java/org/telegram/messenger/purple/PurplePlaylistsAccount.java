package org.telegram.messenger.purple;

import org.json.JSONObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.IOException;

final class PurplePlaylistsAccount {
    static final class Captured {
        final PurpleAccountFiles.Identity identity;
        final String owner;
        final String global;
        private final long revision;
        private final long role;
        private final UserConfig config;
        private final ConnectionsManager connection;

        private Captured(PurpleAccountFiles.Identity identity, String owner, String global,
                long revision, long role) {
            this.identity = identity;
            this.owner = owner;
            this.global = global;
            this.revision = revision;
            this.role = role;
            config = UserConfig.getInstance(identity.slot);
            connection = ConnectionsManager.getInstance(identity.slot);
        }

        boolean same(Captured other) {
            return other != null && identity.slot == other.identity.slot
                    && identity.userId == other.identity.userId && identity.lifetime == other.identity.lifetime
                    && identity.key.equals(other.identity.key) && owner.equals(other.owner) && global.equals(other.global)
                    && revision == other.revision && role == other.role
                    && config == other.config && connection == other.connection;
        }

        boolean current() {
            return PurpleAccountOwner.observedRevision() == revision
                    && PurpleAccountOwner.observedRoleEpoch() == role
                    && UserConfig.getInstance(identity.slot) == config
                    && ConnectionsManager.getInstance(identity.slot) == connection
                    && identity.current() && PurpleAccountOwner.ready()
                    && owner.equals(PurpleAccountOwner.owner())
                    && global.equals(PurpleAccountOwner.global())
                    && PurpleAccountOwner.observedRevision() == revision
                    && PurpleAccountOwner.observedRoleEpoch() == role;
        }
    }

    static Captured capture(int slot) {
        if (!PurpleAccountOwner.ready()) return null;
        PurpleAccountFiles.Identity identity = PurpleAccountFiles.capture(slot);
        String owner = PurpleAccountOwner.owner(), global = PurpleAccountOwner.global();
        long revision = PurpleAccountOwner.revision(), role = PurpleAccountOwner.roleEpoch();
        if (identity == null || owner == null || global == null) return null;
        Captured captured = new Captured(identity, owner, global, revision, role);
        return captured.current() ? captured : null;
    }

    private static final class Route implements PurplePlaylistsStore.Current {
        final Captured captured;
        final PurplePlaylistsStore.Current command;
        final File root = PurpleAccountFiles.root();
        final File localFile = new File(root, "local.toml");
        final File ownerFile = new File(root, "accounts/root.toml");
        final PurpleAccountFileSnapshot.Durable local;
        final PurpleAccountFileSnapshot.Durable owner;

        Route(Captured captured, PurplePlaylistsStore.Current command) throws IOException {
            this.captured = captured;
            this.command = command;
            if (!live()) throw new IOException("AccountChanged");
            local = authority(localFile);
            owner = authority(ownerFile);
            try {
                JSONObject route = new JSONObject(PurpleGlobalChoiceModel.planNative(
                        local.bytes, owner.bytes, captured.owner, captured.global, captured.identity.key));
                if (!route.optBoolean("ok")) throw new IOException("AccountAuthorityUnavailable");
            } catch (IOException e) { throw e; }
            catch (Exception | UnsatisfiedLinkError e) { throw new IOException("AccountAuthorityUnavailable", e); }
            if (!current()) throw new IOException("AccountChanged");
        }

        private boolean live() {
            return command != null && command.current() && root.equals(PurpleAccountFiles.root())
                    && captured.current();
        }

        @Override public boolean current() {
            try {
                return live() && local.same(authority(localFile))
                        && owner.same(authority(ownerFile)) && live();
            } catch (Exception | UnsatisfiedLinkError e) { return false; }
        }
    }

    private static PurpleAccountFileSnapshot.Durable authority(File file) throws IOException {
        return PurpleAccountFileSnapshot.durable("read", file, null, null,
                PurpleAccountFileSnapshot.LIMIT, 0);
    }

    static PurplePlaylistsStore open(Captured captured, PurplePlaylistsStore.Current command)
            throws IOException {
        PurpleAccountFileSnapshot.worker();
        if (captured == null) throw new IOException("AccountUnavailable");
        Route route = new Route(captured, command);
        PurpleAccountFiles paths = PurpleAccountFiles.resolve(captured.identity.key, captured.owner);
        if (paths == null || !paths.playlists.equals(new File(paths.directory, "playlists")))
            throw new IOException("InvalidAccountPath");
        File root = route.root;
        String prefix = root.getAbsolutePath() + File.separator;
        String path = paths.playlists.getAbsolutePath();
        if (!path.startsWith(prefix)) throw new IOException("InvalidAccountPath");
        String relative = path.substring(prefix.length());
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("InvalidAccountPath");
        }
        if (!route.current()) throw new IOException("AccountChanged");
        PurplePlaylistsPhysical physical = new PurplePlaylistsPhysical(
                root.getAbsolutePath(), relative, route, true);
        if (!route.current()) {
            physical.close();
            throw new IOException("AccountChanged");
        }
        return new PurplePlaylistsStore(captured.identity.userId, physical);
    }

    private PurplePlaylistsAccount() { throw new AssertionError(); }
}
