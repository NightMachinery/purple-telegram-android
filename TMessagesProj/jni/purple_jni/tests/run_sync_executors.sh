#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
Tests="$Root/TMessagesProj/jni/purple_jni/tests"
Core="$Root/TMessagesProj/jni/purple"
Java="$Root/TMessagesProj/src/main/java/org/telegram/messenger/purple"
QtPrefix="${QT_PREFIX:-$HOME/code/misc/tdesktop-libs/local/qt}"
JavaHome="$(/usr/libexec/java_home)"
Json="${ORG_JSON_JAR:-}"
if [ -z "$Json" ]; then
    Json="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" \
        -name 'json-*.jar' ! -name '*-sources.jar' 2>/dev/null | sort | tail -1)"
fi
if [ ! -f "$Json" ]; then
    echo "Set ORG_JSON_JAR to an org.json jar (Maven org.json:json)." >&2
    exit 1
fi
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-sync-executors.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT
mkdir -p "$BuildPath/main" "$BuildPath/test" "$BuildPath/runs"

Library="${PURPLE_SYNC_DYLIB:-}"
if [ -z "$Library" ]; then
    Library="$BuildPath/libpurplecore_sync.dylib"
    clang++ -std=c++20 -g -O1 -dynamiclib \
        -o "$Library" \
        "$Root/TMessagesProj/jni/purple_jni/account_sync_bridge.cpp" \
        "$Root/TMessagesProj/jni/purple_jni/config_sync_bridge.cpp" \
        "$Core/purple/purple_settings.cpp" \
        "$Core/purple/purple_state.cpp" \
        "$Core/purple/purple_config_sync.cpp" \
        "$Core/purple/purple_config_diff.cpp" \
        "$Core/purple/purple_config_payload.cpp" \
        "$Core/purple/purple_sync_json.cpp" \
        "$Core/purple/purple_sync_envelope.cpp" \
        "$Core/purple/purple_sync_directory.cpp" \
        "$Core/purple/purple_sync_inventory.cpp" \
        "$Core/purple/purple_sync_config_flow.cpp" \
        "$Core/purple/purple_sync_config_describe.cpp" \
        "$Core/purple/purple_sync_local_state.cpp" \
        -I"$Core" -I"$Core/tomlplusplus" \
        -I"$JavaHome/include" -I"$JavaHome/include/darwin" \
        -I"$QtPrefix/frameworks/QtCore.framework/Headers" \
        -F"$QtPrefix/frameworks" -framework QtCore \
        -Wl,-rpath,"$QtPrefix/frameworks"
fi

javac --release 8 -Xlint:all,-options,-restricted -Werror \
    -d "$BuildPath/main" -cp "$Json" \
    "$Tests/java_binding/android/content/SharedPreferences.java" \
    "$Tests/store_stubs/android/util/AtomicFile.java" \
    "$Tests/sync_history/android/system/ErrnoException.java" \
    "$Tests/sync_history/android/system/Os.java" \
    "$Tests/sync_history/android/system/OsConstants.java" \
    "$Tests/sync_history/android/system/StructStat.java" \
    "$Tests/sync_history/org/telegram/messenger/FileLog.java" \
    "$Tests/sync_executors/org/telegram/messenger/AndroidUtilities.java" \
    "$Tests/sync_executors/org/telegram/messenger/ApplicationLoader.java" \
    "$Tests/sync_executors/org/telegram/messenger/DispatchQueue.java" \
    "$Tests/sync_executors/org/telegram/messenger/MessagesController.java" \
    "$Tests/sync_executors/org/telegram/messenger/UserConfig.java" \
    "$Tests/sync_executors/org/telegram/tgnet/ConnectionsManager.java" \
    "$Tests/config_sync_bridge/org/telegram/messenger/purple/PurpleCore.java" \
    "$Tests/sync_executors/org/telegram/messenger/purple/PurpleDevice.java" \
    "$Tests/sync_executors/org/telegram/messenger/purple/PurpleSettings.java" \
    "$Java/PurpleAccountBinding.java" \
    "$Java/PurpleAccountSyncCore.java" \
    "$Java/PurpleAccountSyncStore.java" \
    "$Java/PurpleSyncCore.java" \
    "$Java/PurpleSyncInventory.java" \
    "$Java/PurpleSyncTransport.java" \
    "$Java/PurpleSyncHistory.java" \
    "$Java/PurpleSyncSettingsFile.java" \
    "$Java/PurpleSyncApply.java" \
    "$Java/PurpleSyncPublisher.java" \
    "$Java/PurpleSyncRunner.java"

javac -Xlint:all,-options,-restricted -Werror -d "$BuildPath/test" \
    -cp "$BuildPath/main:$Json" \
    "$Tests/sync_executors/org/telegram/messenger/purple/PurpleSyncExecutorsTest.java"

java --enable-native-access=ALL-UNNAMED \
    -cp "$BuildPath/test:$BuildPath/main:$Json" \
    -Djava.io.tmpdir="$BuildPath/runs" \
    -Dpurple.core.dylib="$Library" \
    org.telegram.messenger.purple.PurpleSyncExecutorsTest
