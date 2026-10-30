#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description='Seeded three-install crash and race cases on the actual AGP executor and real JNI/files')
for name in ['receipt', 'json', 'library', 'output', 'modern', 'carry', 'store', 'io', 'executor']:
    parser.add_argument('--' + name, type=Path, required=True)
parser.add_argument('--seed', action='append', type=int, help='replay a printed seed; repeat for multiple seeds')
parser.add_argument('--scenario', choices=['all', 'matrix', 'races'], default='all')
args = parser.parse_args()
seeds = args.seed or [271828, 314159, 424242]
tests = Path(__file__).resolve().parent
receipt = json.loads(args.receipt.read_text())
compiler = Path(receipt['compiler'])
production = Path(receipt['destination'])
assert receipt['task'] in [':TMessagesProj:compileDebugJavaWithJavac', ':TMessagesProj:compileStandaloneJavaWithJavac']
cohort = []
for source in receipt['sources']:
    marker = '/TMessagesProj/src/main/java/org/telegram/messenger/purple/'
    if marker in source['path']:
        local = tests.parents[3] / ('TMessagesProj/src/main/java/org/telegram/messenger/purple/' + source['path'].split(marker, 1)[1])
        assert local.is_file() and hashlib.sha256(local.read_bytes()).hexdigest() == source['sha256'], 'AGP source mismatch: ' + str(local)
        cohort.append(source)
assert cohort, 'no production source cohort'
assert args.library.is_file() and (args.modern / 'classes').is_dir() and (args.carry / 'fixtures.jsonl').is_file()
args.output.mkdir(parents=True, exist_ok=False)
out = args.output / 'classes'
out.mkdir()
empty = args.output / 'empty-sourcepath'
empty.mkdir()
classpath = os.pathsep.join([str(args.executor / 'classes'), str(args.io / 'classes'), str(args.store / 'classes'), str(args.modern / 'classes'), str(production), str(args.json)] +
    [item['path'] for name in ['classpath', 'bootstrapClasspath'] for item in receipt[name]])
main = args.output / 'CarryRaceHostMain.java'
main.write_text('import java.io.File; public final class CarryRaceHostMain { public static void main(String[] a) throws Exception {'
    'org.telegram.messenger.purple.PurpleCore.ensureLoaded();'
    'for(String n:new String[]{"PurpleSyncChatMove","PurpleSyncChat","PurpleSyncChatMoveRequests","PurpleSyncAutoEngine","PurpleSyncOperations","PurpleSyncChatService","PurpleSyncRunner","PurpleSyncAuto","PurpleAccountSyncStore","PurpleSyncChatMoveIO","PurpleSyncChatMoveStore","PurpleSyncChatMoveCore","PurpleSyncChatMoveCleanup","PurpleSyncAutoUpload","PurpleSyncRetainedFiles"})'
    'if(!new File(Class.forName("org.telegram.messenger.purple."+n).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new File(a[0]).getCanonicalFile()))throw new AssertionError("business shadow "+n);'
    'org.telegram.messenger.purple.PurpleSyncChatMoveRaceCases.main(new String[]{a[1],a[2],a[3],a[4]});}}\n')
sources = sorted((tests / 'sync_chat_move_races').glob('*.java')) + [main]
def run(name, argv):
    (args.output / (name + '.argv.json')).write_text(json.dumps(list(map(str, argv)), indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log:
        result = subprocess.run(list(map(str, argv)), stdout=log, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    print((args.output / (name + '.log')).read_text(), flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
run('javac', [compiler, '-source', '8', '-target', '8', '-proc:none', '-sourcepath', empty, '-cp', classpath, '-d', out, *sources])
for seed in seeds:
    print('Replay with --seed ' + str(seed), flush=True)
    run('race-' + str(seed), [compiler.with_name('java'), '--add-opens=java.base/sun.nio.fs=ALL-UNNAMED', '-Xcheck:jni', '-Xmx768m', '-XX:ActiveProcessorCount=2',
        '-Djava.library.path=' + str(args.library.parent), '-cp', str(out) + os.pathsep + classpath,
        'CarryRaceHostMain', production, args.carry / 'fixtures.jsonl', args.output / ('fixtures-' + str(seed)), seed, args.scenario])

inputs = sources + [args.receipt, args.library, args.carry / 'fixtures.jsonl']
(args.output / 'inputs.json').write_text(json.dumps([{'path': str(f), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest()} for f in inputs], indent=2) + '\n')
print('CARRY_RACE_DRIVER_SEEDS=' + str(len(seeds)), flush=True)
