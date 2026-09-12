/*
 * This is the source code of Purple Telegram for Android.
 *
 * The recorder behind Settings -> Purple -> Screen time: it appends raw events
 * to screentime.log and does nothing else with them. Every total, every
 * session, every "active versus reading" split is the core's, derived from
 * these lines at read time - which is the whole design. A log of totals would
 * have baked today's thresholds into today forever; a log of events re-derives
 * the history you already have the moment action_span or idle_after changes.
 *
 * Local only, and it stays that way. The file never leaves the phone: two
 * devices would double-count nothing useful, and this is a record of what you
 * looked at.
 */

package org.telegram.messenger.purple;

import android.os.Looper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.TimeZone;

public final class PurpleScreenTime {

    /** The log, next to settings.toml and state.toml in the purple directory. */
    public static final String FILE_NAME = "screentime.log";

    /**
     * What an Action event says it was. Free text as far as the core is
     * concerned - it carries the word through to a screen that wants to break
     * the actions down and never decides anything on it - so these are spelled
     * for a reader rather than parsed anywhere.
     */
    public static final String ACTION_TYPING = "typing";
    public static final String ACTION_SEND = "send";
    public static final String ACTION_VOICE = "voice";
    public static final String ACTION_ATTACH = "attach";
    public static final String ACTION_REPLY = "reply";
    public static final String ACTION_EDIT = "edit";

    /**
     * The event kinds this writes. The core knows one more - "close" - and
     * nothing here ever writes it: leaving a chat with the app still in front
     * is an Open of "elsewhere", because that is what actually happened, and it
     * is what makes the splits add up to foreground time rather than to
     * something smaller with no name. Leaving with the app going away is a
     * Background, which has already ended the session by the time the chat
     * hears about it.
     */
    private static final String KIND_OPEN = "open";
    private static final String KIND_ACTION = "action";
    private static final String KIND_IDLE = "idle";
    private static final String KIND_RESUME = "resume";
    private static final String KIND_PRESET = "preset";
    private static final String KIND_FOREGROUND = "foreground";
    private static final String KIND_BACKGROUND = "background";
    private static final String KIND_PEEK = "peek";
    private static final String KIND_PEEK_END = "peek_end";

    /** The core's ScreenTimeKind spellings, by the value it numbers them with. */
    private static final String[] CHAT_KINDS = {
            "private", "groups", "channels", "bots", "elsewhere",
    };

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** How often the log is walked for expired lines. Once a day is plenty. */
    private static final long PRUNE_EVERY_MS = 24 * 60 * 60 * 1000L;

    /**
     * Where the writes happen. One queue, so the lines land in the order they
     * were made even though the file is opened afresh for each of them, and off
     * the UI thread, because a touch must never wait on a disk.
     *
     * No fsync. A crash losing the last few events costs a few seconds off a
     * total nobody is auditing, and the alternative is a disk flush on every
     * keystroke burst.
     */
    private static volatile DispatchQueue queue;

    // Everything below is the UI thread's, and is only ever read or written
    // from it - see post(). The file is the only thing that crosses threads.

    /** Whether the app is in front of the user with the screen on. */
    private static boolean foreground;

    /** The chat in front, or zero for "elsewhere" - the list, search, settings. */
    private static long dialogId;

    private static int chatKind = PurpleCore.SCREEN_KIND_ELSEWHERE;

    /** Whether that chat is one the running preset hides, reached by peeking. */
    private static boolean hidden;

    /** Whether an Open has been written for the state above and not yet ended. */
    private static boolean sessionOpen;

    /**
     * The peek last written into the log: whether one was running, and the
     * deadline it had.
     *
     * Both, because the deadline moving is worth a line of its own - an
     * extension, or the control tapped again at another length. The core reads
     * a second "peek" with no end between as the SAME peek and not a new one,
     * and takes the newest deadline it saw, which is what stops a peek that
     * outlived the process from swallowing the hours the phone spent with
     * Telegram killed.
     */
    private static boolean peeking;
    private static long peekDeadlineMs;

    /** The preset last written into an event, so a change can be noticed. */
    private static String preset = "";

    /** Whether the session is paused, and when input was last seen. */
    private static boolean idle;
    private static long lastInputMs;
    private static boolean idleArmed;

    /** When the last composer-edit event went out, for the action_span throttle. */
    private static long lastTypingMs;

    /** Whether onLoaded has run once, which is what triggers the day's prune. */
    private static boolean started;

    private PurpleScreenTime() {
    }

    public static File file() {
        return new File(PurpleSettings.dir(), FILE_NAME);
    }

    /**
     * Whether anything is being recorded.
     *
     * Read on the touch path, so it is a volatile read of what the last load
     * left behind and never a file access - and it deliberately does not call
     * {@link PurpleGate#ensureLoaded()}: a screen-time question is not a reason
     * to start the gate.
     */
    public static boolean enabled() {
        final PurpleCore.Loaded state = PurpleGate.state();
        return state != null && state.screenTimeEnabled;
    }

    // ---- the hooks -----------------------------------------------------------

    /**
     * Called from every gate reload: it is what notices the preset changing,
     * and what stops the recording when the switch goes off.
     *
     * The Preset event cuts the running session and starts an identical one
     * carrying the new name, so every second of the log has exactly one preset
     * and a per-preset split needs no lookup. Written only while a session is
     * open, because a preset change with nothing in front of you is not part of
     * any session and the next Open will carry the new name anyway.
     */
    public static void onLoaded(PurpleCore.Loaded next) {
        if (!isUiThread()) {
            final PurpleCore.Loaded carried = next;
            AndroidUtilities.runOnUIThread(() -> onLoaded(carried));
            return;
        }
        if (next == null || !next.screenTimeEnabled) {
            // The switch went off, or the file did. Whatever was running is
            // ended rather than left dangling: a session with no close is one
            // the core has to end at the next event, which could be tomorrow.
            if (sessionOpen) {
                append(now(), KIND_BACKGROUND, 0, PurpleCore.SCREEN_KIND_ELSEWHERE,
                        preset, "", false);
                sessionOpen = false;
                cancelIdle();
            }
            // Nothing is being written any more, so the peek is forgotten
            // rather than ended: a "peek_end" into a log that has stopped
            // recording would be a line about a moment nothing else in it
            // covers. Recording again announces whatever is running then.
            peeking = false;
            peekDeadlineMs = 0;
            started = false;
            return;
        }
        final String running = next.normal ? "" : next.preset;
        if (!started) {
            started = true;
            preset = running;
            pruneIfDue();
        } else if (!running.equalsIgnoreCase(preset)) {
            preset = running;
            if (sessionOpen) {
                append(now(), KIND_PRESET, dialogId, chatKind, preset, "", hidden);
            }
        }
        checkPeek(next);
        if (foreground && !sessionOpen) {
            // Two ways to arrive here, and they want the same thing: the switch
            // was turned on while the app was already in front, or the gate
            // finished its first load after the activity had already resumed.
            // Either way nothing is recording yet and something should be.
            final long at = now();
            append(at, KIND_FOREGROUND, 0, PurpleCore.SCREEN_KIND_ELSEWHERE, preset, "", false);
            open(at);
        }
    }

    /**
     * Writes the peek line when a peek starts, ends, or has its deadline moved.
     *
     * Every one of those arrives here as a gate reload - starting, extending
     * and stopping all write state and reload, and so does the timer that ends
     * a peek at its deadline - so there is nothing to hook beyond this, and no
     * timer of this file's own.
     *
     * Not tied to a session, unlike every other kind here. A peek is not a chat
     * being in front of you: most are started to look at the chat list itself,
     * which is exactly the use the "time in hidden chats" number cannot see, and
     * one started with the app in front can still be running when it goes away.
     */
    private static void checkPeek(PurpleCore.Loaded next) {
        final boolean running = next.clock.peeking;
        final long deadline = running ? next.clock.peekDeadline * 1000L : 0;
        if (running == peeking && deadline == peekDeadlineMs) {
            return;
        }
        peeking = running;
        peekDeadlineMs = deadline;
        append(now(), running ? KIND_PEEK : KIND_PEEK_END, 0,
                PurpleCore.SCREEN_KIND_ELSEWHERE, preset,
                // The deadline rides in the action field, which is what the
                // core reads it out of. A peek with no clock on it has none,
                // and is bounded by nothing - that one really does run until it
                // is stopped.
                (running && deadline > 0) ? Long.toString(deadline) : "",
                false);
    }

    /**
     * The app came to the front, or left it.
     *
     * Both halves of that: {@code LaunchActivity}'s pause and resume, and the
     * screen locking, which reaches the app the other way round and would
     * otherwise leave a session running against a dark screen. Whichever
     * arrives first wins and the second is a no-op, which is why this is one
     * entry point with a flag rather than four.
     *
     * Coming back writes the Foreground event and then re-opens whatever was in
     * front - a chat, or "elsewhere". The core takes nothing from a Foreground
     * on its own: returning to the app does not say which chat you returned to.
     */
    public static void foreground(boolean value) {
        if (!isUiThread()) {
            AndroidUtilities.runOnUIThread(() -> foreground(value));
            return;
        }
        if (foreground == value) {
            return;
        }
        foreground = value;
        if (!enabled()) {
            sessionOpen = false;
            cancelIdle();
            return;
        }
        final long at = now();
        if (value) {
            append(at, KIND_FOREGROUND, 0, PurpleCore.SCREEN_KIND_ELSEWHERE, preset, "", false);
            open(at);
        } else {
            append(at, KIND_BACKGROUND, 0, PurpleCore.SCREEN_KIND_ELSEWHERE, preset, "", false);
            sessionOpen = false;
            cancelIdle();
        }
    }

    /**
     * A chat came to the front.
     *
     * Called from {@code ChatActivity.onResume}, which is the pair that tracks
     * the chat actually in front of the user: a fragment deeper in the back
     * stack is paused, a tab switch pauses the one leaving and resumes the one
     * arriving, and the app going to the background pauses whatever was on top.
     * {@code onFragmentCreate} would have counted a chat still on the stack
     * behind three others.
     */
    public static void openChat(int currentAccount, long chatId) {
        if (!isUiThread()) {
            AndroidUtilities.runOnUIThread(() -> openChat(currentAccount, chatId));
            return;
        }
        if (!enabled()) {
            return;
        }
        final long bare = PurpleGate.bareIdOf(currentAccount, chatId);
        final int kind = PurpleGate.kindOf(currentAccount, chatId);
        final boolean peeked = PurpleGate.hiddenWhilePeeking(currentAccount, chatId);
        if (sessionOpen && dialogId == bare && chatKind == kind && hidden == peeked) {
            return;
        }
        dialogId = bare;
        chatKind = kind;
        hidden = peeked;
        open(now());
    }

    /**
     * That chat went away, with the app still in front.
     *
     * Written as an Open of "elsewhere" rather than as a Close, because that is
     * what actually happened: the chat list is where you went, and the bucket
     * it lands in is what makes the splits add up to foreground time instead of
     * to something smaller with no name. A close while the app is already gone
     * writes nothing - the Background event ended the session at the right
     * moment, and this one arrives after it.
     */
    public static void closeChat(int currentAccount, long chatId) {
        if (!isUiThread()) {
            AndroidUtilities.runOnUIThread(() -> closeChat(currentAccount, chatId));
            return;
        }
        if (!enabled() || dialogId == 0) {
            return;
        }
        // Only when it is still the chat being recorded. Tapping from one chat
        // straight into another resumes the new fragment before it pauses the
        // old one, and a close that did not check would end the session that
        // had just started instead of the one that ended.
        if (PurpleGate.bareIdOf(currentAccount, chatId) != dialogId) {
            return;
        }
        dialogId = 0;
        chatKind = PurpleCore.SCREEN_KIND_ELSEWHERE;
        hidden = false;
        if (!foreground) {
            sessionOpen = false;
            cancelIdle();
            return;
        }
        open(now());
    }

    /**
     * One send action: a composer edit, a send, a voice recording started, an
     * attachment picked, a reply or an edit begun.
     *
     * These are the signal for "active" as against "reading", and they are the
     * only thing written per keystroke-ish event - which is why a composer edit
     * is throttled to one per {@code action_span} rather than one per key. A
     * per-keystroke log would be a keylogger's shape for no extra truth: the
     * core counts one action as active for its span either way.
     *
     * An action is also input, so it ends an idle pause. The core knows that
     * without being told - it ends the pause at the Action whether or not a
     * Resume was written - but the watchdog here has to be reset too.
     */
    public static void action(String what) {
        if (!isUiThread()) {
            AndroidUtilities.runOnUIThread(() -> action(what));
            return;
        }
        if (!enabled() || !sessionOpen) {
            return;
        }
        final long at = now();
        if (ACTION_TYPING.equals(what)) {
            final PurpleCore.Loaded state = PurpleGate.state();
            final long span = Math.max(1, state == null ? 3 : state.screenTimeActionSpan) * 1000L;
            if (at - lastTypingMs < span) {
                // Still inside the burst the last event already claims. The
                // input still counts, so the watchdog is reset below.
                input(at);
                return;
            }
            lastTypingMs = at;
        }
        input(at);
        append(at, KIND_ACTION, dialogId, chatKind, preset, what, hidden);
    }

    /**
     * A touch, a scroll or a key: the things that are not worth a line each but
     * are what "idle" is the absence of.
     *
     * Called from the chat's {@code dispatchTouchEvent} and its message list's
     * scroll listener, so it runs on the hottest path in the app. It writes
     * nothing and allocates nothing in the ordinary case - a field write and a
     * comparison - and the watchdog it arms is one delayed runnable at a time,
     * re-armed only when it fires early.
     */
    public static void input() {
        if (!enabled() || !sessionOpen) {
            return;
        }
        input(now());
    }

    // ---- the sessions --------------------------------------------------------

    private static void open(long at) {
        append(at, KIND_OPEN, dialogId, chatKind, preset, "", hidden);
        sessionOpen = true;
        idle = false;
        lastTypingMs = 0;
        input(at);
    }

    private static void input(long at) {
        lastInputMs = at;
        if (idle) {
            append(at, KIND_RESUME, dialogId, chatKind, preset, "", hidden);
            idle = false;
        }
        armIdle();
    }

    /**
     * The no-input watchdog.
     *
     * It fires at {@code idle_after} from the last time it was armed, which is
     * not necessarily {@code idle_after} from the last input - re-arming on
     * every touch would post a runnable per MotionEvent. So it checks the clock
     * when it fires and re-arms for the remainder instead, which costs one
     * extra post per stretch of activity and keeps the touch path free.
     *
     * The pause itself is still the core's: the Idle event is stamped back to
     * {@code idle_after} before it, where the input actually stopped, so
     * changing the threshold re-derives the same log differently.
     */
    private static final Runnable IDLE_CHECK = () -> {
        idleArmed = false;
        if (!sessionOpen || idle || !enabled()) {
            return;
        }
        final long after = idleAfterMs();
        final long since = now() - lastInputMs;
        if (since >= after) {
            append(now(), KIND_IDLE, dialogId, chatKind, preset, "", hidden);
            idle = true;
        } else {
            idleArmed = true;
            // Named through the class rather than bare: a lambda in a field's
            // own initializer may not refer to the field by its simple name.
            AndroidUtilities.runOnUIThread(
                    PurpleScreenTime.IDLE_CHECK, after - since);
        }
    };

    private static void armIdle() {
        if (idleArmed) {
            return;
        }
        idleArmed = true;
        AndroidUtilities.runOnUIThread(IDLE_CHECK, idleAfterMs());
    }

    private static void cancelIdle() {
        idleArmed = false;
        idle = false;
        AndroidUtilities.cancelRunOnUIThread(IDLE_CHECK);
    }

    private static long idleAfterMs() {
        final PurpleCore.Loaded state = PurpleGate.state();
        final int seconds = (state == null) ? 60 : state.screenTimeIdleAfter;
        return Math.max(5, seconds) * 1000L;
    }

    // ---- the file ------------------------------------------------------------

    /**
     * One line, in the core's own format:
     *
     * <pre>unix_ms \t kind \t dialog_id \t chat_kind \t preset \t action \t hidden</pre>
     *
     * Built here rather than through a native because it is seven fields and a
     * tab, and a JNI call per keystroke burst to assemble a string is a cost
     * with nothing on the other side of it. The core owns the parser, which is
     * what keeps the two honest: a line this writes that it cannot read is
     * dropped in silence and the rest of the history survives.
     *
     * The two free-text fields are flattened the same way {@code FormatEvent}
     * flattens them - a tab in a preset name would take the rest of the line
     * with it.
     */
    private static void append(long at, String kind, long id, int chatKindValue,
            String presetName, String action, boolean hiddenChat) {
        final StringBuilder line = new StringBuilder(64);
        line.append(at).append('\t')
                .append(kind).append('\t')
                .append(id).append('\t')
                .append(CHAT_KINDS[(chatKindValue >= 0 && chatKindValue < CHAT_KINDS.length)
                        ? chatKindValue
                        : PurpleCore.SCREEN_KIND_ELSEWHERE]).append('\t')
                .append(flatten(presetName)).append('\t')
                .append(flatten(action)).append('\t')
                .append(hiddenChat ? '1' : '0').append('\n');
        post(line.toString());
    }

    private static String flatten(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static void post(String line) {
        queue().postRunnable(() -> {
            try {
                final OutputStream out = new FileOutputStream(file(), true);
                try {
                    out.write(line.getBytes(UTF_8));
                } finally {
                    out.close();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static DispatchQueue queue() {
        DispatchQueue result = queue;
        if (result == null) {
            synchronized (PurpleScreenTime.class) {
                result = queue;
                if (result == null) {
                    queue = result = new DispatchQueue("purpleScreenTime");
                }
            }
        }
        return result;
    }

    /**
     * The log as it would read if this were the only chat you ever opened.
     *
     * The per-chat page needs one chat's sessions, and the report native has no
     * filter to ask for them with - deliberately, because the screen it feeds
     * wants every chat at once and a second signature would be a second thing
     * to keep in step. So the log is narrowed here instead, and narrowed in the
     * one way that keeps the session boundaries exactly where they were: every
     * Open naming another chat becomes a Close.
     *
     * Filtering those lines OUT would have been wrong, and quietly so. A
     * session ends at the next Open, so dropping the Opens of other chats would
     * leave this chat's session running through all of them and report a
     * ten-minute look as an hour. Turning them into Closes ends the session at
     * exactly the moment the other chat took the screen, which is what
     * happened. The actions and idles belonging to the other chats stay in the
     * text and cost nothing: the core applies them only inside an open session,
     * and there is none there any more.
     *
     * Everything else is left alone - foreground, background and preset lines
     * mean the same thing whichever chat is being asked about.
     */
    public static byte[] logForChat(byte[] log, long dialogId) {
        if (log == null || log.length == 0) {
            return new byte[0];
        }
        final String text = new String(log, UTF_8);
        final StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            if (end < 0) {
                end = text.length();
            }
            final String line = text.substring(at, end);
            at = end + 1;
            if (line.isEmpty()) {
                continue;
            }
            final int first = line.indexOf('\t');
            final int second = (first < 0) ? -1 : line.indexOf('\t', first + 1);
            final int third = (second < 0) ? -1 : line.indexOf('\t', second + 1);
            if (third < 0) {
                // Not a line this can read. Kept as it is: the core drops what
                // it cannot parse, and a line malformed here may be one it can.
                out.append(line).append('\n');
                continue;
            }
            if (KIND_OPEN.equals(line.substring(first + 1, second))) {
                long named = -1;
                try {
                    named = Long.parseLong(line.substring(second + 1, third));
                } catch (NumberFormatException ignore) {
                }
                if (named != dialogId) {
                    out.append(line, 0, first + 1)
                            .append("close")
                            .append(line, second, line.length())
                            .append('\n');
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return out.toString().getBytes(UTF_8);
    }

    /** The whole log, or an empty array. Read by the screens, never by a hook. */
    public static byte[] read() {
        final File source = file();
        if (!source.exists()) {
            return new byte[0];
        }
        try {
            return PurpleSettings.readAll(source);
        } catch (Exception e) {
            FileLog.e(e);
            return new byte[0];
        }
    }

    /**
     * The zone a day boundary is decided in, as the offset in force right now.
     *
     * Not the answer the report actually uses any more, and still worth
     * sending. The bridge decides day boundaries with the C library's own local
     * time, which knows the whole daylight-saving rule rather than today's
     * offset; this value is what it checks that answer against, because a build
     * where local time does not resolve would otherwise be wrong silently. See
     * ZoneFrom() there.
     */
    public static String zoneId() {
        // The offset spelling, never the IANA id: the bridge's Qt cannot
        // resolve a named zone without a JavaVM it never gets, and crashed
        // trying (see ZoneFrom in the bridge).
        final int offsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        final int abs = Math.abs(offsetMinutes);
        return String.format(java.util.Locale.US, "UTC%s%02d:%02d",
                offsetMinutes < 0 ? "-" : "+", abs / 60, abs % 60);
    }

    /**
     * Rewrites the log without the lines that have expired, at most once a day.
     *
     * Run at the first load after a start rather than on a timer: the file only
     * grows while the app is running, and a rewrite is the one operation here
     * that reads the whole thing. The marker is a preference and not the file's
     * own timestamp, because the file is appended to constantly and its mtime
     * says nothing about when it was last walked.
     *
     * A retention of zero keeps everything, matching every other zero in
     * [screen_time], and the core answers the same, so this still asks: it is
     * the core's decision and not a special case here.
     */
    private static void pruneIfDue() {
        final long at = now();
        final long last = ApplicationLoader.applicationContext
                .getSharedPreferences("purple", 0)
                .getLong("screentime_pruned", 0);
        if (at - last < PRUNE_EVERY_MS) {
            return;
        }
        final byte[] settings = PurpleGate.settingsBytes();
        queue().postRunnable(() -> {
            try {
                final File target = file();
                if (!target.exists()) {
                    return;
                }
                final byte[] log = PurpleSettings.readAll(target);
                final String pruned = PurpleCore.screenTimePrune(settings, log, at);
                if (pruned == null) {
                    // The bridge could not read it. The file is left exactly as
                    // it is: losing history to a failed prune would be worse
                    // than keeping a few expired lines.
                    return;
                }
                final byte[] bytes = pruned.getBytes(UTF_8);
                if (bytes.length != log.length) {
                    // Written through a temp file and a rename, like every
                    // other file here, so an interrupted rewrite leaves the old
                    // log rather than half of a new one. Events appended while
                    // this ran are on the same queue and land after it.
                    PurpleSettings.writeAtomic(target, bytes);
                }
                ApplicationLoader.applicationContext
                        .getSharedPreferences("purple", 0)
                        .edit()
                        .putLong("screentime_pruned", at)
                        .apply();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static boolean isUiThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
