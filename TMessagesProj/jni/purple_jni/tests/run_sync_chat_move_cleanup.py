#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description='Actual AGP exact cleanup, durable outcomes and explicit progress controls')
for name in ['receipt', 'json', 'library', 'output', 'modern', 'carry', 'store', 'io', 'executor', 'change']:
    parser.add_argument('--' + name, type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
receipt = json.loads(args.receipt.read_text())
compiler = Path(receipt['compiler'])
production = Path(receipt['destination'])
assert receipt['task'] in [':TMessagesProj:compileDebugJavaWithJavac', ':TMessagesProj:compileStandaloneJavaWithJavac']
assert args.library.is_file() and (args.modern / 'classes').is_dir() and (args.carry / 'fixtures.jsonl').is_file()
args.output.mkdir(parents=True, exist_ok=False)
out = args.output / 'classes'
out.mkdir()
empty = args.output / 'empty-sourcepath'
empty.mkdir()
classpath = os.pathsep.join([str(args.change / 'classes'), str(args.executor / 'classes'), str(args.io / 'classes'), str(args.store / 'classes'), str(args.modern / 'classes'), str(production), str(args.json)] +
    [item['path'] for name in ['classpath', 'bootstrapClasspath'] for item in receipt[name]])
main = args.output / 'CarryCleanupHostMain.java'
main.write_text('import java.io.File; public final class CarryCleanupHostMain { public static void main(String[] a) throws Exception {'
    'org.telegram.messenger.purple.PurpleCore.ensureLoaded();'
    'for(String n:new String[]{"PurpleSyncChatMoveCleanup","PurpleSyncChatChange","PurpleSyncChatMoveConsent","PurpleSyncChatScreen","PurpleSyncChatMove","PurpleSyncChat","PurpleSyncChatMoveRequests","PurpleSyncAutoEngine","PurpleSyncOperations","PurpleSyncChatService","PurpleSyncRunner","PurpleSyncAuto","PurpleAccountSyncStore","PurpleSyncChatMoveIO","PurpleSyncChatMoveStore","PurpleSyncChatMoveCore","PurpleSyncAutoUpload"})'
    'if(!new File(Class.forName("org.telegram.messenger.purple."+n).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new File(a[0]).getCanonicalFile()))throw new AssertionError("business shadow "+n);'
    'org.telegram.messenger.purple.PurpleSyncChatCleanupReviewCases.main(new String[]{a[1],a[2]});'
    'org.telegram.messenger.purple.PurpleSyncChatCleanupResumeCases.main(new String[]{a[1],new File(a[2],"resume").getPath()});}}\n')
sources = sorted((tests / 'sync_chat_move_cleanup').glob('*.java')) + [main]
def run(name, argv):
    (args.output / (name + '.argv.json')).write_text(json.dumps(list(map(str, argv)), indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log:
        result = subprocess.run(list(map(str, argv)), stdout=log, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    print((args.output / (name + '.log')).read_text()[-6000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
run('javac', [compiler, '-source', '8', '-target', '8', '-proc:none', '-sourcepath', empty, '-cp', classpath, '-d', out, *sources])
run('cleanup', [compiler.with_name('java'), '--add-opens=java.base/sun.nio.fs=ALL-UNNAMED', '-Xcheck:jni', '-Xmx768m', '-XX:ActiveProcessorCount=2',
    '-Dpurple.test.strings=' + str(tests.parents[2] / 'src/main/res/values/purple_strings.xml'), '-Djava.library.path=' + str(args.library.parent), '-cp', str(out) + os.pathsep + classpath,
    'CarryCleanupHostMain', production, args.carry / 'fixtures.jsonl', args.output / 'fixtures'])
inputs = sources + [args.receipt, args.library, args.carry / 'fixtures.jsonl', tests.parents[2] / 'src/main/res/values/purple_strings.xml']
(args.output / 'inputs.json').write_text(json.dumps([{'path': str(f), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()} for f in inputs], indent=2) + '\n')
