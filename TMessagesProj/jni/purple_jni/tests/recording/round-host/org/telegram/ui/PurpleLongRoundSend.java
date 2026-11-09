package org.telegram.ui;
import org.telegram.messenger.*;import org.telegram.messenger.purple.*;
public class PurpleLongRoundSend {
 public static long selectedStart,selectedEnd;public static int reviews;public static Sender sender;public static Runnable done;public static SendMessageChatArguments arguments;
 public interface Sender{void send(PurpleLongRoundMedia.Output output,SendMessageChatArguments arguments,long paid);}
 public static void review(ChatActivity host,PurpleRecordingEntry item,int account,long user,long dialog,SendMessageChatArguments args,Sender send,Runnable finished,long startUs,long endUs){reviews++;selectedStart=startUs;selectedEnd=endUs;sender=send;done=finished;arguments=args;}
}
