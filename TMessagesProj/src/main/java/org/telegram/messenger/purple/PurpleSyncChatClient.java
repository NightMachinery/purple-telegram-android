package org.telegram.messenger.purple;

import java.util.Set;
import static org.telegram.messenger.purple.PurpleSyncChatCore.*;

interface PurpleSyncChatClient {
    enum Error { None, Unknown, ChannelPrivate }
    final class Info {
        final PurpleSyncChatOrigin origin;
        final Peer chat;
        final PurpleSyncChatFacts.MappedPeer peer;
        final Error error;
        final String title;
        final Set<Long> members;
        final boolean readersKnown;
        final boolean hiddenPrehistory;
        Info(PurpleSyncChatOrigin origin, Peer chat, PurpleSyncChatFacts.MappedPeer peer, Error error, String title, Set<Long> members, boolean readersKnown) {
            this(origin,chat,peer,error,title,members,readersKnown,false);
        }
        Info(PurpleSyncChatOrigin origin, Peer chat, PurpleSyncChatFacts.MappedPeer peer, Error error, String title, Set<Long> members, boolean readersKnown, boolean hiddenPrehistory) {
            this.origin = origin; this.chat = chat; this.peer = peer; this.error = error; this.title = title;
            this.members = members != null ? java.util.Collections.unmodifiableSet(new java.util.HashSet<>(members)) : null; this.readersKnown = readersKnown;
            this.hiddenPrehistory = hiddenPrehistory;
        }
        boolean readable() { return error == Error.None && peer != null && peer.facts != null; }
        Eligibility eligibility() { return peer != null ? peer.eligibility() : unknownEligibility(); }
    }
}
