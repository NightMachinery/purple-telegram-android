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
    "PurpleGate", "PurpleCore", "PurpleWriter", "PurpleGlobalAccountChoice", "PurpleAppWideRemoval",
    "PurplePeekForeground", "PurplePeekLocalSettings", "PurplePeekStreak"]]
probes = {
    "baseline": None,
    "direct-local-disable": 'package org.telegram.ui; final class Probe { void click(android.content.Context c) { c.getSharedPreferences("purple_peek_local",0).edit().putBoolean("end_on_focus_loss",false).commit(); } }',
    "policy-setter": 'package org.telegram.ui; final class Probe { void click(org.telegram.messenger.purple.PurplePeekLocalSettings.Snapshot s) { org.telegram.messenger.purple.PurplePeekLocalSettings.commitFocusPolicy(s,false); } }',
    "local-writer-moved": "LOCAL_WRITER_MOVED",
    "controller-writer-moved": "CONTROLLER_WRITER_MOVED",
    "static-import": "package org.telegram.ui; import static org.telegram.messenger.purple.PurpleGate.setPreset; "
        "final class StaticImportProbe { void click() { setPreset(null); } }",
    "renamed-choice": "package org.telegram.ui; import org.telegram.messenger.purple.PurpleGlobalAccountChoice; "
        "final class RenamedChoiceProbe { void click(PurpleGlobalAccountChoice.Choice selected) { selected.apply(); } }",
    "nested-choice-import": "package org.telegram.ui; import org.telegram.messenger.purple.PurpleGlobalAccountChoice.Choice; "
        "final class NestedChoiceProbe { void click(Choice picked) { picked.apply(); } }",
    "qualified": "package org.telegram.ui; final class QualifiedProbe { void click() { "
        "org.telegram.messenger.purple.PurpleGate.setPreset(null); } }",
}
probes["activation-bypass"] = 'package org.telegram.ui; final class Probe { void click() { org.telegram.messenger.purple.PurplePeekStreak.activatePeek(60,null,false); } }'
for option in ["escalation", "step", "max_extra", "reset_minutes"]:
    probes["option-" + option] = 'package org.telegram.ui; final class Probe { void click(org.telegram.messenger.purple.PurplePeekLocalSettings.Snapshot s) { org.telegram.messenger.purple.PurplePeekLocalSettings.commitWaitPolicy(s,"' + option + '",0); } }'
for writer in ["setPeekStartDelay", "setPeekReadyTimeout", "setPeekExitHidden"]:
    probes[writer] = 'package org.telegram.ui; final class Probe { void click() { org.telegram.messenger.purple.PurpleWriter.' + writer + '(null,null,0); } }'
for option in ["exit_hidden_chats_p", "tap_mobile", "auto_off", "hotkey_length", "end_on_app_lock_mobile_p"]:
    probes["table-" + option] = 'package org.telegram.ui; final class Probe { void click() { org.telegram.messenger.purple.PurpleWriter.setTableBool(null,"peek","' + option + '",false,""); } }'
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
    if probe == "LOCAL_WRITER_MOVED":
        path = java / "org/telegram/messenger/purple/PurplePeekLocalSettings.java"
        text = path.read_text().replace("    private PurplePeekLocalSettings()", "    static void bypass() { preferences().edit().putBoolean(KEY, false).commit(); }\n    private PurplePeekLocalSettings()")
        path.write_text(text)
    elif probe == "CONTROLLER_WRITER_MOVED":
        path = java / "org/telegram/ui/PurplePeekControls.java"
        text = path.read_text().replace("        boolean saved = PurplePeekLocalSettings.commitFocusPolicy(local, value == 1);", "        boolean saved = bypass(local, value == 1);")
        text = text.replace("    private PurplePeekControls()", "    static boolean bypass(PurplePeekLocalSettings.Snapshot s, boolean b) { return PurplePeekLocalSettings.commitFocusPolicy(s, b); }\n    private PurplePeekControls()")
        path.write_text(text)
    elif probe is not None:
        (java / "org/telegram/ui/Probe.java").write_text(probe + "\n")
    result = subprocess.run([sys.executable, str(script)], capture_output=True, text=True)
    (fixture / "guard.log").write_text(result.stdout + result.stderr)
    assert (result.returncode == 0) == (probe is None), name
    if probe is not None:
        assert "Work Mode writer inventory changed" in result.stderr and ("org/telegram/ui/Probe.java" in result.stdout or name.endswith("writer-moved")), name
    print(name + (": PASS" if probe is None else ": rejected"))
print("WAIT_ROUTE_TESTS=" + str(len(probes)))
