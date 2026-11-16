"""Exercise literal ordinary bin-send methods with delayed account and copy ports."""
import os
from pathlib import Path
import re
import shutil
import subprocess


SIGNATURES = (
    '    public static void send(',
    '    public interface Done {',
    '    private static boolean sendVoice(',
    '    private static boolean sendRound(',
)


def member(source, signature):
    if source.count(signature) != 1:
        raise ValueError('expected one send member: ' + signature.strip())
    start = source.index(signature)
    opening = source.index('{', start)
    tokens = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/|[{}]', re.S)
    depth = 0
    for token in tokens.finditer(source, opening):
        if token.group() == '{':
            depth += 1
        elif token.group() == '}':
            depth -= 1
            if depth == 0:
                return source[start:token.end()]
    raise ValueError('unterminated send member: ' + signature.strip())


def prepare(root, output):
    tests = Path(__file__).parent
    output.mkdir()
    source = (root / 'TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleRecordingSend.java').read_text()
    route = 'package org.telegram.messenger.purple;\nimport org.telegram.messenger.*;import org.telegram.tgnet.*;import java.io.*;\npublic class OrdinaryRoute{\n'
    route += '\n'.join(member(source, signature) for signature in SIGNATURES)
    route += '\n private static void sendPendingRound(Object... args){throw new AssertionError("pending route outside this fixture");}\n}\n'
    for port in (tests / 'ordinary-host').rglob('*.java'):
        dest = output / port.relative_to(tests / 'ordinary-host')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(port, dest)
    dest = output / 'org/telegram/messenger/purple/OrdinaryRoute.java'
    dest.write_text(route)
    return sorted(output.rglob('*.java'))


def run(root, output):
    sources = prepare(root, output)
    compiler = Path(os.environ['JAVA_HOME']) / 'bin/javac'
    classes = output / 'classes'
    subprocess.run([str(compiler), '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([str(compiler.with_name('java')), '-cp', str(classes),
        'org.telegram.messenger.purple.OrdinarySnapshotCases', str(output / 'files')], check=True)
