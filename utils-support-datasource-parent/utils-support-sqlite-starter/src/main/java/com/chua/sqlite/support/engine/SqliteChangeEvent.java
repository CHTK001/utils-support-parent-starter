package com.chua.sqlite.support.engine;

/**
 * SQLite 变更事件，由 update_hook 触发。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class SqliteChangeEvent {

    public enum Type { INSERT, UPDATE, DELETE }

    private final Type type;
    private final String table;
    private final long rowId;

    private SqliteChangeEvent(Type type, String table, long rowId) {
        this.type = type;
        this.table = table;
        this.rowId = rowId;
    }

    public static SqliteChangeEvent insert(String table, long rowId) {
        return new SqliteChangeEvent(Type.INSERT, table, rowId);
    }

    public static SqliteChangeEvent update(String table, long rowId) {
        return new SqliteChangeEvent(Type.UPDATE, table, rowId);
    }

    public static SqliteChangeEvent delete(String table, long rowId) {
        return new SqliteChangeEvent(Type.DELETE, table, rowId);
    }

    public Type getType() { return type; }
    public String getTable() { return table; }
    public long getRowId() { return rowId; }

    @Override
    public String toString() {
        return "SqliteChangeEvent{type=" + type + ", table='" + table + "', rowId=" + rowId + "}";
    }
}
