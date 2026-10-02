#!/usr/bin/env bash
set -euo pipefail

Root="$(cd "$(dirname "$0")/../../../.." && pwd)"
Script="${PURPLE_HOOKS_SCRIPT:-$Root/purple/upstream-hooks.sh}"
Git="$(command -v git)"
Work="$(mktemp -d "${TMPDIR:-/tmp}/purple-upstream-hooks-test.XXXXXX")"
trap 'command rm -rf "$Work"' EXIT
Repo="$Work/repo"
Java="TMessagesProj/src/main/java/org/telegram"
Res="TMessagesProj/src/main/res"

checks=0
failures=0

g() {
    "$Git" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false \
        -c init.defaultBranch=master -C "$Repo" "$@"
}

hooks() {
    "$Repo/purple/upstream-hooks.sh" "$@"
}

put() {
    mkdir -p "$(dirname "$Repo/$1")"
    cat > "$Repo/$1"
}

bad() {
    failures=$((failures + 1))
    echo "  FAIL  $1"
    if [ -n "${2:-}" ]; then
        printf '%s\n' "$2" | sed 's/^/        /'
    fi
}

expect_check() {
    local name="$1" want="$2" rev="${3:-}" out status=0
    checks=$((checks + 1))
    out="$(hooks check $rev 2>&1)" || status=$?
    if [ "$want" = OK ]; then
        if [ "$status" -ne 0 ] || printf '%s\n' "$out" | grep -q '^FAIL '; then
            bad "$name: want OK, got exit $status" "$out"
        fi
    elif [ "$status" -ne 1 ] || ! printf '%s\n' "$out" | grep -q "^$want"; then
        bad "$name: want a line starting with '$want', got exit $status" "$out"
    fi
}

expect_line() {
    local name="$1" file="$2" line="$3"
    checks=$((checks + 1))
    grep -qxF -- "$line" "$file" || bad "$name: no line '$line' in $(basename "$file")" "$(cat "$file")"
}

expect_no_line() {
    local name="$1" file="$2" pattern="$3"
    checks=$((checks + 1))
    if grep -q -- "$pattern" "$file"; then
        bad "$name: unexpected '$pattern' in $(basename "$file")" "$(grep -- "$pattern" "$file")"
    fi
}

scenario() {
    g reset -q --hard refs/tags/purple
    g clean -qfd
}

mkdir -p "$Repo"
g init -q

put README.md <<'EOF'
Upstream readme.
EOF
put gradle.properties <<'EOF'
APP_PACKAGE=org.telegram.messenger
EOF
put "$Java/ui/DialogsActivity.java" <<'EOF'
package org.telegram.ui;

public class DialogsActivity {
    void a() {
        int x = 1;
        getDialogFilters();
    }

    void b() {
        int y = 2;
    }

    void c() {
        int z = 3;
    }

    void d() {
        int w = 4;
    }
}
EOF
put "$Java/messenger/MessagesStorage.java" <<'EOF'
package org.telegram.messenger;

public class MessagesStorage {
    private int[][] contacts = new int[][]{new int[2], new int[2]};
    private int[][] bots = new int[][]{new int[2], new int[2]};
    private int[] mentionGroups = new int[2];
    private LongSparseArray<Integer> dialogsWithMentions = new LongSparseArray<>();
    private LongSparseArray<Integer> dialogsWithUnread = new LongSparseArray<>();

    private int unrelated[] = new int[1];
}
EOF
put "$Java/messenger/ContactsController.java" <<'EOF'
package org.telegram.messenger;

public class ContactsController {
    Object key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
}
EOF
put "$Java/ui/Components/MessagePrivateSeenView.java" <<'EOF'
package org.telegram.ui.Components;

public class MessagePrivateSeenView {
    void button1() {
        Object key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        Object rule = new TLRPC.TL_inputPrivacyValueAllowAll();
    }
}
EOF
put "$Java/ui/PrivacyControlActivity.java" <<'EOF'
package org.telegram.ui;

public class PrivacyControlActivity {
    Object key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
    Object rule = new TLRPC.TL_inputPrivacyValueAllowAll();
}
EOF
put "$Java/tgnet/TLRPC.java" <<'EOF'
package org.telegram.tgnet;

public class TLRPC {
    public static class TL_inputPrivacyKeyStatusTimestamp {}
    public static class TL_inputPrivacyValueAllowAll {}
}
EOF
put "$Res/values/strings.xml" <<'EOF'
<resources>
    <string name="AppName">Telegram</string>
</resources>
EOF
put "$Res/values-ar/strings.xml" <<'EOF'
<resources>
    <string name="AppName">Telegram</string>
</resources>
EOF
g add -A
g commit -q -m upstream
g tag upstream

put "$Java/ui/DialogsActivity.java" <<'EOF'
package org.telegram.ui;

import org.telegram.messenger.purple.PurpleGate;

public class DialogsActivity {
    void a() {
        int x = 1;
        getDialogFilters();
    }

    void b() {
        // Purple: one
        // Purple: two
        if (PurpleGate.filtering()) {
            return;
        }
        int y = 2;
    }

    void c() {
        int z = PurpleGate.shownFilters(getDialogFiltersUnrestricted()).size();
    }

    void d() {
        int w = PurpleGate.shownFilters(currentAccount, raw).size() + (PurpleGate.hidingArchive(currentAccount) ? 1 : 0);
    }
}
EOF
put "$Java/ui/Components/MessagePrivateSeenView.java" <<'EOF'
package org.telegram.ui.Components;

public class MessagePrivateSeenView {
    void button1() {
        Object key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
        PurpleLastSeenTrade.show();
    }
}
EOF
put "$Res/values/strings.xml" <<'EOF'
<resources>
    <string name="AppName">Purple Telegram</string>
    <!-- Purple: one -->
    <!-- Purple: two -->
    <string name="PurpleOne">One</string>
</resources>
EOF
put "$Res/values-ar/strings.xml" <<'EOF'
<resources>
    <string name="AppName">Purple Telegram</string>
</resources>
EOF
put "$Java/ui/PurpleLastSeenTrade.java" <<'EOF'
package org.telegram.ui;

public class PurpleLastSeenTrade {
    Object key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
    Object rule = new TLRPC.TL_inputPrivacyValueAllowAll();
    Object filters = getDialogFilters();
}
EOF
printf 'Purple readme.\n\n' | cat - "$Repo/README.md" > "$Work/readme"
cp "$Work/readme" "$Repo/README.md"
mkdir -p "$Repo/purple" "$Repo/docs"
cp "$Script" "$Repo/purple/upstream-hooks.sh"
chmod +x "$Repo/purple/upstream-hooks.sh"
g rev-parse upstream > "$Repo/purple/UPSTREAM_BASE"
g add -A
hooks baseline > /dev/null
g add -A
g commit -q -m purple
g tag purple

Base="$Repo/docs/upstream-hooks.baseline"
D="$Java/ui/DialogsActivity.java"
expect_line "baseline names the base" "$Base" "base $(g rev-parse upstream)"
expect_line "baseline counts DialogsActivity" "$Base" "file $D 9 2 2"
expect_line "baseline counts the default strings" "$Base" "file $Res/values/strings.xml 4 1 1"
expect_line "baseline counts MessagePrivateSeenView" "$Base" "file $Java/ui/Components/MessagePrivateSeenView.java 1 1 1"
expect_no_line "README.md is not ratcheted" "$Base" "README.md"
expect_no_line "untouched upstream files are not listed" "$Base" "gradle.properties"
expect_no_line "Purple files are not upstream files" "$Base" "^file .*PurpleLastSeenTrade"
expect_line "an account-less filtering() is recorded" "$Base" "account $D filtering 1"
expect_line "an account-less shownFilters(raw) is recorded" "$Base" "account $D shownFilters 1"
expect_no_line "calls that pass the account are not recorded" "$Base" "hidingArchive"
expect_line "getDialogFilters() callers are classified" "$Base" "caller $D getDialogFilters 1"
expect_line "Purple callers are classified too" "$Base" "caller $Java/ui/PurpleLastSeenTrade.java getDialogFilters 1"

hooks report > "$Work/report"
expect_line "report totals" "$Work/report" \
    "Total: 4 upstream files, +15 -5, 5 hunks, 2 big hunks, 2 multi-line comments (4 lines)."
expect_line "report Java breakdown" "$Work/report" \
    "Java: 10 added lines, 2 of them comments, 1 blank and 1 imports."
expect_line "report README.md apart" "$Work/report" \
    "README.md: +2 -0, Purple documentation before upstream's text, not counted or ratcheted."
expect_line "report lists the account-less lines" "$Work/report" "  $D:14  filtering()"
expect_line "report lists the counters" "$Work/report" \
    "Counter arrays declared next to dialogsWithUnread in MessagesStorage: bots contacts mentionGroups."

scenario
expect_check "a clean tree passes" OK
printf 'Purple readme.\n' >> "$Repo/README.md"
expect_check "README.md may grow" OK

scenario
printf '// Purple: more\n' >> "$Repo/$D"
expect_check "an unstaged line in an upstream file fails the ratchet" "FAIL ratchet: $D"
expect_check "the same commit without the edit passes" OK refs/tags/purple

scenario
printf 'APP_PACKAGE=org.purple.telegram\n' > "$Repo/gradle.properties"
expect_check "an upstream file outside the ratchet must equal upstream" "FAIL ratchet: gradle.properties"

scenario
sed -i.bak 's/^            return;$/            if (PurpleGate.state()) return;/' "$Repo/$D"
expect_check "a new account-less Work Mode call fails" "FAIL account: $D calls PurpleGate.state()"

scenario
sed -i.bak 's/PurpleGate.filtering()/PurpleGate.filtering(currentAccount)/' "$Repo/$D"
expect_check "a Work Mode call that carries the account passes" OK

scenario
put "$Java/ui/PurpleNew.java" <<'EOF'
package org.telegram.ui;

public class PurpleNew {
    Object filters = getDialogFilters();
    boolean muted = isDialogMuted(1);
}
EOF
g add -N "$Java/ui/PurpleNew.java"
expect_check "a new getDialogFilters() caller fails" "FAIL caller: $Java/ui/PurpleNew.java has 1 lines calling getDialogFilters"
expect_check "a new isDialogMuted() caller fails" "FAIL caller: $Java/ui/PurpleNew.java has 1 lines calling isDialogMuted"

scenario
g checkout -q upstream -- "$Res/values-ar/strings.xml"
expect_check "a restored locale without an app override fails" "FAIL appname: $Res/values-ar/strings.xml"
put TMessagesProj_AppStandalone/src/main/res/values-ar/purple_app_name.xml <<'EOF'
<resources>
    <string name="AppName">Purple Telegram</string>
</resources>
EOF
g add -N TMessagesProj_AppStandalone/src/main/res/values-ar/purple_app_name.xml
expect_check "a restored locale with an app override passes" OK

scenario
g checkout -q upstream -- "$Java/ui/Components/MessagePrivateSeenView.java"
expect_check "AllowAll in MessagePrivateSeenView without the guard fails" \
    "FAIL privacy: $Java/ui/Components/MessagePrivateSeenView.java contains"
sed -i.bak 's/^    void button1() {$/    void button1() { if (lastSeen \&\& PurpleLastSeenSheet.onLastSeenButton(sheet)) return;/' \
    "$Repo/$Java/ui/Components/MessagePrivateSeenView.java"
expect_check "AllowAll in MessagePrivateSeenView behind the guard passes" OK

scenario
put "$Java/messenger/ContactsController.java" <<'EOF'
package org.telegram.messenger;

public class ContactsController {
    Object key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
    Object rule = new TLRPC.TL_inputPrivacyValueAllowAll();
}
EOF
expect_check "an upstream file pairing the last-seen key with AllowAll fails" \
    "FAIL privacy: $Java/messenger/ContactsController.java pairs"

scenario
put "$Java/messenger/purple/PurpleUnreadMirror.java" <<'EOF'
package org.telegram.messenger.purple;

public class PurpleUnreadMirror {
    void start(int account, int[][] contacts, int[][] bots, int[] mentionGroups) {}
}
EOF
g add -N "$Java/messenger/purple/PurpleUnreadMirror.java"
expect_check "a mirror naming every counter passes" OK
put "$Java/messenger/MessagesStorage.java" <<'EOF'
package org.telegram.messenger;

public class MessagesStorage {
    private int[][] contacts = new int[][]{new int[2], new int[2]};
    private int[][] bots = new int[][]{new int[2], new int[2]};
    private int[] mentionGroups = new int[2];
    private int[][] premium = new int[][]{new int[2], new int[2]};
    private LongSparseArray<Integer> dialogsWithMentions = new LongSparseArray<>();
    private LongSparseArray<Integer> dialogsWithUnread = new LongSparseArray<>();

    private int unrelated[] = new int[1];
}
EOF
expect_check "a counter array the mirror does not name fails" \
    "FAIL counters: $Java/messenger/MessagesStorage.java declares premium"

scenario
g checkout -q -b next upstream
printf 'APP_PACKAGE=org.telegram.messenger\nAPP_VERSION=2\n' > "$Repo/gradle.properties"
g commit -q -am next-upstream
g checkout -q master
g merge -q --no-edit next
g rev-parse next > "$Repo/purple/UPSTREAM_BASE"
expect_check "a new base needs a new baseline" "FAIL base:"
hooks baseline > /dev/null
expect_check "a regenerated baseline passes" OK

echo "upstream-hooks: $((checks - failures))/$checks checks passed"
[ "$failures" -eq 0 ]
