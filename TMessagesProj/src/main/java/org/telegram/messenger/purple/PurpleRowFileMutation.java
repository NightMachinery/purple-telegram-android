package org.telegram.messenger.purple;

import java.io.File;
import java.io.FileOutputStream;

final class PurpleRowFileMutation {
    interface Guard {
        boolean current();
        boolean prepare(File temporary) throws Exception;
    }

    static boolean replace(File target, byte[] bytes, Guard guard) {
        if (target == null || bytes == null || !guard.current()) return false;
        File temporary = null;
        boolean published = false;
        try {
            temporary = File.createTempFile("row-", ".tmp", target.getParentFile());
            if (!guard.prepare(temporary) || !guard.current()) return false;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            published = publish(guard, temporary, target);
            return published;
        } catch (Exception e) {
            return false;
        } finally {
            if (temporary != null && !published) temporary.delete();
        }
    }

    static boolean publish(Guard guard, File temporary, File target) {
        return guard.current() && temporary.renameTo(target);
    }

    private PurpleRowFileMutation() { }
}
