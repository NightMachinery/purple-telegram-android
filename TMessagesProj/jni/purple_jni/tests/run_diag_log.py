#!/usr/bin/env python3
"""Run the diagnostic log cases against the AGP-compiled PurpleDiagLogStore
and PurpleDiagLog.

The store cases need no platform. The facade cases (export, header, Clear
and the full-queue path) run in a JVM of their own with the modern sync
platform ports (accounts, file root, UI queue), shadowing no Purple class.
The receipt is the JSON the compile-evidence init script writes for
:TMessagesProj:compileStandaloneJavaWithJavac; --json is the org.json jar.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--receipt", type=Path, required=True)
parser.add_argument("--json", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
receipt = json.loads(args.receipt.read_text())
assert receipt["task"] == ":TMessagesProj:compileStandaloneJavaWithJavac"
classes = Path(receipt["destination"])
compiler = Path(receipt["compiler"])
assert classes.is_dir() and args.json.is_file()
assert not args.output.exists(), "output must be an unused directory"
args.output.mkdir(parents=True)
out = args.output / "classes"
out.mkdir()
empty = args.output / "empty-sourcepath"
empty.mkdir()
fixtures = args.output / "fixtures"
fixtures.mkdir()
classpath = os.pathsep.join([str(classes), str(args.json)] + [item["path"] for key in ["classpath", "bootstrapClasspath"] for item in receipt[key]])
main = args.output / "DiagLogHostMain.java"
main.write_text('import java.io.File;public final class DiagLogHostMain{public static void main(String[] a)throws Exception{'
                'File expected=new File(a[0]).getCanonicalFile();'
                'for(String n:new String[]{"PurpleDiagLogStore","PurpleDiagLog"}){'
                'File origin=new File(Class.forName("org.telegram.messenger.purple."+n).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();'
                'if(!expected.equals(origin))throw new AssertionError("business class shadow: "+n);'
                'System.out.println(n+" origin="+origin);}'
                'File root=new File(a[2]);if(!root.mkdir())throw new AssertionError("exclusive fixture");'
                'if(a[1].equals("store"))System.out.println("DIAG_LOG_CHECKS="+org.telegram.messenger.purple.PurpleDiagLogCases.run(root));'
                'else System.out.println("DIAG_LOG_FACADE_CHECKS="+org.telegram.messenger.purple.PurpleDiagLogFacadeCases.run(root));}}\n')
adapters = [path for path in (tests / "modern_sync").rglob("*.java") if "/messenger/purple/" not in str(path)]
sources = adapters + [tests / "diag_log/PurpleDiagLogCases.java", tests / "diag_log/PurpleDiagLogFacadeCases.java", main]
(args.output / "inputs.json").write_text(json.dumps([{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources + [args.receipt]], indent=2) + "\n")


def run(name, command):
    (args.output / (name + ".argv.json")).write_text(json.dumps(list(map(str, command)), indent=2) + "\n")
    with (args.output / (name + ".log")).open("w") as log:
        result = subprocess.run(list(map(str, command)), stdout=log, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    print((args.output / (name + ".log")).read_text()[-4000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)


run("javac", [compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", empty, "-cp", classpath, "-d", out] + sources)
for scenario in ["store", "facade"]:
    run("diag-log-" + scenario, [compiler.with_name("java"), "-Xmx256m", "-cp", str(out) + os.pathsep + classpath,
                                 "DiagLogHostMain", classes, scenario, fixtures / scenario])
