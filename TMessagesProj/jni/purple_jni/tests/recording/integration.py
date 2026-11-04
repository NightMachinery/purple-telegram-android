"""Compile exact recording adapters against inert UI and transport boundaries."""
import os, pathlib, shutil, subprocess

def run(root, output):
    tests = pathlib.Path(__file__).parent
    java_root = root / 'TMessagesProj/src/main/java/org/telegram'
    compiler = pathlib.Path(os.environ['JAVA_HOME']) / 'bin/javac'
    dest = output / 'integration'
    dest.mkdir()
    for source in (tests / 'round-host').rglob('*.java'):
        target = dest / source.relative_to(tests / 'round-host')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    shutil.copyfile(java_root / 'ui/PurpleRoundRecording.java', dest / 'org/telegram/ui/PurpleRoundRecording.java')
    def put(name, text):
        target = dest / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
    put('org/telegram/tgnet/TLRPC.java', 'package org.telegram.tgnet; public class TLRPC { public static class TL_document {} }')
    put('org/telegram/tgnet/tl/TL_stories.java', 'package org.telegram.tgnet.tl; public class TL_stories { public static class StoryItem {} }')
    put('org/telegram/messenger/SendMessagesHelper.java', """package org.telegram.messenger;
import org.telegram.tgnet.*; import org.telegram.tgnet.tl.*;
public class SendMessagesHelper {
 public static int sends;
 public static void prepareSendingVideo(Object... args) { sends++; }
 public static class SendMessageParams {
  public TLRPC.TL_document document; public long peer, monoForumPeer, effect_id, payStars;
  public String path; public MessageObject replyToMsg, replyToTopMsg;
  public SendMessageChatArguments sendMessageChatArguments;
  public MessageSuggestionParams suggestionParams; public TL_stories.StoryItem replyToStoryItem;
  public static SendMessageParams of(TLRPC.TL_document doc,Object v,String path,long peer,MessageObject reply,MessageObject top,Object a,Object b,Object c,Object d,boolean n,int date,int repeat,int ttl,Object e,Object f,boolean g) {
   SendMessageParams p=new SendMessageParams(); p.document=doc;p.path=path;p.peer=peer;p.replyToMsg=reply;p.replyToTopMsg=top;return p;
  }
 }
}""")
    enter = (java_root / 'ui/Components/ChatActivityEnterView.java').read_text()
    branch = enter[enter.index('MediaController.getInstance().cleanRecording(false);', enter.index('private void sendMessageInternal') if 'private void sendMessageInternal' in enter else 0):]
    start = branch.index('SendMessagesHelper.SendMessageParams params =')
    stop = branch.index('sendButton.setEffect(effectId = 0);', start)
    direct = branch[start:stop]
    hook = 'if (!org.telegram.messenger.purple.PurpleRecordingVoicePreview.apply(currentAccount, params)) return;'
    if hook in branch:
        assert branch.index(hook) > branch.index('applyStoryToSendMessageParams(params);')
        direct += hook
    voice = java_root / 'messenger/purple/PurpleRecordingVoicePreview.java'
    if voice.exists():
        shutil.copyfile(voice, dest / 'org/telegram/messenger/purple/PurpleRecordingVoicePreview.java')
    else:
        put('org/telegram/messenger/purple/PurpleRecordingVoicePreview.java', 'package org.telegram.messenger.purple; public class PurpleRecordingVoicePreview { public static void remember(Object... args) {} }')
    mc = (java_root / 'messenger/MediaController.java').read_text()
    resumed = mc.split('if (sendAfterDone == 1) {', 1)[1]
    resumed = resumed[:resumed.index('SendMessagesHelper.getInstance(recordingCurrentAccount).sendMessage(params);')]
    resumed = resumed[resumed.index('SendMessagesHelper.SendMessageParams params ='):]
    put('org/telegram/ui/VoicePreviewCases.java', """package org.telegram.ui;
import org.telegram.messenger.*; import org.telegram.messenger.purple.*;
import org.telegram.tgnet.*; import org.telegram.tgnet.tl.*;
public class VoicePreviewCases {
 int currentAccount=1; long dialog_id=100, effectId=17, payStars=2;
 boolean notify=true,voiceOnce=true; int scheduleDate=0;
 TLRPC.TL_document audioToSend=new TLRPC.TL_document(); String audioToSendPath="trimmed.ogg";
 MessageObject replyingMessageObject=new MessageObject(); ChatActivity parentFragment=new ChatActivity();
 SendMessagesHelper.SendMessageParams sent;
 MessageObject getThreadMessage(){return new MessageObject();}
 long getSendMonoForumPeerId(){return 99;}
 MessageSuggestionParams getSendMessageSuggestionParams(){return new MessageSuggestionParams();}
 void directSend(){""" + direct + """ sent=params; }
 void resumeSend(MessageObject recordReplyingMsg, MessageObject recordReplyingTopMsg, SendMessageChatArguments recordSendMessageChatArguments, TL_stories.StoryItem recordReplyingStory, MessageSuggestionParams recordMonoForumSuggestionParams) {
  TLRPC.TL_document recordingAudio=audioToSend; java.io.File file=new java.io.File(audioToSendPath);
  long recordDialogId=dialog_id,recordMonoForumPeerId=7,sendAfterDonePayStars=payStars;
  boolean sendAfterDoneNotify=notify,sendAfterDoneOnce=voiceOnce; int sendAfterDoneScheduleDate=scheduleDate;
 """ + resumed + """ sent=params;
 }
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("voice check "+checks);}
 public static void main(String[] args) {
  VoicePreviewCases view=new VoicePreviewCases();
  MessageObject reply=new MessageObject(),top=new MessageObject();
  SendMessageChatArguments sender=new SendMessageChatArguments();
  TL_stories.StoryItem story=new TL_stories.StoryItem();
  MessageSuggestionParams suggestion=new MessageSuggestionParams();
  PurpleRecordingVoicePreview.remember(1,view.audioToSend,100L,reply,top,story,sender,7L,suggestion);
  view.directSend();
  check(view.sent.replyToMsg==reply);check(view.sent.replyToTopMsg==top);
  check(view.sent.sendMessageChatArguments==sender);check(view.sent.replyToStoryItem==story);
  check(view.sent.monoForumPeer==7);check(view.sent.suggestionParams==suggestion);
  check(view.sent.path.equals("trimmed.ogg"));check(view.sent.effect_id==17);check(view.sent.payStars==2);
  view.resumeSend(reply,top,sender,story,suggestion);
  check(view.sent.replyToMsg==reply);check(view.sent.replyToTopMsg==top);
  check(view.sent.sendMessageChatArguments==sender);check(view.sent.replyToStoryItem==story);
  check(view.sent.monoForumPeer==7);check(view.sent.suggestionParams==suggestion);
  view.sent=null;view.currentAccount=2;view.directSend();check(view.sent==null);
  System.out.println("VOICE_PREVIEW_CHECKS="+checks);
 }
}""")
    sources = sorted(dest.rglob('*.java'))
    subprocess.run([str(compiler), '-d', str(dest / 'classes'), *map(str, sources)], check=True)
    for klass, args in [('org.telegram.ui.RoundReviewCases', ['backdrop']), ('org.telegram.ui.RoundReviewCases', ['destroy']), ('org.telegram.ui.RoundReviewCases', ['paid']), ('org.telegram.ui.VoicePreviewCases', [])]:
        subprocess.run([str(compiler.with_name('java')), '-cp', str(dest / 'classes'), klass, *args], check=True)
    mc = (java_root / 'messenger/MediaController.java').read_text()
    assert 'PurpleRecordingVoicePreview.remember(recordingCurrentAccount, recordingAudio,' in mc
    assert mc.count('params.sendMessageChatArguments = recordSendMessageChatArguments;') == 2
    for name in ['InstantCameraView.java', 'InstantCameraView2.java']:
        camera = (java_root / 'ui/Components' / name).read_text()
        assert 'PurpleRecordingDock.beginRoundFinish(currentAccount, recordingGuid)' in camera
        for method in ['public void cancel(boolean byGesture) {', 'public void destroy(boolean async) {']:
            assert camera.split(method, 1)[1].lstrip().startswith('if (org.telegram.messenger.purple.PurpleRecordingDock.roundFinalizing(currentAccount, recordingGuid)) return;')
    dock = (java_root / 'messenger/purple/PurpleRecordingDock.java').read_text()
    chooser = dock.split('public static void chooseTarget(long generation) {', 1)[1]
    assert chooser.index('startActivity(openIntent());') < chooser.index('LaunchActivity.getLastFragment()')
    foreground = dock.split('public static void foreground() {', 1)[1].split('private static void paidSend', 1)[0]
    assert foreground.index('ApplicationLoader.mainInterfacePaused') < foreground.index('chooseTarget(generation)')
    assert 'if (valid(generation)) chooseTarget(generation);' in foreground
    assert 'foregroundTarget = -1;' in dock.split('private static void clear() {',1)[1]
    print('RECORDING_INTEGRATION_PINS=12')
