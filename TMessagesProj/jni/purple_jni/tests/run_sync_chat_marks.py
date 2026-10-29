#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description='Actual AGP standalone stale-mark authority and exact absence cases')
for name in ['receipt','json','library','output','modern','discovery','carry','store','io','executor','consent']:
    parser.add_argument('--'+name,type=Path,required=True)
args=parser.parse_args()
tests=Path(__file__).resolve().parent
receipt=json.loads(args.receipt.read_text());compiler=Path(receipt['compiler']);production=Path(receipt['destination'])
assert receipt['executed'] and receipt['didWork'] and not receipt['skipped'] and not receipt['upToDate']
args.output.mkdir(parents=True,exist_ok=False);out=args.output/'classes';out.mkdir();empty=args.output/'empty-sourcepath';empty.mkdir()
classpath=os.pathsep.join([str(args.executor/'classes'),str(args.io/'classes'),str(args.store/'classes'),str(args.modern/'classes'),str(production),str(args.consent/'classes'),str(args.discovery/'classes'),str(args.json)]+[x['path'] for k in ['classpath','bootstrapClasspath'] for x in receipt[k]])
main=args.output/'SyncChatMarkHostMain.java'
main.write_text('import java.io.File; public final class SyncChatMarkHostMain { public static void main(String[] a) throws Exception {'
 'org.telegram.messenger.purple.PurpleCore.ensureLoaded();'
 'for(String n:new String[]{"PurpleRpcRequest","PurpleSyncChatMarkActions","PurpleSyncChatClient","PurpleSyncChatTelegram","PurpleSyncChatScreen","PurpleSyncChatDiscovery","PurpleSyncChatService","PurpleSyncChat","PurpleSyncOperations","PurpleSyncChatMoveStore","PurpleSyncChatMoveCore"})'
 'if(!new File(Class.forName("org.telegram.messenger.purple."+n).getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new File(a[0]).getCanonicalFile()))throw new AssertionError("business shadow "+n);'
 'org.telegram.messenger.purple.PurpleSyncChatMarkCases.main(new String[]{a[1],a[2]});org.telegram.messenger.purple.PurpleSyncChatMarkReviewCases.main(new String[]{a[1],a[2]});}}\n')
ui=tests.parents[2]/'src/main/java/org/telegram/ui/PurpleSyncChatActivity.java'
source=ui.read_text();pos=source.index('frame.staleMarks.isEmpty()');start=source.rfind('            if(',0,pos);brace=source.index('{',pos);depth=1;end=brace+1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
block=source[start:end]
click=next(line.strip() for line in source.splitlines() if 'item.id>=REMOVE_MARK' in line)
section=source.split('private void removeMark(',1)[1].split('private void followFill(',1)[0]
entry=next(line.strip() for line in section.splitlines() if line.strip().startswith('if('))
confirm=next(line.strip() for line in section.split('dialog.setPositiveButton',1)[1].splitlines() if line.strip().startswith('if('))
def injected(text):return text.replace('org.telegram.messenger.purple.PurpleSyncChatMarkActions.ACTIVATED','activated')
layout=args.output/'MarkActivationLayout.java'
layout.write_text('package org.telegram.messenger.purple; import java.util.*; public final class MarkActivationLayout {'
 'static final int REMOVE_MARK=0x70750200; final PurpleSyncChatScreen.Frame frame; int clicks,confirmations,submissions; boolean visible=true;'
 'static final class Control {boolean current(){return true;}} final Control control=new Control();'
 'MarkActivationLayout(PurpleSyncChatScreen.Frame frame){this.frame=frame;}'
 'static final class UItem {final int id; final boolean button; UItem(int id,boolean button){this.id=id;this.button=button;} static UItem asHeader(String s){return new UItem(0,false);} static UItem asButton(int id,String s){return new UItem(id,true);}}'
 'static String text(String key,Object... args){return key;} Object getParentActivity(){return this;}'
 'void removeMark(PurpleSyncChatMarkActions.Row row){clicks++;}'
 'void render(boolean activated,ArrayList<UItem> items){'+injected(block)+'}'
 'void click(boolean activated,UItem item){'+injected(click)+'}'
 'void confirm(boolean activated){Control opening=control;'+injected(entry)+'confirmations++;}'
 'void submit(boolean activated){Control opening=control;'+injected(confirm)+'submissions++;}}\n')
(args.output/'ui-source.json').write_text(json.dumps({'path':str(ui),'sha256':hashlib.sha256(ui.read_bytes()).hexdigest(),'rowBlock':block,'click':click,'confirmationEntry':entry,'positiveConfirmation':confirm},indent=2)+'\n')
sources=[tests/'sync_chat_marks/PurpleSyncChatMarkCases.java',tests/'sync_chat_marks/PurpleSyncChatMarkReviewCases.java',layout,main]
def run(name,argv):
    argv=list(map(str,argv));(args.output/(name+'.argv.json')).write_text(json.dumps(argv,indent=2)+'\n')
    with (args.output/(name+'.log')).open('w') as log:result=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT)
    print(name,result.returncode,flush=True);print((args.output/(name+'.log')).read_text()[-6000:],flush=True)
    if result.returncode:raise SystemExit(result.returncode)
run('javac',[compiler,'-source','8','-target','8','-proc:none','-sourcepath',empty,'-cp',classpath,'-d',out,*sources])
fixtures=args.output/'fixtures';fixtures.mkdir()
run('marks',[compiler.with_name('java'),'--add-opens=java.base/sun.nio.fs=ALL-UNNAMED','-Xcheck:jni','-Xmx512m','-XX:ActiveProcessorCount=2','-Djava.library.path='+str(args.library.parent),'-cp',str(out)+os.pathsep+classpath,'SyncChatMarkHostMain',production,fixtures,args.carry/'fixtures.jsonl'])
inputs=sources+[ui,args.receipt,args.library,args.json,args.carry/'fixtures.jsonl']
(args.output/'inputs.json').write_text(json.dumps([{'path':str(f),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in inputs],indent=2)+'\n')
