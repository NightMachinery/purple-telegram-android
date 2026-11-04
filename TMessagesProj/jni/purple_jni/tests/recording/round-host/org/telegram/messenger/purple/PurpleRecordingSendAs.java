package org.telegram.messenger.purple; import org.telegram.messenger.*; public class PurpleRecordingSendAs {
 public static boolean sameUser(int a,long u){return u!=0&&UserConfig.active&&UserConfig.identity==u;}
 public static SendMessageChatArguments freeze(int a,long d,SendMessageChatArguments v){return freeze(a,UserConfig.identity,d,v);}
 public static SendMessageChatArguments freeze(int a,long u,long d,SendMessageChatArguments v){return sameUser(a,u)?new SendMessageChatArguments():null;}
}