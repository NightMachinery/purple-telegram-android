#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description="Exercise the actual Android media reply adapter over inert ports and real JNI")
parser.add_argument("--receipt", type=Path, required=True)
parser.add_argument("--json", type=Path, required=True)
parser.add_argument("--library", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
receipt = json.loads(args.receipt.read_text())
classes = Path(receipt["destination"])
compiler = Path(receipt["compiler"])
assert receipt["executed"] and receipt["didWork"] and not receipt.get("skipped", False)
assert classes.is_dir() and args.json.is_file() and args.library.is_file()
assert not args.output.exists(), "fixture output must be fresh"
args.output.mkdir(parents=True)
out = args.output / "classes"
out.mkdir()
fixtures = args.output / "fixtures"
fixtures.mkdir()
empty = args.output / "empty"
empty.mkdir()
source = Path(__file__).resolve().parent / "media_reply"
sources = sorted(source.rglob("*.java"))
classpath = os.pathsep.join([str(classes), str(args.json)])
commands = [
    ("javac", [compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", empty,
        "-cp", classpath, "-d", out, *sources]),
    ("adapter", [compiler.with_name("java"), "-Xcheck:jni", "-Xmx512m", "-XX:ActiveProcessorCount=2",
        "-Dscratch=" + str(fixtures), "-Djava.io.tmpdir=" + str(fixtures), "-cp", str(out) + os.pathsep + classpath,
        "org.telegram.messenger.purple.PurpleMediaReplyCases", args.library.resolve(), classes.resolve()]),
]
for name, command in commands:
    with (args.output / (name + ".log")).open("w") as log:
        result = subprocess.run([str(x) for x in command], stdout=log, stderr=subprocess.STDOUT)
    print((args.output / (name + ".log")).read_text(), end="", flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
inputs = sources + [args.library, args.receipt, classes / "org/telegram/messenger/purple/PurpleMediaReply.class"]
(args.output / "inputs.json").write_text(json.dumps([
    {"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in inputs
], indent=2) + "\n")
