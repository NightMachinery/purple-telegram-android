package android.provider;
public final class DocumentsContract {public static boolean isDocumentUri(android.content.Context c,android.net.Uri u){return true;}public static boolean deleteDocument(android.content.ContentResolver r,android.net.Uri u){return r.delete(u,null,null)>0;}}
