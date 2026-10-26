package org.telegram.ui;

import java.util.Arrays;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.purple.PurpleAccountFiles;
import org.telegram.messenger.purple.PurpleArchiveViewerModel;
import org.telegram.messenger.purple.PurpleArchiveMarkdown;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;

public final class MarkdownCopyRouteTest {
    private static int checks;

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static TL_iv.RichMessage rich(boolean part, String... blocks) {
        TL_iv.RichMessage result = new TL_iv.RichMessage();
        result.part = part;
        for (String value : blocks) {
            TL_iv.pageBlockParagraph paragraph = new TL_iv.pageBlockParagraph();
            TL_iv.textPlain text = new TL_iv.textPlain();
            text.text = value;
            paragraph.text = text;
            result.blocks.add(paragraph);
        }
        return result;
    }

    private static MessageObject message(int id, long dialog, boolean partial) {
        MessageObject result = new MessageObject();
        result.currentAccount = 0;
        result.type = 36;
        result.messageOwner = new TLRPC.TL_message();
        result.messageOwner.id = id;
        result.messageOwner.dialog_id = dialog;
        result.messageOwner.peer_id = new TLRPC.TL_peerUser();
        result.messageOwner.peer_id.user_id = dialog;
        result.messageOwner.date = 10;
        result.messageOwner.edit_date = 20;
        result.messageOwner.message = "body";
        result.messageOwner.rich_message = rich(partial, "preview");
        return result;
    }

    private static final class Fixture {
        final ChatActivity chat = new ChatActivity();
        final MessageObject message = message(8, 42, true);
        final ConnectionsManager connection = ConnectionsManager.getInstance(0);
        final PurpleMarkdownCopy action;

        Fixture() {
            UserConfig.selectedAccount = 0;
            UserConfig.getInstance(0).user = 7;
            UserConfig.getInstance(0).active = true;
            PurpleArchiveViewerModel.generation = 0;
            PurpleArchiveMarkdown.source = null; PurpleArchiveMarkdown.queued = false; PurpleArchiveMarkdown.completion = null;
            AndroidUtilities.clipboard.clear();
            AndroidUtilities.queued.clear();
            connection.requests.clear();
            chat.messages.put("42:8", message);
            action = new PurpleMarkdownCopy(chat, (origin, dialog, id) -> origin.messages.get(dialog + ":" + id));
        }

        void start() {
            action.copy(message, null);
            check(connection.requests.size() == 1 && AndroidUtilities.clipboard.isEmpty(), "partial copy waits for full body");
        }

        TLRPC.TL_messages_messages response() {
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            TLRPC.Message full = message(8, 42, false).messageOwner;
            full.rich_message = rich(false, "preview", "second");
            result.messages.add(full);
            return result;
        }

        void deliver(TLRPC.TL_messages_messages response) {
            connection.deliver(0, response);
            AndroidUtilities.flush();
        }

        void refused(String name) { check(AndroidUtilities.clipboard.isEmpty(), name); }
    }

    public static void main(String[] args) throws Exception {
        String origin = PurpleMarkdownCopy.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        check(origin.equals(args[0]), "actual separately compiled production UI helper");
        check(!PurpleAccountFiles.class.getProtectionDomain().getCodeSource().getLocation().equals(MarkdownCopyRouteTest.class.getProtectionDomain().getCodeSource().getLocation()), "real account identity implementation");
        Fixture f = new Fixture();
        f.start(); f.deliver(f.response());
        check(AndroidUtilities.clipboard.equals(Arrays.asList("preview\n\nsecond")), "full response copies every block");
        f = new Fixture(); f.message.messageOwner.rich_message = rich(false, "complete"); f.action.copy(f.message, null);
        check(AndroidUtilities.clipboard.equals(Arrays.asList("complete")), "already complete article");
        f = new Fixture(); f.message.type = 0; f.message.messageOwner.rich_message = null; f.message.display = "visible <display>";
        f.action.copy(f.message, null);
        check(AndroidUtilities.clipboard.equals(Arrays.asList("visible &lt;display&gt;")), "ordinary copy uses selected displayed body");
        f = new Fixture(); f.message.deleted = true; f.message.type = 0; f.message.messageOwner.rich_message = null; f.message.display = "deleted";
        f.action.copy(f.message, null); f.refused("ordinary action has no unchecked deleted fast path");
        f = new Fixture(); f.start(); f.message.messageOwner.edit_date++; f.deliver(f.response()); f.refused("opening message revision changed");
        f = new Fixture(); f.start(); TLRPC.TL_messages_messages changed = f.response(); changed.messages.get(0).edit_date++;
        f.deliver(changed); f.refused("server returned newer revision before local UI updated");
        f = new Fixture(); f.start(); TLRPC.TL_messages_messages wrong = f.response(); wrong.messages.get(0).dialog_id = 99;
        f.deliver(wrong); f.refused("same id in another dialog");
        f = new Fixture(); f.start(); TLRPC.TL_messages_messages duplicate = f.response(); duplicate.messages.add(duplicate.messages.get(0));
        f.deliver(duplicate); f.refused("ambiguous duplicated response");
        f = new Fixture(); f.start(); f.connection.deliver(0, f.response()); UserConfig.selectedAccount = 1;
        AndroidUtilities.flush(); f.refused("queued full page after external account selection");
        f = new Fixture(); f.start(); f.connection.deliver(0, f.response()); PurpleArchiveViewerModel.generation += 2;
        AndroidUtilities.flush(); f.refused("selection A to B to A revokes old delivery");
        f = new Fixture(); f.start(); java.lang.reflect.Field lifetime = PurpleAccountFiles.class.getDeclaredField("lifetimes"); lifetime.setAccessible(true);
        ((long[]) lifetime.get(null))[0]++; f.deliver(f.response()); f.refused("same user after login lifetime rotation");
        f = new Fixture(); f.start(); f.chat.migrated = 44; f.deliver(f.response()); f.refused("migrated-history route changed");
        f = new Fixture(); f.message.messageOwner.dialog_id = 43; f.chat.messages.clear(); f.chat.messages.put("43:8", f.message); f.start();
        TLRPC.TL_messages_messages migrated = f.response(); migrated.messages.get(0).dialog_id = 43;
        f.deliver(migrated); check(AndroidUtilities.clipboard.size() == 1, "opening migrated-history route remains valid");
        f = new Fixture(); MessageObject.GroupedMessages group = new MessageObject.GroupedMessages(); group.groupId = 5;
        f.message.messageOwner.grouped_id = 5; group.messages.add(f.message); MessageObject member = message(9, 42, false); member.messageOwner.grouped_id = 5;
        group.messages.add(member); f.chat.messages.put("42:9", member); f.chat.groups.put(5L, group); f.action.copy(f.message, group);
        group.messages.remove(member); TLRPC.TL_messages_messages album = f.response(); album.messages.get(0).grouped_id = 5;
        f.deliver(album); f.refused("album member removed during request");
        f = new Fixture(); group = new MessageObject.GroupedMessages(); group.groupId = 5;
        f.message.messageOwner.grouped_id = 5; group.messages.add(f.message); f.chat.groups.put(5L, group); f.action.copy(f.message, group);
        group.groupId = 6; f.chat.groups.put(6L, group); album = f.response(); album.messages.get(0).grouped_id = 5;
        f.deliver(album); f.refused("album identity cannot change while retaining the same group object");
        f = new Fixture(); f.start(); f.action.onDestroy(); f.deliver(f.response()); f.refused("destroyed chat never publishes a late response");
        f = new Fixture(); f.start(); f.chat.paused = true; f.deliver(f.response()); f.refused("paused chat never publishes a late response");
        f = new Fixture(); f.start(); f.message.deletedByThanos = true; f.deliver(f.response()); f.refused("deleted message never publishes a late response");
        f = new Fixture(); f.start(); f.chat.messages.put("42:8", message(8, 42, true)); f.deliver(f.response()); f.refused("current-message lookup replacement fences");
        f = new Fixture(); f.message.translated = true; f.message.messageOwner.translatedToLanguage = "es";
        f.message.translatedDisplay = rich(true, "vista previa"); f.action.copy(f.message, null);
        check(f.connection.requests.isEmpty(), "D-M3B5-A1 partial translated endpoint is unversioned and never requested");
        f.refused("D-M3B5-A1 partial translated display refuses entire action");
        f = new Fixture(); f.message.translated = true; f.message.messageOwner.translatedToLanguage = "es";
        f.message.translatedDisplay = rich(false, "traducido", "completo"); f.action.copy(f.message, null);
        check(AndroidUtilities.clipboard.equals(Arrays.asList("traducido\n\ncompleto")), "D-M3B5-A1 complete displayed translation still copies");
        f = new Fixture(); f.start(); TLRPC.TL_messages_messages prefix = f.response(); prefix.messages.get(0).rich_message = rich(false, "changed prefix", "second");
        f.deliver(prefix); f.refused("resolved full original must preserve observed prefix blocks");
        f = new Fixture(); PurpleArchiveMarkdown.source = "**actual source**\r\n"; f.action.copy(f.message, null);
        check(AndroidUtilities.clipboard.equals(Arrays.asList("**actual source**\r\n")) && f.connection.requests.isEmpty(), "verified genuine full source is exact");
        f = new Fixture(); PurpleArchiveMarkdown.source = ""; f.action.copy(f.message, null);
        check(AndroidUtilities.clipboard.equals(Arrays.asList("")), "genuine empty Markdown document copies exact empty source");
        f = new Fixture(); PurpleArchiveMarkdown.source = "**hidden original**"; f.message.translated = true;
        f.message.translatedDisplay = rich(false, "displayed translation"); f.action.copy(f.message, null);
        check(AndroidUtilities.clipboard.equals(Arrays.asList("displayed translation")), "translation never substitutes original source");
        f = new Fixture(); PurpleArchiveMarkdown.source = "**whole source**"; f.message.messageOwner.rich_message = rich(false, "selected display");
        f.action.copySelection(Arrays.asList(f.message));
        check(AndroidUtilities.clipboard.equals(Arrays.asList("selected display")), "selection never substitutes whole source");
        f = new Fixture(); PurpleArchiveMarkdown.queued = true; f.action.copy(f.message, null);
        PurpleArchiveMarkdown.epoch++; PurpleArchiveMarkdown.completion.run(); f.refused("purge or archive commit revokes queued source lookup");
        f = new Fixture(); PurpleArchiveMarkdown.queued = true; f.action.copy(f.message, null);
        f.message.messageOwner.message = "edited body"; PurpleArchiveMarkdown.completion.run(); f.refused("body edit revokes queued source lookup");
        f = new Fixture(); f.message.messageOwner.rich_message = rich(false, "recognized prefix"); f.message.messageOwner.rich_message.blocks.add(new TL_iv.pageBlockUnsupported());
        f.action.copy(f.message, null); f.refused("unknown suffix cannot yield a successful partial clipboard");
        f = new Fixture(); f.start(); f.connection.deliver(0, f.response()); f.action.cancel(); AndroidUtilities.flush(); f.refused("cancelled generation ignores queued callback");
        System.out.println("MARKDOWN_COPY_ROUTE_CHECKS=" + checks);
    }
}
