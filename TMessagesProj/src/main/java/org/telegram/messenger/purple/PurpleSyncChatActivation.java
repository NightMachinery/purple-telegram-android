package org.telegram.messenger.purple;

import java.util.Objects;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

final class PurpleSyncChatActivation {
    final PurpleSyncChatOrigin origin;
    final Cache mark;
    final PurpleSyncChatScreen.Candidate candidate;
    final byte[] journal;
    final String authority;

    PurpleSyncChatActivation(PurpleSyncChatOrigin origin,Cache mark,PurpleSyncChatScreen.Candidate candidate,byte[] journal,String authority) {
        if(origin==null||mark==null||candidate==null||!candidate.allowed||!mark.chat.equals(candidate.chat)
                ||!origin.equals(candidate.info.origin)||!complete(journal,origin,authority,mark))throw new IllegalArgumentException("SetupPostingHeld");
        this.origin=origin;this.mark=mark;this.candidate=candidate;this.journal=journal!=null?journal.clone():null;this.authority=authority;
    }
    static boolean complete(byte[] journal,PurpleSyncChatOrigin origin,String authority,Cache mark) {
        if(journal==null)return true;
        try {
            PurpleSyncChatSetup.Journal value=PurpleSyncChatSetup.Journal.parse(journal,origin,PurpleSyncChatSetup.authority(authority));
            return value.step==PurpleSyncChatSetup.Step.Complete&&Objects.equals(mark,value.mark)&&!value.deferred&&value.retryAt==0;
        }catch(Exception|LinkageError error){return false;}
    }
    boolean accepts(PurpleSyncInventory inventory) {
        return inventory!=null&&inventory.accountUserId==origin.userId&&origin.equals(inventory.origin)&&mark.chat.equals(inventory.chat)&&inventory.scanComplete;
    }
    boolean renew(PurpleSyncChatScreen.Frame frame,boolean issued) {
        if(frame==null||frame.current==null||!frame.current.allowed||!frame.status.complete
                ||frame.status.phase!=PurpleSyncChat.Phase.Use||frame.status.resolution.action!=Action.Use
                ||!mark.chat.equals(frame.status.resolution.readChat)||frame.opening==null||!frame.opening.ok
                ||frame.opening.data==null||frame.opening.data.pendingMove()||!mark.equals(frame.opening.data.location)
                ||!origin.equals(frame.current.info.origin))return false;
        PurpleSyncChatCache.ReadersSnapshot before=PurpleSyncChatCache.ReadersSnapshot.from(candidate.info);
        PurpleSyncChatCache.ReadersSnapshot now=PurpleSyncChatCache.ReadersSnapshot.from(frame.current.info);
        if(before==null||now==null||before.kind!=now.kind||before.creator&&!now.creator||before.admin&&!now.admin)return false;
        return issued||candidate.same(frame.current);
    }
}
