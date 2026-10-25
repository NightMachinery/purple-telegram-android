package org.telegram.messenger;
public final class FileLoader {
    public static java.io.File getInternalCacheDir() { return ApplicationLoader.files; }
    public static String getDocumentFileName(org.telegram.tgnet.TLRPC.Document document) {
        for (org.telegram.tgnet.TLRPC.DocumentAttribute value : document.attributes) if (value instanceof org.telegram.tgnet.TLRPC.TL_documentAttributeFilename) return value.file_name;
        return "";
    }
}
