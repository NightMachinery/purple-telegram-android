#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description='Durable playlist publication journal and actual serialized account worker')
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--json', type=Path, required=True)
    parser.add_argument('--java-runtime', type=Path)
    parser.add_argument('--api', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['compile', 'run'], required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    receipt = json.loads(args.receipt.read_text())
    base = Path(receipt['destination'])
    runtime = args.java_runtime or Path(os.environ.get('PURPLE_HOST_JAVA', str(Path(receipt['compiler']).with_name('java'))))
    if not runtime.is_file() or not os.access(runtime, os.X_OK):
        raise RuntimeError('Host Java runtime unavailable')
    compiler = Path(receipt['compiler'])
    directory = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    sources = [directory / name for name in ['PurpleSyncClient.java', 'PurplePlaylistsSyncCore.java',
        'PurplePlaylistsSyncDiscovery.java', 'PurplePlaylistsSyncMetadata.java',
        'PurplePlaylistsSyncFiles.java', 'PurplePlaylistsSyncPublication.java', 'PurpleSyncChatUpload.java',
        'PurplePlaylistsPublicationJournal.java', 'PurplePlaylistsSyncCommit.java', 'PurplePlaylistsPhysical.java', 'PurplePlaylistsStore.java',
        'PurplePlaylistsCore.java', 'PurplePlaylistsLocalCore.java']]
    sources += [tests / 'playlists/PurplePlaylistsSyncPublicationCases.java',
        tests / 'playlists/PurplePlaylistsPublicationJournalCases.java', tests / 'playlists/PurplePlaylistsSyncDiscoveryCases.java',
        tests / 'java_binding/org/telegram/messenger/purple/PurpleCore.java']
    worker_sources = [directory / name for name in ['PurplePlaylistsView.java', 'PurplePlaylistsEdits.java', 'PurplePlaylistsDownloadsCore.java', 'PurplePlaylistsRemoval.java', 'PurplePlaylistsWorker.java', 'PurplePlaylistsWorkers.java', 'PurplePlaylistsAccount.java', 'PurpleAccountFiles.java', 'PurplePlaylistsStore.java', 'PurplePlaylistsCore.java', 'PurplePlaylistsLocalCore.java', 'PurplePlaylistsPhysical.java', 'PurpleAccountFileSnapshot.java', 'PurpleSyncRetainedBases.java', 'PurpleGlobalChoiceModel.java']]
    worker_sources += [tests / 'playlists/PurplePlaylistsWorkerCases.java',
        tests / 'playlists/PurplePlaylistsPublicationWorkerCases.java',
        tests / 'java_binding/org/telegram/messenger/purple/PurpleCore.java']
    ports = sorted((tests / 'playlists/account_ports').rglob('*.java'))
    if len(ports) != 13:
        raise RuntimeError('Captured account ports incomplete')
    worker_sources += ports
    classpath_inputs = [Path(item['path']) for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]]
    classpath = os.pathsep.join(map(str, [args.json, *([args.api] if args.api else []), base, *classpath_inputs]))
    inputs = [*sources, *worker_sources, args.library, args.receipt, args.json, compiler, runtime, Path(__file__)]
    pins = {str(path.resolve()): digest(path) for path in inputs}
    if args.api:
        pins.update({str(path.resolve()): digest(path) for path in sorted(args.api.rglob('*.class'))})
    classes = args.output / 'classes'
    if args.phase == 'compile':
        args.output.mkdir(parents=True, exist_ok=False)
        classes.mkdir()
        empty = args.output / 'empty-sourcepath'
        empty.mkdir()
        headers = args.output / 'jni-headers'
        headers.mkdir()
        command = [str(compiler), '-J-Xmx128m', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
            '-proc:none', '-sourcepath', str(empty), '-cp', classpath, '-d', str(classes),
            '-h', str(headers), *map(str, sources)]
    else:
        if json.loads((args.output / 'inputs.json').read_text()) != pins:
            raise RuntimeError('Playlist production or JNI inputs changed after compilation')
        compiled = {str(path.relative_to(classes)): digest(path) for path in sorted(classes.rglob('*.class'))}
        if json.loads((args.output / 'classes.json').read_text()) != compiled:
            raise RuntimeError('Compiled playlist fixtures changed')
        command = [str(runtime), '-Xcheck:jni', '-Xmx512m', '-XX:ActiveProcessorCount=2',
            '-Dfile.encoding=UTF-8', '-Dpurple.test.library=' + str(args.library.resolve()),
            '-cp', str(classes) + os.pathsep + classpath,
            'org.telegram.messenger.purple.PurplePlaylistsPublicationJournalCases', str(args.output / 'synthetic')]
    worker_classes = args.output / 'worker-classes'
    if args.phase == 'compile':
        worker_classes.mkdir()
        worker_command = [str(compiler), '-J-Xmx256m', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
            '-proc:none', '-sourcepath', str(empty), '-cp', str(classes) + os.pathsep + classpath,
            '-d', str(worker_classes), *map(str, worker_sources)]
    else:
        compiled_worker = {str(path.relative_to(worker_classes)): digest(path) for path in sorted(worker_classes.rglob('*.class'))}
        if json.loads((args.output / 'worker-classes.json').read_text()) != compiled_worker:
            raise RuntimeError('Worker classes changed')
        tmp = args.output / 'worker-synthetic'
        tmp.mkdir(exist_ok=True)
        worker_command = [str(runtime), '-Xcheck:jni', '-Xmx512m', '-XX:ActiveProcessorCount=2',
            '-Djava.io.tmpdir=' + str(tmp.resolve()), '-Dpurple.test.library=' + str(args.library.resolve()),
            '-cp', str(worker_classes) + os.pathsep + str(classes) + os.pathsep + classpath,
            'org.telegram.messenger.purple.PurplePlaylistsPublicationWorkerCases', str(tmp)]
    with (args.output / (args.phase + '.log')).open('x') as log:
        result = subprocess.run(command, cwd=repo, stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, timeout=180)
    (args.output / (args.phase + '-exit.json')).write_text(json.dumps({'exit': result.returncode}) + '\n')
    if result.returncode:
        print('Playlist bridge phase failed; inspect its finite log.')
        raise SystemExit(result.returncode)
    with (args.output / ('worker-' + args.phase + '.log')).open('x') as log:
        worker_result = subprocess.run(worker_command, cwd=repo, stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, timeout=180)
    (args.output / ('worker-' + args.phase + '-exit.json')).write_text(json.dumps({'exit': worker_result.returncode}) + '\n')
    if worker_result.returncode:
        raise RuntimeError('Publication worker phase failed')
    if args.phase == 'compile':
        (args.output / 'worker-classes.json').write_text(json.dumps({str(path.relative_to(worker_classes)): digest(path)
            for path in sorted(worker_classes.rglob('*.class'))}, indent=2) + '\n')
        (args.output / 'inputs.json').write_text(json.dumps(pins, indent=2) + '\n')
        compiled = {str(path.relative_to(classes)): digest(path) for path in sorted(classes.rglob('*.class'))}
        (args.output / 'classes.json').write_text(json.dumps(compiled, indent=2) + '\n')
    else:
        report = (args.output / 'run.log').read_text()
        import re
        if re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', report):
            raise RuntimeError('Checked JNI diagnostic')
        if not re.search(r'(?m)^PLAYLIST_PUBLICATION_JOURNAL_CHECKS=(\d+)$', report) or 'PLAYLIST_PUBLICATION_JOURNAL_OK' not in report:
            raise RuntimeError('Missing captured discovery completion proof')
        worker_report = (args.output / 'worker-run.log').read_text()
        if re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', worker_report):
            raise RuntimeError('Checked worker JNI diagnostic')
        if not re.search(r'(?m)^PLAYLIST_PUBLICATION_WORKER_CHECKS=(\d+)$', worker_report) or 'PLAYLIST_PUBLICATION_WORKER_OK' not in worker_report:
            raise RuntimeError('Missing publication worker proof')
        print(report, end='')
        print(worker_report, end='')


if __name__ == '__main__':
    main()
