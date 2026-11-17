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
    parser = argparse.ArgumentParser(description='Whole production default writer, editor and shared settings against synthetic files')
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--json', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['compile', 'run'], required=True)
    parser.add_argument('--java-runtime', type=Path)
    parser.add_argument('--writer-source', type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    java = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    receipt = json.loads(args.receipt.read_text())
    compiler = Path(receipt['compiler'])
    runtime = args.java_runtime or Path(os.environ.get('PURPLE_HOST_JAVA', str(compiler.with_name('java'))))
    if not runtime.is_file() or not os.access(runtime, os.X_OK):
        raise RuntimeError('Explicit host Java runtime is unavailable')
    sources = [java / (name + '.java') for name in ['PurpleWriter', 'PurpleAccountSettings', 'PurpleAccountFileSnapshot', 'PurpleRoutedWriteModel', 'PurpleAccountEditorModel', 'PurpleGlobalChoiceFiles', 'PurpleEditorDirectories']]
    if args.writer_source is not None:
        if args.writer_source.name != 'PurpleWriter.java':
            raise RuntimeError('Writer override must name the actual class source')
        sources[0] = args.writer_source
    context = (java / 'PurpleGlobalAccountChoice.java').read_text()
    start = context.index('public static final class Session {')
    end = context.index('        public Choice preview(', start)
    if context[start:end] not in (tests / 'playlists/defaults_ports/org/telegram/messenger/purple/PurpleGlobalAccountChoice.java').read_text():
        raise RuntimeError('Source-bound global Session differs from production')
    sources += [tests / 'playlists/PurplePlaylistsDefaultWriterCases.java', *sorted((tests / 'playlists/defaults_ports').rglob('*.java'))]
    inputs = [*sources, java / 'PurpleGlobalAccountChoice.java', args.receipt, args.json, args.library, compiler, runtime, Path(__file__)]
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
            raise RuntimeError('Captured shared-settings inputs changed after compilation')
        artifacts = {str(path.relative_to(classes)): digest(path) for path in sorted(classes.rglob('*.class'))}
        if json.loads((args.output / 'classes.json').read_text()) != artifacts:
            raise RuntimeError('Compiled account fixtures changed')
        command = [str(runtime), '-Xcheck:jni', '-Xmx512m', '-XX:ActiveProcessorCount=2',
            '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + str((args.output / 'fixtures').resolve()),
            '-Dpurple.test.library=' + str(args.library.resolve()),
            '-Dscratch=' + str((args.output / 'fixtures').resolve()),
            '-cp', str(classes) + os.pathsep + classpath,
            '-Djava.library.path=' + str(args.library.resolve().parent), 'org.telegram.messenger.purple.PurplePlaylistsDefaultWriterCases', str(classes.resolve())]
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
    if {str(path.resolve()): digest(path) for path in inputs} != pins:
        raise RuntimeError('Captured account inputs changed during the phase')


if __name__ == '__main__':
    main()
