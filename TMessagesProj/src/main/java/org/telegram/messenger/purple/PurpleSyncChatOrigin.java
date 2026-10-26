package org.telegram.messenger.purple;

import java.util.Objects;

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
    @Override public int hashCode() { return Objects.hash(slot, environment, userId, login); }
}
