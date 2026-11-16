#!/usr/bin/env python3
"""Exercise sleep timer rules and source-bound Saved Music refresh and deferred Play intent."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess


def member(source, signature):
    if source.count(signature) != 1:
        raise ValueError('Expected one production member: ' + signature)
    start = source.index(signature)
    if signature.endswith(';'):
        return source[start:start + len(signature)]
    opening = source.index('{', start)
    depth = 0
    state = 'code'
    escaped = False
    index = opening
    while index < len(source):
        char = source[index]
        following = source[index:index + 2]
        if state == 'line':
            if char == '\n':
                state = 'code'
        elif state == 'comment':
            if following == '*/':
                state = 'code'
                index += 1
        elif state in ('"', "'"):
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == state:
                state = 'code'
        elif following == '//':
            state = 'line'
            index += 1
        elif following == '/*':
            state = 'comment'
            index += 1
        elif char in ('"', "'"):
            state = char
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
        index += 1
    raise ValueError('Unclosed production member: ' + signature)


# The fixtures declare the timer facade in their own package, so the one fully qualified
# upstream call into it must name that copy instead.
QUALIFIED_TIMER = 'org.telegram.messenger.purple.PurpleSleepTimer.get()'


def bound(kind, text):
    if kind in ('chat', 'native'):
        if text.count(QUALIFIED_TIMER) != 1:
            raise ValueError('Expected one qualified timer call in the timestamp or native Play member')
        text = text.replace(QUALIFIED_TIMER, 'PurpleSleepTimer.get()')
    if kind == 'media':
        text = text.replace('org.telegram.messenger.purple.PurpleSleepTimerAudio.', 'PurpleSleepTimerAudio.')
    return text


def fixture_source(template, production):
    def replace(match):
        text = member(production[match[1]], match[2].strip())
        if match[1] == 'media':
            for name in re.findall(r'(?<![\w.])Purple[A-Z]\w*', text):
                if 'import org.telegram.messenger.purple.' + name + ';' not in production['media']:
                    raise ValueError('Unqualified Purple class without a production import: ' + name)
        return bound(match[1], text)
    result = re.sub(r'(?m)^\s*//@EXTRACT (timer|audio|media|helper|session|chat|native) ([^\n]+)$', replace, template)
    if re.search(r'(?m)^\s*//@EXTRACT', result):
        raise ValueError('Unresolved production member')
    return result

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--phase', choices=['compile', 'run'], required=True)
parser.add_argument('--compile-wrapper', default='')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
owned = root / 'TMessagesProj/src/main/java/org/telegram/messenger/purple'
sources = [owned / 'PurpleSleepTimerRules.java', owned / 'PurpleSleepTimerSavedSong.java', owned / 'PurpleSleepTimerViewerIdentity.java', owned / 'PurpleSleepTimerViewerSelection.java',
           Path(__file__).parent / 'sleep_timer/PurpleSleepTimerRulesCases.java',
           Path(__file__).parent / 'sleep_timer/PurpleSleepTimerPlayRequestCases.java',
           root / 'TMessagesProj/src/main/java/org/telegram/ui/PurpleEmbeddedPlaybackPort.java',
           Path(__file__).parent / 'sleep_timer/PurpleEmbeddedPlaybackPortCases.java']
templates = [Path(__file__).parent / 'sleep_timer' / name for name in
             ['PurpleSleepTimerSavedRefreshCases.java.in', 'PurpleSleepTimerDeferredCases.java.in', 'PurpleSleepTimerProximityCases.java.in']]
production = {
    'timer': owned / 'PurpleSleepTimer.java',
    'audio': owned / 'PurpleSleepTimerAudio.java',
    'media': owned.parent / 'MediaController.java',
    'helper': owned / 'PurpleSleepTimerSession.java',
    'session': owned.parent / 'TelegramMediaSession.java',
    'chat': root / 'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java',
    'native': root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/VideoPlayer.java',
}
java_home = Path(os.environ['JAVA_HOME'])
classes = args.output / 'classes'
pins = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in [*sources, *production.values(), *templates, Path(__file__)]}
production_text = {name: path.read_text() for name, path in production.items()}
generated = {args.output / 'generated' / template.name[:-len('.in')]: fixture_source(template.read_text(), production_text)
             for template in templates}
if args.phase == 'compile':
    args.output.mkdir(parents=True, exist_ok=False)
    classes.mkdir()
    (args.output / 'generated').mkdir()
    for path, text in generated.items():
        path.write_text(text)
    command = [*shlex.split(args.compile_wrapper), str(java_home / 'bin/javac'),
               '-J-Xmx128m', '-encoding', 'UTF-8', '-d', str(classes), *map(str, [*sources, *generated])]
    (args.output / 'compile-command.json').write_text(json.dumps(command, indent=2) + '\n')
    with (args.output / 'compile.log').open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
    (args.output / 'compile-exit.json').write_text(json.dumps({'exit': result.returncode}) + '\n')
    if result.returncode:
        raise SystemExit(result.returncode)
    (args.output / 'compiled-sources.json').write_text(json.dumps(pins, indent=2) + '\n')
else:
    if json.loads((args.output / 'compiled-sources.json').read_text()) != pins:
        raise SystemExit('Production sources changed after compilation')
    if any(path.read_text() != text for path, text in generated.items()):
        raise SystemExit('Generated production members changed after compilation')
    for label, main_class in [
        ('rules', 'org.telegram.messenger.purple.PurpleSleepTimerRulesCases'),
        ('saved-refresh', 'org.telegram.messenger.purple.savedrefresh.PurpleSleepTimerSavedRefreshCases'),
        ('play-request', 'org.telegram.messenger.purple.PurpleSleepTimerPlayRequestCases'),
        ('embedded-port', 'org.telegram.ui.PurpleEmbeddedPlaybackPortCases'),
        ('deferred', 'org.telegram.messenger.purple.deferred.PurpleSleepTimerDeferredCases'),
        ('proximity', 'org.telegram.messenger.purple.proximity.PurpleSleepTimerProximityCases'),
    ]:
        command = [str(java_home / 'bin/java'), '-Xmx64m', '-Dfile.encoding=UTF-8', '-cp', str(classes), main_class]
        (args.output / (label + '-run-command.json')).write_text(json.dumps(command, indent=2) + '\n')
        with (args.output / (label + '.log')).open('w') as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
        (args.output / (label + '-run-exit.json')).write_text(json.dumps({'exit': result.returncode}) + '\n')
        print((args.output / (label + '.log')).read_text(), end='')
        if result.returncode:
            raise SystemExit(result.returncode)
