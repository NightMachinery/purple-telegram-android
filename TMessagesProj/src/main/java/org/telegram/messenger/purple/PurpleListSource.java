/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Work Mode lists a chat is in, read without opening a write session.
 *
 * The lists box reads its rows through a write session when it can get one,
 * because a row is only worth ticking when the tick can be written. When the
 * session is refused (the settings owner is not chosen yet, a file changed, a
 * file needs recovery) the box still has to show which lists exist and which
 * of them hold this chat, read-only. The membership shown must be the one the
 * running resolution uses, so this reads the inputs PurpleWorkMode.reload
 * reads:
 *
 * - with a ready settings owner, the account's own file layered over the
 *   app-wide file the gate runs on, with the local Work Mode choice, exactly
 *   as the running per-account view layers them;
 * - with no owner yet, the app-wide file the gate runs on, or its last good
 *   copy when the gate runs from that, which is the running legacy route.
 *
 * When the running view used an input this read cannot reproduce, or the
 * account's own file is gone and has to be recovered, or cannot be read
 * or parsed (the running view fell back to its good copy), or local.toml
 * cannot be read or parsed (the running view kept an earlier copy), or the
 * gate runs from the app-wide good copy, the lists are returned by name
 * only, with no membership, as {@link #NAMES_ONLY}: list definitions are
 * app-wide in every mode, but a checkmark from another file could
 * contradict the chat list.
 *
 * Nothing here writes, and nothing here is logged with an id.
 */
public final class PurpleListSource {
    public static final String ROUTED = "routed";
    public static final String APP_WIDE = "app_wide";
    public static final String APP_WIDE_GOOD = "app_wide_good";
    public static final String NAMES_ONLY = "names_only";
    public static final String NONE = "none";

    public static final class Lists {
        public final List<PurpleCore.ListEntry> entries;
        /** One of the source constants above: where the entries came from. */
        public final String source;

        Lists(List<PurpleCore.ListEntry> entries, String source) {
            this.entries = entries;
            this.source = source;
        }

        /** Whether {@link PurpleCore.ListEntry#member} says which lists hold the chat. */
        public boolean membership() {
            return !NAMES_ONLY.equals(source) && !NONE.equals(source);
        }
    }

    private PurpleListSource() {
    }

    /**
     * @param slot     the account the box was opened for
     * @param peer     the chat's bare id, as the write session takes it
     * @param lastGood whether the running preset was loaded from the app-wide
     *                 file's last good copy
     */
    public static Lists read(int slot, long peer, boolean lastGood) {
        try {
            PurpleCore.ensureLoaded();
            Lists appWide = appWide(peer, lastGood);
            if (!PurpleAccountOwner.ready()) return appWide == null ? none() : appWide;
            Lists routed = routed(slot, peer, lastGood);
            if (routed != null) return routed;
            if (appWide == null) return none();
            List<PurpleCore.ListEntry> names = new ArrayList<>();
            for (PurpleCore.ListEntry entry : appWide.entries) names.add(entry.withoutMembers());
            return new Lists(names, NAMES_ONLY);
        } catch (Exception | UnsatisfiedLinkError e) {
            PurpleDiagLog.event("lists", "read_failed", PurpleDiagLog.slot(slot), PurpleDiagLog.error(e));
        }
        return none();
    }

    private static Lists none() {
        return new Lists(Collections.<PurpleCore.ListEntry>emptyList(), NONE);
    }

    /** The app-wide file the gate runs on, or null when it cannot be read. */
    private static Lists appWide(long peer, boolean lastGood) {
        File global = PurpleAccountSettings.globalFile();
        byte[] bytes = bytes(lastGood ? new File(global.getPath() + ".good") : global);
        return bytes == null ? null : new Lists(PurpleCore.listsFor(bytes, peer), lastGood ? APP_WIDE_GOOD : APP_WIDE);
    }

    /**
     * The account's running view, or null when its inputs cannot be read the
     * way PurpleWorkMode.reload read them.
     */
    private static Lists routed(int slot, long peer, boolean lastGood) throws Exception {
        if (lastGood) return null;
        PurpleAccountFiles.Identity id = PurpleAccountFiles.capture(slot);
        String owner = PurpleAccountOwner.owner();
        String global = PurpleAccountOwner.global();
        if (id == null || owner == null || global == null) return null;
        PurpleAccountFiles own = PurpleAccountFiles.resolve(id.key, owner);
        PurpleAccountFiles app = PurpleAccountFiles.resolve(global, owner);
        if (own == null || app == null) return null;
        // local.toml as PurpleWorkModeModel.localForReload takes it: missing
        // means the defaults, unreadable means an earlier copy this cannot see.
        PurpleAccountFileSnapshot local = PurpleAccountFileSnapshot.read(PurpleAccountFiles.file("local.toml"));
        byte[] localBytes;
        if (local.status == PurpleAccountFileSnapshot.Status.Missing) localBytes = new byte[0];
        else if (local.status == PurpleAccountFileSnapshot.Status.Present) localBytes = local.bytes;
        else return null;
        int mode = PurpleRoutedWriteModel.modeNative(localBytes, id.key, global);
        if (mode < 0) return null;
        // Following the app-wide file, or being its account, takes membership
        // from that file alone, whatever the own file holds. Otherwise the
        // own file decides. One never written adds no layer, as in the
        // running view. One that is gone, by the rule the account editor
        // refuses with "recovery required" (it is the root file, or its good
        // copy is still there), has to be recovered, and one that cannot be
        // read or parsed made the running view use its good copy.
        boolean appWideMembers = id.key.equals(global) || mode == 1;
        PurpleAccountFileSnapshot ownFile = PurpleAccountFileSnapshot.read(own.settings);
        byte[] ownBytes;
        if (ownFile.status == PurpleAccountFileSnapshot.Status.Present) ownBytes = ownFile.bytes;
        else if (appWideMembers) ownBytes = null;
        else if (ownFile.status == PurpleAccountFileSnapshot.Status.Missing && !id.key.equals(owner)
                && PurpleAccountFileSnapshot.read(new File(own.settings.getPath() + ".good")).status
                        == PurpleAccountFileSnapshot.Status.Missing) ownBytes = null;
        else return null;
        byte[] globalBytes = bytes(app.settings);
        if (globalBytes == null) return null;
        JSONObject result = new JSONObject(PurpleRoutedWriteModel.listNative(localBytes, ownBytes, own.settings.getPath(),
                globalBytes, app.settings.getPath(), id.key, global, null, peer, false, null));
        if (!result.optBoolean("ok")) return null;
        return new Lists(PurpleCore.listEntries(result.getJSONArray("lists")), ROUTED);
    }

    private static byte[] bytes(File file) {
        PurpleAccountFileSnapshot snapshot = PurpleAccountFileSnapshot.read(file);
        return snapshot.status == PurpleAccountFileSnapshot.Status.Present ? snapshot.bytes : null;
    }
}
