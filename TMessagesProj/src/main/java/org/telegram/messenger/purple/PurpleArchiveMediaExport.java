package org.telegram.messenger.purple;

import java.io.OutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.telegram.messenger.AndroidUtilities;

public final class PurpleArchiveMediaExport {
    public interface Destination { OutputStream open() throws Exception; boolean delete() throws Exception; }
    public interface Delivered { void result(Result result); }
    public static final class Result {
        public final boolean saved, cancelled, cleanupFailed, removed;
        Result(boolean saved, boolean cancelled, boolean cleanupFailed, boolean removed) {
            this.saved = saved; this.cancelled = cancelled; this.cleanupFailed = cleanupFailed; this.removed = removed;
        }
    }
    public static final class Ticket implements AutoCloseable {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final PurpleArchiveMediaAccess.Capability capability;
        Ticket(PurpleArchiveMediaAccess.Capability capability) { this.capability = capability; }
        @Override public void close() { cancelled.set(true); capability.close(); }
        boolean current() { return !cancelled.get() && capability.available(); }
    }
    private static final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> {
        Thread thread = new Thread(r, "PurpleArchiveExport"); thread.setDaemon(true); return thread;
    });
    public static Ticket save(PurpleArchiveMediaAccess.Capability capability, Destination destination, Delivered delivered) {
        if (capability == null || destination == null || delivered == null || !capability.available()) return null;
        Ticket ticket = new Ticket(capability);
        try { io.execute(() -> export(ticket, destination, delivered)); return ticket; }
        catch (java.util.concurrent.RejectedExecutionException e) { return null; }
    }
    public static boolean cleanup(Destination destination, Delivered delivered) {
        try {
            io.execute(() -> {
                boolean failed; try { failed = !destination.delete(); } catch (Exception | LinkageError e) { failed = true; }
                boolean result = failed; AndroidUtilities.runOnUIThread(() -> delivered.result(new Result(false, true, result, !result)));
            }); return true;
        } catch (java.util.concurrent.RejectedExecutionException e) { return false; }
    }
    private static void export(Ticket ticket, Destination destination, Delivered delivered) {
        boolean saved = false, cleanupFailed = false, removed = false;
        byte[] buffer = new byte[65536];
        try {
            if (!ticket.current()) throw new IOException("Archive export revoked");
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); long count = 0;
            try (OutputStream output = destination.open(); PurpleArchiveMediaDataSource input = new PurpleArchiveMediaDataSource(ticket.capability)) {
                for (;;) {
                    if (!ticket.current()) throw new IOException("Archive export cancelled");
                    int length = input.read(buffer, 0, buffer.length); if (length < 0) break;
                    hash.update(buffer, 0, length); count = Math.addExact(count, length);
                    if (!ticket.current()) throw new IOException("Archive export revoked"); output.write(buffer, 0, length); Arrays.fill(buffer, (byte) 0);
                }
                if (count != ticket.capability.bytes || !PurpleArchiveMediaAccess.hex(hash.digest()).equals(ticket.capability.expected.getString("blob"))
                    || !ticket.current()) throw new IOException("Archive export final verification failed");
                output.flush();
            }
            saved = true;
        } catch (Exception | LinkageError e) {
            try { removed = destination.delete(); cleanupFailed = !removed; } catch (Exception | LinkageError failure) { cleanupFailed = true; }
            if (cleanupFailed) PurpleArchive.captureGap(ticket.capability.identity, "archive_export_partial_cleanup_failed");
        } finally { Arrays.fill(buffer, (byte) 0); }
        boolean complete = saved, failedCleanup = cleanupFailed, deleted = removed;
        AndroidUtilities.runOnUIThread(() -> {
            boolean current = ticket.current();
            try { delivered.result(new Result(complete, !current, failedCleanup, deleted)); }
            finally { ticket.capability.close(); }
        });
    }
    private PurpleArchiveMediaExport() {}
}
