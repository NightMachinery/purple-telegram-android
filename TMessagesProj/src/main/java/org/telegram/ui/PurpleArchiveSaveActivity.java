package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.purple.PurpleArchiveMediaAccess;
import org.telegram.messenger.purple.PurpleArchiveMediaExport;
import org.telegram.messenger.purple.PurpleArchiveMediaDestination;
import org.telegram.messenger.purple.PurpleArchiveMediaProvider;
import org.telegram.ui.ActionBar.Theme;

public final class PurpleArchiveSaveActivity extends Activity {
    private static final int PICK = 0x7534;
    private static final class State {
        final PurpleArchiveMediaAccess.Capability capability;
        WeakReference<PurpleArchiveSaveActivity> owner = new WeakReference<>(null);
        PurpleArchiveMediaExport.Ticket ticket;
        PurpleArchiveMediaExport.Result result;
        boolean launched, done;
        State(PurpleArchiveMediaAccess.Capability capability) { this.capability = capability; }
        void changed() { PurpleArchiveSaveActivity activity = owner.get(); if (activity != null && !activity.isFinishing()) activity.update(); }
        void completed(PurpleArchiveMediaExport.Result result) {
            this.result = result; done = true; ticket = null;
            if (result.cleanupFailed) Toast.makeText(ApplicationLoader.applicationContext, text(R.string.PurpleArchiveSaveCleanupFailed), Toast.LENGTH_LONG).show();
            changed();
        }
    }
    private State state;
    private TextView status;
    private Button cancel;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Object retained = getLastNonConfigurationInstance();
        state = retained instanceof State ? (State) retained : new State(PurpleArchiveMediaProvider.lookup(getIntent().getData()));
        state.owner = new WeakReference<>(this);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        status = new TextView(this); status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)); layout.addView(status);
        cancel = new Button(this); cancel.setText(text(R.string.Cancel)); cancel.setOnClickListener(view -> finish()); layout.addView(cancel); setContentView(layout);
        if (saved != null) state.launched |= saved.getBoolean("picker", false);
        if (state.capability != null) state.capability.onRevoked(state::changed);
        if (!state.launched && state.capability != null && state.capability.available()) {
            state.launched = true;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(state.capability.mime).putExtra(Intent.EXTRA_TITLE, state.capability.name);
            try { startActivityForResult(intent, PICK); }
            catch (RuntimeException e) { state.done = true; state.capability.close(); }
        }
        update();
    }
    private void update() {
        int message = state.result != null ? state.result.saved ? R.string.PurpleArchiveSaved : state.result.cleanupFailed ? R.string.PurpleArchiveSaveCleanupFailed : state.result.removed ? R.string.PurpleArchiveSaveFailed : R.string.PurpleArchiveMediaUnavailable
            : state.done || state.capability == null || !state.capability.available() ? R.string.PurpleArchiveMediaUnavailable
            : state.ticket != null ? R.string.PurpleArchiveSaving : R.string.PurpleArchiveChooseSave;
        status.setText(text(message)); cancel.setText(text(state.done ? R.string.Close : R.string.Cancel));
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK) return;
        Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) { state.done = true; if (state.capability != null) state.capability.close(); update(); return; }
        PurpleArchiveMediaDestination destination = PurpleArchiveMediaDestination.create(uri);
        if (destination == null) { state.done = true; if (state.capability != null) state.capability.close(); update(); return; }
        if (state.capability == null || !state.capability.available()) { cleanup(destination); return; }
        State pending = state;
        state.ticket = PurpleArchiveMediaExport.save(state.capability, destination, pending::completed);
        if (state.ticket == null) { state.capability.close(); cleanup(destination); }
        update();
    }
    private void cleanup(PurpleArchiveMediaDestination destination) {
        if (!PurpleArchiveMediaExport.cleanup(destination, state::completed)) {
            Toast.makeText(ApplicationLoader.applicationContext, text(R.string.PurpleArchiveSaveCleanupFailed), Toast.LENGTH_LONG).show();
            state.done = true; update();
        }
    }
    @Override public Object onRetainNonConfigurationInstance() { return state; }
    @Override protected void onSaveInstanceState(Bundle saved) { saved.putBoolean("picker", state.launched); super.onSaveInstanceState(saved); }
    @Override protected void onDestroy() {
        if (state.owner.get() == this) state.owner.clear();
        if (!isChangingConfigurations()) { if (state.ticket != null) state.ticket.close(); else if (state.capability != null) state.capability.close(); }
        super.onDestroy();
    }
    private static String text(int id) { return LocaleController.getString(id); }
}
