package org.telegram.ui;import android.content.SharedPreferences;import java.util.*;
    public class PeekPreferences implements SharedPreferences {
        public final Map<String,Object> values=new HashMap<>();public boolean fail;public int commits;
        public synchronized Map<String,?> getAll(){return new HashMap<>(values);}
        public synchronized String getString(String key,String fallback){return values.containsKey(key)?(String)values.get(key):fallback;}
        @SuppressWarnings("unchecked") public synchronized Set<String> getStringSet(String key,Set<String> fallback){return values.containsKey(key)?new HashSet<>((Set<String>)values.get(key)):fallback;}
        public synchronized int getInt(String key,int fallback){return values.containsKey(key)?(Integer)values.get(key):fallback;}
        public synchronized long getLong(String key,long fallback){return values.containsKey(key)?(Long)values.get(key):fallback;}
        public synchronized float getFloat(String key,float fallback){return values.containsKey(key)?(Float)values.get(key):fallback;}
        public synchronized boolean getBoolean(String key,boolean fallback){return values.containsKey(key)?(Boolean)values.get(key):fallback;}
        public synchronized boolean contains(String key){return values.containsKey(key);}
        public Editor edit(){return new Edit(this);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){}
    private static final class Edit implements SharedPreferences.Editor {
        final PeekPreferences owner;final Map<String,Object> changes=new HashMap<>();boolean clear;
        Edit(PeekPreferences owner){this.owner=owner;}
        public SharedPreferences.Editor putString(String key,String value){changes.put(key,value);return this;}
        public SharedPreferences.Editor putStringSet(String key,Set<String> value){changes.put(key,value!=null?new HashSet<>(value):null);return this;}
        public SharedPreferences.Editor putInt(String key,int value){changes.put(key,value);return this;}
        public SharedPreferences.Editor putLong(String key,long value){changes.put(key,value);return this;}
        public SharedPreferences.Editor putFloat(String key,float value){changes.put(key,value);return this;}
        public SharedPreferences.Editor putBoolean(String key,boolean value){changes.put(key,value);return this;}
        public SharedPreferences.Editor remove(String key){changes.put(key,null);return this;}
        public SharedPreferences.Editor clear(){clear=true;return this;}
        public boolean commit(){++owner.commits;synchronized(owner){if(clear)owner.values.clear();for(Map.Entry<String,Object> entry:changes.entrySet())if(entry.getValue()==null)owner.values.remove(entry.getKey());else owner.values.put(entry.getKey(),entry.getValue());}return !owner.fail;}
        public void apply(){commit();}
    }
}
