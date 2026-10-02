#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/.." && pwd)"
Variant="${PURPLE_FACTS_VARIANT:-afatStandalone}"
Apk="${1:-$Root/TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk}"
export LC_ALL=C

if [ $# -gt 1 ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    cat >&2 <<'EOF'
usage: purple/apk-facts.sh [APK]

Prints the facts of a finished build that a refactor batch must not change,
for diffing against the facts of the build before it: the APK's badging,
AppName and AppNameBeta per configuration, the sorted string pool, lib/,
the manifest, the generated BuildConfig.java files (credential values shown
as a digest), the CMake configure commands and compile_commands.json files
under .cxx, and R8's usage.txt and seeds.txt.

APK defaults to the afatStandalone output of this checkout. AAPT2 names the
aapt2 binary; otherwise it comes from PATH, ANDROID_HOME, ANDROID_SDK_ROOT or
the sdk.dir line of local.properties. PURPLE_FACTS_VARIANT names the R8
mapping directory (default afatStandalone).
EOF
    exit 2
fi

die() {
    echo "apk-facts: $*" >&2
    exit 2
}

[ -f "$Apk" ] || die "no APK at $Apk"

find_aapt2() {
    local sdk=""
    if [ -n "${AAPT2:-}" ]; then
        printf '%s\n' "$AAPT2"
        return
    fi
    if command -v aapt2 > /dev/null 2>&1; then
        command -v aapt2
        return
    fi
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -z "$sdk" ] && [ -f "$Root/local.properties" ]; then
        sdk="$(command sed -n 's/^sdk\.dir=//p' "$Root/local.properties" | command head -1)"
    fi
    [ -n "$sdk" ] || return 0
    command ls -d "$sdk"/build-tools/*/aapt2 2> /dev/null | command sort -V | command tail -1
}

Strings="$(mktemp "${TMPDIR:-/tmp}/apk-facts.XXXXXX")"
trap 'command rm -f "$Strings"' EXIT
Aapt2="$(find_aapt2)"
[ -n "$Aapt2" ] && [ -x "$Aapt2" ] || die "no aapt2; set AAPT2 or ANDROID_HOME"

digest() {
    if command -v sha256sum > /dev/null 2>&1; then
        command sha256sum | command cut -c1-16
    else
        command shasum -a 256 | command cut -c1-16
    fi
}

rel() {
    printf '%s' "${1#"$Root"/}"
}

section() {
    printf '\n=== %s\n' "$1"
}

zip_list() {
    if command -v zipinfo > /dev/null 2>&1; then
        command zipinfo -1 "$Apk"
    elif command -v unzip > /dev/null 2>&1; then
        command unzip -Z1 "$Apk"
    else
        python3 -c 'import sys, zipfile; print("\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))' "$Apk"
    fi
}

mask() {
    local line value
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            *" APP_ID = "* | *" APP_HASH = "* | *KEY*" = "* | *SECRET*" = "* | *TOKEN*" = "* | *PASSWORD*" = "*)
                value="${line#* = }"
                printf '%s = sha256:%s;\n' "${line%% = *}" "$(printf '%s' "${value%;}" | digest)"
                ;;
            *) printf '%s\n' "$line" ;;
        esac
    done
}

print_files() {
    local missing="$1" f found=0
    shift
    while IFS= read -r f; do
        found=1
        printf -- '--- %s\n' "$(rel "$f")"
        "$@" < "$f"
    done
    [ "$found" -eq 1 ] || printf 'missing: %s\n' "$missing"
}

section "badging"
"$Aapt2" dump badging "$Apk"

section "AppName and AppNameBeta per configuration"
"$Aapt2" dump resources "$Apk" | command awk '
    /^[ \t]*resource 0x[0-9a-f]+ string\/(AppName|AppNameBeta)$/ { name = $3; next }
    /^[ \t]*resource / { name = ""; next }
    name != "" && /^[ \t]*\(/ { sub(/^[ \t]+/, ""); print name " " $0 }
' | command sort

section "string pool, sorted"
"$Aapt2" dump strings "$Apk" > "$Strings"
command head -1 "$Strings"
command sed '1d; s/^String #[0-9]* : //' "$Strings" | command sort

section "lib/"
zip_list | command grep '^lib/' | command sort || true

section "AndroidManifest.xml"
"$Aapt2" dump xmltree --file AndroidManifest.xml "$Apk"

section "BuildConfig.java"
for dir in TMessagesProj TMessagesProj_AppStandalone; do
    { command find "$Root/$dir/build/generated/source/buildConfig" -name BuildConfig.java 2> /dev/null || true; } |
        command sort | print_files "$dir/build/generated/source/buildConfig" mask
done

section "CMake configure commands and compile_commands.json"
for dir in TMessagesProj TMessagesProj_AppStandalone; do
    { command find "$Root/$dir/.cxx" \( -name metadata_generation_command.txt -o -name compile_commands.json \) 2> /dev/null || true; } |
        command sort | print_files "$dir/.cxx" command cat
done

for name in usage.txt seeds.txt; do
    section "R8 $name"
    f="$Root/TMessagesProj_AppStandalone/build/outputs/mapping/$Variant/$name"
    if [ -f "$f" ]; then
        command cat "$f"
    else
        printf 'missing: %s\n' "$(rel "$f")"
    fi
done
