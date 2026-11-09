package org.telegram.tgnet;
public class TLRPC {
 public static class Peer {public long channel_id,chat_id,user_id;}
 public static class MessageReplyHeader {public int reply_to_top_id,reply_to_msg_id;public boolean forum_topic;}
 public static class Message {public int id,date,send_state,mediaKind,quick_reply_shortcut_id;public String quick_reply_shortcut;public boolean out,from_scheduled;public long dialog_id;public Peer peer_id,from_id;public MessageReplyHeader reply_to;public MessageAction action;}
 public static class MessageAction {}
 public static class TL_messageActionEmpty extends MessageAction {}
 public static class Chat {public boolean allowed=true,channel,megagroup;}
 public static class InputPeer {public long id;}
 public static class InputReplyTo {public int flags,reply_to_msg_id,top_msg_id;}
 public static class TL_inputReplyToMessage extends InputReplyTo {}
 public static class TL_messages_sendMessage extends TLObject {public InputPeer peer;public String message;public long random_id;public InputReplyTo reply_to;public int flags;public boolean no_webpage;public long allow_paid_stars;public boolean allow_paid_floodskip;}
 public static class Updates extends TLObject {}
 public static class TL_error extends TLObject {}
}
