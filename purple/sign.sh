#!/usr/bin/env bash
# Signs an unsigned Purple Telegram APK with the local keystore.
#
# The build host only ever produces unsigned APKs: it is a shared machine and
# the keystore must not live there. This runs on the machine that holds it.
#
#   purple/sign.sh path/to/app.apk [out.apk]
#
# Reads KEYSTORE, KEY_ALIAS, STORE_PASS, KEY_PASS from $PURPLE_ANDROID_KEYS
# (default: ~/code/misc/tdesktop-libs/purple-android-keys/keystore.env) and
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
