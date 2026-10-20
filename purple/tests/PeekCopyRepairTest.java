package org.telegram.messenger.purple;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

public final class PeekCopyRepairTest {
    private static int checks;

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        ++checks;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        check(directory.mkdir(), "own fixture directory");
        File settings = new File(directory, "settings.toml");
        PurplePeekSnapshot missing = PurplePeekSnapshot.read(settings, 1024);
        check(missing.status == PurplePeekSnapshot.Status.Missing, "missing is distinct");
        check(!missing.sameAs(PurplePeekSnapshot.read(settings, 1024)), "two missing inputs never authorize completion");
        check(!missing.replace(settings, bytes("new")), "missing input never authorizes write");
        Files.write(settings.toPath(), bytes("[peek]\nstart_delay_seconds = 0\n"));
        PurplePeekSnapshot cachedZero = PurplePeekSnapshot.read(settings, 1024);
        Files.write(settings.toPath(), bytes("[peek]\nstart_delay_seconds = 10\n"));
        PurplePeekSnapshot freshTen = PurplePeekSnapshot.read(settings, 1024);
        check(!cachedZero.sameAs(freshTen), "fresh zero to ten edit differs from cached bytes");
        check(!cachedZero.matches(settings), "stale zero snapshot cannot complete");
        check(text(freshTen.bytes()).contains("= 10"), "fresh snapshot carries actual ten bytes");
        check(PurplePeekSnapshot.waitSeconds(10, null) == 10, "activation uses resolved snapshot delay");
        check(PurplePeekSnapshot.waitSeconds(10, 0) == 10, "reducing delay honors actual old ten");
        check(PurplePeekSnapshot.waitSeconds(10, 20) == 20, "raising delay honors new twenty");
        check(PurplePeekSnapshot.waitSeconds(0, 0) == 0, "explicit zero stays immediate");
        byte[] escaped = freshTen.bytes();
        escaped[0] = 0;
        check(freshTen.matches(settings), "callers cannot mutate captured snapshot");
        Files.write(settings.toPath(), bytes("[peek]\nstart_delay_seconds = 20\nother = true\n"));
        check(!freshTen.replace(settings, bytes("[peek]\nstart_delay_seconds = 0\n")), "stale delayed edit refuses write");
        check(text(Files.readAllBytes(settings.toPath())).contains("other = true"), "external edit survives refused write");
        PurplePeekSnapshot twenty = PurplePeekSnapshot.read(settings, 1024);
        byte[] proposed = bytes("[peek]\nstart_delay_seconds = 5\nother = true\n");
        check(twenty.replace(settings, proposed), "same validated input permits replacement");
        check(text(Files.readAllBytes(settings.toPath())).equals(text(proposed)), "successful replacement writes exact supplied splice bytes");
        check(!twenty.matches(settings), "successful replacement invalidates old operation");
        check(PurplePeekSnapshot.read(settings, 2).status == PurplePeekSnapshot.Status.Invalid, "oversize input is invalid");
        check(PurplePeekSnapshot.read(directory, 1024).status == PurplePeekSnapshot.Status.Invalid, "non-file input is invalid");
        Files.delete(settings.toPath());
        check(!twenty.matches(settings), "deleted input cannot authorize completion");
        check(directory.list().length == 0, "guarded writes leave no temporary files");

        PurpleRecentOpen open = new PurpleRecentOpen();
        open.set(0, 42, true);
        open.suppress(1, 42);
        check(open.eligible, "other account suppression cannot affect open chat");
        open.suppress(0, 43);
        check(open.eligible, "other dialog suppression cannot affect open chat");
        check(!open.close(1, 42) && open.matches(0, 42), "stale account close preserves current ownership");
        check(open.close(0, 42), "ordinary eligible close still grants grace");
        open.set(0, 42, true);
        open.suppress(0, 42);
        check(!open.eligible, "forced peek exit removes open exemption");
        check(!open.close(0, 42), "forced close cannot bank grace again");
        check(open.dialogId == 0 && open.account == -1, "normal close clears exact open identity");
        open.set(1, 42, true);
        open.suppress(0, 42);
        check(open.close(1, 42), "same dialog in another account retains ordinary grace");
        open.set(0, 42, false);
        check(!open.close(0, 42), "ineligible ordinary navigation remains ineligible");
        open.set(0, 42, true);
        check(open.close(0, 42), "subsequent deliberate reopen gets fresh eligibility");

        check("tg://user?id=12345".equals(PurpleMarkdownLinks.userMention("12345")), "numeric mention canonical target");
        check(PurpleMarkdownLinks.userMention("0") == null, "zero mention remains plain text");
        check(PurpleMarkdownLinks.userMention("-12") == null, "negative mention remains plain text");
        check(PurpleMarkdownLinks.userMention("999999999999999999999") == null, "overflow mention remains plain text");
        check("https://t.me/sample".equals(PurpleMarkdownLinks.noUnderline("@sample")), "username canonical target");
        check(PurpleMarkdownLinks.noUnderline("@bad/name") == null, "interaction target cannot invent username link");
        for (String interaction : new String[] {"#tag", "$CASH", "/command", "video?3", "audio?8", "card:12345", "12345"}) {
            check(PurpleMarkdownLinks.noUnderline(interaction) == null, "interaction remains text: " + interaction);
        }
        for (String url : new String[] {"https://example.invalid/a?q=x", "mailto:sample@example.invalid", "tel:+12345", "tg://resolve?domain=sample"}) {
            check(url.equals(PurpleMarkdownLinks.noUnderline(url)), "real absolute target preserved: " + url);
        }
        check(PurpleMarkdownLinks.absolute("relative/path") == null, "generic relative target remains text");
        check(PurpleMarkdownLinks.absolute("https://example.invalid/\nnext") == null, "control target remains text");
        String person = PurpleMarkdown.render("Person", Collections.singletonList(new PurpleMarkdown.Span(
                0, 6, PurpleMarkdown.LINK, PurpleMarkdownLinks.userMention("12345"))));
        check("[Person](<tg://user?id=12345>)".equals(person), "genuine Markdown writer composes numeric mention");
        String username = PurpleMarkdown.render("@sample", Collections.singletonList(new PurpleMarkdown.Span(
                0, 7, PurpleMarkdown.LINK, PurpleMarkdownLinks.noUnderline("@sample"))));
        check("[@sample](<https://t.me/sample>)".equals(username), "genuine Markdown writer composes username mention");
        System.out.println("PASS " + checks + " focused production-helper checks");
    }
}
