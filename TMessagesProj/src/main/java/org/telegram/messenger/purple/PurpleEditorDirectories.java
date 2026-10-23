package org.telegram.messenger.purple;

import org.json.JSONObject;
import java.io.File;

final class PurpleEditorDirectories {
    interface Creator {
        Receipt create(File directory, PurpleGlobalChoiceFiles.Mark parent) throws Exception;
    }
    static final class Receipt {
        final boolean created;
        final boolean owned;
        final String parentIdentity;
        final PurpleGlobalChoiceFiles.Mark mark;
        Receipt(JSONObject value) {
            created = value.optBoolean("created");
            owned = value.optBoolean("owned");
            parentIdentity = value.optString("parentIdentity");
            mark = new PurpleGlobalChoiceFiles.Mark(value.optString("identity"), "", value.optInt("mode"));
        }
    }
    static final Creator NATIVE = (directory, parent) -> new Receipt(new JSONObject(createNative(
            directory.getParent(), directory.getName(), parent.identity)));
    private static native String createNative(String parent, String name, String parentIdentity);
    private PurpleEditorDirectories() { }
}
