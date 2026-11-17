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
    parser = argparse.ArgumentParser(description='Shared playlist record/codec/inventory/step JNI boundary')
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--json', type=Path, required=True)
    parser.add_argument('--java-runtime', type=Path)
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
    source = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple/PurplePlaylistsSyncCore.java'
    sources = [source, tests / 'playlists/PurplePlaylistsSyncCases.java',
        tests / 'java_binding/org/telegram/messenger/purple/PurpleCore.java']
    classpath_inputs = [Path(item['path']) for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]]
    classpath = os.pathsep.join(map(str, [args.json, base, *classpath_inputs]))
    inputs = [*sources, args.library, args.receipt, args.json, compiler, runtime, Path(__file__)]
    pins = {str(path.resolve()): digest(path) for path in inputs}
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
            'org.telegram.messenger.purple.PurplePlaylistsSyncCases']
    with (args.output / (args.phase + '.log')).open('x') as log:
        result = subprocess.run(command, cwd=repo, stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, timeout=180)
    (args.output / (args.phase + '-exit.json')).write_text(json.dumps({'exit': result.returncode}) + '\n')
    if result.returncode:
        print('Playlist bridge phase failed; inspect its finite log.')
        raise SystemExit(result.returncode)
    if args.phase == 'compile':
        (args.output / 'inputs.json').write_text(json.dumps(pins, indent=2) + '\n')
        compiled = {str(path.relative_to(classes)): digest(path) for path in sorted(classes.rglob('*.class'))}
        (args.output / 'classes.json').write_text(json.dumps(compiled, indent=2) + '\n')
    else:
        report = (args.output / 'run.log').read_text()
        import re
        if re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', report):
            raise RuntimeError('Checked JNI diagnostic')
        if not re.search(r'(?m)^PLAYLIST_SYNC_CORE_CHECKS=(\d+)$', report) or 'PLAYLIST_SYNC_CORE_OK' not in report:
            raise RuntimeError('Missing shared sync completion proof')
        print(report, end='')


if __name__ == '__main__':
    main()
