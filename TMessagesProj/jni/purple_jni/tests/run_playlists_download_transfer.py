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


def retained_hooks(source):
    text = source.read_text()
    signature = '    public void deleteFiles(final ArrayList<File> files, final int type) {'
    start = text.index(signature); at = text.index('{', start) + 1; depth = 1
    while depth:
        depth += (text[at] == '{') - (text[at] == '}'); at += 1
    deletion = text[start:at].replace('FileLoader.getInternalCacheDir()', 'getInternalCacheDir()')
    signature = '    public File getPathToAttach(TLObject attach, String size, String ext, boolean forceCache, boolean useFileDatabaseQueue) {'
    hook = text.split(signature, 1)[1].split('        File dir = null;', 1)[0]
    start = text.index('                if (!TextUtils.isEmpty(document.localPath)) {', text.index(signature))
    end = text.index('                if (document.key != null)', start)
    explicit = text[start:end].replace('!TextUtils.isEmpty(document.localPath)',
            'document.localPath != null && !document.localPath.isEmpty()')
    return ("package org.telegram.messenger.purple;\n"
        "import java.io.File; import java.util.ArrayList; import org.telegram.tgnet.TLObject; import org.telegram.tgnet.TLRPC;\n"
        "import org.telegram.messenger.FileLog; import org.telegram.messenger.ImageLoader;\n"
        "final class RetainedNativeHooks {\n"
        "int currentAccount; File cache;\n"
        "static final class Queue { void postRunnable(Runnable action) { action.run(); } }\n"
        "final Queue fileLoaderQueue = new Queue(); File getInternalCacheDir() { return cache; }\n"
        "File lookup(TLObject attach, boolean forceCache, File fallback) {\n" + hook
        + "if (!forceCache && attach instanceof TLRPC.Document) { TLRPC.Document document = (TLRPC.Document) attach;\n"
        + explicit + "} return fallback; }\n"
        + deletion + "\n}\n")


def main():
    parser = argparse.ArgumentParser(description='Owned download requests and durable inventory on the actual account worker')
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--json', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--phase', choices=['compile', 'run'], required=True)
    parser.add_argument('--java-runtime', type=Path)
    parser.add_argument('--cache-source', type=Path, help=argparse.SUPPRESS)
    parser.add_argument('--downloads-source', type=Path, help=argparse.SUPPRESS)
    parser.add_argument('--scenario', choices=['full', 'removal', 'cache', 'capacity', 'registration', 'registration-cancel', 'opaque', 'policy', 'controls', 'cancel', 'authority', 'transfer'], default='full')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[4]
    tests = Path(__file__).resolve().parent
    java = repo / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    receipt = json.loads(args.receipt.read_text())
    compiler = Path(receipt['compiler'])
    runtime = args.java_runtime or Path(os.environ.get('PURPLE_HOST_JAVA', str(compiler.with_name('java'))))
    if not runtime.is_file() or not os.access(runtime, os.X_OK):
        raise RuntimeError('Explicit host Java runtime is unavailable')
    sources = [java / name for name in ['PurplePlaylistsDownloadControls.java', 'PurplePlaylistsCache.java', 'PurplePlaylistsRetained.java', 'PurplePlaylistsDownloadsCore.java', 'PurplePlaylistsDownloadJournal.java', 'PurplePlaylistsDownloadTransfer.java', 'PurplePlaylistsDownloads.java', 'PurplePlaylistsDownloadsTelegram.java', 'PurplePlaylistsResolver.java', 'PurplePlaylistsTelegram.java', 'PurplePlaylistsView.java', 'PurplePlaylistsEdits.java', 'PurplePlaylistsRemoval.java', 'PurplePlaylistsWorker.java', 'PurplePlaylistsWorkers.java', 'PurplePlaylistsAccount.java', 'PurpleAccountFiles.java',
        'PurplePlaylistsStore.java', 'PurplePlaylistsCore.java', 'PurplePlaylistsLocalCore.java',
        'PurplePlaylistsPhysical.java', 'PurpleAccountFileSnapshot.java',
        'PurpleSyncRetainedBases.java', 'PurpleGlobalChoiceModel.java']]
    if args.cache_source is not None:
        if args.cache_source.name != 'PurplePlaylistsCache.java':
            raise RuntimeError('Cache override must name the actual class source')
        sources[sources.index(java / 'PurplePlaylistsCache.java')] = args.cache_source
    if args.downloads_source is not None:
        if args.downloads_source.name != 'PurplePlaylistsDownloads.java':
            raise RuntimeError('Downloads override must name the actual class source')
        sources[sources.index(java / 'PurplePlaylistsDownloads.java')] = args.downloads_source
    sources += [tests / 'playlists/PurplePlaylistsDownloadCases.java',
        tests / 'java_binding/org/telegram/messenger/purple/PurpleCore.java']
    ports = sorted((tests / 'playlists/download_ports').rglob('*.java'))
    if len(ports) != 19:
        raise RuntimeError('Captured account platform ports are incomplete')
    sources += ports
    sources.append(tests / 'playlists/account_ports/org/telegram/messenger/purple/PurplePlaylistsSyncRuntime.java')
    native_source = java.parent / 'FileLoader.java'
    generated = args.output / 'RetainedNativeHooks.java'
    bound_text = retained_hooks(native_source)
    if args.phase == 'compile':
        args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
        generated.write_text(bound_text)
    elif generated.read_text() != bound_text:
        raise RuntimeError('FileLoader hook binding changed')
    sources.append(generated)
    inputs = [*sources, native_source, args.receipt, args.json, args.library, compiler, runtime, Path(__file__)]
    pins = {str(path.resolve()): digest(path) for path in inputs}
    classpath = os.pathsep.join([str(args.json), receipt['destination'], *[
        item['path'] for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]]])
    classes = args.output / 'classes'
    if args.phase == 'compile':
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
            'org.telegram.messenger.purple.PurplePlaylistsDownloadCases']
    if args.phase == 'run' and args.scenario != 'full': command.append(args.scenario)
    environment = dict(os.environ)
    for key in list(environment):
        if key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS') or key.startswith('DYLD_'):
            environment.pop(key)
    log = args.output / (args.phase + ('' if args.scenario == 'full' else '-' + args.scenario) + '.log')
    timeout = 600 if args.phase == 'run' and args.scenario == 'full' else 240
    with log.open('x') as handle:
        result = subprocess.run(command, cwd=repo, env=environment, stdin=subprocess.DEVNULL,
            stdout=handle, stderr=subprocess.STDOUT, timeout=timeout)
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
        if args.scenario == 'full':
            for mode in ['success', 'overlap', 'inode', 'cleanup']:
                handoff = args.output / 'fixtures' / ('restart-' + mode + '.json')
                for stage, tail, expected in [('seed', ['seed', str(handoff)], 86),
                        ('recover', ['recover', str(handoff), mode], 0)]:
                    child_log = args.output / ('restart-' + mode + '-' + stage + '.log')
                    with child_log.open('x') as output:
                        child = subprocess.run([*command, *([('cleanup-' + tail[0]), tail[1]] if mode == 'cleanup' else tail)], cwd=repo, env=environment,
                            stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, timeout=90)
                    report = child_log.read_text()
                    if child.returncode != expected or re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', report):
                        raise RuntimeError('Cold process ' + stage + '/' + mode + ' failed: ' + str(child.returncode))
                    print(report, end='')
    if {str(path.resolve()): digest(path) for path in inputs} != pins:
        raise RuntimeError('Captured account inputs changed during the phase')


if __name__ == '__main__':
    main()
