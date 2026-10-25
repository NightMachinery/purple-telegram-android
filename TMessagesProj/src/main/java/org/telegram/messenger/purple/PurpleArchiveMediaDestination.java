package org.telegram.messenger.purple;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.OutputStream;
import org.telegram.messenger.ApplicationLoader;

public final class PurpleArchiveMediaDestination implements PurpleArchiveMediaExport.Destination {
    private final Uri uri;
    private PurpleArchiveMediaDestination(Uri uri) { this.uri = uri; }
    public static PurpleArchiveMediaDestination create(Uri uri) {
        if (uri == null || ApplicationLoader.applicationContext == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null) return null;
        String prefix = ApplicationLoader.applicationContext.getPackageName() + ".";
        return uri.getAuthority().startsWith(prefix) ? null : new PurpleArchiveMediaDestination(uri);
    }
    @Override public OutputStream open() throws Exception {
        OutputStream result = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri, "wt");
        if (result == null) throw new IOException("Archive export destination unavailable"); return result;
    }
    @Override public boolean delete() throws Exception {
        ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        return DocumentsContract.isDocumentUri(ApplicationLoader.applicationContext, uri) ? DocumentsContract.deleteDocument(resolver, uri) : resolver.delete(uri, null, null) > 0;
    }
}
