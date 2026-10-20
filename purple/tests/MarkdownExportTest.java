import java.util.Arrays;
import java.util.Collections;
import org.telegram.messenger.purple.PurpleMarkdown;
import static org.telegram.messenger.purple.PurpleMarkdown.*;

public final class MarkdownExportTest {
    private static int checks;
    private static Span span(int start, int end, int kind) {
        return new Span(start, end, kind, null);
    }
    private static void check(String expected, String text, Span... spans) {
        String actual = render(text, Arrays.asList(spans));
        if (!actual.equals(expected)) throw new AssertionError("expected=[" + expected + "] actual=[" + actual + "]");
        checks++;
    }
    public static void main(String[] args) {
        check("", "");
        check("Hello world", "Hello world");
        check("**Hello** world", "Hello world", span(0, 5, BOLD));
        check("*Hello*", "Hello", span(0, 5, ITALIC));
        check("~~gone~~", "gone", span(0, 4, STRIKE));
        check("<u>line</u>", "line", span(0, 4, UNDERLINE));
        check(" **Hello** ", " Hello ", span(0, 7, BOLD));
        check("**bold *and italic***", "bold and italic", span(0, 15, BOLD), span(5, 15, ITALIC));
        check("**Hello**", "Hello", span(0, 5, BOLD), span(0, 5, BOLD));
        check("`a*b`", "a*b", span(0, 3, CODE));
        check("``a`b``", "a`b", span(0, 3, CODE));
        check("`` `x` ``", "`x`", span(0, 3, CODE));
        check("`  `", "  ", span(0, 2, CODE));
        check("```java\nx();\n```\n", "x();", new Span(0, 4, PRE, "java"));
        check("````\n```\n````\n", "```", span(0, 3, PRE));
        check("```\nx\n```\n", "x", new Span(0, 1, PRE, "bad\n```"));
        check("> first\n> second\n", "first\nsecond", span(0, 12, QUOTE));
        check("[name](<https://example.org/a(b)%20c>)", "name", new Span(0, 4, LINK, "https://example.org/a(b) c"));
        check("[x](<https://example.org/%3E%5C>)", "x", new Span(0, 1, LINK, "https://example.org/>\\"));
        check("😀 **ok**", "😀 ok", span(3, 5, BOLD));
        check("**hi**", "hi", span(-2, 200, BOLD));
        check("hi", "hi", span(10, 20, BOLD), span(1, 1, CODE));
        check("&lt;tag&gt;&amp;", "<tag>&");
        check("\\*literal\\*", "*literal*");
        check("**abc*de****f*", "abcdef", span(0, 5, BOLD), span(3, 6, ITALIC));
        System.out.println("Markdown export checks: " + checks);
    }
}
