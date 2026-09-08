/*
 * This is the source code of Purple Telegram for Android.
 *
 * What this install calls itself, so a [[schedule.rulesets]] block can name it.
 *
 * The whole point of rulesets is that one settings.toml is carried between a
 * phone and a laptop unchanged: the file describes devices and each device
 * describes itself. This class is the second half of that on Android.
 */

package org.telegram.messenger.purple;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;

public final class PurpleDevice {

    /** What the core matches a ruleset's {@code device = "android"} against. */
    public static final String PLATFORM = "android";

    /** And its {@code device = "mobile"}. A phone is never the desktop class. */
    public static final String CLASS = "mobile";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String PREFS = "purple_device";
    private static final String KEY_ID = "id";

    /**
     * How much of the digest the id carries.
     *
     * Eight hex digits is four bytes, which is plenty to tell apart the handful
     * of devices one person carries and short enough to be typed into a file by
     * hand and read back off a screen. It is not a security boundary: the point
     * of hashing is that the raw Android identifier never lands in a file that
     * gets sent to Saved Messages, not that the id resists a search.
     */
    private static final int ID_HEX = 8;

    private static volatile String id;

    private PurpleDevice() {
    }

    /**
     * This device's id, as {@code "android-3f9a2c1d"}.
     *
     * Derived from {@code Settings.Secure.ANDROID_ID}, which is the OS's own
     * answer and therefore survives clearing the app's data - the one thing a
     * generated id would not, and the reason a device's rulesets would
     * otherwise quietly stop applying after a reinstall. It is per app signing
     * key, so it identifies this install of this fork and nothing else about
     * the phone.
     *
     * Hashed and shortened rather than written down: the raw value travels in
     * a file that gets sent to Saved Messages and read on a laptop, and an
     * identifier several apps could correlate has no business being in it.
     *
     * A phone that answers nothing - which the emulator and a few OEM builds
     * do - falls back to a random id kept in a preference. That one does not
     * survive a data reset, which is the trade for having an id at all.
     */
    public static String id() {
        final String known = id;
        if (known != null) {
            return known;
        }
        return compute();
    }

    private static synchronized String compute() {
        if (id != null) {
            return id;
        }
        final String hashed = hashed(androidId());
        id = (hashed != null) ? hashed : remembered();
        return id;
    }

    /** What the OS says, or null when it says nothing usable. */
    private static String androidId() {
        try {
            final String value = Settings.Secure.getString(
                    ApplicationLoader.applicationContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            return (value == null || value.length() == 0) ? null : value;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** {@code "android-"} and the first eight hex digits of the SHA-256. */
    private static String hashed(String value) {
        if (value == null) {
            return null;
        }
        try {
            final byte[] sum = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(UTF_8));
            return PLATFORM + "-" + hex(sum);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * The random id, made once and kept.
     *
     * Written through {@code commit} rather than {@code apply}: this is read on
     * the very next call and every reload after it, and an id that changed
     * because the process died before the write landed would be a ruleset
     * silently addressing a device that no longer exists.
     */
    private static String remembered() {
        try {
            final SharedPreferences prefs = prefs();
            final String kept = prefs.getString(KEY_ID, null);
            if (kept != null && kept.length() > 0) {
                return kept;
            }
            final byte[] random = new byte[(ID_HEX + 1) / 2];
            new SecureRandom().nextBytes(random);
            final String made = PLATFORM + "-" + hex(random);
            prefs.edit().putString(KEY_ID, made).commit();
            return made;
        } catch (Exception e) {
            FileLog.e(e);
            // An id nothing can keep is still better than none: a ruleset
            // naming this device will not match, and every other ruleset works
            // exactly as it would have.
            return PLATFORM;
        }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder out = new StringBuilder(ID_HEX);
        for (int a = 0; a < bytes.length && out.length() < ID_HEX; ++a) {
            out.append(Character.forDigit((bytes[a] >> 4) & 0xf, 16));
            if (out.length() < ID_HEX) {
                out.append(Character.forDigit(bytes[a] & 0xf, 16));
            }
        }
        return out.toString();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * What {@code [devices]} calls this one, or null when it says nothing.
     *
     * The label lives in settings.toml rather than in a preference so that it
     * travels with the file: the laptop showing "the phone" beside a ruleset is
     * the entire reason for writing one down.
     */
    public static String label() {
        return labelFor(PurpleGate.state(), id());
    }

    /** The same lookup for any id, as a ruleset row naming one needs it. */
    public static String labelFor(PurpleCore.Loaded state, String deviceId) {
        if (state == null || deviceId == null || deviceId.length() == 0) {
            return null;
        }
        final List<PurpleCore.DeviceLabel> devices = state.devices;
        for (int a = 0, n = devices.size(); a < n; ++a) {
            final PurpleCore.DeviceLabel device = devices.get(a);
            // Ignoring case, the same as a ruleset matching an id: the id ends
            // up typed by hand into the file at least once.
            if (deviceId.equalsIgnoreCase(device.id) && device.label.length() > 0) {
                return device.label;
            }
        }
        return null;
    }
}
