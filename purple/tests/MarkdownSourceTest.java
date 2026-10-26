package org.telegram.messenger.purple;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class MarkdownSourceTest {
    private static int checks;

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        byte[] body = bytes("verified normalized body");
        PurpleMarkdownSource.Candidate compose = PurpleMarkdownSource.compose("**A**\r\n", "A", body, false);
        check(compose != null && compose.kind == 0, "actual normalized compose source");
        check(compose.text().equals("**A**\r\n"), "compose bytes retain syntax and line endings");
        check(compose.matchesCompose(body), "bind only its normalized body");
        check(!compose.matchesCompose(bytes("another body")), "changed body refuses source");
        body[0] = 0;
        check(compose.matchesCompose(bytes("verified normalized body")), "capture detaches mutable normalized bytes");
        byte[] escaped = compose.bytes();
        escaped[0] = 0;
        check(compose.text().equals("**A**\r\n"), "callers cannot rewrite source bytes");
        check(!compose.matchesFile("document_1", compose.bytes().length), "compose cannot become file provenance");
        check(PurpleMarkdownSource.compose("received text", "received text", bytes("body"), false) == null, "ordinary unchanged text has no inferred Markdown source");
        check(PurpleMarkdownSource.compose("**A**", "A", bytes("body"), true) == null, "unrepresented styled input refuses source");
        check(PurpleMarkdownSource.compose("**A**", "A", null, false) == null, "missing normalized preimage");
        check(PurpleMarkdownSource.compose("\ud800", "A", bytes("body"), false) == null, "malformed compose UTF16 cannot silently change bytes");
        byte[] original = new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '#', ' ', 'A', '\r', '\n'};
        PurpleMarkdownSource.Candidate file = PurpleMarkdownSource.file("original.MD", "text/plain", "document_17", original.length, original);
        check(file != null && file.kind == 1 && file.matchesFile("document_17", original.length), "original acquired Markdown document");
        check(Arrays.equals(file.bytes(), original), "file BOM and CRLF are exact");
        original[3] = 0;
        check(file.text().equals("\ufeff# A\r\n"), "file bytes detached from producer buffer");
        check(!file.matchesFile("document_18", original.length), "same size different document refuses");
        check(!file.matchesFile("document_17", original.length + 1), "changed file size refuses");
        check(!file.matchesCompose(bytes("body")), "file cannot become compose provenance");
        check(PurpleMarkdownSource.file("document.txt", "text/plain", "document_17", 1, bytes("A")) == null, "unlabelled received text is not a Markdown file");
        check(PurpleMarkdownSource.file("document.txt", "TEXT/MARKDOWN; charset=utf-8", "document_17", 1, bytes("A")) != null, "explicit Markdown media type");
        check(PurpleMarkdownSource.file("a.md", "", "document_17", 2, bytes("A")) == null, "incomplete acquired bytes refuse");
        check(PurpleMarkdownSource.file("a.md", "", "document_0", 1, bytes("A")) == null, "missing document id refuses");
        check(PurpleMarkdownSource.file("a.md", "", "document_9223372036854775808", 1, bytes("A")) == null, "overflow document id refuses");
        check(PurpleMarkdownSource.file("a.md", "", "document_-17", 0, new byte[0]) != null, "nonzero signed document id and genuine empty file");
        check(PurpleMarkdownSource.file("a.md", "", "document_17", PurpleMarkdownSource.LIMIT + 1L, new byte[PurpleMarkdownSource.LIMIT + 1]) == null, "bounded original file source");
        PurpleMarkdownSource.Candidate malformed = PurpleMarkdownSource.file("a.md", "", "document_17", 1, new byte[]{(byte) 0xff});
        check(malformed != null, "original invalid UTF8 bytes can be retained exactly");
        boolean refused = false;
        try { malformed.text(); } catch (CharacterCodingException expected) { refused = true; }
        check(refused, "clipboard decoding never substitutes invalid original bytes");
        byte[] acquired = bytes("# actual\r\n");
        StringBuilder hash = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(acquired)) hash.append(String.format("%02x", value & 255));
        PurpleMarkdownSource.FileCapture capture = new PurpleMarkdownSource.FileCapture("a.md", "", "document_1", acquired.length);
        capture.piece(4, Arrays.copyOfRange(acquired, 4, acquired.length));
        check(capture.finish(hash.toString()) == null, "holes cannot establish source");
        capture.piece(0, Arrays.copyOfRange(acquired, 0, 4));
        check(Arrays.equals(capture.finish(hash.toString()).bytes(), acquired), "out of order verified bytes preserve exact source");
        check(capture.finish("wrong digest") == null, "source must agree with validated staging hash");
        capture.piece(0, new byte[]{'!'});
        check(capture.finish(hash.toString()) == null, "conflicting overlap refuses original source");
        capture.close();
        check(capture.finish(hash.toString()) == null, "closed capture cannot revive");
        System.out.println("MARKDOWN_SOURCE_CHECKS=" + checks);
    }
}
