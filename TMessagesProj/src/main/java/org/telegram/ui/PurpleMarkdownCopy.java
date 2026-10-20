package org.telegram.ui;

import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.graphics.Typeface;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.CodeHighlighting;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.purple.PurpleMarkdown;
import org.telegram.messenger.purple.PurpleMarkdownLinks;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.QuoteSpan;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.URLSpanMono;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.ui.Components.URLSpanBrowser;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.URLSpanUserMention;
import org.telegram.ui.Components.URLSpanCopyToClipboard;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.telegram.messenger.purple.PurpleRichMessageMarkdown;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;

final class PurpleMarkdownCopy {
    static final int OPTION = 0x70750005;

    interface Lookup {
        MessageObject find(ChatActivity chat, long dialogId, int messageId);
    }

    private final WeakReference<ChatActivity> origin;
    private final Lookup lookup;
    private final ArrayList<Snapshot> snapshots = new ArrayList<>();
    private final ArrayList<String> results = new ArrayList<>();
    private int requestId;
    private ConnectionsManager requestManager;
    private int generation;
    private int currentAccount;
    private long clientUserId;
    private long dialogId;
    private int next;
    private boolean destroyed;

    PurpleMarkdownCopy(ChatActivity chat, Lookup lookup) {
        origin = new WeakReference<>(chat);
        this.lookup = lookup;
    }

    private static final class Snapshot {
        final MessageObject message;
        final TLRPC.Message owner;
        final TL_iv.RichMessage article;
        final long dialogId;
        final int messageId;
        final int editDate;
        final boolean translated;
        final String language;
        final TL_iv.RichMessage original;
        final byte[] originalBytes;
        final byte[] richBytes;
        final String ordinary;

        Snapshot(MessageObject message) {
            this.message = message;
            owner = message.messageOwner;
            article = message.getDisplayRichMessage();
            dialogId = message.getDialogId();
            messageId = message.getId();
            editDate = owner.edit_date;
            translated = message.translated;
            language = owner.translatedToLanguage;
            original = owner.rich_message;
            originalBytes = original == null ? null : serialize(original);
            richBytes = article == null ? null : serialize(article);
            ordinary = article == null ? ordinary(message) : null;
        }

        boolean unchanged(MessageObject current, int account) {
            return current == message && !current.deleted && !current.deletedByThanos
                    && current.currentAccount == account && current.messageOwner == owner
                    && current.getDialogId() == dialogId && current.getId() == messageId
                    && owner.edit_date == editDate && current.translated == translated
                    && java.util.Objects.equals(owner.translatedToLanguage, language)
                    && owner.rich_message == original
                    && (original == null || Arrays.equals(originalBytes, serialize(original)))
                    && current.getDisplayRichMessage() == article
                    && (article == null ? ordinary.equals(ordinary(current)) : Arrays.equals(richBytes, serialize(article)));
        }
    }

    private static byte[] serialize(TL_iv.RichMessage article) {
        SerializedData data = new SerializedData();
        try {
            article.serializeToStream(data);
            return data.toByteArray();
        } finally {
            data.cleanup();
        }
    }

    void copy(MessageObject message, MessageObject.GroupedMessages group) {
        cancel();
        ChatActivity chat = origin.get();
        if (destroyed || chat == null || chat.isFinished || message == null) return;
        currentAccount = chat.getCurrentAccount();
        clientUserId = chat.getUserConfig().getClientUserId();
        dialogId = chat.getDialogId();
        List<MessageObject> messages = group == null ? java.util.Collections.singletonList(message) : group.messages;
        boolean hasArticle = message.type == MessageObject.TYPE_ARTICLE || message.getDisplayRichMessage() != null;
        for (MessageObject item : messages) {
            if (item != null && (item.type == MessageObject.TYPE_ARTICLE || item.getDisplayRichMessage() != null)) hasArticle = true;
        }
        if (!hasArticle) {
            String markdown = ordinary(message, group);
            if (!TextUtils.isEmpty(markdown)) {
                AndroidUtilities.addToClipboard(markdown);
                BulletinFactory.of(chat).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            }
            return;
        }
        if (!messages.contains(message)) {
            fail();
            return;
        }
        try {
            for (MessageObject item : messages) {
                if (item == null || item.messageOwner == null || item.currentAccount != currentAccount) {
                    fail();
                    return;
                }
                if (item.type == MessageObject.TYPE_ARTICLE && item.getDisplayRichMessage() == null) {
                    fail();
                    return;
                }
                snapshots.add(new Snapshot(item));
            }
            step();
        } catch (RuntimeException e) {
            fail();
        }
    }

    void copySelection(List<MessageObject> messages) {
        cancel();
        ChatActivity chat = origin.get();
        if (destroyed || chat == null || chat.isFinished || messages.isEmpty()) return;
        currentAccount = chat.getCurrentAccount();
        clientUserId = chat.getUserConfig().getClientUserId();
        dialogId = chat.getDialogId();
        try {
            for (MessageObject message : messages) {
                if (message == null || message.messageOwner == null || message.currentAccount != currentAccount
                        || (message.type == MessageObject.TYPE_ARTICLE && message.getDisplayRichMessage() == null)) {
                    fail();
                    return;
                }
                snapshots.add(new Snapshot(message));
            }
            step();
        } catch (RuntimeException e) {
            fail();
        }
    }

    private ChatActivity validOrigin() {
        ChatActivity chat = origin.get();
        if (destroyed || chat == null || chat.isFinished || chat.isPaused() || chat.getParentActivity() == null
                || chat.getCurrentAccount() != currentAccount || chat.getDialogId() != dialogId
                || !chat.getUserConfig().isClientActivated() || chat.getUserConfig().getClientUserId() != clientUserId) return null;
        for (Snapshot snapshot : snapshots) {
            if (!snapshot.unchanged(lookup.find(chat, snapshot.dialogId, snapshot.messageId), currentAccount)) return null;
        }
        return chat;
    }

    private void step() {
        ChatActivity chat = validOrigin();
        if (chat == null) {
            cancel();
            return;
        }
        while (next < snapshots.size()) {
            Snapshot snapshot = snapshots.get(next);
            if (snapshot.article == null) {
                results.add(snapshot.ordinary);
                next++;
            } else if (!snapshot.article.part) {
                results.add(PurpleRichMessageMarkdown.render(snapshot.article));
                next++;
            } else {
                if (snapshot.messageId <= 0) {
                    fail();
                    return;
                }
                if (snapshot.article != snapshot.owner.rich_message) {
                    requestTranslation(chat, snapshot);
                    return;
                }
                TL_iv.getRichMessage request = new TL_iv.getRichMessage();
                request.peer = chat.getMessagesController().getInputPeer(snapshot.dialogId);
                request.id = snapshot.messageId;
                if (request.peer == null) {
                    fail();
                    return;
                }
                WeakReference<PurpleMarkdownCopy> weak = new WeakReference<>(this);
                int expectedGeneration = generation;
                int expectedIndex = next;
                requestManager = chat.getConnectionsManager();
                requestId = requestManager.sendRequestTyped(request, AndroidUtilities::runOnUIThread, (response, error) -> {
                    PurpleMarkdownCopy action = weak.get();
                    if (action != null && action.generation == expectedGeneration && action.next == expectedIndex) {
                        action.received(response, error);
                    }
                });
                requestManager.bindRequestToGuid(requestId, chat.getClassGuid());
                return;
            }
        }
        if (validOrigin() == null) {
            cancel();
            return;
        }
        StringBuilder markdown = new StringBuilder();
        for (String result : results) {
            if (TextUtils.isEmpty(result)) continue;
            if (markdown.length() > 0) markdown.append("\n\n");
            markdown.append(result);
        }
        if (markdown.length() > 0) {
            AndroidUtilities.addToClipboard(markdown.toString());
            BulletinFactory.of(chat).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
        cancel();
    }

    private void requestTranslation(ChatActivity chat, Snapshot snapshot) {
        if (TextUtils.isEmpty(snapshot.language)) {
            fail();
            return;
        }
        TLRPC.TL_messages_translateRichMessage request = new TLRPC.TL_messages_translateRichMessage();
        request.flags |= 1;
        request.peer = chat.getMessagesController().getInputPeer(snapshot.dialogId);
        request.id.add(snapshot.messageId);
        request.to_lang = TranslateController.normalizeLanguage(snapshot.language);
        if (request.peer == null) {
            fail();
            return;
        }
        WeakReference<PurpleMarkdownCopy> weak = new WeakReference<>(this);
        int expectedGeneration = generation;
        int expectedIndex = next;
        requestManager = chat.getConnectionsManager();
        requestId = requestManager.sendRequestTyped(request, AndroidUtilities::runOnUIThread, (response, error) -> {
            PurpleMarkdownCopy action = weak.get();
            if (action != null && action.generation == expectedGeneration && action.next == expectedIndex) {
                action.receivedTranslation(response, error);
            }
        });
        requestManager.bindRequestToGuid(requestId, chat.getClassGuid());
    }

    private void receivedTranslation(TLRPC.TL_messages_translatedRichMessage response, TLRPC.TL_error error) {
        requestId = 0;
        try {
            if (validOrigin() == null) {
                cancel();
                return;
            }
            if (error != null || response == null || response.result.size() != 1
                    || response.result.get(0) == null || response.result.get(0).part) {
                fail();
                return;
            }
            results.add(PurpleRichMessageMarkdown.render(response.result.get(0)));
            next++;
            step();
        } catch (RuntimeException e) {
            fail();
        }
    }

    private void received(TLRPC.messages_Messages response, TLRPC.TL_error error) {
        requestId = 0;
        try {
            if (validOrigin() == null) {
                cancel();
                return;
            }
            if (error != null || response == null || next >= snapshots.size()) {
                fail();
                return;
            }
            Snapshot snapshot = snapshots.get(next);
            TL_iv.RichMessage full = null;
            for (TLRPC.Message message : response.messages) {
                if (message.id == snapshot.messageId && MessageObject.getDialogId(message) == snapshot.dialogId) {
                    full = message.rich_message;
                    break;
                }
            }
            if (full == null || full.part) {
                fail();
                return;
            }
            results.add(PurpleRichMessageMarkdown.render(full));
            next++;
            step();
        } catch (RuntimeException e) {
            fail();
        }
    }

    private void fail() {
        ChatActivity chat = origin.get();
        cancel();
        if (!destroyed && chat != null && !chat.isFinished && chat.getParentActivity() != null) {
            BulletinFactory.of(chat).createErrorBulletin(LocaleController.getString(R.string.UnknownError)).show();
        }
    }

    void cancel() {
        generation++;
        if (requestId != 0 && requestManager != null) {
            requestManager.cancelRequest(requestId, true);
        }
        requestId = 0;
        snapshots.clear();
        results.clear();
        next = 0;
    }

    void onDestroy() {
        destroyed = true;
        cancel();
    }

    static boolean canCopy(MessageObject message) {
        return message != null && (message.type == MessageObject.TYPE_TEXT
                || message.type == MessageObject.TYPE_ARTICLE || message.isAnimatedEmoji()
                || message.isAnimatedEmojiStickers() || message.isDice() || message.caption != null);
    }

    static void addMenuOptions(MessageObject message, MessageObject.GroupedMessages group,
            ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons, int copy) {
        int index = options.indexOf(copy);
        if (index < 0 && !canCopy(message) && ChatActivity.getMessageCaption(message, group, null) == null) return;
        if (index < 0) {
            index = options.size();
            options.add(copy);
            items.add(LocaleController.getString(R.string.Copy));
            icons.add(R.drawable.msg_copy);
        }
        if (options.contains(OPTION)) return;
        options.add(index + 1, OPTION);
        items.add(index + 1, LocaleController.getString(R.string.PurpleCopyAsMarkdown));
        icons.add(index + 1, R.drawable.msg_copy);
    }

    private static String ordinary(MessageObject message) {
        return ordinary(message, null);
    }

    private static String ordinary(MessageObject message, MessageObject.GroupedMessages group) {
        CharSequence text;
        if (message.isDice()) text = message.getDiceEmoji();
        else {
            text = ChatActivity.getMessageCaption(message, group, null);
            if (text == null) text = ChatActivity.getMessageContent(message, 0, false);
        }
        return formattedText(text);
    }

    static String formattedText(CharSequence text) {
        if (TextUtils.isEmpty(text)) return "";
        ArrayList<PurpleMarkdown.Span> spans = new ArrayList<>();
        if (text instanceof Spanned) {
            Spanned styled = (Spanned) text;
            for (Object span : styled.getSpans(0, styled.length(), Object.class)) {
                int start = styled.getSpanStart(span), end = styled.getSpanEnd(span);
                if (span instanceof TextStyleSpan) addStyle(spans, start, end, ((TextStyleSpan) span).getStyleFlags());
                if (span instanceof URLSpanReplacement) {
                    TextStyleSpan.TextStyleRun run = ((URLSpanReplacement) span).getTextStyleRun();
                    if (run != null) addStyle(spans, start, end, run.flags);
                }
                if (span instanceof URLSpanBrowser) {
                    TextStyleSpan.TextStyleRun run = ((URLSpanBrowser) span).getStyle();
                    if (run != null) addStyle(spans, start, end, run.flags);
                }
                if (span instanceof URLSpan) {
                    String target = linkTarget((URLSpan) span);
                    if (target != null) spans.add(new PurpleMarkdown.Span(start, end, PurpleMarkdown.LINK, target));
                }
                if (span instanceof URLSpanMono) spans.add(new PurpleMarkdown.Span(start, end, PurpleMarkdown.CODE, null));
                if (span instanceof CodeHighlighting.Span) spans.add(new PurpleMarkdown.Span(start, end, PurpleMarkdown.PRE, ((CodeHighlighting.Span) span).lng));
                if (span instanceof QuoteSpan) spans.add(new PurpleMarkdown.Span(start, end, PurpleMarkdown.QUOTE, null));
                if (span instanceof StyleSpan) {
                    int style = ((StyleSpan) span).getStyle();
                    if ((style & Typeface.BOLD) != 0) spans.add(new PurpleMarkdown.Span(start, end, PurpleMarkdown.BOLD, null));
                    if ((style & Typeface.ITALIC) != 0) spans.add(new PurpleMarkdown.Span(start, end, PurpleMarkdown.ITALIC, null));
                }
            }
        }
        return PurpleMarkdown.render(text.toString(), spans);
    }

    private static String linkTarget(URLSpan span) {
        if (span instanceof URLSpanCopyToClipboard) return null;
        if (span instanceof URLSpanUserMention) return PurpleMarkdownLinks.userMention(span.getURL());
        if (span instanceof URLSpanNoUnderline) return PurpleMarkdownLinks.noUnderline(span.getURL());
        return PurpleMarkdownLinks.absolute(span.getURL());
    }

    private static void addStyle(ArrayList<PurpleMarkdown.Span> spans, int start, int end, int flags) {
        int[] masks = {TextStyleSpan.FLAG_STYLE_BOLD, TextStyleSpan.FLAG_STYLE_ITALIC,
                TextStyleSpan.FLAG_STYLE_STRIKE, TextStyleSpan.FLAG_STYLE_UNDERLINE,
                TextStyleSpan.FLAG_STYLE_MONO | TextStyleSpan.FLAG_STYLE_CODE};
        int[] kinds = {PurpleMarkdown.BOLD, PurpleMarkdown.ITALIC, PurpleMarkdown.STRIKE,
                PurpleMarkdown.UNDERLINE, PurpleMarkdown.CODE};
        for (int i = 0; i < masks.length; i++) {
            if ((flags & masks[i]) != 0) spans.add(new PurpleMarkdown.Span(start, end, kinds[i], null));
        }
    }
}
