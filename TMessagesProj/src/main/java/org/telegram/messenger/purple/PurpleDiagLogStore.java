/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The storage and line format of the Purple diagnostic log, kept free of
 * Android so host tests run this production class unchanged.
 *
 * Privacy is a property of the API rather than of each caller's care. A line
 * is built only from {@link Field} values, and every factory takes a type that
 * cannot carry an identifier: a slot index, a small clamped count, a version,
 * a boolean, or a word from the fixed {@link #VOCABULARY}. Areas, events,
 * field names, reason codes and other enumerated values must all be words of
 * that list; anything else is written as {@value #INVALID}. There is no field
 * that takes a long, and no field that takes free text except an exception
 * message. That message is scrubbed (see {@link #sanitize}), and JSON
 * exceptions keep only their class, because their messages echo input values.
 * Other words of an exception message are kept, so callers log exceptions
 * whose messages are Purple's own constant text.
 *
 * The store is bounded: one current file up to {@link #LIMIT} bytes and one
 * rotated predecessor, so it never holds more than twice the limit.
 */
public final class PurpleDiagLogStore {
    public static final long LIMIT = 256 * 1024;
    static final String CURRENT = "purple-diagnostic.log";
    static final String ROTATED = "purple-diagnostic.log.1";
    static final int LINE_LIMIT = 512;
    static final int MAX_SLOT = 31;
    static final int MAX_COUNT = 99999;
    static final String INVALID = "invalid";

    /**
     * Every word a line may hold besides numbers and a scrubbed exception
     * message. A new call site adds its words here; the host cases check that
     * each is a lowercase token.
     */
    static final Set<String> VOCABULARY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // diagnostics itself
            "diag", "export", "exported", "export_failed", "cleared", "clear_failed", "lines",
            // the settings owner plan (PurpleAccountOwner)
            "owner", "planned", "accounts", "state", "ready", "owned", "pending_owner_writes",
            "waiting_for_first_sign_in", "needs_choice", "single_file_error", "invalid_input",
            "input_unavailable", "partial_view", "plan_stale"
    )));

    /** One {@code key=value} pair of a line. Only the factories below make one. */
    public static final class Field {
        final String key;
        final String value;

        private Field(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    /** An account slot index, never an account or user id. */
    public static Field slot(int slot) {
        return new Field("slot", slot >= 0 && slot <= MAX_SLOT ? Integer.toString(slot) : "?");
    }

    /** A small enumerated mode, such as own/follow/stock as 0/1/2. */
    public static Field mode(int mode) {
        return new Field("mode", mode >= -1 && mode <= 9 ? Integer.toString(mode) : "?");
    }

    /** A count, clamped so that an id passed by mistake cannot survive. */
    public static Field count(String key, int value) {
        return new Field(token(key), value < 0 ? "neg" : value > MAX_COUNT ? MAX_COUNT + "+" : Integer.toString(value));
    }

    /** A schema or format version. */
    public static Field version(String key, int value) {
        return new Field(token(key), value >= 0 && value <= 9999 ? Integer.toString(value) : "?");
    }

    public static Field flag(String key, boolean value) {
        return new Field(token(key), value ? "1" : "0");
    }

    /** A reason code from {@link #VOCABULARY}, such as {@code owner_not_ready}. */
    public static Field reason(String code) {
        return new Field("reason", token(code));
    }

    /** Any other enumerated value, such as a source name; both parts are vocabulary words. */
    public static Field code(String key, String value) {
        return new Field(token(key), token(value));
    }

    /** An exception's class and its sanitized message. */
    public static Field error(Throwable error) {
        if (error == null) return new Field("error", "none");
        String name = error.getClass().getName();
        int dot = name.lastIndexOf('.');
        name = clean(dot < 0 ? name : name.substring(dot + 1), 60).replace(' ', '_');
        String message = name.equals("JSONException") ? "" : sanitize(error.getMessage());
        return new Field("error", message.isEmpty() ? name : name + ":\"" + message + "\"");
    }

    /**
     * {@code value} when it is a word of {@link #VOCABULARY}, otherwise
     * {@value #INVALID}. Areas, events, field names, reason codes and other
     * enumerated values all pass through here, so a caller cannot smuggle a
     * name, a list key or an id into one.
     */
    static String token(String value) {
        return value != null && VOCABULARY.contains(value) ? value : INVALID;
    }

    /** The shape every vocabulary word has: a lowercase token of at most 48 characters without three digits in a row. */
    static boolean tokenShape(String value) {
        if (value == null || value.isEmpty() || value.length() > 48) return false;
        char first = value.charAt(0);
        if (first < 'a' || first > 'z') return false;
        int digits = 0;
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            if (!(c >= 'a' && c <= 'z') && !digit && c != '_' && c != '.') return false;
            digits = digit ? digits + 1 : 0;
            if (digits >= 3) return false;
        }
        return true;
    }

    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"?|'[^']*'?|`[^`]*`?", Pattern.DOTALL);
    private static final Pattern PATH = Pattern.compile("[/\\\\][^\\s:,)]*");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");
    private static final Pattern HANDLE = Pattern.compile("@[A-Za-z0-9_]+");
    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]{8,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d(?:[\\s().-]*\\d){6,}");
    private static final Pattern DIGITS = Pattern.compile("\\d{3,}");

    /**
     * An exception message with identifying parts replaced: quoted text by
     * {@code <q>}, anything path-like by {@code <path>}, email addresses by
     * {@code <email>}, {@code @handles} by {@code <handle>}, hex runs of eight
     * or more characters that contain a letter by {@code <hex>}, phone-like
     * numbers (seven or more digits with a {@code +} or separators) by
     * {@code <phone>}, and every other run of three or more digits by
     * {@code #}. Printable ASCII only, at most 120 characters. Other words are
     * kept.
     */
    static String sanitize(String message) {
        if (message == null) return "";
        String out = QUOTED.matcher(message).replaceAll("<q>");
        out = PATH.matcher(out).replaceAll("<path>");
        out = EMAIL.matcher(out).replaceAll("<email>");
        out = HANDLE.matcher(out).replaceAll("<handle>");
        out = replace(HEX, out, "<hex>");
        out = replace(PHONE, out, "<phone>");
        out = DIGITS.matcher(out).replaceAll("#");
        StringBuilder printable = new StringBuilder();
        for (int i = 0; i < out.length(); ++i) {
            char c = out.charAt(i);
            printable.append(c < 0x20 || c > 0x7e ? ' ' : c);
        }
        return clean(printable.toString().trim(), 120);
    }

    /**
     * Replaces each match that is not a bare digit run: a hex run needs a
     * letter and a phone-like number needs a {@code +} or a separator, and
     * bare digit runs are left to {@link #DIGITS}.
     */
    private static String replace(Pattern pattern, String text, String with) {
        Matcher m = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String found = m.group();
            boolean digitsOnly = true;
            for (int i = 0; i < found.length() && digitsOnly; ++i) digitsOnly = Character.isDigit(found.charAt(i));
            m.appendReplacement(out, Matcher.quoteReplacement(digitsOnly ? found : with));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String clean(String value, int limit) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < limit; ++i) {
            char c = value.charAt(i);
            out.append(c < 0x20 || c > 0x7e || c == '"' ? ' ' : c);
        }
        return out.toString();
    }

    /** One line, without its newline: {@code <UTC time> <area> <event> key=value...}. */
    public static String line(long unixMillis, String area, String event, Field... fields) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder out = new StringBuilder(format.format(new Date(unixMillis)))
                .append(' ').append(token(area)).append(' ').append(token(event));
        if (fields != null) for (Field field : fields) {
            if (field != null) out.append(' ').append(field.key).append('=').append(field.value);
        }
        return out.length() > LINE_LIMIT ? out.substring(0, LINE_LIMIT) : out.toString();
    }

    private final File directory;
    private final long limit;

    public PurpleDiagLogStore(File directory, long limit) {
        this.directory = directory;
        this.limit = Math.max(LINE_LIMIT * 2L, limit);
    }

    File current() { return new File(directory, CURRENT); }
    File rotated() { return new File(directory, ROTATED); }

    /**
     * Appends one line, rotating first when it would not fit. Returns false
     * and leaves the files as they were on any failure; callers ignore it.
     */
    public synchronized boolean append(String line) {
        try {
            if (line == null) return false;
            String bounded = line.length() > LINE_LIMIT ? line.substring(0, LINE_LIMIT) : line;
            byte[] bytes = (bounded.replace('\n', ' ').replace('\r', ' ') + "\n").getBytes(StandardCharsets.UTF_8);
            if (!directory.isDirectory() && !directory.mkdirs()) return false;
            File current = current();
            if (current.length() + bytes.length > limit) {
                File rotated = rotated();
                if (rotated.exists() && !rotated.delete()) return false;
                if (current.exists() && !current.renameTo(rotated)) return false;
            }
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                out.write(bytes);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** The rotated file, then the current one, oldest line first. */
    public synchronized String read() {
        return read(rotated()) + read(current());
    }

    private String read(File file) {
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1 && out.size() <= limit) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        }
    }

    public synchronized boolean clear() {
        boolean ok = true;
        for (File file : new File[]{current(), rotated()}) {
            if (file.exists() && !file.delete()) ok = false;
        }
        return ok;
    }
}
