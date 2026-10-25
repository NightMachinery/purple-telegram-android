#!/usr/bin/env python3
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / "TMessagesProj/src/main/java"
expected = {
    "setPreset": {"org/telegram/ui/PurplePeekControls.java": 1,
        "org/telegram/messenger/purple/PurpleGlobalAccountChoice.java": 1,
        "org/telegram/messenger/purple/PurpleGate.java": 2,
        "org/telegram/messenger/purple/PurpleCore.java": 1},
    "startPeek": {"org/telegram/messenger/purple/PurplePeekStreak.java": 2,
        "org/telegram/messenger/purple/PurpleGate.java": 2,
        "org/telegram/messenger/purple/PurpleCore.java": 1},
    "stopPeek": {"org/telegram/ui/PurplePeekControls.java": 2,
        "org/telegram/messenger/purple/PurpleGate.java": 1,
        "org/telegram/messenger/purple/PurplePeekForeground.java": 1,
        "org/telegram/messenger/purple/PurpleCore.java": 1},
    "extendPeek": {"org/telegram/messenger/purple/PurplePeekStreak.java": 3,
        "org/telegram/messenger/purple/PurpleGate.java": 2,
        "org/telegram/messenger/purple/PurpleCore.java": 1},
    "commitFocusPolicy": {"org/telegram/ui/PurplePeekControls.java": 1,
        "org/telegram/messenger/purple/PurplePeekLocalSettings.java": 1},
    "commitWaitPolicy": {"org/telegram/ui/PurplePeekControls.java": 1,
        "org/telegram/messenger/purple/PurplePeekLocalSettings.java": 1},
    "setPeekExitHidden": {"org/telegram/ui/PurplePeekControls.java": 1,
        "org/telegram/messenger/purple/PurpleWriter.java": 1},
    "setPeekStartDelay": {"org/telegram/ui/PurplePeekControls.java": 1,
        "org/telegram/messenger/purple/PurpleWriter.java": 2,
        "org/telegram/messenger/purple/PurpleCore.java": 1},
    "storeStreak": {"org/telegram/messenger/purple/PurplePeekLocalSettings.java": 1,
        "org/telegram/messenger/purple/PurplePeekStreak.java": 4},
    "activatePeek": {"org/telegram/messenger/purple/PurplePeekStreak.java": 1,
        "org/telegram/ui/PurplePeekControls.java": 2},
    "setMode": {"org/telegram/messenger/purple/PurpleWriter.java": 2,
        "org/telegram/ui/PurplePeekControls.java": 1},
    "apply": {"org/telegram/ui/PurplePeekControls.java": 1,
        "org/telegram/messenger/purple/PurpleGlobalAccountChoice.java": 2,
        "org/telegram/messenger/purple/PurpleAppWideRemoval.java": 2,
        "org/telegram/messenger/purple/PurpleWriter.java": 15},
}
noncode = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*', re.S)
found = {name: {} for name in expected}
files = list(java.rglob("*.java")) + list(java.rglob("*.kt"))
violations = []
def method_body(text, name):
    match = re.search(r"\b" + name + r"\s*\([^;{}]*\)\s*\{", text)
    if match is None:
        return (-1, -1)
    start = match.end(); depth = 1; end = start
    while depth and end < len(text):
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return start, end - 1

for path in files:
    raw = path.read_text()
    text = noncode.sub(lambda match: " " * len(match.group()), raw)
    relative = str(path.relative_to(java))
    local = relative == "org/telegram/messenger/purple/PurplePeekLocalSettings.java"
    if not local and re.search(r'"(?:purple_peek_local|end_on_focus_loss)"', raw):
        violations.append(relative + ": ungated local Peek preference access")
    if local:
        ranges = [method_body(text, name) for name in ["commitFocusPolicy", "commitWaitPolicy", "storeStreak"]]
        for write in re.finditer(r"\b(?:edit|putBoolean|putInt|putLong|putString|remove|clear)\s*\(", text):
            if not any(start <= write.start() < end for start, end in ranges):
                violations.append(relative + ": local Peek writer outside admitted policy/streak methods")
    if relative == "org/telegram/ui/PurplePeekControls.java":
        start, end = method_body(text, "complete")
        for write in re.finditer(r"\b(?:commitFocusPolicy|commitWaitPolicy|setPeekStartDelay|setPeekExitHidden|activatePeek)\s*\(", text):
            if not start <= write.start() < end:
                violations.append(relative + ": local Peek write outside pending completion")
    if relative != "org/telegram/messenger/purple/PurpleWriter.java" and re.search(r'\bsetTable(?:Bool|String|Int)\s*\([^;]*?"peek"', raw, re.S):
        violations.append(relative + ": ungated in-app Peek table writer")
    for name in expected:
        if name == "setMode" and "PurpleWriter" not in text:
            continue
        if name == "apply" and "PurpleGlobalAccountChoice" not in text:
            continue
        count = len(re.findall(r"(?<![\w$])" + name + r"\s*\(", text))
        if count:
            found[name][relative] = count
for name, writers in found.items():
    for path, count in sorted(writers.items()):
        print(f"{name}: {path} ({count})")
for violation in violations:
    print(violation)
assert found == expected and not violations, f"Work Mode writer inventory changed: {found}"
print("WAIT_ROUTES_OK")
