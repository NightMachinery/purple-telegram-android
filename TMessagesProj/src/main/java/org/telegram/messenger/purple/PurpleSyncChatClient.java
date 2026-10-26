package org.telegram.messenger.purple;

import org.telegram.tgnet.TLRPC;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
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
    final class Exact {
        final DirectRead state;
        final TLRPC.Message message;
        final Info info;
        Exact(DirectRead state, TLRPC.Message message, Info info) { this.state = state; this.message = message; this.info = info; }
    }
    PurpleSyncChatOrigin origin();
    boolean current();
    void dispatch(Runnable task);
    void search(PurpleSyncChatRequests.Search kind, PurpleSyncChatRequests.Cursor cursor, Peer target, Consumer<PurpleSyncChatRequests.Page> done);
    void info(Peer target, Consumer<Info> done);
    void exact(Cache location, Consumer<Exact> done);
    void hint(Consumer<SettingsHint> done);
    List<Peer> fallback();
    void cancel();
}
