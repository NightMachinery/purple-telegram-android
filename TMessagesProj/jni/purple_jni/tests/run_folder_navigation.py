#!/usr/bin/env python3
"""Exercise the current folder navigation source over inert Android ports."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--phase', choices=['all', 'compile', 'run'], default='all')
args = parser.parse_args()
root = Path(__file__).resolve().parents[4]
fixture = Path(__file__).parent / 'folder_navigation'
receipt = json.loads(args.receipt.read_text())
if not (receipt['executed'] and receipt['didWork'] and not receipt['skipped']
        and not receipt['upToDate'] and Path(receipt['destination']).is_dir()):
    raise ValueError('fresh actual application Java receipt required')
compiler = Path(receipt['compiler'])
if args.phase in ['all', 'compile']:
    args.output.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location('folder_navigation_prepare', fixture / 'prepare.py')
    preparation = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(preparation)
    preparation.prepare(root, args.output)
    shutil.copyfile(fixture / 'FolderNavigationCases.java',
            args.output / 'src/org/telegram/ui/FolderNavigationCases.java')
    sources = sorted((args.output / 'src').rglob('*.java'))
    inputs = json.loads((args.output / 'input-hashes.json').read_text())
    for path in [args.receipt, fixture / 'prepare.py', fixture / 'FolderNavigationCases.java']:
        inputs[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
    (args.output / 'inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
    with (args.output / 'javac.log').open('x') as log:
        result = subprocess.run([str(compiler), '-encoding', 'UTF-8', '--release', '8',
                '-d', str(args.output / 'classes'), *map(str, sources)], stdout=log, stderr=subprocess.STDOUT)
    print((args.output / 'javac.log').read_text(), end='', flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
if args.phase in ['all', 'run']:
    inputs = json.loads((args.output / 'inputs.json').read_text())
    for name, expected in inputs.items():
        if hashlib.sha256(Path(name).read_bytes()).hexdigest() != expected:
            raise ValueError('folder navigation input changed: ' + name)
    with (args.output / 'run.log').open('x') as log:
        result = subprocess.run([str(compiler.with_name('java')), '-cp', str(args.output / 'classes'),
                'org.telegram.ui.FolderNavigationCases'], stdout=log, stderr=subprocess.STDOUT)
    print((args.output / 'run.log').read_text(), end='', flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
