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
            "media_trace", "trace_build", "source_build", "capture_budget", "audio_record_start_returned", "resume_applied", "pause_preview_ready", "chat_hidden", "chat_visible", "process_hi", "process_lo", "take_hi", "take_lo", "backend", "elapsed_s", "elapsed_ms", "cap_s", "stop_s", "stop_ms", "legacy", "v2", "voice", "split", "capture", "started", "pause_request", "pause_ack", "resume_request", "resume_ack", "background", "foreground", "docked", "expanded", "discard_modal", "discard_cancel", "discard_request", "stop_request", "deadline", "audio_input_eos", "audio_output_eos", "video_input_eos", "video_output_eos", "drain_returned", "muxer_complete", "audio_encoder_release_returned", "video_encoder_release_returned", "flip_frame_submitted", "release_returned", "failure", "flip_request", "flip_configured", "flip_first_frame", "retain_request", "retain_saved", "retain_failed", "retry", "split_created", "split_cancelled", "split_cleanup", "state_starting", "state_recording", "state_pausing", "state_previewing", "state_resuming", "state_finishing", "state_completed", "state_error", "state_released",
            // diagnostics itself
            "diag", "export", "exported", "export_failed", "cleared", "clear_failed", "lines",
            // the repeat limit (PurpleDiagRepeats): diag repeated area=<word> event=<word> count=<dropped>
            "repeated", "area", "event", "count",
            // the sync chat (PurpleSyncMagicChat): lookup, Telegram requests, pauses and migration
            "sync_chat", "lookup_start", "lookup_result", "request", "wait_honored", "auto_paused", "auto_resumed",
            "problem_shown", "migration", "trigger", "startup", "screen", "manual", "auto", "post",
            "main", "loaded", "partial", "result", "found", "created", "absent_waiting", "ineligible", "bound",
            "rebound", "method", "outcome", "ok", "flood", "network", "timeout", "error", "seconds", "rpc",
            "channels.getfullchannel", "channels.createchannel", "folders.editpeerfolders",
            "account.updatenotifysettings", "messages.gethistory", "messages.getmessages", "channels.getmessages",
            "messages.sendmedia", "messages.editmessage", "messages.deletemessages", "channels.deletemessages",
            "upload.savefilepart", "upload.savebigfilepart", "upload.getfile",
            "chat_gone", "chat_left", "not_megagroup", "not_creator", "extra_member", "extra_admin",
            "public_username", "restricted", "cannot_post", "foreign_record", "forwarded_record", "via_bot_record",
            "create_limit", "create_uncertain", "magic_ineligible", "space_mismatch", "request_refused", "lists_loading",
            "drop_pending", "move", "inventory", "publish", "joined", "user_check", "user_resume", "chat_changed",
            "binding_unreadable", "code", "user_action", "choose", "default", "join", "listed", "value", "on", "off",
            "rejected", "wait_recorded", "retry",
            // first use of the sync chat (PurpleSyncFirstUse) and the Check screen's named refusals (PurpleSyncCheckReason)
            "sync", "first_use", "check_refused", "ask", "join_silently", "published", "archived", "refused",
            "not_first_use", "first_use_unavailable", "archive_refused", "check_changed", "check_failed", "apply_refused",
            "busy", "account_unavailable", "unreadable", "ambiguous", "newer_schema", "heads_unclear", "own_records",
            "synced", "this_device", "successor_unsent", "incomplete", "clone_detected", "account_unbound", "store_error", "invalid_settings",
            "using_last_good",
            // an automatic pass that threw (PurpleSyncAutoEngine.failureCode): sync auto_failed reason=<code>
            // one line per automatic pass (PurpleSyncAutoEngine.pass): sync auto_pass action= outcome= reason=
            "auto_send_refused", "send_not_postable", "send_no_client", "send_check_not_postable", "send_check_not_current",
            "send_not_current", "edit_head_absent", "edit_head_not_owned", "edit_head_changed", "edit_authority",
            "auto_pass", "no_op", "check", "auto_apply", "edit_in_place", "reconcile", "retire", "hold", "retry_at",
            "staged", "sent", "committed", "deleted", "network_failure", "outcome_unknown", "edit_refused",
            "deletion_refused", "transient_write_wait", "invalid_record", "ownership_unanswered", "wrong_file_account",
            "sync_chat_not_resolved", "chat_not_writable", "file_absent", "last_good", "no_saved_bytes", "needs_review",
            "conflict", "unsafe_impact", "work_mode_scope_changed", "store_mismatch", "ownership_changed",
            "retry_stopped", "interrupted_apply", "state_changed", "sync_chat_problem",
            "auto_failed", "automatic_chat_mismatch", "illegal_argument", "illegal_state", "json", "io", "unexpected",
            // the durable file guard (PurpleAccountFileSnapshot.inside): a sync path outside the app's files directory
            "path_refused", "path_outside_root",
            // the settings owner plan (PurpleAccountOwner)
            "owner", "planned", "accounts", "state", "ready", "owned", "pending_owner_writes",
            "waiting_for_first_sign_in", "needs_choice", "single_file_error", "invalid_input",
            "input_unavailable", "partial_view", "plan_stale",
            // write sessions (PurpleWriter) and their refusal codes, PurpleWriter.REASONS
            "writer", "capture_refused", "app_wide",
            "not_ui_thread", "write_in_progress", "view_not_current", "accounts_loading", "owner_not_ready",
            "choice_refused", "choice_applying", "owner_missing", "global_missing", "gate_not_loaded", "choice_stale",
            "account_signed_out", "choice_changed", "session_stale", "native_unavailable", "file_not_utf8",
            "route_missing", "local_choice_invalid", "route_changed", "files_changed", "recovery_required",
            "unsafe_file", "file_too_large", "file_unreadable", "session_failed",
            // the lists box (PurpleListBox, PurpleListSource) and its row sources
            "lists", "box_opened", "box_refreshed", "tick_failed", "new_list_refused", "read_failed",
            "editable", "source", "session_current", "lists_unreadable",
            "routed", "app_wide_good", "names_only", "none",
            "wait", "requested", "waiting", "aborted", "applied", "apply_failed", "held", "instant",
            "open_failed", "recovery_failed", "step", "paths", "login_context", "login_bootstrap", "removal_recovery",
            "catalogue_admission", "login_admission", "store_open", "store_close", "gap_read", "sequence_recovery",
            "capture_gap_recovery", "gap_recovery", "usage_scan", "gap_query", "staging_recovery", "media_recovery",
            "retained_paths", "retained_inspect", "retained_removal", "account_inventory", "native_load", "retained_root", "retained_aliases",
            "archive_schema_mismatch", "archive_schema_unsupported", "archive_login_persist_failed", "archive_login_nonce_missing",
            "archive_login_admission_failed", "archive_login_changed", "archive_login_record_missing",
            "archive_login_binding_missing", "archive_login_record_invalid", "archive_login_unauthenticated",
            "archive_login_key_unavailable", "archive_catalogue_ambiguous", "archive_capture_recovery_failed", "archive_key_failed",
            "archive_removal_failed", "archive_metadata_invalid", "archive_storage_failed", "archive_lock_failed", "archive_database_failed", "archive_open_failed",
            "archive", "capture_committed", "capture_unchanged", "capture_stale", "capture_gap",
            "capture_overflow", "capture_preimage_failed", "capture_identity_missing", "capture_write_failed", "capture_timeout", "capture_interrupted",
            "action", "peek_start", "peek_end", "peek_extend", "delay_edit", "ready_timeout_edit", "preset_change", "mode_change", "source_change", "peek_focus_policy",
            // F10 recordings (PurpleRecordingRegistry): counts and states only, never a path, chat or id
            "recordings", "recovery", "orphan_scan", "sweep", "begin", "discarded", "bin_cleared", "bin_send",
            "recovered", "unrecoverable", "missing", "finished_cleanly", "orphans", "failures", "expired",
            "kind", "kept", "io_failed", "voice", "round", "journal_stopped",
            "long_work", "cancelled",
            "long_copy_cleanup", "long_prepare", "long_reject", "long_range", "long_convert", "long_metadata",
            "long_format", "long_track", "long_duration", "part", "track",
            "exception", "avc", "aac", "conversion", "metadata",
            "metadata_release", "track_format", "track_scan", "track_unselect", "extractor_open",
            "extractor_release", "inspect_duration", "source_inspect", "selection", "space",
            "source_digest", "copy", "copy_digest", "work_check", "create",
            "conversion_setup", "audio_release", "part_inspect", "output_audio", "part_duration",
            "final_digest", "final_check", "media", "format", "audio_format",
            "tracks", "timestamps", "missing_samples", "source_changed", "duration",
            "width", "height", "rotation", "monotonic", "start_s",
            "start_ms", "start_us", "end_s", "end_ms", "end_us",
            "duration_s", "duration_ms", "duration_us", "first_s", "first_ms",
            "first_us", "last_s", "last_ms", "last_us", "current_s",
            "current_ms", "current_us", "expected_s", "expected_ms", "expected_us",
            // crash capture (PurpleCrashCapture, PurpleCrashRecord): record layout, field names and the codes the factories below emit
            "crash", "start", "frame", "end", "cause", "library", "exit", "n", "at", "line", "frames", "depth",
            "class", "main_thread", "status", "importance", "pss_mb", "rss_mb", "signal", "sigcode", "abort",
            "memory", "tool", "lib", "fn", "pc", "build_id", "system", "other",
            // ApplicationExitInfo reasons
            "unknown", "exit_self", "signaled", "low_memory", "crash_native", "anr", "initialization_failure",
            "permission_change", "excessive_resource_usage", "user_requested", "user_stopped", "dependency_died",
            "freezer", "package_state_change", "package_updated",
            // signals and their codes
            "sighup", "sigint", "sigquit", "sigill", "sigtrap", "sigabrt", "sigbus", "sigfpe", "sigkill", "sigusr1",
            "sigsegv", "sigusr2", "sigpipe", "sigalrm", "sigterm", "sigstkflt", "sigsys",
            "segv_maperr", "segv_accerr", "segv_bnderr", "segv_pkuerr", "segv_mteaerr", "segv_mteserr",
            "bus_adraln", "bus_adrerr", "bus_objerr", "bus_mceerr_ar", "bus_mceerr_ao",
            "fpe_intdiv", "fpe_intovf", "fpe_fltdiv", "fpe_fltovf", "fpe_fltund", "fpe_fltres", "fpe_fltinv", "fpe_fltsub",
            "ill_illopc", "ill_illopn", "ill_illadr", "ill_illtrp", "ill_prvopc", "ill_prvreg", "ill_coproc", "ill_badstk",
            "trap_brkpt", "trap_trace", "trap_branch", "trap_hwbkpt", "sys_seccomp",
            "si_user", "si_kernel", "si_queue", "si_timer", "si_mesgq", "si_asyncio", "si_sigio", "si_tkill",
            // abort message classes, memory error tools and types (PurpleCrashTombstone)
            "jni_error", "fortify", "check_failed", "scudo", "gwp_asan", "out_of_memory", "assertion", "mutex",
            "use_after_free", "double_free", "invalid_free", "buffer_overflow", "buffer_underflow",
            // ANR main thread states (PurpleCrashRecord)
            "runnable", "sleeping", "native", "suspended", "blocked", "timed_waiting", "gc"
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

    static Field sourceBuild(String value) {
        return new Field("source_build", value != null && value.matches("[0-9a-f]{7,40}(?:\\+dirty)?") ? value : "unknown");
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

    /** Packages whose names a crash frame keeps; any other class is logged as {@code other}. */
    static final String[] CODE_PACKAGES = {"org.telegram", "android", "androidx", "java", "javax", "kotlin", "com.google"};
    static final int FRAME_NAME_LIMIT = 200;
    static final int FUNCTION_LIMIT = 96;

    /**
     * One Java stack frame of a crash: {@code at=<class>.<method> line=<n>}.
     * Names pass {@link #codeClass} and {@link #codeMethod}, so a class of a
     * package outside {@link #CODE_PACKAGES} becomes {@code at=other} without
     * its method, and anything that is not an ASCII Java identifier with dots
     * becomes {@value #INVALID}. The line is a clamped count, and a negative
     * one (unknown, native) is left out.
     */
    public static Field frame(String className, String method, int line) {
        String cls = codeClass(className);
        String at = cls;
        if (!cls.equals("other") && !cls.equals(INVALID)) {
            String name = codeMethod(method);
            at = name.equals(INVALID) ? INVALID : cls + "." + name;
        }
        return new Field("at", line < 0 ? at : at + " line=" + clampCount(line));
    }

    /**
     * One native frame of a crash: {@code lib=<library> fn=<function> pc=0x<hex>}.
     * The library is kept only when it is one of the app's own
     * ({@link #appLibrary}) and is {@code system} otherwise; the relative pc,
     * a code offset into that library, is logged only for the app's own
     * libraries, so a frame in them can be symbolised later against the
     * build's unstripped library. The function name, when present, passes
     * {@link #codeFunction}, which filters a Java method's class like a Java
     * frame's.
     */
    public static Field nativeFrame(String library, String function, long relativePc) {
        String lib = appLibrary(library);
        StringBuilder value = new StringBuilder(lib == null ? "system" : lib);
        String fn = codeFunction(function);
        if (fn != null) value.append(" fn=").append(fn);
        if (lib != null && relativePc >= 0) value.append(" pc=0x").append(Long.toHexString(relativePc));
        return new Field("lib", value.toString());
    }

    /**
     * The build id of one of the app's own libraries, logged once per crash
     * record so its relative pcs can be matched to the right unstripped
     * library: {@code lib=<library> build_id=<hex>}. Null for any other
     * library, and {@code ?} for an id that is not lowercase hex of at most 64
     * characters.
     */
    public static Field library(String library, String buildId) {
        String lib = appLibrary(library);
        if (lib == null) return null;
        String id = buildId == null ? "" : buildId.toLowerCase(Locale.US);
        boolean hex = !id.isEmpty() && id.length() <= 64;
        for (int i = 0; i < id.length() && hex; ++i) hex = Character.digit(id.charAt(i), 16) >= 0;
        return new Field("lib", lib + " build_id=" + (hex ? id : "?"));
    }

    /** A signal and its code by number: {@code signal=sigsegv sigcode=segv_maperr}, or {@code other}. */
    public static Field signal(int signo, int code) {
        return new Field("signal", signalName(signo) + " sigcode=" + signalCode(signo, code));
    }

    /** A class name through {@link #codeClass}, such as an exception's: {@code class=java.lang.IllegalStateException}. */
    public static Field codeName(String key, String className) {
        return new Field(token(key), codeClass(className));
    }

    private static String clampCount(int value) {
        return value < 0 ? "neg" : value > MAX_COUNT ? MAX_COUNT + "+" : Integer.toString(value);
    }

    private static boolean identifier(String value, boolean dots) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '$'
                    || dots && c == '.';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * A class name for a crash line: the synthetic lambda part (from
     * {@code $$}, and the older {@code -$$Lambda$} prefix) is dropped, the
     * rest must be ASCII identifier characters and dots of at most
     * {@value #FRAME_NAME_LIMIT} characters ({@value #INVALID} otherwise),
     * and a class outside {@link #CODE_PACKAGES} is {@code other}.
     */
    static String codeClass(String className) {
        if (className == null) return INVALID;
        String name = className.replace("-$$Lambda$", "");
        int lambda = name.indexOf("$$");
        if (lambda >= 0) name = name.substring(0, lambda);
        if (name.length() > FRAME_NAME_LIMIT || !identifier(name, true) || name.startsWith(".") || name.endsWith(".")
                || name.contains("..")) return INVALID;
        for (String pkg : CODE_PACKAGES) {
            if (name.startsWith(pkg + ".")) return name;
        }
        return "other";
    }

    /**
     * A method name for a crash line: {@code <init>} and {@code <clinit>}
     * as they are, otherwise ASCII identifier characters of at most 80, with
     * synthetic numeric suffixes ({@code lambda$onPause$12} becomes
     * {@code lambda$onPause}) dropped.
     */
    static String codeMethod(String method) {
        if (method == null) return INVALID;
        if (method.equals("<init>") || method.equals("<clinit>")) return method;
        String name = method;
        while (true) {
            int dollar = name.lastIndexOf('$');
            if (dollar <= 0 || dollar == name.length() - 1) break;
            boolean digits = true;
            for (int i = dollar + 1; i < name.length() && digits; ++i) digits = Character.isDigit(name.charAt(i));
            if (!digits) break;
            name = name.substring(0, dollar);
        }
        return name.length() <= 80 && identifier(name, false) ? name : INVALID;
    }

    /**
     * A native function name, or null when there is none: cut at the
     * parameter list and at a {@code +offset}, and reduced to its last word
     * (dropping a return type such as {@code void}). Then, by its kind:
     * <ul>
     * <li>a C++ name ({@code ::}, or mangled, starting with {@code _Z}) is
     *     kept as identifier characters, {@code :}, {@code ~} and {@code $},
     *     with a mangled name's {@code .cold} or {@code .llvm.n} suffix cut;</li>
     * <li>a name with a dot is a Java method in ART's compiled or JIT code,
     *     and goes through {@link #codeClass} and {@link #codeMethod} exactly
     *     like a Java frame, so a class outside {@link #CODE_PACKAGES} is
     *     {@code other};</li>
     * <li>anything else is kept as identifier characters.</li>
     * </ul>
     * A name with any other character is {@value #INVALID}. At most
     * {@value #FUNCTION_LIMIT} characters.
     */
    static String codeFunction(String function) {
        if (function == null) return null;
        String name = function.trim();
        int cut = name.indexOf('(');
        if (cut >= 0) name = name.substring(0, cut);
        cut = name.indexOf('+');
        if (cut >= 0) name = name.substring(0, cut);
        name = name.trim();
        int space = name.lastIndexOf(' ');
        if (space >= 0) name = name.substring(space + 1);
        if (name.isEmpty()) return null;
        boolean cpp = name.contains("::") || name.startsWith("_Z");
        if (cpp && name.startsWith("_Z") && name.indexOf('.') > 0) name = name.substring(0, name.indexOf('.'));
        if (!cpp && name.indexOf('.') >= 0) {
            int dot = name.lastIndexOf('.');
            String cls = codeClass(name.substring(0, dot));
            if (cls.equals("other") || cls.equals(INVALID)) return cls;
            String method = codeMethod(name.substring(dot + 1));
            if (method.equals(INVALID)) return INVALID;
            name = cls + "." + method;
            return name.length() > FUNCTION_LIMIT ? name.substring(0, FUNCTION_LIMIT) : name;
        }
        if (name.length() > FUNCTION_LIMIT) name = name.substring(0, FUNCTION_LIMIT);
        for (int i = 0; i < name.length(); ++i) {
            char c = name.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '$'
                    || cpp && (c == ':' || c == '~');
            if (!ok) return INVALID;
        }
        return name;
    }

    /**
     * The base name of one of the app's own native libraries
     * ({@code libtmessages.*}, {@code libpurplecore*}, {@code libQt6Core*}),
     * or null for any other library. Only the part after the last {@code /}
     * or {@code !} (a library inside the APK) is looked at, so no directory
     * of the device is ever kept.
     */
    static String appLibrary(String library) {
        if (library == null) return null;
        String name = library.trim();
        int slash = Math.max(name.lastIndexOf('/'), Math.max(name.lastIndexOf('\\'), name.lastIndexOf('!')));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isEmpty() || name.length() > 48) return null;
        for (int i = 0; i < name.length(); ++i) {
            char c = name.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '.'
                    || c == '-';
            if (!ok) return null;
        }
        boolean own = name.startsWith("libtmessages.") && name.length() > "libtmessages.".length()
                || name.startsWith("libpurplecore") || name.startsWith("libQt6Core");
        return own ? name : null;
    }

    static String signalName(int signo) {
        switch (signo) {
            case 1: return "sighup";
            case 2: return "sigint";
            case 3: return "sigquit";
            case 4: return "sigill";
            case 5: return "sigtrap";
            case 6: return "sigabrt";
            case 7: return "sigbus";
            case 8: return "sigfpe";
            case 9: return "sigkill";
            case 10: return "sigusr1";
            case 11: return "sigsegv";
            case 12: return "sigusr2";
            case 13: return "sigpipe";
            case 14: return "sigalrm";
            case 15: return "sigterm";
            case 16: return "sigstkflt";
            case 31: return "sigsys";
            default: return "other";
        }
    }

    private static final String[] SEGV = {"segv_maperr", "segv_accerr", "segv_bnderr", "segv_pkuerr", null, null, null,
            "segv_mteaerr", "segv_mteserr"};
    private static final String[] BUS = {"bus_adraln", "bus_adrerr", "bus_objerr", "bus_mceerr_ar", "bus_mceerr_ao"};
    private static final String[] FPE = {"fpe_intdiv", "fpe_intovf", "fpe_fltdiv", "fpe_fltovf", "fpe_fltund",
            "fpe_fltres", "fpe_fltinv", "fpe_fltsub"};
    private static final String[] ILL = {"ill_illopc", "ill_illopn", "ill_illadr", "ill_illtrp", "ill_prvopc",
            "ill_prvreg", "ill_coproc", "ill_badstk"};
    private static final String[] TRAP = {"trap_brkpt", "trap_trace", "trap_branch", "trap_hwbkpt"};

    /** The name of a signal's {@code si_code}: the generic sender codes, then the codes of SEGV, BUS, FPE, ILL, TRAP and SYS. */
    static String signalCode(int signo, int code) {
        switch (code) {
            case 0: return "si_user";
            case 0x80: return "si_kernel";
            case -1: return "si_queue";
            case -2: return "si_timer";
            case -3: return "si_mesgq";
            case -4: return "si_asyncio";
            case -5: return "si_sigio";
            case -6: return "si_tkill";
            default: break;
        }
        String[] names;
        switch (signo) {
            case 11: names = SEGV; break;
            case 7: names = BUS; break;
            case 8: names = FPE; break;
            case 4: names = ILL; break;
            case 5: names = TRAP; break;
            case 31: names = new String[]{"sys_seccomp"}; break;
            default: names = null;
        }
        String name = names != null && code >= 1 && code <= names.length ? names[code - 1] : null;
        return name == null ? "other" : name;
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
        return stamped(unixMillis, body(area, event, fields));
    }

    /** A line without its time, {@code <area> <event> key=value...}, as a crash record keeps it before it is stamped. */
    static String body(String area, String event, Field... fields) {
        StringBuilder out = new StringBuilder(token(area)).append(' ').append(token(event));
        if (fields != null) for (Field field : fields) {
            if (field != null) out.append(' ').append(field.key).append('=').append(field.value);
        }
        return out.length() > LINE_LIMIT ? out.substring(0, LINE_LIMIT) : out.toString();
    }

    /** {@code body} with its UTC time in front, bounded to {@value #LINE_LIMIT} characters. */
    static String stamped(long unixMillis, String body) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String out = format.format(new Date(unixMillis)) + ' ' + body;
        return out.length() > LINE_LIMIT ? out.substring(0, LINE_LIMIT) : out;
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
