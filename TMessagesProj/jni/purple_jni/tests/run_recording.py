#!/usr/bin/env python3
"""Run the media recording cases (docs/media-recording-design.md) on real AGP classes and pin their upstream hooks.

F11, crash capture: PurpleCrashCaptureCases runs against the AGP-compiled
PurpleDiagLogStore, PurpleDiagRepeats, PurpleCrashRecord,
PurpleCrashTombstone and PurpleCrashCollect, and the ApplicationLoader.onCreate hook is pinned.
The receipt is the JSON the compile-evidence init script writes for
:TMessagesProj:compileStandaloneJavaWithJavac.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
repo = tests.parents[3]
java = repo / 'TMessagesProj/src/main/java/org/telegram'
receipt = json.loads(args.receipt.read_text())
assert receipt['task'] == ':TMessagesProj:compileStandaloneJavaWithJavac'
classes = Path(receipt['destination'])
compiler = Path(receipt['compiler'])
assert classes.is_dir()

# The Purple classes the cases load from the receipt, which must be the ones
# in this tree, and the files whose hooks are pinned below.
purple = [java / 'messenger/purple' / name for name in [
    'PurpleDiagLogStore.java', 'PurpleDiagRepeats.java', 'PurpleCrashRecord.java', 'PurpleCrashTombstone.java',
    'PurpleCrashCollect.java', 'PurpleCrashCapture.java']]
hooked = {name: java / name for name in ['messenger/ApplicationLoader.java']}
hashes = {Path(item['path']): item['sha256'] for item in receipt['sources']}
for path in purple + list(hooked.values()):
    assert hashes.get(path) == hashlib.sha256(path.read_bytes()).hexdigest(), str(path) + ' receipt mismatch'

# Each hook is the line right after the one named, inside the function named.
PINS = [
    ('messenger/ApplicationLoader.java', '    public void onCreate() {\n', '        super.onCreate();\n',
     '        org.telegram.messenger.purple.PurpleCrashCapture.start(this);\n'),
]
PROGRAMS = [('crash', 'CRASH_CAPTURE_CHECKS', 'org.telegram.messenger.purple.PurpleCrashCaptureCases')]

assert not args.output.exists(), 'output must be an unused directory'
args.output.mkdir(parents=True)
pinned = 0
for name, function, anchor, hook in PINS:
    text = hooked[name].read_text(encoding='utf-8')
    assert text.count(function) == 1, name + ': ' + function.strip()
    assert text.count(hook) == 1, name + ': the hook appears once'
    start = text.index(function)
    at = text.index(anchor + hook, start)
    assert '\n    }\n' not in text[start:at], name + ': ' + hook.strip() + ' is inside ' + function.strip()
    pinned += 1
(args.output / 'pins.log').write_text('RECORDING_HOOK_PINS=%d\n' % pinned)

destination = args.output / 'classes'
destination.mkdir()
empty = args.output / 'empty-sourcepath'
empty.mkdir()
fixtures = args.output / 'fixtures'
fixtures.mkdir()
cases = [tests / 'recording/PurpleCrashCaptureCases.java']
(args.output / 'inputs.json').write_text(json.dumps([
    {'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
    for p in [args.receipt, *cases, *purple, *hooked.values()]], indent=2) + '\n')
commands = {'javac': [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty),
                      '-cp', str(classes), '-d', str(destination), *map(str, cases)]}
for program, _, main in PROGRAMS:
    commands[program] = [str(compiler.with_name('java')), '-Xmx256m', '-cp', os.pathsep.join([str(destination), str(classes)]),
                         main, str(classes), str(fixtures / program)]
for name, command in commands.items():
    (args.output / (name + '.argv.json')).write_text(json.dumps(command, indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log:
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
    output = (args.output / (name + '.log')).read_text()
    print(name, result.returncode, output[-2000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
for program, key, _ in PROGRAMS:
    assert key + '=' in (args.output / (program + '.log')).read_text(), program + ' printed no ' + key
