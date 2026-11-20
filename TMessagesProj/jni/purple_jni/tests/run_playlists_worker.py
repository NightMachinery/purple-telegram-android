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


def cold_checkpoints(command, folder, repo, environment):
    fixture = 'org.telegram.messenger.purple.PurplePlaylistsEditCheckpointCases'
    command = [*command[:-1], fixture]
    scenarios = [
        ('prepare', 'prepare', 'prior', 'baseline'),
        ('library', 'library', 'prior', 'baseline'),
        ('library-unknown', 'library-unknown', 'partial', 'baseline'),
        ('local', 'local', 'partial', 'baseline'),
        ('local-unknown', 'local-unknown', 'settled', 'baseline'),
        ('retire', 'retire', 'settled', 'baseline'),
        ('double-exit', 'local', 'after-recovery', 'baseline'),
        ('cooldown', 'local', 'partial', 'wait'),
        ('new-library', 'local', 'partial', 'pristine'),
        ('clean', 'none', 'settled', 'baseline'),
    ]
    refusals = ('library', 'state', 'account', 'damaged', 'format')

    def phase(path, success, *args):
        run = subprocess.run([*command, args[0], str(path), *args[1:]], cwd=repo,
            env=environment, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, timeout=10)
        with (folder / 'cold-checkpoints.log').open('a') as log:
            log.write(run.stdout)
        if run.returncode or run.stdout.strip() != success:
            raise RuntimeError('Cold edit checkpoint failed at ' + path.name + ' ' + args[0])

    for name, fault, recovered, mode in scenarios:
        path = folder / name
        path.mkdir(mode=0o700)
        phase(path, 'SEED_' + fault, 'seed', fault, mode)
        if name == 'double-exit':
            phase(path, 'RECOVERY_INTERRUPTED_AFTER_STATE', 'read', 'retry-retire', mode)
        phase(path, 'RECOVERED_' + recovered, 'read', recovered, mode)
    for reason in refusals:
        path = folder / ('refuse-' + reason)
        path.mkdir(mode=0o700)
        phase(path, 'SEED_local', 'seed', 'local', 'baseline')
        phase(path, 'REFUSED_' + reason, 'refuse', reason)
    return len(scenarios) + len(refusals)


def main():
    parser = argparse.ArgumentParser(description='Captured playlist account authority over real JNI and files')
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--json', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['compile', 'run'], required=True)
    parser.add_argument('--java-runtime', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    java = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    receipt = json.loads(args.receipt.read_text())
    compiler = Path(receipt['compiler'])
    runtime = args.java_runtime or Path(os.environ.get('PURPLE_HOST_JAVA', str(compiler.with_name('java'))))
    if not runtime.is_file() or not os.access(runtime, os.X_OK):
        raise RuntimeError('Explicit host Java runtime is unavailable')
    sources = [java / name for name in ['PurplePlaylistsView.java', 'PurplePlaylistsEdits.java', 'PurplePlaylistsDownloadsCore.java', 'PurplePlaylistsRemoval.java', 'PurplePlaylistsWorker.java', 'PurplePlaylistsWorkers.java', 'PurplePlaylistsAccount.java', 'PurpleAccountFiles.java',
        'PurplePlaylistsStore.java', 'PurplePlaylistsCore.java', 'PurplePlaylistsLocalCore.java',
        'PurplePlaylistsPhysical.java', 'PurpleAccountFileSnapshot.java',
        'PurpleSyncRetainedBases.java', 'PurpleGlobalChoiceModel.java']]
    sources += [tests / 'playlists/PurplePlaylistsWorkerCases.java',
        tests / 'playlists/PurplePlaylistsEditCheckpointCases.java',
        tests / 'java_binding/org/telegram/messenger/purple/PurpleCore.java']
    ports = sorted((tests / 'playlists/account_ports').rglob('*.java'))
    if len(ports) != 13:
        raise RuntimeError('Captured account platform ports are incomplete')
    sources += ports
    inputs = [*sources, args.receipt, args.json, args.library, compiler, runtime, Path(__file__)]
    pins = {str(path.resolve()): digest(path) for path in inputs}
    classpath = os.pathsep.join([str(args.json), receipt['destination'], *[
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
            'org.telegram.messenger.purple.PurplePlaylistsWorkerCases']
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
        cold = args.output / 'cold-checkpoints'
        cold.mkdir(mode=0o700)
        print(f'PLAYLIST_EDIT_CHECKPOINT_COLD_CASES={cold_checkpoints(command, cold, repo, environment)}')
    if {str(path.resolve()): digest(path) for path in inputs} != pins:
        raise RuntimeError('Captured account inputs changed during the phase')


if __name__ == '__main__':
    main()
