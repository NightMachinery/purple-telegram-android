#!/usr/bin/env python3
import argparse
from pathlib import Path
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
assert not args.output.exists()
args.output.mkdir(parents=True)
paths = ["org/telegram/ui/PurplePeekControls.java"]
paths += ["org/telegram/messenger/purple/" + name + ".java" for name in [
    "PurpleGate", "PurpleCore", "PurpleWriter", "PurpleGlobalAccountChoice", "PurpleAppWideRemoval"]]
probes = {
    "baseline": None,
    "static-import": "package org.telegram.ui; import static org.telegram.messenger.purple.PurpleGate.setPreset; "
        "final class StaticImportProbe { void click() { setPreset(null); } }",
    "renamed-choice": "package org.telegram.ui; import org.telegram.messenger.purple.PurpleGlobalAccountChoice; "
        "final class RenamedChoiceProbe { void click(PurpleGlobalAccountChoice.Choice selected) { selected.apply(); } }",
    "nested-choice-import": "package org.telegram.ui; import org.telegram.messenger.purple.PurpleGlobalAccountChoice.Choice; "
        "final class NestedChoiceProbe { void click(Choice picked) { picked.apply(); } }",
    "qualified": "package org.telegram.ui; final class QualifiedProbe { void click() { "
        "org.telegram.messenger.purple.PurpleGate.setPreset(null); } }",
}
for name, probe in probes.items():
    fixture = args.output / name
    script = fixture / "purple/check-wait-routes.py"
    script.parent.mkdir(parents=True)
    shutil.copyfile(root / "purple/check-wait-routes.py", script)
    java = fixture / "TMessagesProj/src/main/java"
    for relative in paths:
        target = java / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / "TMessagesProj/src/main/java" / relative, target)
    if probe is not None:
        (java / "org/telegram/ui/Probe.java").write_text(probe + "\n")
    result = subprocess.run([sys.executable, str(script)], capture_output=True, text=True)
    (fixture / "guard.log").write_text(result.stdout + result.stderr)
    assert (result.returncode == 0) == (probe is None), name
    if probe is not None:
        assert "Work Mode writer inventory changed" in result.stderr and "org/telegram/ui/Probe.java" in result.stdout, name
    print(name + (": PASS" if probe is None else ": rejected"))
print("WAIT_ROUTE_TESTS=5")
