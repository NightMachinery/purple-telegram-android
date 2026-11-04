package org.telegram.messenger;

import java.io.File;
import org.telegram.tgnet.TLRPC;

public final class FileLoader {
    public static final int MEDIA_DIR_CACHE=4,PRIORITY_NORMAL_UP=1;
    public static String getDocumentFileName(TLRPC.Document document) {
        for(TLRPC.DocumentAttribute attribute:document.attributes)
            if(attribute.getClass()==TLRPC.TL_documentAttributeFilename.class)return attribute.file_name;
        return "";
    }
    public static String getAttachFileName(TLRPC.Document document){return getDocumentFileName(document);}
    public static File getDirectory(int kind){return new File(System.getProperty("scratch","."),"loader-cache");}
    public static FileLoader getInstance(int account){throw new AssertionError("unexpected native loader");}
    public File getPathToAttach(TLRPC.Document document,boolean cache){throw new AssertionError("unexpected native download");}
    public boolean isLoadingFile(String name){throw new AssertionError("unexpected native download");}
    public void loadFile(TLRPC.Document document,Object parent,int priority,int cache){throw new AssertionError("unexpected native download");}
    public void cancelLoadFile(TLRPC.Document document){throw new AssertionError("unexpected native download");}
}
