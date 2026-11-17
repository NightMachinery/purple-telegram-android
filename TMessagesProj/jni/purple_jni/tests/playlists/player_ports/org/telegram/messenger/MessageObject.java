package org.telegram.messenger;
import org.telegram.tgnet.TLRPC;
public final class MessageObject {
 public int currentAccount; public final TLRPC.Message messageOwner;
 public long eventId; public boolean scheduled, voice, round, video; public int audioProgressSec;
 public MessageObject(int account, TLRPC.Message message, boolean users, boolean layout) { currentAccount = account; messageOwner = message; }
 public long getDialogId() { TLRPC.Peer p = messageOwner.peer_id; return p.channel_id != 0 ? -p.channel_id : p.chat_id != 0 ? -p.chat_id : p.user_id; }
 public int getId() { return messageOwner.id; }
 public boolean isMusic() { return !voice && !round && !video && getDocument() != null; }
 public boolean isVoice() { return voice; } public boolean isRoundVideo() { return round; } public boolean isVideo() { return video; }
 public TLRPC.Document getDocument() { return messageOwner.media == null ? null : messageOwner.media.document; }
 public void resetPlayingProgress() { audioProgressSec = 0; }
}
