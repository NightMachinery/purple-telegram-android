package org.telegram.messenger.purple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PurpleMarkdown {
    public static final int QUOTE = 0, PRE = 1, CODE = 2, LINK = 3;
    public static final int BOLD = 4, ITALIC = 5, STRIKE = 6, UNDERLINE = 7;

    public static final class Span {
        public final int start, end, kind;
        public final String data;

        public Span(int start, int end, int kind, String data) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.data = data == null ? "" : data;
        }
    }

    private PurpleMarkdown() {
    }

    public static String render(String text, List<Span> input) {
        ArrayList<Span> spans = new ArrayList<>();
        for (Span span : input) {
            int start = Math.max(0, span.start), end = Math.min(text.length(), span.end);
            if (span.kind >= LINK) {
                while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            }
            if (start >= end) continue;
            if (start > 0 && Character.isLowSurrogate(text.charAt(start)) && Character.isHighSurrogate(text.charAt(start - 1))) continue;
            if (end < text.length() && Character.isLowSurrogate(text.charAt(end)) && Character.isHighSurrogate(text.charAt(end - 1))) continue;
            Span normalized = new Span(start, end, span.kind, span.data);
            boolean duplicate = false;
            for (Span prior : spans) {
                if (prior.start == start && prior.end == end && prior.kind == span.kind && prior.data.equals(span.data)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) spans.add(normalized);
        }
        spans.sort(Comparator.comparingInt((Span s) -> s.start)
                .thenComparingInt(s -> s.kind <= CODE ? s.kind : LINK)
                .thenComparing((Span s) -> -s.end).thenComparingInt(s -> s.kind));
        return range(text, 0, text.length(), spans, new boolean[spans.size()], 0);
    }

    private static String range(String text, int from, int to, List<Span> spans, boolean[] active, int depth) {
        if (depth >= 64) return escape(text.substring(from, to));
        StringBuilder out = new StringBuilder();
        int position = from;
        while (position < to) {
            int selected = -1, next = to;
            for (int i = 0; i < spans.size(); i++) {
                Span span = spans.get(i);
                if (active[i] || span.end <= position || span.start >= to) continue;
                if (span.start <= position) {
                    selected = i;
                    break;
                }
                next = Math.min(next, span.start);
            }
            if (selected < 0) {
                out.append(escape(text.substring(position, next)));
                position = next;
                continue;
            }
            Span span = spans.get(selected);
            int end = Math.min(to, span.end);
            String raw = text.substring(position, end);
            active[selected] = true;
            String body = span.kind == CODE || span.kind == PRE ? raw : range(text, position, end, spans, active, depth + 1);
            active[selected] = false;
            String wrapped;
            switch (span.kind) {
                case BOLD: wrapped = "**" + body + "**"; break;
                case ITALIC: wrapped = "*" + body + "*"; break;
                case STRIKE: wrapped = "~~" + body + "~~"; break;
                case UNDERLINE: wrapped = "<u>" + body + "</u>"; break;
                case LINK: wrapped = "[" + body + "](<" + destination(span.data) + ">)"; break;
                case CODE:
                    if (raw.indexOf('\n') < 0) {
                        String ticks = repeat('`', longestTicks(raw) + 1);
                        boolean pad = raw.startsWith("`") || raw.endsWith("`") || raw.startsWith(" ") && raw.endsWith(" ") && !raw.trim().isEmpty();
                        wrapped = ticks + (pad ? " " : "") + raw + (pad ? " " : "") + ticks;
                        break;
                    }
                case PRE:
                    String fence = repeat('`', Math.max(3, longestTicks(raw) + 1));
                    String language = span.kind == PRE && span.data.matches("[A-Za-z0-9_+-]*") ? span.data : "";
                    wrapped = fence + language + "\n" + raw + (raw.endsWith("\n") ? "" : "\n") + fence;
                    break;
                case QUOTE: wrapped = "> " + body.replace("\n", "\n> "); break;
                default: wrapped = body;
            }
            if (span.kind == PRE || span.kind == QUOTE || span.kind == CODE && raw.indexOf('\n') >= 0) {
                if (position > from && text.charAt(position - 1) != '\n') out.append("\n\n");
                out.append(wrapped);
                if (end < to && text.charAt(end) != '\n') out.append("\n\n");
                else out.append('\n');
            } else {
                out.append(wrapped);
            }
            position = end;
        }
        return out.toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') out.append("&amp;");
            else if (c == '<') out.append("&lt;");
            else if (c == '>') out.append("&gt;");
            else {
                if ("\\`*_{}[]()#+-.!|~".indexOf(c) >= 0) out.append('\\');
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String destination(String url) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= 32 || c == '<' || c == '>' || c == '\\' || c == 127) {
                out.append('%');
                String hex = Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT);
                if (hex.length() < 2) out.append('0');
                out.append(hex);
            } else out.append(c);
        }
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

    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }
}
