package org.telegram.messenger;
public final class MessagesStorage {
    private static final java.util.HashMap<Integer, MessagesStorage> instances = new java.util.HashMap<>();
    public static MessagesStorage getInstance(int slot) { return instances.get(slot); }
    public static void install(int slot, MessagesStorage storage) { instances.put(slot, storage); }
    private final org.telegram.SQLite.SQLiteDatabase database;
    private final DispatchQueue queue = new DispatchQueue();
    public MessagesStorage(org.telegram.SQLite.SQLiteDatabase database) { this.database = database; }
    public org.telegram.SQLite.SQLiteDatabase getDatabase() { return database; }
    public DispatchQueue getStorageQueue() { return queue; }
}
