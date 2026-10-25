#!/usr/bin/env python3
"""Exercise the production display hook from a real AGP class receipt."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
repo = tests.parents[3]
java_root = repo / 'TMessagesProj/src/main/java/org/telegram'
receipt = json.loads(args.receipt.read_text())
assert receipt['task'] == ':TMessagesProj:compileStandaloneJavaWithJavac'
classes = Path(receipt['destination'])
compiler = Path(receipt['compiler'])
assert classes.is_dir() and compiler.is_file()
assert not args.output.exists(), 'output directory must be unused'
args.output.mkdir(parents=True)


def method(text, signature):
    start = text.index(signature)
    begin = text.index('{', start)
    end, depth = begin + 1, 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


owned = java_root / 'messenger/purple/PurpleSearchRefresh.java'
adapter = java_root / 'ui/Adapters/DialogsSearchAdapter.java'
screen = java_root / 'ui/PurpleDialogsHooks.java'
activity = java_root / 'ui/DialogsActivity.java'
by_path = {Path(item['path']): item['sha256'] for item in receipt['sources']}
for path in [owned, adapter, screen, activity]:
    assert by_path.get(path) == hashlib.sha256(path.read_bytes()).hexdigest(), str(path) + ' receipt mismatch'
owned_text = owned.read_text()
for forbidden in ['loadHints', 'loadRecentSearch', 'putRecentSearch', 'savePeer', 'increasePeer',
                  'sendRequest', 'saveConfig', 'MessagesStorage', 'MediaDataController']:
    assert forbidden not in owned_text, forbidden
assert 'filterRecent(query == null ? null : query.trim())' in owned_text
projection = method(adapter.read_text(), 'public void filterRecent(String query)')
assert 'filteredRecentSearchObjects.add(recentSearchObjects.get(i))' in projection
assert 'filtered2RecentSearchObjects.add(obj)' in projection
assert 'recentSearchObjects.add(' not in projection and 'sendRequest' not in projection
notification = activity.read_text().split('id == NotificationCenter.dialogFiltersUpdated)', 1)[1].split('} else if', 1)[0]
assert notification.index('purple.refreshSearch(account, searchViewPager, searchIsShowed)') < notification.index('updateFilterTabs(true, true)')
show = method(activity.read_text(), 'private void showSearch(boolean show, boolean startFromDownloads, boolean animated, boolean forceNotOnlyDialogs)')
assert 'searchIsShowed = show;' in show
assert 'searchViewPager = null' not in activity.read_text()
assert 'searchViewPager.setVisibility(show ? View.VISIBLE : View.GONE);' in show
assert show.index('purple.refreshSearch(currentAccount, searchViewPager, searchIsShowed)') > show.index('searchViewPager.setVisibility(show ? View.VISIBLE : View.GONE);')
bridges = {
    'org/telegram/ui/PurpleDialogsHooks.java.in': method(screen.read_text(), 'public void refreshSearch(int account, SearchViewPager pager, boolean searching)'),
    'org/telegram/ui/Adapters/DialogsSearchAdapter.java.in': method(adapter.read_text(), 'public void refreshPurpleVisibility(int account)'),
}
port_root = tests / 'search_refresh/ports'
prepared = args.output / 'ports'
sources = []
for source in sorted(port_root.rglob('*')):
    if not source.is_file():
        continue
    relative = str(source.relative_to(port_root))
    data = source.read_text()
    if relative in bridges:
        assert data.count('__PURPLE_REFRESH_BRIDGE__') == 1
        data = data.replace('__PURPLE_REFRESH_BRIDGE__', bridges[relative])
    target = prepared / (relative[:-3] if relative.endswith('.in') else relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(data)
    sources.append(target)
sources.append(tests / 'search_refresh/SearchRefreshCases.java')
(args.output / 'inputs.json').write_text(json.dumps([
    {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    for path in [args.receipt, owned, adapter, screen, activity, *sources]
], indent=2) + '\n')
destination = args.output / 'classes'
destination.mkdir()
empty = args.output / 'empty-sourcepath'
empty.mkdir()
commands = {
    'javac': [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty),
              '-cp', str(classes), '-d', str(destination), *map(str, sources)],
    'host': [str(compiler.with_name('java')), '-Xmx256m', '-cp', str(destination) + os.pathsep + str(classes),
             'org.telegram.ui.SearchRefreshCases', str(classes)],
}
result = {}
for name, command in commands.items():
    (args.output / (name + '.argv.json')).write_text(json.dumps(command, indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log:
        completed = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
    result[name] = completed.returncode
    output = (args.output / (name + '.log')).read_text()
    print(name, completed.returncode, output[-2000:], flush=True)
    (args.output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    if completed.returncode:
        raise SystemExit(completed.returncode)
assert 'SEARCH_REFRESH_CHECKS=51' in (args.output / 'host.log').read_text()
