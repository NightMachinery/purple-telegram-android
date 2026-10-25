package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.HashMap;
import org.telegram.tgnet.TLRPC;

public final class PurpleArchiveOrigin {
    public static final class Cause {
        final int origin, pts;
        final String stream, token;
        final long selection;
        Cause(int origin, String stream, int pts, String token) { this(origin, stream, pts, token, PurpleArchiveViewerModel.selectionGeneration()); }
        Cause(int origin, String stream, int pts, String token, long selection) { this.origin = origin; this.stream = stream; this.pts = pts; this.token = token; this.selection = selection; }
    }
    public static final class Batch {
        final PurpleAccountFiles.Identity identity;
        final ArrayList<PurpleArchiveCapture.Input> inputs = new ArrayList<>();
        final HashMap<String, Cause> deletes = new HashMap<>();
        boolean refused;
        long bytes;
        public Batch(int slot) { identity = android.os.Build.VERSION.SDK_INT >= 23 ? PurpleAccountFiles.capture(slot) : null; }
        public void edit(TLRPC.Message message, int pts) {
            if (identity == null) return;
            if (pts <= 0) { refused = true; inputs.clear(); return; }
            long channel = message.peer_id == null ? 0 : message.peer_id.channel_id;
            add(message, new Cause(1, channel == 0 ? "global" : "channel_" + channel, pts, ""), false);
        }
        public void delete(long dialog, ArrayList<Integer> ids, int pts) {
            if (pts <= 0 || ids == null || ids.size() > 4096 || deletes.size() + ids.size() > 4096) { refused = true; return; }
            for (int id : ids) deletes.put(dialog + ":" + id, new Cause(2, dialog == 0 ? "global" : "channel_" + -dialog, pts, ""));
        }
        void add(TLRPC.Message message, Cause cause, boolean preimage) {
            if (identity == null || refused) return;
            try {
                PurpleArchiveCapture.Input input = new PurpleArchiveCapture.Input(PurpleArchiveNormalize.key(identity.key, message).toString(),
                    PurpleArchiveNormalize.body(message).toString(), cause, preimage);
                bytes += input.body.length() * 2L;
                if (inputs.size() >= 64 || bytes > 8 * 1024 * 1024) { refused = true; inputs.clear(); return; }
                inputs.add(input);
            } catch (Exception | LinkageError e) { refused = true; inputs.clear(); }
        }
    }
    static Cause baseline() { return new Cause(0, "", 0, ""); }
    static Cause local() { return new Cause(3, "", 0, PurpleArchiveBinding.token()); }
    private PurpleArchiveOrigin() {}
}
