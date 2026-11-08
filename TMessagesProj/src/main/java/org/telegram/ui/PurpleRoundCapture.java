package org.telegram.ui;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import org.telegram.messenger.*;
import org.telegram.messenger.purple.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.*;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;

public final class PurpleRoundCapture implements InstantCameraView.Delegate, PurpleRecordingDock.Host {
    private static PurpleRoundCapture active;
    private static int captures;
    private final int capture = ++captures;
    private TextureView lastTexture;
    private SurfaceTexture lastSurface;
    private int textureChanges, surfaceChanges;
    private final PurpleRoundCaptureLease lease;
    private final long[] target;
    private LaunchActivity activity;
    private FrameLayout root;
    private InstantCameraViewBase camera;
    private WeakReference<ChatActivity> binding = new WeakReference<>(null);
    private long bindingToken;
    private Object[] preview;
    private boolean frameClock, suspended, ready, released;
    private MediaController.PhotoEntry output;
    private VideoEditedInfo outputInfo;
    private int attached, detached, surfaces, destroyedSurfaces;
    private long frames, duration;

    private PurpleRoundCapture(ChatActivity chat, LaunchActivity activity) {
        this.activity = activity;
        int account = chat.getCurrentAccount();
        long user = UserConfig.getInstance(account).getClientUserId();
        lease = new PurpleRoundCaptureLease(account, user, chat.getDialogId(), chat.isTopic ? chat.getTopicId() : 0,
                chat.getChatMode(), ConnectionsManager.generateClassGuid());
        target = new long[]{account, lease.dialog, lease.topic, chat.getReplyMessage() == null ? 0 : chat.getReplyMessage().getId(), user,
                chat.chatActivityEnterView != null && chat.chatActivityEnterView.voiceOnce ? PurpleRecordingSessions.FLAG_ONCE : 0};
    }

    public static boolean refuseStart() {
        if (active == null) return false;
        if (!active.lease.finishing()) active.confirmDiscard();
        return true;
    }

    public static boolean command(ChatActivity chat, int state, boolean notify, int date, int repeat, int ttl, long effect, long stars) {
        if (state == 0) {
            if (active != null) return true;
            if (!(chat.getParentActivity() instanceof LaunchActivity)) return false;
            if (PurpleRecordingDock.refuseStart() || !org.telegram.messenger.camera.CameraView.isCameraAllowed()) return true;
            if (chat.getChatMode() != ChatActivity.MODE_DEFAULT || chat.isSecretChat() || chat.isThreadChat() && !chat.isTopic) {
                Toast.makeText(chat.getContext(), LocaleController.getString(R.string.PurpleRecordingSendUnavailable), Toast.LENGTH_LONG).show();
                if (chat.chatActivityEnterView != null) chat.chatActivityEnterView.purpleReleaseRecordUi();
                return true;
            }
            PurpleRoundCapture session = new PurpleRoundCapture(chat, (LaunchActivity) chat.getParentActivity());
            active = session;
            session.start(chat);
            return true;
        }
        PurpleRoundCapture session = forChat(chat);
        if (session == null) return false;
        if (state == 2 || state == 5) session.confirmDiscard();
        else if (state == 3) session.pauseForPreview();
        else if (state == 1 || state == 4) session.finish();
        return true;
    }

    private void start(ChatActivity chat) {
        root = new FrameLayout(activity) {
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                return !suspended && !lease.finishing() && super.dispatchTouchEvent(event);
            }
        };
        root.setClipChildren(false);
        activity.getMainContainerFrameLayout().addView(root,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        PurpleRecordingRegistry.watchSession(lease.guid, this::target);
        PurpleRecordingDock.watch(lease.guid, this);
        bind(chat);
        camera = InstantCameraViewBase.create(activity, this, null, true);
        camera.setClipToPadding(false);
        camera.setAnimationCallback((open, fromPaused) -> {
            boolean expanded = PurpleRoundBubble.animation(camera, open, fromPaused);
            if (!open && !lease.finishing()) AndroidUtilities.runOnUIThread(this::closeCapture);
            if (open && !expanded) releaseComposer();
        });
        camera.setTrimCallback((start, end) -> {
            ChatActivity bound = binding.get();
            if (bound != null && bound.chatActivityEnterView != null) bound.chatActivityEnterView.setVideoTimelineTrim(start, end);
        });
        camera.setRecordingUiFrameCallback(new InstantCameraViewBase.RecordingUiFrameCallback() {
            public void onActiveChanged(boolean value) {
                frameClock = value;
                ChatActivity bound = binding.get();
                if (bound != null && bound.chatActivityEnterView != null) bound.chatActivityEnterView.setRoundVideoUiFrameClockActive(value);
            }
            public void onFrame(long value) {
                frames++; duration = value;
                ChatActivity bound = binding.get();
                if (bound != null && bound.chatActivityEnterView != null) bound.chatActivityEnterView.onRoundVideoUiFrame(value);
            }
        });
        BlurredBackgroundSourceColor source = new BlurredBackgroundSourceColor();
        source.setColor(0x44000000);
        camera.setButtonsBackground(new BlurredBackgroundDrawableViewFactory(source), BlurredBackgroundProviderImpl.photoViewer(null));
        PurpleRoundBubble.attach(camera, small -> {
            PurpleRecordingDock.floatRound(lease.account, lease.guid, small);
            if (!small) restoreComposer();
            snapshot(small ? "small" : "expanded");
        });
        camera.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { attached++; snapshot("attached"); }
            public void onViewDetachedFromWindow(View view) { detached++; snapshot("detached"); }
        });
        root.addView(camera, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        TextView save = button(LocaleController.getString(R.string.PurpleRoundCaptureSave), this::finish);
        root.addView(save, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.RIGHT | Gravity.BOTTOM, 12, 0, 12, 96));
        TextView discard = button(LocaleController.getString(R.string.Cancel), this::confirmDiscard);
        root.addView(discard, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.LEFT | Gravity.BOTTOM, 12, 0, 12, 96));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateInsets());
        updateInsets();
        snapshot("created");
        camera.showCamera(false);
    }

    private TextView button(String text, Runnable action) {
        TextView view = new TextView(activity);
        view.setText(text); view.setTextColor(0xffffffff); view.setTextSize(14);
        view.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        view.setGravity(Gravity.CENTER); view.setBackgroundColor(0x99000000);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void updateInsets() {
        if (camera == null || root == null) return;
        android.graphics.Rect visible = new android.graphics.Rect();
        root.getWindowVisibleDisplayFrame(visible);
        int[] location = new int[2]; root.getLocationOnScreen(location);
        int inset = Math.max(0, location[1] + root.getHeight() - visible.bottom);
        camera.setInternalPadding(inset + AndroidUtilities.dp(96));
    }

    private static PurpleRoundCapture forChat(ChatActivity chat) {
        return active != null && active.matches(chat) ? active : null;
    }

    private boolean matches(ChatActivity chat) {
        return lease.matches(chat.getCurrentAccount(), UserConfig.getInstance(chat.getCurrentAccount()).getClientUserId(),
                chat.getDialogId(), chat.isTopic ? chat.getTopicId() : 0, chat.getChatMode());
    }

    public static void attach(ChatActivity chat) {
        PurpleRoundCapture session = forChat(chat);
        if (session != null && !session.suspended && !session.lease.finishing()) session.bind(chat);
    }

    private void bind(ChatActivity chat) {
        if (binding.get() == chat) return;
        releaseComposer();
        bindingToken = lease.attach(chat.getCurrentAccount(), UserConfig.getInstance(chat.getCurrentAccount()).getClientUserId(),
                chat.getDialogId(), chat.isTopic ? chat.getTopicId() : 0, chat.getChatMode());
        if (bindingToken == 0) return;
        binding = new WeakReference<>(chat);
        if (chat.chatActivityEnterView != null) chat.chatActivityEnterView.purpleRoundRecordingGuid(lease.guid);
        snapshot("bind");
    }

    public static boolean detach(ChatActivity chat) {
        PurpleRoundCapture session = active;
        if (session == null || session.binding.get() != chat) return false;
        if (session.ready && !session.lease.finishing()) {
            PurpleRoundBubble.shrink(session.camera);
            if (!PurpleRecordingSettings.read().keepAcrossChats) session.finish();
        }
        session.releaseComposer();
        session.lease.detach(session.bindingToken);
        session.binding.clear(); session.bindingToken = 0;
        session.snapshot("unbind");
        return true;
    }

    public static boolean leave(ChatActivity chat, boolean invoked) {
        PurpleRoundCapture session = forChat(chat);
        if (session == null) return false;
        if (invoked && session.camera != null) {
            PurpleRoundBubble.shrink(session.camera);
            session.releaseComposer();
        }
        return true;
    }

    private void releaseComposer() {
        ChatActivity chat = binding.get();
        if (chat != null && chat.chatActivityEnterView != null) {
            chat.chatActivityEnterView.setRoundVideoUiFrameClockActive(false);
            chat.chatActivityEnterView.purpleReleaseRecordUi();
            chat.chatActivityEnterView.purpleRoundRecordingGuid(chat.getClassGuid());
        }
    }

    private void restoreComposer() {
        ChatActivity chat = binding.get();
        if (chat != null && chat.chatActivityEnterView != null && !chat.isPaused()) {
            chat.chatActivityEnterView.purpleRoundRecordingGuid(lease.guid);
            chat.chatActivityEnterView.purpleRestoreRoundUi(preview);
            chat.chatActivityEnterView.setRoundVideoUiFrameClockActive(frameClock);
        }
    }

    public static boolean togglePause(ChatActivity chat) {
        PurpleRoundCapture session = forChat(chat);
        if (session == null) return false;
        session.roundPause(); return true;
    }
    public static boolean manages(ChatActivity chat) { return forChat(chat) != null; }
    public static boolean paused(ChatActivity chat) {
        PurpleRoundCapture session = forChat(chat);
        return session != null && session.camera != null && session.camera.isPaused();
    }
    public static boolean previewState(ChatActivity chat, int state, float progress) {
        PurpleRoundCapture session = forChat(chat);
        if (session == null) return false;
        if (session.camera != null) session.camera.changeVideoPreviewState(state, progress);
        return true;
    }

    public static void suspend() {
        if (active != null) {
            active.suspended = true;
            active.releaseComposer();
            active.finish();
            if (active != null && active.root != null) active.root.setAlpha(0);
        }
    }

    public static void activityPaused(Activity activity) {
        if (active != null && active.activity == activity) suspend();
    }

    public static void activityDestroyed(Activity activity) {
        if (active == null || active.activity != activity) return;
        PurpleRoundCapture session = active;
        suspend();
        session.binding.clear();
        AndroidUtilities.runOnUIThread(() -> { if (!session.released) session.closeCapture(); }, 30000);
    }

    public static void traceSurface(TextureView view, SurfaceTexture surface, boolean destroyed) {
        PurpleRoundCapture session = active;
        if (session == null || session.camera == null || session.camera.getTextureView() != view) return;
        if (destroyed) session.destroyedSurfaces++; else session.surfaces++;
        session.snapshot(destroyed ? "surface-destroyed" : "surface-available");
    }

    private void snapshot(String event) {
        TextureView texture = camera == null ? null : camera.getTextureView();
        SurfaceTexture surface = texture == null ? null : texture.getSurfaceTexture();
        if (lastTexture != null && texture != null && lastTexture != texture) textureChanges++;
        if (lastSurface != null && surface != null && lastSurface != surface) surfaceChanges++;
        if (lastSurface == null && surface != null && surfaces == 0) surfaces = 1;
        if (texture != null) lastTexture = texture;
        if (surface != null) lastSurface = surface;
        PurpleDiagLog.event("round_capture", event.replace('-', '_'), PurpleDiagLog.count("capture", capture),
                PurpleDiagLog.flag("adapter_v2", camera instanceof InstantCameraView2),
                PurpleDiagLog.count("window_attaches", attached), PurpleDiagLog.count("window_detaches", detached),
                PurpleDiagLog.count("surface_creates", surfaces), PurpleDiagLog.count("surface_destroys", destroyedSurfaces),
                PurpleDiagLog.count("texture_changes", textureChanges), PurpleDiagLog.count("surface_changes", surfaceChanges),
                PurpleDiagLog.count("frames", (int) Math.min(99999, frames)),
                PurpleDiagLog.count("duration_ms", (int) Math.min(99999, duration)));
        FileLog.d("PurpleRoundCapture " + event + " capture=" + lease.guid
                + " adapter=" + (camera == null ? "none" : camera.getClass().getSimpleName())
                + " texture=" + System.identityHashCode(texture)
                + " surface=" + System.identityHashCode(texture == null ? null : texture.getSurfaceTexture())
                + " windows=" + attached + "/" + detached + " surfaces=" + surfaces + "/" + destroyedSurfaces
                + " frames=" + frames + " duration=" + duration);
    }

    private void pauseForPreview() {
        if (camera != null && ready && !lease.finishing() && !camera.isPaused()) camera.togglePause();
    }

    public static boolean save(int account, int guid) {
        if (active == null || active.lease.account != account || active.lease.guid != guid) return false;
        active.finish();
        return true;
    }

    private void finish() {
        if (camera == null || released || lease.finishing()) return;
        snapshot("save-request");
        camera.send(camera.isPaused() ? 4 : 1, true, 0, 0, (target[5] & PurpleRecordingSessions.FLAG_ONCE) != 0 ? 0x7fffffff : 0, 0, 0);
    }

    private void confirmDiscard() {
        if (released || lease.finishing()) return;
        new AlertDialog.Builder(activity).setTitle(LocaleController.getString(R.string.DiscardVideoMessageTitle))
                .setMessage(LocaleController.getString(R.string.DiscardVideoMessageDescription))
                .setPositiveButton(LocaleController.getString(R.string.DiscardVoiceMessageAction), (d, w) -> roundDiscard())
                .setNegativeButton(LocaleController.getString(R.string.Continue), null).show();
    }

    public long[] target() { return target.clone(); }
    public boolean scheduled() { return false; }
    public void roundStarted() {
        ready = true; snapshot("recording");
        if (suspended) finish();
        else if (binding.get() == null) PurpleRoundBubble.shrink(camera);
    }
    public void roundFinishing() { lease.finish(); snapshot("finalizing"); }
    public boolean roundVisible() { return !released && !suspended && !ApplicationLoader.mainInterfacePaused; }
    public void roundPause() {
        if (camera != null && ready && !lease.finishing() && !suspended) camera.togglePause();
    }
    public void roundPreview() { if (camera != null) { PurpleRoundBubble.expand(camera); pauseForPreview(); } }
    public void roundPreviewReady(Object[] args) {
        preview = args.clone(); snapshot("paused");
        if (!PurpleRoundBubble.small(camera)) restoreComposer();
    }
    public void roundSend(boolean notify, int date, long stars) { finish(); }
    public void roundDiscard() {
        if (camera == null || released || lease.finishing()) return;
        lease.end(); camera.cancel(false);
        AndroidUtilities.runOnUIThread(this::closeCapture);
    }
    public void roundAbandon() { AndroidUtilities.runOnUIThread(this::closeCapture); }

    @Override public View getFragmentView() { return root; }
    @Override public Activity getParentActivity() { return activity; }
    @Override public int getClassGuid() { return lease.guid; }
    @Override public int getCurrentAccount() { return lease.account; }
    @Override public long getDialogId() { return lease.dialog; }

    @Override public void sendMedia(MediaController.PhotoEntry entry, VideoEditedInfo info, boolean notify, int date, int repeat, boolean unused, long stars) {
        if (released || lease.ended()) return;
        outputInfo = info;
        if (output != null) return;
        output = entry;
        retain(0);
    }

    private void retain(int attempt) {
        if (released || lease.ended()) return;
        if (outputInfo.notReadyYet && attempt < 120) {
            AndroidUtilities.runOnUIThread(() -> retain(attempt + 1), 250); return;
        }
        if (outputInfo.notReadyYet) {
            PurpleRecordingRegistry.retainFailedRound(lease.account, lease.guid, output.path, target, id -> closeCapture());
        } else {
            PurpleRecordingRegistry.retainRound(lease.account, lease.guid, output.path, target, id -> {
                if (id == null && attempt < 120) AndroidUtilities.runOnUIThread(() -> retain(attempt + 1), 250);
                else {
                    snapshot(id == null ? "retain-failed" : "retained");
                    if (id != null) Toast.makeText(ApplicationLoader.applicationContext,
                            LocaleController.getString(R.string.PurpleRoundCaptureSaved), Toast.LENGTH_LONG).show();
                    closeCapture();
                }
            });
        }
    }

    @Override public void release() { releaseComposer(); }

    private void closeCapture() {
        if (released) return;
        released = true; lease.end(); snapshot("released");
        releaseComposer(); binding.clear();
        PurpleRecordingDock.roundEnded(lease.account, lease.guid);
        PurpleRecordingDock.unwatch(lease.guid);
        PurpleRecordingRegistry.unwatchSession(lease.guid);
        if (camera != null) { camera.hideCamera(true); camera.setVisibility(View.INVISIBLE); }
        if (root != null && root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        MediaController.getInstance().requestRecordAudioFocus(false);
        camera = null; root = null; activity = null;
        if (active == this) active = null;
    }
}
