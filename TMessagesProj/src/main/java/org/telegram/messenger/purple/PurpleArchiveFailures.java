package org.telegram.messenger.purple;

final class PurpleArchiveFailures {
    static String reason(Throwable error) {
        String value = error instanceof PurpleArchiveStore.Failure ? ((PurpleArchiveStore.Failure) error).reason : "";
        if (value.contains("SchemaMismatch")) return "archive_schema_mismatch";
        if (value.contains("schema")) return "archive_schema_unsupported";
        if (value.startsWith("Archive login persistence")) return "archive_login_persist_failed";
        if (value.startsWith("Archive login nonce")) return "archive_login_nonce_missing";
        if (value.startsWith("Archive login")) return "archive_login_admission_failed";
        if (value.startsWith("Archive catalogue")) return "archive_catalogue_ambiguous";
        if (value.startsWith("Archive interrupted") || value.startsWith("Archive prior capture")) return "archive_capture_recovery_failed";
        if (value.contains("key") || error instanceof java.security.GeneralSecurityException) return "archive_key_failed";
        if (value.contains("removal")) return "archive_removal_failed";
        if (error instanceof LinkageError) return "native_unavailable";
        if (error instanceof org.json.JSONException) return "archive_metadata_invalid";
        if (error instanceof java.io.IOException) return "archive_storage_failed";
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            String message = error.getMessage();
            if (message != null && (message.startsWith("archive metadata JSON") || message.startsWith("archive metadata UTF8")
                || message.startsWith("archive metadata size") || message.startsWith("archive binding") || message.startsWith("archive generation"))) return "archive_metadata_invalid";
        }
        if (error instanceof IllegalStateException) {
            String message = error.getMessage();
            if (message != null && (message.startsWith("archive directory") || message.startsWith("archive metadata")
                || message.startsWith("archive publication") || message.startsWith("archive listing") || message.startsWith("archive removal"))) return "archive_storage_failed";
            if (message != null && (message.startsWith("archive account busy") || message.startsWith("archive process lock"))) return "archive_lock_failed";
            if (message != null && message.startsWith("archive key")) return "archive_key_failed";
            if (message != null && (message.startsWith("archive connection") || message.startsWith("archive sqlite"))) return "archive_database_failed";
        }
        if (value.startsWith("Archive query") || value.startsWith("Archive connection") || value.startsWith("Archive WAL")
            || value.startsWith("Archive metadata") || value.startsWith("Archive header")) return "archive_database_failed";
        return "archive_open_failed";
    }
    static String state(Throwable error) {
        switch (reason(error)) {
            case "archive_schema_mismatch": return "SchemaMismatch";
            case "archive_schema_unsupported": return "ReadOnly";
            case "archive_login_persist_failed": case "archive_login_nonce_missing": return "LoginUnavailable";
            case "archive_login_admission_failed": return "LoginRecordUnavailable";
            case "archive_key_failed": return "KeyUnavailable";
            case "archive_catalogue_ambiguous": return "CatalogueUnavailable";
            case "archive_capture_recovery_failed": return "CaptureRecoveryUnavailable";
            case "archive_storage_failed": case "archive_lock_failed": return "StorageUnavailable";
            case "archive_database_failed": return "DatabaseUnavailable";
            case "archive_metadata_invalid": return "MetadataUnavailable";
            case "archive_removal_failed": return "Removal pending";
            case "native_unavailable": return "NativeUnavailable";
            default: return "Unavailable";
        }
    }
    static void record(int slot, String step, Throwable error) {
        if (slot >= 0) PurpleDiagLog.event("archive", "open_failed", PurpleDiagLog.slot(slot), PurpleDiagLog.code("step", step), PurpleDiagLog.reason(reason(error)));
        else PurpleDiagLog.event("archive", "recovery_failed", PurpleDiagLog.code("step", step), PurpleDiagLog.reason(reason(error)));
    }
    private PurpleArchiveFailures() {}
}
