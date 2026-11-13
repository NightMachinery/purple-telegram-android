package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public class MessageObject {
 public TLRPC.Message messageOwner;
 public MessageObject(){}
 public MessageObject(TLRPC.Message messageOwner){this.messageOwner=messageOwner;}
 public static long getDialogId(TLRPC.Message m){return m.dialog_id!=0?m.dialog_id:m.peer_id.channel_id!=0?-m.peer_id.channel_id:m.peer_id.chat_id!=0?-m.peer_id.chat_id:m.from_id==null||m.out?m.peer_id.user_id:m.from_id.user_id;}
 private static TLRPC.MessageMedia getMedia(TLRPC.Message m){
  if(m==null)return null;
  if(m.media!=null&&!m.media.extended_media.isEmpty()&&m.media.extended_media.get(0) instanceof TLRPC.TL_messageExtendedMedia){
   return ((TLRPC.TL_messageExtendedMedia)m.media.extended_media.get(0)).media;
  }
  return m.media;
 }
 private static boolean isVoiceDocument(TLRPC.Document doc){
  if(doc==null)return false;
  for(int i=0;i<doc.attributes.size();++i){
   TLRPC.DocumentAttribute a=doc.attributes.get(i);
   if(a instanceof TLRPC.TL_documentAttributeAudio)return a.voice;
  }
  return false;
 }
 private static boolean isRoundVideoDocument(TLRPC.Document doc){
  if(doc!=null&&"video/mp4".equals(doc.mime_type)){
   int w=0,h=0;boolean round=false;
   for(int i=0;i<doc.attributes.size();++i){
    TLRPC.DocumentAttribute a=doc.attributes.get(i);
    if(a instanceof TLRPC.TL_documentAttributeVideo){w=a.w;h=a.h;round=a.round_message;}
   }
   return round&&w<=1280&&h<=1280;
  }
  return false;
 }
 public static boolean isVoiceMessage(TLRPC.Message m){
  if(m==null)return false;
  if(m.mediaKind==1)return true;
  TLRPC.MessageMedia media=getMedia(m);
  if(media instanceof TLRPC.TL_messageMediaWebPage&&media.webpage!=null)return isVoiceDocument(media.webpage.document);
  return media!=null&&isVoiceDocument(media.document);
 }
 public static boolean isRoundVideoMessage(TLRPC.Message m){
  if(m==null)return false;
  if(m.mediaKind==2)return true;
  TLRPC.MessageMedia media=getMedia(m);
  if(media instanceof TLRPC.TL_messageMediaWebPage&&media.webpage!=null)return isRoundVideoDocument(media.webpage.document);
  return media!=null&&isRoundVideoDocument(media.document);
 }
}
