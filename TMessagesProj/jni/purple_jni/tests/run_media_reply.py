#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description="Exercise the actual Android media reply adapter over inert ports and real JNI")
parser.add_argument("--receipt", type=Path, required=True)
parser.add_argument("--json", type=Path, required=True)
parser.add_argument("--library", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--reviewed-classes", type=Path)
parser.add_argument("--reviewed-ui", type=Path)
parser.add_argument("--phase", choices=["all", "compile", "run"], default="all")
args = parser.parse_args()
receipt = json.loads(args.receipt.read_text())
classes = Path(receipt["destination"])
compiler = Path(receipt["compiler"])
assert receipt["executed"] and receipt["didWork"] and not receipt.get("skipped", False)
assert classes.is_dir() and args.json.is_file() and args.library.is_file()
if args.phase in ["all", "compile"]:
    assert not args.output.exists(), "fixture output must be fresh"
    args.output.mkdir(parents=True)
    for name in ["classes", "fixtures", "empty"]:
        (args.output / name).mkdir()
out = args.output / "classes"
fixtures = args.output / "fixtures"
empty = args.output / "empty"
source = Path(__file__).resolve().parent / "media_reply"
repo = Path(__file__).resolve().parents[3]
ui = repo / "src/main/java/org/telegram/ui/PurpleMediaReplySettingsActivity.java"
writer_ui = repo / "src/main/java/org/telegram/ui/PurpleWriterUI.java"
def method(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]
ui_template = (source / "PickerCases.java.template").read_text()
generated = []
for name, path in [("PickerCurrent", ui), *([("PickerReviewed", args.reviewed_ui)] if args.reviewed_ui else [])]:
    text = path.read_text()
    methods = method(text, "private void select()") + "\n" + method(text, "private boolean write(String row,")
    if "private boolean writeFrom(" in text: methods += "\n" + method(text, "private boolean writeFrom(")
    target = args.output / (name + ".java")
    if args.phase in ["all", "compile"]:
        target.write_text(ui_template.replace("@CLASS@", name).replace("@METHODS@", methods))
    generated.append(target)
text = writer_ui.read_text()
target = args.output / "GeneratedWriterUI.java"
if args.phase in ["all", "compile"]:
    target.write_text("package org.telegram.ui;\nimport java.util.WeakHashMap;\nimport java.lang.ref.WeakReference;\nimport android.app.Activity;\nimport android.view.View;\nimport org.telegram.ui.ActionBar.BaseFragment;\nimport org.telegram.messenger.purple.PurpleWriter;\nclass PurpleWriterUI {\n"
        + "static final WeakHashMap<BaseFragment,Long> lifetimes=new WeakHashMap<>();\nstatic final WeakHashMap<Activity,Long> activities=new WeakHashMap<>();\nstatic final WeakHashMap<View,long[]> attachments=new WeakHashMap<>();\nstatic long accountEpoch;\nstatic void observe(Activity activity){}\n"
        + method(text, "private static long[] attachment(") + "\n" + method(text, "static PurpleWriter.Current current(")
        + "\nstatic PurpleWriter.Session captureOrExplain(BaseFragment fragment,boolean appWide,Runnable retry){PurpleWriter.Current view=current(fragment);return view.current()?new PurpleWriter.Session(view):null;}\n}\n")
generated.append(target)
sources = sorted(source.rglob("*.java")) + generated
classpath = os.pathsep.join([str(classes), str(args.json)])
commands = [
    ("javac", [compiler, "-source", "8", "-target", "8", "-proc:none", "-sourcepath", empty,
        "-cp", classpath, "-d", out, *sources]),
    ("adapter", [compiler.with_name("java"), "-Xcheck:jni", "-Xmx512m", "-XX:ActiveProcessorCount=2",
        "-Dscratch=" + str(fixtures), "-Djava.io.tmpdir=" + str(fixtures), "-cp", str(out) + os.pathsep + classpath,
        "org.telegram.messenger.purple.PurpleMediaReplyCases", args.library.resolve(), classes.resolve()]),
]
def java(name, main, extra, origin=classes):
    scratch = fixtures if name == "adapter" else fixtures / name
    if args.phase in ["all", "compile"]: scratch.mkdir(exist_ok=True)
    return (name, [compiler.with_name("java"), "-Xcheck:jni", "-Xmx512m", "-XX:ActiveProcessorCount=2",
        "-Dscratch=" + str(scratch), "-cp", os.pathsep.join(map(str, [out, origin, classes, args.json])),
        main, args.library.resolve(), *extra])
for fault in ["", "write", "sync", "close", "rename", "directory-sync", "readback"]:
    commands.append(java("commit-" + (fault or "success"), "org.telegram.messenger.purple.PurpleMediaReplyCommitCases",
        [classes.resolve(), "current", fault]))
for mode in ["errors", "corrupt", "full"]:
    commands.append(java("status-" + mode, "org.telegram.messenger.purple.PurpleMediaReplyStatusCases", [mode]))
commands.append(java("presentation", "org.telegram.messenger.purple.PurpleMediaReplyPresentationCases", []))
commands.append(java("picker", "org.telegram.ui.PickerCurrent", ["current"]))
if args.reviewed_classes:
    assert args.reviewed_ui
    for fault in ["sync", "rename"]:
        commands.append(java("reviewed-commit-" + fault, "org.telegram.messenger.purple.PurpleMediaReplyCommitCases",
            [args.reviewed_classes.resolve(), "reviewed", fault], args.reviewed_classes.resolve()))
    commands.append(java("reviewed-picker", "org.telegram.ui.PickerReviewed", ["reviewed"]))
for name, command in commands:
    if args.phase == "compile" and name != "javac" or args.phase == "run" and name == "javac":
        continue
    with (args.output / (name + ".log")).open("w") as log:
        result = subprocess.run([str(x) for x in command], stdout=log, stderr=subprocess.STDOUT)
    print((args.output / (name + ".log")).read_text(), end="", flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
inputs = sources + [ui, writer_ui, source / "PickerCases.java.template", args.library, args.receipt, *sorted((classes / "org/telegram/messenger/purple").glob("PurpleMediaReply*.class"))]
(args.output / "inputs.json").write_text(json.dumps([
    {"path": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in inputs
], indent=2) + "\n")
