package org.telegram.messenger.purple;

import java.math.BigInteger;
import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleArchiveUsage {
    private static final class Frame implements AutoCloseable {
        final PurpleArchiveFiles.Directory directory;
        final PurpleArchiveFiles.Listing listing;
        String[] page = new String[0]; int position;
        Frame(PurpleArchiveFiles.Directory directory) { this.directory = directory; listing = directory.listing(); }
        public void close() { listing.close(); }
    }
    static String scan(PurpleArchiveStore store) throws Exception {
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        BigInteger ordinal = BigInteger.ZERO;
        String bytes = "0", cursor = "";
        JSONArray entries = new JSONArray();
        try {
            store.requireCurrent(); stack.push(new Frame(store.directory));
            while (!stack.isEmpty()) {
                store.requireCurrent(); Frame frame = stack.peek();
                if (frame.position == frame.page.length) {
                    frame.page = frame.listing.next(); frame.position = 0;
                    if (frame.page.length == 0) { frame.close(); stack.pop(); continue; }
                }
                String child = frame.page[frame.position++];
                PurpleArchiveFiles.Mark mark = PurpleArchiveFiles.markAny(frame.directory.file(child));
                if (mark == null) continue;
                if ((mark.mode & 0170000) == 0040000) {
                    if (stack.size() >= 8) throw new PurpleArchiveStore.Failure("Archive usage directory depth refused");
                    stack.push(new Frame(new PurpleArchiveFiles.Directory(frame.directory.file(child), true)));
                } else {
                    ordinal = ordinal.add(BigInteger.ONE);
                    String token = ordinal.toString();
                    if (token.length() > 20) throw new PurpleArchiveStore.Failure("Archive usage cursor exhausted");
                    token = "00000000000000000000".substring(token.length()) + token;
                    entries.put(new JSONObject().put("token", token).put("bytes", Long.toString(mark.bytes)));
                    if (entries.length() == 128) {
                        JSONObject result = page(store, bytes, cursor, entries, false);
                        bytes = result.getString("bytes"); cursor = result.getString("cursor"); entries = new JSONArray();
                    }
                }
            }
            return page(store, bytes, cursor, entries, true).getString("bytes");
        } finally { while (!stack.isEmpty()) stack.pop().close(); }
    }
    private static JSONObject page(PurpleArchiveStore store, String bytes, String cursor, JSONArray entries, boolean complete) throws Exception {
        store.requireCurrent();
        JSONObject result = PurpleArchiveCore.call("usage", store.arguments().put("generation", store.binding.generation)
            .put("bytes", bytes).put("cursor", cursor).put("entries", entries).put("complete", complete), null);
        store.requireCurrent();
        if (!result.getString("status").equals("Ready")) throw new PurpleArchiveStore.Failure("Archive usage scan refused");
        return result;
    }
    private PurpleArchiveUsage() {}
}
