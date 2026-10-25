#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description="Run the actual AGP direct-share publisher with a synthetic ShortcutManager port")
p.add_argument("--receipt", type=Path, required=True)
p.add_argument("--output", type=Path, required=True)
a = p.parse_args()
d = json.loads(a.receipt.read_text())
assert d["task"] == ":TMessagesProj:compileStandaloneJavaWithJavac"
a.output.mkdir()
classes = a.output / "classes"
classes.mkdir()
empty = a.output / "empty"
empty.mkdir()
production = Path(d["destination"])
compiler = Path(d["compiler"])
case = Path(__file__).resolve().parent / "direct_share/PurpleDirectShareCases.java"
main = a.output / "DirectShareMain.java"
main.write_text('''import java.io.File;
public final class DirectShareMain {
 public static void main(String[] a) throws Exception {
  File expected = new File(a[0]).getCanonicalFile();
  for (String name : new String[]{"PurpleDirectShare", "PurpleDirectSharePublisher", "PurpleDirectSharePublisher$Target", "PurpleDirectSharePublisher$Port"}) {
   Class<?> type = Class.forName("org.telegram.messenger.purple." + name);
   File actual = new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();
   if (!expected.equals(actual)) throw new AssertionError("publisher shadow: " + name);
   System.out.println(name + " origin=" + actual);
  }
  org.telegram.messenger.purple.PurpleDirectShareAdapterCases.main(new String[0]);
  org.telegram.messenger.purple.PurpleDirectShareCases.main(new String[0]);
 }
}
''')
classpath = os.pathsep.join([str(production)] + [x["path"] for k in ["classpath", "bootstrapClasspath"] for x in d[k]])
sources = [case, main, case.with_name("PurpleDirectShareAdapterCases.java"), case.with_name("PurpleDirectShareRoutingCases.java")] + list((case.parent / "ports").rglob("*.java"))
(a.output / "inputs.json").write_text(json.dumps([{"path": str(x), "sha256": hashlib.sha256(x.read_bytes()).hexdigest()} for x in sources + [a.receipt]], indent=2) + "\n")
for name, args in [("javac", [compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", empty, "-cp", classpath, "-d", classes] + sources),
                   ("cases", [compiler.with_name("java"), "-cp", str(classes) + os.pathsep + classpath, "DirectShareMain", production])]:
    (a.output / (name + ".argv.json")).write_text(json.dumps(list(map(str, args)), indent=2) + "\n")
    with (a.output / (name + ".log")).open("w") as f:
        result = subprocess.run(list(map(str, args)), stdout=f, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    print((a.output / (name + ".log")).read_text(), flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)

for scenario in ["launcher", "handoff", "legacy", "legacy-partial", "legacy-no-slot", "session", "multiple"]:
    command = [compiler.with_name("java"), "-cp", str(classes) + os.pathsep + classpath,
               "org.telegram.messenger.purple.PurpleDirectShareRoutingCases", scenario]
    (a.output / (scenario + ".argv.json")).write_text(json.dumps(list(map(str, command)), indent=2) + "\n")
    with (a.output / (scenario + ".log")).open("w") as log:
        result = subprocess.run(list(map(str, command)), stdout=log, stderr=subprocess.STDOUT)
    print(scenario, result.returncode, flush=True)
    print((a.output / (scenario + ".log")).read_text(), flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)

root = Path(__file__).resolve().parents[4]
source = root / "TMessagesProj/src/main/java"
launch = source / "org/telegram/ui/LaunchActivity.java"
text = launch.read_text()
first = text.index("PurpleDirectShare.admitIncoming(this, intent)")
selection = text.index("switchToAccount(intentAccount[0], true)", first)
recipient = text.index("long dialogId = org.telegram.messenger.purple.PurpleDirectShare.shareRecipient(intent);")
single = text.index("if (Intent.ACTION_SEND.equals(intent.getAction()))", recipient)
second = text.index("PurpleDirectShare.admitIncoming(this, intent)", first + 1)
dispatch = text.index("if (push_story_id > 0)", second)
assert first < selection < recipient < single < second < dispatch
assert text.index("ApplicationLoader.postInitApplication();") < first
hook_sources = [launch]
for name in ["OpenChatReceiver", "OpenAttachedMenuBotReceiver"]:
    file = source / ("org/telegram/messenger/" + name + ".java")
    text = file.read_text()
    assert "admitShortcut" not in text
    assert text.index("intent2.putExtras(intent)") < text.index("PurpleDirectShare.forwardShortcut(intent2)") < text.index("startActivity(intent2)")
    hook_sources.append(file)
file = source / "org/telegram/messenger/MediaDataController.java"
text = file.read_text()
assert "PurpleDirectShare.bindShortcut(currentAccount, dialogId, shortcutIntent)" in text
assert "PurpleDirectShare.bindShortcut(currentAccount, botId, shortcutIntent)" in text
hook_sources.append(file)
(a.output / "receiver-hooks.json").write_text(json.dumps({"pass": True, "commonRecipientBeforeBothShareBranches": True,
    "admissionBeforeAccountSelectionAndCommonDispatch": True, "forwardingPreservesIdentityWithoutAccountAdmission": True,
    "sources": [{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in hook_sources]}, indent=2) + "\n")
print("DIRECT_SHARE_RECEIVER_HOOKS=PASS")

cold_classes = a.output / "cold-classes"
cold_classes.mkdir()
cold_sources = [p for p in (case.parent / "ports").rglob("*.java") if p.name != "OpenChatReceiver.java"]
cold_sources += list((case.parent / "cold_ports").rglob("*.java"))
cold_sources += [case.with_name("PurpleDirectShareColdStartCases.java"), case.with_name("PurpleDirectShareNavigationCases.java")]
(a.output / "cold-inputs.json").write_text(json.dumps([{"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in cold_sources], indent=2) + "\n")
cold_commands = [("cold-javac", [compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", empty, "-cp", classpath, "-d", cold_classes] + cold_sources)]
for scenario in ["chat", "bot"]:
    cold_commands.append(("cold-" + scenario, [compiler.with_name("java"), "-cp", str(cold_classes) + os.pathsep + classpath,
        "org.telegram.messenger.purple.PurpleDirectShareColdStartCases", scenario, production]))
cold_commands.append(("navigation", [compiler.with_name("java"), "-cp", str(cold_classes) + os.pathsep + classpath,
    "org.telegram.messenger.purple.PurpleDirectShareNavigationCases"]))
for name, command in cold_commands:
    (a.output / (name + ".argv.json")).write_text(json.dumps(list(map(str, command)), indent=2) + "\n")
    with (a.output / (name + ".log")).open("w") as log:
        result = subprocess.run(list(map(str, command)), stdout=log, stderr=subprocess.STDOUT)
    print(name, result.returncode, flush=True)
    print((a.output / (name + ".log")).read_text(), flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
