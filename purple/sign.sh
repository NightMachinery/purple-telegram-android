#!/usr/bin/env bash
# Signs an unsigned Purple Telegram APK with the local keystore.
#
# Signing is configured locally.
# Private build-machine notes live in the private purple-build repo; when it is cloned at ~/.purple-build, see ~/.purple-build/readme.md.
#
#   purple/sign.sh path/to/app.apk [out.apk]
#
# Reads KEYSTORE, KEY_ALIAS, STORE_PASS, KEY_PASS from $PURPLE_ANDROID_KEYS
# (default: ~/.purple-build/keystore.env) and
# uses zipalign + apksigner from $ANDROID_SDK_ROOT
# (default: ~/code/misc/tdesktop-libs/android-sdk).
set -euo pipefail

In="${1:?usage: purple/sign.sh unsigned.apk [out.apk]}"
Out="${2:-${In%.apk}-signed.apk}"
KeysEnv="${PURPLE_ANDROID_KEYS:-$HOME/code/misc/tdesktop-libs/purple-android-keys/keystore.env}"
Sdk="${ANDROID_SDK_ROOT:-$HOME/code/misc/tdesktop-libs/android-sdk}"
Tools="$(ls -d "$Sdk"/build-tools/* | sort -V | tail -1)"

# shellcheck disable=SC1090
. "$KeysEnv"

Aligned="$(mktemp -t purple-apk).apk"
trap 'command rm -f "$Aligned"' EXIT

"$Tools/zipalign" -p -f 4 "$In" "$Aligned"
"$Tools/apksigner" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$Out" "$Aligned" 2>/dev/null
"$Tools/apksigner" verify --print-certs "$Out" 2>/dev/null | grep -m1 'Signer #1 certificate DN'
echo "signed: $Out"
