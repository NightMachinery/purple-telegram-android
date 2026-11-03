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
sources=[tests/'sync_chat_marks/PurpleSyncChatMarkCases.java',tests/'sync_chat_marks/PurpleSyncChatMarkReviewCases.java',main]
def run(name,argv):
    argv=list(map(str,argv));(args.output/(name+'.argv.json')).write_text(json.dumps(argv,indent=2)+'\n')
    with (args.output/(name+'.log')).open('w') as log:result=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT)
    print(name,result.returncode,flush=True);print((args.output/(name+'.log')).read_text()[-6000:],flush=True)
    if result.returncode:raise SystemExit(result.returncode)
run('javac',[compiler,'-source','8','-target','8','-proc:none','-sourcepath',empty,'-cp',classpath,'-d',out,*sources])
fixtures=args.output/'fixtures';fixtures.mkdir()
run('marks',[compiler.with_name('java'),'--add-opens=java.base/sun.nio.fs=ALL-UNNAMED','-Xcheck:jni','-Xmx512m','-XX:ActiveProcessorCount=2','-Djava.library.path='+str(args.library.parent),'-cp',str(out)+os.pathsep+classpath,'SyncChatMarkHostMain',production,fixtures,args.carry/'fixtures.jsonl'])
inputs=sources+[args.receipt,args.library,args.json,args.carry/'fixtures.jsonl']
(args.output/'inputs.json').write_text(json.dumps([{'path':str(f),'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in inputs],indent=2)+'\n')
