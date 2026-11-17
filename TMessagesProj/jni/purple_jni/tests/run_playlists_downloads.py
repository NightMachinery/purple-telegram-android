#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
from run_playlists_physical import native_flags
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description='Real shared-core playlist local-state JNI boundary cases')
    parser.add_argument('--json', type=Path, required=True)
    parser.add_argument('--java-runtime', type=Path)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['compile', 'run'], required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    receipt = json.loads(args.receipt.read_text())
    base = Path(receipt['destination'])
    compiler = Path(receipt['compiler'])
    java = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    source = java / 'PurplePlaylistsDownloadsCore.java'
    runtime = args.java_runtime or Path(os.environ.get('PURPLE_HOST_JAVA', str(compiler.with_name('java'))))
    core = repo / 'TMessagesProj/jni/purple'
    fixture = core / 'tests/test_playlist_downloads.cpp'
    native = args.output / 'playlist-downloads-core-cases'
    sources = [source, java / 'PurplePlaylistsLocalCore.java', tests / 'playlists/PurplePlaylistsDownloadsCases.java',
        tests / 'java_binding/org/telegram/messenger/purple/PurpleCore.java']
    classpath_inputs = [Path(item['path']) for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]]
    classpath = os.pathsep.join(map(str, [base, args.json, *classpath_inputs]))
    inputs = [*sources, fixture, args.library, args.receipt, args.json, compiler, runtime, Path(__file__),
        tests / 'run_playlists_physical.py', *[core / ('purple/purple_playlists_download_' + kind + ext)
        for kind in ['state', 'job'] for ext in ['.cpp', '.h']]]
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
        command = [str(runtime), '-Xcheck:jni', '-Xmx256m', '-XX:ActiveProcessorCount=2',
            '-Djava.io.tmpdir=' + str(args.output.resolve()), '-Dfile.encoding=UTF-8', '-Dpurple.test.library=' + str(args.library.resolve()),
            '-cp', str(classes) + os.pathsep + classpath,
            'org.telegram.messenger.purple.PurplePlaylistsDownloadsCases']
    with (args.output / (args.phase + '.log')).open('x') as log:
        result = subprocess.run(command, cwd=repo, stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, timeout=180)
    (args.output / (args.phase + '-exit.json')).write_text(json.dumps({'exit': result.returncode}) + '\n')
    if result.returncode:
        print('Playlist bridge phase failed; inspect its finite log.')
        raise SystemExit(result.returncode)
    if args.phase == 'compile':
        cxx = os.environ.get('CXX', 'clang++')
        argv = [cxx, '-std=c++20', '-O0', '-I' + str(core), str(fixture),
            *native_flags(args.library.resolve()), '-o', str(native)]
        with (args.output / 'native-compile.log').open('x') as log:
            subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                check=True, timeout=180)
        (args.output / 'native-pin.json').write_text(json.dumps({'sha256': digest(native)}) + '\n')
        (args.output / 'inputs.json').write_text(json.dumps(pins, indent=2) + '\n')
        compiled = {str(path.relative_to(classes)): digest(path) for path in sorted(classes.rglob('*.class'))}
        (args.output / 'classes.json').write_text(json.dumps(compiled, indent=2) + '\n')
    else:
        if digest(native) != json.loads((args.output / 'native-pin.json').read_text())['sha256']:
            raise RuntimeError('Compiled native download fixture changed')
        log = (args.output / 'run.log').read_text()
        if re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', log):
            raise RuntimeError('JNI runtime diagnostics')
        print(log, end='')
        result = subprocess.run([str(native)], capture_output=True, text=True, timeout=180)
        (args.output / 'native-run.log').write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('Native shared download fixture failed')
        print(result.stdout, end='')


if __name__ == '__main__':
    main()
