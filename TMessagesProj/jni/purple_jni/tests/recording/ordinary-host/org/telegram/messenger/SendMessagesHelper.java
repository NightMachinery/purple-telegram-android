package org.telegram.messenger;import org.telegram.tgnet.TLRPC;public class SendMessagesHelper{
public static int sends;public static long sentUser,documentUser;
public static SendMessagesHelper getInstance(int a){return new SendMessagesHelper();}
public void sendMessage(SendMessageParams p){if(p.sendMessageChatArguments==null||p.sendMessageChatArguments.user!=UserConfig.identity)throw new AssertionError("frozen user mismatch");sends++;sentUser=UserConfig.identity;documentUser=p.document.user_id;}
public static void prepareSendingVideo(Object... a){SendMessageChatArguments p=(SendMessageChatArguments)a[19];if(p==null||p.user!=UserConfig.identity)throw new AssertionError("frozen round user mismatch");sends++;sentUser=UserConfig.identity;}
public static class SendMessageParams{public TLRPC.Document document;public SendMessageChatArguments sendMessageChatArguments;public static SendMessageParams of(Object... args){SendMessageParams p=new SendMessageParams();p.document=(TLRPC.Document)args[0];return p;}}
}
