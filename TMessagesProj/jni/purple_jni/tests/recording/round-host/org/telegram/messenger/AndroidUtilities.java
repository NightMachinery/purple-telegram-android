package org.telegram.messenger;
import java.util.*;
public class AndroidUtilities {
 public static boolean defer; public static List<Runnable> ui=new ArrayList<>(),later=new ArrayList<>();
 public static void runOnUIThread(Runnable r){if(defer)ui.add(r);else r.run();}
 public static void runOnUIThread(Runnable r,long delay){later.add(r);}
 public static void flush(){List<Runnable> run=new ArrayList<>(ui);ui.clear();for(Runnable r:run)r.run();}
 public static void tick(){List<Runnable> run=new ArrayList<>(later);later.clear();for(Runnable r:run)r.run();}
}
