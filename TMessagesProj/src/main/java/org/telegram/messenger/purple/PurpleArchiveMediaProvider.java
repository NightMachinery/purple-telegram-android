package org.telegram.messenger.purple;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.telegram.messenger.ApplicationLoader;

public final class PurpleArchiveMediaProvider extends ContentProvider {
    private static final ThreadPoolExecutor pipes = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), r -> {
        Thread thread = new Thread(r, "PurpleArchivePipe"); thread.setDaemon(true); return thread;
    });
    private static final class ProxyIO {
        static final Handler handler;
        static { HandlerThread thread = new HandlerThread("PurpleArchiveProxy"); thread.start(); handler = new Handler(thread.getLooper()); }
    }
    public static Uri uri(String token) {
        if (token == null || !token.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Archive capability token");
        return Uri.parse("content://" + ApplicationLoader.applicationContext.getPackageName() + ".purple_archive_media/" + token);
    }
    public static PurpleArchiveMediaAccess.Capability lookup(Uri uri) {
        if (uri == null || ApplicationLoader.applicationContext == null || uri.toString().length() > 256 || uri.getPathSegments().size() != 1) return null;
        String token = uri.getLastPathSegment();
        if (token == null || !token.matches("[0-9a-f]{64}") || !uri.toString().equals(uri(token).toString())) return null;
        return PurpleArchiveMediaAccess.find(token);
    }
    private static PurpleArchiveMediaAccess.Capability admit(Uri uri) throws FileNotFoundException {
        if (Binder.getCallingUid() != Process.myUid()) throw new FileNotFoundException("Archive URI is internal");
        PurpleArchiveMediaAccess.Capability value = lookup(uri);
        if (value == null) throw new FileNotFoundException("Archive capability expired"); return value;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { try { return admit(uri).mime; } catch (FileNotFoundException e) { return null; } }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            PurpleArchiveMediaAccess.Capability value = admit(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            if (columns.length > 8) return null;
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; ++i) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = value.name;
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = value.bytes;
            }
            MatrixCursor cursor = new MatrixCursor(columns, 1); cursor.addRow(row); return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Archive media is read only");
        PurpleArchiveMediaAccess.Capability cap = admit(uri);
        PurpleArchiveMediaDataSource source = null;
        try {
            source = new PurpleArchiveMediaDataSource(cap);
            if (Build.VERSION.SDK_INT >= 26) return Api26.proxy(this, source);
            return pipe(source);
        } catch (Exception | LinkageError e) {
            if (source != null) source.close(); throw new FileNotFoundException("Archive descriptor unavailable");
        }
    }
    private static final class Api26 {
    static ParcelFileDescriptor proxy(PurpleArchiveMediaProvider owner, PurpleArchiveMediaDataSource source) throws IOException {
        StorageManager manager = (StorageManager) owner.getContext().getSystemService(android.content.Context.STORAGE_SERVICE);
        if (manager == null) throw new IOException("Archive proxy service unavailable");
        return manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, new ProxyFileDescriptorCallback() {
            @Override public long onGetSize() throws ErrnoException {
                try { return source.size(); } catch (IOException e) { throw new ErrnoException("archive_size", OsConstants.EIO); }
            }
            @Override public int onRead(long offset, int length, byte[] data) throws ErrnoException {
                try { return Math.max(0, source.readAt(offset, data, 0, Math.min(length, data.length))); }
                catch (IOException e) { source.capability.close(); throw new ErrnoException("archive_read", OsConstants.EIO); }
            }
            @Override public void onRelease() { source.close(); }
        }, ProxyIO.handler);
    }
    }
    private static ParcelFileDescriptor pipe(PurpleArchiveMediaDataSource source) throws Exception {
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createReliablePipe();
        try {
            int flags = Os.fcntlInt(pair[1].getFileDescriptor(), OsConstants.F_GETFL, 0);
            Os.fcntlInt(pair[1].getFileDescriptor(), OsConstants.F_SETFL, flags | OsConstants.O_NONBLOCK);
            pipes.execute(() -> pump(source, pair[1])); return pair[0];
        } catch (Exception e) { pair[0].close(); pair[1].close(); throw e; }
    }
    private static void pump(PurpleArchiveMediaDataSource source, ParcelFileDescriptor writer) {
        byte[] buffer = new byte[65536];
        try {
            for (;;) {
                int length = source.read(buffer, 0, buffer.length); if (length < 0) break;
                int at = 0; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (at < length) {
                    if (!source.capability.available() || System.nanoTime() >= deadline) throw new IOException("Archive sequential reader cancelled or stalled");
                    try { int count = Os.write(writer.getFileDescriptor(), buffer, at, length - at); if (count <= 0) throw new IOException("Archive pipe stalled"); at += count; }
                    catch (ErrnoException e) { if (e.errno != OsConstants.EAGAIN) throw e; Thread.sleep(10); }
                }
                Arrays.fill(buffer, (byte) 0);
            }
            writer.close();
        } catch (Exception | LinkageError e) {
            try { writer.closeWithError("Archive media stream unavailable"); } catch (IOException ignored) {}
        } finally { Arrays.fill(buffer, (byte) 0); source.close(); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
