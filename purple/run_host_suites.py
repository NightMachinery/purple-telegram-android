#!/usr/bin/env python3
# Runs every Purple Android host suite against one tree, after the real AGP
# compile of that tree and a host build of its JNI library, and writes one
# results.json. purple/host_suites.json is the canonical list; see AGENTS.md.
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import runpy
import shlex
import shutil
import signal
import subprocess
import sys
import time

script = Path(__file__).resolve()
BUILT = {'agp', 'host-library'}
DRIVERS = ['TMessagesProj/jni/purple_jni/tests', 'purple/tests']
COMPILERS = {'javac', 'clang', 'clang++', 'cc', 'c++', 'gcc', 'g++'}


def exec_driver(calls, wrapper, argv):
    # Runs one suite driver in this process, with the compile wrapper in front
    # of every compiler it starts, and logs each compiler call.
    prefix = json.loads(wrapper)
    original = subprocess.Popen

    class Wrapped(original):
        def __init__(self, command, *rest, **options):
            if isinstance(command, (list, tuple)) and command and Path(str(command[0])).name in COMPILERS:
                with open(calls, 'a') as handle:
                    handle.write(json.dumps({'suite': os.environ.get('PURPLE_HOST_SUITE'), 'argv': list(map(str, command)),
                        'cwd': str(options.get('cwd') or os.getcwd())}) + '\n')
                command = [*prefix, *map(str, command)]
            super().__init__(command, *rest, **options)
    subprocess.Popen = Wrapped
    sys.argv = list(argv)
    runpy.run_path(argv[0], run_name='__main__')


def blob(data):
    return hashlib.sha1(b'blob %d\0' % len(data) + data).hexdigest()


def file_mode(path):
    return '100755' if path.stat().st_mode & 0o100 else '100644'


def git_tree(entries):
    # The git tree object id of {path: (mode, sha)}.
    root = {}
    for path, value in entries.items():
        node = root
        *parents, name = path.split('/')
        for parent in parents:
            node = node.setdefault(parent, {})
        node[name] = value

    def write(node):
        items = []
        for name, value in node.items():
            raw = name.encode('utf-8', 'surrogateescape')
            if isinstance(value, dict):
                items.append((raw + b'/', b'40000', raw, write(value)))
            else:
                items.append((raw, value[0].encode(), raw, value[1]))
        body = b''.join(mode + b' ' + raw + b'\0' + bytes.fromhex(sha) for _, mode, raw, sha in sorted(items))
        return hashlib.sha1(b'tree %d\0' % len(body) + body).hexdigest()
    return write(root)


def toplevel(folder):
    try:
        top = subprocess.run(['git', '-C', str(folder), 'rev-parse', '--show-toplevel'], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return False
    return bool(top) and Path(top).resolve() == folder.resolve()


def tree_hash(tree):
    # The hash `git add -A && git write-tree` would give, computed without
    # writing to the repository. Like write-tree, it records a submodule only
    # by its commit; submodules() hashes their contents.
    def names(*args):
        out = subprocess.run(['git', '-C', str(tree), *args], capture_output=True, check=True).stdout
        return [value.decode('utf-8', 'surrogateescape') for value in out.split(b'\0') if value]

    entries = {}
    for line in names('ls-files', '-s', '-z'):
        meta, path = line.split('\t', 1)
        mode, sha, _ = meta.split()
        entries[path] = (mode, sha)
    for path in names('ls-files', '-z', '--deleted'):
        entries.pop(path, None)
    for path, (mode, sha) in list(entries.items()):
        if mode == '160000' and (tree / path / '.git').exists():
            entries[path] = (mode, subprocess.run(['git', '-C', str(tree / path), 'rev-parse', 'HEAD'], capture_output=True, text=True, check=True).stdout.strip())
    changed = [path for path in names('ls-files', '-z', '--modified', '--others', '--exclude-standard')
        if not path.endswith('/') and entries.get(path, ('',))[0] != '160000' and os.path.lexists(tree / path)]
    paths = []
    for path in changed:
        if os.path.islink(tree / path):
            entries[path] = ('120000', blob(os.readlink(tree / path).encode('utf-8', 'surrogateescape')))
        else:
            entries[path] = (file_mode(tree / path), None)
            paths.append(path)
    if paths:
        shas = subprocess.run(['git', '-C', str(tree), 'hash-object', '--stdin-paths'], input='\n'.join(paths) + '\n',
            capture_output=True, text=True, check=True).stdout.split()
        for path, sha in zip(paths, shas, strict=True):
            entries[path] = (entries[path][0], sha)
    return git_tree(entries)


def submodules(tree, repos):
    # Every submodule of the tree. A populated one is hashed like a git tree
    # from its working files and compared with the tree its gitlink names,
    # found in its own .git or in the repository --submodule-repo gives for its
    # path (a bare mirror will do; it is read offline).
    listing = subprocess.run(['git', '-C', str(tree), 'ls-files', '-s', '-z'], capture_output=True, check=True).stdout
    result = {}
    for line in [value.decode() for value in listing.split(b'\0') if value]:
        meta, path = line.split('\t', 1)
        mode, link, _ = meta.split()
        if mode != '160000':
            continue
        folder = tree / path
        if not folder.is_dir() or not any(folder.iterdir()):
            result[path] = {'gitlink': link, 'state': 'unpopulated'}
            continue
        if (folder / '.git').exists():
            repo, content = folder, tree_hash(folder)
        else:
            repo = repos.get(path)
            names = [str(p.relative_to(folder)) for p in folder.rglob('*') if (p.is_file() or p.is_symlink()) and '.git' not in p.relative_to(folder).parts]
            ignored = set()
            if repo:
                ignored = set(subprocess.run(['git', '--git-dir', str(git_dir(repo)), '--work-tree', str(folder), 'check-ignore', '--no-index', '--stdin'],
                    input='\n'.join(names) + '\n', capture_output=True, text=True).stdout.splitlines())
            content = git_tree({name: ('120000', blob(os.readlink(folder / name).encode())) if (folder / name).is_symlink()
                else (file_mode(folder / name), blob((folder / name).read_bytes())) for name in names if name not in ignored})
        expected = None
        if repo:
            found = subprocess.run(['git', '--git-dir', str(git_dir(repo)), 'rev-parse', '--verify', '-q', link + '^{tree}'], capture_output=True, text=True)
            expected = found.stdout.strip() or None
        result[path] = {'gitlink': link, 'state': 'matches gitlink' if content == expected else 'differs from gitlink' if expected else 'unverifiable',
            'hash': content, 'gitlinkTree': expected}
    return result


def git_dir(repo):
    found = subprocess.run(['git', '-C', str(repo), 'rev-parse', '--absolute-git-dir'], capture_output=True, text=True)
    return Path(found.stdout.strip()) if found.returncode == 0 else Path(repo)


def suite_choices(path):
    # The values a Python driver's --suite option accepts, from its source:
    # None when it declares --suite with choices that are not a literal list.
    choices = set()
    for node in ast.walk(ast.parse(path.read_text(), str(path))):
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == 'add_argument'
                and any(isinstance(arg, ast.Constant) and arg.value == '--suite' for arg in node.args)):
            given = [keyword.value for keyword in node.keywords if keyword.arg == 'choices']
            try:
                values = ast.literal_eval(given[0]) if given else None
            except ValueError:
                values = None
            if not isinstance(values, (list, tuple, set)) or not all(isinstance(value, str) for value in values):
                return None
            choices |= set(values)
    return choices


def dependencies(suite):
    return re.findall(r'\{out:([^}]+)\}', json.dumps(suite.get('args', [])))


def provided(suites):
    # What the built-in configurations make, by the placeholder that names it:
    # an AGP compile its receipt, host-library the library.
    return {suite['provides']: suite['name'] for suite in suites if suite.get('kind') in BUILT and 'provides' in suite}


def prerequisites(suite, suites):
    # The built-in configurations whose product a suite's arguments name.
    text = json.dumps(suite.get('args', []))
    return [name for key, name in provided(suites).items() if '{' + key + '}' in text]


ALIAS = re.compile(r'exec python3 "\$(?:Tests|Root/TMessagesProj/jni/purple_jni/tests)/(run_\w+\.py)" --suite ([\w-]+) "\$@"')


def alias_problems(tree, listing, by_name):
    # An alias is a shell driver that only hands its arguments to one --suite
    # of a listed driver: apart from the shebang, `set -euo pipefail` and one
    # folder variable, its single command is that exec.
    problems = []
    for relative, name in sorted(listing.get('aliases', {}).items()):
        suite = by_name.get(name)
        path = tree / relative
        if suite is None or 'driver' not in suite:
            problems.append(relative + ' is an alias of ' + name + ', which is not a listed driver configuration')
            continue
        if not path.exists():
            problems.append(relative + ' is recorded as an alias, but does not exist')
            continue
        lines = [line.strip() for line in path.read_text().splitlines()]
        rest = [line for line in lines if line and not line.startswith('#') and line != 'set -euo pipefail'
            and not re.fullmatch(r'(Tests|Root)="\$\(cd "\$\(dirname "\$0"\)(/\.\./\.\./\.\./\.\.)?" && pwd\)"', line)]
        match = ALIAS.fullmatch(rest[0]) if len(rest) == 1 else None
        arguments = suite.get('args', [])
        accepted = {arguments[arguments.index('--suite') + 1]} if '--suite' in arguments else set()
        accepted |= set(suite.get('covers', []))
        if not match:
            problems.append(relative + ' is recorded as an alias, but does more than exec one --suite of a driver')
        elif suite['driver'].rsplit('/', 1)[1] != match.group(1) or match.group(2) not in accepted:
            problems.append(relative + ' runs ' + match.group(1) + ' --suite ' + match.group(2) + ', which ' + name + ' does not run or cover')
    return problems


def self_check(tree, listing):
    problems = []
    suites = listing['suites']
    names = [suite['name'] for suite in suites]
    problems += [name + ' is listed twice' for name in sorted({n for n in names if names.count(n) > 1})]
    drivers = {suite['driver'] for suite in suites if 'driver' in suite}
    undriven = listing.get('undriven', {})
    aliases = listing.get('aliases', {})
    problems += alias_problems(tree, listing, {suite['name']: suite for suite in suites})
    for folder in DRIVERS:
        for path in sorted((tree / folder).rglob('*')):
            if path.is_file() and path.suffix in ('.py', '.sh') and path.name.startswith(('run_', 'test_')):
                relative = str(path.relative_to(tree))
                if sum([relative in drivers, relative in undriven, relative in aliases]) != 1:
                    problems.append(relative + ' must be exactly one of: a driver in host_suites.json, an alias, or undriven')
    for relative in sorted(undriven):
        if relative in drivers:
            problems.append(relative + ' is recorded as undriven, but a configuration runs it')
        elif not (tree / relative).exists():
            problems.append(relative + ' is recorded as undriven, but does not exist')
    seen = set()
    for driver in sorted(drivers):
        if not (tree / driver).exists():
            problems.append(driver + ' is listed, but does not exist')
            continue
        # Every --suite value the driver accepts is run by a configuration,
        # either as its argument or as one it declares it covers.
        configured = set()
        for suite in suites:
            if suite.get('driver') == driver:
                arguments = suite.get('args', [])
                if '--suite' in arguments:
                    configured.add(arguments[arguments.index('--suite') + 1])
                configured |= set(suite.get('covers', []))
        choices = suite_choices(tree / driver) if driver.endswith('.py') else set()
        if choices is None:
            problems.append(driver + ' declares --suite with choices that are not a literal list, so its variants cannot be established')
            continue
        for choice in sorted(choices - configured):
            problems.append(driver + ' accepts --suite ' + choice + ', which no configuration runs or covers')
    for suite in suites:
        if 'phases' in suite and not (isinstance(suite['phases'], list) and suite['phases'] and all(isinstance(v, str) and v for v in suite['phases'])):
            problems.append(suite['name'] + ' has an empty or invalid phases list')
        if {'-h', '--help'} & set(suite.get('args', [])):
            problems.append(suite['name'] + ' passes --help, which runs no checks')
        expects = suite.get('expects')
        if 'driver' in suite and not (isinstance(expects, dict) and expects and all(isinstance(key, str) and ':' in key
                and (value == 'PASS' or (type(value) is int and value > 0 and ':LINE ' not in key)) for key, value in expects.items())):
            problems.append(suite['name'] + ' has no expects table: each completion record (log:KEY) it must print, with a positive floor or "PASS"')
    makers = [suite.get('provides') for suite in suites if suite.get('kind') in BUILT]
    problems += [suite['name'] + ' must name what it provides' for suite in suites if suite.get('kind') in BUILT and not suite.get('provides')]
    problems += [key + ' is provided twice' for key in sorted({key for key in makers if key and makers.count(key) > 1})]
    problems += [suite['name'] + ' must name its AGP task' for suite in suites if suite.get('kind') == 'agp' and not suite.get('task')]
    for suite in suites:
        for dependency in dependencies(suite) + prerequisites(suite, suites):
            if dependency not in seen:
                problems.append(suite['name'] + ' reuses ' + dependency + ', which is not listed before it')
        for key in sorted(set(re.findall(r'\{([\w-]+)\}', json.dumps(suite.get('args', [])))) - {'json', 'output'} - set(provided(suites))):
            problems.append(suite['name'] + ' names {' + key + '}, which no configuration provides')
        seen.add(suite['name'])
    return problems


def stop(group, grace):
    # SIGTERM to the whole group first, so that a compile wrapper still
    # running can release its hold, then SIGKILL for whatever is left after the
    # grace, whether or not the group's leader has already exited.
    try:
        os.killpg(group, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        return
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        try:
            os.killpg(group, 0)
        except (ProcessLookupError, PermissionError):
            return
        time.sleep(0.1)
    try:
        os.killpg(group, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def execute(command, log, env, cwd, timeout):
    start = time.monotonic()
    with log.open('ab') as handle:
        handle.write(('$ ' + shlex.join(map(str, command)) + '\n').encode())
        handle.flush()
        process = subprocess.Popen(list(map(str, command)), cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=handle,
            stderr=subprocess.STDOUT, start_new_session=True)
        timed_out = False
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            stop(process.pid, 10)
            code = process.wait()
        except BaseException:
            stop(process.pid, 5)
            process.wait()
            raise
        # Nothing a step started outlives it.
        stop(process.pid, 2)
    return code, round(time.monotonic() - start, 1), timed_out


def checks(folder, only=None, failures=None):
    # The counts each suite prints: KEY=N lines, "N checks, M failures",
    # "name: N/N checks passed", and "... passed" lines that carry no count.
    # A recognised failure report (M failures with M > 0, or KEY=FAIL) is
    # appended to failures.
    found = {}
    for log in sorted(folder.rglob((only or '*') + '.log')):
        for line in log.read_text(errors='replace').splitlines():
            if match := re.fullmatch(r'([A-Z][A-Za-z0-9_-]*)=(\d+)', line.strip()):
                found[log.stem + ':' + match.group(1)] = int(match.group(2))
            elif match := re.fullmatch(r'([A-Z][A-Za-z0-9_-]*)=PASS', line.strip()):
                found[log.stem + ':' + match.group(1)] = 'PASS'
            elif match := re.fullmatch(r'([A-Z][A-Za-z0-9_-]*)=FAIL', line.strip()):
                if failures is not None:
                    failures.append(log.stem + ':' + match.group(1) + '=FAIL')
            elif match := re.fullmatch(r'(?:(\w[\w -]*): )?(\d+) checks, (\d+) failures', line.strip()):
                found[log.stem + ':' + (match.group(1) or 'checks')] = int(match.group(2))
                if int(match.group(3)) and failures is not None:
                    failures.append(log.stem + ': ' + line.strip())
            elif match := re.fullmatch(r'([\w-]+): (\d+)/\2 checks passed', line.strip()):
                found[log.stem + ':' + match.group(1)] = int(match.group(2))
            elif match := re.fullmatch(r'(\w+) host test passed', line.strip()):
                found[log.stem + ':' + match.group(1)] = 'passed'
    return found


def wrapper_version(tree):
    url = re.search(r'^distributionUrl=(.*)$', (tree / 'gradle/wrapper/gradle-wrapper.properties').read_text(), re.M).group(1).replace('\\:', ':')
    name = url.rsplit('/', 1)[1]
    return re.fullmatch(r'gradle-(.+)-(?:bin|all)\.zip', name).group(1), name


def wrapper_installation(tree, env):
    # Where the wrapper keeps the distribution its URL names: the folder is
    # the base-36 MD5 of the URL, as the wrapper computes it, and holds the
    # unpacked gradle-<version> beside the .ok marker of a finished unpack.
    version, archive = wrapper_version(tree)
    url = re.search(r'^distributionUrl=(.*)$', (tree / 'gradle/wrapper/gradle-wrapper.properties').read_text(), re.M).group(1).replace('\\:', ':')
    number, key = int.from_bytes(hashlib.md5(url.encode()).digest(), 'big'), ''
    while number:
        number, digit = divmod(number, 36)
        key = '0123456789abcdefghijklmnopqrstuvwxyz'[digit] + key
    home = Path(env.get('GRADLE_USER_HOME') or Path.home() / '.gradle')
    folder = home / 'wrapper/dists' / archive[:-len('.zip')] / (key or '0')
    launcher = folder / ('gradle-' + version) / 'bin/gradle'
    return launcher if (folder / (archive + '.ok')).is_file() and launcher.is_file() else None, folder


def gradle_launcher(args, tree, env, log):
    # The Gradle to run, always a launcher of exactly the wrapper's version:
    # --gradle, or the installation the wrapper already unpacked for its URL.
    # The wrapper itself never runs, so nothing can be downloaded.
    version, archive = wrapper_version(tree)
    gradle = args.gradle
    if not gradle:
        gradle, folder = wrapper_installation(tree, env)
        if gradle is None:
            raise RuntimeError('the wrapper\'s Gradle ' + version + ' is not unpacked at ' + str(folder)
                + ', and ./gradlew would download it; pass --gradle PATH to an extracted Gradle ' + version)
    if gradle:
        out = subprocess.run([str(gradle), '--version'], env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL)
        found = re.search(r'^Gradle (\S+)$', out.stdout, re.M)
        with log.open('a') as handle:
            handle.write(out.stdout + out.stderr)
        if out.returncode or not found:
            raise RuntimeError(str(gradle) + ' --version failed')
        if found.group(1) != version:
            raise RuntimeError(str(gradle) + ' is Gradle ' + found.group(1) + ', but the wrapper pins ' + version)
        return [str(gradle)]


def agp_compile(args, tree, out, env, suite, row):
    log = out / 'logs' / (suite['name'] + '.log')
    receipt = out / 'receipts' / (suite['name'] + '.json')
    receipt.parent.mkdir(exist_ok=True)
    task = suite['task']
    properties = tree / 'local.properties'
    written = None
    for folder in re.findall(r"projectDir = file\('([^']+)'\)", (tree / 'settings.gradle').read_text()):
        if not (tree / folder).is_dir() or not any((tree / folder).iterdir()):
            raise RuntimeError(folder + ', a project the build includes, is empty; populate its submodule')
    launcher = gradle_launcher(args, tree, env, log)
    if properties.is_symlink():
        raise RuntimeError('local.properties is a symbolic link; the runner neither follows nor replaces it')
    try:
        if not os.path.lexists(properties):
            # The library module's configuration needs PURPLE_QT_ANDROID even
            # though javac never uses it. The runner writes that one key only
            # into a tree that has no local.properties, creating the file
            # itself (never through a link), and later removes exactly that
            # file; it never reads an existing one.
            if not args.qt_android:
                raise RuntimeError('the tree has no local.properties; pass --qt-android PATH, the Qt 6 Android prefix')
            descriptor = os.open(properties, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            # The identity of the file this call created, from its own
            # descriptor while still open, never from whatever the path names
            # later.
            written = os.fstat(descriptor)
            with os.fdopen(descriptor, 'w') as handle:
                handle.write('PURPLE_QT_ANDROID=' + str(args.qt_android) + '\n')
        # --rerun makes Gradle run javac even when the task is up to date, and
        # with the build cache off nothing is restored from it, so the receipt
        # can require that this compile really ran.
        command = [*shlex.split(args.build_wrapper), *launcher, '--offline', '--no-daemon', '--no-build-cache', '--console=plain',
            '--init-script', str(script.parent / 'gradle/host-receipt.gradle'), *args.gradle_arg, task, '--rerun']
        row['command'] = command
        code, row['seconds'], timed_out = execute(command, log, dict(env, PURPLE_HOST_RECEIPT=str(receipt), PURPLE_HOST_TASK=task), tree, args.timeout)
    finally:
        if written is not None:
            now = os.lstat(properties) if os.path.lexists(properties) else None
            if now is not None and (now.st_dev, now.st_ino) == (written.st_dev, written.st_ino) and not properties.is_symlink():
                properties.unlink()
    row['exit'] = code
    if code or timed_out:
        raise RuntimeError('timed out' if timed_out else 'Gradle exited ' + str(code))
    data = json.loads(receipt.read_text())
    if data.get('task') != task:
        raise RuntimeError('the receipt names ' + str(data.get('task')))
    row.update({key: data.get(key) for key in ['executed', 'didWork', 'skipped', 'skipMessage', 'upToDate']})
    if not (data.get('executed') is True and data.get('didWork') is True and data.get('skipped') is False and data.get('upToDate') is False):
        raise RuntimeError(task + ' did not compile in this run (' + ', '.join(key + '=' + str(data.get(key))
            for key in ['executed', 'didWork', 'skipped', 'skipMessage', 'upToDate']) + ')')
    return receipt


def host_library(args, tree, out, row):
    # libpurplecore for the host, from exactly the sources the app's
    # purplecore library lists in purple.cmake.
    jni = tree / 'TMessagesProj/jni'
    cmake = (jni / 'purple_jni/purple.cmake').read_text()
    sources = [jni / name for name in re.search(r'add_library\(purplecore SHARED(.*?)\)', cmake, re.S).group(1).split()]
    java_home = Path(args.java_home)
    darwin = platform.system() == 'Darwin'
    qt = Path(args.qt_frameworks) if args.qt_frameworks else None
    if darwin and not qt:
        raise RuntimeError('pass --qt-frameworks, the host Qt frameworks folder')
    folder = out / 'host-library'
    folder.mkdir()
    log = out / 'logs/host-library.log'
    wrapper = shlex.split(args.compile_wrapper)
    includes = ['-I' + str(jni / 'purple'), '-I' + str(jni / 'purple/tomlplusplus'), '-I' + str(java_home / 'include'),
        '-I' + str(java_home / 'include' / ('darwin' if darwin else 'linux'))]
    if qt:
        includes += ['-F' + str(qt), '-I' + str(qt / 'QtCore.framework/Headers')]
    objects, seconds = [], 0
    for source in sources:
        free_space(args, out)
        objects.append(folder / (source.stem + '.o'))
        code, spent, timed_out = execute([*wrapper, args.cxx, '-std=c++20', '-O0', '-fPIC', *includes, '-c', source, '-o', objects[-1]],
            log, None, tree, args.timeout)
        seconds += spent
        if code or timed_out:
            raise RuntimeError('compiling ' + str(source.relative_to(tree)) + ' failed')
    library = folder / ('libpurplecore.dylib' if darwin else 'libpurplecore.so')
    link = [*wrapper, args.cxx, *(['-dynamiclib', '-Wl,-undefined,error', '-Wl,-rpath,' + str(qt), '-F' + str(qt)] if darwin else ['-shared']),
        *objects, *(['-framework', 'QtCore'] if darwin else ['-lQt6Core']), '-o', library]
    code, spent, timed_out = execute(link, log, None, tree, args.timeout)
    row.update(sources=len(sources), seconds=round(seconds + spent, 1), exit=code, sha256=hashlib.sha256(library.read_bytes()).hexdigest() if not code else None)
    if code or timed_out:
        raise RuntimeError('linking the host library failed')
    return library


def free_space(args, out):
    if shutil.disk_usage(out).free < args.min_free_gb * 2 ** 30:
        raise RuntimeError('free space is below ' + str(args.min_free_gb) + ' GiB; nothing was cleaned')


def find_json(home):
    jars = sorted((home / 'caches/modules-2/files-2.1/org.json/json').glob('*/*/json-*.jar'))
    return jars[-1] if jars else None


def main():
    # Purple checkouts are often partial clones; never let a git query here
    # fetch a missing object from the network.
    os.environ['GIT_NO_LAZY_FETCH'] = '1'
    parser = argparse.ArgumentParser(description='Run every Purple Android host suite against one tree.')
    parser.add_argument('--tree', type=Path, default=script.parent.parent, help='source tree to test (default: this repository)')
    parser.add_argument('--output-dir', type=Path, help='results, logs and suite outputs; must be outside the tree')
    parser.add_argument('--only', action='append', default=[], help='comma-separated configurations to run, plus what they reuse')
    parser.add_argument('--skip', action='append', default=[], help='comma-separated configurations to leave out')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'), help='JDK for Gradle and the JNI headers (default: $JAVA_HOME)')
    parser.add_argument('--gradle', type=Path, help='an extracted Gradle of exactly the wrapper\'s version (default: ./gradlew, if its Gradle is unpacked)')
    parser.add_argument('--gradle-arg', action='append', default=[], help='extra Gradle argument, repeatable')
    parser.add_argument('--qt-android', help='PURPLE_QT_ANDROID for a tree without local.properties')
    parser.add_argument('--qt-frameworks', help='host Qt frameworks folder the host JNI library links (macOS)')
    parser.add_argument('--json-jar', type=Path, help='org.json jar for the host JVM (default: from the Gradle cache)')
    parser.add_argument('--cxx', default='clang++', help='C++ compiler for the host JNI library')
    parser.add_argument('--compile-wrapper', default='', help='command prefix around every compiler or linker call')
    parser.add_argument('--build-wrapper', default='', help='command prefix around the Gradle call')
    parser.add_argument('--core-repo', type=Path, help='a purple-core clone, to verify a submodule populated without its own .git')
    parser.add_argument('--submodule-repo', action='append', default=[], metavar='PATH=REPO',
        help='a clone or bare mirror holding the gitlinked commit of the submodule at PATH; read offline')
    parser.add_argument('--timeout', type=int, default=3600, help='seconds allowed per step')
    parser.add_argument('--min-free-gb', type=float, default=5.0, help='stop before a compile when free space falls below this')
    parser.add_argument('--list', action='store_true', help='print the canonical list, run the self-check and exit')
    args = parser.parse_args()

    tree = args.tree.resolve()
    listing_path = tree / 'purple/host_suites.json'
    raw = listing_path.read_bytes()
    listing = json.loads(raw)
    digest = hashlib.sha256(raw).hexdigest()
    suites = listing['suites']
    problems = self_check(tree, listing)
    if args.list:
        for suite in suites:
            print(suite['name'].ljust(28) + ' ' + (suite.get('driver') or suite['kind']) + ' ' + ' '.join(suite.get('args', []) or [suite.get('task', '')]))
        for problem in problems:
            print('SELF-CHECK: ' + problem)
        print(str(len(suites)) + ' configurations, ' + str(len(listing.get('aliases', {}))) + ' aliases, '
            + str(len(listing.get('undriven', {}))) + ' undriven, list sha256 ' + digest[:16])
        return 1 if problems else 0
    if problems:
        for problem in problems:
            print('SELF-CHECK: ' + problem, file=sys.stderr)
        return 1
    if not args.output_dir:
        parser.error('--output-dir is required')
    out = args.output_dir.resolve()
    if out == tree or tree in out.parents:
        parser.error('--output-dir must be outside the tree')
    if out.exists() and any(out.iterdir()):
        parser.error('--output-dir must be empty or absent')
    if not args.java_home:
        parser.error('--java-home or $JAVA_HOME is required')
    (out / 'logs').mkdir(parents=True, exist_ok=True)
    (out / 'suites').mkdir()

    names = [suite['name'] for suite in suites]
    by_name = {suite['name']: suite for suite in suites}
    only = {name for value in args.only for name in value.split(',') if name}
    skip = {name for value in args.skip for name in value.split(',') if name}
    for name in sorted(only | skip):
        if name not in by_name:
            parser.error('unknown configuration ' + name)
    selected = set(only or names) - skip
    for name in reversed(names):
        if name in selected:
            # A suite needs the receipts and library its arguments name, and what it reuses.
            selected |= set(dependencies(by_name[name])) | set(prerequisites(by_name[name], suites))

    repos = {'TMessagesProj/jni/purple': args.core_repo} if args.core_repo else {}
    for value in args.submodule_repo:
        path, _, repo = value.partition('=')
        if not repo:
            parser.error('--submodule-repo takes PATH=REPO')
        repos[path.strip('/')] = Path(repo)
    env = dict(os.environ, JAVA_HOME=args.java_home)
    head = subprocess.run(['git', '-C', str(tree), 'rev-parse', 'HEAD'], capture_output=True, text=True).stdout.strip()
    before = tree_hash(tree)
    results = {'runner': 'purple/run_host_suites.py', 'listSha256': digest, 'canonical': len(suites), 'selected': len(selected),
        'started': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'tree': str(tree), 'head': head, 'treeHash': before,
        'submodules': submodules(tree, repos), 'bash': subprocess.run(['bash', '--version'], capture_output=True, text=True).stdout.split('\n')[0], 'undriven': listing.get('undriven', {}), 'configurations': []}
    built = {}
    status = {}
    calls = out / 'compile-calls.jsonl'
    wrapper = json.dumps(shlex.split(args.compile_wrapper))
    for suite in suites:
        name = suite['name']
        if name not in selected:
            continue
        row = {'name': name, 'log': str(out / 'logs' / (name + '.log'))}
        results['configurations'].append(row)
        print('RUN  ' + name, flush=True)
        try:
            missing = [dependency for dependency in dependencies(suite) + prerequisites(suite, suites) if status.get(dependency) != 'PASS']
            if missing:
                raise RuntimeError('needs ' + ', '.join(missing) + ', which did not pass')
            if suite.get('kind') == 'agp':
                free_space(args, out)
                built[suite['provides']] = agp_compile(args, tree, out, env, suite, row)
            elif suite.get('kind') == 'host-library':
                built[suite['provides']] = host_library(args, tree, out, row)
            else:
                folder = out / 'suites' / name
                values = dict(built, output=folder)
                if '{json}' in json.dumps(suite.get('args', [])):
                    values['json'] = args.json_jar or find_json(Path(env.get('GRADLE_USER_HOME') or Path.home() / '.gradle'))
                    if not values['json'] or not values['json'].is_file():
                        raise RuntimeError('no org.json jar; pass --json-jar')

                def fill(value):
                    value = re.sub(r'\{out:([^}]+)\}', lambda match: str(out / 'suites' / match.group(1)), value)
                    return re.sub(r'\{([\w-]+)\}', lambda match: str(values[match.group(1)]), value)
                arguments = [fill(value) for value in suite.get('args', [])]
                driver = tree / suite['driver']
                if driver.suffix == '.py':
                    launch = [sys.executable, script, '--exec-driver', calls, wrapper, driver]
                else:
                    # A shell driver starts its own javac, so the whole driver
                    # runs inside one compile-wrapper call when it compiles.
                    launch = [*(shlex.split(args.compile_wrapper) if suite.get('compiles') else []), 'bash', driver]
                tmp = out / 'tmp' / name
                tmp.mkdir(parents=True)
                # Drivers read PURPLE_* overrides for their sources and scripts;
                # the runner always tests the tree, so none of them pass through.
                driver_env = {key: value for key, value in env.items() if not key.startswith('PURPLE_')}
                driver_env.update(TMPDIR=str(tmp), PATH=str(Path(args.java_home) / 'bin') + os.pathsep + env.get('PATH', ''), PURPLE_HOST_SUITE=name)
                row['commands'], row['seconds'] = [], 0
                for phase in suite.get('phases', [None]):
                    free_space(args, out)
                    command = [*launch, *arguments, *(['--phase', phase] if phase else [])]
                    row['commands'].append(list(map(str, command)))
                    code, spent, timed_out = execute(command, out / 'logs' / (name + '.log'), driver_env, tree, args.timeout)
                    row['seconds'] = round(row['seconds'] + spent, 1)
                    row['exit'] = code
                    if code or timed_out:
                        raise RuntimeError(('timed out' if timed_out else 'exited ' + str(code)) + (' in the ' + phase + ' phase' if phase else ''))
                failed = []
                # Each program's own log in the suite's folder, and the
                # driver's output, which carries its closing markers.
                row['checks'] = {**(checks(folder, failures=failed) if folder.is_dir() else {}), **checks(out / 'logs', name, failed)}
                # PASS needs this run's own completion record for every
                # program and phase the list expects of the suite, each at
                # its floor, and no recognised failure report anywhere in its
                # logs; for a driver that compiles against a receipt, also a
                # compiler call of its own.
                row['failures'] = failed = list(dict.fromkeys(failed))
                if failed:
                    raise RuntimeError('its logs report failures: ' + '; '.join(failed[:5]))
                # A program that prints no count is held to its own closing
                # output instead: "<log>:LINE <text>" needs a line starting
                # with that text in this run's log of that program.
                lines = {}
                for log in [*(folder.rglob('*.log') if folder.is_dir() else []), out / 'logs' / (name + '.log')]:
                    if log.is_file():
                        lines.setdefault(log.stem, set()).update(line.strip() for line in log.read_text(errors='replace').splitlines())
                def met(key, floor):
                    stem, _, record = key.partition(':')
                    if record.startswith('LINE '):
                        return any(line.startswith(record[len('LINE '):]) for line in lines.get(stem, ()))
                    value = row['checks'].get(key)
                    return value in ('PASS', 'passed') if floor == 'PASS' else isinstance(value, int) and value >= floor
                missing = [key + ('' if ':LINE ' in key else ' (' + str(row['checks'].get(key, 'absent')) + ' < ' + str(floor) + ')')
                    for key, floor in suite['expects'].items() if not met(key, floor)]
                if not row['commands'] or missing:
                    raise RuntimeError('exited 0 without the completion records the list expects: ' + ', '.join(missing))
                if driver.suffix == '.py' and re.search(r'\{[\w-]*receipt\}', json.dumps(suite.get('args', []))):
                    made = sum(1 for line in (calls.read_text().splitlines() if calls.exists() else []) if json.loads(line).get('suite') == name)
                    row['compileCalls'] = made
                    if not made:
                        raise RuntimeError('compiles against a receipt but made no compiler call')
            row['status'] = 'PASS'
        except Exception as error:
            row['status'] = 'FAIL'
            row['failReason'] = str(error)
        status[name] = row['status']
        print(row['status'].ljust(4) + ' ' + name + ('' if row['status'] == 'PASS' else ': ' + row['failReason']), flush=True)
    results['treeHashAfter'] = tree_hash(tree)
    # Every populated submodule is built from, so each must match its gitlink,
    # before and after the run.
    results['submodulesAfter'] = submodules(tree, repos)
    unverified = sorted(path for path, entry in results['submodules'].items() if entry['state'] not in ('matches gitlink', 'unpopulated'))
    moved = sorted(path for path, entry in results['submodules'].items() if results['submodulesAfter'].get(path) != entry)
    results['unverifiedSubmodules'], results['changedSubmodules'] = unverified, moved
    results['finished'] = time.strftime('%Y-%m-%dT%H:%M:%S%z')
    passed = sum(1 for row in results['configurations'] if row['status'] == 'PASS')
    clean = passed == len(selected) and results['treeHashAfter'] == before and not unverified and not moved
    summary = str(passed) + '/' + str(len(selected)) + ' PASS'
    if len(selected) < len(suites):
        summary = 'PARTIAL ' + summary + ', ' + str(len(selected)) + ' of ' + str(len(suites)) + ' configurations selected'
    if results['treeHashAfter'] != before or moved:
        summary = 'NOT CLEAN ' + summary + ', the tree changed during the run' + (' (' + ' '.join(moved) + ')' if moved else '')
    if unverified:
        summary = 'UNVERIFIED ' + summary + ', submodules not at their gitlink or not verifiable: ' + ' '.join(
            path + ' (' + results['submodules'][path]['state'] + ')' for path in unverified)
    summary += ', ' + str(len(listing.get('undriven', {}))) + ' undriven, list sha256 ' + digest[:16]
    results['summary'] = summary
    (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(summary)
    return 1 if not clean else 0 if len(selected) == len(suites) else 3


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--exec-driver':
        exec_driver(sys.argv[2], sys.argv[3], sys.argv[4:])
    else:
        sys.exit(main())
