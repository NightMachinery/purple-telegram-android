package org.telegram.messenger.purple;

import android.os.FileObserver;
import org.telegram.messenger.AndroidUtilities;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class PurpleWatcher {
    private static final ArrayList<FileObserver> observers = new ArrayList<>();
    private static boolean started;
    private static long binding;
    private static final int EVENTS = FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO
            | FileObserver.MOVED_FROM | FileObserver.CREATE | FileObserver.DELETE
            | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF;
    private static final Runnable RELOAD = () -> PurpleSync.refreshFromWatcher(() -> {
        PurpleAccountOwner.reloadGlobal();
        PurpleAccountSettings.invalidate();
        rebind();
        PurpleGate.reload("account settings changed on disk");
    });

    public static synchronized void start() {
        if (started) return;
        started = true;
        rebind();
    }

    public static synchronized void rebind() {
        if (!started) return;
        ++binding;
        long current = binding;
        for (FileObserver observer : observers) observer.stopWatching();
        observers.clear();
        Set<String> paths = new HashSet<>();
        paths.add(PurpleAccountFiles.root().getParent());
        addExisting(paths, PurpleAccountFiles.root());
        addExisting(paths, PurpleAccountFiles.file("accounts"));
        PurpleAccountFiles global = PurpleAccountOwner.globalFiles();
        if (global != null) addExisting(paths, global.directory);
        if (PurpleAccountOwner.ready()) {
            for (PurpleAccountFiles.Identity id : PurpleAccountOwner.signedIn()) {
                PurpleAccountFiles files = PurpleAccountFiles.resolve(id.key, PurpleAccountOwner.owner());
                if (files != null) addExisting(paths, files.directory);
            }
        }
        for (String path : paths) {
            try {
                FileObserver observer = new FileObserver(path, EVENTS) {
                    @Override public void onEvent(int event, String name) {
                        synchronized (PurpleWatcher.class) {
                            if (current != binding) return;
                        }
                        if (name != null && !(name.equals("purple") || name.equals("accounts")
                                || name.equals("local.toml") || name.equals("settings.toml")
                                || name.equals("settings.toml.good") || name.matches("[01]-[1-9][0-9]*"))) return;
                        AndroidUtilities.cancelRunOnUIThread(RELOAD);
                        AndroidUtilities.runOnUIThread(RELOAD, 400);
                    }
                };
                observer.startWatching();
                observers.add(observer);
            } catch (RuntimeException e) { }
        }
    }

    private static void addExisting(Set<String> paths, File directory) {
        if (PurpleAccountFileSnapshot.directory(directory) == 1) paths.add(directory.getPath());
    }

    private PurpleWatcher() { }
}
