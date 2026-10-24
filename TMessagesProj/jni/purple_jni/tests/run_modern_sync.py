#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description="Run account sync fixtures against actual AGP classes and host JNI")
parser.add_argument("--receipt", type=Path, required=True)
parser.add_argument("--json", type=Path, required=True)
parser.add_argument("--library", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--suite", choices=["all", "modern", "routed", "existing", "core", "store", "history"], default="all")
args = parser.parse_args()
tests = Path(__file__).resolve().parent
repo = tests.parents[3]
receipt = json.loads(args.receipt.read_text())
classes = Path(receipt["destination"])
compiler = Path(receipt["compiler"])
java = compiler.with_name("java")
assert receipt["task"] == ":TMessagesProj:compileStandaloneJavaWithJavac"
assert classes.is_dir() and args.json.is_file() and args.library.is_file()
assert not args.output.exists(), "output must be an unused fixture directory"
args.output.mkdir(parents=True)
out = args.output / "classes"
out.mkdir()
empty = args.output / "empty-sourcepath"
empty.mkdir()
fixtures = args.output / "fixtures"
fixtures.mkdir()
classpath = os.pathsep.join([str(classes), str(args.json)] + [item["path"] for key in ["classpath", "bootstrapClasspath"] for item in receipt[key]])
adapters = [path for path in (tests / "modern_sync").rglob("*.java") if "/messenger/purple/" not in str(path)]
case_sources = [path for path in (tests / "modern_sync").rglob("*.java") if "/messenger/purple/" in str(path)]
case_sources += [tests / path for path in [
    "account_editor/PurpleAccountEditorCases.java", "routed_writes/PurpleRoutedWriteCases.java", "transfers/PurpleTransferCases.java",
    "store_stubs/org/telegram/messenger/purple/PurpleAccountSyncStoreTest.java",
    "config_sync_bridge/org/telegram/messenger/purple/PurpleSyncCoreTest.java",
    "sync_history/org/telegram/messenger/purple/PurpleSyncHistoryTest.java"]]
main = args.output / "SyncHostMain.java"
main.write_text('import java.io.File;public final class SyncHostMain{public static void main(String[] a)throws Exception{org.telegram.messenger.purple.PurpleCore.ensureLoaded();File expected=new File(a[0]).getCanonicalFile();for(String n:new String[]{"PurpleSyncRunner","PurpleSyncApply","PurpleSyncPublisher","PurpleSyncCore","PurpleAccountSyncStore","PurpleSyncHistory","PurpleAccountBinding","PurpleSyncRecoveryCore","PurpleSyncRecoveryModel","PurpleSyncRecoveryFiles","PurpleSyncRecovery","PurpleAccountFiles","PurpleAccountSettings","PurpleAccountEditorModel","PurpleConfigImpact","PurpleRoutedWriteModel"}){File origin=new File(Class.forName("org.telegram.messenger.purple."+n).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();if(!expected.equals(origin))throw new AssertionError("business class shadow: "+n);System.out.println(n+" origin="+origin);}String suite=a[1];File root=new File(a[2]);if(!root.mkdir())throw new AssertionError("exclusive fixture");if(suite.equals("modern")){System.out.println("MODERN_CHECKS="+org.telegram.messenger.purple.PurpleModernSyncCases.run(root));return;}if(suite.equals("routed")){File routed=new File(root,"routed"),editor=new File(root,"editor"),transfers=new File(root,"transfers");if(!routed.mkdir()||!editor.mkdir()||!transfers.mkdir())throw new AssertionError("fixtures");System.out.println("ROUTED_CHECKS="+org.telegram.messenger.purple.PurpleRoutedWriteCases.run(routed));System.out.println("EDITOR_CHECKS="+org.telegram.messenger.purple.PurpleAccountEditorCases.run(editor));System.out.println("TRANSFER_CHECKS="+org.telegram.messenger.purple.PurpleTransferCases.run(transfers));return;}String name=suite.equals("core")?"PurpleSyncCoreTest":suite.equals("store")?"PurpleAccountSyncStoreTest":"PurpleSyncHistoryTest";Class.forName("org.telegram.messenger.purple."+name).getMethod("main",String[].class).invoke(null,(Object)(suite.startsWith("history")?new String[]{suite.substring(8)}:new String[0]));}}\n')
sources = adapters + case_sources + [main]
def run(name, command):
    (args.output / (name + ".argv.json")).write_text(json.dumps(list(map(str, command)), indent=2) + "\n")
    with (args.output / (name + ".log")).open("w") as log:
        result = subprocess.run(list(map(str, command)), cwd=repo, stdout=log, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    print((args.output / (name + ".log")).read_text()[-5000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
manifest = [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources + [args.library, args.receipt]]
(args.output / "inputs.json").write_text(json.dumps(manifest, indent=2) + "\n")
run("javac", [compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", empty, "-cp", classpath, "-d", out] + sources)
suites = ["modern", "routed", "store", "core", "history-rules", "history-native"] if args.suite == "all" else ["store", "core", "history-rules", "history-native"] if args.suite == "existing" else ["history-rules", "history-native"] if args.suite == "history" else [args.suite]
for suite in suites:
    run(suite, [java, "-Xcheck:jni", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-Djava.library.path=" + str(args.library.parent), "-Djava.io.tmpdir=" + str(fixtures), "-Dscratch=" + str(fixtures), "-Dpurple.test.strings=" + str(repo / "TMessagesProj/src/main/res/values/purple_strings.xml"), "-cp", str(out) + os.pathsep + classpath, "SyncHostMain", classes, suite, fixtures / suite])
