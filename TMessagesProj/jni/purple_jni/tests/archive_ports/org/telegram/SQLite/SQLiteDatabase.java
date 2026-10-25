package org.telegram.SQLite;
public final class SQLiteDatabase {
    public interface Resolver { Object[][] rows(String sql, Object[] arguments); }
    private final Resolver resolver;
    public int reads;
    public SQLiteDatabase(Resolver resolver) { this.resolver = resolver; }
    public SQLiteCursor queryFinalized(String sql, Object... arguments) { ++reads; return new SQLiteCursor(resolver.rows(sql, arguments)); }
}
