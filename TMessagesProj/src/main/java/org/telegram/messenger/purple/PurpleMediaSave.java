package org.telegram.messenger.purple;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.view.Gravity;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.HintView2;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public final class PurpleMediaSave {
    public static final int GALLERY = 0x70750020;
    public static final int DOWNLOADS = 0x70750021;
    private static final int MENU = 0x70750022;

    private PurpleMediaSave() {
    }

    private static boolean hasMedia(MessageObject message) {
        if (message == null || message.messageOwner == null || message.isExpiredStory()) {
            return false;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        TLRPC.Document document = MessageObject.getDocument(message.messageOwner);
        return document != null && !(document instanceof TLRPC.TL_documentEmpty)
                || media != null && media.photo != null && !(media.photo instanceof TLRPC.TL_photoEmpty);
    }

    private static boolean visual(MessageObject message) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        return message.isPhoto() || message.isVideo() || message.isRoundVideo() || message.isGif()
                || media != null && media.photo != null;
    }

    private static boolean restricted(MessageObject message) {
        return message.messageOwner.noforwards || message.isSecretMedia()
                || message.isVoiceOnce() || message.isRoundOnce()
                || MessagesController.getInstance(message.currentAccount).isPeerNoForwards(message.getDialogId());
    }

    public static void addMenuOptions(MessageObject message, ArrayList<CharSequence> items,
            ArrayList<Integer> options, ArrayList<Integer> icons) {
        if (!hasMedia(message) || !restricted(message)) {
            return;
        }
        for (int i = options.size() - 1; i >= 0; i--) {
            int option = options.get(i);
            if (option == ChatActivity.OPTION_SAVE_TO_GALLERY || option == ChatActivity.OPTION_SAVE_TO_GALLERY2
                    || option == ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC) {
                options.remove(i);
                items.remove(i);
                icons.remove(i);
            }
        }
        if (visual(message)) {
            items.add(LocaleController.getString(R.string.SaveToGallery));
            options.add(message.isLivePhoto() && !message.shouldEncryptPhotoOrVideo()
                    ? ChatActivity.OPTION_SAVE_TO_GALLERY : GALLERY);
            icons.add(R.drawable.msg_gallery);
        }
        if (!options.contains(DOWNLOADS)) {
            items.add(LocaleController.getString(R.string.SaveToDownloads));
            options.add(message.isLivePhoto() && !message.shouldEncryptPhotoOrVideo()
                    ? ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC : DOWNLOADS);
            icons.add(R.drawable.msg_download);
        }
    }

    public static void addAudioOption(ItemOptions options, MessageObject message) {
        if (hasMedia(message) && restricted(message)) {
            options.add(R.drawable.msg_download, LocaleController.getString(R.string.SaveToDownloads),
                    () -> save(options.getContext(), message, false));
        }
    }

    public static void addSecretMenu(ActionBar bar, android.view.View timer, HintView2 hint,
            Utilities.Callback0Return<MessageObject> current) {
        ActionBarMenuItem menu = bar.createMenu().addItem(MENU, R.drawable.ic_ab_other);
        menu.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        addSaveItems(menu, bar.getContext(), current, true);
        timer.setTranslationX(-AndroidUtilities.dp(48));
        hint.setJoint(1, -74);
        bar.setTitleRightMargin(AndroidUtilities.dp(189));
    }

    public static void addVoiceMenu(FrameLayout container, Utilities.Callback0Return<MessageObject> current) {
        View previous = container.findViewWithTag(MENU);
        if (previous != null) {
            container.removeView(previous);
        }
        ActionBarMenuItem menu = new ActionBarMenuItem(container.getContext(), null, 0, 0xffffffff);
        menu.setTag(MENU);
        menu.setIcon(R.drawable.ic_ab_other);
        menu.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        menu.setOnClickListener(v -> menu.toggleSubMenu());
        addSaveItems(menu, container.getContext(), current, hasMedia(current.run()) && visual(current.run()));
        container.addView(menu, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.RIGHT, 0, 16, 16, 0));
    }

    private static void addSaveItems(ActionBarMenuItem menu, Context context,
            Utilities.Callback0Return<MessageObject> current, boolean gallery) {
        if (gallery) {
            menu.addSubItem(GALLERY, R.drawable.msg_gallery, LocaleController.getString(R.string.SaveToGallery))
                    .setOnClickListener(v -> {
                        menu.closeSubMenu();
                        save(context, current.run(), true);
                    });
        }
        menu.addSubItem(DOWNLOADS, R.drawable.msg_download, LocaleController.getString(R.string.SaveToDownloads))
                .setOnClickListener(v -> {
                    menu.closeSubMenu();
                    save(context, current.run(), false);
                });
    }

    public static boolean saveEncrypted(Context context, MessageObject message, boolean gallery) {
        if (hasMedia(message) && message.shouldEncryptPhotoOrVideo()) {
            save(context, message, gallery);
            return true;
        }
        return false;
    }

    public static void save(Context context, MessageObject message, boolean gallery) {
        if (context == null || !hasMedia(message)) {
            return;
        }
        Activity activity = AndroidUtilities.findActivity(context);
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (activity != null) {
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
            }
            return;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> export(app, message, gallery), "PurpleMediaSave").start();
    }

    private static PurpleMediaSaveSource candidate(File path, boolean encryptedCache,
            TLRPC.Document document, String mime) {
        String name = document == null ? null : FileLoader.getDocumentFileName(document);
        if (document != null) {
            mime = document.mime_type;
            if ((name == null || name.isEmpty()) && path != null) {
                name = path.getName();
            }
        }
        return PurpleMediaSaveSource.find(path, encryptedCache, name, mime);
    }

    private static PurpleMediaSaveSource localFile(MessageObject message) {
        FileLoader loader = FileLoader.getInstance(message.currentAccount);
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        boolean photo = media != null && media.photo != null;
        TLRPC.Document document = photo ? null : MessageObject.getDocument(message.messageOwner);
        String mime = photo ? "image/jpeg" : message.getMimeType();
        String attached = message.messageOwner.attachPath;
        PurpleMediaSaveSource source = candidate(attached == null || attached.isEmpty() ? null : new File(attached), false, document, mime);
        if (source != null) {
            return source;
        }
        source = candidate(loader.getPathToMessage(message.messageOwner), false, document, mime);
        if (source != null) {
            return source;
        }
        for (File path : cachePaths(message)) {
            source = candidate(path, true, document, mime);
            if (source != null) {
                return source;
            }
        }
        if (message.cachedQuality != null && message.cachedQuality.isCached()) {
            String path = message.cachedQuality.uri.getPath();
            if (path != null) {
                source = candidate(new File(path), false, message.cachedQuality.document, mime);
                if (source != null) {
                    return source;
                }
            }
        }
        if (message.qualityToSave != null) {
            source = candidate(loader.getPathToAttach(message.qualityToSave, null, false, true), false, message.qualityToSave, mime);
            if (source != null) {
                return source;
            }
            return candidate(loader.getPathToAttach(message.qualityToSave, null, true, true), true, message.qualityToSave, mime);
        }
        return null;
    }

    private static ArrayList<File> cachePaths(MessageObject message) {
        FileLoader loader = FileLoader.getInstance(message.currentAccount);
        ArrayList<File> paths = new ArrayList<>();
        paths.add(loader.getPathToMessage(message.messageOwner, true, true));
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media != null && media.photo != null) {
            TLRPC.PhotoSize displayed = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
            if (displayed != null) {
                paths.add(loader.getPathToAttach(displayed, true));
            }
        }
        return paths;
    }

    private static boolean isExpectedCacheFile(MessageObject message, File source) throws java.io.IOException {
        File cache = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE).getCanonicalFile();
        File actual = source.getCanonicalFile();
        if (!cache.equals(actual.getParentFile())) {
            return false;
        }
        ArrayList<File> expectedPaths = cachePaths(message);
        FileLoader loader = FileLoader.getInstance(message.currentAccount);
        if (message.cachedQuality != null && message.cachedQuality.isCached() && message.cachedQuality.uri.getPath() != null) {
            expectedPaths.add(new File(message.cachedQuality.uri.getPath()));
        }
        if (message.qualityToSave != null) {
            expectedPaths.add(loader.getPathToAttach(message.qualityToSave, null, false, true));
            expectedPaths.add(loader.getPathToAttach(message.qualityToSave, null, true, true));
        }
        for (File expected : expectedPaths) {
            if (actual.equals(expected.getCanonicalFile())
                    || actual.equals(new File(expected.getPath() + ".enc").getCanonicalFile())) {
                return true;
            }
        }
        return false;
    }

    private static void export(Context context, MessageObject message, boolean gallery) {
        Uri outputUri = null;
        File outputFile = null;
        boolean saved = false;
        try {
            PurpleMediaSaveSource selected = localFile(message);
            if (selected == null || selected.file.length() == 0) {
                notify(context, R.string.PleaseDownload);
                return;
            }
            File source = selected.file;
            if (AndroidUtilities.isInternalUri(Uri.fromFile(source)) && !isExpectedCacheFile(message, source)) {
                throw new SecurityException("Internal media path");
            }
            String mime = selected.mime;
            boolean video = mime != null && !mime.isEmpty() ? mime.startsWith("video/") : message.isVideo() || message.isRoundVideo();
            if (mime == null || mime.isEmpty()) {
                mime = gallery ? (video ? "video/mp4" : "image/jpeg") : "application/octet-stream";
            }
            String name = selected.name;
            if (name == null || name.isEmpty()) {
                String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                name = AndroidUtilities.generateFileName(video ? 1 : 0, extension == null ? "bin" : extension);
            }
            name = new File(name).getName();
            String directory = gallery ? (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                    : Environment.DIRECTORY_DOWNLOADS;
            InputStream input = selected.encrypted
                    ? new EncryptedFileInputStream(source, new File(FileLoader.getInternalCacheDir(), source.getName() + ".key"))
                    : new FileInputStream(source);
            try (InputStream in = input) {
                long size = ((FileInputStream) in).getChannel().size();
                if (size <= 0) {
                    throw new java.io.IOException("Empty media source");
                }
                OutputStream destination;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/Telegram");
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri collection = !gallery ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                            : video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    outputUri = context.getContentResolver().insert(collection, values);
                    if (outputUri == null) {
                        throw new java.io.IOException("Media destination unavailable");
                    }
                    destination = context.getContentResolver().openOutputStream(outputUri);
                } else {
                    File folder = new File(Environment.getExternalStoragePublicDirectory(directory), "Telegram");
                    if (!folder.isDirectory() && !folder.mkdirs()) {
                        throw new java.io.IOException("Media directory unavailable");
                    }
                    int dot = name.lastIndexOf('.');
                    outputFile = File.createTempFile("Purple_", dot < 0 ? "" : name.substring(dot), folder);
                    destination = new FileOutputStream(outputFile);
                }
                if (destination == null) {
                    throw new java.io.IOException("Media output unavailable");
                }
                try (OutputStream out = new BufferedOutputStream(destination)) {
                    PurpleMediaSaveStream.copy(in, out, size, selected.encrypted);
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (context.getContentResolver().update(outputUri, values, null, null) <= 0) {
                    throw new java.io.IOException("Media publication failed");
                }
            } else {
                MediaScannerConnection.scanFile(context, new String[]{outputFile.getAbsolutePath()}, new String[]{mime}, null);
            }
            saved = true;
            notify(context, R.string.PurpleMediaSaved);
        } catch (Exception error) {
            FileLog.e(error);
            notify(context, R.string.ErrorOccurred);
        } finally {
            if (!saved) {
                try {
                    if (outputUri != null) {
                        context.getContentResolver().delete(outputUri, null, null);
                    }
                    if (outputFile != null) {
                        outputFile.delete();
                    }
                } catch (Exception error) {
                    FileLog.e(error);
                }
            }
        }
    }

    private static void notify(Context context, int text) {
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(context, LocaleController.getString(text), Toast.LENGTH_SHORT).show());
    }
}
