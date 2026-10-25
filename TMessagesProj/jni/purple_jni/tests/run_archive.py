#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description="Exercise actual AGP archive classes with the bundled-SQLite JNI and JCA provider")
parser.add_argument("--receipt", required=True, type=Path)
parser.add_argument("--json", required=True, type=Path)
parser.add_argument("--core", required=True, type=Path)
parser.add_argument("--sqlite", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--platform-ports", type=Path)
a = parser.parse_args()
tests = Path(__file__).resolve().parent
receipt = json.loads(a.receipt.read_text())
assert receipt["task"] == ":TMessagesProj:compileStandaloneJavaWithJavac"
assert not a.output.exists(), "exclusive fixture output"
a.output.mkdir(parents=True)
out = a.output / "classes"
out.mkdir()
empty = a.output / "empty-sourcepath"
empty.mkdir()
production = Path(receipt["destination"])
compiler = Path(receipt["compiler"])
classpath = os.pathsep.join([str(production), str(a.json)] + [item["path"] for name in ["classpath", "bootstrapClasspath"] for item in receipt[name]])
ports = [p for p in (a.platform_ports or tests / "modern_sync").rglob("*.java") if "/messenger/purple/" not in str(p)]
cases = list((tests / "archive").glob("*.java"))
main = a.output / "ArchiveHostMain.java"
main.write_text('''import java.io.File;
public final class ArchiveHostMain {
    public static void main(String[] a) throws Exception {
        File expected = new File(a[0]).getCanonicalFile();
        for (String name : new String[]{"Sqlite","Files","Paths","Binding","Catalogue","Core","Crypto","Keys","Store","Removal","Usage","Gaps","OwnedMedia","Login","Discard","Normalize","Origin","Slice","Sequence","Capture","ViewerModel",""}) {
            String type = "org.telegram.messenger.purple.PurpleArchive" + name;
            File origin = new File(Class.forName(type).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();
            if (!origin.equals(expected)) throw new AssertionError("business class shadow: " + type);
            System.out.println(type + " origin=" + origin);
        }
        if (a[1].startsWith("logout-")) org.telegram.messenger.purple.PurpleArchiveLogoutCrashCases.main(new String[]{a[2],a[3],a[1].substring(7)});
        else if (a[1].startsWith("refusal-")) org.telegram.messenger.purple.PurpleArchiveRefusalCases.main(new String[]{a[2],a[3],a[1].substring(8)});
        else if (a[1].startsWith("selection-")) org.telegram.messenger.purple.PurpleArchiveSelectionCases.main(new String[]{a[2],a[3],a[1].substring(10)});
        else if (a[1].startsWith("login-")) org.telegram.messenger.purple.PurpleArchiveLoginRestartCases.main(new String[]{a[2],a[3],a[1].substring(6)});
        else if (a[1].equals("foundation")) org.telegram.messenger.purple.PurpleArchiveFoundationCases.main(new String[]{a[2],a[3]});
        else if (a[1].equals("capture")) org.telegram.messenger.purple.PurpleArchiveCaptureCases.main(new String[]{a[2],a[3]});
        else if (a[1].startsWith("manager")) org.telegram.messenger.purple.PurpleArchiveManagerCases.main(new String[]{a[2],a[3],a[1].equals("manager-seed")?"seed":a[1].equals("manager-recover")?"recover":"session"});
        else org.telegram.messenger.purple.PurpleArchiveStoreCases.main(new String[]{a[2],a[3]});
    }
}
''')
sources = ports + list((tests / "archive_ports").rglob("*.java")) + cases + [main]
(a.output / "inputs.json").write_text(json.dumps([{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sources + [a.receipt, a.core, a.sqlite]], indent=2) + "\n")
def run(name, command):
    (a.output / (name + ".argv.json")).write_text(json.dumps(list(map(str, command)), indent=2) + "\n")
    with (a.output / (name + ".log")).open("w") as f:
        result = subprocess.run(list(map(str, command)), stdout=f, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    print((a.output / (name + ".log")).read_text()[-6000:], flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
run("javac", [compiler,"-source","8","-target","8","-proc:none","-sourcepath",empty,"-cp",classpath,"-d",out]+sources)
for suite in ["foundation", "store", "manager", "manager-seed", "manager-recover", "login-seed", "login-same", "login-new", "logout-seed", "logout-restart", "logout-seed-reuse", "logout-reuse", "refusal-fence", "refusal-intent", "refusal-malformed", "refusal-oversized", "refusal-unreadable", "refusal-missing", "refusal-invalid", "selection-viewer", "selection-hook", "capture"]:
    run(suite, [compiler.with_name("java"),"-Xcheck:jni","-Xmx512m","-XX:ActiveProcessorCount=2","-Djava.library.path="+str(a.core.parent),"-Dpurple.archive.format1="+str(tests/"archive/format1.sql"),"-cp",str(out)+os.pathsep+classpath,"ArchiveHostMain",production,suite,a.sqlite,a.output/("logout-reuse-fixtures" if suite in ["logout-seed-reuse", "logout-reuse"] else "logout-crash-fixtures" if suite.startswith("logout-") else "login-restart-fixtures" if suite.startswith("login-") else "manager-restart-fixtures" if suite in ["manager-seed", "manager-recover"] else suite+"-fixtures")])
