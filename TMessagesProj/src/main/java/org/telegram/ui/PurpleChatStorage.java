package org.telegram.ui;

import android.app.Dialog;
import android.os.Bundle;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.CacheControlActivity.DialogFileEntities;
import org.telegram.ui.Storage.CacheModel;

import java.util.ArrayList;
import java.util.HashSet;

public final class PurpleChatStorage {
    private final CacheControlActivity owner;
    private long scopedDialogId;
    private long scopedMergeDialogId;
    private boolean scopedDialogHandled;
    private ArrayList<DialogFileEntities> scopedEntities;
    private ShowAttempt pendingAttempt;

    public PurpleChatStorage(CacheControlActivity owner) {
        this.owner = owner;
    }

    public void read(Bundle arguments) {
        scopedDialogId = arguments != null ? arguments.getLong("dialog_id", 0) : 0;
        scopedMergeDialogId = arguments != null ? arguments.getLong("merge_dialog_id", 0) : 0;
    }

    public void onEntities(ArrayList<DialogFileEntities> entities) {
        showScopedDialog(entities);
    }

    public void onTransitionEnd(boolean isOpen) {
        if (isOpen && scopedEntities != null) {
            showScopedDialog(scopedEntities);
        }
    }

    public void onResume() {
        if (scopedEntities != null) {
            showScopedDialog(scopedEntities);
        }
    }

    private void showScopedDialog(ArrayList<DialogFileEntities> entities) {
        if (scopedDialogId == 0 || scopedDialogHandled) {
            return;
        }
        scopedEntities = entities;
        if (owner.isPaused() || owner.getParentActivity() == null || owner.getParentLayout() == null
                || owner.getParentLayout().isTransitionAnimationInProgress()) {
            return;
        }
        scopedDialogHandled = true;
        scopedEntities = null;
        DialogFileEntities own = null;
        DialogFileEntities migrated = null;
        for (int i = 0; i < entities.size(); i++) {
            DialogFileEntities candidate = entities.get(i);
            if (candidate.dialogId == scopedDialogId) {
                own = candidate;
            } else if (scopedMergeDialogId != 0 && candidate.dialogId == scopedMergeDialogId) {
                migrated = candidate;
            }
        }
        if (migrated != null) {
            DialogFileEntities combined = new DialogFileEntities(scopedDialogId);
            if (own != null) {
                combined.merge(own);
            }
            combined.merge(migrated);
            if (show(combined, own, migrated)) {
                return;
            }
        } else if (own != null && show(own)) {
            return;
        }
        owner.showDialog(new AlertDialog.Builder(owner.getParentActivity(), owner.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.PurpleChatStorage))
                .setMessage(LocaleController.getString(R.string.PurpleChatStorageEmpty))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create());

    }

    private boolean show(DialogFileEntities entities, DialogFileEntities... parts) {
        ShowAttempt attempt = new ShowAttempt(entities, parts);
        pendingAttempt = attempt;
        try {
            owner.showClearCacheDialog(entities);
            return attempt.shown;
        } finally {
            if (pendingAttempt == attempt) {
                pendingAttempt = null;
            }
        }
    }

    ShowAttempt capture(DialogFileEntities entities) {
        if (pendingAttempt == null || pendingAttempt.entities != entities) {
            return null;
        }
        ShowAttempt attempt = pendingAttempt;
        pendingAttempt = null;
        return attempt;
    }

    void onShown(ShowAttempt attempt, Dialog result) {
        if (attempt != null) {
            attempt.shown = result != null;
        }
    }

    void afterCleanup(DialogFileEntities remaining, ShowAttempt attempt) {
        if (attempt != null && attempt.parts.length > 0) {
            removeClearedFiles(remaining, attempt.parts);
        }
    }

    private void removeClearedFiles(DialogFileEntities remaining, DialogFileEntities[] parts) {
        HashSet<CacheModel.FileInfo> kept = new HashSet<>();
        for (int i = 0; i < remaining.entitiesByType.size(); i++) {
            kept.addAll(remaining.entitiesByType.valueAt(i).files);
        }
        for (DialogFileEntities part : parts) {
            if (part == null) {
                continue;
            }
            for (int i = part.entitiesByType.size() - 1; i >= 0; i--) {
                for (CacheModel.FileInfo file : new ArrayList<>(part.entitiesByType.valueAt(i).files)) {
                    if (!kept.contains(file)) {
                        part.removeFile(file);
                    }
                }
                if (part.entitiesByType.valueAt(i).files.isEmpty()) {
                    part.entitiesByType.removeAt(i);
                }
            }
            if (part.entitiesByType.size() == 0 && owner.cacheModel != null) {
                owner.cacheModel.remove(part);
            }
        }
        owner.updateRows();

    }

    static final class ShowAttempt {
        private final DialogFileEntities entities;
        private final DialogFileEntities[] parts;
        private boolean shown;

        private ShowAttempt(DialogFileEntities entities, DialogFileEntities[] parts) {
            this.entities = entities;
            this.parts = parts;
        }
    }
}
