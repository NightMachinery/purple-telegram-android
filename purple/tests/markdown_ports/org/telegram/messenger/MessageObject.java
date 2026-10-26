package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
public class MessageObject {
 public static final int TYPE_TEXT=0,TYPE_ARTICLE=36;
 public int currentAccount,type;
 public boolean deleted,deletedByThanos,translated;
 public CharSequence caption,display="";
 public TLRPC.Message messageOwner;
 public TL_iv.RichMessage translatedDisplay;
 public TL_iv.RichMessage getDisplayRichMessage(){return translatedDisplay==null?messageOwner.rich_message:translatedDisplay;}
 public long getDialogId(){return messageOwner.dialog_id;}
 public int getId(){return messageOwner.id;}
 public static long getDialogId(TLRPC.Message owner){return owner.dialog_id;}
 public boolean isAnimatedEmoji(){return false;}
 public boolean isAnimatedEmojiStickers(){return false;}
 public boolean isDice(){return false;}
 public String getDiceEmoji(){return "";}
 public static class GroupedMessages {public long groupId;public java.util.ArrayList<MessageObject> messages=new java.util.ArrayList<>();}
}
