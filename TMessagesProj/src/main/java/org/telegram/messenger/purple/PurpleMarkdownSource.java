package org.telegram.messenger.purple;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public final class PurpleMarkdownSource {
    public static final int LIMIT = 1024 * 1024;

    public static final class Candidate implements AutoCloseable {
        public final int kind;
        public final String media;
        private final byte[] bytes, normalized;
        private boolean closed;

        private Candidate(int kind, String media, byte[] bytes, byte[] normalized) {
            this.kind = kind;
            this.media = media;
            this.bytes = bytes.clone();
            this.normalized = normalized == null ? null : normalized.clone();
        }

        public synchronized byte[] bytes() {
            if (closed) throw new IllegalStateException("Markdown source released");
            return bytes.clone();
        }

        synchronized int memoryBytes() { return closed ? 0 : bytes.length + (normalized == null ? 0 : normalized.length); }

        public synchronized boolean matchesCompose(byte[] normalized) {
            return !closed && kind == 0 && normalized != null && Arrays.equals(this.normalized, normalized);
        }

        public synchronized boolean matchesFile(String media, long size) {
            return !closed && kind == 1 && this.media.equals(media) && size == bytes.length;
        }

        public synchronized String text() throws CharacterCodingException {
            if (closed) throw new IllegalStateException("Markdown source released");
            return decode(bytes);
        }

        @Override public synchronized void close() {
            closed = true;
            Arrays.fill(bytes, (byte) 0);
            if (normalized != null) Arrays.fill(normalized, (byte) 0);
        }
    }

    public static Candidate compose(String original, String normalized, byte[] normalizedBody, boolean styledInput) {
        if (original == null || normalized == null || normalizedBody == null || normalizedBody.length == 0
                || styledInput || original.equals(normalized) || original.length() > LIMIT || normalizedBody.length > LIMIT) return null;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(original));
            if (encoded.remaining() > LIMIT) return null;
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return new Candidate(0, "", bytes, normalizedBody);
        } catch (CharacterCodingException e) { return null; }
    }

    public static boolean markdown(String filename, String mime) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String type = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mkd")
                || name.endsWith(".mdown") || name.endsWith(".mkdn") || name.endsWith(".mdwn")
                || type.equals("text/markdown") || type.equals("text/x-markdown") || type.equals("text/x-web-markdown");
    }

    public static Candidate file(String filename, String mime, String media, long declaredBytes, byte[] original) {
        boolean markdown = markdown(filename, mime);
        if (!markdown || media == null || !media.matches("document_-?[1-9][0-9]*") || original == null
                || original.length > LIMIT || declaredBytes != original.length) return null;
        try {
            if (Long.parseLong(media.substring(9)) == 0) return null;
        } catch (NumberFormatException e) { return null; }
        return new Candidate(1, media, original, null);
    }

    static final class FileCapture implements AutoCloseable {
        final String filename, mime, media;
        final byte[] bytes;
        final java.util.BitSet observed;
        boolean refused;

        FileCapture(String filename, String mime, String media, long length) {
            if (!markdown(filename, mime) || length < 0 || length > LIMIT) throw new IllegalArgumentException("Markdown file capture size");
            this.filename = filename; this.mime = mime; this.media = media;
            bytes = new byte[(int) length]; observed = new java.util.BitSet(bytes.length);
        }

        void piece(long offset, byte[] value) {
            if (refused || offset < 0 || offset > bytes.length || value.length > bytes.length - offset) {
                refused = true; return;
            }
            int start = (int) offset;
            for (int i = 0; i < value.length; ++i) {
                if (observed.get(start + i) && bytes[start + i] != value[i]) { refused = true; return; }
            }
            System.arraycopy(value, 0, bytes, start, value.length); observed.set(start, start + value.length);
        }

        Candidate finish(String hash) throws Exception {
            if (refused || observed.nextClearBit(0) < bytes.length) return null;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder actual = new StringBuilder();
            for (byte value : digest) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
            return actual.toString().equals(hash) ? file(filename, mime, media, bytes.length, bytes) : null;
        }

        @Override public void close() { refused = true; Arrays.fill(bytes, (byte) 0); observed.clear(); }
    }

    public static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private PurpleMarkdownSource() {}
}
