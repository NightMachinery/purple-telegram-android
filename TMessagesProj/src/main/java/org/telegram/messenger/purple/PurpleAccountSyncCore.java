/*
 * This is the source code of Purple Telegram for Android.
 *
 * Licensed under the GNU General Public License, version 2 or (at your
 * option) any later version.
 */
package org.telegram.messenger.purple;

import org.json.JSONException;
import org.json.JSONObject;
import java.security.SecureRandom;

public final class PurpleAccountSyncCore {
    public static final int OBSERVATION_UNRESOLVED = 0;
    public static final int OBSERVATION_ABSENT = 1;
    public static final int OBSERVATION_PRESENT = 2;
    public static final int PRESENCE_PRESENT = 0;
    public static final int PRESENCE_ABSENT = 1;
    private static final SecureRandom SPACE_ID_RANDOM = new SecureRandom();

    private PurpleAccountSyncCore() {
    }

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

    public static final class Result {
        public final String status;
        public final String error;
        public final String envelopeStatus;
        public final String envelopeError;
        public final String payloadStatus;
        public final String payloadError;
        public final String stateError;
        public final String verdict;
        public final String space;
        public final String stream;
        public final String install;
        public final String device;
        public final String platform;
        public final String app;
        public final String key;
        public final String id;
        public final int comparison;
        public final String payloadHash;
        public final long seq;
        public final long at;
        public final long pendingSeq;
        public final long confirmedSeq;
        public final long writerWarnings;
        public final long localWarnings;
        public final boolean changed;
        public final byte[] record;
        public final byte[] state;
        public final byte[] configText;

        private Result(RawReply raw) throws JSONException {
            final JSONObject metadata = new JSONObject(raw.metadataJson);
            status = metadata.getString("status");
            error = metadata.optString("error", "None");
            envelopeStatus = metadata.optString("envelopeStatus", "");
            envelopeError = metadata.optString("envelopeError", "");
            payloadStatus = metadata.optString("payloadStatus", "");
            payloadError = metadata.optString("payloadError", "");
            stateError = metadata.optString("stateError", "");
            verdict = metadata.optString("verdict", "");
            space = metadata.optString("space", "");
            stream = metadata.optString("stream", "");
            install = metadata.optString("install", "");
            device = metadata.optString("device", "");
            platform = metadata.optString("platform", "");
            app = metadata.optString("app", "");
            key = metadata.optString("key", "");
            id = metadata.optString("id", "");
            comparison = metadata.optInt("comparison", 0);
            payloadHash = metadata.optString("payloadHash", "");
            seq = decimal(metadata, "seq");
            at = decimal(metadata, "at");
            pendingSeq = decimal(metadata, "pendingSeq");
            confirmedSeq = decimal(metadata, "confirmedSeq");
            writerWarnings = decimal(metadata, "writerWarnings");
            localWarnings = decimal(metadata, "localWarnings");
            changed = metadata.optBoolean("changed", false);
            record = raw.record;
            state = raw.state;
            configText = raw.configText;
        }

        private Result(String bridgeError) {
            status = "BridgeError";
            error = bridgeError;
            envelopeStatus = "";
            envelopeError = "";
            payloadStatus = "";
            payloadError = "";
            stateError = "";
            verdict = "";
            space = "";
            stream = "";
            install = "";
            device = "";
            platform = "";
            app = "";
            key = "";
            id = "";
            comparison = 0;
            payloadHash = "";
            seq = 0;
            at = 0;
            pendingSeq = 0;
            confirmedSeq = 0;
            writerWarnings = 0;
            localWarnings = 0;
            changed = false;
            record = null;
            state = null;
            configText = null;
        }

        private static long decimal(JSONObject metadata, String name)
                throws JSONException {
            if (!metadata.has(name)) {
                return 0;
            }
            final Object value = metadata.get(name);
            if (!(value instanceof String)) {
                throw new JSONException(name + " must be a decimal string");
            }
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                throw new JSONException(name + " is not a safe integer");
            }
        }

        public boolean isValid() {
            return "Valid".equals(status);
        }
    }

    private static Result result(RawReply raw) {
        if (raw == null) {
            return new Result("NativeUnavailable");
        }
        try {
            return new Result(raw);
        } catch (JSONException | IllegalArgumentException e) {
            return new Result("MalformedNativeReply");
        }
    }

    private static native RawReply inspectConfigRecordNative(byte[] record);
    private static native RawReply inspectStateNative(byte[] state);
    private static native RawReply buildConfigRecordNative(byte[] text,
            byte[][] parentRecords, String space, String install,
            String device, String platform, String app, long seq, long at);
    private static native RawReply buildConfigAcknowledgementNative(byte[] text,
            byte[] remoteRecord, String space, String install,
            String device, String platform, String app, long seq, long at);
    private static native RawReply reserveConfigSeqNative(
            byte[] state, String payloadHash);
    private static native RawReply initializeLocalStateNative(
            String installId, String createdDeviceId, String spaceId);
    private static native RawReply reserveConfigRecordNative(
            byte[] state, byte[] canonicalOwnRecord);
    private static native RawReply appendIssuedConfigRecordNative(
            byte[] state, byte[] canonicalOwnRecord);
    private static native RawReply adoptIssuedOwnConfigMessageNative(
            byte[] state, int messageId, byte[] canonicalServerRecord);
    private static native RawReply checkOwnRecordNative(byte[] state,
            String currentDevice, int observationKind, byte[] observedRecord);
    private static native RawReply confirmConfigReadBackNative(byte[] state,
            byte[] stagedRecord, String currentDevice, int observationKind,
            byte[] observedRecord, int messageId);
    private static native RawReply recordConfirmedOwnConfigMessageNative(
            byte[] state, int messageId, byte[] canonicalServerRecord);
    private static native RawReply checkOwnConfigMessageDeletionNative(
            byte[] state, int messageId, byte[] freshCanonicalServerRecord);
    private static native RawReply removeAbsentOwnConfigMessageNative(
            byte[] state, int messageId, int presenceKind);
    private static native RawReply formatInstallIdNative(byte[] entropy16);
    private static native RawReply formatSpaceIdNative(byte[] entropy16);
    private static native RawReply formatTimeOrderedSpaceIdNative(
            long serverMillis, byte[] randomTail10);
    private static native RawReply compareSpaceIdsNative(String a, String b);

    public static Result inspectConfigRecord(byte[] record) {
        try {
            PurpleCore.ensureLoaded();
            return result(inspectConfigRecordNative(record));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result inspectState(byte[] state) {
        try {
            PurpleCore.ensureLoaded();
            return result(inspectStateNative(state));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result buildConfigRecord(byte[] text,
            byte[][] parentRecords, String space, String install,
            String device, String platform, String app, long seq, long at) {
        try {
            PurpleCore.ensureLoaded();
            return result(buildConfigRecordNative(text, parentRecords,
                    space, install, device, platform, app, seq, at));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result buildConfigAcknowledgement(byte[] text,
            byte[] remoteRecord, String space, String install,
            String device, String platform, String app, long seq, long at) {
        try {
            PurpleCore.ensureLoaded();
            return result(buildConfigAcknowledgementNative(text, remoteRecord,
                    space, install, device, platform, app, seq, at));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result reserveConfigSeq(byte[] state, String payloadHash) {
        try {
            PurpleCore.ensureLoaded();
            return result(reserveConfigSeqNative(state, payloadHash));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result initializeLocalState(String installId,
            String createdDeviceId, String spaceId) {
        try {
            PurpleCore.ensureLoaded();
            return result(initializeLocalStateNative(
                    installId, createdDeviceId, spaceId));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result reserveConfigRecord(byte[] state,
            byte[] canonicalOwnRecord) {
        try {
            PurpleCore.ensureLoaded();
            return result(reserveConfigRecordNative(
                    state, canonicalOwnRecord));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result appendIssuedConfigRecord(byte[] state,
            byte[] canonicalOwnRecord) {
        try {
            PurpleCore.ensureLoaded();
            return result(appendIssuedConfigRecordNative(
                    state, canonicalOwnRecord));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result adoptIssuedOwnConfigMessage(byte[] state,
            int messageId, byte[] canonicalServerRecord) {
        try {
            PurpleCore.ensureLoaded();
            return result(adoptIssuedOwnConfigMessageNative(
                    state, messageId, canonicalServerRecord));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result checkOwnRecord(byte[] state, String currentDevice,
            int observationKind, byte[] observedRecord) {
        try {
            PurpleCore.ensureLoaded();
            return result(checkOwnRecordNative(
                    state, currentDevice, observationKind, observedRecord));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result confirmConfigReadBack(byte[] state,
            byte[] stagedRecord, String currentDevice, int observationKind,
            byte[] observedRecord, int messageId) {
        try {
            PurpleCore.ensureLoaded();
            return result(confirmConfigReadBackNative(state, stagedRecord,
                    currentDevice, observationKind, observedRecord, messageId));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result recordConfirmedOwnConfigMessage(byte[] state,
            int messageId, byte[] canonicalServerRecord) {
        try {
            PurpleCore.ensureLoaded();
            return result(recordConfirmedOwnConfigMessageNative(
                    state, messageId, canonicalServerRecord));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result checkOwnConfigMessageDeletion(byte[] state,
            int messageId, byte[] freshCanonicalServerRecord) {
        try {
            PurpleCore.ensureLoaded();
            return result(checkOwnConfigMessageDeletionNative(
                    state, messageId, freshCanonicalServerRecord));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result removeAbsentOwnConfigMessage(byte[] state,
            int messageId, int presenceKind) {
        try {
            PurpleCore.ensureLoaded();
            return result(removeAbsentOwnConfigMessageNative(
                    state, messageId, presenceKind));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result formatInstallId(byte[] entropy16) {
        try {
            PurpleCore.ensureLoaded();
            return result(formatInstallIdNative(entropy16));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result formatSpaceId(byte[] entropy16) {
        try {
            PurpleCore.ensureLoaded();
            return result(formatSpaceIdNative(entropy16));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result formatTimeOrderedSpaceId(long serverMillis) {
        final byte[] randomTail = new byte[10];
        SPACE_ID_RANDOM.nextBytes(randomTail);
        try {
            PurpleCore.ensureLoaded();
            return result(formatTimeOrderedSpaceIdNative(
                    serverMillis, randomTail));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }

    public static Result compareSpaceIds(String a, String b) {
        try {
            PurpleCore.ensureLoaded();
            return result(compareSpaceIdsNative(a, b));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return new Result("NativeUnavailable");
        }
    }
}
