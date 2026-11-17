#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def native_flags(library):
    if platform.system() != 'Darwin':
        flags = shlex.split(subprocess.check_output(
            ['pkg-config', '--cflags', '--libs', 'Qt6Core'], text=True, timeout=20))
        return [str(library), '-Wl,-rpath,' + str(library.parent), *flags]
    metadata = subprocess.check_output(['/usr/bin/otool', '-l', str(library)], text=True, timeout=20)
    roots = [Path(value) for value in re.findall(
        r'cmd LC_RPATH\s+cmdsize \d+\s+path (.*?) \(offset \d+\)', metadata)]
    roots = sorted(set(path for path in roots if path.is_absolute()
        and (path / 'QtCore.framework/Headers/QCoreApplication').is_file()))
    if len(roots) != 1:
        raise RuntimeError('The current host library must name exactly one QtCore framework root')
    qt = roots[0]
    return [str(library), '-I' + str(qt / 'QtCore.framework/Headers'), '-F' + str(qt),
        '-framework', 'QtCore', '-Wl,-rpath,' + str(qt), '-Wl,-rpath,' + str(library.parent)]


def main():
    parser = argparse.ArgumentParser(description='Actual playlist file engine and Java/JNI snapshot cases')
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['compile', 'run'], required=True)
    parser.add_argument('--java-runtime', type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    java = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    native = tests.parent
    sources = [java / name for name in ['PurplePlaylistsPhysical.java', 'PurplePlaylistsStore.java',
        'PurplePlaylistsCore.java', 'PurplePlaylistsLocalCore.java']]
    sources += [tests / 'playlists/PurplePlaylistsMediaCases.java', tests / 'playlists/PurplePlaylistsPhysicalCases.java',
        tests / 'java_binding/org/telegram/messenger/purple/PurpleCore.java']
    fixture = tests / 'playlists/playlist_files_cases.cpp'
    fault_fixture = tests / 'playlists/playlist_files_fault_cases.cpp'
    media_fixture = tests / 'playlists/playlist_media_files_cases.cpp'
    inputs = [media_fixture, *sources, fixture, fault_fixture, native / 'playlists_files.h', native / 'playlists_files.cpp',
        native / 'playlists_files_bridge.cpp', args.receipt, args.library, Path(__file__)]
    pins = {str(path.resolve()): digest(path) for path in inputs}
    receipt = json.loads(args.receipt.read_text())
    compiler = Path(receipt['compiler'])
    java_runtime = args.java_runtime or Path(os.environ.get('PURPLE_HOST_JAVA', str(compiler.with_name('java'))))
    if not java_runtime.is_file() or not os.access(java_runtime, os.X_OK):
        raise RuntimeError('Explicit host Java runtime is unavailable')
    pins[str(java_runtime.resolve())] = digest(java_runtime)
    classpath = os.pathsep.join([receipt['destination'], *[
        item['path'] for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]]])
    classes = args.output / 'classes'
    executable = args.output / 'playlist-files-cases'
    fault_executable = args.output / 'playlist-files-fault-cases'
    fault_subject = args.output / 'playlist_files_fault_subject.inc'
    media_subject = args.output / 'playlist_media_subject.inc'
    media_executable = args.output / 'playlist-media-cases'
    if args.phase == 'compile':
        args.output.mkdir(exist_ok=False, parents=True)
        classes.mkdir()
        empty = args.output / 'empty-sourcepath'
        empty.mkdir()
        headers = args.output / 'jni-headers'
        headers.mkdir()
        cxx = shutil.which('clang++') or shutil.which('c++')
        if not cxx:
            raise RuntimeError('No native C++ compiler found')
        subject = (native / 'playlists_files.cpp').read_text()
        for before, after in [(' ::write(', ' ::PlaylistFaultWrite('),
                (' ::read(', ' ::PlaylistFaultRead('), ('fsync(', 'PlaylistFaultSync('),
                ('renameat(', 'PlaylistFaultRename('), ('unlinkat(', 'PlaylistFaultUnlink(')]:
            if before not in subject:
                raise RuntimeError('Native fault fixture lost its syscall binding')
            subject = subject.replace(before, after)
        fault_subject.write_text(subject)
        media_text = (native / 'playlists_files.cpp').read_text()
        for before, after in [('pwrite(', 'MediaFaultWrite('), ('pread(', 'MediaFaultRead('), ('fsync(', 'MediaFaultSync('), ('renameatx_np(', 'MediaFaultRename('), ('syscall(SYS_renameat2,', 'MediaFaultSyscall(SYS_renameat2,'), ('unlinkat(', 'MediaFaultUnlink(')]:
            if before not in media_text: raise RuntimeError('Media syscall binding missing')
            media_text = media_text.replace(before, after)
        media_subject.write_text(media_text)
        commands = [
            ('javac', [str(compiler), '-J-Xmx128m', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                '-proc:none', '-sourcepath', str(empty), '-cp', classpath, '-d', str(classes),
                '-h', str(headers), *map(str, sources)]),
            ('native-compile', [cxx, '-std=c++20', '-O0', '-pthread', str(fixture),
                *native_flags(args.library.resolve()), '-o', str(executable)]),
            ('native-fault-compile', [cxx, '-std=c++20', '-O0', '-pthread', str(fault_fixture),
                '-I' + str(native), '-I' + str(args.output),
                *native_flags(args.library.resolve())[1:], '-o', str(fault_executable)]),
            ('media-compile', [cxx, '-std=c++20', '-O0', '-pthread', str(media_fixture),
                '-I' + str(native), '-I' + str(args.output),
                *native_flags(args.library.resolve())[1:], '-o', str(media_executable)])]
    else:
        if json.loads((args.output / 'inputs.json').read_text()) != pins:
            raise RuntimeError('Playlist physical source or library changed after compilation')
        artifacts = {str(path.relative_to(args.output)): digest(path)
            for path in [*sorted(classes.rglob('*.class')), executable, fault_executable, fault_subject, media_executable, media_subject]}
        if json.loads((args.output / 'artifacts.json').read_text()) != artifacts:
            raise RuntimeError('Compiled physical fixtures changed')
        commands = [
            ('jni', [str(java_runtime), '-Xcheck:jni', '-Xmx512m', '-XX:ActiveProcessorCount=2',
                '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + str(args.output.resolve()), '-Dpurple.test.library=' + str(args.library.resolve()),
                '-cp', str(classes) + os.pathsep + classpath,
                'org.telegram.messenger.purple.PurplePlaylistsPhysicalCases']),
            ('media-jni', [str(java_runtime), '-Xcheck:jni', '-Xmx128m', '-XX:ActiveProcessorCount=2',
                '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + str(args.output.resolve()),
                '-Dpurple.test.library=' + str(args.library.resolve()), '-cp', str(classes) + os.pathsep + classpath,
                'org.telegram.messenger.purple.PurplePlaylistsMediaCases']),
            ('native', [str(executable.resolve())]),
            ('native-fault', [str(fault_executable.resolve())]),
            ('media', [str(media_executable.resolve())])]
    for name, command in commands:
        log = args.output / (name + '.log')
        with log.open('x') as handle:
            result = subprocess.run(command, cwd=repo, stdin=subprocess.DEVNULL,
                stdout=handle, stderr=subprocess.STDOUT, timeout=240)
        (args.output / (name + '-exit.json')).write_text(json.dumps({'exit': result.returncode}) + '\n')
        if result.returncode:
            raise RuntimeError(name + ' failed; inspect its finite log')
        if args.phase == 'run':
            content = log.read_text()
            if re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', content):
                raise RuntimeError('The physical fixture reported a JNI diagnostic')
            print(content, end='')
    if args.phase == 'compile':
        (args.output / 'inputs.json').write_text(json.dumps(pins, indent=2) + '\n')
        artifacts = {str(path.relative_to(args.output)): digest(path)
            for path in [*sorted(classes.rglob('*.class')), executable, fault_executable, fault_subject, media_executable, media_subject]}
        (args.output / 'artifacts.json').write_text(json.dumps(artifacts, indent=2) + '\n')
    if {str(path.resolve()): digest(path) for path in [*inputs, java_runtime]} != pins:
        raise RuntimeError('Playlist physical inputs changed during the phase')


if __name__ == '__main__':
    main()
