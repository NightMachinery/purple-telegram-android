#!/usr/bin/env python3
from pathlib import Path
import re
root=Path(__file__).resolve().parents[4]
cmake=(root/'TMessagesProj/jni/purple_jni/purple.cmake').read_text()
registered=set(re.findall(r'purple/purple/([a-z_]+\.cpp)',cmake))
core=root/'TMessagesProj/jni/purple/purple'
assert registered=={p.name for p in core.glob('*.cpp')},'register every core translation unit'
for name in sorted(registered):print(core/name)
