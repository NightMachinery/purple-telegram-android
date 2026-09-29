package org.telegram.messenger.purple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PurpleAccountSyncCore {
    private static final String SPACE = "sp-" + "a".repeat(26);
    private static final String OTHER_SPACE = "sp-" + "e".repeat(26);
    private static final String INSTALL_A = "in-" + "a".repeat(26);
    private static final String INSTALL_B = "in-" + "a".repeat(25) + "e";
    private static final byte[] ROOT = "version = 1\nname = 'root'\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHILD = "version = 1\nname = 'child'\n"
            .getBytes(StandardCharsets.UTF_8);

    private static final class RawReply {
        final byte[] record;
        final byte[] state;
        final byte[] configText;
        final String metadataJson;

        private RawReply(byte[] record, byte[] state, byte[] configText,
                String metadataJson) {
            this.record = record;
            this.state = state;
            this.configText = configText;
            this.metadataJson = metadataJson;
        }
    }

    private static native RawReply buildConfigRecordNative(byte[] text,
            byte[][] parents, String space, String install, String device,
            String platform, String app, long seq, long at);
    private static native RawReply buildConfigAcknowledgementNative(byte[] text,
            byte[] remoteRecord, String space, String install, String device,
            String platform, String app, long seq, long at);
    private static native RawReply inspectConfigRecordNative(byte[] record);

    private static void expect(RawReply reply, String status, String error) {
        if (reply == null || !reply.metadataJson.contains("\"status\":\"" + status + "\"")
                || !reply.metadataJson.contains("\"error\":\"" + error + "\"")) {
            throw new AssertionError(reply == null ? "null" : reply.metadataJson);
        }
    }

    private static String key(RawReply reply) {
        final Matcher matcher = Pattern.compile("\\\"key\\\":\\\"([^\\\"]+)\\\"")
                .matcher(reply.metadataJson);
        if (!matcher.find()) {
            throw new AssertionError(reply.metadataJson);
        }
        return matcher.group(1);
    }

    private static String arrayField(RawReply reply, String name) {
        final Matcher matcher = Pattern.compile("\"" + name + "\":(\\[[^\\]]*\\])")
                .matcher(new String(reply.record, StandardCharsets.UTF_8));
        if (!matcher.find()) {
            throw new AssertionError(name);
        }
        return matcher.group(1);
    }

    private static byte[] newerRecord(RawReply remote) throws Exception {
        final byte[] newerText = "version = 2\nname = 'child'\n"
                .getBytes(StandardCharsets.UTF_8);
        final String fingerprint = newerText.length + ":"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(newerText));
        String json = new String(remote.record, StandardCharsets.UTF_8)
                .replace("\"schema\":1", "\"schema\":2")
                .replace("version = 1", "version = 2")
                .replace(key(remote), "2." + fingerprint);
        final int payloadStart = json.indexOf("\"payload\":") + 10;
        final int payloadEnd = json.indexOf(",\"payload_sha256\"");
        if (payloadStart < 10 || payloadEnd < payloadStart) {
            throw new AssertionError(json);
        }
        final String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        json.substring(payloadStart, payloadEnd)
                                .getBytes(StandardCharsets.UTF_8)));
        json = json.replaceFirst("\"payload_sha256\":\"[0-9a-f]{64}\"",
                "\"payload_sha256\":\"" + hash + "\"");
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        final RawReply root = buildConfigRecordNative(ROOT, new byte[0][],
                SPACE, INSTALL_A, "phone", "android", "Purple", 1, 0);
        expect(root, "Valid", "None");
        final RawReply remote = buildConfigRecordNative(CHILD,
                new byte[][] { root.record }, SPACE, INSTALL_A,
                "phone", "android", "Purple", 2, 0);
        expect(remote, "Valid", "None");
        final RawReply acknowledged = buildConfigAcknowledgementNative(CHILD,
                remote.record, SPACE, INSTALL_B, "desktop", "macos", "Purple", 1, 0);
        expect(acknowledged, "Valid", "None");
        if (!key(remote).equals(key(acknowledged))
                || !key(remote).startsWith("2.")
                || Arrays.equals(remote.record, acknowledged.record)) {
            throw new AssertionError("acknowledgement changed the version");
        }
        final RawReply inspected = inspectConfigRecordNative(acknowledged.record);
        expect(inspected, "Valid", "None");
        if (!key(inspected).equals(key(remote))
                || !Arrays.equals(inspected.configText, CHILD)
                || !arrayField(remote, "parents").equals(arrayField(acknowledged, "parents"))
                || !arrayField(remote, "lineage").equals(arrayField(acknowledged, "lineage"))) {
            throw new AssertionError("acknowledgement did not round trip");
        }
        final byte[] newer = newerRecord(remote);
        expect(inspectConfigRecordNative(newer), "NewerSchema", "None");
        final RawReply newerAcknowledgement = buildConfigAcknowledgementNative(
                "version = 2\nname = 'child'\n".getBytes(StandardCharsets.UTF_8),
                newer, SPACE, INSTALL_B, "desktop", "macos", "Purple", 2, 0);
        expect(newerAcknowledgement, "Invalid", "RemotePayload");
        if (!newerAcknowledgement.metadataJson.contains(
                "\"payloadStatus\":\"NewerSchema\"")) {
            throw new AssertionError(newerAcknowledgement.metadataJson);
        }
        expect(buildConfigAcknowledgementNative(ROOT, remote.record, SPACE,
                INSTALL_B, "desktop", "macos", "Purple", 2, 0),
                "Invalid", "TextMismatch");
        expect(buildConfigAcknowledgementNative(CHILD, remote.record, OTHER_SPACE,
                INSTALL_B, "desktop", "macos", "Purple", 2, 0),
                "Invalid", "RemoteSpace");
        expect(buildConfigAcknowledgementNative(CHILD, new byte[] { 1 }, SPACE,
                INSTALL_B, "desktop", "macos", "Purple", 2, 0),
                "Invalid", "RemoteEnvelope");
        final byte[] wrongStream = new String(remote.record, StandardCharsets.UTF_8)
                .replace("\"stream\":\"config\"",
                        "\"stream\":\"library\"")
                .getBytes(StandardCharsets.UTF_8);
        expect(buildConfigAcknowledgementNative(CHILD, wrongStream, SPACE,
                INSTALL_B, "desktop", "macos", "Purple", 2, 0),
                "Invalid", "RemotePayload");
        final byte[] noncanonical = Arrays.copyOf(remote.record,
                remote.record.length + 1);
        noncanonical[noncanonical.length - 1] = ' ';
        expect(buildConfigAcknowledgementNative(CHILD, noncanonical, SPACE,
                INSTALL_B, "desktop", "macos", "Purple", 2, 0),
                "Invalid", "RemoteCanonical");
        expect(buildConfigAcknowledgementNative(CHILD, remote.record, SPACE,
                INSTALL_B, "desktop", "macos", "Purple", 0, 0),
                "Invalid", "Envelope");
        System.out.println("account sync acknowledgement JNI smoke passed");
    }
}
