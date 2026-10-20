import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.telegram.messenger.purple.PurpleArticleMarkdown;
import static org.telegram.messenger.purple.PurpleArticleMarkdown.*;

public final class ArticleMarkdownTest {
    private static int checks;

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=[" + expected + "] actual=[" + actual + "]");
        checks++;
    }

    private static void has(String source, String required) {
        if (!source.contains(required)) throw new AssertionError("missing=[" + required + "] actual=[" + source + "]");
        checks++;
    }

    private static void lacks(String source, String forbidden) {
        if (source.contains(forbidden)) throw new AssertionError("forbidden=[" + forbidden + "] actual=[" + source + "]");
        checks++;
    }

    private static Item item(Content body) {
        return new Item(body, null, null, null, false, false);
    }

    private static Cell cell(String text, boolean header, int alignment) {
        return new Cell(PurpleArticleMarkdown.text(text), header, 1, 1, alignment, 0);
    }

    public static void main(String[] args) {
        equal("", join(Collections.emptyList(), true).markdown);
        equal("\\# literal \\*word\\* &lt;script&gt;", text("# literal *word* <script>").markdown);
        for (int level = 1; level <= 6; level++) {
            String marks = new String(new char[level]).replace('\0', '#');
            equal(marks + " title", heading(level, text("title")).markdown);
            equal("<h" + level + ">title</h" + level + ">", heading(level, text("title")).html);
        }
        equal(" **bold** ", style(text(" bold "), "**", "strong").markdown);
        equal("**bold *nested***", style(join(Arrays.asList(text("bold "), style(text("nested"), "*", "em")), false), "**", "strong").markdown);
        equal("<span class=\"spoiler\">secret</span>", htmlInline(text("secret"), "span class=\"spoiler\"").html);
        equal("<u><em>x</em></u>", htmlInline(style(text("x"), "*", "em"), "u").markdown);
        equal("``a`b``", code("a`b", null, false).markdown);
        equal("`` `x` ``", code("`x`", null, false).markdown);
        equal("`````java\n````\n`````", code("````", "java", true).markdown);
        equal("<pre><code data-language=\"bad&#10;```\">x</code></pre>", code("x", "bad\n```", true).markdown);
        equal("<pre><code data-language=\"C#\">x</code></pre>", code("x", "C#", true).markdown);
        has(code("<tag>&", "html", true).html, "&lt;tag&gt;&amp;");
        equal("[a *label*](<https://example.org/a(b)%20x>)", link(join(Arrays.asList(text("a "), style(text("label"), "*", "em")), false), "https://example.org/a(b) x").markdown);
        equal("[x](<https://example.org/%3E%5C>)", link(text("x"), "https://example.org/>\\").markdown);
        equal("x", link(text("x"), "javascript:alert(1)").markdown);
        equal("x", link(text("x"), "file:///private/cache").markdown);
        equal("x", link(text("x"), "/private/cache").markdown);
        equal("x", link(text("x"), "12345").markdown);
        equal("[email](<mailto:a@example.org>)", link(text("email"), "mailto:a@example.org").markdown);
        equal("[section](<#part>)", link(text("section"), "#part").markdown);
        Content body = join(Arrays.asList(paragraph(text("first")), paragraph(text("second")), list(Collections.singletonList(item(paragraph(text("nested")))), false, 1, false, null)), true);
        equal("- first\n  \n  second\n  \n  - nested", list(Collections.singletonList(item(body)), false, 1, false, null).markdown);
        Content numbered = list(Arrays.asList(item(text("seven")), item(text("eight"))), true, 7, false, "1");
        equal("7. seven\n8. eight", numbered.markdown);
        Content task = list(Arrays.asList(new Item(text("done"), null, null, null, true, true), new Item(text("todo"), null, null, null, true, false)), false, 1, false, null);
        equal("- [x] done\n- [ ] todo", task.markdown);
        has(task.html, "type=\"checkbox\" disabled checked");
        Content special = list(Arrays.asList(new Item(body, "iv)", 4, "i", true, true)), true, 4, true, "i");
        has(special.markdown, "<ol start=\"4\" reversed type=\"i\">");
        has(special.markdown, "<li value=\"4\" type=\"i\" style=\"list-style:none\"><span>iv)</span>");
        has(special.markdown, "<p>second</p>");
        has(special.markdown, "<ul>");
        has(list(Collections.singletonList(item(text("negative"))), true, -1, false, null).markdown, "<ol start=\"-1\">");
        equal("> first\n> \n> second\n> \n> author", quote(join(Arrays.asList(paragraph(text("first")), paragraph(text("second"))), true), text("author")).markdown);
        has(quote(body, text("author")).html, "<cite>author</cite>");
        Content closed = details(text("Summary"), join(Arrays.asList(heading(2, text("hidden")), body), true), false);
        has(closed.markdown, "<details><summary>Summary</summary>");
        has(closed.markdown, "<h2>hidden</h2>");
        has(closed.markdown, "<p>second</p>");
        has(details(text("Summary"), body, true).markdown, "<details open>");
        List<List<Cell>> rows = Arrays.asList(Arrays.asList(cell("left", true, 0), cell("center", true, 1)), Arrays.asList(cell("one", false, 0), cell("two", false, 1)));
        equal("Table\n\n| left | center |\n| --- | :---: |\n| one | two |", table(text("Table"), rows).markdown);
        Content merged = table(text("caption"), Arrays.asList(Collections.singletonList(new Cell(text("a<b"), true, 2, 2, 2, 1))));
        has(merged.markdown, "<caption>caption</caption>");
        has(merged.markdown, "colspan=\"2\" rowspan=\"2\"");
        has(merged.markdown, "text-align:right;vertical-align:middle");
        has(merged.markdown, "a&lt;b");
        Content pipe = table(text(""), Arrays.asList(Collections.singletonList(cell("a|b", true, 0))));
        has(pipe.markdown, "<table>");
        has(pipe.markdown, "a|b");
        Content multiline = table(text(""), Arrays.asList(Collections.singletonList(cell("a\nb", true, 0))));
        has(multiline.markdown, "a<br>b");
        Content headless = table(text(""), Arrays.asList(Collections.singletonList(cell("body", false, 0))));
        has(headless.markdown, "<td");
        Content media = media("Photo", "123", text("Caption"), text("Credit"));
        equal("\\[Photo\\]\n\nCaption\n\nCredit", media.markdown);
        lacks(media.markdown, "123");
        Content linkedMedia = media("Photo", "https://example.org/photo", text("caption"), text("credit"));
        has(linkedMedia.markdown, "[Photo](<https://example.org/photo>)");
        Content absentMedia = media("<img src=x>", "file:///cache/private", text(""), text(""));
        has(absentMedia.markdown, "&lt;img src=x&gt;");
        lacks(absentMedia.markdown, "file:");
        equal("$x+y$", math("x+y", false).markdown);
        equal("```math\nx+y\n```", math("x+y", true).markdown);
        equal("<code class=\"math\">$x</code>", math("$x", false).markdown);
        has(unsupported("newBlock", paragraph(text("remaining content"))).markdown, "remaining content");
        has(unsupported("<script>", null).html, "[Unsupported article block]");
        lacks(unsupported("<script>", null).html, "<script>");
        equal("<a id=\"a&quot;b\"></a>", anchor("a\"b").markdown);
        equal("😀", text("😀").markdown);
        equal("---", divider().markdown);
        System.out.println("Article Markdown checks: " + checks);
    }
}
