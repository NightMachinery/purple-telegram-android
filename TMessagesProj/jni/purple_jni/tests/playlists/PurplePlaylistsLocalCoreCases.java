package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PurplePlaylistsLocalCoreCases {
    private static int checks;
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private static void check(boolean condition, String reason) {
        ++checks;
        if (!condition) throw new AssertionError(reason);
    }
    private static byte[] valid(PurplePlaylistsLocalCore.Result result) {
        check(result.valid(), "valid local state");
        check(result.status == PurplePlaylistsLocalCore.Status.Valid, "typed valid local state");
        check(result.error.equals("None") && result.playlistError.equals("None"), "no false diagnostics");
        check(result.canonicalBytes() != null, "valid state has bytes");
        return result.canonicalBytes();
    }
    private static void refused(PurplePlaylistsLocalCore.Result result, PurplePlaylistsLocalCore.Status status) {
        check(!result.valid(), "input refused");
        check(result.status == status, "typed refusal");
        check(result.canonicalBytes() == null, "refusal cannot replace state");
    }
    public static void main(String[] args) {
        byte[] defaults = valid(PurplePlaylistsLocalCore.defaults(11));
        check(text(defaults).equals("{\"format\":1,\"sync\":true}"), "explicit shared defaults");
        check(Arrays.equals(defaults, valid(PurplePlaylistsLocalCore.canonicalize(bytes("{}"), 11))),
                "empty object follows shared default contract");
        for (byte[] bad : new byte[][] {null, bytes(""), bytes("null"), bytes("[]"), bytes("{"),
                bytes("{\"sync\":true,\"sync\":false}"), bytes("{\"sync\":\"false\"}"),
                bytes("{\"future\":1.5}"), bytes("{\"recent_use\":{\"bad\":1}}"),
                new byte[] {(byte) 0xc3, 0x28}}) {
            refused(PurplePlaylistsLocalCore.canonicalize(bad, 11), PurplePlaylistsLocalCore.Status.Invalid);
            refused(PurplePlaylistsLocalCore.setSync(bad, true, 11), PurplePlaylistsLocalCore.Status.Invalid);
        }
        for (long account : new long[] {0, -1}) {
            PurplePlaylistsLocalCore.Result result = PurplePlaylistsLocalCore.defaults(account);
            refused(result, PurplePlaylistsLocalCore.Status.Invalid);
            check(result.error.equals("InvalidSelfUserId"), "account diagnostic");
        }
        valid(PurplePlaylistsLocalCore.defaults(Long.MAX_VALUE));
        byte[] newer = bytes("{\"format\":2,\"future\":true}");
        refused(PurplePlaylistsLocalCore.canonicalize(newer, 11), PurplePlaylistsLocalCore.Status.NewerFormat);
        refused(PurplePlaylistsLocalCore.setSync(newer, false, 11), PurplePlaylistsLocalCore.Status.NewerFormat);
        String raw = "{\"sync\":false,\"chat\":\"user:11\",\"unsynced_since\":2,"
                + "\"status\":{\"result\":\"wait\",\"last_step_at_ms\":4,\"future_status\":{\"v\":3}},"
                + "\"due_seen\":{\"pl-aaaaaaaaaaaaaaaa\":{\"deletion_stamp\":5,\"first_seen_at_ms\":6,\"note\":\"kept\"}},"
                + "\"recent_use\":{\"pl-bbbbbbbbbbbbbbbb\":7},"
                + "\"future\":{\"title\":\"\ud83c\udfb5 موسيقى\",\"values\":[true,null,9]}}";
        byte[] original = bytes(raw), preserved = original.clone();
        byte[] canonical = valid(PurplePlaylistsLocalCore.canonicalize(original, 11));
        check(text(canonical).contains("\"chat\":\"self\""), "local chat normalizes with captured account");
        check(text(canonical).contains("\ud83c\udfb5 موسيقى"), "supplementary and RTL unknown text retained");
        check(text(canonical).contains("\"future_status\":{\"v\":3}"), "nested status unknown retained");
        check(text(canonical).contains("\"note\":\"kept\""), "nested due-seen unknown retained");
        check(Arrays.equals(canonical, valid(PurplePlaylistsLocalCore.canonicalize(canonical, 11))),
                "local canonicalization idempotent");
        byte[] enabled = valid(PurplePlaylistsLocalCore.setSync(original, true, 11));
        check(text(enabled).equals(text(canonical).replace("\"sync\":false", "\"sync\":true")),
                "sync edit preserves all bookkeeping and unknown data");
        check(Arrays.equals(canonical, valid(PurplePlaylistsLocalCore.setSync(enabled, false, 11))),
                "sync toggle roundtrip retains state");
        check(Arrays.equals(original, preserved), "caller bytes never modified");
        PurplePlaylistsLocalCore.Result immutable = PurplePlaylistsLocalCore.canonicalize(original, 11);
        byte[] exposed = immutable.canonicalBytes(); exposed[0] = 0;
        check(immutable.canonicalBytes()[0] == '{', "returned bytes cannot mutate result");
        byte[] large = new byte[25 * 1024 * 1024]; Arrays.fill(large, (byte) ' ');
        System.arraycopy(defaults, 0, large, 0, defaults.length);
        PurplePlaylistsLocalCore.Result largeResult = PurplePlaylistsLocalCore.canonicalize(large, 11);
        check(largeResult.valid(), "local state crosses settings and edit input budgets");
        check(Arrays.equals(defaults, largeResult.canonicalBytes()), "large state canonical output");
        System.out.println("PLAYLIST_LOCAL_BRIDGE_CHECKS=" + checks);
        System.out.println("PLAYLIST_LOCAL_BRIDGE_OK");
    }
}
