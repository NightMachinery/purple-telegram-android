#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

parser = argparse.ArgumentParser(description="Source-only Markdown qualification against an existing AGP class receipt")
parser.add_argument("--receipt", type=Path, required=True)
parser.add_argument("--json", type=Path, required=True)
parser.add_argument("--core", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--archive-host", type=Path)
parser.add_argument("--sqlite", type=Path)
parser.add_argument("--format1", type=Path)
parser.add_argument("--hold-helper", type=Path, required=True)
parser.add_argument("--holder", default=str(uuid.uuid4()))
args = parser.parse_args()
if any([args.archive_host, args.sqlite, args.format1]) and not all([args.archive_host, args.sqlite, args.format1]):
    parser.error("Real Store release cases require --archive-host, --sqlite and --format1 together")
root = Path(__file__).resolve().parents[2]
receipt = json.loads(args.receipt.read_text())
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=False)
production = output / "production"
production.mkdir()
empty = output / "empty"
empty.mkdir()
compiler = Path(receipt["compiler"])
classpath = os.pathsep.join([str(args.json), receipt["destination"]] + [item["path"] for key in ["classpath", "bootstrapClasspath"] for item in receipt[key]])
java = root / "TMessagesProj/src/main/java"
owned = ["PurpleMarkdownSource", "PurpleComposePending", "PurpleComposeSource", "PurpleArchiveSource", "PurpleArchiveAcquiredSource", "PurpleSendReceipts", "PurpleArchive", "PurpleArchiveNormalize", "PurpleArchiveStore", "PurpleArchiveMarkdown", "PurpleArchiveMarkdownBody", "PurpleArchiveViewerModel", "PurpleArchiveDeletedModel", "PurpleRichMessageMarkdown"]
sources = [java / "org/telegram/messenger/purple" / (name + ".java") for name in owned]
sources += [java / "org/telegram/ui" / (name + ".java") for name in ["PurpleMarkdownCopy", "PurpleEditHistoryActivity", "PurpleDeletedMessagesActivity"]]
sources += [java / name for name in ["org/telegram/messenger/SendMessagesHelper.java", "org/telegram/ui/Components/ChatActivityEnterView.java"]]
result = {"scope": "host javac and supplied JNI library; Android UI, AGP and device behavior remain unqualified", "steps": {}, "inputs": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}}


def floor():
    free = shutil.disk_usage(output).free
    result["minimumFreeBytes"] = min(free, result.get("minimumFreeBytes", free))
    if free < 6 * 1024 ** 3:
        raise RuntimeError("Disk below 6 GiB; no cleanup attempted")


def run(name, command):
    floor()
    command = list(map(str, command))
    (output / (name + ".argv.json")).write_text(json.dumps(command, indent=2) + "\n")
    with (output / (name + ".log")).open("w") as log:
        completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    result["steps"][name] = completed.returncode
    if completed.returncode:
        raise RuntimeError("Failed " + name + "; inspect its scratch log")
    floor()


def compile_sources(name, destination, inputs, additional_classpath=()):
    destination.mkdir(exist_ok=True)
    floor()
    subprocess.run([str(args.hold_helper), "acquire", "compiler:local", "--holder", args.holder, "--reason", "Markdown source-host javac"], check=True)
    try:
        run(name, [compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", empty, "-cp", os.pathsep.join([str(production)] + list(map(str, additional_classpath)) + [classpath]), "-d", destination] + inputs)
    finally:
        subprocess.run([str(args.hold_helper), "release", "compiler:local", "--holder", args.holder], check=True)


def execute(name, destination, class_name, extra=()):
    run(name, [compiler.with_name("java"), "-Xcheck:jni", "-Xmx256m", "-Dpurple.test.core=" + str(args.core), "-cp", str(destination) + os.pathsep + str(production) + os.pathsep + classpath, class_name] + list(extra))


try:
    compile_sources("production-javac", production, sources)
    tests = root / "purple/tests"
    names = ["MarkdownSourceTest", "ComposePendingTest", "RichMessageMarkdownSafetyTest", "MarkdownExportTest", "ArticleMarkdownTest", "PeekCopyRepairTest"]
    plain = output / "plain"
    compile_sources("plain-javac", plain, [tests / (name + ".java") for name in names])
    for name in names:
        prefix = "" if name in ["MarkdownExportTest", "ArticleMarkdownTest"] else "org.telegram.messenger.purple."
        execute(name, plain, prefix + name, [output / "peek-fixtures"] if name == "PeekCopyRepairTest" else [])
    ports = list((tests / "markdown_ports").rglob("*.java"))
    route = output / "route"
    compile_sources("route-javac", route, ports + [tests / "MarkdownCopyRouteTest.java"])
    execute("route", route, "org.telegram.ui.MarkdownCopyRouteTest", [str(production) + "/"])
    archive = output / "archive"
    compile_sources("archive-javac", archive, [path for path in ports if path.name != "PurpleArchiveMarkdown.java"] + [tests / "ArchiveMarkdownTest.java"])
    execute("archive", archive, "org.telegram.messenger.purple.ArchiveMarkdownTest")
    if args.archive_host:
        release = output / "release"
        fixture = root / "TMessagesProj/jni/purple_jni/tests/archive/PurpleArchiveMarkdownReleaseCases.java"
        result["releaseFixtureSha256"] = hashlib.sha256(fixture.read_bytes()).hexdigest()
        source_fixture = root / "TMessagesProj/jni/purple_jni/tests/archive/PurpleArchiveSourceCases.java"
        result["sourceFixtureSha256"] = hashlib.sha256(source_fixture.read_bytes()).hexdigest()
        result["coreSha256"] = hashlib.sha256(args.core.read_bytes()).hexdigest()
        result["sqliteSha256"] = hashlib.sha256(args.sqlite.read_bytes()).hexdigest()
        compile_sources("release-javac", release, [fixture, source_fixture], [args.archive_host])
        for mode in ["control", "capture", "cancel", "parent-close", "purge", "cancel-before-worker", "purge-before-worker"]:
            run("release-" + mode, [compiler.with_name("java"), "-Xcheck:jni", "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-Djava.library.path=" + str(args.core.parent), "-Dpurple.archive.format1=" + str(args.format1), "-cp", os.pathsep.join([str(release), str(production), str(args.archive_host), classpath]), "org.telegram.messenger.purple.PurpleArchiveMarkdownReleaseCases", args.sqlite, output / ("release-" + mode + "-fixtures"), mode])
        for mode in ["basic", "attach-purge", "purge-attach", "logout", "discard", "scope", "selection"]:
            run("source-" + mode, [compiler.with_name("java"), "-Xcheck:jni", "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-Djava.library.path=" + str(args.core.parent), "-Dpurple.archive.format1=" + str(args.format1), "-cp", os.pathsep.join([str(release), str(production), str(args.archive_host), classpath]), "org.telegram.messenger.purple.PurpleArchiveSourceCases", args.sqlite, output / ("source-" + mode + "-fixtures"), mode])
    result["status"] = "PASS"
except BaseException as error:
    result["status"] = "PARTIAL"
    result["error"] = type(error).__name__ + ": " + str(error)
    raise
finally:
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
