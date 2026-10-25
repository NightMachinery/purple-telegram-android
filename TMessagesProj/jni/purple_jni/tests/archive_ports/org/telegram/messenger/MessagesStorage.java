package org.telegram.messenger;
public final class MessagesStorage {
    private final org.telegram.SQLite.SQLiteDatabase database;
    private final DispatchQueue queue = new DispatchQueue();
    public MessagesStorage(org.telegram.SQLite.SQLiteDatabase database) { this.database = database; }
    public org.telegram.SQLite.SQLiteDatabase getDatabase() { return database; }
    public DispatchQueue getStorageQueue() { return queue; }
}
