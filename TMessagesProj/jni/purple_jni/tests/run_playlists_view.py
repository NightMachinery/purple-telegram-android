#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description='Typed shared playlist projection and screen scope over real JNI/files')
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--json', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['compile', 'run'], required=True)
    parser.add_argument('--java-runtime', type=Path)
    parser.add_argument('--api', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    java = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    receipt = json.loads(args.receipt.read_text())
    compiler = Path(receipt['compiler'])
    runtime = args.java_runtime or Path(os.environ.get('PURPLE_HOST_JAVA', str(compiler.with_name('java'))))
    if not runtime.is_file() or not os.access(runtime, os.X_OK):
        raise RuntimeError('Explicit host Java runtime is unavailable')
    sources = [java / name for name in ['PurplePlaylistsSyncResult.java', 'PurplePlaylistsAdd.java', 'PurplePlaylistsResolver.java', 'PurplePlaylistsScreen.java', 'PurplePlaylistsView.java', 'PurplePlaylistsEdits.java', 'PurplePlaylistsDownloadsCore.java', 'PurplePlaylistsRemoval.java', 'PurplePlaylistsWorker.java', 'PurplePlaylistsWorkers.java', 'PurplePlaylistsAccount.java', 'PurpleAccountFiles.java',
        'PurplePlaylistsStore.java', 'PurplePlaylistsCore.java', 'PurplePlaylistsLocalCore.java',
        'PurplePlaylistsPhysical.java', 'PurpleAccountFileSnapshot.java',
        'PurpleSyncRetainedBases.java', 'PurpleGlobalChoiceModel.java']]
    sources += [tests / 'playlists/PurplePlaylistsWorkerCases.java', tests / 'playlists/PurplePlaylistsEditsCases.java', tests / 'playlists/PurplePlaylistsViewCases.java',
        tests / 'java_binding/org/telegram/messenger/purple/PurpleCore.java']
    ports = sorted((tests / 'playlists/account_ports').rglob('*.java'))
    if len(ports) != 13:
        raise RuntimeError('Captured account platform ports are incomplete')
    sources += ports
    sources += [tests / ('playlists/player_ports/' + name) for name in [
        'org/telegram/messenger/MessageObject.java', 'org/telegram/messenger/MessagesController.java',
        'org/telegram/messenger/purple/PurpleCopyPolicy.java']]
    sources += [tests / ('playlists/view_ports/' + name) for name in [
        'org/telegram/messenger/MediaController.java', 'org/telegram/messenger/purple/PurplePlaylistsPlayer.java',
        'org/telegram/messenger/purple/PurplePlaylistsDownloadControls.java']]
    inputs = [*sources, args.receipt, args.json, args.library, compiler, runtime, Path(__file__)]
    pins = {str(path.resolve()): digest(path) for path in inputs}
    if args.api:
        pins.update({str(path.resolve()): digest(path) for path in sorted(args.api.rglob('*.class'))})
    classpath = os.pathsep.join([str(args.json), *([str(args.api)] if args.api else []), receipt['destination'], *[
        item['path'] for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]]])
    classes = args.output / 'classes'
    if args.phase == 'compile':
        args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
        classes.mkdir(mode=0o700)
        empty = args.output / 'empty-sourcepath'
        empty.mkdir(mode=0o700)
        (args.output / 'fixtures').mkdir(mode=0o700)
        command = [str(compiler), '-J-Xmx128m', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
            '-proc:none', '-sourcepath', str(empty), '-cp', classpath, '-d', str(classes), *map(str, sources)]
    else:
        if json.loads((args.output / 'inputs.json').read_text()) != pins:
            raise RuntimeError('Captured playlist account inputs changed after compilation')
        artifacts = {str(path.relative_to(classes)): digest(path) for path in sorted(classes.rglob('*.class'))}
        if json.loads((args.output / 'classes.json').read_text()) != artifacts:
            raise RuntimeError('Compiled account fixtures changed')
        command = [str(runtime), '-Xcheck:jni', '-Xmx512m', '-XX:ActiveProcessorCount=2',
            '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + str((args.output / 'fixtures').resolve()),
            '-Dpurple.test.library=' + str(args.library.resolve()),
            '-cp', str(classes) + os.pathsep + classpath,
            'org.telegram.messenger.purple.PurplePlaylistsViewCases']
    environment = dict(os.environ)
    for key in list(environment):
        if key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS') or key.startswith('DYLD_'):
            environment.pop(key)
    log = args.output / (args.phase + '.log')
    with log.open('x') as handle:
        result = subprocess.run(command, cwd=repo, env=environment, stdin=subprocess.DEVNULL,
            stdout=handle, stderr=subprocess.STDOUT, timeout=240)
    (args.output / (args.phase + '-exit.json')).write_text(json.dumps({'exit': result.returncode}) + '\n')
    if result.returncode:
        raise RuntimeError('Captured account phase failed; inspect its finite log')
    if args.phase == 'compile':
        (args.output / 'inputs.json').write_text(json.dumps(pins, indent=2) + '\n')
        artifacts = {str(path.relative_to(classes)): digest(path) for path in sorted(classes.rglob('*.class'))}
        (args.output / 'classes.json').write_text(json.dumps(artifacts, indent=2) + '\n')
    else:
        text = log.read_text()
        if re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', text):
            raise RuntimeError('Captured account fixture reported a JNI diagnostic')
        print(text, end='')
    current_pins = {str(path.resolve()): digest(path) for path in inputs}
    if args.api:
        current_pins.update({str(path.resolve()): digest(path) for path in sorted(args.api.rglob('*.class'))})
    if current_pins != pins:
        raise RuntimeError('Captured account inputs changed during the phase')


if __name__ == '__main__':
    main()
