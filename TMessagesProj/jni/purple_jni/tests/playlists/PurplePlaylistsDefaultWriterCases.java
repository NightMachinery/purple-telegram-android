package org.telegram.messenger.purple;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.Arrays;
public final class PurplePlaylistsDefaultWriterCases {
 private static int checks;
 private static void check(boolean ok,String why){++checks;if(!ok)throw new AssertionError(why);}
 private static String setting(boolean on){return "version=2\n# retained comment\n[playlists]\nkeep_downloaded_p="+on+"\n[unknown]\nkeep='preserved'\n";}
 private static void reset(String mode,boolean on){NativeWriterFixture.reset(mode);NativeWriterFixture.replace(0,setting(on));NativeWriterFixture.replace(1,setting(!on));PurpleAccountSettings.invalidate();}
 private static PurpleAccountSettings.Effective get(int slot){PurpleAccountSettings.Effective value=PurpleAccountSettings.forAccount(slot);check(value!=null&&value.current()&&value.playlistsReady,"whole actual account settings available");return value;}
 private static PurpleWriter.Session capture(int slot,PurpleWriter.Current view){PurpleWriter.Capture c=PurpleWriter.captureResult(slot,true,view,null);check(c.session!=null,"whole production writer captures app-wide route");check(c.session.appWide&&c.session.globalKey.equals("0-101"),"writer app-wide route ignores account Work Mode choice");return c.session;}
 private static String save(PurpleWriter.Session s,boolean value){return PurpleWriter.setTableBool(s,"playlists","keep_downloaded_p",value,"playlist keep default");}
 public static void main(String[] args)throws Exception {
  System.load(System.getProperty("purple.test.library"));PurpleCore.ensureLoaded();
  for(String name:new String[]{"PurpleWriter","PurpleRoutedWriteModel","PurpleAccountEditorModel","PurpleAccountSettings","PurpleAccountFileSnapshot"}){
   Class<?> c=Class.forName("org.telegram.messenger.purple."+name);check(new File(c.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile().equals(new File(args[0]).getCanonicalFile()),"actual whole production class bytecode origin: "+name);
  }
  for(String mode:new String[]{"follow","own"})for(int slot:new int[]{0,1})for(boolean before:new boolean[]{false,true}){
   reset(mode,before);PurpleAccountSettings.Effective opening=get(slot),other=get(1-slot);String own=NativeWriterFixture.text(1),local=NativeWriterFixture.local();
   check(opening.playlistsKeepDownloaded==before&&other.playlistsKeepDownloaded==before,"both accounts resolve global default despite opposite account-local value");
   PurpleWriter.Session session=capture(slot,()->true);session.guardBeforePublish(()->opening.current()&&opening.playlistsReady);
   long generation=PurpleAccountSettings.generation();String error=save(session,!before);session.retire();
   check(error==null,"actual editor permits prepublication admission to retire after successful refresh");
   check(!opening.current()&&!other.current()&&PurpleAccountSettings.generation()==generation+2,"successful publication invalidates both cached effective settings");
   check(get(slot).playlistsKeepDownloaded==!before&&get(1-slot).playlistsKeepDownloaded==!before,"next reads immediately see committed app-wide default");
   check(NativeWriterFixture.text(1).equals(own)&&NativeWriterFixture.local().equals(local),"global default changes neither account settings nor Work Mode route");
   check(NativeWriterFixture.text(0).contains("# retained comment")&&NativeWriterFixture.text(0).contains("keep='preserved'"),"real shared splice preserves comments and unrelated settings");
   check(NativeWriterFixture.synced==1&&NativeWriterFixture.notified==1,"one publication notifies and submits exact committed bytes to inert sync endpoint");
   check(save(session,before)!=null&&get(slot).playlistsKeepDownloaded==!before,"retired session cannot publish a second toggle");
  }
  reset("follow",true);PurpleAccountSettings.Effective stale=get(1);PurpleWriter.Session session=capture(1,()->true);String original=NativeWriterFixture.text(0);
  session.guardBeforePublish(()->false);check(save(session,false)!=null&&NativeWriterFixture.text(0).equals(original)&&NativeWriterFixture.synced==0,"stale screen admission refuses before publication");
  reset("own",true);boolean[] live={true};session=capture(1,()->live[0]);live[0]=false;check(save(session,false)!=null&&NativeWriterFixture.text(0).equals(setting(true)),"paused or replaced view refuses before publication");
  reset("own",true);session=capture(1,()->true);NativeWriterFixture.replaceAccount(1);check(save(session,false)!=null&&NativeWriterFixture.text(0).equals(setting(true)),"captured account replacement refuses app-wide write");
  reset("follow",true);session=capture(1,()->true);NativeWriterFixture.replace(0,setting(false));check(save(session,true)!=null&&NativeWriterFixture.text(0).equals(setting(false)),"external global change after capture is preserved");
  reset("own",true);session=capture(1,()->true);NativeWriterFixture.duringWrite=()->NativeWriterFixture.replace(0,"version=2\n# replacement during backup\n[playlists]\nkeep_downloaded_p=true\n");
  check(save(session,false)!=null&&NativeWriterFixture.text(0).contains("replacement during backup")&&NativeWriterFixture.synced==0,"actual backup write rechecks external settings before final publication");
  reset("follow",true);stale=get(1);NativeWriterFixture.replace(0,setting(false));check(get(1)==stale,"external settings are cached until watcher invalidation");PurpleAccountSettings.invalidate();check(!stale.current()&&!get(1).playlistsKeepDownloaded,"watcher invalidation refreshes shared global source");
  reset("own",true);session=capture(1,()->true);PurpleAccountSettings.invalidate();check(save(session,false)!=null,"settings epoch change refuses retained writer");
  reset("follow",true);session=capture(1,()->true);session.retire();check(save(session,false)!=null,"explicit retirement refuses publication");
  reset("follow",true);PurpleAccountSettings.Effective old=get(1);NativeWriterFixture.replaceAccount(1);check(!old.current()&&get(1)!=old,"same account key with replaced lifetime refreshes cache");
  reset("own",true);NativeWriterFixture.replace(0,"[broken");PurpleAccountSettings.invalidate();PurpleAccountSettings.Effective bad=PurpleAccountSettings.forAccount(1);check(bad!=null&&!bad.playlistsReady,"malformed shared default never permits confident playlist toggle");
  for(boolean on:new boolean[]{false,true}){
   reset("own",on);PurpleAccountSettings.Effective previous=get(0);PurpleWriter.Session captured=capture(1,()->true);
   PurpleAccountOwner.choose("0-202");
   check(!previous.current(),"global source role/revision change immediately retires captured effective settings");
   check(get(0).playlistsKeepDownloaded==!on&&get(1).playlistsKeepDownloaded==!on,"actual cache resolves newly selected global settings source for both accounts");
   check(save(captured,!on)!=null&&NativeWriterFixture.text(0).equals(setting(on))&&NativeWriterFixture.text(1).equals(setting(!on)),"global route replacement refuses retained writer without touching either source");
   PurpleAccountOwner.choose("0-101");check(get(1).playlistsKeepDownloaded==on&&!previous.current(),"switching away and back refreshes source without reviving captured epoch");
  }
  System.out.println("PLAYLIST_DEFAULT_WRITER_CHECKS="+checks);
 }
}
