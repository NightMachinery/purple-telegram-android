package org.telegram.messenger.purple;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.telegram.tgnet.TLRPC;
public final class PurpleArchiveMarkdown {
 public static String source;
 public static boolean queued;
 public static Runnable completion;
 public static long epoch;
 public static final class Guard {
  final PurpleAccountFiles.Identity identity;
  final long opening = epoch;
  Guard(PurpleAccountFiles.Identity value){identity=value;}
  public boolean current(){return identity.current() && opening==epoch;}
  public boolean publish(BooleanSupplier active,Runnable action){if(!current() || !active.getAsBoolean())return false;action.run();return true;}
 }
 public static Guard guard(PurpleAccountFiles.Identity identity){return identity==null?null:new Guard(identity);}
 public static void current(Guard guard,TLRPC.Message message,Consumer<String> delivered,Runnable refused){
  if(queued)completion=()->delivered.accept(source);else delivered.accept(source);
 }
}
