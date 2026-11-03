#!/usr/bin/env python3
"""Run the media recording cases (docs/media-recording-design.md, F10) on real AGP classes and the host core library,
check the AAC measure shim and the round recovery bridge against streams encoded at test time, and pin the F10
upstream hooks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--json', type=Path, required=True)
parser.add_argument('--library', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
repo = tests.parents[3]
jni = repo / 'TMessagesProj/jni/purple_jni'
core = repo / 'TMessagesProj/jni/purple'
java = repo / 'TMessagesProj/src/main/java/org/telegram'
receipt = json.loads(args.receipt.read_text())
assert receipt['task'] == ':TMessagesProj:compileStandaloneJavaWithJavac'
classes = Path(receipt['destination'])
compiler = Path(receipt['compiler'])
assert classes.is_dir() and args.json.is_file() and args.library.is_file()

# The Purple and upstream classes the cases load from the receipt, which must
# be the ones in this tree, and the files whose hooks are pinned below.
purple = [java / 'messenger/purple' / name for name in [
    'PurpleCore.java', 'PurpleRecordingSettings.java', 'PurpleRecordingCore.java', 'PurpleRecordingEntry.java',
    'PurpleRecordingManifest.java', 'PurpleRecordingFiles.java', 'PurpleRecordingStore.java',
    'PurpleRoundJournal.java', 'PurpleRoundRecovery.java']]
upstream = [java / 'messenger/video' / name for name in ['Mp4Movie.java', 'MP4Builder.java', 'Track.java']]
hooked = {name: java / name for name in [
    'messenger/video/Mp4Movie.java', 'ui/Components/InstantCameraView.java',
    'utils/recorder/round/RoundVideoMp4Writer.java']}
hashes = {Path(item['path']): item['sha256'] for item in receipt['sources']}
for path in purple + upstream + list(hooked.values()):
    assert hashes.get(path) == hashlib.sha256(path.read_bytes()).hexdigest(), str(path) + ' receipt mismatch'

# F10 round: each hook is the line right after the one named, and appears once.
PINS = [
    ('messenger/video/Mp4Movie.java', '    public void addSample(int trackIndex, long offset, MediaCodec.BufferInfo bufferInfo) {\n',
     '        org.telegram.messenger.purple.PurpleRoundJournal.onSample(this, trackIndex, offset, bufferInfo);\n'),
    ('messenger/video/Mp4Movie.java', '    public int addTrack(MediaFormat mediaFormat, boolean isAudio) {\n',
     '        org.telegram.messenger.purple.PurpleRoundJournal.onTrack(this, tracks.size(), mediaFormat, isAudio);\n'),
    ('ui/Components/InstantCameraView.java', '                    movie.setCacheFile(fileToWrite);\n',
     '                    org.telegram.messenger.purple.PurpleRoundJournal.register(movie, isSecretChat, SharedConfig.deviceIsHigh());\n'),
    ('utils/recorder/round/RoundVideoMp4Writer.java', '        movie.setCacheFile(file);\n',
     '        org.telegram.messenger.purple.PurpleRoundJournal.register(movie, true, true);\n'),
]

work = args.output / 'work'
adts = args.output / 'mono48k.adts'
host_library = args.output / ('libpurpleround.dylib' if platform.system() == 'Darwin' else 'libpurpleround.so')
# Each Java program: its class, its completion record and its arguments.
PROGRAMS = [('settings', 'RecordingCases', 'RECORDING_SETTINGS_CHECKS', []),
            ('bin', 'RecordingCases', 'RECORDING_BIN_CHECKS',
             [str(args.output / 'voice.ogg'), str(args.output / 'finished.mp4'), str(work / 'bin')]),
            ('journal', 'RoundCases', 'ROUND_JOURNAL_CHECKS', [str(work / 'journal')]),
            ('recovery', 'RoundCases', 'ROUND_RECOVERY_CHECKS', [str(host_library), str(adts), str(work / 'recovery')])]

assert not args.output.exists(), 'output must be an unused directory'
args.output.mkdir(parents=True)
work.mkdir()
pinned = 0
for name, signature, hook in PINS:
    text = hooked[name].read_text(encoding='utf-8')
    assert text.count(signature) == 1, name + ': ' + signature.strip()
    assert text.count('\n' + hook) == 1, name + ': the hook appears once'
    assert text.startswith(hook, text.index(signature) + len(signature)), name + ': ' + hook.strip() + ' follows ' + signature.strip()
    pinned += 1
# No other Purple call in the hooked files' F10 lines: Mp4Movie calls the
# journal twice, each recorder registers once.
text = hooked['messenger/video/Mp4Movie.java'].read_text(encoding='utf-8')
assert text.count('org.telegram.messenger.purple.') == 2, 'Mp4Movie: two journal calls'
pinned += 1
for name in ['ui/Components/InstantCameraView.java', 'utils/recorder/round/RoundVideoMp4Writer.java']:
    assert hooked[name].read_text(encoding='utf-8').count('PurpleRoundJournal.') == 1, name + ': one registration'
    pinned += 1
(args.output / 'pins.log').write_text('RECORDING_HOOK_PINS=%d\n' % pinned)

# The AAC measure shim and the round recovery bridge are compiled into
# upstream's library on Android, against its FFmpeg; here they are compiled
# against the host's libavcodec, and run against AAC streams ffmpeg encodes now
# (mono 48 kHz as the round recorders use, and stereo 44.1 kHz), whose ADTS
# headers give each frame's length.
ffmpeg = shutil.which('ffmpeg')
assert ffmpeg, 'the recording suite needs ffmpeg on PATH'
flags = subprocess.run(['pkg-config', '--cflags', '--libs', 'libavcodec', 'libavutil'], check=True,
                       stdout=subprocess.PIPE, text=True).stdout.split()
java_home = compiler.resolve().parent.parent
java_includes = ['-I' + str(java_home / 'include'), '-I' + str(java_home / 'include' / ('darwin' if platform.system() == 'Darwin' else 'linux'))]
streams = {
    'mono48k.adts': ['-f', 'lavfi', '-i', 'sine=frequency=440:duration=4:sample_rate=48000', '-ac', '1', '-b:a', '64k'],
    'stereo44k.adts': ['-f', 'lavfi', '-i', 'anoisesrc=d=2:c=pink:r=44100', '-ac', '2', '-b:a', '128k'],
}
commands = {}
for name, source in streams.items():
    commands['encode-' + name.split('.')[0]] = [ffmpeg, '-nostdin', '-loglevel', 'error', '-y', *source,
                                                 '-c:a', 'aac', '-f', 'adts', str(args.output / name)]
# A finished voice recording (Ogg/Opus) and a finished MP4, which the bin
# program truncates into crashed recordings.
commands['encode-voice'] = [ffmpeg, '-nostdin', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
                            'sine=frequency=330:duration=4:sample_rate=48000', '-ac', '1', '-c:a', 'libopus', '-b:a', '32k',
                            '-f', 'ogg', str(args.output / 'voice.ogg')]
commands['encode-finished'] = [ffmpeg, '-nostdin', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
                               'testsrc=size=240x240:rate=30', '-t', '1', '-c:v', 'mpeg4', '-f', 'mp4', str(args.output / 'finished.mp4')]
commands['aac-build'] = ['c++', '-std=c++17', '-O1', '-I' + str(jni), str(jni / 'aac_measure.cpp'),
                         str(tests / 'recording/aac_measure_cases.cpp'), *flags, '-o', str(args.output / 'aac_measure_cases')]
commands['aac'] = [str(args.output / 'aac_measure_cases'), *[str(args.output / name) for name in streams]]
# The sources purple.cmake adds to upstream's library for F10, as one host library.
round_sources = [jni / 'aac_measure.cpp', jni / 'round_recovery_bridge.cpp',
                 core / 'purple/purple_mp4_repair.cpp', core / 'purple/purple_round_journal.cpp',
                 core / 'purple/purple_recording_file.cpp']
commands['round-build'] = ['c++', '-std=c++17', '-O1', '-shared', '-fPIC', '-I' + str(jni), '-I' + str(core), *java_includes,
                           *map(str, round_sources), *flags, '-o', str(host_library)]

destination = args.output / 'classes'
destination.mkdir()
stubs = args.output / 'host-stubs'
stubs.mkdir()
empty = args.output / 'empty-sourcepath'
empty.mkdir()
cases = [tests / 'recording/RecordingCases.java', tests / 'recording/RoundCases.java']
stub_sources = sorted((tests / 'recording/host_stubs').rglob('*.java'))
(args.output / 'inputs.json').write_text(json.dumps([
    {'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
    for p in [args.receipt, args.library, *cases, *stub_sources, tests / 'recording/aac_measure_cases.cpp',
              jni / 'aac_measure.h', *round_sources, *purple, *upstream, *hooked.values()]], indent=2) + '\n')
# android.jar's media classes only throw on the host: MediaFormat and
# MediaCodec.BufferInfo come from small host stand-ins, first on the class path.
dependencies = [item['path'] for key in ['classpath', 'bootstrapClasspath'] for item in receipt.get(key, [])]
classpath = os.pathsep.join([str(stubs), str(destination), str(classes), str(args.json), *dependencies])
commands['javac-stubs'] = [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty),
                           '-d', str(stubs), *map(str, stub_sources)]
commands['javac'] = [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty),
                     '-cp', classpath, '-d', str(destination), *map(str, cases)]
# The programs load the host libpurplecore through the real PurpleCore, which
# reads its load result with org.json; recovery also loads the host build of
# the recovery bridge.
for program, cls, _, extra in PROGRAMS:
    commands[program] = [str(compiler.with_name('java')), '-Xmx512m', '-Xcheck:jni', '-Djava.library.path=' + str(args.library.parent),
                         '-cp', classpath, 'org.telegram.messenger.purple.' + cls, program, *extra]
for name, command in commands.items():
    (args.output / (name + '.argv.json')).write_text(json.dumps(command, indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log, (args.output / (name + '.stderr.log')).open('w') as errors:
        # FFmpeg's decoder reports each refused prefix on stderr, so the aac
        # and recovery programs keep their stderr apart from the completion
        # records.
        separate = name in ('aac', 'recovery')
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=log,
                                stderr=errors if separate else subprocess.STDOUT)
    output = (args.output / (name + '.log')).read_text()
    if result.returncode and name in ('aac', 'recovery'):
        output += ''.join(line for line in (args.output / (name + '.stderr.log')).read_text().splitlines(True)
                          if not line.startswith('[aac @'))
    print(name, result.returncode, output[-2000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
for program, key in [(p, k) for p, _, k, _ in PROGRAMS] + [('aac', 'AAC_MEASURE_CHECKS')]:
    assert key + '=' in (args.output / (program + '.log')).read_text(), program + ' printed no ' + key
