#!/usr/bin/env python3
import argparse, hashlib, json, os, subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description='Actual AGP Peek controller/lifecycle/policy with synthetic Android, routing and IO ports')
for name in ['receipt','json','library','output']:p.add_argument('--'+name,type=Path,required=True)
a=p.parse_args();d=json.loads(a.receipt.read_text());assert d['task']==':TMessagesProj:compileStandaloneJavaWithJavac'
a.output.mkdir();classes=a.output/'classes';classes.mkdir();empty=a.output/'empty';empty.mkdir();tests=Path(__file__).resolve().parent;production=Path(d['destination']);compiler=Path(d['compiler'])
classpath=os.pathsep.join([str(production),str(a.json)]+[x['path']for k in ['classpath','bootstrapClasspath']for x in d[k]])
main=a.output/'PeekFocusMain.java';main.write_text('''import java.io.File;public final class PeekFocusMain {public static void main(String[] a)throws Exception {
 File expected=new File(a[0]).getCanonicalFile();
 for(String name:new String[]{"org.telegram.ui.PurplePeekControls","org.telegram.ui.PurplePeekControls$Pending",
 "org.telegram.messenger.purple.PurplePeekFocusModel","org.telegram.messenger.purple.PurplePeekForeground",
 "org.telegram.messenger.purple.PurplePeekLocalSettings","org.telegram.messenger.purple.PurpleCore",
 "org.telegram.ui.Components.ForegroundDetector","org.telegram.messenger.ScreenReceiver"}){
 Class<?> type=Class.forName(name,false,PeekFocusMain.class.getClassLoader());
 File actual=new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();
 if(!expected.equals(actual))throw new AssertionError("business class shadow: "+name);}
 org.telegram.ui.PeekFocusCases.main(new String[]{a[1]});}}\n''')
sources=list((tests/'peek_focus/ports').rglob('*.java'))+[tests/'peek_focus/PeekFocusCases.java',main]
(a.output/'inputs.json').write_text(json.dumps([{'path':str(x),'sha256':hashlib.sha256(x.read_bytes()).hexdigest()}for x in sources+list((tests/'peek_focus/actual_gate').glob('*.java'))+[a.receipt,a.library]],indent=2)+'\n')
def run(name,args):
 (a.output/(name+'.argv.json')).write_text(json.dumps(list(map(str,args)),indent=2)+'\n')
 with(a.output/(name+'.log')).open('w')as f:r=subprocess.run(list(map(str,args)),stdout=f,stderr=subprocess.STDOUT)
 print(name,r.returncode,flush=True);print((a.output/(name+'.log')).read_text()[-4000:],flush=True)
 if r.returncode:raise SystemExit(r.returncode)
run('javac',[compiler,'-source','8','-target','8','-proc:none','-sourcepath',empty,'-cp',classpath,'-d',classes]+sources)
scenarios=['model','background','replacement','transitions','off','startup','failed-state','toggles','enable-during-wait','failed-preference','legacy-lock','resume-lock','gate-backstop','hidden-exit','end-button']
scenarios+=['lock-'+s for s in ['screen','launch','external']]
scenarios+=['cancel-'+s for s in ['pause','stop','destroy','screen','launch','external','screen-on','launch-on','external-on']]
scenarios+=['revoke-'+s for s in ['selection','logout','settings','revision','view','screen','launch','external']]
scenarios+=['reload-failure-'+s for s in ['background','late','lock']]
for scenario in scenarios:run(scenario,[compiler.with_name('java'),'-Xcheck:jni','-Xmx512m','-Djava.library.path='+str(a.library.parent),'-cp',str(classes)+os.pathsep+classpath,'PeekFocusMain',production,scenario])
actual=a.output/'actual-gate';actual.mkdir()
run('actual-gate-javac',[compiler,'-source','8','-target','8','-proc:none','-sourcepath',empty,'-cp',str(classes)+os.pathsep+classpath,'-d',actual]+list((tests/'peek_focus/actual_gate').glob('*.java')))
import shutil
origin=production/'org/telegram/messenger/purple';dest=actual/'org/telegram/messenger/purple';dest.mkdir(parents=True,exist_ok=True)
for file in origin.glob('PurpleGate*.class'):shutil.copyfile(file,dest/file.name)
for cut in ['background','lock']:run('actual-gate-'+cut,[compiler.with_name('java'),'-Xcheck:jni','-Xmx512m','-Djava.library.path='+str(a.library.parent),'-cp',str(actual)+os.pathsep+str(classes)+os.pathsep+classpath,'ActualGateProbe',cut])
for cut in ['background','lock']:run('actual-account-'+cut,[compiler.with_name('java'),'-Xcheck:jni','-Xmx512m','-Djava.library.path='+str(a.library.parent),'-cp',str(actual)+os.pathsep+str(classes)+os.pathsep+classpath,'org.telegram.messenger.purple.NativeAccountProbe',cut])
root=tests.parents[3];java=root/'TMessagesProj/src/main/java'
hooks={'org/telegram/messenger/ApplicationLoader.java':'PurplePeekForeground.init(this);','org/telegram/messenger/ScreenReceiver.java':'PurplePeekForeground.screenOff();','org/telegram/ui/ExternalActionActivity.java':'PurpleGate.locked();','org/telegram/ui/LaunchActivity.java':'PurpleGate.locked();'}
for file,call in hooks.items():assert call in(java/file).read_text(),file
text=(java/'org/telegram/messenger/ScreenReceiver.java').read_text();assert text.index('ACTION_SCREEN_OFF')<text.index('PurplePeekForeground.screenOff();')<text.index('setAppPaused(true, true)')
text=(java/'org/telegram/ui/ExternalActionActivity.java').read_text();assert text.index('SharedConfig.appLocked = true;')<text.index('PurpleGate.locked();')<text.index('passcodeView.onShow(true, false);')
for name in ['purple_archive_backup.xml','purple_archive_transfer.xml']:
 tree=ET.parse(root/'TMessagesProj/src/main/res/xml'/name).getroot();sections=[tree]if name.endswith('backup.xml')else[tree.find('cloud-backup'),tree.find('device-transfer')]
 for section in sections:assert any(e.get('domain')=='sharedpref'and e.get('path')=='purple_peek_local.xml'for e in section.findall('exclude'))
print('PEEK_FOCUS_HOOKS_AND_LOCAL_EXCLUSIONS=PASS')
(a.output/'result.json').write_text(json.dumps({'exit':0,'scenarios':scenarios,'actualAGPBusinessOriginsVerified':True,'syntheticDependencies':['Android/UI','account routing and selection','settings snapshot','IO','clock','preferences'],'hookAndBackupSourceChecks':True},indent=2)+'\n')
