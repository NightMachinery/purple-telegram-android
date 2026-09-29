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
    private static native RawReply confirmConfigReadBackNative(byte[] state,
            byte[] stagedRecord, String currentDevice, int observationKind,
            byte[] observedRecord);

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

    private static String field(RawReply reply, String name) {
        final Matcher matcher = Pattern.compile("\\\"" + name
                + "\\\":\\\"([^\\\"]*)\\\"").matcher(reply.metadataJson);
        if (!matcher.find()) {
            throw new AssertionError(reply.metadataJson);
        }
        return matcher.group(1);
    }

    private static byte[] state(long seq, long pendingSeq, long confirmedSeq,
            String ownHash, String base, String pending, String lineage,
            String equiv) {
        return ("{\"version\":1,\"install\":\"" + INSTALL_A
                + "\",\"created_device\":\"phone\",\"space\":\"" + SPACE
                + "\",\"streams\":{\"config\":{\"seq\":" + seq
                + ",\"pending_seq\":" + pendingSeq
                + ",\"confirmed_seq\":" + confirmedSeq
                + ",\"own_hash\":\"" + ownHash
                + "\",\"base\":\"" + base
                + "\",\"base_lineage\":" + lineage
                + ",\"equiv\":" + equiv
                + ",\"pending\":\"" + pending
                + "\",\"seen_seq\":{\"" + INSTALL_B + "\":7}},"
                + "\"library\":{\"seq\":0,\"pending_seq\":0,"
                + "\"confirmed_seq\":0,\"own_hash\":\"\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void expectConfirmation(RawReply reply, boolean changed,
            String verdict, String base, String lineage, String equiv) {
        expect(reply, "Valid", "None");
        if (!reply.metadataJson.contains("\"changed\":" + changed)
                || !reply.metadataJson.contains("\"verdict\":\"" + verdict + "\"")
                || reply.state == null) {
            throw new AssertionError(reply.metadataJson);
        }
        final String saved = new String(reply.state, StandardCharsets.UTF_8);
        if (!saved.contains("\"base\":\"" + base + "\"")
                || !saved.contains("\"base_lineage\":" + lineage)
                || !saved.contains("\"equiv\":" + equiv)
                || !saved.contains("\"" + INSTALL_B + "\":7")) {
            throw new AssertionError(saved);
        }
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

        final byte[] firstState = state(1, 1, 0, field(root, "payloadHash"),
                "", key(root), "[]", "[]");
        final RawReply firstConfirmed = confirmConfigReadBackNative(firstState,
                root.record, "phone", 2, root.record);
        expectConfirmation(firstConfirmed, true, "NoClone", key(root), "[]", "[]");
        if (!new String(firstConfirmed.state, StandardCharsets.UTF_8)
                .contains("\"pending_seq\":0")) {
            throw new AssertionError("first pending sequence survived");
        }

        final RawReply oldEquivalent = buildConfigRecordNative(ROOT,
                new byte[][] { root.record }, SPACE, INSTALL_A,
                "phone", "android", "Purple", 77, 0);
        expect(oldEquivalent, "Valid", "None");
        final String oldEquiv = "[\"" + key(oldEquivalent) + "\"]";
        final byte[] changedState = state(2, 2, 1, field(remote, "payloadHash"),
                key(root), key(remote), "[]", oldEquiv);
        final RawReply changedConfirmed = confirmConfigReadBackNative(
                changedState, remote.record, "phone", 2, remote.record);
        expectConfirmation(changedConfirmed, true, "NoClone", key(remote),
                arrayField(remote, "lineage"), "[]");
        final RawReply sameFingerprint = buildConfigRecordNative(CHILD,
                new byte[][] { remote.record }, SPACE, INSTALL_A,
                "phone", "android", "Purple", 77, 0);
        expect(sameFingerprint, "Valid", "None");
        final RawReply sameVersion = buildConfigAcknowledgementNative(CHILD,
                remote.record, SPACE, INSTALL_A, "phone", "android", "Purple", 3, 0);
        expect(sameVersion, "Valid", "None");
        final String retainedEquiv = "[\"" + key(sameFingerprint) + "\"]";
        final byte[] sameState = state(3, 3, 2, field(sameVersion, "payloadHash"),
                key(remote), key(remote), arrayField(remote, "lineage"),
                retainedEquiv);
        expectConfirmation(confirmConfigReadBackNative(sameState,
                sameVersion.record, "phone", 2, sameVersion.record),
                true, "NoClone", key(remote), arrayField(remote, "lineage"),
                retainedEquiv);

        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "phone", 2, root.record), false, "NoClone",
                key(root), "[]", oldEquiv);
        final RawReply wrongHash = buildConfigRecordNative(ROOT,
                new byte[0][], SPACE, INSTALL_A, "phone", "android", "Purple", 2, 0);
        expect(wrongHash, "Valid", "None");
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "phone", 2, wrongHash.record), false,
                "HashMismatch", key(root), "[]", oldEquiv);
        final RawReply higher = buildConfigRecordNative(ROOT,
                new byte[0][], SPACE, INSTALL_A, "phone", "android", "Purple", 3, 0);
        expect(higher, "Valid", "None");
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "phone", 2, higher.record), false,
                "RemoteAhead", key(root), "[]", oldEquiv);
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "", 2, remote.record), false,
                "DeviceMismatch", key(root), "[]", oldEquiv);
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "other-phone", 2, remote.record), false,
                "DeviceMismatch", key(root), "[]", oldEquiv);

        expect(confirmConfigReadBackNative(changedState, null,
                "phone", 2, remote.record), "Invalid", "NullInput");
        expect(confirmConfigReadBackNative(changedState, new byte[] { 1 },
                "phone", 2, remote.record), "Invalid", "StagedEnvelope");
        expect(confirmConfigReadBackNative(changedState, wrongStream,
                "phone", 2, remote.record), "Invalid", "StagedPayload");
        expect(confirmConfigReadBackNative(changedState, noncanonical,
                "phone", 2, remote.record), "Invalid", "StagedCanonical");
        expect(confirmConfigReadBackNative(changedState, root.record,
                "phone", 2, remote.record), "Invalid", "StagedMismatch");
        expect(confirmConfigReadBackNative(changedState, acknowledged.record,
                "phone", 2, remote.record), "Invalid", "StagedMismatch");
        final byte[] wrongStagedHash = state(2, 2, 1,
                field(root, "payloadHash"), key(root), key(remote), "[]", "[]");
        expect(confirmConfigReadBackNative(wrongStagedHash, remote.record,
                "phone", 2, remote.record), "Invalid", "StagedMismatch");
        final byte[] wrongPendingKey = state(2, 2, 1,
                field(remote, "payloadHash"), key(root), key(root), "[]", "[]");
        expect(confirmConfigReadBackNative(wrongPendingKey, remote.record,
                "phone", 2, remote.record), "Invalid", "StagedMismatch");
        expect(confirmConfigReadBackNative(changedState, remote.record,
                "phone", 2, noncanonical), "Invalid", "ObservationCanonical");
        expect(confirmConfigReadBackNative(changedState, remote.record,
                "phone", 2, acknowledged.record), "Invalid", "ObservationIdentity");
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "phone", 1, null), false, "NoClone",
                key(root), "[]", oldEquiv);
        System.out.println("account sync JNI smoke passed");
    }
}
