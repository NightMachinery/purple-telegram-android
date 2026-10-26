package org.telegram.messenger.purple;

import org.telegram.tgnet.tl.TL_iv;
import org.telegram.tgnet.tl.TL_keyboard;

public final class RichMessageMarkdownSafetyTest {
    private static int checks;

    private static TL_iv.textPlain text(String value) {
        TL_iv.textPlain result = new TL_iv.textPlain();
        result.text = value;
        return result;
    }

    private static TL_iv.RichMessage article(TL_iv.PageBlock block) {
        TL_iv.RichMessage result = new TL_iv.RichMessage();
        result.blocks.add(block);
        return result;
    }

    private static TL_iv.pageBlockParagraph paragraph(TL_iv.RichText value) {
        TL_iv.pageBlockParagraph result = new TL_iv.pageBlockParagraph();
        result.text = value;
        return result;
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void refuses(TL_iv.RichMessage value, String label) {
        boolean refused = false;
        try { PurpleRichMessageMarkdown.render(value); }
        catch (IllegalArgumentException expected) { refused = true; }
        check(refused, label);
    }

    public static void main(String[] args) {
        check(PurpleRichMessageMarkdown.render(article(paragraph(text("first")))).equals("first"), "known paragraph");
        TL_iv.RichMessage complete = article(paragraph(text("first")));
        complete.blocks.add(paragraph(text("second")));
        check(PurpleRichMessageMarkdown.render(complete).equals("first\n\nsecond"), "every received block");
        TL_iv.RichMessage partial = article(paragraph(text("preview")));
        partial.part = true;
        refuses(partial, "partial is not a complete article");
        refuses(null, "missing article");
        refuses(article(null), "missing block");
        TL_iv.pageBlockUnsupported unknown = new TL_iv.pageBlockUnsupported();
        unknown.text = text("known-looking text");
        refuses(article(unknown), "unknown block never becomes successful placeholder");
        TL_iv.pageBlockCover cover = new TL_iv.pageBlockCover();
        cover.cover = unknown;
        refuses(article(cover), "nested unknown block refuses entire action");
        complete.blocks.add(unknown);
        refuses(complete, "recognized prefix cannot hide unknown suffix");
        TL_iv.RichText unrecognized = new TL_iv.RichText() {};
        unrecognized.text = text("retained label");
        refuses(article(paragraph(unrecognized)), "unknown rich text refuses");
        TL_iv.pageBlockPreformatted code = new TL_iv.pageBlockPreformatted();
        code.text = unrecognized;
        code.language = "text";
        refuses(article(code), "unknown code text refuses");
        TL_iv.pageBlockList list = new TL_iv.pageBlockList();
        list.items.add(new TL_iv.PageListItem() {});
        refuses(article(list), "unknown list item refuses");
        TL_iv.pageBlockOrderedList ordered = new TL_iv.pageBlockOrderedList();
        ordered.items.add(new TL_iv.PageListOrderedItem() {});
        refuses(article(ordered), "unknown ordered list item refuses");
        TL_iv.RichText deep = text("bottom");
        for (int i = 0; i < 140; ++i) {
            TL_iv.textBold parent = new TL_iv.textBold();
            parent.text = deep;
            deep = parent;
        }
        refuses(article(paragraph(deep)), "nesting budget refuses rather than drops suffix");
        TL_iv.pageBlockDetails details = new TL_iv.pageBlockDetails();
        details.title = text("<Summary>&");
        details.blocks.add(paragraph(text("<hidden>&")));
        String html = PurpleRichMessageMarkdown.render(article(details));
        check(html.contains("&lt;Summary&gt;&amp;") && html.contains("&lt;hidden&gt;&amp;"), "closed details preserve escaped contents");
        TL_iv.textButton button = new TL_iv.textButton();
        button.text = text("open");
        button.type = new TL_keyboard.TL_inlineButtonTypeUrl();
        ((TL_keyboard.TL_inlineButtonTypeUrl) button.type).url = "https://example.invalid/";
        check(PurpleRichMessageMarkdown.render(article(paragraph(button))).contains("https://example.invalid/"), "known URL button preserved");
        button.type = null;
        refuses(article(paragraph(button)), "unknown button cannot be labelled complete");
        System.out.println("RICH_MARKDOWN_SAFETY_CHECKS=" + checks);
    }
}
