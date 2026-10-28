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
parser.add_argument('--suite', choices=['facts','discovery','integration'], default='facts')
parser.add_argument('--overlay', type=Path)
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
if args.suite == 'discovery':
    production = repo / 'TMessagesProj/src/main/java'
    owned = production / 'org/telegram/messenger/purple'
    sources = list(owned.glob('PurpleSyncChat*.java')) + list(owned.glob('PurpleRpc*.java'))
    sources += [owned / (name + '.java') for name in ['PurpleSyncClient','PurpleSyncOperations','PurpleSyncTelegramClient','PurpleSyncRunner','PurpleSyncTransport','PurpleSyncAuto','PurpleAccountFiles','PurpleSyncAutoClient','PurpleSyncAutoNetwork','PurpleSyncManagedReads','PurpleSyncReadScope','PurpleSyncReader','PurpleSyncInventory','PurpleSyncCheck','PurpleSyncCore','PurpleSyncPost','PurpleAccountSyncStore','PurpleSyncAutoCore','PurpleSyncTelegramTransport','PurpleSyncPublisher','PurpleSyncAutoUpload','PurpleSyncAutoEngine','PurpleAccountSyncCore','PurpleSyncApply','PurpleSyncAutoEdits','PurpleSyncHistory','PurpleSyncMutation','PurpleAccountEditorModel']]
    sources += [production / 'org/telegram/ui' / (name + '.java') for name in ['PurpleDialogsHooks','PurpleSyncActivity','PurpleSyncChatActivity','PurpleSyncChatSetupUI','PurpleSyncReviewDialog']]
    sources += list((tests / 'modern_sync/android/system').glob('*.java'))
    sources += [tests / 'modern_sync/android/util/Base64.java', tests / 'sync_chat_move_io/ports/org/telegram/messenger/FileLoader.java', tests / 'sync_chat_move_io/ports/android/text/TextUtils.java']
    sources += [p for p in (tests / 'sync_chat').glob('*.java') if not p.name.endswith('IntegrationCases.java')] + list((tests / 'sync_chat/ports').rglob('*.java'))
if args.suite == 'integration':
    if args.overlay is None or not args.overlay.is_dir():
        parser.error('--overlay compiled production classes required for integration')
    classpath = str(args.overlay) + os.pathsep + classpath
    sources = list((tests / 'modern_sync').rglob('*.java'))
    sources += [tests / path for path in ['account_editor/PurpleAccountEditorCases.java','routed_writes/PurpleRoutedWriteCases.java','transfers/PurpleTransferCases.java']]
    sources += list((tests / 'sync_chat').glob('*IntegrationCases.java'))
    sources += [tests / path for path in ['store_stubs/org/telegram/messenger/purple/PurpleAccountSyncStoreTest.java','config_sync_bridge/org/telegram/messenger/purple/PurpleSyncCoreTest.java']]
args.output.mkdir(parents=True, exist_ok=True)
if args.phase == 'compile':
    classes.mkdir(exist_ok=True)
    empty.mkdir(exist_ok=True)
    command = [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty), '-cp', classpath, '-d', str(classes)] + list(map(str, sources))
    if args.suite == 'integration':
        (args.output / 'overlay-inputs.json').write_text(json.dumps([{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(args.overlay.rglob('*.class'))],indent=2) + '\n')
    inputs = sources + [args.library, args.receipt, args.json, base / 'org/telegram/tgnet/TLRPC.class', base / 'org/telegram/tgnet/TLRPC$TL_message.class', base / 'org/telegram/messenger/purple/PurpleCore.class']
    (args.output / 'inputs.json').write_text(json.dumps([{'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in inputs], indent=2) + '\n')
else:
    command = [str(compiler.with_name('java')), '-Xcheck:jni', '-Xmx512m', '-XX:ActiveProcessorCount=2', '-Djava.library.path=' + str(args.library.parent), '-cp', str(classes) + os.pathsep + classpath, 'org.telegram.messenger.purple.' + ('PurpleSyncChatCases' if args.suite == 'facts' else 'PurpleSyncChatIntegrationCases' if args.suite == 'integration' else 'PurpleSyncChatDiscoveryCases'), str(classes), str(base), str(args.output)]
    if args.suite == 'integration':
        command = command[:-3] + [str(args.overlay),str(args.output)]
        command[1:1] = ['-Dscratch=' + str(args.output), '-Djava.io.tmpdir=' + str(args.output), '-Dpurple.test.strings=' + str(repo / 'TMessagesProj/src/main/res/values/purple_strings.xml')]
(args.output / (args.phase + '.argv.json')).write_text(json.dumps(command, indent=2) + '\n')
with (args.output / (args.phase + '.log')).open('w') as log:
    result = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT)
print((args.output / (args.phase + '.log')).read_text()[-6000:])
raise SystemExit(result.returncode)
