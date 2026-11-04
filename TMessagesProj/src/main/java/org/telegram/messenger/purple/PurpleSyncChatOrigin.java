package org.telegram.messenger.purple;

import java.util.Objects;
import java.nio.charset.StandardCharsets;

public final class PurpleSyncChatOrigin {
    public final int slot, environment;
    public final long userId;
    public final String login;
    public PurpleSyncChatOrigin(int slot, int environment, long userId, String login) {
        if (slot < 0 || environment < 0 || environment > 1 || userId <= 0 || login == null || login.isEmpty()) throw new IllegalArgumentException("sync chat origin");
        this.slot = slot; this.environment = environment; this.userId = userId; this.login = login;
    }
    public String accountKey() { return environment + "-" + userId; }
    @Override public boolean equals(Object value) {
        if (!(value instanceof PurpleSyncChatOrigin)) return false; PurpleSyncChatOrigin other = (PurpleSyncChatOrigin) value;
        return slot == other.slot && environment == other.environment && userId == other.userId && login.equals(other.login);
    }
    static String authority(String loginAnchor) {
        if (loginAnchor == null || !loginAnchor.matches("[0-9a-f]{32}")) return null;
        return hash(("purple-sync-chat-login-v1:"+loginAnchor).getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(byte[] bytes) {
        try { byte[] value = java.security.MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder hex = new StringBuilder(); for(byte b:value)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return hex.toString(); }
        catch (Exception error) { throw new IllegalStateException("sync chat hash"); }
    }
    @Override public int hashCode() { return Objects.hash(slot, environment, userId, login); }
}
