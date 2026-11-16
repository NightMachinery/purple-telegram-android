package org.telegram.ui;

import org.telegram.ui.PurpleEmbeddedPlaybackPort.*;

public final class PurpleEmbeddedPlaybackPortCases {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("case " + checks);
    }
    private static Handle handle(long load, String frame, long media, long incarnation, Provider provider, String item) {
        return new Handle(load, frame, media, incarnation, provider, item);
    }
    private static State state(Handle handle, Classification classification, Evidence evidence,
            boolean loaded, boolean ended, boolean looping) {
        return new State(handle, classification, evidence, loaded, true, ended, looping, false, true, 7, 2);
    }
    public static void main(String[] args) {
        Handle item = handle(1, "frame-one", 1, 1, Provider.HTML_MEDIA, "");
        check(item.same(handle(1, "frame-one", 1, 1, Provider.HTML_MEDIA, "")));
        check(!item.same(handle(2, "frame-one", 1, 1, Provider.HTML_MEDIA, "")));
        check(!item.same(handle(1, "frame-two", 1, 1, Provider.HTML_MEDIA, "")));
        check(!item.same(handle(1, "frame-one", 2, 1, Provider.HTML_MEDIA, "")));
        check(!item.same(handle(1, "frame-one", 1, 2, Provider.HTML_MEDIA, "")));
        check(!item.same(handle(1, "frame-one", 1, 1, Provider.YOUTUBE_WRAPPER, "")));
        check(!item.same(handle(1, "frame-one", 1, 1, Provider.HTML_MEDIA, "other")));
        check(!item.same(null));
        check(state(item, Classification.FINITE, Evidence.OBSERVED_IMMUTABLE_BLOB, true, false, false).canEnd());
        check(!state(item, Classification.FINITE, Evidence.NONE, true, false, false).canEnd());
        check(!state(item, Classification.UNKNOWN, Evidence.NONE, true, false, false).canEnd());
        check(!state(item, Classification.LIVE, Evidence.YOUTUBE_PLAYER_RESPONSE, true, false, false).canEnd());
        check(!state(item, Classification.FINITE, Evidence.OBSERVED_IMMUTABLE_BLOB, false, false, false).canEnd());
        check(!state(item, Classification.FINITE, Evidence.OBSERVED_IMMUTABLE_BLOB, true, true, false).canEnd());
        check(!state(item, Classification.FINITE, Evidence.OBSERVED_IMMUTABLE_BLOB, true, false, true).canEnd());
        check(!state(item, Classification.FINITE, Evidence.YOUTUBE_PLAYER_RESPONSE, true, false, false).canEnd());
        Handle youtube = handle(1, "frame-one", 0, 1, Provider.YOUTUBE_WRAPPER, "selected-video");
        check(state(youtube, Classification.FINITE, Evidence.YOUTUBE_PLAYER_RESPONSE, true, false, false).canEnd());
        check(!state(youtube, Classification.FINITE, Evidence.OBSERVED_IMMUTABLE_BLOB, true, false, false).canEnd());
        System.out.println("EMBEDDED_PORT_CHECKS=" + checks);
    }
}
