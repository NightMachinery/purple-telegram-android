#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/.." && pwd)"
BaseFile="$Root/purple/UPSTREAM_BASE"
Baseline="${PURPLE_HOOKS_BASELINE:-$Root/docs/upstream-hooks.baseline}"
Readme=README.md
Storage=TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java
Mirror=TMessagesProj/src/main/java/org/telegram/messenger/purple/PurpleUnreadMirror.java
SeenView=TMessagesProj/src/main/java/org/telegram/ui/Components/MessagePrivateSeenView.java
SeenGuard='PurpleLastSeenSheet.onLastSeenButton('
PrivacyOk="TMessagesProj/src/main/java/org/telegram/ui/PrivacyControlActivity.java TMessagesProj/src/main/java/org/telegram/tgnet/TLRPC.java $SeenView"
LibraryRes=TMessagesProj/src/main/res
AppRes=TMessagesProj_AppStandalone/src/main/res
export GIT_NO_LAZY_FETCH=1
export LC_ALL=C

usage() {
    cat >&2 <<'EOF'
usage: purple/upstream-hooks.sh report [--lines] [REV]
       purple/upstream-hooks.sh check [REV]
       purple/upstream-hooks.sh baseline [REV]

Measures Purple's edits to upstream files, the paths that exist at the commit
named in purple/UPSTREAM_BASE. Without REV it reads the working tree: tracked
files whether staged or not, and new files once marked with `git add -N`.

  report    lists every edited upstream file, the account-less Work Mode
            calls and the callers to classify at a merge
  check     fails when an edit grows past docs/upstream-hooks.baseline or
            breaks one of the rules in docs/upstream-merge.md
  baseline  rewrites docs/upstream-hooks.baseline from the tree and prints
            what changed
EOF
    exit 2
}

die() {
    echo "upstream-hooks: $*" >&2
    exit 2
}

g() {
    command git --no-optional-locks -C "$Root" "$@"
}

Work="$(mktemp -d "${TMPDIR:-/tmp}/purple-upstream-hooks.XXXXXX")"
trap 'command rm -rf "$Work"' EXIT

resolve_base() {
    [ -f "$BaseFile" ] || die "purple/UPSTREAM_BASE is missing"
    local sha
    sha="$(command tr -d '[:space:]' < "$BaseFile")"
    Base="$(g rev-parse --verify -q "$sha^{commit}")" ||
        die "purple/UPSTREAM_BASE names $sha, which this clone does not have"
}

resolve_tree() {
    local head index
    if [ -n "$Rev" ]; then
        Tree="$(g rev-parse --verify -q "$Rev^{tree}")" || die "no such revision: $Rev"
        head="$(g rev-parse --verify -q "$Rev^{commit}")" || head=""
        Label="$Rev"
    else
        index="$(g rev-parse --git-path index)"
        case "$index" in
            /*) ;;
            *) index="$Root/$index" ;;
        esac
        command cp "$index" "$Work/index"
        GIT_INDEX_FILE="$Work/index" g add -u
        Tree="$(GIT_INDEX_FILE="$Work/index" g write-tree)"
        head="$(g rev-parse --verify -q HEAD)"
        Label="the working tree"
    fi
    if [ -n "$head" ] && ! g merge-base --is-ancestor "$Base" "$head"; then
        die "the upstream base $Base is not an ancestor of $Label"
    fi
}

read -r -d '' StatsAwk <<'EOF' || true
BEGIN { OFS = "\t" }
function kind(p,   b) {
    b = p
    sub(/.*\//, "", b)
    if (b ~ /\.(java|kt|kts|gradle|c|cc|cpp|cxx|h|hpp|js|aidl)$/) return "c"
    if (b ~ /\.xml$/) return "x"
    if (b ~ /\.(properties|pro|cmake|sh|toml|cfg|mk|yml|yaml)$/ || b == "CMakeLists.txt" || b ~ /^\.git(ignore|modules|attributes)$/) return "h"
    return ""
}
function run_end() {
    if (run >= 2) {
        cr[f]++
        cl[f] += run
        print "C", f, rs, rs + run - 1, run
    }
    run = 0
}
function hunk_end() {
    run_end()
    if (ha > 3) {
        bg[f]++
        print "B", f, hs, hl, ha
    }
    if (hd > 0) print "R", f, hs, hd, ha
    inh = 0
}
function file_end() {
    if (inh) hunk_end()
    if (f != "") print "F", f, ad[f] + 0, de[f] + 0, hk[f] + 0, bg[f] + 0, cr[f] + 0, cl[f] + 0, ja[f] + 0, jc[f] + 0, jb[f] + 0, ji[f] + 0
}
/^diff --git a\// {
    file_end()
    p = substr($0, 14)
    f = substr(p, 1, (length(p) - 3) / 2)
    k = kind(f)
    java = (f ~ /\.java$/)
    inh = 0
    xo = 0
    next
}
/^@@ / {
    if (inh) hunk_end()
    match($0, /\+[0-9]+/)
    nl = substr($0, RSTART + 1, RLENGTH - 1) + 0
    hk[f]++
    inh = 1
    ha = 0
    hd = 0
    hs = nl
    hl = nl
    run = 0
    xo = 0
    next
}
!inh { next }
/^\+/ {
    s = substr($0, 2)
    t = s
    sub(/^[ \t]+/, "", t)
    ad[f]++
    ha++
    hl = nl
    c = 0
    if (k == "c") c = (t ~ /^(\/\/|\/\*|\*)/)
    else if (k == "h") c = (t ~ /^#/)
    else if (k == "x") {
        if (xo) {
            c = 1
            if (t ~ /-->/) xo = 0
        } else if (t ~ /^<!--/) {
            c = 1
            if (t !~ /-->/) xo = 1
        }
    }
    if (c) {
        if (run == 0) rs = nl
        run++
    } else run_end()
    if (java) {
        ja[f]++
        if (c) jc[f]++
        else if (t == "") jb[f]++
        else if (t ~ /^import[ \t]/) ji[f]++
    }
    r = s
    while (match(r, /PurpleGate\.(filtering|foldersRestricted|peeking|hidingEverywhere|hidingArchive|badgeRebuilt|refillViewPinsIfNeeded|hidingFromSuggestions|filteringStories|addStoryShown|state|recentStyle|menuLabel)\(\)|PurpleGate\.shownFilters\([^,()]*(\([^()]*\))?[^,()]*\)/)) {
        m = substr(r, RSTART + 11, RLENGTH - 11)
        sub(/\(.*/, "", m)
        print "A", f, nl, m, t
        r = substr(r, RSTART + RLENGTH)
    }
    nl++
    next
}
/^-/ {
    de[f]++
    hd++
    run_end()
    xo = 0
    next
}
/^ / {
    nl++
    run_end()
    xo = 0
    next
}
END { file_end() }
EOF

stats() {
    g -c core.quotePath=false diff --no-color --no-ext-diff --no-textconv --no-renames \
        --no-relative --diff-algorithm=myers --inter-hunk-context=0 \
        --src-prefix=a/ --dst-prefix=b/ -U3 --diff-filter=MDT "$Base" "$Tree" -- |
        command awk "$StatsAwk" > "$Work/stats"
}

callers() {
    local family pattern status line
    : > "$Work/callers"
    for family in getDialogFilters isDialogMuted; do
        case "$family" in
            getDialogFilters) pattern='getDialogFilters()' ;;
            isDialogMuted) pattern='isDialogMuted(' ;;
        esac
        status=0
        g grep -c -F -e "$pattern" "$Tree" -- '*.java' > "$Work/grep" || status=$?
        [ "$status" -le 1 ] || die "git grep failed while counting $pattern"
        while IFS= read -r line; do
            line="${line#"$Tree":}"
            printf 'caller %s %s %s\n' "${line%:*}" "$family" "${line##*:}" >> "$Work/callers"
        done < "$Work/grep"
    done
}

current() {
    printf 'base %s\n' "$Base"
    {
        command awk -F '\t' -v readme="$Readme" '
            $1 == "F" && $2 != readme { print "file", $2, $3, $4, $5 }
            $1 == "A" { n[$2 " " $4]++ }
            END { for (k in n) print "account", k, n[k] }
        ' "$Work/stats"
        command cat "$Work/callers"
    } | command sort
}

counter_fields() {
    g show "$Tree:$Storage" | command awk '
        { line[NR] = $0 }
        !at && $0 ~ /[ \t]dialogsWithUnread[ \t]*(=|;)/ { at = NR }
        function field(s) { return s ~ /^[ \t]*(private|protected|public)[ \t].*;[ \t]*$/ }
        function emit(s,   t) {
            if (s !~ /(^|[ \t])(int|long)(\[\])+[ \t]+[A-Za-z_]/) return
            t = s
            sub(/[ \t]*(=.*)?;[ \t]*$/, "", t)
            sub(/.*[ \t]/, "", t)
            print t
        }
        END {
            if (!at) exit 1
            for (i = at; i >= 1 && field(line[i]); i--) emit(line[i])
            for (i = at + 1; i <= NR && field(line[i]); i++) emit(line[i])
        }
    ' | command sort
}

has() {
    local status=0
    g grep -q -F -e "$1" "$Tree" -- "$2" || status=$?
    [ "$status" -le 1 ] || die "git grep failed on $2"
    return "$status"
}

files_with() {
    local status=0
    g grep -l -F -e "$1" "$Tree" -- '*.java' > "$Work/grep" || status=$?
    [ "$status" -le 1 ] || die "git grep failed while looking for $1"
    command sed "s|^$Tree:||" "$Work/grep" | command sort
}

edited() {
    command awk -F '\t' -v p="$1" '$1 == "F" && $2 == p { found = 1 } END { exit !found }' "$Work/stats"
}

fail() {
    printf 'FAIL %s\n' "$*" >> "$Work/fails"
}

check_app_names() {
    local f dir
    g ls-tree -r --name-only "$Tree" -- "$LibraryRes" |
        command grep -E "^$LibraryRes/values[^/]*/strings\\.xml\$" > "$Work/strings" || true
    while IFS= read -r f; do
        has 'name="AppName"' "$f" || continue
        edited "$f" && continue
        dir="${f%/strings.xml}"
        dir="${dir##*/}"
        has 'name="AppName"' "$AppRes/$dir/" ||
            fail "appname: $f defines AppName as upstream does, and $AppRes/$dir/ does not override it"
    done < "$Work/strings"
}

check_privacy() {
    local f
    if has 'TL_inputPrivacyValueAllowAll' "$SeenView" && ! has "$SeenGuard" "$SeenView"; then
        fail "privacy: $SeenView contains TL_inputPrivacyValueAllowAll without the guard line calling $SeenGuard...)"
    fi
    files_with TL_inputPrivacyKeyStatusTimestamp > "$Work/status"
    files_with TL_inputPrivacyValueAllowAll > "$Work/allowall"
    command comm -12 "$Work/status" "$Work/allowall" > "$Work/pairs"
    while IFS= read -r f; do
        case " $PrivacyOk " in
            *" $f "*) continue ;;
        esac
        g cat-file -e "$Base:$f" 2> /dev/null || continue
        fail "privacy: $f pairs TL_inputPrivacyKeyStatusTimestamp with TL_inputPrivacyValueAllowAll"
    done < "$Work/pairs"
}

check_counters() {
    local name
    g cat-file -e "$Tree:$Mirror" 2> /dev/null || return 0
    if ! counter_fields > "$Work/counters" || [ ! -s "$Work/counters" ]; then
        fail "counters: no counter arrays found next to dialogsWithUnread in $Storage"
        return 0
    fi
    g show "$Tree:$Mirror" > "$Work/mirror"
    while IFS= read -r name; do
        command grep -q -E "(^|[^A-Za-z0-9_])$name([^A-Za-z0-9_]|\$)" "$Work/mirror" ||
            fail "counters: $Storage declares $name next to dialogsWithUnread, and $Mirror does not name it"
    done < "$Work/counters"
}

prepare() {
    resolve_base
    resolve_tree
    stats
    callers
}

cmd_report() {
    local lines=0
    if [ "${1:-}" = "--lines" ]; then
        lines=1
        shift
    fi
    [ $# -le 1 ] || usage
    Rev="${1:-}"
    prepare
    printf 'Upstream files edited since %s, in %s.\n\n' "$(g log -1 --format='%h (%s)' "$Base")" "$Label"
    command awk -F '\t' -v readme="$Readme" -v lines="$lines" '
        FILENAME == ARGV[1] && $1 == "F" && $2 == readme { rma = $3; rmd = $4; next }
        FILENAME == ARGV[1] && $1 == "F" {
            order[++n] = $2
            if (n == 1) {
                printf "%7s %7s %6s %5s %10s  %s\n", "added", "removed", "hunks", "big", "comments", "path"
            }
            printf "%7s %7s %6d %5d %10s  %s\n", "+" $3, "-" $4, $5, $6, $7 "/" $8, $2
            ta += $3; td += $4; th += $5; tb += $6; tc += $7; tl += $8
            ja += $9; jc += $10; jb += $11; ji += $12
            next
        }
        FILENAME == ARGV[1] && $1 == "A" { acc[++na] = $2 ":" $3 "  " $4 "()"; next }
        FILENAME == ARGV[1] && ($1 == "C" || $1 == "B" || $1 == "R") {
            if ($1 == "C") d = "multi-line comment, lines " $3 "-" $4
            else if ($1 == "B") d = "hunk adding " $5 " lines, lines " $3 "-" $4
            else d = "hunk removing " $4 " upstream lines, at line " $3
            det[$2] = det[$2] "    " d "\n"
            next
        }
        FILENAME == ARGV[2] {
            split($0, w, " ")
            cn[w[3]] += w[4]
            cf[w[3]]++
            cl[w[3]] = cl[w[3]] sprintf("    %5d  %s\n", w[4], w[2])
            next
        }
        END {
            print ""
            print "  big: hunks adding more than 3 lines; comments: Purple comments longer than one line / their lines"
            printf "\nTotal: %d upstream files, +%d -%d, %d hunks, %d big hunks, %d multi-line comments (%d lines).\n", n, ta, td, th, tb, tc, tl
            printf "Java: %d added lines, %d of them comments, %d blank and %d imports.\n", ja, jc, jb, ji
            if (rma != "") printf "README.md: +%d -%d, Purple documentation before upstream'"'"'s text, not counted or ratcheted.\n", rma, rmd
            printf "\nPreset-level PurpleGate calls in upstream files without an account: %d.\n", na
            for (i = 1; i <= na; i++) print "  " acc[i]
            print "\nCallers to classify at an upstream merge:"
            printf "  getDialogFilters(): %d lines in %d files\n", cn["getDialogFilters"], cf["getDialogFilters"]
            if (lines) printf "%s", cl["getDialogFilters"]
            printf "  isDialogMuted(): %d lines in %d files\n", cn["isDialogMuted"], cf["isDialogMuted"]
            if (lines) printf "%s", cl["isDialogMuted"]
            if (lines) {
                print "\nWhere each file'"'"'s big hunks, multi-line comments and removals are (new-file line numbers):"
                for (i = 1; i <= n; i++) if (order[i] in det) printf "  %s\n%s", order[i], det[order[i]]
            }
        }
    ' "$Work/stats" "$Work/callers"
    if counter_fields > "$Work/counters" 2> /dev/null; then
        printf '\nCounter arrays declared next to dialogsWithUnread in MessagesStorage: %s.\n' "$(command tr '\n' ' ' < "$Work/counters" | command sed 's/ $//')"
    fi
}

cmd_check() {
    [ $# -le 1 ] || usage
    Rev="${1:-}"
    prepare
    [ -f "$Baseline" ] || die "docs/upstream-hooks.baseline is missing; run purple/upstream-hooks.sh baseline"
    : > "$Work/fails"
    current > "$Work/current"
    command awk -v stats="$Work/stats" '
        NR == FNR {
            if ($1 == "base") bb = $2
            else if ($1 == "file") { ba[$2] = $3; bd[$2] = $4 }
            else if ($1 == "account" || $1 == "caller") bn[$1 " " $2 " " $3] = $4
            next
        }
        $1 == "base" {
            if ($2 != bb) print "FAIL base: docs/upstream-hooks.baseline was made against " bb " and purple/UPSTREAM_BASE names " $2 "; follow docs/upstream-merge.md, then run purple/upstream-hooks.sh baseline"
            next
        }
        $1 == "file" {
            seen[$2] = 1
            a = ba[$2] + 0
            d = bd[$2] + 0
            if ($3 > a || $4 > d) print "FAIL ratchet: " $2 " is at +" $3 " -" $4 ", over its ratchet of +" a " -" d
            else if ($3 < a || $4 < d) low++
            next
        }
        {
            k = $1 " " $2 " " $3
            seen[k] = 1
            b = bn[k] + 0
            if ($4 > b && $1 == "account") {
                print "FAIL account: " $2 " calls PurpleGate." $3 "() without an account " $4 " times, over the " b " recorded as needing the account; pass the account (AGENTS.md)"
                while ((getline line < stats) > 0) {
                    split(line, x, "\t")
                    if (x[1] == "A" && x[2] == $2 && x[4] == $3) print "     line " x[3] ": " x[5]
                }
                close(stats)
            } else if ($4 > b) {
                print "FAIL caller: " $2 " has " $4 " lines calling " $3 ", over the " b " classified; classify the new call (docs/upstream-merge.md), then run purple/upstream-hooks.sh baseline"
            } else if ($4 < b) low++
        }
        END {
            for (p in ba) if (!(p in seen)) low++
            for (k in bn) if (!(k in seen)) low++
            if (low) print "note: " low " entries are now below docs/upstream-hooks.baseline; run purple/upstream-hooks.sh baseline to lower them"
        }
    ' "$Baseline" "$Work/current" > "$Work/ratchet"
    command grep -v '^note: ' "$Work/ratchet" >> "$Work/fails" || true
    check_app_names
    check_privacy
    check_counters
    command cat "$Work/fails"
    command grep '^note: ' "$Work/ratchet" || true
    if command grep -q '^FAIL ' "$Work/fails"; then
        printf 'upstream-hooks check: %d problems in %s\n' "$(command grep -c '^FAIL ' "$Work/fails")" "$Label"
        exit 1
    fi
    printf 'upstream-hooks check: OK for %s\n' "$Label"
}

cmd_baseline() {
    [ $# -le 1 ] || usage
    Rev="${1:-}"
    prepare
    current > "$Work/current"
    if [ -f "$Baseline" ]; then
        command diff "$Baseline" "$Work/current" || true
    fi
    command cp "$Work/current" "$Baseline"
    printf 'Wrote %s from %s.\n' "${Baseline#"$Root"/}" "$Label"
}

[ $# -ge 1 ] || usage
command="$1"
shift
case "$command" in
    report) cmd_report "$@" ;;
    check) cmd_check "$@" ;;
    baseline) cmd_baseline "$@" ;;
    *) usage ;;
esac
