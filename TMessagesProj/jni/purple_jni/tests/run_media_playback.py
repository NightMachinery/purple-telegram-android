#!/usr/bin/env python3
"""Run the media playback cases (docs/media-design.md) on real AGP classes and pin their upstream hooks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import importlib.util
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--json', type=Path, required=True)
parser.add_argument('--library', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
repo = tests.parents[3]
java = repo / 'TMessagesProj/src/main/java/org/telegram'
receipt = json.loads(args.receipt.read_text())
assert receipt['task'] == ':TMessagesProj:compileStandaloneJavaWithJavac'
classes = Path(receipt['destination'])
compiler = Path(receipt['compiler'])
assert classes.is_dir() and args.json.is_file() and args.library.is_file()

# The Purple classes the cases load from the receipt, which must be the ones
# in this tree, and the files whose hooks are pinned below.
purple = [java / 'messenger/purple' / name for name in [
    'PurpleDoubleTap.java', 'PurpleMediaRestart.java', 'PurpleRoundPip.java', 'PurpleCore.java', 'PurpleVoiceQueue.java',
    'PurpleSeek.java', 'PurpleVoicePlayback.java']]
hooked = {name: java / name for name in [
    'ui/Cells/ChatMessageCell.java', 'ui/Components/PipRoundVideoView.java', 'ui/ChatActivity.java',
    'ui/PurpleChatHooks.java', 'messenger/MediaController.java', 'ui/Components/FragmentContextView.java',
    'messenger/MusicPlayerService.java', 'ui/PurpleRecordingStrip.java', 'ui/PurplePlayerStripLayout.java']}
hashes = {Path(item['path']): item['sha256'] for item in receipt['sources']}
for path in purple + list(hooked.values()):
    assert hashes.get(path) == hashlib.sha256(path.read_bytes()).hexdigest(), str(path) + ' receipt mismatch'

# Each hook is the first statement of its function, or the one line named.
PINS = [
    ('ui/Cells/ChatMessageCell.java', '    private void didClickedImage() {\n',
     '        if (org.telegram.messenger.purple.PurpleMediaRestart.onImageTap(currentMessageObject, buttonState)) return;\n'),
    ('ui/Cells/ChatMessageCell.java', '    private void didPressButton(boolean animated, boolean video) {\n',
     '        if (org.telegram.messenger.purple.PurpleMediaRestart.onButton(currentMessageObject, buttonState)) return;\n'),
    ('ui/Components/PipRoundVideoView.java',
     '                        MessageObject messageObject = MediaController.getInstance().getPlayingMessageObject();\n',
     '                        if (org.telegram.messenger.purple.PurpleRoundPip.onTap(messageObject, event.getEventTime() - event.getDownTime())) messageObject = null;\n'),
    ('ui/ChatActivity.java',
     '                        if (messageObject.isRoundVideo() || messageObject.isVideo()) {\n',
     '                            if (checkTextureViewPosition || PipRoundVideoView.getInstance() != null || purple.roundLeavesToPip(messageObject, needScroll)) {\n'),
    ('ui/ChatActivity.java',
     '                if (messageObject.getDialogId() == mergeDialogId && startMessageObject.getDialogId() != mergeDialogId) {\n'
     '                    continue;\n'
     '                }\n',
     '                if (purple.skipsInVoicePlaylist(startMessageObject, messageObject)) continue;\n'),
    ('messenger/MediaController.java',
     '                        messageObject = arr.get(a);\n',
     '                        if (org.telegram.messenger.purple.PurpleVoicePlayback.skipsAppended(messageObject)) continue;\n'),
    # F9: the strip's per-instance helper, handed each new style before the
    # style branches run, and the media session's rewind and fast-forward.
    ('ui/Components/FragmentContextView.java', '    private final Theme.ResourcesProvider resourcesProvider;\n',
     '    private final org.telegram.ui.PurpleRecordingStrip purple = new org.telegram.ui.PurpleRecordingStrip(this, () -> !isLocation, () -> checkPlayer(false));\n'),
    ('ui/Components/FragmentContextView.java', '        currentStyle = style;\n',
     '        purple.updateStyle(style, titleTextView, resourcesProvider);\n'),
    ('messenger/MusicPlayerService.java', '                public void onCustomAction(String action, android.os.Bundle extras) {\n',
     '                    if (PurpleSeek.onCustomAction(action)) return;\n'),
    ('messenger/MusicPlayerService.java', '                public void onRewind() {\n',
     '                    PurpleSeek.step(-1);\n'),
    ('messenger/MusicPlayerService.java', '                public void onFastForward() {\n',
     '                    PurpleSeek.step(1);\n'),
    ('messenger/MusicPlayerService.java',
     '\n                long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_SET_REPEAT_MODE | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;\n',
     '                actions |= PurpleSeek.sessionActions(playbackState);\n'),
    ('messenger/MusicPlayerService.java',
     '\n            long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_SET_REPEAT_MODE | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;\n',
     '            actions |= PurpleSeek.sessionActions(playbackState);\n'),
]
PROGRAMS = [('restart', 'MEDIA_RESTART_CHECKS'), ('pip', 'MEDIA_PIP_CHECKS'), ('core', 'MEDIA_QUEUE_CORE_CHECKS'), ('queue', 'MEDIA_QUEUE_CHECKS'),
            ('seek', 'MEDIA_SEEK_CHECKS'), ('seekcore', 'MEDIA_SEEK_CORE_CHECKS'), ('continuationcore', 'MEDIA_CONTINUATION_CORE_CHECKS')]

assert not args.output.exists(), 'output must be an unused directory'
args.output.mkdir(parents=True)
pinned = 0
for name, signature, hook in PINS:
    text = hooked[name].read_text(encoding='utf-8')
    assert text.count(signature) == 1, name + ': ' + signature.strip()
    assert text.count('\n' + hook) == 1, name + ': the hook appears once'
    assert text.startswith(hook, text.index(signature) + len(signature)), name + ': ' + hook.strip() + ' opens ' + signature.strip()
    pinned += 1
helper = hooked['ui/PurpleChatHooks.java'].read_text(encoding='utf-8').split('boolean roundLeavesToPip(MessageObject message, boolean needScroll) {', 1)[1].split('}', 1)[0]
assert ' '.join(helper.split()) == ('return org.telegram.messenger.purple.PurpleRoundPip.leavesToPip( '
                                    'message.isRoundVideo(), needScroll, needScroll && queueOnScreen(message));'), helper
pinned += 1
helper = hooked['ui/PurpleChatHooks.java'].read_text(encoding='utf-8').split('boolean skipsInVoicePlaylist(MessageObject start, MessageObject candidate) {', 1)[1].split('}', 1)[0]
assert helper.strip() == 'return org.telegram.messenger.purple.PurpleVoiceQueue.skipsInPlaylist(start, candidate);'
pinned += 1
# The strip routes style and dock show/hide through its one helper;
# the media session calls PurpleSeek five times.
text = hooked['ui/Components/FragmentContextView.java'].read_text(encoding='utf-8')
assert text.count('org.telegram.ui.PurpleRecordingStrip') == 2 and text.count('purple.') == 5, 'FragmentContextView: one helper, style, measure, height and dock calls'
pinned += 1
text = hooked['messenger/MusicPlayerService.java'].read_text(encoding='utf-8')
assert text.count('PurpleSeek.') == 5 and text.count('import org.telegram.messenger.purple.PurpleSeek;\n') == 1, 'MusicPlayerService: five PurpleSeek calls'
assert 'purple.Purple' not in text.replace('import org.telegram.messenger.purple.PurpleSeek;', ''), 'MusicPlayerService: no other Purple class'
pinned += 1
helper = hooked['ui/PurpleRecordingStrip.java'].read_text(encoding='utf-8')
assert helper.count('PurpleSeek.step(direction);') == 1, 'the strip buttons seek through PurpleSeek'
assert 'setAlpha(' not in helper, 'seek labels keep the full primary color without view alpha'
pinned += 1
(args.output / 'pins.log').write_text('MEDIA_HOOK_PINS=%d\n' % pinned)

destination = args.output / 'classes'
destination.mkdir()
empty = args.output / 'empty-sourcepath'
empty.mkdir()
case = tests / 'media_playback/MediaPlaybackCases.java'
layout_case = tests / 'media_playback/PlayerStripLayoutCases.java'
(args.output / 'inputs.json').write_text(json.dumps([
    {'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
    for p in [args.receipt, args.library, case, layout_case, *purple, *hooked.values()]], indent=2) + '\n')
commands = {'javac': [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty),
                      '-cp', str(classes), '-d', str(destination), str(case), str(layout_case)]}
# The core and seekcore programs load the host libpurplecore through the real PurpleCore,
# which reads its load result with org.json.
for program, _ in PROGRAMS:
    commands[program] = [str(compiler.with_name('java')), '-Xmx256m', '-Xcheck:jni', '-Djava.library.path=' + str(args.library.parent),
                         '-cp', os.pathsep.join([str(destination), str(classes), str(args.json)]),
                         'org.telegram.messenger.purple.MediaPlaybackCases', program]
commands['layout'] = [str(compiler.with_name('java')), '-cp', os.pathsep.join([str(destination), str(classes)]), 'org.telegram.ui.PlayerStripLayoutCases']
for name, command in commands.items():
    (args.output / (name + '.argv.json')).write_text(json.dumps(command, indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log:
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
    output = (args.output / (name + '.log')).read_text()
    print(name, result.returncode, output[-2000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
for program, key in PROGRAMS:
    assert key + '=' in (args.output / (program + '.log')).read_text(), program + ' printed no ' + key

assert 'MEDIA_STRIP_LAYOUT_CHECKS=' in (args.output / 'layout.log').read_text()

ports_file = tests / 'media_playback/strip_queue_cases.py'
spec = importlib.util.spec_from_file_location('media_ports', ports_file)
ports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ports)
ports.run(repo, args.output, compiler)

controller = hooked['messenger/MediaController.java'].read_text()
assert controller.count('PurpleVoicePlayback.advance(') == 2
assert 'if (org.telegram.messenger.purple.PurpleVoicePlayback.restorePaused()) pauseMessage(messageObject, false);' in controller
assert 'PurpleVoicePlayback.keepsMusic(savedMusicPlaylistState != null, messageObject)' in controller
assert 'if (purplePlaylist != null) return purplePlaylist;' in hooked['ui/ChatActivity.java'].read_text()

settings = (java / 'ui/PurpleSettingsActivity.java').read_text()
rows = re.findall(r'private static final int (ROW_\w+) = (0x[0-9a-fA-F]+|[0-9]+);', settings)
row_values = [int(value, 0) for name, value in rows]
assert len(row_values) == len(set(row_values)), 'settings row ids collide'
settings_checks = len(rows)
for route in ['case ROW_AFTER_VOICE:', 'case ROW_RESUME_MUSIC:',
              '"playback", "after_voice"', '"playback", "resume_music_after_voice_p"',
              'UItem.asButton(ROW_AFTER_VOICE', 'UItem.asCheck(ROW_RESUME_MUSIC']:
    assert route in settings, route
    settings_checks += 1
(args.output / 'settings.log').write_text('MEDIA_PLAYBACK_SETTINGS_CHECKS=%d\n' % settings_checks)
print('MEDIA_PLAYBACK_SETTINGS_CHECKS=%d' % settings_checks)

assert 'afterVoiceSummary(org.telegram.messenger.purple.PurpleVoicePlayback.mode())' in settings
assert 'final CharSequence[] labels = {afterVoiceLabel(0), afterVoiceLabel(1), afterVoiceLabel(2)};' in settings
