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



def member(text, signature):
    start = text.index(signature)
    at = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[at] == '{') - (text[at] == '}')
        at += 1
    return text[start:at]


def bind_ports(ports, output, mc, audio):
    native = mc.read_text()
    wrapper = audio.read_text()
    blocks = {
        'bridge': member(native, 'public boolean purplePlaylistStart('),
        'release': member(native, 'public void purplePlaylistRelease('),
        'clear': member(native, 'private void clearPlaylist()'),
        'play': member(native, 'public boolean playMessage(final MessageObject messageObject, boolean silent)'),
        'history': member(native, 'public void loadMoreMusic()').splitlines()[1],
        'prefetch': member(native, 'private void checkIsNextMusicFileDownloaded(').splitlines()[1],
        'automatic': '\n'.join(member(wrapper, signature) for signature in [
            'public void automaticRun(', 'public boolean playlistAutomatic(', 'public boolean automaticContext()'])
    }
    for index, event in enumerate(['musicDidLoad', 'mediaDidLoad', 'musicListLoaded']):
        blocks['page' + str(index)] = native.split('} else if (id == NotificationCenter.' + event + ') {', 1)[1].splitlines()[1]
    result = []
    root = output / 'bound-ports'
    root.mkdir(mode=0o700)
    for port in ports:
        value = port.read_text()
        for key, replacement in blocks.items():
            value = re.sub(r'/\* EXTRACT:' + key + r' \*/.*?/\* END:' + key + r' \*/',
                           lambda match: replacement, value, flags=re.S)
        if '/* EXTRACT:' in value:
            raise RuntimeError('Unbound native player endpoint')
        # Preserve package paths without depending on filesystem traversal order.
        relative = port.parts[port.parts.index('player_ports') + 1:]
        target = root.joinpath(*relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)
        result.append(target)
    return result


def main():
    parser = argparse.ArgumentParser(description='Whole native playlist adapter and source-bound MediaController bridge with inert audio endpoints')
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
        'PurplePlaylistsResolver.java', 'PurplePlaylistsTelegram.java', 'PurplePlaylistsView.java', 'PurplePlaylistsPlayback.java', 'PurplePlaylistsPlayer.java', 'PurplePlaylistsLookAhead.java', 'PurpleSleepTimerRules.java']]
    sources.append(tests / 'playlists/PurplePlaylistsPlayerCases.java')
    ports = sorted((tests / 'playlists/player_ports').rglob('*.java'))
    if len(ports) != 12:
        raise RuntimeError('Resolver platform endpoints are incomplete')
    endpoint_sources = list(sources)
    sources += ports
    bound = [java.parent / 'MediaController.java', java / 'PurpleSleepTimerAudio.java']
    pins = {str(p.resolve()): digest(p) for p in [*sources, *bound, args.receipt, compiler, runtime, Path(__file__)]}
    classpath = os.pathsep.join([receipt['destination'], *[
        item['path'] for key in ['classpath', 'bootstrapClasspath'] for item in receipt[key]]])
    classes = args.output / 'classes'
    if args.phase == 'compile':
        args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
        classes.mkdir(mode=0o700)
        empty = args.output / 'empty-sourcepath'
        empty.mkdir(mode=0o700)
        generated = bind_ports(ports, args.output, bound[0], bound[1])
        command = [str(compiler), '-J-Xmx128m', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                   '-proc:none', '-sourcepath', str(empty), '-cp', classpath, '-d', str(classes), *map(str, [*endpoint_sources, *generated])]
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (args.output / 'compile.log').write_text(result.stdout)
        print(result.stdout, end='')
        if result.returncode:
            raise RuntimeError('Native player fixture compilation failed')
        (args.output / 'bound-port-pins.json').write_text(json.dumps({str(p): digest(p) for p in generated}, indent=2) + '\n')
        (args.output / 'input-pins.json').write_text(json.dumps(pins, indent=2) + '\n')
        artifacts = {str(p.relative_to(classes)): digest(p) for p in classes.rglob('*.class')}
        (args.output / 'class-pins.json').write_text(json.dumps(artifacts, indent=2) + '\n')
        return
    if json.loads((args.output / 'input-pins.json').read_text()) != pins:
        raise RuntimeError('Native player fixture inputs changed')
    for p, h in json.loads((args.output / 'bound-port-pins.json').read_text()).items():
        if digest(Path(p)) != h:
            raise RuntimeError('Bound native player endpoint changed')
    artifacts = json.loads((args.output / 'class-pins.json').read_text())
    if not artifacts or any(digest(classes / p) != h for p, h in artifacts.items()):
        raise RuntimeError('Native player compiled classes changed')
    result = subprocess.run([str(runtime), '-Xmx128m', '-Xcheck:jni', '-cp',
                             str(classes) + os.pathsep + classpath,
                             'org.telegram.messenger.purple.PurplePlaylistsPlayerCases'],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (args.output / 'run.log').write_text(result.stdout)
    (args.output / 'run-exit.json').write_text(json.dumps({'exit': result.returncode}) + '\n')
    print(result.stdout, end='')
    if result.returncode or re.search(r'(?i)\bwarning\b|fatal error|JNI DETECTED ERROR', result.stdout):
        raise RuntimeError('Native player fixture failed or emitted runtime diagnostics')
    if not re.search(r'(?m)^PLAYLIST_NATIVE_PLAYER_CHECKS=[1-9][0-9]*$', result.stdout):
        raise RuntimeError('Resolver completion counter missing')
    if {str(p.resolve()): digest(p) for p in [*sources, *bound, args.receipt, compiler, runtime, Path(__file__)]} != pins:
        raise RuntimeError('Native player fixture inputs changed during execution')
    if any(digest(classes / p) != h for p, h in artifacts.items()):
        raise RuntimeError('Native player compiled classes changed during execution')


if __name__ == '__main__':
    main()
