"""Compile the real retry route, sender snapshot and delivery against delayed boundaries."""
import pathlib, os, shutil, subprocess

def run(root, output, baseline=None):
    output.mkdir()
    tests=pathlib.Path(__file__).parent
    base=tests/'round-host'
    for name in ['org/telegram/messenger/AndroidUtilities.java','org/telegram/messenger/UserConfig.java',
                 'org/telegram/messenger/MessageSuggestionParams.java','org/telegram/messenger/purple/PurpleDiagLog.java']:
        target=output/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(base/name,target)
    def put(name,text):
        target=output/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
    java=root/'TMessagesProj/src/main/java/org/telegram/messenger/purple'
    def source(name):return (pathlib.Path(baseline)/('before-'+name) if baseline else java/name).read_text()
    for name in ['PurpleRecordingSendAs.java','PurpleRecordingDelivery.java','PurpleRecordingSendIdentity.java']:
        put('org/telegram/messenger/purple/'+name, source(name) if name!='PurpleRecordingSendIdentity.java' else (java/name).read_text())
    text=source('PurpleRecordingSend.java');start=text.index('    public static void send(');stop=text.index('    /** As MediaController',start)
    public=text[start:stop]
    start=text.index('    private static void sendPendingRound(');stop=text.index('    /**',start)
    pending=text[start:stop]
    put('org/telegram/messenger/purple/RetryRoute.java', 'package org.telegram.messenger.purple;import org.telegram.messenger.*;import org.telegram.tgnet.*;import java.io.*;public class RetryRoute{'+public+pending+"""
    static boolean sendVoice(Object... args){throw new AssertionError("wrong route");}
    static boolean sendRound(Object... args){throw new AssertionError("wrong route");}
    }""")
    put('org/telegram/messenger/purple/PurpleRecordingEntry.java', 'package org.telegram.messenger.purple;public class PurpleRecordingEntry{public String id="retry",media;public long durationMs=1000,userId=9;public boolean once,awaitingQueue=true;public boolean sameAccount(boolean active,long id){return active&&userId!=0&&id==userId;}public boolean round(){return true;}}')
    put('org/telegram/messenger/purple/PurpleRecordingFiles.java', """package org.telegram.messenger.purple;
import java.io.*;import java.nio.file.*;public class PurpleRecordingFiles {public static int copies;public static Runnable afterCopy;
public static void copy(File a,File b)throws IOException{Files.copy(a.toPath(),b.toPath());copies++;if(afterCopy!=null)afterCopy.run();}}""")
    put('org/telegram/messenger/purple/PurpleRecordingRegistry.java', """package org.telegram.messenger.purple;
import java.util.*;import java.util.function.Consumer;public class PurpleRecordingRegistry{
public static boolean workerDefer,pendingDefer;public static Set<String> bin=new HashSet<>();public static List<Runnable> workers=new ArrayList<>(),pending=new ArrayList<>();public static int ack;
public static void onWorker(Runnable r){if(workerDefer)workers.add(r);else r.run();}
public static void pendingRound(String id,Consumer<Boolean> done){Runnable r=()->done.accept(bin.contains(id));if(pendingDefer)pending.add(r);else r.run();}
public static void queuedRound(String id,Runnable done){bin.remove(id);ack++;done.run();}
public static void sent(String id){throw new AssertionError("prepare is not acknowledgment");}
public static void workers(){List<Runnable> q=new ArrayList<>(workers);workers.clear();for(Runnable r:q)r.run();}
public static void pending(){List<Runnable> q=new ArrayList<>(pending);pending.clear();for(Runnable r:q)r.run();}
}""")
    put('org/telegram/messenger/FileLoader.java','package org.telegram.messenger;import java.io.*;public class FileLoader{public static final int MEDIA_DIR_DOCUMENT=1;public static File directory;public static File getDirectory(int a){return directory;}}')
    put('org/telegram/messenger/SharedConfig.java','package org.telegram.messenger;public class SharedConfig{static int id;public static int getLastLocalId(){return ++id;}public static void saveConfig(){}}')
    put('org/telegram/messenger/AccountInstance.java','package org.telegram.messenger;public class AccountInstance{public static AccountInstance getInstance(int a){return new AccountInstance();}}')
    put('org/telegram/messenger/MessageObject.java','package org.telegram.messenger;import org.telegram.tgnet.TLRPC;public class MessageObject{public MessageObject(int a,TLRPC.Message m,boolean b,boolean c){}public static long getPeerId(TLRPC.Peer p){return p.id;}}')
    put('org/telegram/messenger/DialogObject.java','package org.telegram.messenger;public class DialogObject{public static boolean isEncryptedDialog(long d){return false;}}')
    put('org/telegram/messenger/ChatObject.java','package org.telegram.messenger;import org.telegram.tgnet.TLRPC;public class ChatObject{public static boolean isChannelAndNotMegaGroup(TLRPC.Chat c){return false;}}')
    put('org/telegram/messenger/MessagesController.java','package org.telegram.messenger;import org.telegram.tgnet.TLRPC;public class MessagesController{public static MessagesController getInstance(int a){return new MessagesController();}public TLRPC.Chat getChat(long id){return null;}public TLRPC.ChatFull getChatFull(long id){return null;}public TLRPC.Peer getPeer(long id){TLRPC.Peer p=new TLRPC.Peer();p.id=id;return p;}}')
    put('org/telegram/messenger/SendMessageChatArguments.java','package org.telegram.messenger;public class SendMessageChatArguments{public long welcomeMessageChatId;public String quickReplyShortcut;public int quickReplyShortcutId;public static class Builder{public void setWelcomeMessageChatId(long id){}public void setQuickReplyShortcut(String s,int id){}public SendMessageChatArguments build(){return new SendMessageChatArguments();}}}')
    put('org/telegram/messenger/VideoEditedInfo.java','package org.telegram.messenger;public class VideoEditedInfo{public boolean roundVideo;public long startTime,endTime,estimatedSize,estimatedDuration;public int framerate,resultWidth,originalWidth,resultHeight,originalHeight;public String originalPath;}')
    put('org/telegram/tgnet/TLRPC.java','package org.telegram.tgnet;public class TLRPC{public static class Message{public int id,local_id;}public static class Peer{public long id;}public static class Rights{public boolean anonymous;}public static class Chat{public long id;public Rights admin_rights;public boolean signatures;}public static class ChatFull{public Peer default_send_as;}}')
    put('org/telegram/SQLite/SQLiteCursor.java','package org.telegram.SQLite;public class SQLiteCursor{public boolean next(){return org.telegram.messenger.MessagesStorage.stored;}public void dispose(){}}')
    put('org/telegram/messenger/MessagesStorage.java', """package org.telegram.messenger;
import java.util.*;import org.telegram.SQLite.SQLiteCursor;import org.telegram.tgnet.TLRPC;
public class MessagesStorage{
public static boolean stored=true,immediate=true;public static int queries;public static Runnable afterQuery;public static List<Runnable> jobs=new ArrayList<>();
public static MessagesStorage getInstance(int a){return new MessagesStorage();}public MessagesStorage getStorageQueue(){return this;}public MessagesStorage getDatabase(){return this;}
public TLRPC.Message getMessage(long d,long id){return new TLRPC.Message();}
public void postRunnable(Runnable r){if(immediate)r.run();else jobs.add(r);}
public SQLiteCursor queryFinalized(String s,Object... a){queries++;if(afterQuery!=null)afterQuery.run();return new SQLiteCursor();}
public static void flush(){List<Runnable> q=new ArrayList<>(jobs);jobs.clear();for(Runnable r:q)r.run();}
}""")
    put('org/telegram/ui/Components/AlertsCreator.java',"""package org.telegram.ui.Components;
import java.util.function.LongConsumer;public class AlertsCreator{public static int confirmations;public static boolean defer;public static LongConsumer pending;
public static void ensurePaidMessageConfirmation(int a,long d,int n,LongConsumer c){confirmations++;if(defer)pending=c;else c.accept(0);}
public static void approve(){LongConsumer c=pending;pending=null;c.accept(0);}}
""")
    put('org/telegram/messenger/SendMessagesHelper.java',"""package org.telegram.messenger;
import org.telegram.messenger.purple.*;import org.telegram.tgnet.TLRPC;public class SendMessagesHelper{
public static int sends,prepared,rejected;public static long sentUser;public static Runnable beforeNative;
public static void prepareSendingVideo(Object... args){prepared++;if(beforeNative!=null)beforeNative.run();SendMessageChatArguments a=(SendMessageChatArguments)args[19];
if(PurpleRecordingSendAs.reject(1,(Long)args[5],a)){rejected++;return;}sends++;sentUser=UserConfig.identity;
SendMessageParams p=new SendMessageParams();p.peer=(Long)args[5];p.sendMessageChatArguments=a;TLRPC.Message m=new TLRPC.Message();m.id=m.local_id=-sends;PurpleRecordingDelivery.queued(1,p,m);}
public static class SendMessageParams{public SendMessageChatArguments sendMessageChatArguments;public long peer;public int scheduleDate;}}
""")
    shutil.copyfile(tests/'RetryIdentityBoundaryCases.java',output/'org/telegram/messenger/purple/RetryIdentityBoundaryCases.java')
    compiler=pathlib.Path(os.environ['JAVA_HOME'])/'bin/javac';classes=output/'classes'
    sources=sorted(output.rglob('*.java'))
    subprocess.run([str(compiler),'-d',str(classes),*map(str,sources)],check=True)
    subprocess.run([str(compiler.with_name('java')),'-cp',str(classes),'org.telegram.messenger.purple.RetryIdentityBoundaryCases',str(output/'files')],check=True)
