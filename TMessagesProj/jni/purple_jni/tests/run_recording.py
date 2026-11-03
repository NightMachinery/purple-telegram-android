#!/usr/bin/env python3
"""Run the media recording cases (docs/media-recording-design.md, F10) on real AGP classes and the host core library,
check the AAC measure shim against a stream encoded at test time, and pin the F10 upstream hooks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
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
java = repo / 'TMessagesProj/src/main/java/org/telegram'
receipt = json.loads(args.receipt.read_text())
assert receipt['task'] == ':TMessagesProj:compileStandaloneJavaWithJavac'
classes = Path(receipt['destination'])
compiler = Path(receipt['compiler'])
assert classes.is_dir() and args.json.is_file() and args.library.is_file()

# The Purple classes the cases load from the receipt, which must be the ones in
# this tree.
purple = [java / 'messenger/purple' / name for name in [
    'PurpleCore.java', 'PurpleRecordingSettings.java', 'PurpleRecordingCore.java', 'PurpleRecordingEntry.java',
    'PurpleRecordingManifest.java', 'PurpleRecordingFiles.java', 'PurpleRecordingStore.java']]
hashes = {Path(item['path']): item['sha256'] for item in receipt['sources']}
for path in purple:
    assert hashes.get(path) == hashlib.sha256(path.read_bytes()).hexdigest(), str(path) + ' receipt mismatch'

work = args.output / 'work'
# Each program: its completion record and its arguments.
PROGRAMS = [('settings', 'RECORDING_SETTINGS_CHECKS', []),
            ('bin', 'RECORDING_BIN_CHECKS', [str(args.output / 'voice.ogg'), str(args.output / 'finished.mp4'), str(work / 'bin')])]

assert not args.output.exists(), 'output must be an unused directory'
args.output.mkdir(parents=True)

# The AAC measure shim is compiled into upstream's library on Android, against
# its FFmpeg; here it is compiled against the host's libavcodec, and measured
# against AAC streams ffmpeg encodes now (mono 48 kHz as the round recorders
# use, and stereo 44.1 kHz), whose ADTS headers give each frame's length.
ffmpeg = shutil.which('ffmpeg')
assert ffmpeg, 'the aac phase needs ffmpeg on PATH'
flags = subprocess.run(['pkg-config', '--cflags', '--libs', 'libavcodec', 'libavutil'], check=True,
                       stdout=subprocess.PIPE, text=True).stdout.split()
streams = {
    'mono48k.adts': ['-f', 'lavfi', '-i', 'sine=frequency=440:duration=3:sample_rate=48000', '-ac', '1', '-b:a', '64k'],
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

destination = args.output / 'classes'
destination.mkdir()
empty = args.output / 'empty-sourcepath'
empty.mkdir()
case = tests / 'recording/RecordingCases.java'
(args.output / 'inputs.json').write_text(json.dumps([
    {'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
    for p in [args.receipt, args.library, case, jni / 'aac_measure.cpp', jni / 'aac_measure.h',
              tests / 'recording/aac_measure_cases.cpp', *purple]], indent=2) + '\n')
commands['javac'] = [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty),
                     '-cp', str(classes), '-d', str(destination), str(case)]
# The programs load the host libpurplecore through the real PurpleCore, which
# reads its load result with org.json.
for program, _, extra in PROGRAMS:
    commands[program] = [str(compiler.with_name('java')), '-Xmx256m', '-Xcheck:jni', '-Djava.library.path=' + str(args.library.parent),
                         '-cp', os.pathsep.join([str(destination), str(classes), str(args.json)]),
                         'org.telegram.messenger.purple.RecordingCases', program, *extra]
for _, _, extra in PROGRAMS:
    for path in extra:
        if path.startswith(str(work)):
            Path(path).mkdir(parents=True, exist_ok=True)
for name, command in commands.items():
    (args.output / (name + '.argv.json')).write_text(json.dumps(command, indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log:
        # FFmpeg's decoder reports each refused prefix on stderr; only the
        # cases' own output goes to the aac log.
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=log,
                                stderr=subprocess.DEVNULL if name == 'aac' else subprocess.STDOUT)
    output = (args.output / (name + '.log')).read_text()
    print(name, result.returncode, output[-2000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
for program, key in [(p, k) for p, k, _ in PROGRAMS] + [('aac', 'AAC_MEASURE_CHECKS')]:
    assert key + '=' in (args.output / (program + '.log')).read_text(), program + ' printed no ' + key
