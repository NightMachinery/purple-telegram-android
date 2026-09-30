/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Copies of settings.toml kept before account sync, a restore or an undo
 * replaces the file, so every such replacement can be put back.
 *
 * The desktop keeps the same store under its config directory's
 * {@code sync/history}. Here it is {@code files/purple/sync-history}, a sibling
 * of the account sync store rather than inside it, because that store pauses
 * on any file in its root it does not own. The layout and every rule follow
 * the desktop: an entry is {@code <id>.toml} holding the exact bytes plus
 * {@code <id>.json} holding the metadata; the id is sixteen decimal digits of
 * creation milliseconds, a dash and sixteen lowercase hex digits; an entry is
 * valid only when both files are private regular files and the bytes still
 * match the recorded size and fingerprint; the newest 30 valid entries are
 * kept, plus one the caller asks to keep for the length of a save.
 *
 * The fingerprint and the version key check belong to the shared core and
 * come through the settings sync flow bridge; without the native library
 * every save is refused and no entry reads as valid.
 *
 * Nothing calls this yet.
 */
public final class PurpleSyncHistory {
    public static final int LIMIT = 30;
    public static final int MAX_TEXT_BYTES = 256 * 1024;
    public static final int MAX_LABEL_LENGTH = 256;
    static final int MAX_METADATA_BYTES = 16 * 1024;

    private static final int METADATA_VERSION = 1;
    private static final int TIME_DIGITS = 16;
    private static final int SUFFIX_DIGITS = 16;
    private static final int ID_LENGTH = TIME_DIGITS + 1 + SUFFIX_DIGITS;
    private static final int ID_ATTEMPTS = 4;
    private static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private static final int PRIVATE_DIRECTORY_MODE = 0700;
    private static final int PRIVATE_FILE_MODE = 0600;
    private static final String TEXT_SUFFIX = ".toml";
    private static final String METADATA_SUFFIX = ".json";
    private static final String TEMPORARY_SUFFIX = ".tmp";

    // One process, one History: saves, lists and reads never interleave, so
    // a prune cannot remove an entry a read is in the middle of.
    private static final Object LOCK = new Object();

    public enum Reason {
        BeforeUpdate("before_update"),
        BeforeChoice("before_choice"),
        BeforeRestore("before_restore"),
        BeforeUndo("before_undo");

        final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        static Reason parse(Object value) {
            if (!(value instanceof String)) {
                return null;
            }
            for (Reason reason : values()) {
                if (reason.wire.equals(value)) {
                    return reason;
                }
            }
            return null;
        }
    }

    public static final class Entry {
        public final String id;
        public final long createdMs;
        public final Reason reason;
        public final String label;
        public final String versionKey;
        public final String fingerprint;
        public final long size;
        /** False when settings.toml did not exist; the copy is then empty. */
        public final boolean existed;

        Entry(String id, long createdMs, Reason reason, String label,
                String versionKey, String fingerprint, long size,
                boolean existed) {
            this.id = id;
            this.createdMs = createdMs;
            this.reason = reason;
            this.label = label;
            this.versionKey = versionKey;
            this.fingerprint = fingerprint;
            this.size = size;
            this.existed = existed;
        }
    }

    /** What only the shared core may decide. */
    interface Core {
        /** The core's settings fingerprint of the bytes, or null. */
        String fingerprint(byte[] bytes);

        /** Whether the core accepts the key as a config version key. */
        boolean isVersionKey(String key);
    }

    interface Clock {
        long nowMs();
    }

    static final Core NATIVE = new Core() {
        @Override
        public String fingerprint(byte[] bytes) {
            return PurpleSyncCore.settingsFingerprint(bytes);
        }

        @Override
        public boolean isVersionKey(String key) {
            return PurpleSyncCore.isConfigVersionKey(key);
        }
    };

    private static final class Loaded {
        final Entry entry;
        final byte[] text;

        Loaded(Entry entry, byte[] text) {
            this.entry = entry;
            this.text = text;
        }
    }

    private final File directory;
    private final Core core;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PurpleSyncHistory() {
        this(new File(new File(ApplicationLoader.getFilesDirFixed(), "purple"),
                "sync-history"), NATIVE, System::currentTimeMillis);
    }

    PurpleSyncHistory(File directory, Core core, Clock clock) {
        this.directory = directory;
        this.core = core;
        this.clock = clock;
    }

    public File directory() {
        return directory;
    }

    /**
     * Keeps a copy of settings.toml as it is now.
     *
     * @param text the file's exact bytes, or null when it does not exist
     * @param label a short source description, at most 256 UTF-16 units
     * @param versionKey the config version key of these bytes when known, or ""
     * @param keepId an entry the prune after this save must not remove, or null
     * @return the saved entry, or null when nothing was saved
     */
    public Entry save(byte[] text, Reason reason, String label,
            String versionKey, String keepId) {
        final byte[] bytes = (text != null) ? text : new byte[0];
        if (reason == null || label == null || versionKey == null
                || bytes.length > MAX_TEXT_BYTES
                || label.length() > MAX_LABEL_LENGTH
                || (!versionKey.isEmpty() && !core.isVersionKey(versionKey))) {
            FileLog.e("Purple: refused an invalid settings history entry.");
            return null;
        }
        final long createdMs = clock.nowMs();
        if (createdMs <= 0 || createdMs > MAX_SAFE_INTEGER) {
            FileLog.e("Purple: settings history has no usable clock.");
            return null;
        }
        final String fingerprint = core.fingerprint(bytes);
        if (fingerprint == null) {
            FileLog.e("Purple: settings history has no core fingerprint.");
            return null;
        }
        synchronized (LOCK) {
            if (!prepareDirectories()) {
                return null;
            }
            final String id = unusedId(createdMs);
            if (id == null) {
                FileLog.e("Purple: could not choose a settings history id.");
                return null;
            }
            final Entry entry = new Entry(id, createdMs, reason, label,
                    versionKey, fingerprint, bytes.length, text != null);
            final byte[] metadata = serialize(entry);
            final boolean written = metadata != null
                    && writePrivate(textFile(id), bytes)
                    && writePrivate(metadataFile(id), metadata);
            final Loaded saved = written ? load(id) : null;
            if (saved == null || !Arrays.equals(saved.text, bytes)) {
                FileLog.e("Purple: could not save settings history " + id
                        + ".");
                discard(id);
                return null;
            }
            final Set<String> keep = new HashSet<>();
            keep.add(id);
            if (keepId != null && !keepId.isEmpty()) {
                keep.add(keepId);
            }
            prune(keep);
            return saved.entry;
        }
    }

    /** Every valid entry, newest first. */
    public List<Entry> list() {
        synchronized (LOCK) {
            if (!existingDirectories()) {
                return Collections.emptyList();
            }
            final List<Entry> result = new ArrayList<>();
            int invalid = 0;
            for (String id : entryIds(entryNames())) {
                final Loaded loaded = load(id);
                if (loaded != null) {
                    result.add(loaded.entry);
                } else {
                    ++invalid;
                }
            }
            if (invalid != 0) {
                FileLog.e("Purple: skipped " + invalid
                        + " invalid settings history entries.");
            }
            sortNewestFirst(result);
            return Collections.unmodifiableList(result);
        }
    }

    /** The exact bytes of a valid entry, or null. */
    public byte[] read(String id) {
        synchronized (LOCK) {
            if (!isHistoryId(id) || !existingDirectories()) {
                FileLog.e("Purple: no settings history entry " + id + ".");
                return null;
            }
            final Loaded loaded = load(id);
            if (loaded == null) {
                FileLog.e("Purple: settings history entry " + id
                        + " is invalid.");
                return null;
            }
            return loaded.text;
        }
    }

    static boolean isHistoryId(String id) {
        if (id == null || id.length() != ID_LENGTH
                || id.charAt(TIME_DIGITS) != '-') {
            return false;
        }
        for (int i = 0; i != ID_LENGTH; ++i) {
            if (i == TIME_DIGITS) {
                continue;
            }
            final char code = id.charAt(i);
            final boolean digit = (code >= '0' && code <= '9');
            final boolean hex = (i > TIME_DIGITS && code >= 'a' && code <= 'f');
            if (!digit && !hex) {
                return false;
            }
        }
        return true;
    }

    private static long idTime(String id) {
        return Long.parseLong(id.substring(0, TIME_DIGITS));
    }

    private static String nameId(String name) {
        if (name.length() <= ID_LENGTH || name.charAt(ID_LENGTH) != '.') {
            return null;
        }
        final String id = name.substring(0, ID_LENGTH);
        return isHistoryId(id) ? id : null;
    }

    private File textFile(String id) {
        return new File(directory, id + TEXT_SUFFIX);
    }

    private File metadataFile(String id) {
        return new File(directory, id + METADATA_SUFFIX);
    }

    /** Names of everything in the directory but subdirectories, sorted. */
    private List<String> entryNames() {
        final String[] names = directory.list();
        final List<String> result = new ArrayList<>();
        if (names == null) {
            return result;
        }
        for (String name : names) {
            final StructStat stat = lstat(new File(directory, name));
            if (stat == null || !OsConstants.S_ISDIR(stat.st_mode)) {
                result.add(name);
            }
        }
        Collections.sort(result);
        return result;
    }

    private static List<String> entryIds(List<String> names) {
        final List<String> result = new ArrayList<>();
        for (String name : names) {
            final String id = nameId(name);
            if (id != null && !result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private static void sortNewestFirst(List<Entry> entries) {
        Collections.sort(entries, (a, b) -> b.id.compareTo(a.id));
    }

    private boolean prepareDirectories() {
        final File parent = directory.getParentFile();
        if (parent == null) {
            return false;
        }
        if (lstat(parent) == null && !parent.mkdir()) {
            FileLog.e("Purple: could not create " + parent + ".");
            return false;
        }
        if (!plainDirectory(parent)) {
            FileLog.e("Purple: settings history refused " + parent + ".");
            return false;
        }
        if (lstat(directory) == null) {
            try {
                Os.mkdir(directory.getPath(), PRIVATE_DIRECTORY_MODE);
            } catch (ErrnoException e) {
                FileLog.e("Purple: could not create " + directory + ".");
                return false;
            }
        }
        if (!privateDirectory(directory)) {
            FileLog.e("Purple: settings history refused " + directory + ".");
            return false;
        }
        return true;
    }

    private boolean existingDirectories() {
        final File parent = directory.getParentFile();
        return parent != null
                && lstat(directory) != null
                && plainDirectory(parent)
                && privateDirectory(directory);
    }

    private static boolean plainDirectory(File dir) {
        final StructStat stat = lstat(dir);
        return stat != null && OsConstants.S_ISDIR(stat.st_mode);
    }

    private static boolean privateDirectory(File dir) {
        final StructStat stat = lstat(dir);
        return stat != null && OsConstants.S_ISDIR(stat.st_mode)
                && ownerOnly(stat.st_mode)
                && (stat.st_mode & OsConstants.S_IRWXU) == OsConstants.S_IRWXU;
    }

    private static boolean ownerOnly(int mode) {
        return (mode & (OsConstants.S_IRWXG | OsConstants.S_IRWXO)) == 0;
    }

    /** lstat, or null for a path that is absent or cannot be examined. */
    private static StructStat lstat(File file) {
        try {
            return Os.lstat(file.getPath());
        } catch (ErrnoException e) {
            return null;
        }
    }

    private String unusedId(long createdMs) {
        for (int attempt = 0; attempt != ID_ATTEMPTS; ++attempt) {
            final String id = String.format(Locale.US, "%0" + TIME_DIGITS
                    + "d-%0" + SUFFIX_DIGITS + "x", createdMs,
                    random.nextLong());
            if (lstat(textFile(id)) == null
                    && lstat(metadataFile(id)) == null
                    && lstat(temporary(textFile(id))) == null
                    && lstat(temporary(metadataFile(id))) == null) {
                return id;
            }
        }
        return null;
    }

    private static File temporary(File file) {
        return new File(file.getPath() + TEMPORARY_SUFFIX);
    }

    private void discard(String id) {
        for (File file : new File[] {
                textFile(id), metadataFile(id),
                temporary(textFile(id)), temporary(metadataFile(id)) }) {
            if (lstat(file) != null && !file.delete()) {
                FileLog.e("Purple: could not remove " + file + ".");
            }
        }
    }

    private void prune(Set<String> keep) {
        final List<String> names = entryNames();
        final List<Entry> valid = new ArrayList<>();
        for (String id : entryIds(names)) {
            final Loaded loaded = load(id);
            if (loaded != null) {
                valid.add(loaded.entry);
            }
        }
        sortNewestFirst(valid);
        final Set<String> validIds = new HashSet<>();
        for (Entry entry : valid) {
            validIds.add(entry.id);
        }
        final Set<String> kept = new HashSet<>();
        for (String id : keep) {
            if (validIds.contains(id)) {
                kept.add(id);
            }
        }
        for (Entry entry : valid) {
            if (kept.size() < LIMIT) {
                kept.add(entry.id);
            }
        }
        final boolean full = kept.size() >= LIMIT;
        final String oldestKept = full ? Collections.min(kept) : null;
        for (String name : names) {
            final String id = nameId(name);
            if (id == null || kept.contains(id)) {
                continue;
            }
            final boolean obsolete = validIds.contains(id)
                    || (full && id.compareTo(oldestKept) < 0);
            final File file = new File(directory, name);
            if (obsolete && !file.delete()) {
                FileLog.e("Purple: could not remove " + file + ".");
            }
        }
    }

    private static byte[] serialize(Entry entry) {
        try {
            final JSONObject object = new JSONObject();
            object.put("version", METADATA_VERSION);
            object.put("id", entry.id);
            object.put("created_ms", entry.createdMs);
            object.put("reason", entry.reason.wire);
            object.put("label", entry.label);
            object.put("version_key", entry.versionKey);
            object.put("fingerprint", entry.fingerprint);
            object.put("size", entry.size);
            object.put("existed", entry.existed);
            return object.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            FileLog.e(e);
            return null;
        }
    }

    private Loaded load(String id) {
        if (!isHistoryId(id)) {
            return null;
        }
        final byte[] metadata = readPrivate(metadataFile(id),
                MAX_METADATA_BYTES);
        final JSONObject object = (metadata != null) ? parse(metadata) : null;
        if (object == null) {
            return null;
        }
        final Long version = exactInteger(object.opt("version"));
        final Object storedId = object.opt("id");
        final Long created = exactInteger(object.opt("created_ms"));
        final Reason reason = Reason.parse(object.opt("reason"));
        final Object label = object.opt("label");
        final Object versionKey = object.opt("version_key");
        final Object fingerprint = object.opt("fingerprint");
        final Long size = exactInteger(object.opt("size"));
        final Object existed = object.opt("existed");
        final long time = idTime(id);
        if (version == null || version != METADATA_VERSION
                || !id.equals(storedId)
                || created == null || created != time
                || time <= 0 || time > MAX_SAFE_INTEGER
                || reason == null
                || !(label instanceof String)
                || ((String) label).length() > MAX_LABEL_LENGTH
                || !(versionKey instanceof String)
                || (!((String) versionKey).isEmpty()
                        && !core.isVersionKey((String) versionKey))
                || !(fingerprint instanceof String)
                || size == null || size < 0 || size > MAX_TEXT_BYTES
                || !(existed instanceof Boolean)
                || (!((Boolean) existed) && size != 0)) {
            return null;
        }
        final byte[] text = readPrivate(textFile(id), MAX_TEXT_BYTES);
        if (text == null || text.length != size
                || !fingerprint.equals(core.fingerprint(text))) {
            return null;
        }
        return new Loaded(new Entry(id, time, reason, (String) label,
                (String) versionKey, (String) fingerprint, text.length,
                (Boolean) existed), text);
    }

    /** One JSON object and nothing after it, from strict UTF-8. */
    private static JSONObject parse(byte[] metadata) {
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(metadata))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        try {
            final JSONTokener tokener = new JSONTokener(text);
            final Object value = tokener.nextValue();
            return (value instanceof JSONObject && tokener.nextClean() == 0)
                    ? (JSONObject) value
                    : null;
        } catch (JSONException e) {
            return null;
        } catch (RuntimeException e) {
            // Two clauses rather than one: Android's JSONException is checked,
            // while the reference org.json makes it a RuntimeException.
            return null;
        }
    }

    /** The value as a whole number within the safe integer range, or null. */
    private static Long exactInteger(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        final Number number = (Number) value;
        final double asDouble = number.doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)
                || asDouble != Math.rint(asDouble)
                || Math.abs(asDouble) > MAX_SAFE_INTEGER) {
            return null;
        }
        final long asLong = number.longValue();
        return (asLong == (long) asDouble) ? asLong : null;
    }

    /** A private regular file's bytes, at most limit of them, or null. */
    private static byte[] readPrivate(File file, int limit) {
        final StructStat stat = lstat(file);
        if (stat == null || !OsConstants.S_ISREG(stat.st_mode)
                || stat.st_size > limit || !ownerOnly(stat.st_mode)) {
            return null;
        }
        try (FileInputStream stream = new FileInputStream(file)) {
            final byte[] bytes = new byte[(int) stat.st_size];
            int offset = 0;
            while (offset < bytes.length) {
                final int count = stream.read(bytes, offset,
                        bytes.length - offset);
                if (count < 0) {
                    return null;
                }
                offset += count;
            }
            return (stream.read() == -1) ? bytes : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /**
     * Writes a new owner-only file through a temporary sibling and a rename,
     * then requires it to read back exactly.
     */
    private static boolean writePrivate(File target, byte[] bytes) {
        final File temp = temporary(target);
        if (lstat(temp) != null || lstat(target) != null) {
            return false;
        }
        boolean renamed = false;
        try {
            try (FileOutputStream stream = new FileOutputStream(temp)) {
                Os.chmod(temp.getPath(), PRIVATE_FILE_MODE);
                stream.write(bytes);
                stream.getFD().sync();
            }
            renamed = temp.renameTo(target);
        } catch (IOException | ErrnoException | SecurityException e) {
            FileLog.e(e);
        } finally {
            if (!renamed && lstat(temp) != null && !temp.delete()) {
                FileLog.e("Purple: could not remove " + temp + ".");
            }
        }
        if (!renamed) {
            return false;
        }
        final byte[] readBack = readPrivate(target, bytes.length);
        return readBack != null && Arrays.equals(readBack, bytes);
    }
}
