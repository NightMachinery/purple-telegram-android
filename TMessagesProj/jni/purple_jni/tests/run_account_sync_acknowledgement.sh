#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
QtPrefix="${QT_PREFIX:-$HOME/code/misc/tdesktop-libs/local/qt}"
JavaHome="$(/usr/libexec/java_home)"
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-account-sync-jni.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT

CoreSources=()
while IFS= read -r file; do CoreSources+=("$file"); done < <(python3 "$Root/TMessagesProj/jni/purple_jni/tests/core_sources.py")

clang++ -std=c++20 -g -O0 -dynamiclib \
    -o "$BuildPath/libpurplecore_smoke.dylib" \
    "$Root/TMessagesProj/jni/purple_jni/account_sync_bridge.cpp" \
    "${CoreSources[@]}" \
    -I"$Root/TMessagesProj/jni/purple" \
    -I"$Root/TMessagesProj/jni/purple/tomlplusplus" \
    -I"$JavaHome/include" -I"$JavaHome/include/darwin" \
    -I"$QtPrefix/frameworks/QtCore.framework/Headers" \
    -F"$QtPrefix/frameworks" -framework QtCore \
    -Wl,-rpath,"$QtPrefix/frameworks"

javac -d "$BuildPath" \
    "$Root/TMessagesProj/jni/purple_jni/tests/PurpleAccountSyncCore.java"
java --enable-native-access=ALL-UNNAMED -cp "$BuildPath" org.telegram.messenger.purple.PurpleAccountSyncCore \
    "$BuildPath/libpurplecore_smoke.dylib"
