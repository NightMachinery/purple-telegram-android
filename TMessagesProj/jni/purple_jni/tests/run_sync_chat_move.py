#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess

parser = argparse.ArgumentParser(description='Replay pinned core carry fixtures through the real Android JNI bridge')
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--json', type=Path, required=True)
parser.add_argument('--library', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
repo = tests.parents[3]
core = repo / 'TMessagesProj/jni/purple'
receipt = json.loads(args.receipt.read_text())
assert receipt['task'] in [':TMessagesProj:compileDebugJavaWithJavac', ':TMessagesProj:compileStandaloneJavaWithJavac']
compiler = Path(receipt['compiler'])
classes = Path(receipt['destination'])
assert args.library.is_file() and args.json.is_file() and classes.is_dir()
args.output.mkdir(parents=True, exist_ok=False)

def run(name, command):
    (args.output / (name + '.argv.json')).write_text(json.dumps(list(map(str, command)), indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log:
        result = subprocess.run(list(map(str, command)), cwd=repo, stdout=log, stderr=subprocess.STDOUT)
    print((args.output / (name + '.log')).read_text()[-6000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)

if platform.system() == 'Darwin':
    load_commands = subprocess.check_output(['otool', '-l', str(args.library)], text=True)
    candidates = re.findall(r'cmd LC_RPATH\s+cmdsize \d+\s+path (.*?) \(offset', load_commands)
    frameworks = [Path(path) for path in candidates if (Path(path) / 'QtCore.framework/Headers').is_dir()]
    assert len(frameworks) == 1, 'host library must name its unique Qt framework directory'
    qt = frameworks[0]
    qt_flags = ['-F' + str(qt), '-I' + str(qt / 'QtCore.framework/Headers'), '-framework', 'QtCore', '-Wl,-rpath,' + str(qt)]
else:
    import shlex
    qt_flags = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'Qt6Core'], text=True))
fixture_binary = args.output / 'fixtures'
fixture_rows = args.output / 'fixtures.jsonl'
run('fixture-compile', ['clang++', '-std=c++20', '-O0', '-I' + str(core), '-I' + str(core / 'tomlplusplus'),
    tests / 'sync_chat_move/fixtures.cpp', args.library, *qt_flags, '-o', fixture_binary])
run('core', [fixture_binary, fixture_rows])
out = args.output / 'classes'
out.mkdir()
sourcepath = args.output / 'empty-sourcepath'
sourcepath.mkdir()
main = args.output / 'MoveHostMain.java'
main.write_text('import java.io.File; public final class MoveHostMain { public static void main(String[] a) throws Exception {'
    'File origin = new File(Class.forName("org.telegram.messenger.purple.PurpleSyncChatMoveCore").getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();'
    'if (!origin.equals(new File(a[0]).getCanonicalFile())) throw new AssertionError("carry business class shadow");'
    'System.load(a[1]); org.telegram.messenger.purple.PurpleSyncChatMoveCases.main(new String[]{a[2]}); }}\n')
classpath = os.pathsep.join([str(classes), str(args.json)] + [item['path'] for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]])
run('javac', [compiler, '-source', '8', '-target', '8', '-proc:none', '-sourcepath', sourcepath, '-cp', classpath, '-d', out,
    tests / 'sync_chat_move/PurpleSyncChatMoveCases.java', main])
run('jni', [compiler.with_name('java'), '-Xcheck:jni', '-Xmx768m', '-XX:ActiveProcessorCount=2',
    '-Djava.library.path=' + str(args.library.parent), '-cp', str(out) + os.pathsep + classpath,
    'MoveHostMain', classes, args.library.resolve(), fixture_rows])
inputs = [tests / 'sync_chat_move/fixtures.cpp', tests / 'sync_chat_move/PurpleSyncChatMoveCases.java',
    core / 'tests/test_sync_chat_carry.cpp', args.receipt, args.library]
(args.output / 'inputs.json').write_text(json.dumps([{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in inputs], indent=2) + '\n')
