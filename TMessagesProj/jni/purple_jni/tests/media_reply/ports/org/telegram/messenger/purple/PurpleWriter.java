package org.telegram.messenger.purple;
public final class PurpleWriter {
 public interface Current {boolean current();}
 public static int writes;
 public static Object last;
 public static final class Session {
  final Current view;Current guard=()->true;
  public Session(Current view){this.view=view;}
  public void guard(Current value){guard=value;}
  public boolean current(){return view.current()&&guard.current();}
 }
 public static String setMediaReplyValue(Session session,String row,String key,Object value){if(!session.current())return "stale";++writes;last=value;return null;}
}
