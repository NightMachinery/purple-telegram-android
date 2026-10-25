package org.telegram.messenger.purple;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.LaunchActivity;

public final class PurpleDirectShareActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            ApplicationLoader.postInitApplication();
            Intent routed = PurpleDirectShare.routeShare(getIntent());
            if (routed != null) startActivity(routed.setClass(this, LaunchActivity.class));
        } catch (Exception e) { FileLog.e(e); }
        finally { finish(); }
    }
}
