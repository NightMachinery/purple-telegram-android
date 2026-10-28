package org.telegram.messenger.purple;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

final class PurpleSyncRetainedFiles {
    static boolean valid(File root) {
        File directory = new File(root, "retained-bases");
        try { if (missing(directory)) return true; records(root); return true; } catch (Exception e) { return false; }
    }
    static byte[][] records(File root) throws IOException {
        PurpleAccountFileSnapshot.worker();
        File directory = new File(root, "retained-bases");
        if (missing(directory)) return new byte[0][];
        String identity = PurpleAccountFileSnapshot.durableDirectoryIdentity(directory);
        if (identity.equals("missing") || !directory.isDirectory() || !directory.getCanonicalFile().equals(directory.getAbsoluteFile())) throw new IOException("InvalidRetention");
        File[] files = directory.listFiles();
        if (files == null || files.length > 1024) throw new IOException("RetentionFull");
        ArrayList<byte[]> records = new ArrayList<>(); long total = 0;
        for (File file : files) {
            if (!file.getName().matches("[0-9a-f]{64}\\.json")) throw new IOException("InvalidRetention");
            PurpleAccountFileSnapshot.Durable snapshot = PurpleAccountFileSnapshot.durable("read", file, null, null, 262144, 0);
            if (snapshot.bytes == null || !file.getName().equals(hash(snapshot.bytes) + ".json")) throw new IOException("InvalidRetention");
            total += snapshot.bytes.length;
            if (total > 67108864) throw new IOException("RetentionFull");
            records.add(snapshot.bytes);
        }
        if (!identity.equals(PurpleAccountFileSnapshot.durableDirectoryIdentity(directory))) throw new IOException("Changed");
        return records.toArray(new byte[0][]);
    }
    private static boolean missing(File path) throws IOException {
        try { android.system.Os.lstat(path.getPath()); return false; }
        catch (android.system.ErrnoException e) {
            if (e.errno == android.system.OsConstants.ENOENT) return true;
            throw new IOException("InvalidRetention", e);
        }
    }
    static String hash(byte[] bytes) {
        return PurpleSyncChatMoveCore.request("hash", PurpleSyncChatCore.json("bytes", PurpleSyncRetainedBases.encode(bytes)).toString().getBytes(StandardCharsets.UTF_8)).fields.optString("hash");
    }
    static byte[][] combine(byte[][] first, byte[][] second) {
        byte[][] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length); return result;
    }
    static void pin(File root, byte[] record) throws IOException {
        PurpleAccountFileSnapshot.worker();
        byte[][] existing = records(root);
        String hash = hash(record);
        for (byte[] value : existing) if (Arrays.equals(value, record)) {
            File file = new File(root, "retained-bases/" + hash + ".json");
            PurpleAccountFileSnapshot.Durable opening = PurpleAccountFileSnapshot.durable("read", file, null, null, 262144, 0);
            PurpleAccountFileSnapshot.durable("barrier", file, opening, null, 262144, 0);
            return;
        }
        long total = record.length;
        for (byte[] value : existing) total += value.length;
        if (record.length > 262144 || existing.length >= 1024 || total > 67108864) throw new IOException("RetentionFull");
        File directory = new File(root, "retained-bases");
        PurpleAccountFileSnapshot.durableDirectory(directory);
        File file = new File(directory, hash + ".json");
        PurpleAccountFileSnapshot.Durable opening = PurpleAccountFileSnapshot.durable("read", file, null, null, 262144, 0);
        if (opening.bytes != null && !Arrays.equals(opening.bytes, record)) throw new IOException("Changed");
        PurpleAccountFileSnapshot.durable(opening.bytes == null ? "write" : "barrier", file, opening, record, 262144, 0);
    }
    static void materialize(File root, byte[] state) throws Exception {
        PurpleAccountFileSnapshot.worker();
        JSONArray records = PurpleSyncRetainedBases.request("inspect", state, null).fields.getJSONArray("entries");
        if (records.length() == 0) return;
        byte[][] existing = records(root);
        java.util.Set<String> hashes = new java.util.HashSet<>();
        long total = 0;
        for (byte[] value : existing) { hashes.add(hash(value)); total += value.length; }
        for (int i = 0; i < records.length(); ++i) {
            JSONObject entry = records.getJSONObject(i);
            if (hashes.add(entry.getString("sha256"))) total += entry.getString("record").getBytes(StandardCharsets.UTF_8).length;
        }
        if (hashes.size() > 1024 || total > 67108864) throw new IOException("RetentionFull");
        File directory = new File(root, "retained-bases");
        PurpleAccountFileSnapshot.durableDirectory(directory);
        for (int i = 0; i < records.length(); ++i) {
            JSONObject entry = records.getJSONObject(i);
            byte[] bytes = entry.getString("record").getBytes(StandardCharsets.UTF_8);
            File file = new File(directory, entry.getString("sha256") + ".json");
            PurpleAccountFileSnapshot.Durable opening = PurpleAccountFileSnapshot.durable("read", file, null, null, 262144, 0);
            if (opening.bytes != null && !Arrays.equals(opening.bytes, bytes)) throw new IOException("Changed");
            PurpleAccountFileSnapshot.durable(opening.bytes == null ? "write" : "barrier", file, opening, bytes, 262144, 0);
        }
        records(root);
    }
    private PurpleSyncRetainedFiles() { }
}
