package org.telegram.messenger.purple;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

final class PurpleSyncRecovery {
    private static final class PreferenceRead {
        final JSONArray others=new JSONArray(), retained=new JSONArray(), active=new JSONArray(), observed=new JSONArray();
        boolean complete=true;
    }
    private static PreferenceRead preferences(int account,PurpleAccountBinding.Binding binding) throws Exception {
        PreferenceRead read=new PreferenceRead();
        for(int slot=0;slot<UserConfig.MAX_ACCOUNT_COUNT;++slot) {
            PurpleAccountFiles.Identity identity=PurpleAccountFiles.capture(slot);
            if(identity!=null)read.active.put(identity.key);
            read.observed.put(new JSONObject().put("slot",slot).put("identity",identity!=null?identity.key:"")
                    .put("lifetime",identity!=null?Long.toString(identity.lifetime):""));
            Map<String,?> all=MessagesController.getMainSettings(slot).getAll();
            ArrayList<String> names=new ArrayList<>();
            for(String name:all.keySet())if(name.startsWith(PurpleAccountBinding.KEY_PREFIX))names.add(name);
            Collections.sort(names);
            for(String name:names) {
                Object value=all.get(name);
                String suffix=name.substring(PurpleAccountBinding.KEY_PREFIX.length());
                boolean own=slot==account&&suffix.equals(Long.toString(binding.userId));
                if(!own)read.observed.put(new JSONObject().put("slot",slot).put("name",name).put("value",value instanceof String?value:JSONObject.NULL));
                if(own)continue;
                String key=null;
                if(PurpleSyncRecoveryFiles.key(suffix))key=suffix;
                else if(identity!=null&&suffix.equals(Long.toString(identity.userId))&&identity.current())key=identity.key;
                if(key==null || identity==null || !identity.key.equals(key)) {
                    String user=suffix.matches("[1-9][0-9]*")?suffix:"";
                    read.retained.put(new JSONObject().put("account",key!=null?key:"").put("user",user)
                            .put("token",value instanceof String?value:""));
                    continue;
                }
                if(!(value instanceof String)) { read.complete=false;continue; }
                read.others.put(new JSONObject().put("account",key).put("token",value));
            }
        }
        return read;
    }
    static PurpleSyncRecoveryModel.Offer check(PurpleSyncFileContext context,PurpleSyncInventory inventory,
            PurpleSyncSettingsFile.Contents local) {
        if(context==null||!context.current())return null;
        try {
            int account=context.origin.slot;
            PurpleAccountBinding.Binding binding=PurpleAccountBinding.readForCheck(account);
            if(!binding.isValid()||!binding.current())return null;
            PurpleSyncRecoveryModel.Preferences preferences=capturePreferences(account,binding,context::current);
            PurpleSyncRecoveryModel model=new PurpleSyncRecoveryModel(PurpleAccountFiles.root(),context.origin.key,
                    PurpleAccountOwner.owner(),PurpleAccountOwner.global(),preferences,context::current,
                    PurpleGlobalAccountChoice.PHYSICAL,PurpleEditorDirectories.NATIVE,PurpleSyncRecoveryFiles.NATIVE_MOVES);
            return context.current()?model.check(inventory,local):null;
        } catch(Exception|LinkageError e){return null;}
    }
    static PurpleSyncRecoveryModel.Preferences capturePreferences(int account,PurpleAccountBinding.Binding binding,
            PurpleSyncRecoveryModel.Current current) throws Exception {
        PreferenceRead read=preferences(account,binding);
        String observed=read.observed.toString();
        PurpleAccountBinding.Binding[] expected={binding};
        return new PurpleSyncRecoveryModel.Preferences(binding.token,read.others,read.retained,read.active,read.complete,
                ()->{try{return current.current()&&expected[0].current()&&observed.equals(preferences(account,expected[0]).observed.toString());}catch(Exception e){return false;}},
                token->{
                if(!current.current()||!expected[0].current())return false;
                try { if(!observed.equals(preferences(account,expected[0]).observed.toString()))return false; }
                catch(Exception e){return false;}
                PurpleAccountBinding.Binding stored=PurpleAccountBinding.restore(expected[0],token);
                if(!stored.isValid()||!stored.current())return false;
                expected[0]=stored;return true;
                });
    }
    private PurpleSyncRecovery() { }
}
