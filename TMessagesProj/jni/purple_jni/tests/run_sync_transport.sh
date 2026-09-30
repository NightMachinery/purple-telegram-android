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
BuildPath="$(mktemp -d "${TMPDIR:-/tmp}/purple-sync-transport.XXXXXX")"
trap 'command rm -rf "$BuildPath"' EXIT

JavaSources="${PURPLE_SYNC_TRANSPORT_SOURCES:-$Java}"

clang++ -std=c++20 -g -O1 -dynamiclib \
    -o "$BuildPath/libpurplecore_sync.dylib" \
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

javac -Xlint:all,-options,-restricted -Werror -d "$BuildPath" -cp "$Json" \
    "$Tests/java_binding/android/content/SharedPreferences.java" \
    "$Tests/config_sync_bridge/org/telegram/messenger/MessagesController.java" \
    "$Tests/config_sync_bridge/org/telegram/messenger/UserConfig.java" \
    "$Tests/config_sync_bridge/org/telegram/messenger/purple/PurpleCore.java" \
    "$Tests/config_sync_bridge/org/telegram/messenger/purple/PurpleSyncSettingsFile.java" \
    "$Tests/sync_transport/org/telegram/messenger/purple/PurpleSyncTelegramClient.java" \
    "$Java/PurpleAccountBinding.java" \
    "$Java/PurpleAccountSyncCore.java" \
    "$Java/PurpleSyncCore.java" \
    "$Java/PurpleSyncInventory.java" \
    "$Java/PurpleSyncTransport.java" \
    "$JavaSources/PurpleSyncClient.java" \
    "$JavaSources/PurpleSyncScanner.java" \
    "$JavaSources/PurpleSyncReader.java" \
    "$JavaSources/PurpleSyncCheck.java" \
    "$JavaSources/PurpleSyncPost.java" \
    "$JavaSources/PurpleSyncTelegramTransport.java" \
    "$Tests/sync_transport/org/telegram/messenger/purple/PurpleSyncTransportTest.java"
java --enable-native-access=ALL-UNNAMED -cp "$BuildPath:$Json" \
    -Djava.io.tmpdir="$BuildPath" \
    -Dpurple.core.dylib="$BuildPath/libpurplecore_sync.dylib" \
    org.telegram.messenger.purple.PurpleSyncTransportTest
