package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleDiagLog;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * The Purple settings rows that export and clear the diagnostic log. Export
 * shares a text file through the same FileProvider authority upstream's
 * "Send logs" uses; see {@link PurpleDiagLog} for what the file contains.
 */
final class PurpleDiagnosticsUI {
    private static final int EXPORT = 0x70750330;
    private static final int CLEAR = 0x70750331;

    static void add(ArrayList<UItem> items) {
        items.add(UItem.asHeader(text(R.string.PurpleDiagnosticsHeader)));
        items.add(UItem.asButton(EXPORT, text(R.string.PurpleDiagnosticsExport)));
        items.add(UItem.asButton(CLEAR, text(R.string.PurpleDiagnosticsClear)));
        items.add(UItem.asShadow(text(R.string.PurpleDiagnosticsInfo)));
    }

    static boolean click(UniversalFragment fragment, int row) {
        if (row == EXPORT) {
            export(fragment);
            return true;
        }
        if (row == CLEAR) {
            if (fragment.getParentActivity() == null) return true;
            fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                    .setTitle(text(R.string.PurpleDiagnosticsClear))
                    .setMessage(text(R.string.PurpleDiagnosticsClearConfirm))
                    .setPositiveButton(text(R.string.PurpleDiagnosticsClearAction), (dialog, which) -> {
                        WeakReference<UniversalFragment> weak = new WeakReference<>(fragment);
                        PurpleDiagLog.clear(cleared -> notice(weak.get(),
                                text(cleared ? R.string.PurpleDiagnosticsCleared : R.string.PurpleDiagnosticsClearFailed)));
                    })
                    .setNegativeButton(text(R.string.Cancel), null).create());
            return true;
        }
        return false;
    }

    private static void export(UniversalFragment fragment) {
        WeakReference<UniversalFragment> weak = new WeakReference<>(fragment);
        PurpleDiagLog.event("diag", "export");
        PurpleDiagLog.export(file -> {
            UniversalFragment current = weak.get();
            Activity activity = current == null ? null : current.getParentActivity();
            if (activity == null) return;
            if (file == null) {
                notice(current, text(R.string.PurpleDiagnosticsExportFailed));
                return;
            }
            try {
                Uri uri = Build.VERSION.SDK_INT >= 24
                        ? FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file)
                        : Uri.fromFile(file);
                Intent intent = new Intent(Intent.ACTION_SEND);
                if (Build.VERSION.SDK_INT >= 24) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_SUBJECT, file.getName());
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                activity.startActivity(Intent.createChooser(intent, text(R.string.PurpleDiagnosticsExport)));
            } catch (Exception e) {
                FileLog.e(e);
                notice(current, text(R.string.PurpleDiagnosticsExportFailed));
            }
        });
    }

    private static void notice(UniversalFragment fragment, String message) {
        if (fragment != null && BulletinFactory.canShowBulletin(fragment)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, message).show();
        }
    }

    private static String text(int id) {
        return LocaleController.getString(id);
    }

    private PurpleDiagnosticsUI() {
    }
}
