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
    private static native RawReply initializeLocalStateNative(
            String installId, String createdDeviceId, String spaceId);
    private static native RawReply reserveConfigRecordNative(
            byte[] state, byte[] canonicalOwnRecord);
    private static native RawReply confirmConfigReadBackNative(byte[] state,
            byte[] stagedRecord, String currentDevice, int observationKind,
            byte[] observedRecord, int messageId);
    private static native RawReply recordConfirmedOwnConfigMessageNative(
            byte[] state, int messageId, byte[] canonicalServerRecord);
    private static native RawReply checkOwnConfigMessageDeletionNative(
            byte[] state, int messageId, byte[] freshCanonicalServerRecord);
    private static native RawReply removeAbsentOwnConfigMessageNative(
            byte[] state, int messageId, int presenceKind);

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

    private static byte[] changedKeyRecord(RawReply record, String replacement)
            throws Exception {
        String json = new String(record.record, StandardCharsets.UTF_8)
                .replace(key(record), replacement);
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
                root.record, "phone", 2, root.record, 101);
        expectConfirmation(firstConfirmed, true, "NoClone", key(root), "[]", "[]");
        final String firstConfirmedJson = new String(firstConfirmed.state,
                StandardCharsets.UTF_8);
        if (!firstConfirmedJson.contains("\"pending_seq\":0")
                || !firstConfirmedJson.contains("\"message_id\":101")) {
            throw new AssertionError("confirmation did not atomically record its ID");
        }

        final RawReply oldEquivalent = buildConfigRecordNative(ROOT,
                new byte[][] { root.record }, SPACE, INSTALL_A,
                "phone", "android", "Purple", 77, 0);
        expect(oldEquivalent, "Valid", "None");
        final String oldEquiv = "[\"" + key(oldEquivalent) + "\"]";
        final byte[] changedState = state(2, 2, 1, field(remote, "payloadHash"),
                key(root), key(remote), "[]", oldEquiv);
        final RawReply changedConfirmed = confirmConfigReadBackNative(
                changedState, remote.record, "phone", 2, remote.record, 201);
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
                sameVersion.record, "phone", 2, sameVersion.record, 301),
                true, "NoClone", key(remote), arrayField(remote, "lineage"),
                retainedEquiv);

        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "phone", 2, root.record, 201), false,
                "NoClone", key(root), "[]", oldEquiv);
        final RawReply wrongHash = buildConfigRecordNative(ROOT,
                new byte[0][], SPACE, INSTALL_A, "phone", "android", "Purple", 2, 0);
        expect(wrongHash, "Valid", "None");
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "phone", 2, wrongHash.record, 201), false,
                "HashMismatch", key(root), "[]", oldEquiv);
        final RawReply higher = buildConfigRecordNative(ROOT,
                new byte[0][], SPACE, INSTALL_A, "phone", "android", "Purple", 3, 0);
        expect(higher, "Valid", "None");
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "phone", 2, higher.record, 201), false,
                "RemoteAhead", key(root), "[]", oldEquiv);
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "", 2, remote.record, 201), false,
                "DeviceMismatch", key(root), "[]", oldEquiv);
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "other-phone", 2, remote.record, 201), false,
                "DeviceMismatch", key(root), "[]", oldEquiv);

        expect(confirmConfigReadBackNative(changedState, null,
                "phone", 2, remote.record, 201), "Invalid", "NullInput");
        expect(confirmConfigReadBackNative(changedState, new byte[] { 1 },
                "phone", 2, remote.record, 201), "Invalid", "StagedEnvelope");
        expect(confirmConfigReadBackNative(changedState, wrongStream,
                "phone", 2, remote.record, 201), "Invalid", "StagedPayload");
        expect(confirmConfigReadBackNative(changedState, noncanonical,
                "phone", 2, remote.record, 201), "Invalid", "StagedCanonical");
        expect(confirmConfigReadBackNative(changedState, root.record,
                "phone", 2, remote.record, 201), "Invalid", "StagedMismatch");
        expect(confirmConfigReadBackNative(changedState, acknowledged.record,
                "phone", 2, remote.record, 201), "Invalid", "StagedMismatch");
        final byte[] wrongStagedHash = state(2, 2, 1,
                field(root, "payloadHash"), key(root), key(remote), "[]", "[]");
        expect(confirmConfigReadBackNative(wrongStagedHash, remote.record,
                "phone", 2, remote.record, 201), "Invalid", "StagedMismatch");
        final byte[] wrongPendingKey = state(2, 2, 1,
                field(remote, "payloadHash"), key(root), key(root), "[]", "[]");
        expect(confirmConfigReadBackNative(wrongPendingKey, remote.record,
                "phone", 2, remote.record, 201), "Invalid", "StagedMismatch");
        expect(confirmConfigReadBackNative(changedState, remote.record,
                "phone", 2, noncanonical, 201), "Invalid", "ObservationCanonical");
        expect(confirmConfigReadBackNative(changedState, remote.record,
                "phone", 2, acknowledged.record, 201), "Invalid", "ObservationIdentity");
        expectConfirmation(confirmConfigReadBackNative(changedState,
                remote.record, "phone", 1, null, 0), false, "NoClone",
                key(root), "[]", oldEquiv);
        final RawReply alteredEnvelope = buildConfigRecordNative(CHILD,
                new byte[][] { root.record }, SPACE, INSTALL_A,
                "phone", "android", "Other", 2, 0);
        expect(alteredEnvelope, "Valid", "None");
        if (!field(remote, "payloadHash").equals(field(alteredEnvelope,
                "payloadHash")) || Arrays.equals(remote.record,
                alteredEnvelope.record)) {
            throw new AssertionError("test needs distinct canonical envelopes");
        }
        expect(confirmConfigReadBackNative(changedState, remote.record,
                "phone", 2, alteredEnvelope.record, 201), "Invalid", "ReadBackMismatch");
        expect(confirmConfigReadBackNative(changedState, remote.record,
                "phone", 2, remote.record, 0), "Invalid", "InvalidMessageId");
        expect(confirmConfigReadBackNative(changedState, remote.record,
                "phone", 1, null, 201), "Invalid", "InvalidMessageId");
        final RawReply alteredRoot = buildConfigRecordNative(ROOT,
                new byte[0][], SPACE, INSTALL_A,
                "phone", "android", "Other", 1, 0);
        expect(alteredRoot, "Valid", "None");

        final RawReply initialized = initializeLocalStateNative(
                INSTALL_A, "phone", SPACE);
        expect(initialized, "Valid", "None");
        if (initialized.state == null
                || !initialized.metadataJson.contains("\"install\":\"" + INSTALL_A + "\"")
                || !initialized.metadataJson.contains("\"seq\":\"0\"")
                || !new String(initialized.state, StandardCharsets.UTF_8)
                        .contains("\"confirmed_seq\":0")) {
            throw new AssertionError(initialized.metadataJson);
        }
        final RawReply reserved = reserveConfigRecordNative(
                initialized.state, root.record);
        expect(reserved, "Valid", "None");
        final String reservedState = new String(reserved.state, StandardCharsets.UTF_8);
        if (!reserved.metadataJson.contains("\"seq\":\"1\"")
                || !reserved.metadataJson.contains("\"payloadHash\":\""
                        + field(root, "payloadHash") + "\"")
                || !reserved.metadataJson.contains("\"key\":\"" + key(root) + "\"")
                || !reservedState.contains("\"pending_seq\":1")
                || !reservedState.contains("\"pending\":\"" + key(root) + "\"")
                || !reservedState.contains("\"own_hash\":\""
                        + field(root, "payloadHash") + "\"")) {
            throw new AssertionError(reserved.metadataJson + " " + reservedState);
        }
        final RawReply superseded = reserveConfigRecordNative(reserved.state,
                remote.record);
        expect(superseded, "Valid", "None");
        if (!new String(superseded.state, StandardCharsets.UTF_8)
                .contains("\"pending\":\"" + key(remote) + "\"")) {
            throw new AssertionError("new pending key was not stored");
        }
        expect(initializeLocalStateNative("bad", "phone", SPACE),
                "Invalid", "InvalidId");
        expect(initializeLocalStateNative(INSTALL_A, "phone", "bad"),
                "Invalid", "InvalidId");
        expect(initializeLocalStateNative(INSTALL_A, "", SPACE),
                "Invalid", "InvalidDevice");
        expect(initializeLocalStateNative(INSTALL_A, "x".repeat(257), SPACE),
                "Invalid", "InvalidDevice");
        expect(initializeLocalStateNative(null, "phone", SPACE),
                "Invalid", "NullInput");
        final byte[] unboundState = new String(initialized.state,
                StandardCharsets.UTF_8)
                .replace("\"created_device\":\"phone\"",
                        "\"created_device\":\"\"")
                .getBytes(StandardCharsets.UTF_8);
        expect(reserveConfigRecordNative(unboundState, root.record),
                "Invalid", "InvalidDevice");
        expect(reserveConfigRecordNative(initialized.state, acknowledged.record),
                "Invalid", "RecordIdentity");
        final RawReply foreignSpace = buildConfigRecordNative(ROOT,
                new byte[0][], OTHER_SPACE, INSTALL_A,
                "phone", "android", "Purple", 1, 0);
        expect(foreignSpace, "Valid", "None");
        expect(reserveConfigRecordNative(initialized.state, foreignSpace.record),
                "Invalid", "RecordIdentity");
        final RawReply foreignDevice = buildConfigRecordNative(ROOT,
                new byte[0][], SPACE, INSTALL_A,
                "other-phone", "android", "Purple", 1, 0);
        expect(foreignDevice, "Valid", "None");
        expect(reserveConfigRecordNative(initialized.state, foreignDevice.record),
                "Invalid", "RecordIdentity");
        expect(reserveConfigRecordNative(initialized.state, remote.record),
                "Invalid", "RecordSequence");
        expect(reserveConfigRecordNative(initialized.state, noncanonical),
                "Invalid", "RecordCanonical");
        expect(reserveConfigRecordNative(initialized.state, new byte[] { 1 }),
                "Invalid", "RecordEnvelope");
        expect(reserveConfigRecordNative(initialized.state, wrongStream),
                "Invalid", "RecordPayload");
        final byte[] badHash = new String(root.record, StandardCharsets.UTF_8)
                .replace(field(root, "payloadHash"), "0".repeat(64))
                .getBytes(StandardCharsets.UTF_8);
        expect(reserveConfigRecordNative(initialized.state, badHash),
                "Invalid", "RecordEnvelope");
        expect(reserveConfigRecordNative(initialized.state,
                changedKeyRecord(root, key(remote))), "Invalid", "RecordPayload");
        final byte[] exhausted = state(9007199254740991L, 0,
                9007199254740991L, field(root, "payloadHash"),
                "", "", "[]", "[]");
        expect(reserveConfigRecordNative(exhausted, root.record),
                "Invalid", "SequenceExhausted");

        final RawReply recorded = recordConfirmedOwnConfigMessageNative(
                firstConfirmed.state, 101, root.record);
        expect(recorded, "Valid", "None");
        if (!recorded.metadataJson.contains("\"changed\":false")
                || !new String(recorded.state, StandardCharsets.UTF_8)
                        .contains("\"message_id\":101")) {
            throw new AssertionError(recorded.metadataJson);
        }
        final RawReply repeated = recordConfirmedOwnConfigMessageNative(
                recorded.state, 101, root.record);
        expect(repeated, "Valid", "None");
        if (!repeated.metadataJson.contains("\"changed\":false")) {
            throw new AssertionError(repeated.metadataJson);
        }
        final RawReply duplicate = recordConfirmedOwnConfigMessageNative(
                recorded.state, 102, root.record);
        expect(duplicate, "Valid", "None");
        expect(recordConfirmedOwnConfigMessageNative(recorded.state,
                101, alteredRoot.record), "Invalid", "IdConflict");
        expect(checkOwnConfigMessageDeletionNative(duplicate.state,
                101, root.record), "Invalid", "NotOlder");
        expect(checkOwnConfigMessageDeletionNative(duplicate.state,
                102, root.record), "Invalid", "NotOlder");
        expect(removeAbsentOwnConfigMessageNative(duplicate.state,
                102, 1), "Invalid", "NotOlder");
        final RawReply advanced = confirmConfigReadBackNative(
                state(2, 2, 1, field(remote, "payloadHash"), key(root),
                        key(remote), "[]", "[]"), remote.record,
                "phone", 2, remote.record, 201);
        expect(advanced, "Valid", "None");
        final String advancedJson = new String(advanced.state,
                StandardCharsets.UTF_8);
        final String ledgerJson = new String(duplicate.state,
                StandardCharsets.UTF_8);
        final Matcher ledgerEntries = Pattern.compile(
                "\"own_messages\":(\\[[^\\]]*\\])").matcher(ledgerJson);
        final Matcher currentEntry = Pattern.compile(
                "\"own_messages\":(\\[[^\\]]*\\])").matcher(advancedJson);
        if (!ledgerEntries.find() || !currentEntry.find()) {
            throw new AssertionError(ledgerJson);
        }
        final String joinedEntries = ledgerEntries.group(1).substring(0,
                ledgerEntries.group(1).length() - 1) + ","
                + currentEntry.group(1).substring(1);
        final byte[] advancedLedger = advancedJson.replace(
                currentEntry.group(1), joinedEntries)
                .getBytes(StandardCharsets.UTF_8);
        expect(checkOwnConfigMessageDeletionNative(advancedLedger,
                101, root.record), "Valid", "None");
        expect(checkOwnConfigMessageDeletionNative(advancedLedger,
                102, alteredRoot.record), "Invalid", "RecordMismatch");
        expect(checkOwnConfigMessageDeletionNative(advancedLedger,
                102, noncanonical), "Invalid", "InvalidRecord");
        expect(checkOwnConfigMessageDeletionNative(advancedLedger,
                102, null), "Invalid", "NullInput");
        expect(checkOwnConfigMessageDeletionNative(advancedLedger,
                999, root.record), "Invalid", "NotFound");
        expect(recordConfirmedOwnConfigMessageNative(advancedLedger,
                103, root.record), "Invalid", "RecordMismatch");
        expect(recordConfirmedOwnConfigMessageNative(firstConfirmed.state,
                103, alteredRoot.record), "Valid", "None");
        expect(recordConfirmedOwnConfigMessageNative(firstConfirmed.state,
                0, root.record), "Invalid", "InvalidMessageId");
        expect(recordConfirmedOwnConfigMessageNative(firstConfirmed.state,
                103, noncanonical), "Invalid", "InvalidRecord");
        expect(removeAbsentOwnConfigMessageNative(advancedLedger,
                101, 0), "Invalid", "NotAbsent");
        expect(removeAbsentOwnConfigMessageNative(advancedLedger,
                101, 2), "Invalid", "InvalidPresenceKind");
        final RawReply removed = removeAbsentOwnConfigMessageNative(
                advancedLedger, 101, 1);
        expect(removed, "Valid", "None");
        if (!removed.metadataJson.contains("\"changed\":true")
                || new String(removed.state, StandardCharsets.UTF_8)
                        .contains("\"message_id\":101")) {
            throw new AssertionError(removed.metadataJson);
        }
        expect(removeAbsentOwnConfigMessageNative(removed.state,
                101, 1), "Invalid", "NotFound");
        final RawReply edited = recordConfirmedOwnConfigMessageNative(
                advancedLedger, 101, remote.record);
        expect(edited, "Valid", "None");
        expect(checkOwnConfigMessageDeletionNative(edited.state,
                101, remote.record), "Invalid", "NotOlder");
        expect(checkOwnConfigMessageDeletionNative(edited.state,
                102, root.record), "Valid", "None");
        System.out.println("account sync JNI smoke passed");
    }
}
