package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PurplePlaylistsCoreCases {
    private static final String EMPTY = "{\"format\":1,\"playlists\":{},\"purged\":{}}";
    private static final String ID = "pl-aaaaaaaaaaaaaaaa";
    private static int checks;

    private static void check(boolean condition, String reason) {
        ++checks;
        if (!condition) throw new AssertionError(reason);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }

    private static byte[] library(String title, long stamp, String peer, String document) {
        return bytes("{\"format\":1,\"playlists\":{\"" + ID + "\":{\"title\":[\"" + title + "\"," + stamp
                + "],\"deleted\":[false,1],\"songs\":{\"" + document
                + "\":[{\"peer\":\"" + peer + "\",\"msg\":7,\"meta\":{\"title\":\"Song\",\"perf\":\"Artist\",\"dur\":30},\"since\":1},1]}}},\"purged\":{}}");
    }

    private static byte[] canonical(byte[] input) {
        PurplePlaylistsCore.Result result = PurplePlaylistsCore.canonicalize(input, 11);
        check(result.valid(), "canonicalization valid");
        check(result.status == PurplePlaylistsCore.Status.Valid, "typed valid status");
        check(result.error.equals("None"), "no error on success");
        check(result.input == PurplePlaylistsCore.Input.None, "success has no refused input");
        check(result.contains == null, "canonicalization has no contains decision");
        check(result.canonicalBytes() != null, "canonical bytes returned");
        return result.canonicalBytes();
    }

    private static void refused(PurplePlaylistsCore.Result result, PurplePlaylistsCore.Status status,
            PurplePlaylistsCore.Input input, String reason) {
        check(!result.valid(), reason + " refused");
        check(result.status == status, reason + " typed status");
        check(result.input == input, reason + " input provenance");
        check(result.canonicalBytes() == null, reason + " no replacement library");
        check(result.contains == null, reason + " no contains authority");
    }

    private static void contained(byte[] outer, byte[] inner, boolean expected) {
        PurplePlaylistsCore.Result result = PurplePlaylistsCore.contains(outer, inner, 11);
        check(result.valid(), "contains inputs valid");
        check(result.input == PurplePlaylistsCore.Input.None, "contains no refused input");
        check(result.canonicalBytes() == null, "contains does not supply replacement bytes");
        check(result.contains != null && result.contains == expected, "shared contains decision");
    }

    public static void main(String[] args) {
        check(text(canonical(bytes(" { \"purged\":{}, \"playlists\":{}, \"format\":1 } \n"))).equals(EMPTY),
                "canonical JSON is not the original formatting");
        check(text(canonical(bytes(EMPTY.replace("\"format\":1", "\"format\":1.0")))).equals(EMPTY),
                "exact integer decimal spelling follows shared JSON normalization");
        byte[] unicode = library("\ud83c\udfb5 موسيقى", 1, "self", "18446744073709551615");
        byte[] normalized = canonical(unicode);
        check(text(normalized).contains("\ud83c\udfb5 موسيقى"), "UTF-8 supplementary and RTL title retained");
        check(text(normalized).contains("18446744073709551615"), "unsigned document id does not pass through a Java long");
        check(Arrays.equals(normalized, canonical(library("\ud83c\udfb5 موسيقى", 1, "user:11", "18446744073709551615"))),
                "account user token normalizes to self");
        check(Arrays.equals(normalized, canonical(normalized)), "canonicalization idempotent");
        PurplePlaylistsCore.Result immutable = PurplePlaylistsCore.canonicalize(unicode, 11);
        byte[] exposed = immutable.canonicalBytes();
        exposed[0] = 0;
        check(immutable.canonicalBytes()[0] == '{', "caller cannot change retained canonical bytes");
        check(Arrays.equals(unicode, library("\ud83c\udfb5 موسيقى", 1, "self", "18446744073709551615")),
                "canonicalization does not modify caller bytes");

        String[] invalid = {"", "null", "[]", "{}", "{\"format\":1,\"format\":1,\"playlists\":{},\"purged\":{}}",
                "{\"format\":1.5,\"playlists\":{},\"purged\":{}}", EMPTY.replace("\"purged\":{}", "\"unknown\":0,\"purged\":{}"),
                EMPTY.replace("\"format\":1", "\"format\":0"),
                text(library("Bad", 1, "self", "18446744073709551616")),
                text(library("Bad", 4503599627370497L, "self", "1")),
                text(library("Bad", 1, "self", "01"))};
        for (String value : invalid) {
            refused(PurplePlaylistsCore.canonicalize(bytes(value), 11), PurplePlaylistsCore.Status.Invalid,
                    PurplePlaylistsCore.Input.Local, "invalid library");
        }
        refused(PurplePlaylistsCore.canonicalize(null, 11), PurplePlaylistsCore.Status.Invalid,
                PurplePlaylistsCore.Input.Local, "null input");
        refused(PurplePlaylistsCore.canonicalize(new byte[]{(byte) 0xc3, 0x28}, 11), PurplePlaylistsCore.Status.Invalid,
                PurplePlaylistsCore.Input.Local, "invalid UTF-8");
        for (long self : new long[]{-1, 0}) {
            PurplePlaylistsCore.Result result = PurplePlaylistsCore.canonicalize(bytes(EMPTY), self);
            check(result.error.equals("InvalidSelfUserId"), "invalid account has named core error");
            check(result.canonicalBytes() == null && result.contains == null, "invalid account has no data authority");
        }
        check(PurplePlaylistsCore.canonicalize(bytes(EMPTY), Long.MAX_VALUE).valid(),
                "full positive Java long account bound is valid in the shared core");
        byte[] newer = bytes("{\"format\":2}");
        refused(PurplePlaylistsCore.canonicalize(newer, 11), PurplePlaylistsCore.Status.NewerFormat,
                PurplePlaylistsCore.Input.Local, "newer local format");
        refused(PurplePlaylistsCore.join(bytes(EMPTY), newer, 11), PurplePlaylistsCore.Status.NewerFormat,
                PurplePlaylistsCore.Input.Remote, "newer remote format");
        refused(PurplePlaylistsCore.join(newer, bytes(EMPTY), 11), PurplePlaylistsCore.Status.NewerFormat,
                PurplePlaylistsCore.Input.Local, "newer local join");
        refused(PurplePlaylistsCore.join(bytes(EMPTY), null, 11), PurplePlaylistsCore.Status.Invalid,
                PurplePlaylistsCore.Input.Remote, "null remote join");
        refused(PurplePlaylistsCore.contains(bytes(EMPTY), bytes("{}"), 11), PurplePlaylistsCore.Status.Invalid,
                PurplePlaylistsCore.Input.Remote, "invalid inner cannot prove contains");
        refused(PurplePlaylistsCore.contains(bytes("{}"), bytes(EMPTY), 11), PurplePlaylistsCore.Status.Invalid,
                PurplePlaylistsCore.Input.Local, "invalid outer cannot prove contains");

        byte[] a = library("Alpha", 1, "self", "1");
        byte[] b = library("Beta", 2, "self", "1");
        byte[] savedA = a.clone(), savedB = b.clone();
        PurplePlaylistsCore.Result ab = PurplePlaylistsCore.join(a, b, 11);
        PurplePlaylistsCore.Result ba = PurplePlaylistsCore.join(b, a, 11);
        check(ab.valid() && ba.valid(), "join inputs valid");
        check(ab.contains == null && ba.contains == null, "join is not a contains response");
        check(Arrays.equals(ab.canonicalBytes(), canonical(b)), "greater shared stamp wins");
        check(Arrays.equals(ab.canonicalBytes(), ba.canonicalBytes()), "shared join commutative");
        check(Arrays.equals(PurplePlaylistsCore.join(a, a, 11).canonicalBytes(), canonical(a)), "join idempotent");
        contained(ab.canonicalBytes(), a, true);
        contained(a, b, false);
        contained(ab.canonicalBytes(), b, true);
        check(Arrays.equals(a, savedA) && Arrays.equals(b, savedB), "join and contains leave both inputs untouched");
        byte[] tied = library("Zulu", 1, "self", "1");
        check(Arrays.equals(PurplePlaylistsCore.join(a, tied, 11).canonicalBytes(), canonical(tied)),
                "equal-stamp canonical value tie uses shared core");
        byte[] purged = bytes("{\"format\":1,\"playlists\":{},\"purged\":{\"" + ID + "\":1}}");
        check(Arrays.equals(PurplePlaylistsCore.join(b, purged, 11).canonicalBytes(), canonical(purged)),
                "purged ids dominate visible playlists");
        contained(purged, b, true);

        byte[] large = new byte[25 * 1024 * 1024];
        Arrays.fill(large, (byte) ' ');
        System.arraycopy(bytes(EMPTY), 0, large, 0, bytes(EMPTY).length);
        PurplePlaylistsCore.Result largeResult = PurplePlaylistsCore.canonicalize(large, 11);
        check(largeResult.valid(), "local parse crosses both the settings envelope and edit budgets");
        check(text(largeResult.canonicalBytes()).equals(EMPTY), "large local library canonical bytes");
        check(large[large.length - 1] == ' ', "large input retained");
        System.out.println("PLAYLIST_LIBRARY_BRIDGE_CHECKS=" + checks);
        System.out.println("PLAYLIST_LIBRARY_BRIDGE_OK");
    }
}
