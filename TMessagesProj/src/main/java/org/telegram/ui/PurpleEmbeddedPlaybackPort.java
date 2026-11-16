package org.telegram.ui;

public interface PurpleEmbeddedPlaybackPort {
    enum Classification { FINITE, LIVE, UNKNOWN }
    enum Evidence { NONE, OBSERVED_IMMUTABLE_BLOB, YOUTUBE_PLAYER_RESPONSE }
    enum Provider { HTML_MEDIA, YOUTUBE_WRAPPER }
    enum Capability { FEATURES_UNAVAILABLE, SELECTION_UNRESOLVED, METADATA_PENDING, LIVE, READY, RETIRED }

    final class Handle {
        public final long load, media, incarnation;
        public final String frame, providerItem;
        public final Provider provider;
        public Handle(long load, String frame, long media, long incarnation, Provider provider, String providerItem) {
            this.load = load;
            this.frame = frame;
            this.media = media;
            this.incarnation = incarnation;
            this.provider = provider;
            this.providerItem = providerItem;
        }
        public boolean same(Handle other) {
            return other != null && load == other.load && frame.equals(other.frame) && media == other.media
                    && incarnation == other.incarnation && provider == other.provider && providerItem.equals(other.providerItem);
        }
    }
    final class State {
        public final Handle handle;
        public final Classification classification;
        public final Evidence evidence;
        public final boolean loaded, paused, ended, looping, blocked, endFence;
        public final long authorization;
        public final int transport;
        public State(Handle handle, Classification classification, Evidence evidence, boolean loaded,
                boolean paused, boolean ended, boolean looping, boolean blocked, boolean endFence, long authorization, int transport) {
            this.handle = handle;
            this.classification = classification;
            this.evidence = evidence;
            this.loaded = loaded;
            this.paused = paused;
            this.ended = ended;
            this.looping = looping;
            this.blocked = blocked;
            this.endFence = endFence;
            this.authorization = authorization;
            this.transport = transport;
        }
        public boolean canEnd() {
            final boolean proven = handle.provider == Provider.HTML_MEDIA && evidence == Evidence.OBSERVED_IMMUTABLE_BLOB
                    || handle.provider == Provider.YOUTUBE_WRAPPER && evidence == Evidence.YOUTUBE_PLAYER_RESPONSE;
            return loaded && !ended && !looping && classification == Classification.FINITE && proven;
        }
    }
    interface Reply { void accept(State state); }
    interface Observer {
        default void identity(String item, boolean retired) {}
        void changed();
        void ended(State state);
        void metadata(long load, String providerItem, Classification classification, Evidence evidence);
    }
    Capability capability();
    Handle selected();
    java.util.List<State> candidates();
    void queryState(Handle handle, long authorization, Reply reply);
    void installEndFence(Handle handle, long authorization, Reply reply);
    void setExpiryFence(Handle handle, long authorization, boolean blocked, boolean endFence);
    void pauseLocal(Handle handle, long authorization);
    void explicitPlay(Handle handle, long authorization);
    void retire();
}
