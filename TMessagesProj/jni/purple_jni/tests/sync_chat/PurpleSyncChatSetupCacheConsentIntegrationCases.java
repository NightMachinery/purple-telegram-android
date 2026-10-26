package org.telegram.messenger.purple;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import org.telegram.messenger.*;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;
import static org.telegram.messenger.purple.PurpleSyncChatCases.*;
import static org.telegram.messenger.purple.PurpleSyncChatRequestCases.*;

final class PurpleSyncChatSetupCacheConsentIntegrationCases {
    static Object field(String name)throws Exception{java.lang.reflect.Field f=PurpleAccountOwner.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static void field(String name,Object value)throws Exception{java.lang.reflect.Field f=PurpleAccountOwner.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
    static void run(File parent)throws Exception{
        File oldFiles=ApplicationLoader.files;
        Map<String,Object> owner=new HashMap<>();for(String name:new String[]{"started","owner","global","ready"})owner.put(name,field(name));
        boolean[] active=new boolean[UserConfig.MAX_ACCOUNT_COUNT];long[] users=new long[active.length];
        for(int i=0;i<active.length;i++){active[i]=UserConfig.getInstance(i).active;users[i]=UserConfig.getInstance(i).user;}
        PurpleArchiveLogin.Source source=PurpleArchiveLogin.source;
        try{
            for(boolean discovered:new boolean[]{false,true}){
                File root=Files.createTempDirectory(parent.toPath(),"consent-restart-").toFile();ApplicationLoader.files=root;
                for(int i=0;i<active.length;i++){UserConfig.getInstance(i).active=i==0;UserConfig.getInstance(i).user=i==0?11:0;}
                String key=PurpleAccountFiles.capture(0).key;field("started",true);field("owner",key);field("global",key);field("ready",true);
                PurpleArchiveLogin.source=slot->"00000000000000000000000000000000";
                PurpleSyncChat.Platform platform=new PurpleSyncChat.Platform(0);PurpleSyncChat.Holder holder=new PurpleSyncChat.Holder(platform);
                check(platform.current(),"synthetic restart account is current");
                Cache mark=new Cache(CHANNEL,7,50);PurpleSyncChatClient.Info info=PurpleSyncChatRequestCases.info(CHANNEL);
                PurpleSyncChatCache.ReadersSnapshot original=PurpleSyncChatCache.ReadersSnapshot.from(info);
                if(discovered){
                    PurpleSyncChatCache.Loaded empty=PurpleSyncChatCache.load(platform.cache,platform.origin,platform.authority);
                    PurpleSyncChatClient.Info later=PurpleSyncChatRequestCases.info(CHANNEL);later.peer.facts.otherMembers=1L;
                    PurpleSyncChatCache.ReadersSnapshot discoveredBaseline=PurpleSyncChatCache.ReadersSnapshot.from(later);
                    check(!discoveredBaseline.json().toString().equals(original.json().toString()),"discovery may see a different post-consent reader baseline");
                    PurpleSyncChatCache.Data data=new PurpleSyncChatCache.Data(mark,"synthetic discovery",discoveredBaseline,Collections.singleton("library"),new org.json.JSONObject(),true);
                    check(PurpleSyncChatCache.save(platform.cache,empty,platform.origin,platform.authority,data,()->true),"discovery wins the consent-cache save before setup restart");
                }
                PurpleSyncChatSetupEnvironment env=new PurpleSyncChatSetupEnvironment(holder,()->true,()->true);
                check(env.commit(mark,info,original,false,()->true),"explicit consent commit after restart "+discovered);
                PurpleSyncChatCache.Loaded result=PurpleSyncChatCache.load(platform.cache,platform.origin,platform.authority);
                check(result.ok&&result.data!=null&&result.data.accepted("config"),"config consent is retained "+discovered);
                check(result.data.readers.json().toString().equals(original.json().toString()),"original consent baseline survives restart");
                check(!discovered||result.data.accepted("library")&&result.data.newReaders,"other stream receipt and sticky warning survive consent union");
                check(env.commit(mark,info,original,false,()->true),"repeated consent commit is idempotent");
                check(PurpleSyncChatCache.load(platform.cache,platform.origin,platform.authority).data.warned.size()==(discovered?2:1),"consent union has no duplicates");
                check(!env.commit(new Cache(CHANNEL,8,51),info,original,false,()->true),"different mark cannot inherit consent");
                check(!env.commit(mark,info,original,false,()->false),"revoked operation cannot add consent");
                holder.service.close(false);
            }
        }finally{
            PurpleArchiveLogin.source=source;ApplicationLoader.files=oldFiles;
            for(Map.Entry<String,Object> entry:owner.entrySet())field(entry.getKey(),entry.getValue());
            for(int i=0;i<active.length;i++){UserConfig.getInstance(i).active=active[i];UserConfig.getInstance(i).user=users[i];}
        }
    }
}
