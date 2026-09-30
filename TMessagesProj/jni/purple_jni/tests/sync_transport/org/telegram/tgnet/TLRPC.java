package org.telegram.tgnet;

import java.util.ArrayList;

public class TLRPC {
    public abstract static class DocumentAttribute {
        public String file_name;
    }

    public static class TL_documentAttributeFilename extends DocumentAttribute {
    }

    public static class TL_documentAttributeVideo extends DocumentAttribute {
    }

    public abstract static class Document {
        public ArrayList<DocumentAttribute> attributes = new ArrayList<>();
    }

    public static class TL_document extends Document {
    }
}
