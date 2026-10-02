#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
Tests="$Root/TMessagesProj/jni/purple_jni/tests"
Script="${PURPLE_VERSION_SCRIPT:-$Root/purple/version.gradle}"
Gradle="${GRADLE:-$(command -v gradle || true)}"
if [ -z "$Gradle" ]; then
    Gradle="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle 2>/dev/null | sort -V | tail -1)"
fi
if [ ! -x "$Gradle" ]; then
    echo "Set GRADLE to a Gradle 7.5 or later launcher." >&2
    exit 1
fi
export JAVA_HOME="${GRADLE_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
Git="$(command -v git)"
Work="$(mktemp -d "${TMPDIR:-/tmp}/purple-build-version.XXXXXX")"
trap 'command rm -rf "$Work"' EXIT
export GRADLE_USER_HOME="$Work/gradle-home"

checks=0
failures=0

g() {
    "$Git" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false \
        -c protocol.file.allow=always -c init.defaultBranch=master "$@"
}

short() {
    g -C "$1" rev-parse --short HEAD
}

fields() {
    local dir="$1"
    shift
    env "$@" "$Gradle" --no-daemon --offline -q -Dorg.gradle.jvmargs=-Xmx256m \
        -p "$dir" -PpurpleVersionScript="$Script" help
}

expect() {
    local name="$1" dir="$2" app="$3" core="$4"
    shift 4
    local out got_app got_core
    checks=$((checks + 1))
    if ! out="$(fields "$dir" "$@" 2>&1)"; then
        failures=$((failures + 1))
        echo "  FAIL  $name: the build failed"
        printf '%s\n' "$out" | sed 's/^/        /'
        return
    fi
    got_app="$(printf '%s\n' "$out" | sed -n 's/^FIELD PURPLE_GIT_HASH String "\(.*\)"$/\1/p')"
    got_core="$(printf '%s\n' "$out" | sed -n 's/^FIELD PURPLE_CORE_HASH String "\(.*\)"$/\1/p')"
    if [ "$got_app" != "$app" ] || [ "$got_core" != "$core" ]; then
        failures=$((failures + 1))
        echo "  FAIL  $name: got ($got_app, core $got_core), want ($app, core $core)"
    fi
}

Core="$Work/core"
g init -q "$Core"
echo one > "$Core/core.txt"
g -C "$Core" add core.txt
g -C "$Core" commit -q -m core

app() {
    local dir="$Work/$1"
    g init -q "$dir"
    cp "$Tests/build_version/settings.gradle" "$Tests/build_version/build.gradle" "$dir/"
    echo one > "$dir/README.md"
    g -C "$dir" add README.md settings.gradle build.gradle
    g -C "$dir" submodule add -q "$Core" TMessagesProj/jni/purple
    g -C "$dir" commit -q -m app
    echo "$dir"
}

CoreHash="$(short "$Core")"

dir="$(app clean)"
expect "clean checkout" "$dir" "$(short "$dir")" "$CoreHash"

dir="$(app modified)"
echo two >> "$dir/README.md"
expect "modified tracked file" "$dir" "$(short "$dir")+dirty" "$CoreHash"

dir="$(app staged)"
echo two >> "$dir/README.md"
g -C "$dir" add README.md
expect "staged change" "$dir" "$(short "$dir")+dirty" "$CoreHash"

dir="$(app deleted)"
command rm "$dir/README.md"
expect "deleted tracked file" "$dir" "$(short "$dir")+dirty" "$CoreHash"

dir="$(app untracked)"
echo new > "$dir/untracked.txt"
expect "untracked file only" "$dir" "$(short "$dir")" "$CoreHash"

dir="$(app submodule-dirty)"
command rm "$dir/TMessagesProj/jni/purple/core.txt"
expect "dirty submodule worktree" "$dir" "$(short "$dir")" "$CoreHash"

dir="$(app submodule-moved)"
echo two > "$dir/TMessagesProj/jni/purple/core.txt"
g -C "$dir/TMessagesProj/jni/purple" commit -q -am moved
expect "submodule at another commit" "$dir" "$(short "$dir")" \
    "$(short "$dir/TMessagesProj/jni/purple")"

source="$(app clone-source)"
dir="$Work/uninitialized"
g clone -q "$source" "$dir"
expect "submodule not initialized" "$dir" "$(short "$dir")" "unknown"

outer="$Work/outer"
g init -q "$outer"
echo outer > "$outer/outer.txt"
g -C "$outer" add outer.txt
g -C "$outer" commit -q -m outer
dir="$outer/export"
mkdir -p "$dir/TMessagesProj/jni/purple"
cp "$Tests/build_version/settings.gradle" "$Tests/build_version/build.gradle" "$dir/"
expect "not a checkout, inside another repository" "$dir" "unknown" "unknown"

NoGit="$Work/no-git-bin"
mkdir -p "$NoGit"
for tool in ls sed tr uname xargs; do
    ln -s "$(command -v "$tool")" "$NoGit/$tool"
done
dir="$(app no-git)"
expect "git not installed" "$dir" "unknown" "unknown" PATH="$NoGit"

echo "$checks checks, $failures failures"
if [ "$failures" -ne 0 ]; then
    exit 1
fi
