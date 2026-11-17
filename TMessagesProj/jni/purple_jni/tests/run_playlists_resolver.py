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
    parser = argparse.ArgumentParser(description='Complete playlist resolver and Telegram adapter over actual wire classes')
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['compile', 'run'], required=True)
    parser.add_argument('--java-runtime', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    java = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    receipt = json.loads(args.receipt.read_text())
    compiler = Path(receipt['compiler'])
    runtime = args.java_runtime or compiler.with_name('java')
    if not runtime.is_file() or not os.access(runtime, os.X_OK):
        raise RuntimeError('Host Java runtime is unavailable')
    sources = [java / name for name in [
        'PurplePlaylistsResolver.java', 'PurplePlaylistsTelegram.java', 'PurplePlaylistsView.java']]
    sources.append(tests / 'playlists/PurplePlaylistsResolverCases.java')
    ports = sorted((tests / 'playlists/resolver_ports').rglob('*.java'))
    if len(ports) != 4:
        raise RuntimeError('Resolver platform endpoints are incomplete')
    sources += ports
    pins = {str(p.resolve()): digest(p) for p in [*sources, args.receipt, compiler, runtime, Path(__file__)]}
    classpath = os.pathsep.join([receipt['destination'], *[
        item['path'] for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]]])
    classes = args.output / 'classes'
    if args.phase == 'compile':
        args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
        classes.mkdir(mode=0o700)
        empty = args.output / 'empty-sourcepath'
        empty.mkdir(mode=0o700)
        command = [str(compiler), '-J-Xmx128m', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                   '-proc:none', '-sourcepath', str(empty), '-cp', classpath, '-d', str(classes), *map(str, sources)]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (args.output / 'compile.log').write_text(result.stdout)
        print(result.stdout, end='')
        if result.returncode:
            raise RuntimeError('Complete resolver fixture compilation failed')
        (args.output / 'input-pins.json').write_text(json.dumps(pins, indent=2) + '\n')
        artifacts = {str(p.relative_to(classes)): digest(p) for p in classes.rglob('*.class')}
        (args.output / 'class-pins.json').write_text(json.dumps(artifacts, indent=2) + '\n')
        return
    if json.loads((args.output / 'input-pins.json').read_text()) != pins:
        raise RuntimeError('Resolver fixture inputs changed')
    artifacts = json.loads((args.output / 'class-pins.json').read_text())
    if not artifacts or any(digest(classes / p) != h for p, h in artifacts.items()):
        raise RuntimeError('Resolver compiled classes changed')
    result = subprocess.run([str(runtime), '-Xmx128m', '-Xcheck:jni', '-cp',
                             str(classes) + os.pathsep + classpath,
                             'org.telegram.messenger.purple.PurplePlaylistsResolverCases'],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (args.output / 'run.log').write_text(result.stdout)
    (args.output / 'run-exit.json').write_text(json.dumps({'exit': result.returncode}) + '\n')
    print(result.stdout, end='')
    if result.returncode or re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', result.stdout):
        raise RuntimeError('Resolver fixture failed or emitted runtime diagnostics')
    if not re.search(r'(?m)^PLAYLIST_RESOLVER_CHECKS=[1-9][0-9]*$', result.stdout):
        raise RuntimeError('Resolver completion counter missing')
    if {str(p.resolve()): digest(p) for p in [*sources, args.receipt, compiler, runtime, Path(__file__)]} != pins:
        raise RuntimeError('Resolver fixture inputs changed during execution')
    if any(digest(classes / p) != h for p, h in artifacts.items()):
        raise RuntimeError('Resolver compiled classes changed during execution')


if __name__ == '__main__':
    main()
