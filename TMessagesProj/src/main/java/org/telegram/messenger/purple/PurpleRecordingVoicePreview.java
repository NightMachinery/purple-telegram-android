package org.telegram.messenger.purple;

import java.util.WeakHashMap;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;

public final class PurpleRecordingVoicePreview {
    private static final WeakHashMap<TLRPC.TL_document, Context> contexts = new WeakHashMap<>();
    private static final class Context {
        int account;
        long user, dialog, mono;
        MessageObject reply, top;
        TL_stories.StoryItem story;
        SendMessageChatArguments sender;
        MessageSuggestionParams suggestion;
    }
    public static void remember(int account, TLRPC.TL_document document, long dialog,
            MessageObject reply, MessageObject top, TL_stories.StoryItem story,
            SendMessageChatArguments sender, long mono, MessageSuggestionParams suggestion) {
        Context context = new Context();
        context.account = account; context.user = UserConfig.getInstance(account).getClientUserId();
        context.dialog = dialog; context.reply = reply; context.top = top; context.story = story;
        context.sender = sender; context.mono = mono; context.suggestion = suggestion;
        contexts.put(document, context);
    }
    public static boolean apply(int account, SendMessagesHelper.SendMessageParams params) {
        Context context = contexts.get(params.document);
        if (context == null) return true;
        if (context.account != account || context.dialog != params.peer
                || context.user != UserConfig.getInstance(account).getClientUserId()) return false;
        params.replyToMsg = context.reply; params.replyToTopMsg = context.top;
        params.replyToStoryItem = context.story; params.sendMessageChatArguments = context.sender;
        params.monoForumPeer = context.mono; params.suggestionParams = context.suggestion;
        return true;
    }
    private PurpleRecordingVoicePreview() { }
}
