package org.telegram.messenger.purple;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.tgnet.tl.TL_keyboard;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.telegram.messenger.purple.PurpleArticleMarkdown.*;

public final class PurpleRichMessageMarkdown {
    private final TL_iv.RichMessage article;

    private PurpleRichMessageMarkdown(TL_iv.RichMessage article) {
        this.article = article;
    }

    public static String render(TL_iv.RichMessage article) {
        if (article == null || article.part) throw new IllegalArgumentException("A complete article is required");
        return new PurpleRichMessageMarkdown(article).blocks(article.blocks, 0).markdown;
    }

    private Content blocks(List<TL_iv.PageBlock> input, int depth) {
        if (depth > 128) throw new IllegalArgumentException("Article nesting limit");
        List<Content> result = new ArrayList<>();
        if (input != null) for (TL_iv.PageBlock block : input) result.add(block(block, depth + 1));
        return join(result, true);
    }

    private Content block(TL_iv.PageBlock b, int depth) {
        if (b == null) throw new IllegalArgumentException("Missing article block");
        if (depth > 128) throw new IllegalArgumentException("Article nesting limit");
        int heading = headingLevel(b);
        if (heading > 0) return heading(heading, inline(b.text, depth));
        if (b instanceof TL_iv.pageBlockParagraph || b instanceof TL_iv.pageBlockFooter || b instanceof TL_iv.pageBlockKicker) {
            return paragraph(inline(b.text, depth));
        }
        if (b instanceof TL_iv.pageBlockThinking) return quote(paragraph(inline(b.text, depth)), text(""));
        if (b instanceof TL_iv.pageBlockPreformatted) {
            return code(plain(b.text, depth), ((TL_iv.pageBlockPreformatted) b).language, true);
        }
        if (b instanceof TL_iv.pageBlockDivider) return divider();
        if (b instanceof TL_iv.pageBlockAnchor) return anchor(((TL_iv.pageBlockAnchor) b).name);
        if (b instanceof TL_iv.pageBlockAuthorDate) {
            TL_iv.pageBlockAuthorDate byline = (TL_iv.pageBlockAuthorDate) b;
            return paragraph(join(java.util.Arrays.asList(inline(byline.author, depth), text(date(byline.published_date).isEmpty() ? "" : " · " + date(byline.published_date))), false));
        }
        if (b instanceof TL_iv.pageBlockList) {
            TL_iv.pageBlockList list = (TL_iv.pageBlockList) b;
            List<Item> items = new ArrayList<>();
            for (TL_iv.PageListItem item : list.items) {
                Content body;
                if (item instanceof TL_iv.TL_pageListItemText) body = paragraph(inline(((TL_iv.TL_pageListItemText) item).text, depth));
                else if (item instanceof TL_iv.TL_pageListItemBlocks) body = blocks(((TL_iv.TL_pageListItemBlocks) item).blocks, depth);
                else throw new IllegalArgumentException("Unsupported article list item");
                items.add(new Item(body, null, null, null, item.checkbox, item.checked));
            }
            return list(items, list.ordered, 1, false, null);
        }
        if (b instanceof TL_iv.pageBlockOrderedList) {
            TL_iv.pageBlockOrderedList list = (TL_iv.pageBlockOrderedList) b;
            List<Item> items = new ArrayList<>();
            for (TL_iv.PageListOrderedItem item : list.items) {
                Content body;
                if (item instanceof TL_iv.TL_pageListOrderedItemText) body = paragraph(inline(((TL_iv.TL_pageListOrderedItemText) item).text, depth));
                else if (item instanceof TL_iv.TL_pageListOrderedItemBlocks) body = blocks(((TL_iv.TL_pageListOrderedItemBlocks) item).blocks, depth);
                else throw new IllegalArgumentException("Unsupported article list item");
                items.add(new Item(body, item.num, (item.flags & 8) != 0 ? item.value : null, item.type, item.checkbox, item.checked));
            }
            int start = (list.flags & 1) != 0 ? list.start : list.reversed ? items.size() : 1;
            return list(items, true, start, list.reversed, list.type);
        }
        if (b instanceof TL_iv.pageBlockBlockquoteBlocks) {
            TL_iv.pageBlockBlockquoteBlocks q = (TL_iv.pageBlockBlockquoteBlocks) b;
            return quote(blocks(q.blocks, depth), inline(q.caption, depth));
        }
        if (b instanceof TL_iv.pageBlockBlockquote) {
            TL_iv.pageBlockBlockquote q = (TL_iv.pageBlockBlockquote) b;
            return quote(paragraph(inline(q.text, depth)), inline(q.caption, depth));
        }
        if (b instanceof TL_iv.pageBlockPullquote) {
            TL_iv.pageBlockPullquote q = (TL_iv.pageBlockPullquote) b;
            return quote(paragraph(inline(q.text, depth)), inline(q.caption, depth));
        }
        if (b instanceof TL_iv.pageBlockDetails) {
            TL_iv.pageBlockDetails d = (TL_iv.pageBlockDetails) b;
            return details(inline(d.title, depth), blocks(d.blocks, depth), d.open);
        }
        if (b instanceof TL_iv.pageBlockTable) {
            TL_iv.pageBlockTable t = (TL_iv.pageBlockTable) b;
            List<List<Cell>> rows = new ArrayList<>();
            for (TL_iv.pageTableRow row : t.rows) {
                List<Cell> cells = new ArrayList<>();
                for (TL_iv.pageTableCell cell : row.cells) {
                    cells.add(new Cell(inline(cell.text, depth), cell.header, cell.colspan, cell.rowspan,
                            cell.align_right ? 2 : cell.align_center ? 1 : 0, cell.valign_bottom ? 2 : cell.valign_middle ? 1 : 0));
                }
                rows.add(cells);
            }
            return table(inline(t.title, depth), rows);
        }
        if (b instanceof TL_iv.pageBlockMath) return math(((TL_iv.pageBlockMath) b).source, true);
        if (b instanceof TL_iv.pageBlockCover) return block(((TL_iv.pageBlockCover) b).cover, depth + 1);
        if (b instanceof TL_iv.pageBlockCollage || b instanceof TL_iv.pageBlockSlideshow) {
            List<TL_iv.PageBlock> items = b instanceof TL_iv.pageBlockCollage ? ((TL_iv.pageBlockCollage) b).items : ((TL_iv.pageBlockSlideshow) b).items;
            return join(java.util.Arrays.asList(blocks(items, depth), caption(b, depth)), true);
        }
        if (b instanceof TL_iv.pageBlockPhoto) return mediaBlock(b, "Photo", ((TL_iv.pageBlockPhoto) b).url, depth);
        if (b instanceof TL_iv.pageBlockVideo) return mediaBlock(b, documentName(((TL_iv.pageBlockVideo) b).video_id, "Video"), null, depth);
        if (b instanceof TL_iv.pageBlockAudio) return mediaBlock(b, documentName(((TL_iv.pageBlockAudio) b).audio_id, "Audio"), null, depth);
        if (b instanceof TL_iv.pageBlockDocument) return mediaBlock(b, documentName(((TL_iv.pageBlockDocument) b).document_id, "Document"), null, depth);
        if (b instanceof TL_iv.pageBlockEmbed) {
            TL_iv.pageBlockEmbed e = (TL_iv.pageBlockEmbed) b;
            List<Content> parts = new ArrayList<>();
            parts.add(mediaBlock(b, "Embedded content", e.url, depth));
            if (!value(e.html).isEmpty()) parts.add(code(e.html, "html", true));
            return join(parts, true);
        }
        if (b instanceof TL_iv.pageBlockEmbedPost) {
            TL_iv.pageBlockEmbedPost e = (TL_iv.pageBlockEmbedPost) b;
            Content author = link(text(value(e.author) + (e.date != 0 ? " · " + date(e.date) : "")), e.url);
            List<Content> content = new ArrayList<>();
            if (e.author_photo_id != 0) content.add(media("Author photo", e.url, text(""), text("")));
            content.add(blocks(e.blocks, depth));
            content.add(caption(b, depth));
            return quote(join(content, true), author);
        }
        if (b instanceof TL_iv.pageBlockChannel) {
            TLRPC.Chat channel = ((TL_iv.pageBlockChannel) b).channel;
            return paragraph(channel == null ? text("[Channel]") : link(text(channel.title), value(channel.username).isEmpty() ? null : "https://t.me/" + channel.username));
        }
        if (b instanceof TL_iv.pageBlockMap) {
            TL_iv.pageBlockMap m = (TL_iv.pageBlockMap) b;
            String coordinates = m.geo == null ? "" : m.geo.lat + ", " + m.geo._long;
            String url = m.geo == null ? null : "https://www.openstreetmap.org/?mlat=" + m.geo.lat + "&mlon=" + m.geo._long;
            return mediaBlock(b, "Map" + (coordinates.isEmpty() ? "" : ": " + coordinates), url, depth);
        }
        if (b instanceof TL_iv.pageBlockRelatedArticles) {
            TL_iv.pageBlockRelatedArticles related = (TL_iv.pageBlockRelatedArticles) b;
            List<Content> parts = new ArrayList<>();
            parts.add(paragraph(inline(related.title, depth)));
            for (TL_iv.pageRelatedArticle a : related.articles) {
                parts.add(paragraph(link(text(value(a.title).isEmpty() ? a.url : a.title), a.url)));
                if (a.photo_id != 0) parts.add(media("Article image", a.url, text(""), text("")));
                if (!value(a.description).isEmpty()) parts.add(paragraph(text(a.description)));
                if (!value(a.author).isEmpty() || a.published_date != 0) parts.add(paragraph(text(value(a.author) + (a.published_date != 0 ? " · " + date(a.published_date) : ""))));
            }
            return join(parts, true);
        }
        if (b instanceof TL_iv.pageBlockButtonRow) {
            List<Content> parts = new ArrayList<>();
            for (TL_keyboard.PageButton button : ((TL_iv.pageBlockButtonRow) b).buttons) {
                parts.add(paragraph(button(inline(button.text, depth), button.type)));
            }
            return join(parts, true);
        }
        throw new IllegalArgumentException("Unsupported article block: " + b.getClass().getSimpleName());
    }

    private Content caption(TL_iv.PageBlock block, int depth) {
        if (block.caption == null) return text("");
        return join(java.util.Arrays.asList(paragraph(inline(block.caption.text, depth)), paragraph(inline(block.caption.credit, depth))), true);
    }

    private Content mediaBlock(TL_iv.PageBlock block, String label, String url, int depth) {
        return media(label, url, block.caption == null ? text("") : inline(block.caption.text, depth),
                block.caption == null ? text("") : inline(block.caption.credit, depth));
    }

    private String documentName(long id, String fallback) {
        for (TLRPC.Document document : article.documents) {
            if (document.id != id) continue;
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeFilename && !value(attribute.file_name).isEmpty()) return fallback + ": " + attribute.file_name;
                if (attribute instanceof TLRPC.TL_documentAttributeAudio && !value(attribute.title).isEmpty()) return fallback + ": " + attribute.title;
            }
        }
        return fallback;
    }

    private Content inline(TL_iv.RichText input, int depth) {
        if (input == null || input instanceof TL_iv.textEmpty) return text("");
        if (depth > 128) throw new IllegalArgumentException("Article nesting limit");
        depth++;
        if (input instanceof TL_iv.textPlain) return text(((TL_iv.textPlain) input).text);
        if (input instanceof TL_iv.textConcat) {
            List<Content> parts = new ArrayList<>();
            for (TL_iv.RichText child : input.texts) parts.add(inline(child, depth));
            return join(parts, false);
        }
        if (input instanceof TL_iv.textFixed) return code(plain(input.text, depth), null, false);
        if (input instanceof TL_iv.textMath) return math(((TL_iv.textMath) input).source, false);
        if (input instanceof TL_iv.textCustomEmoji) return text(value(((TL_iv.textCustomEmoji) input).alt).isEmpty() ? "[Custom emoji]" : ((TL_iv.textCustomEmoji) input).alt);
        if (input instanceof TL_iv.textImage) return text("[" + documentName(((TL_iv.textImage) input).document_id, "Image") + "]");
        if (input instanceof TL_iv.textDiff) {
            TL_iv.textDiff diff = (TL_iv.textDiff) input;
            Content old = htmlInline(inline(diff.old_text, depth), "del");
            Content next = htmlInline(inline(diff.text, depth), "ins");
            return join(java.util.Arrays.asList(old, next), false);
        }
        Content body = inline(input.text, depth);
        if (input instanceof TL_iv.textBold) return style(body, "**", "strong");
        if (input instanceof TL_iv.textItalic) return style(body, "*", "em");
        if (input instanceof TL_iv.textStrike) return style(body, "~~", "del");
        if (input instanceof TL_iv.textUnderline) return htmlInline(body, "u");
        if (input instanceof TL_iv.textSubscript) return htmlInline(body, "sub");
        if (input instanceof TL_iv.textSuperscript) return htmlInline(body, "sup");
        if (input instanceof TL_iv.textMarked) return htmlInline(body, "mark");
        if (input instanceof TL_iv.textSpoiler) return htmlInline(body, "span class=\"spoiler\"");
        if (input instanceof TL_iv.textUrl || input instanceof TL_iv.textAutoUrl) return link(body, input.url == null ? plain(input.text, depth) : input.url);
        if (input instanceof TL_iv.textEmail || input instanceof TL_iv.textAutoEmail) return link(body, "mailto:" + value(input.email == null ? plain(input.text, depth) : input.email));
        if (input instanceof TL_iv.textPhone) return link(body, "tel:" + value(((TL_iv.textPhone) input).phone));
        if (input instanceof TL_iv.textAutoPhone) return link(body, "tel:" + plain(input.text, depth));
        if (input instanceof TL_iv.textMentionName) return link(body, "tg://user?id=" + ((TL_iv.textMentionName) input).user_id);
        if (input instanceof TL_iv.textMention) return link(body, "https://t.me/" + plain(input.text, depth).replaceFirst("^@", ""));
        if (input instanceof TL_iv.textAnchor) return join(java.util.Arrays.asList(anchor(((TL_iv.textAnchor) input).name), body), false);
        if (input instanceof TL_iv.textDate) return body.markdown.isEmpty() ? text(date(((TL_iv.textDate) input).date)) : body;
        if (input instanceof TL_iv.textButton) return button(body, ((TL_iv.textButton) input).type);
        if (input instanceof TL_iv.textHashtag || input instanceof TL_iv.textBotCommand || input instanceof TL_iv.textCashtag || input instanceof TL_iv.textBankCard) return body;
        throw new IllegalArgumentException("Unsupported article text: " + input.getClass().getSimpleName());
    }

    private Content button(Content label, TL_keyboard.InlineButtonType type) {
        if (type instanceof TL_keyboard.TL_inlineButtonTypeUrl) return link(label, ((TL_keyboard.TL_inlineButtonTypeUrl) type).url);
        if (type instanceof TL_keyboard.TL_inlineButtonTypeUrlAuth) return link(label, ((TL_keyboard.TL_inlineButtonTypeUrlAuth) type).url);
        throw new IllegalArgumentException("Unsupported article button");
    }

    private String plain(TL_iv.RichText input, int depth) {
        if (depth > 128) throw new IllegalArgumentException("Article nesting limit");
        if (input == null) return "";
        if (input instanceof TL_iv.textPlain) return value(((TL_iv.textPlain) input).text);
        if (input instanceof TL_iv.textMath) return value(((TL_iv.textMath) input).source);
        if (input instanceof TL_iv.textCustomEmoji) return value(((TL_iv.textCustomEmoji) input).alt);
        if (input instanceof TL_iv.textImage) return "[Image]";
        if (input instanceof TL_iv.textConcat) {
            StringBuilder out = new StringBuilder();
            for (TL_iv.RichText child : input.texts) out.append(plain(child, depth + 1));
            return out.toString();
        }
        if (input instanceof TL_iv.textEmpty) return "";
        if (input instanceof TL_iv.textBold || input instanceof TL_iv.textItalic
                || input instanceof TL_iv.textUnderline || input instanceof TL_iv.textStrike
                || input instanceof TL_iv.textFixed || input instanceof TL_iv.textUrl
                || input instanceof TL_iv.textEmail || input instanceof TL_iv.textSubscript
                || input instanceof TL_iv.textSuperscript || input instanceof TL_iv.textMarked
                || input instanceof TL_iv.textPhone || input instanceof TL_iv.textAnchor
                || input instanceof TL_iv.textSpoiler || input instanceof TL_iv.textMention
                || input instanceof TL_iv.textHashtag || input instanceof TL_iv.textBotCommand
                || input instanceof TL_iv.textCashtag || input instanceof TL_iv.textAutoUrl
                || input instanceof TL_iv.textAutoEmail || input instanceof TL_iv.textAutoPhone
                || input instanceof TL_iv.textBankCard || input instanceof TL_iv.textMentionName
                || input instanceof TL_iv.textDate) return plain(input.text, depth + 1);
        throw new IllegalArgumentException("Unsupported article code text: " + input.getClass().getSimpleName());
    }

    private static int headingLevel(TL_iv.PageBlock b) {
        if (b instanceof TL_iv.pageBlockHeading1 || b instanceof TL_iv.pageBlockTitle) return 1;
        if (b instanceof TL_iv.pageBlockHeading2 || b instanceof TL_iv.pageBlockSubtitle) return 2;
        if (b instanceof TL_iv.pageBlockHeading3 || b instanceof TL_iv.pageBlockHeader) return 3;
        if (b instanceof TL_iv.pageBlockHeading4 || b instanceof TL_iv.pageBlockSubheader) return 4;
        if (b instanceof TL_iv.pageBlockHeading5) return 5;
        if (b instanceof TL_iv.pageBlockHeading6) return 6;
        return 0;
    }

    private static String date(int date) {
        if (date == 0) return "";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(date * 1000L));
    }
}
