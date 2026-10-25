#!/usr/bin/env python3
"""Run the numeric formatter/history from real AGP classes; replay the SELECT in SQLite."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--receipt', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
tests = Path(__file__).resolve().parent
repo = tests.parents[3]
java = repo / 'TMessagesProj/src/main/java/org/telegram'
receipt = json.loads(args.receipt.read_text())
assert receipt['task'] == ':TMessagesProj:compileStandaloneJavaWithJavac'
classes = Path(receipt['destination'])
compiler = Path(receipt['compiler'])
paths = [java / p for p in [
    'messenger/purple/PurpleRankingReport.java', 'messenger/purple/PurpleHintHistory.java',
    'messenger/purple/PurpleRankingDiag.java', 'messenger/PurpleRankingAccess.java',
    'messenger/MediaDataController.java', 'messenger/MessagesController.java',
    'messenger/MessagesStorage.java', 'tgnet/ConnectionsManager.java', 'messenger/purple/PurpleDiagLog.java', 'ui/PurpleDiagnosticsUI.java', 'messenger/purple/PurpleGate.java']]
hashes = {Path(item['path']): item['sha256'] for item in receipt['sources']}
for path in paths:
    assert hashes.get(path) == hashlib.sha256(path.read_bytes()).hexdigest(), str(path) + ' receipt mismatch'
collector = paths[2].read_text()
for forbidden in ['sendRequest', 'loadHints(', 'saveConfig', 'savePeer(', 'executeFast', 'FileLog',
                  'first_name', 'last_name', 'username', '.phone', 'getInstance(account).hints']:
    assert forbidden not in collector, forbidden
assert not re.search(r'\.id\b', collector)
assert 'MediaDataController.getExistingInstance(account)' in collector
for dependency in ['MessagesController', 'MessagesStorage', 'ConnectionsManager']:
    assert dependency + '.getExistingInstance(' in collector
    assert dependency + '.getInstance(' not in collector
assert 'storage.getStorageQueue().postRunnable' in collector
assert 'current.dialogs.clear()' in collector
assert 'long now = current.report.server;' in collector
assert 'config.getClientUserId() == pending.login' in collector
assert collector.count('sameLogin(current)') >= 4
assert 'if (!sameLogin(each)) changed(each)' in collector
assert 'PurpleHints.visibleHints' not in collector and 'suggestionFolders(' not in collector
assert 'PurpleGate.shownFiltersQuiet(' in collector
assert 'account.server - slot.lastOutgoing' in paths[0].read_text()
gate = (java / 'messenger/purple/PurpleGate.java').read_text()
quiet = gate.split('shownFiltersQuiet(', 1)[1].split('private static ArrayList<MessagesController.DialogFilter> shownFilters(', 1)[0]
assert 'return shownFilters(currentAccount, raw, false)' in quiet
shared = gate.split('int currentAccount, ArrayList<MessagesController.DialogFilter> raw, boolean log)', 1)[1].split('private static void logStrip(', 1)[0]
assert shared.count('if (log) logStrip(') == 2
export_path = collector.split('public static void export(', 1)[1].split('private static Pending capture', 1)[0]
assert export_path.index('long generation = PurpleHintHistory.generation()') < export_path.index('AndroidUtilities.runOnUIThread')
assert 'org.telegram.messenger.purple.PurpleRankingDiag.export(file ->' in (java / 'ui/PurpleDiagnosticsUI.java').read_text()
hooks = (java / 'messenger/MediaDataController.java').read_text()
increment = hooks.split('public void increasePeerRaiting(long dialogId)', 1)[1].split('private void savePeer', 1)[0]
assert increment.index('PurpleRankingDiag.rank(') < increment.index('peer.rating +=')
assert increment.index('Collections.sort(hints') < increment.index('PurpleRankingDiag.increment(') < increment.index('savePeer(')
assert hooks.count('PurpleRankingDiag.serverResult(currentAccount, response, hints.size())') == 1
for source in ['CACHE_LOAD', 'SERVER_START', 'REMOVE']:
    assert 'PurpleHintHistory.Source.' + source in hooks
assert 'PurpleRankingDiag.reset(currentAccount)' in hooks
sql_block = collector.split('cursor = storage.getDatabase().queryFinalized(', 1)[1].split('now - 86400', 1)[0]
sql = ''.join(re.findall(r'"([^"\n]*)"', sql_block))
assert sql.startswith('SELECT ') and sql.count('?') == 4 and 'FROM messages_v2 WHERE uid = ? AND out = 1' in sql
db = sqlite3.connect(':memory:')
db.execute('CREATE TABLE messages_v2 (uid INTEGER, out INTEGER, mid INTEGER, date INTEGER)')
now, peer = 2000000000, 9876543210123
db.executemany('INSERT INTO messages_v2 VALUES (?, ?, ?, ?)', [
    (peer, 1, 1, now - 10), (peer, 1, 2, now - 2 * 86400),
    (peer, 1, 3, now - 10 * 86400), (peer, 1, 4, now - 40 * 86400),
    (peer, 0, 9, now - 1), (77, 1, 8, now),
    (peer, 1, -1, now + 20), (peer, 1, 5, now - 86400)])
before = db.total_changes
row = db.execute(sql, (now - 86400, now - 7 * 86400, now - 30 * 86400, peer)).fetchone()
assert row == (3, 4, 5, 5, now + 20), row
assert db.total_changes == before
assert db.execute(sql, (now - 86400, now - 7 * 86400, now - 30 * 86400, 99)).fetchone() == (None,) * 5
assert not args.output.exists(), 'output must be unused'
args.output.mkdir(parents=True)
destination = args.output / 'classes'
destination.mkdir()
empty = args.output / 'empty-sourcepath'
empty.mkdir()
case = tests / 'ranking_diag/RankingReportCases.java'
collector_case = tests / 'ranking_diag/CollectorRegression.java'
ports_module = tests / 'ranking_diag/collector_ports.py'
namespace = {}
exec(compile(ports_module.read_text(), str(ports_module), 'exec'), namespace)
port_sources = namespace['prepare'](args.output / 'collector-ports')
collector_classes = args.output / 'collector-classes'
collector_classes.mkdir()
(args.output / 'inputs.json').write_text(json.dumps([
    {'path': str(p), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
    for p in [args.receipt, case, collector_case, ports_module, *paths, *port_sources]], indent=2) + '\n')
commands = {
    'javac': [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty),
              '-cp', str(classes), '-d', str(destination), str(case)],
    'host': [str(compiler.with_name('java')), '-Xmx256m', '-cp', str(destination) + os.pathsep + str(classes),
             'org.telegram.messenger.purple.RankingReportCases', str(classes)],
    'collector-javac': [str(compiler), '-source', '8', '-target', '8', '-proc:none', '-sourcepath', str(empty),
                        '-cp', str(classes), '-d', str(collector_classes), *map(str, port_sources), str(collector_case)],
    'collector-host': [str(compiler.with_name('java')), '-Xmx256m', '-cp', str(collector_classes) + os.pathsep + str(classes),
                       'org.telegram.messenger.purple.CollectorRegression', str(classes)],
}
results = {'sqliteReplay': 'PASS: read-only SELECT, boundaries, future and pending rows, account isolation, empty peer'}
for name, command in commands.items():
    (args.output / (name + '.argv.json')).write_text(json.dumps(command, indent=2) + '\n')
    with (args.output / (name + '.log')).open('w') as log:
        result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
    results[name] = result.returncode
    output = (args.output / (name + '.log')).read_text()
    print(name, result.returncode, output[-2000:], flush=True)
    (args.output / 'result.json').write_text(json.dumps(results, indent=2) + '\n')
    if result.returncode:
        raise SystemExit(result.returncode)
assert 'RANKING_DIAG_CHECKS=' in (args.output / 'host.log').read_text()
