package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PurpleHistoryMutationCases {
    private static final byte[] SENTINEL = "inert history fixture".getBytes(StandardCharsets.UTF_8);

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void emptyDirectory(File directory) {
        String[] names = directory.list();
        if (!directory.isDirectory() || names == null || names.length != 0) {
            throw new IllegalArgumentException("a new empty private fixture directory is required");
        }
    }

    private static void writeAndSync(File file) throws Exception {
        require(file.createNewFile(), "fixture must be newly owned");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(SENTINEL);
            stream.getFD().sync();
        }
    }

    private static byte[] read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            return output.toByteArray();
        }
    }

    public static void run(File directory) throws Exception {
        emptyDirectory(directory);
        AtomicBoolean originalLifetime = new AtomicBoolean(true);
        PurpleSyncHistory.Current original = originalLifetime::get;
        File temporary = new File(directory, "retired.toml.tmp");
        File target = new File(directory, "retired.toml");
        require(original.current(), "original preflight is current");
        writeAndSync(temporary);
        originalLifetime.set(false);
        AtomicBoolean replacementLifetime = new AtomicBoolean(true);
        require(replacementLifetime.get(), "replacement lifetime exists independently");
        require(!PurpleSyncHistory.publishIfCurrent(original, temporary, target), "retired original cannot publish after fsync");
        require(!target.exists() && Arrays.equals(read(temporary), SENTINEL), "retirement preserves unpublished bytes without publication");
        require(temporary.delete(), "fixture owner may clean its own unpublished temporary");
        require(!target.exists(), "owned temporary cleanup cannot remove a published target");

        AtomicBoolean live = new AtomicBoolean(true);
        PurpleSyncHistory.Current current = live::get;
        File liveTemporary = new File(directory, "current.toml.tmp");
        File liveTarget = new File(directory, "current.toml");
        writeAndSync(liveTemporary);
        require(PurpleSyncHistory.publishIfCurrent(current, liveTemporary, liveTarget), "still-current original publishes");
        require(!liveTemporary.exists() && Arrays.equals(read(liveTarget), SENTINEL), "successful publication retains exact bytes");

        File first = new File(directory, "prune-first.toml");
        File second = new File(directory, "prune-second.toml");
        writeAndSync(first);
        writeAndSync(second);
        require(directory.list() != null && Arrays.equals(read(first), SENTINEL)
                && Arrays.equals(read(second), SENTINEL), "real enumeration and reads finish before retirement");
        live.set(false);
        require(!PurpleSyncHistory.deleteIfCurrent(current, first)
                && !PurpleSyncHistory.deleteIfCurrent(current, second), "retired original cannot prune or discard after IO");
        require(Arrays.equals(read(first), SENTINEL) && Arrays.equals(read(second), SENTINEL), "stale deletions leave exact files unchanged");

        AtomicBoolean deletionLifetime = new AtomicBoolean(true);
        PurpleSyncHistory.Current deletion = deletionLifetime::get;
        require(PurpleSyncHistory.deleteIfCurrent(deletion, first), "current deletion succeeds");
        deletionLifetime.set(false);
        require(!PurpleSyncHistory.deleteIfCurrent(deletion, second), "each deletion revalidates original lifetime");
        require(!first.exists() && Arrays.equals(read(second), SENTINEL), "retirement between deletions preserves the later entry");
        System.out.println("REAL_HISTORY_MUTATION_BOUNDARY_CASES_COMPLETED");
    }

    public static void runAndroidUnownedTemporaryCase(File directory) throws Exception {
        emptyDirectory(directory);
        File target = new File(directory, "unowned.toml");
        File temporary = new File(directory, "unowned.toml.tmp");
        writeAndSync(temporary);
        PurpleSyncHistory history = new PurpleSyncHistory(directory, PurpleSyncHistory.NATIVE, System::currentTimeMillis);
        Method method = PurpleSyncHistory.class.getDeclaredMethod("writePrivate", File.class, byte[].class);
        method.setAccessible(true);
        require(!(Boolean) method.invoke(history, target, SENTINEL), "actual Android helper refuses a pre-existing temporary");
        require(!target.exists() && Arrays.equals(read(temporary), SENTINEL), "unowned temporary survives actual helper error cleanup");
    }
}
