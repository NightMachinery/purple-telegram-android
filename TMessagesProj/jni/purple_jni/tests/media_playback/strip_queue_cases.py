"""Host ports for real strip geometry, playlist building, completion routing and media-button receiver."""
from pathlib import Path
import subprocess
import json
import xml.etree.ElementTree as ET


def block(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


def run(root, output, compiler):
    java = root / 'TMessagesProj/src/main/java/org/telegram'
    src = output / 'ports'
    def put(name, text):
        path = src / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    media = (java / 'messenger/MediaController.java').read_text()
    chat = (java / 'ui/ChatActivity.java').read_text()
    build = block(chat, '    private ArrayList<MessageObject> createVoiceMessagesPlaylist(').replace('private ', 'public ', 1)
    completion = media.split('                            messageObject.audioProgress = 1f;', 1)[1].split('                        } else if (audioPlayer != null && seekToProgressPending', 1)[0]
    completion = 'messageObject.audioProgress = 1f;' + completion
    cleanup = media.split('                int index = -1;', 1)[1].split('            if (stopService) {', 1)[0].rsplit('}', 1)[0]
    cleanup = 'int index = -1;' + cleanup
    round_end = block(media, '                if (org.telegram.messenger.purple.PurpleVoicePlayback.advance(this)) return;\n                final boolean restored = restoreMusicPlaylistState();\n                if (!restored) {') if False else media.split('                if (org.telegram.messenger.purple.PurpleVoicePlayback.advance(this)) return;',1)[1].split('\n            }\n        }\n    }',1)[0]
    round_end = 'if (org.telegram.messenger.purple.PurpleVoicePlayback.advance(this)) return;' + round_end
    restore = block(media, '    private boolean restoreMusicPlaylistState()')
    player = (java / 'messenger/purple/PurplePlaylistsPlayer.java').read_text()
    same_message = block(player, '    public static boolean same(MessageObject first, MessageObject second)')
    saved_state = block(media, '    private static class SavedMusicPlaylistState').replace('private ', '', 1)
    music_state = ''.join(block(media, signature) for signature in [
        '    private void clearMusicPlaylistState()',
        '    public boolean purpleHasInterruptedMusic()',
        '    private boolean saveMusicPlaylistStateIfNeeded(',
    ])
    start = media.index('        final boolean saved = saveMusicPlaylistStateIfNeeded(messageObject);')
    before_cleanup = media[start:media.index('        cleanupPlayer(notify, false);', start)]
    no_next = block(media, '    public boolean hasNoNextVoiceOrRoundVideoMessage()')
    setter = block(media, '    public void setVoiceMessagesPlaylist(')
    receiver = (java / 'messenger/MusicPlayerReceiver.java').read_text()
    put('android/content/BroadcastReceiver.java', 'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}')
    put('android/content/Context.java', 'package android.content;public class Context {}')
    put('android/os/Bundle.java', 'package android.os;public class Bundle {public Object value; public Object get(String k){return value;}}')
    put('android/content/Intent.java', 'package android.content;public class Intent {public static final String ACTION_MEDIA_BUTTON="android.intent.action.MEDIA_BUTTON",EXTRA_KEY_EVENT="event";public String action=ACTION_MEDIA_BUTTON;public android.os.Bundle extras=new android.os.Bundle();public String getAction(){return action;}public android.os.Bundle getExtras(){return extras;}}')
    put('android/view/KeyEvent.java', 'package android.view;public class KeyEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_HEADSETHOOK=79,KEYCODE_MEDIA_PLAY_PAUSE=85,KEYCODE_MEDIA_PLAY=126,KEYCODE_MEDIA_PAUSE=127,KEYCODE_MEDIA_STOP=86,KEYCODE_MEDIA_NEXT=87,KEYCODE_MEDIA_PREVIOUS=88;private final int action,key;public KeyEvent(int a,int k){action=a;key=k;}public int getAction(){return action;}public int getKeyCode(){return key;}}')
    put('android/util/SparseArray.java', 'package android.util;public class SparseArray<T> extends java.util.HashMap<Integer,T>{public void put(int k,T v){super.put(k,v);}public void remove(int k){super.remove(k);}}')
    put('org/telegram/messenger/MessageObject.java', '''package org.telegram.messenger;
public class MessageObject {
 public int currentAccount=0,id;public long dialog,eventId;public boolean out,round,music,video,unread=true,once;public float audioProgress,forceSeekTo;public int audioProgressMs,audioProgressSec;
 public MessageObject(int id,long dialog,boolean out,boolean round){this.id=id;this.dialog=dialog;this.out=out;this.round=round;}
 public static class Document{public long id;}private final Document document=new Document();
 public Document getDocument(){document.id=id;return document;}
 public int getId(){return id;}public long getDialogId(){return dialog;}public boolean isOut(){return out;}public boolean isVoice(){return !round&&!music&&!video;}public boolean isVideo(){return video;}public boolean isRoundVideo(){return round;}public boolean isMusic(){return music;}public boolean isContentUnread(){return unread;}public boolean isVoiceOnce(){return once&&!round;}public boolean isRoundOnce(){return once&&round;}public void resetPlayingProgress(){audioProgress=0;}
}''')
    put('org/telegram/messenger/DialogObject.java', 'package org.telegram.messenger;public class DialogObject {public static boolean isEncryptedDialog(long d){return false;}public static boolean isUserDialog(long d){return d>0;}}')
    put('org/telegram/messenger/UserConfig.java', 'package org.telegram.messenger;public class UserConfig {public static UserConfig getInstance(int a){return new UserConfig();}public long getClientUserId(){return 1;}}')
    put('org/telegram/messenger/UserObject.java', 'package org.telegram.messenger;public class UserObject {public static final long VERIFY=777,ANONYMOUS=778;public static boolean isReplyUser(long d){return false;}public static boolean isService(long d){return false;}}')
    put('org/telegram/messenger/SharedConfig.java', 'package org.telegram.messenger;public class SharedConfig {public static boolean shuffleMusic;public static int repeatMode;public static void setRepeatMode(int i){repeatMode=i;}}')
    put('android/media/AudioManager.java', 'package android.media;public class AudioManager {public static final String ACTION_AUDIO_BECOMING_NOISY="noisy";}')
    put('org/telegram/messenger/MusicPlayerService.java', 'package org.telegram.messenger;public class MusicPlayerService {public static final String NOTIFY_PLAY="play",NOTIFY_PAUSE="pause",NOTIFY_NEXT="next",NOTIFY_CLOSE="close",NOTIFY_PREVIOUS="previous",NOTIFY_REPEAT="repeat",NOTIFY_SHUFFLE="shuffle";}')
    put('org/telegram/messenger/NotificationCenter.java', 'package org.telegram.messenger;public class NotificationCenter {public static final int messagePlayingProgressDidChanged=1,messagePlayingDidReset=2;public static NotificationCenter getInstance(int a){return new NotificationCenter();}public void postNotificationName(int n,Object... args){}}')
    put('org/telegram/messenger/purple/PurpleSleepTimer.java', 'package org.telegram.messenger.purple;public final class PurpleSleepTimer {private static final PurpleSleepTimer INSTANCE=new PurpleSleepTimer();public static PurpleSleepTimer get(){return INSTANCE;}public boolean localPause(){return false;}}')
    put('org/telegram/messenger/purple/PurpleSleepTimerAudio.java', 'package org.telegram.messenger.purple;\nimport org.telegram.messenger.*;import java.util.function.BooleanSupplier;\npublic final class PurpleSleepTimerAudio {\n public static int automaticCalls,restoreCalls,transportCalls;private final MediaController controller;\n public PurpleSleepTimerAudio(MediaController c){controller=c;}\n public Object capture(){return this;}\n public boolean autoPlay(MessageObject m,Object receipt){if(receipt!=this)throw new AssertionError("wrong automatic receipt");automaticCalls++;return controller.playMessage(m);}\n public boolean restore(MessageObject m,BooleanSupplier stock){restoreCalls++;return stock.getAsBoolean();}\n public static void transportPlay(MediaController c){transportCalls++;c.playMessage(c.getPlayingMessageObject());}\n}')
    put('org/telegram/messenger/purple/PurpleCore.java', 'package org.telegram.messenger.purple;public class PurpleCore {public static final int PLAYBACK_ONE_TO_ONE=0,PLAYBACK_SELF=1,PLAYBACK_OTHER=2;public static boolean on=true;public static int settings=5;public static int voiceContinuationSettings(){return settings;}public static int voiceContinuationDecision(int m,boolean out,boolean unread,boolean kept){return m==0?2:m==1?(out?0:unread?1:2):kept?1:0;}public static boolean voiceQueueKeeps(int chat,boolean start,boolean out){return !(on&&chat==PLAYBACK_ONE_TO_ONE&&!start&&out);}}')
    put('org/telegram/messenger/purple/PurplePlaylistsPlayer.java',
        'package org.telegram.messenger.purple; import org.telegram.messenger.MessageObject;'
        'public final class PurplePlaylistsPlayer { public void restored(MessageObject message) {} '
        + same_message + '}')
    put('org/telegram/messenger/purple/PurpleVoiceQueue.java', (java/'messenger/purple/PurpleVoiceQueue.java').read_text())
    put('org/telegram/messenger/purple/PurpleVoicePlayback.java', (java/'messenger/purple/PurpleVoicePlayback.java').read_text())
    put('org/telegram/ui/PurplePlayerStripLayout.java', (java/'ui/PurplePlayerStripLayout.java').read_text())
    put('org/telegram/ui/PurpleChatHooks.java', 'package org.telegram.ui;import org.telegram.messenger.*;public class PurpleChatHooks {ChatActivity chat;PurpleChatHooks(ChatActivity c){chat=c;}java.util.ArrayList<MessageObject> voicePlaylist(MessageObject s,boolean u,long merge){return org.telegram.messenger.purple.PurpleVoicePlayback.playlist(s,chat.messages,false,u,merge);}boolean skipsInVoicePlaylist(MessageObject s,MessageObject c){return org.telegram.messenger.purple.PurpleVoiceQueue.skipsInPlaylist(s,c);}}')
    put('org/telegram/ui/ChatActivity.java', 'package org.telegram.ui;import java.util.*;import org.telegram.messenger.*;public class ChatActivity {public ArrayList<MessageObject> messages=new ArrayList<>();long mergeDialogId=-999;Object currentEncryptedChat;PurpleChatHooks purple=new PurpleChatHooks(this);'+build+'}')
    put('org/telegram/messenger/MediaController.java', '''package org.telegram.messenger;
import java.util.*;import android.util.SparseArray;import org.telegram.messenger.purple.PurpleSleepTimer;
public class MediaController {
 final org.telegram.messenger.purple.PurpleSleepTimerAudio purpleSleep=new org.telegram.messenger.purple.PurpleSleepTimerAudio(this);
 final org.telegram.messenger.purple.PurplePlaylistsPlayer purplePlaylists=new org.telegram.messenger.purple.PurplePlaylistsPlayer();
 public static final MediaController instance=new MediaController();public static MediaController getInstance(){return instance;}
 public MessageObject playingMessageObject;public ArrayList<MessageObject> playlist=new ArrayList<>(),shuffledPlaylist=new ArrayList<>(),voiceMessagesPlaylist;public SparseArray<MessageObject> voiceMessagesPlaylistMap;boolean voiceMessagesPlaylistUnread,playMusicAgain;int currentPlaylistNum,pipSwitchingState;Pip pipRoundVideoView;public boolean paused;public int restores,plays;SavedMusicPlaylistState savedMusicPlaylistState;
 static class Pip{void close(boolean v){}}
 static class AudioPlayer{boolean playing=true;boolean isPlaying(){return playing;}}
 AudioPlayer audioPlayer;boolean downloadingCurrentMessage;
 '''+saved_state+music_state+'''
 public void setup(ArrayList<MessageObject> voices,MessageObject music){playlist.clear();shuffledPlaylist.clear();savedMusicPlaylistState=null;paused=downloadingCurrentMessage=false;audioPlayer=new AudioPlayer();plays=restores=0;playingMessageObject=voices.get(0);setVoiceMessagesPlaylist(voices,false);if(music!=null){playlist.add(music);currentPlaylistNum=0;music.audioProgress=.4f;music.audioProgressMs=400;savedMusicPlaylistState=new SavedMusicPlaylistState(music,true);}}
 public MessageObject getPlayingMessageObject(){return playingMessageObject;}
 public boolean isMessagePaused(){return paused||downloadingCurrentMessage;}
 public boolean playMessage(MessageObject messageObject){'''+before_cleanup+'''if(messageObject.isVoice()||messageObject.isRoundVideo()||messageObject.isVideo()){if(!saved)playlist.clear();}playingMessageObject=messageObject;paused=downloadingCurrentMessage=false;plays++;return true;}
 public boolean pauseMessage(MessageObject m){return pauseMessage(m,false);}public boolean pauseMessage(MessageObject m,boolean ignored){paused=true;return true;}
 public void setPlaybackOrderType(int v){}public void playNextMessage(){}public void playPreviousMessage(){}public void playPreviousMessageWithoutOrder(){}
 public void cleanupPlayer(boolean a,boolean b){playingMessageObject=null;}public void seekToProgress(MessageObject m,float p){}
 void playNextMessageWithoutOrder(boolean b){throw new AssertionError("voice sent to music skip");}void startRecordingIfFromSpeaker(){}
 public void complete(){if(playingMessageObject.isRoundVideo()){completeRound();return;}MessageObject messageObject=playingMessageObject;'''+completion+'''}
 public void cleanupPlayer(boolean notify,boolean stopService,boolean byVoiceEnd,boolean transfer){MessageObject lastFile=playingMessageObject;playingMessageObject=null;boolean playingNext=false;'''+cleanup+'''}
'''+ 'public void completeRound(){'+round_end+'}'+restore+no_next+setter+'}')
    put('org/telegram/messenger/MusicPlayerReceiver.java', receiver)
    palette = block((java / 'ui/PurpleRecordingStrip.java').read_text(), '    private void recolor()').replace('private void', 'public void', 1)
    put('org/telegram/ui/StripPalettePort.java', PALETTE.replace('RECOLOR_BODY', palette))
    settings = (java / 'ui/PurpleSettingsActivity.java').read_text()
    labels = [block(settings, '    private CharSequence ' + name + '(int mode)').replace('private ', 'public ', 1)
              for name in ['afterVoiceSummary', 'afterVoiceLabel']]
    resources = {e.attrib['name']: e.text for e in ET.parse(root / 'TMessagesProj/src/main/res/values/purple_strings.xml').getroot()}
    names = ['PurpleAfterVoiceResume', 'PurpleAfterVoiceUnheard', 'PurpleAfterVoiceAll',
             'PurpleAfterVoiceUnheardShort', 'PurpleAfterVoiceAllShort']
    constants = ';'.join('static final int %s=%d' % (name, i) for i, name in enumerate(names)) + ';'
    values = ','.join(json.dumps(resources[name]) for name in names)
    put('org/telegram/ui/VoiceLabelPort.java', LABELS.replace('METHODS', ''.join(labels)).replace('CONSTANTS', constants).replace('VALUES', values))
    put('org/telegram/messenger/PortCases.java', CASES)
    classes=output/'port-classes'
    sources=sorted(src.rglob('*.java'))
    subprocess.run([str(compiler), '--release', '8', '-d', str(classes), *map(str,sources)],check=True)
    subprocess.run([str(compiler.with_name('java')), '-cp',str(classes),'org.telegram.messenger.PortCases'],check=True)


CASES = r'''package org.telegram.messenger;
import java.util.*;import org.telegram.ui.*;import org.telegram.messenger.purple.*;
public class PortCases {
 static int checks;static void check(boolean b,String n){checks++;if(!b)throw new AssertionError(n);}
 static MessageObject msg(int id,long dialog,boolean out,boolean round){return new MessageObject(id,dialog,out,round);}
 static void queue(long dialog,boolean option,int mode,boolean resume,boolean music,boolean ownStart,boolean roundStart){
  PurpleCore.on=option;PurpleCore.settings=mode|(resume?4:0);ChatActivity chat=new ChatActivity();MessageObject start=msg(10,dialog,ownStart,roundStart);
  MessageObject incomingRound=msg(11,dialog,false,true),ownVoice=msg(12,dialog,true,false),incomingVoice=msg(13,dialog,false,false),heardRound=msg(14,dialog,false,true),laterVoice=msg(15,dialog,false,false);heardRound.unread=false;ownVoice.unread=false;
  chat.messages.addAll(Arrays.asList(laterVoice,heardRound,incomingVoice,ownVoice,incomingRound,start));
  MediaController.instance.savedMusicPlaylistState=music?new MediaController.SavedMusicPlaylistState(msg(50,99,false,false),true):null;
  ArrayList<MessageObject> list=chat.createVoiceMessagesPlaylist(start,false);
  mode=music?mode:2;
  boolean confined=option&&dialog==42&&!ownStart;
  ArrayList<MessageObject> expected=new ArrayList<>();expected.add(start);
  if(mode==1)expected.addAll(Arrays.asList(incomingRound,incomingVoice));
  if(mode==2){expected.add(incomingRound);if(!confined)expected.add(ownVoice);expected.addAll(Arrays.asList(incomingVoice,heardRound,laterVoice));}
  check(list.equals(expected),"mode "+mode+" mixed queue "+dialog+option+ownStart);
  PurpleCore.settings=(2-mode)|(resume?0:4);
  MediaController c=MediaController.instance;MessageObject track=music?msg(50,99,false,false):null;if(track!=null)track.music=true;c.setup(list,track);
  for(int i=1;i<list.size();i++){c.complete();check(c.playingMessageObject==list.get(i),"next mixed message plays "+i);check(!c.paused,"next message playing");if(music)check(c.playlist.contains(track),"interrupted music list preserved");}
  c.complete();check(c.playingMessageObject==track,"queue end returns music or stops");
  if(music){check(c.paused!=resume,"resume switch controls restored track");check(track.forceSeekTo==.4f&&track.audioProgress==.4f,"restored position retained");}
  check(PurpleVoicePlayback.skipsAppended(msg(20,dialog,true,true))==(mode!=2||confined),"own live round follows mode");
  check(PurpleVoicePlayback.skipsAppended(msg(21,dialog,false,true))==(mode!=2),"incoming live round cannot cross heard barrier");
 }
 static void voiceInterruption() {
  PurpleCore.settings=4;
  MediaController c=MediaController.instance;
  for(boolean round:new boolean[]{false,true})for(int state=0;state<5;state++){
   MessageObject voice=msg(10,42,false,round),track=msg(50,99,false,false);
   ArrayList<MessageObject> list=new ArrayList<>(Collections.singletonList(voice));
   track.music=true;track.audioProgress=.4f;track.audioProgressMs=400;
   c.setup(list,null);c.playingMessageObject=track;c.playlist.add(track);c.currentPlaylistNum=0;
   c.paused=state==1;
   if(state==2)c.audioPlayer=null;
   c.downloadingCurrentMessage=state==3;
   if(state==4)c.audioPlayer.playing=false;
   c.playMessage(voice);
   check(c.purpleHasInterruptedMusic()==(state==0),"interruption requires active music "+round+" "+state);
   c.setVoiceMessagesPlaylist(PurpleVoicePlayback.playlist(voice,list,false,false,0),false);
   c.complete();
   check(c.playingMessageObject==(state==0?track:null),"voice ends without starting inactive music "+round+" "+state);
   if(state==0)check(!c.paused&&track.forceSeekTo==.4f&&track.audioProgressMs==400,"active song resumes at saved position");
  }
  MessageObject voice=msg(10,42,false,false),video=msg(20,42,false,false),track=msg(50,99,false,false);
  video.video=true;track.music=true;
  ArrayList<MessageObject> list=new ArrayList<>(Collections.singletonList(voice));
  c.setup(list,null);c.playingMessageObject=track;c.playlist.add(track);
  c.playMessage(video);
  check(c.savedMusicPlaylistState!=null&&!c.purpleHasInterruptedMusic(),"ordinary video keeps its own music snapshot");
  c.playMessage(voice);
  check(!c.purpleHasInterruptedMusic(),"voice after video does not inherit the earlier song");
  c.setVoiceMessagesPlaylist(PurpleVoicePlayback.playlist(voice,list,false,false,0),false);
  c.complete();
  check(c.playingMessageObject==null,"voice after video stops without playing music");
 }
 static boolean overlap(int a,int aw,int b,int bw){return aw>0&&bw>0&&a<b+bw&&b<a+aw;}
 static void geometry(){
  check(PurplePlayerStripLayout.HEIGHT_DP==48,"48dp height");
  for(int width:new int[]{240,280,320,360,480,800})for(boolean rtl:new boolean[]{false,true})for(boolean play:new boolean[]{false,true})for(boolean close:new boolean[]{false,true})for(boolean speed:new boolean[]{false,true})for(int side:new int[]{0,64}){
   PurplePlayerStripLayout b=new PurplePlayerStripLayout(width,48,36,side,rtl,play,close,speed);
   check(b.titleLeft>=0&&b.titleWidth>=0&&b.titleLeft+b.titleWidth<=width,"title in bounds");
   int[] x={b.backLeft,b.forwardLeft,b.playLeft,b.closeLeft,b.speedLeft},w={48,48,play?48:0,close?36:0,speed?36:0};
   for(int i=0;i<x.length;i++){if(w[i]==0)continue;check(x[i]>=0&&x[i]+w[i]<=width,"control in bounds");check(!overlap(b.titleLeft,b.titleWidth,x[i],w[i]),"title clear");for(int k=0;k<i;k++)check(!overlap(x[i],w[i],x[k],w[k]),"controls clear");}
  }
 }
 static void buttons(){
  PurpleSleepTimerAudio.transportCalls=0;
  MusicPlayerReceiver r=new MusicPlayerReceiver();MediaController c=MediaController.instance;c.playingMessageObject=msg(10,42,false,false);
  for(boolean music:new boolean[]{false,true})for(int key:new int[]{android.view.KeyEvent.KEYCODE_HEADSETHOOK,android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}){
   c.playingMessageObject.music=music;
   c.paused=false;android.content.Intent intent=new android.content.Intent();intent.extras.value=new android.view.KeyEvent(0,key);r.onReceive(null,intent);check(c.paused,"down pauses "+key);
   intent.extras.value=new android.view.KeyEvent(1,key);r.onReceive(null,intent);check(c.paused,"up does not toggle twice "+key);
   intent.extras.value=new android.view.KeyEvent(0,key);r.onReceive(null,intent);check(!c.paused,"next down plays "+key);
  }
  check(PurpleSleepTimerAudio.transportCalls==4,"actual receiver reaches the explicit timer transport port once per Play");
 }
 public static void main(String[] args){
  System.out.println("MEDIA_STRIP_THEME_CHECKS="+StripPalettePort.run());
  System.out.println("MEDIA_VOICE_LABEL_CHECKS="+VoiceLabelPort.run());
  geometry();System.out.println("MEDIA_STRIP_LAYOUT_CHECKS="+checks);checks=0;
  for(long dialog:new long[]{42,-42,-43,1})for(boolean on:new boolean[]{false,true})for(int mode:new int[]{0,1,2})for(boolean resume:new boolean[]{false,true})for(boolean music:new boolean[]{false,true})for(boolean own:new boolean[]{false,true})for(boolean round:new boolean[]{false,true})queue(dialog,on,mode,resume,music,own,round);
  check(PurpleSleepTimerAudio.automaticCalls>0,"actual completion reaches the timer automatic port");
  check(PurpleSleepTimerAudio.restoreCalls>0,"actual saved music restore reaches the timer port");
  System.out.println("MEDIA_MIXED_QUEUE_CHECKS="+checks);checks=0;
  voiceInterruption();System.out.println("MEDIA_VOICE_INTERRUPTION_CHECKS="+checks);checks=0;buttons();System.out.println("MEDIA_BUTTON_CHECKS="+checks);
 }
}'''


PALETTE = r'''package org.telegram.ui;
public class StripPalettePort {
 static class Theme {
  static final int key_inappPlayerTitle=1,key_inappPlayerPerformer=2;
  static class ResourcesProvider {int primary,secondary;ResourcesProvider(int p,int s){primary=p;secondary=s;}}
  static int getColor(int key,ResourcesProvider p){return key==key_inappPlayerPerformer?p.primary:p.secondary;}
  static Object createSelectorDrawable(int color,int kind,int radius){return Integer.valueOf(color);}
 }
 static class TextView {
  int tint,writes;Object background;
  Object getBackground(){return background;}void setTextColor(int c){tint=c;writes++;}void setBackground(Object b){background=b;}
 }
 Theme.ResourcesProvider resourcesProvider;int color;TextView back=new TextView(),forward=new TextView();
 static int dp(int value){return value;}
 RECOLOR_BODY
 static int checks;static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 public static int run(){
  StripPalettePort p=new StripPalettePort();
  for(int[] colors:new int[][]{{0xfff5f5f5,0xff70818d},{0xff1a1d21,0xff1a1d21},{0xffffd370,0xff807059}}){
   p.resourcesProvider=new Theme.ResourcesProvider(colors[0],colors[1]);p.recolor();
   check(p.back.tint==colors[0],"back uses full primary player text");check(p.forward.tint==colors[0],"forward uses full primary player text");
   check((p.back.tint>>>24)==255,"primary text remains opaque");check(p.back.background.equals(colors[0]&0x19ffffff),"only selector has reduced alpha");
   int writes=p.back.writes;p.recolor();check(p.back.writes==writes,"stable theme does not recreate tint");
   p.back.background=null;p.recolor();check(p.back.writes==writes+1,"missing selector is restored");
  }
  return checks;
 }
}'''


LABELS = r'''package org.telegram.ui;
public class VoiceLabelPort {
 static class R {static class string {CONSTANTS}}
 static final String[] values={VALUES};
 static String getString(int id){return values[id];}
 METHODS
 static int checks;static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 public static int run(){
  VoiceLabelPort p=new VoiceLabelPort();
  String[] shortExpected={"Resume music","Unheard, then music","All, then music"};
  String[] fullExpected={"Resume music","Play unheard, then resume music","Play all following, then resume music"};
  for(int mode=0;mode<3;mode++){
   String summary=p.afterVoiceSummary(mode).toString(),full=p.afterVoiceLabel(mode).toString();
   check(summary.equals(shortExpected[mode]),"compact row label "+mode);
   check(full.equals(fullExpected[mode]),"full dialog label "+mode);
   check(summary.length()<=20,"row value stays concise "+mode);
   if(mode!=0)check(full.length()>summary.length(),"dialog retains explanation "+mode);
  }
  return checks;
 }
}'''
