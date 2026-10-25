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
    "startPeek": {"org/telegram/ui/PurplePeekControls.java": 1,
        "org/telegram/messenger/purple/PurpleGate.java": 2,
        "org/telegram/messenger/purple/PurpleCore.java": 1},
    "stopPeek": {"org/telegram/ui/PurplePeekControls.java": 2,
        "org/telegram/messenger/purple/PurpleGate.java": 2,
        "org/telegram/messenger/purple/PurpleCore.java": 1},
    "extendPeek": {"org/telegram/ui/PurplePeekControls.java": 1,
        "org/telegram/messenger/purple/PurpleGate.java": 2,
        "org/telegram/messenger/purple/PurpleCore.java": 1},
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
for path in files:
    text = noncode.sub(" ", path.read_text())
    relative = str(path.relative_to(java))
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
assert found == expected, f"Work Mode writer inventory changed: {found}"
print("WAIT_ROUTES_OK")
