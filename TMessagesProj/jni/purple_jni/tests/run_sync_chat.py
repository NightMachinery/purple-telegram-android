#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description='Source and host-JNI sync-chat cases, without AGP or Telegram requests')
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--json', type=Path, required=True)
parser.add_argument('--library', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--phase', choices=['compile', 'run'], required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[4]
tests = Path(__file__).resolve().parent
receipt = json.loads(args.receipt.read_text())
base = Path(receipt['destination'])
compiler = Path(receipt['compiler'])
classes = args.output / 'classes'
empty = args.output / 'empty-sourcepath'
classpath = os.pathsep.join([str(base), str(args.json)] + [item['path'] for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]])
sources = [repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple' / (name + '.java') for name in ['PurpleSyncChatCore', 'PurpleSyncChatOrigin', 'PurpleSyncChatFacts']]
sources += [tests / 'sync_chat/PurpleSyncChatCases.java'] + list((tests / 'sync_chat/ports').rglob('*.java'))
args.output.mkdir(parents=True, exist_ok=True)
if args.phase == 'compile':
    classes.mkdir(exist_ok=True)
    empty.mkdir(exist_ok=True)
    command = [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty), '-cp', classpath, '-d', str(classes)] + list(map(str, sources))
    inputs = sources + [args.library, args.receipt, args.json, base / 'org/telegram/tgnet/TLRPC.class', base / 'org/telegram/tgnet/TLRPC$TL_message.class', base / 'org/telegram/messenger/purple/PurpleCore.class']
    (args.output / 'inputs.json').write_text(json.dumps([{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in inputs], indent=2) + '\n')
else:
    command = [str(compiler.with_name('java')), '-Xcheck:jni', '-Xmx512m', '-XX:ActiveProcessorCount=2', '-Djava.library.path=' + str(args.library.parent), '-cp', str(classes) + os.pathsep + classpath, 'org.telegram.messenger.purple.PurpleSyncChatCases', str(classes), str(base)]
(args.output / (args.phase + '.argv.json')).write_text(json.dumps(command, indent=2) + '\n')
with (args.output / (args.phase + '.log')).open('w') as log:
    result = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
print((args.output / (args.phase + '.log')).read_text()[-6000:])
raise SystemExit(result.returncode)
