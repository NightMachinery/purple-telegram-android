#!/usr/bin/env python3
"""Run F-WAIT state-machine and account-targeting cases on real AGP classes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--receipt", type=Path, required=True)
parser.add_argument("--json", type=Path, required=True)
parser.add_argument("--library", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
receipt = json.loads(args.receipt.read_text())
assert receipt["task"] == ":TMessagesProj:compileStandaloneJavaWithJavac"
classes = Path(receipt["destination"])
compiler = Path(receipt["compiler"])
java = compiler.with_name("java")
assert classes.is_dir() and args.json.is_file() and args.library.is_file()
assert not args.output.exists(), "output must be an unused directory"
args.output.mkdir(parents=True)
out = args.output / "classes"
out.mkdir()
empty = args.output / "empty-sourcepath"
empty.mkdir()
fixtures = args.output / "fixtures"
fixtures.mkdir()
classpath = os.pathsep.join([str(classes), str(args.json)] + [item["path"] for key in ["classpath", "bootstrapClasspath"] for item in receipt[key]])
# The modern sync platform ports (accounts, preferences, file root, Os), plus a
# main Looper; no production class is shadowed.
adapters = [path for path in (tests / "modern_sync").rglob("*.java") if "/messenger/purple/" not in str(path)]
adapters += list((tests / "list_capture/android").rglob("*.java"))
business = ["PurpleAccountOwner", "PurpleAccountFiles", "PurpleAccountSettings", "PurpleWriter", "PurpleGlobalAccountChoice",
            "PurpleRoutedWriteModel", "PurpleListSource", "PurpleListMenu", "PurpleAccountRowWrite", "PurpleCore",
            "PurpleDiagLog", "PurpleDiagLogStore", "PurpleWaitGate", "PurpleWaitPresence", "PurpleWaitLayout", "PurplePeekSnapshot"]
main = args.output / "WaitGateHostMain.java"
main.write_text('import java.io.File;public final class WaitGateHostMain{public static void main(String[] a)throws Exception{'
                'org.telegram.messenger.purple.PurpleCore.ensureLoaded();File expected=new File(a[0]).getCanonicalFile();'
                'for(String n:new String[]{' + ",".join('"%s"' % n for n in business) + '}){'
                'File origin=new File(Class.forName("org.telegram.messenger.purple."+n).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();'
                'if(!expected.equals(origin))throw new AssertionError("business class shadow: "+n);}'
                'File root=new File(a[2]);if(!root.mkdir())throw new AssertionError("exclusive fixture");'
                'if(a[1].equals("ready")){System.out.println("READY_WAIT_CHECKS="+org.telegram.messenger.purple.PurpleReadyWaitCases.run());return;}'
                'if(a[1].equals("controller")){System.out.println("WAIT_GATE_CONTROLLER_CHECKS="+org.telegram.ui.PurpleWaitControllerCases.run(expected));return;}'
                'System.out.println("WAIT_GATE_"+a[1].toUpperCase()+"_CHECKS="+org.telegram.messenger.purple.PurpleWaitCases.run(a[1],root));}}\n')
sources = adapters + [tests / "list_capture/PurpleListCaptureCases.java", tests / "wait_gate/PurpleWaitCases.java", tests / "wait_gate/PurpleReadyWaitCases.java", tests / "wait_gate/PurpleWaitControllerCases.java", main]
(args.output / "inputs.json").write_text(json.dumps([{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                                                     for p in sources + [args.library, args.receipt]], indent=2) + "\n")


def run(name, command):
    (args.output / (name + ".argv.json")).write_text(json.dumps(list(map(str, command)), indent=2) + "\n")
    with (args.output / (name + ".log")).open("w") as log:
        result = subprocess.run(list(map(str, command)), stdout=log, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    print((args.output / (name + ".log")).read_text()[-4000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)


run("javac", [compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", empty, "-cp", classpath, "-d", out] + sources)
for scenario in ["machine", "account", "context", "held", "controller", "ready"]:
    run(scenario, [java, "-Xcheck:jni", "-Xmx512m", "-Djava.library.path=" + str(args.library.parent),
                   "-Djava.io.tmpdir=" + str(fixtures), "-cp", str(out) + os.pathsep + classpath,
                   "WaitGateHostMain", classes, scenario, fixtures / scenario])

run("peek-focus", ["python3", tests / "run_peek_focus.py", "--receipt", args.receipt, "--json", args.json, "--library", args.library, "--output", args.output / "peek-focus"])
