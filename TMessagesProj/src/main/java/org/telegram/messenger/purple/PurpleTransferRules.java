package org.telegram.messenger.purple;

final class PurpleTransferRules {
    static boolean fileName(String name) {
        return "settings.toml".equalsIgnoreCase(name) || "account-settings.toml".equalsIgnoreCase(name);
    }
    static String exportName(int version) { return version == 2 ? "account-settings.toml" : "settings.toml"; }
    static boolean receiptAllowed(String owner, String key, boolean legacyStaged, boolean ready) {
        return key != null && (owner == null || owner.equals(key) || legacyStaged && ready);
    }
    private PurpleTransferRules() { }
}
