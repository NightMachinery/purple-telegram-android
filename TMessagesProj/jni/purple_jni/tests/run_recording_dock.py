#!/usr/bin/env python3
"""Exercise dock ownership, pause/send transitions and bubble bounds without Android."""
import argparse, os, pathlib, shutil, subprocess
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[4]
java = root / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
args.output.mkdir(parents=True, exist_ok=False)
javac = pathlib.Path(os.environ['JAVA_HOME']) / 'bin/javac' if os.environ.get('JAVA_HOME') else pathlib.Path(shutil.which('javac'))
sources = [java / 'PurpleRecordingDockState.java', java / 'PurpleRoundBubbleGeometry.java', java / 'PurpleRecordingTarget.java', java / 'PurpleRecordingSendIdentity.java', java / 'PurpleRecordingPreviewHandoff.java', pathlib.Path(__file__).parent / 'recording/DockCases.java']
subprocess.run([str(javac), '-d', str(args.output), *map(str, sources)], check=True)
subprocess.run([str(javac.with_name('java')), '-cp', str(args.output), 'DockCases'], check=True)

ui = root / 'TMessagesProj/src/main/java/org/telegram'
pins = [
    ('messenger/purple/PurpleRecordingService.java', 'intent.setData(android.net.Uri.parse(PurpleRecordingDock.state.actionIdentity(action)));'),
    ('messenger/purple/PurpleRecordingService.java', 'generation = state.generation;'),
    ('messenger/purple/PurpleRecordingDock.java', 'if (DialogObject.isEncryptedDialog(state.dialog) || (!state.round && !state.canReplaceTarget(false)))'),
    ('messenger/MediaController.java', 'PurpleRecordingDock.keepsOnChatPause(chatActivity, fromChat)'),
    ('messenger/MediaController.java', 'PurpleRecordingDock.preventsDraftRestore()'),
    ('messenger/MediaController.java', 'PurpleRecordingDock.adoptNativePreview('),
    ('messenger/MediaController.java', 'PurpleRecordingDock.refuseStart()'),
    ('messenger/SendMessagesHelper.java', 'PurpleRecordingSendAs.reject('),
    ('messenger/SendMessagesHelper.java', 'PurpleRecordingSendAs.apply('),
    ('ui/Components/ChatActivityEnterView.java', 'PurpleRecordingDock.refuseStart()'),
    ('ui/Components/ChatActivityEnterView.java', 'PurpleRecordingDock.ignoreEvent(id, account, recordingGuid)'),
    ('ui/Components/ChatActivityEnterView.java', 'PurpleRecordingPanel.install('),
    ('ui/Components/ChatActivityEnterView.java', 'PurpleRecordingPanel.refuseCamera('),
    ('ui/ChatActivity.java', 'purple.dockRecording(invoked)'),
    ('ui/ChatActivity.java', 'purple.attachRoundCamera('),
    ('ui/ChatActivity.java', 'purple.sendRoundMedia('),
    ('messenger/MediaController.java', 'public void purpleRetargetRecording('),
    ('ui/Components/ChatActivityEnterView.java', 'public void purpleRestoreRoundUi('),
    ('ui/ChatActivity.java', 'purple.roundRecordingTouch(event)'),
    ('ui/Components/FragmentContextView.java', '&& !purple.docked()'),
    ('ui/Components/FragmentContextView.java', 'if (purple.showRecording()) return;'),
    ('ui/Components/InstantCameraViewBase.java', 'PurpleRoundBubble.dispatch('),
    ('ui/Components/InstantCameraView.java', 'public class InstantCameraView extends InstantCameraViewBase'),
    ('ui/Components/InstantCameraView2.java', 'class InstantCameraView2 extends InstantCameraViewBase'),
    ('messenger/purple/PurpleRecordingRegistry.java', 'target(guid, true)'),
]
for relative, pin in pins:
    assert (ui / relative).read_text().count(pin) == 1, relative + ': ' + pin
enter = (ui / 'ui/Components/ChatActivityEnterView.java').read_text()
release = enter.split('public void purpleReleaseRecordUi() {', 1)[1].split('}', 1)[0]
assert 'recordingAudioVideo = false;' in release and 'updateRecordInterface(RECORD_STATE_CANCEL, true);' in release
assert 'stopRecording' not in release
manifest = (root / 'TMessagesProj/src/main/AndroidManifest.xml').read_text()
assert 'android:name="org.telegram.messenger.purple.PurpleRecordingService" android:exported="false" android:foregroundServiceType="microphone"' in manifest
print('RECORDING_DOCK_PINS=' + str(len(pins) + 2))

import runpy
runpy.run_path(str(pathlib.Path(__file__).parent / 'recording/integration.py'))['run'](root, args.output)

runpy.run_path(str(pathlib.Path(__file__).parent / 'recording/bubble_routing.py'))['run'](root, args.output / 'bubble-routing')

runpy.run_path(str(pathlib.Path(__file__).parent / 'recording/lifetime.py'))['run'](root, args.output / 'lifetime')

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/polish.py"))["run"](root, args.output / "polish")

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/long_limits.py"))["run"](root, args.output / "long-limits")

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/long_outputs.py"))["run"](root, args.output / "long-outputs")

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/long_choices.py"))["run"](root, args.output / "long-choices")

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/long_originals.py"))["run"](root, args.output / "long-originals")

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/native_cap.py"))["run"](root, args.output / "native-cap")

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/native_eos.py"))["run"](root, args.output / "native-eos")

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/native_finalization.py"))["run"](root, args.output / "native-finalization")

runpy.run_path(str(pathlib.Path(__file__).parent / "recording/native_long_audio.py"))["run"](root, args.output / "native-long-audio")
