package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class PurpleArticleMarkdown {
    public static final class Content {
        public final String markdown;
        public final String html;

        public Content(String markdown, String html) {
            this.markdown = value(markdown);
            this.html = value(html);
        }
    }

    public static final class Item {
        public final Content body;
        public final String number;
        public final Integer value;
        public final String type;
        public final boolean checkbox;
        public final boolean checked;

        public Item(Content body, String number, Integer value, String type, boolean checkbox, boolean checked) {
            this.body = body;
            this.number = number;
            this.value = value;
            this.type = type;
            this.checkbox = checkbox;
            this.checked = checked;
        }
    }

    public static final class Cell {
        public final Content text;
        public final boolean header;
        public final int colspan;
        public final int rowspan;
        public final int alignment;
        public final int verticalAlignment;

        public Cell(Content text, boolean header, int colspan, int rowspan, int alignment, int verticalAlignment) {
            this.text = text;
            this.header = header;
            this.colspan = Math.max(1, colspan);
            this.rowspan = Math.max(1, rowspan);
            this.alignment = alignment;
            this.verticalAlignment = verticalAlignment;
        }
    }

    private PurpleArticleMarkdown() {
    }

    public static String value(String text) {
        return text == null ? "" : text;
    }

    public static String escapeHtml(String text) {
        return value(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static Content text(String text) {
        String source = value(text);
        return new Content(PurpleMarkdown.render(source, Collections.emptyList()), escapeHtml(source).replace("\n", "<br>"));
    }

    public static Content join(List<Content> parts, boolean blocks) {
        StringBuilder md = new StringBuilder(), html = new StringBuilder();
        for (Content part : parts) {
            if (part == null) continue;
            if (blocks && md.length() > 0 && !part.markdown.isEmpty()) md.append("\n\n");
            md.append(part.markdown);
            html.append(part.html);
            if (blocks) html.append('\n');
        }
        return new Content(md.toString(), html.toString());
    }

    public static Content paragraph(Content text) {
        return new Content(text.markdown, "<p>" + text.html + "</p>");
    }

    public static Content heading(int level, Content text) {
        level = Math.max(1, Math.min(6, level));
        return new Content(repeat('#', level) + " " + text.markdown,
                "<h" + level + ">" + text.html + "</h" + level + ">");
    }

    public static Content style(Content text, String marker, String tag) {
        String md = text.markdown;
        int start = 0, end = md.length();
        while (start < end && Character.isWhitespace(md.charAt(start))) start++;
        while (end > start && Character.isWhitespace(md.charAt(end - 1))) end--;
        String formatted = start == end ? md : md.substring(0, start) + marker + md.substring(start, end) + marker + md.substring(end);
        return new Content(formatted, "<" + tag + ">" + text.html + "</" + tag + ">");
    }

    public static Content htmlInline(Content text, String tag) {
        String html = "<" + tag + ">" + text.html + "</" + tag.split(" ", 2)[0] + ">";
        return new Content(html, html);
    }

    public static String safeUrl(String url) {
        String input = value(url).trim();
        String lower = input.toLowerCase(Locale.ROOT);
        if (!(input.startsWith("#") || lower.startsWith("https://") || lower.startsWith("http://")
                || lower.startsWith("tg://") || lower.startsWith("mailto:") || lower.startsWith("tel:"))) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c <= 32 || c == '<' || c == '>' || c == '\\' || c == 127) {
                out.append('%');
                String hex = Integer.toHexString(c).toUpperCase(Locale.ROOT);
                if (hex.length() < 2) out.append('0');
                out.append(hex);
            } else out.append(c);
        }
        return out.toString();
    }

    public static Content link(Content label, String url) {
        String target = safeUrl(url);
        if (target.isEmpty()) return label;
        if (label.markdown.isEmpty()) label = text(url);
        return new Content("[" + label.markdown + "](<" + target + ">)",
                "<a href=\"" + escapeHtml(target) + "\">" + label.html + "</a>");
    }

    public static Content code(String source, String language, boolean block) {
        source = value(source);
        if (!block && source.indexOf('\n') < 0) {
            String md = PurpleMarkdown.render(source, Collections.singletonList(
                    new PurpleMarkdown.Span(0, source.length(), PurpleMarkdown.CODE, null)));
            return new Content(md, "<code>" + escapeHtml(source) + "</code>");
        }
        String fence = repeat('`', Math.max(3, longestTicks(source) + 1));
        String lang = value(language);
        if (!lang.matches("[A-Za-z0-9_+-]*")) {
            String attribute = escapeHtml(lang).replace("\n", "&#10;").replace("\r", "&#13;");
            String html = "<pre><code data-language=\"" + attribute + "\">" + escapeHtml(source) + "</code></pre>";
            return new Content(html, html);
        }
        return new Content(fence + lang + "\n" + source + (source.endsWith("\n") ? "" : "\n") + fence,
                "<pre><code" + (lang.isEmpty() ? "" : " class=\"language-" + escapeHtml(lang) + "\"")
                        + ">" + escapeHtml(source) + "</code></pre>");
    }

    public static Content quote(Content body, Content author) {
        String md = body.markdown;
        if (!author.markdown.isEmpty()) md += "\n\n" + author.markdown;
        return new Content("> " + md.replace("\n", "\n> "),
                "<blockquote>" + body.html + (author.html.isEmpty() ? "" : "<cite>" + author.html + "</cite>") + "</blockquote>");
    }

    public static Content details(Content title, Content body, boolean open) {
        String html = "<details" + (open ? " open" : "") + "><summary>" + title.html + "</summary>\n" + body.html + "\n</details>";
        return new Content(html, html);
    }

    public static Content math(String source, boolean block) {
        source = value(source);
        if (block) return code(source, "math", true);
        String html = "<code class=\"math\">" + escapeHtml(source) + "</code>";
        return new Content(source.indexOf('$') < 0 && source.indexOf('\n') < 0 ? "$" + source + "$" : html, html);
    }

    public static Content divider() {
        return new Content("---", "<hr>");
    }

    public static Content anchor(String name) {
        String html = "<a id=\"" + escapeHtml(name) + "\"></a>";
        return new Content(html, html);
    }

    public static Content media(String label, String url, Content caption, Content credit) {
        String name = value(label).isEmpty() ? "Media" : label;
        Content description = safeUrl(url).isEmpty() ? text("[" + name + "]") : link(text(name), url);
        List<Content> parts = new ArrayList<>();
        parts.add(paragraph(description));
        if (!caption.markdown.isEmpty()) parts.add(paragraph(caption));
        if (!credit.markdown.isEmpty()) parts.add(paragraph(credit));
        return join(parts, true);
    }

    public static Content unsupported(String kind, Content body) {
        List<Content> parts = new ArrayList<>();
        parts.add(paragraph(text("nesting limit".equals(kind) ? "[Article nesting limit reached]" : "[Unsupported article block]")));
        if (body != null && !body.markdown.isEmpty()) parts.add(body);
        return join(parts, true);
    }

    public static Content list(List<Item> items, boolean ordered, int start, boolean reversed, String type) {
        String listType = value(type);
        boolean complex = ordered && (start < 0 || start > 999999999) || reversed || !listType.isEmpty() && !listType.equals("1");
        int counter = start;
        for (Item item : items) {
            if (item.value != null || !value(item.type).isEmpty()
                    || !value(item.number).isEmpty() && !item.number.equals(String.valueOf(counter))) complex = true;
            counter += reversed ? -1 : 1;
        }
        String tag = ordered ? "ol" : "ul";
        StringBuilder html = new StringBuilder("<" + tag);
        if (ordered) {
            html.append(" start=\"").append(start).append('"');
            if (reversed) html.append(" reversed");
            if (listType.matches("[1aAiI]")) html.append(" type=\"").append(listType).append('"');
        }
        html.append(">\n");
        StringBuilder md = new StringBuilder();
        counter = start;
        for (Item item : items) {
            html.append("<li");
            if (item.value != null) html.append(" value=\"").append(item.value).append('"');
            if (value(item.type).matches("[1aAiI]")) html.append(" type=\"").append(item.type).append('"');
            boolean rawNumber = !value(item.number).isEmpty() && !item.number.equals(String.valueOf(counter));
            if (rawNumber) html.append(" style=\"list-style:none\"");
            html.append('>');
            if (rawNumber) html.append("<span>").append(escapeHtml(item.number)).append("</span> ");
            if (item.checkbox) html.append("<input type=\"checkbox\" disabled").append(item.checked ? " checked" : "").append("> ");
            html.append(item.body.html).append("</li>\n");
            String marker = (ordered ? counter + ". " : "- ") + (item.checkbox ? (item.checked ? "[x] " : "[ ] ") : "");
            if (md.length() > 0) md.append('\n');
            md.append(marker).append(item.body.markdown.replace("\n", "\n" + repeat(' ', marker.length())));
            counter += reversed ? -1 : 1;
        }
        html.append("</").append(tag).append('>');
        return new Content(complex ? html.toString() : md.toString(), html.toString());
    }

    public static Content table(Content title, List<List<Cell>> rows) {
        boolean simple = !rows.isEmpty() && !rows.get(0).isEmpty();
        int columns = simple ? rows.get(0).size() : 0;
        StringBuilder html = new StringBuilder("<table>");
        if (!title.html.isEmpty()) html.append("<caption>").append(title.html).append("</caption>");
        for (int r = 0; r < rows.size(); r++) {
            List<Cell> row = rows.get(r);
            if (row.size() != columns) simple = false;
            html.append("<tr>");
            for (Cell cell : row) {
                if (cell.colspan != 1 || cell.rowspan != 1 || cell.header != (r == 0) || cell.verticalAlignment != 0
                        || cell.text.markdown.indexOf('\n') >= 0 || cell.text.markdown.indexOf('|') >= 0) simple = false;
                String tag = cell.header ? "th" : "td";
                html.append('<').append(tag).append(" colspan=\"").append(cell.colspan).append("\" rowspan=\"").append(cell.rowspan).append('"');
                String align = cell.alignment == 2 ? "right" : cell.alignment == 1 ? "center" : "left";
                String vertical = cell.verticalAlignment == 2 ? "bottom" : cell.verticalAlignment == 1 ? "middle" : "top";
                html.append(" style=\"text-align:").append(align).append(";vertical-align:").append(vertical).append("\">")
                        .append(cell.text.html).append("</").append(tag).append('>');
            }
            html.append("</tr>");
        }
        html.append("</table>");
        if (!simple) return new Content(html.toString(), html.toString());
        for (int r = 1; r < rows.size(); r++) {
            for (int c = 0; c < columns; c++) if (rows.get(r).get(c).alignment != rows.get(0).get(c).alignment) {
                return new Content(html.toString(), html.toString());
            }
        }
        StringBuilder md = new StringBuilder();
        if (!title.markdown.isEmpty()) md.append(title.markdown).append("\n\n");
        for (int r = 0; r < rows.size(); r++) {
            md.append('|');
            for (Cell cell : rows.get(r)) md.append(' ').append(cell.text.markdown).append(" |");
            if (r == 0) {
                md.append("\n|");
                for (Cell cell : rows.get(0)) md.append(cell.alignment == 2 ? " ---: |" : cell.alignment == 1 ? " :---: |" : " --- |");
            }
            if (r + 1 < rows.size()) md.append('\n');
        }
        return new Content(md.toString(), html.toString());
    }

    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder();
        while (count-- > 0) out.append(c);
        return out.toString();
    }

    private static int longestTicks(String text) {
        int longest = 0, run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
