package org.telegram.messenger;
import java.util.*; import org.telegram.SQLite.SQLiteCursor;
public class MessagesStorage {
 public static boolean stored=true, immediate=true; public static List<Runnable> jobs=new ArrayList<>(); public static int queries;
 public static MessagesStorage getInstance(int a){return new MessagesStorage();}
 public MessagesStorage getStorageQueue(){return this;} public MessagesStorage getDatabase(){return this;}
 public void postRunnable(Runnable r){if(immediate)r.run();else jobs.add(r);}
 public SQLiteCursor queryFinalized(String s,Object... args){queries++;if(!s.startsWith("SELECT mid FROM ")||args.length!=2)throw new AssertionError();return new SQLiteCursor();}
 public static void flush(){List<Runnable> run=new ArrayList<>(jobs);jobs.clear();for(Runnable r:run)r.run();}
}
